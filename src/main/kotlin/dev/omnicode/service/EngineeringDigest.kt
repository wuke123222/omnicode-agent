package dev.omnicode.service

import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

data class GitCommitSummary(
    val hash: String,
    val date: String,
    val subject: String,
)

data class GitVersionDelta(
    val fromRef: String?,
    val toRef: String,
    val stat: String,
)

data class GitProgressSnapshot(
    val branch: String?,
    val commits: List<GitCommitSummary>,
    val versionDeltas: List<GitVersionDelta>,
    val workingTree: String?,
    val warnings: List<String>,
)

/**
 * Collects read-only Git evidence for an explicit user-triggered report export. It never invokes
 * a shell, follows repository Harness commands, sends data over the network, or writes to the
 * repository. Every argv and output buffer is bounded and uses the existing Git process guard.
 */
internal class GitProgressCollector(
    private val gitExecutableResolver: () -> Path? = ::resolveGitExecutable,
    private val processExecutor: GitProcessExecutor = DirectGitProcessExecutor,
) {
    suspend fun collect(projectRoot: Path, periodDays: Long = 7, maxVersions: Int = 6): GitProgressSnapshot {
        val root = runCatching { projectRoot.toRealPath() }.getOrElse {
            return GitProgressSnapshot(null, emptyList(), emptyList(), null, listOf("项目目录无法解析。"))
        }
        if (!Files.isDirectory(root)) {
            return GitProgressSnapshot(null, emptyList(), emptyList(), null, listOf("项目目录不是文件夹。"))
        }
        val executable = gitExecutableResolver()?.let { candidate ->
            runCatching { candidate.toRealPath() }.getOrNull()?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
        } ?: return GitProgressSnapshot(null, emptyList(), emptyList(), null, listOf("未找到 Git，可在连接诊断中检查。"))

        val warnings = mutableListOf<String>()
        val branch = read(executable, root, listOf("rev-parse", "--abbrev-ref", "HEAD"), warnings)
            ?.trim()?.takeIf { it.isNotBlank() }
        val commits = read(executable, root, listOf(
            "--no-pager", "log", "--no-merges", "--since=${periodDays.coerceIn(1, 90)} days ago",
            "-n", MAX_COMMITS.toString(), "--date=short", "--pretty=format:%h%x09%ad%x09%s", "--",
        ), warnings).orEmpty().lineSequence().mapNotNull(::parseCommit).toList()
        val workingTree = read(executable, root, listOf("--no-pager", "status", "--short", "--branch"), warnings)
            ?.take(MAX_STATUS_CHARS)?.trim()?.takeIf { it.isNotBlank() }
        val refs = read(executable, root, listOf(
            "--no-pager", "for-each-ref", "--sort=-creatordate", "--format=%(refname:short)%x09%(creatordate:short)", "refs/tags",
        ), warnings).orEmpty().lineSequence().mapNotNull(::parseTag).take(MAX_TAGS).toList()

        val deltas = mutableListOf<GitVersionDelta>()
        refs.zipWithNext().take(maxVersions.coerceIn(1, 12) - 1).forEach { (newer, older) ->
            val stat = diffStat(executable, root, older.first, newer.first, warnings)
            deltas += GitVersionDelta(fromRef = older.first, toRef = newer.first, stat = stat)
        }
        refs.firstOrNull()?.let { latest ->
            deltas += GitVersionDelta(
                fromRef = latest.first,
                toRef = "HEAD",
                stat = diffStat(executable, root, latest.first, "HEAD", warnings),
            )
        }
        return GitProgressSnapshot(branch, commits, deltas, workingTree, warnings.distinct().take(MAX_WARNINGS))
    }

    private suspend fun diffStat(
        executable: Path,
        root: Path,
        fromRef: String,
        toRef: String,
        warnings: MutableList<String>,
    ): String {
        if (!SAFE_REF.matches(fromRef) || !SAFE_REF.matches(toRef)) return "版本引用不符合安全格式，已跳过。"
        return read(
            executable,
            root,
            listOf("--no-pager", "diff", "--stat", "--no-renames", "--no-ext-diff", fromRef, toRef, "--"),
            warnings,
            MAX_DIFF_CHARS,
        )?.trim().orEmpty().ifBlank { "无文件差异或版本不可比较。" }
    }

    private suspend fun read(
        executable: Path,
        root: Path,
        args: List<String>,
        warnings: MutableList<String>,
        maxChars: Int = MAX_OUTPUT_CHARS,
    ): String? {
        val result = try {
            processExecutor.execute(
                GitProcessSpec(
                    argv = listOf(executable.toString()) + args,
                    workingDirectory = root,
                    environment = cleanGitEnvironment(),
                    timeoutMillis = GIT_TIMEOUT.toMillis(),
                    maxStdoutChars = maxChars,
                    maxStderrChars = MAX_ERROR_CHARS,
                ),
            )
        } catch (error: Throwable) {
            warnings += "Git 读取失败：${error.message.orEmpty().take(180)}"
            return null
        }
        if (result.timedOut) {
            warnings += "Git 读取超时，已跳过一项证据。"
            return null
        }
        if (result.exitCode != 0) {
            val detail = result.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.take(180)
            warnings += "Git 返回错误${detail?.let { "：$it" }.orEmpty()}"
            return null
        }
        if (result.stdoutTruncated) warnings += "Git 输出超过上限，已截断。"
        return result.stdout
    }

    private fun parseCommit(line: String): GitCommitSummary? {
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) return null
        return GitCommitSummary(parts[0].take(20), parts[1].take(20), parts[2].trim().take(MAX_SUBJECT_CHARS))
    }

    private fun parseTag(line: String): Pair<String, String>? {
        val parts = line.split('\t', limit = 2)
        val name = parts.firstOrNull()?.trim().orEmpty()
        if (!SAFE_REF.matches(name)) return null
        return name to parts.getOrNull(1).orEmpty().take(20)
    }

    private companion object {
        const val MAX_COMMITS = 80
        const val MAX_TAGS = 12
        const val MAX_OUTPUT_CHARS = 64 * 1_024
        const val MAX_DIFF_CHARS = 48 * 1_024
        const val MAX_ERROR_CHARS = 4_000
        const val MAX_STATUS_CHARS = 12_000
        const val MAX_SUBJECT_CHARS = 320
        const val MAX_WARNINGS = 12
        val GIT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val SAFE_REF = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,200}")
    }
}

data class EngineeringDigestInput(
    val projectName: String,
    val periodStart: Instant,
    val generatedAt: Instant = Instant.now(),
    val git: GitProgressSnapshot,
    val tasks: List<UnifiedTaskEntry>,
)

object EngineeringDigestExporter {
    const val MAX_DIGEST_CHARS = 100_000
    private const val MAX_ITEMS = 80
    private const val MAX_STATUS_CHARS = 12_000
    private val redactor = DefaultSensitiveDataRedactor()

    fun markdown(input: EngineeringDigestInput): String {
        val periodEnd = input.generatedAt
        val completed = input.tasks.count { it.status == UnifiedTaskStatus.COMPLETED }
        val failed = input.tasks.count { it.status in setOf(UnifiedTaskStatus.FAILED, UnifiedTaskStatus.BUDGET_EXHAUSTED) }
        val report = buildString {
            appendLine("# OmniCode 工程进展周报")
            appendLine()
            appendLine("- 项目：`${cell(input.projectName, 180)}`")
            appendLine("- 周期：${input.periodStart} → $periodEnd")
            appendLine("- 生成方式：本地 Git 版本差异 + OmniCode 任务账本（未上传仓库）")
            appendLine()
            appendLine("## 一句话进展")
            appendLine()
            appendLine("本周期完成 ${completed} 个任务，Git 产生 ${input.git.commits.size} 个提交，${input.git.versionDeltas.size} 个版本区间可供复盘；失败/中断任务 ${failed} 个。")
            appendLine()
            appendLine("## 版本与 Diff")
            appendLine()
            if (input.git.versionDeltas.isEmpty()) appendLine("未发现可比较的 Git 标签；可先创建版本标签后重新导出。")
            input.git.versionDeltas.take(MAX_ITEMS).forEach { delta ->
                appendLine("### ${cell(delta.fromRef ?: "起点", 120)} → ${cell(delta.toRef, 120)}")
                appendLine("```")
                appendLine(cell(delta.stat, 24_000))
                appendLine("```")
            }
            appendLine()
            appendLine("## 本周期提交")
            appendLine()
            if (input.git.commits.isEmpty()) appendLine("本周期没有可读取的提交。")
            input.git.commits.take(MAX_ITEMS).forEach { commit ->
                appendLine("- `${cell(commit.hash, 20)}` ${cell(commit.date, 20)} · ${cell(commit.subject)}")
            }
            appendLine()
            appendLine("## OmniCode 任务")
            appendLine()
            if (input.tasks.isEmpty()) appendLine("本地任务账本没有可展示记录。")
            input.tasks.sortedByDescending(UnifiedTaskEntry::updatedAt).take(MAX_ITEMS).forEach { task ->
                appendLine(
                    "- **${cell(task.title, 260)}** · ${taskStatus(task.status)} · ${task.mode}/${task.strategy}" +
                        task.currentStage?.let { " · 阶段 ${cell(it, 120)}" }.orEmpty() +
                        " · 更新 ${task.updatedAt}",
                )
                if (task.toolFailureCount > 0 || task.retryCount > 0) {
                    appendLine("  - 工具失败 ${task.toolFailureCount} · 重试 ${task.retryCount} · 模型请求 ${task.modelRequestCount}")
                }
            }
            appendLine()
            appendLine("## 当前工作区")
            appendLine()
            appendLine("分支：`${cell(input.git.branch ?: "未知", 160)}`")
            appendLine("```")
            appendLine(cell(input.git.workingTree ?: "无法读取工作区状态。", MAX_STATUS_CHARS))
            appendLine("```")
            if (input.git.warnings.isNotEmpty()) {
                appendLine()
                appendLine("## 证据限制")
                input.git.warnings.forEach { appendLine("- ${cell(it)}") }
            }
            appendLine()
            appendLine("> 周报是本地证据摘要，不是完整仓库快照；提交标题、分支名和任务标题均经过有界脱敏。")
        }
        return report.take(MAX_DIGEST_CHARS)
    }

    private fun taskStatus(status: UnifiedTaskStatus): String = when (status) {
        UnifiedTaskStatus.COMPLETED -> "完成"
        UnifiedTaskStatus.RUNNING -> "运行中"
        UnifiedTaskStatus.FAILED -> "失败"
        UnifiedTaskStatus.BUDGET_EXHAUSTED -> "预算暂停"
        UnifiedTaskStatus.RECOVERABLE -> "待恢复"
        UnifiedTaskStatus.WAITING_FOR_APPROVAL -> "待审批"
        UnifiedTaskStatus.PAUSED -> "暂停"
        UnifiedTaskStatus.CANCELLED -> "已取消"
    }

    private fun cell(value: String, maxChars: Int = 1_000): String = redactor.redact(value)
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("`", "'")
        .trim()
        .take(maxChars)
}
