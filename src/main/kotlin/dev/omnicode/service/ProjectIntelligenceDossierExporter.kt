package dev.omnicode.service

import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import java.time.Instant

/**
 * A bounded, redacted project hand-off document. It deliberately contains metadata and
 * navigation hints rather than source text, prompts, credentials, or a repository snapshot.
 */
data class ProjectIntelligenceDossierInput(
    val projectName: String,
    val generatedAt: Instant = Instant.now(),
    val rules: ProjectRulesResult? = null,
    val pinned: PinnedProjectContext? = null,
    val harness: ProjectHarnessReport? = null,
)

object ProjectIntelligenceDossierExporter {
    const val MAX_DOSSIER_CHARS = 120_000
    private const val MAX_LIST_ITEMS = 80
    private const val MAX_FIELD_CHARS = 400
    private val redactor = DefaultSensitiveDataRedactor()

    fun markdown(input: ProjectIntelligenceDossierInput): String {
        val report = buildString {
            appendLine("# OmniCode 项目智能档案")
            appendLine()
            appendLine("- 项目：`${cell(input.projectName, 160)}`")
            appendLine("- 生成时间：${input.generatedAt}")
            appendLine("- 用途：团队交接、架构梳理和新成员快速建立上下文")
            appendLine()

            appendLine("## 项目就绪度")
            appendLine()
            input.harness?.let { report ->
                appendLine("- Harness：${report.readiness} · ${report.score}/100")
                appendLine("- 模型可用元数据：${if (report.safeForModel) "是" else "否（已失败关闭）"}")
                appendLine("- 配置：${report.configurationStatus}")
                appendLine("- 验证方式：${report.feedbackLoops.size} 个")
                if (report.feedbackLoops.isNotEmpty()) {
                    report.feedbackLoops.take(MAX_LIST_ITEMS).forEach { loop ->
                        val executable = loop.argv.firstOrNull().orEmpty()
                        appendLine(
                            "  - `${cell(loop.id, 100)}`：${cell(loop.label)} · " +
                                "入口 `${cell(executable, 160)}` · 参数 ${loop.argv.size.coerceAtLeast(1) - 1} 个",
                        )
                    }
                }
                if (report.issues.isNotEmpty()) {
                    appendLine()
                    appendLine("### 风险与建议")
                    report.issues.take(MAX_LIST_ITEMS).forEach { issue ->
                        appendLine("- ${issue.severity} · ${cell(issue.summary)}")
                        appendLine("  - 建议：${cell(issue.recoverySuggestion)}")
                    }
                }
            } ?: appendLine("尚未完成 Harness 检查。")

            appendLine()
            appendLine("## 项目规则")
            appendLine()
            input.rules?.let { rules ->
                appendLine("- 已应用：${rules.appliedRules.size} 个 · 问题：${rules.issues.size} 个")
                rules.appliedRules.take(MAX_LIST_ITEMS).forEach { rule ->
                    appendLine(
                        "- `${cell(rule.relativePath, 240)}` · ${rule.includedCharacters} 字符" +
                            if (rule.truncated) " · 已截断" else "",
                    )
                }
                rules.issues.take(MAX_LIST_ITEMS).forEach { issue ->
                    appendLine("- ⚠ `${cell(issue.relativePath, 240)}` · ${issue.reason} · ${cell(issue.detail)}")
                }
            } ?: appendLine("尚未加载项目规则。")

            appendLine()
            appendLine("## 上下文占用")
            appendLine()
            input.pinned?.let { pinned ->
                val occupancy = pinned.occupancy
                appendLine(
                    "- 固定文件：${pinned.files.size} 个 · ${occupancy.usedCharacters}/${occupancy.characterBudget} 字符" +
                        " · 约 ${occupancy.estimatedTokens} tokens · ${occupancy.percentUsed}%",
                )
                appendLine("- 截断文件：${pinned.truncatedFiles} · 省略字节：${pinned.omittedBytes}")
                pinned.files.take(MAX_LIST_ITEMS).forEach { file ->
                    appendLine(
                        "- `${cell(file.relativePath, 240)}` · ${file.includedBytes} bytes" +
                            if (file.truncated) " · 已截断" else "",
                    )
                }
                pinned.issues.take(MAX_LIST_ITEMS).forEach { issue ->
                    appendLine("- ⚠ `${cell(issue.relativePath, 240)}` · ${cell(issue.detail)}")
                }
            } ?: appendLine("尚未计算上下文占用。")

            appendLine()
            appendLine("## 边界")
            appendLine()
            appendLine("> 本档案只包含有界的项目元数据和导航摘要，不包含源代码全文、提示词、API Key、图片二进制或完整仓库快照。")
        }
        return report.take(MAX_DOSSIER_CHARS)
    }

    private fun cell(value: String, maxChars: Int = MAX_FIELD_CHARS): String = redactor.redact(value)
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("`", "'")
        .trim()
        .take(maxChars)
}
