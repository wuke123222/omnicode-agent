package dev.omnicode.provider

import com.intellij.openapi.application.PathManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Describes one CLI-based coding agent and how to invoke it.
 */
internal enum class CliTool(
    val executableNames: List<String>,
    val buildArgs: (prompt: String, model: String?) -> List<String>,
    val supportsJsonOutput: Boolean,
    val supportsStreamJson: Boolean,
) {
    CLAUDE(
        executableNames = listOf("claude"),
        buildArgs = { prompt, model ->
            buildList {
                add("-p"); add(prompt)
                add("--output-format"); add("text")
                // Native CLI mutations cannot bypass OmniCode's approval gate. Plan mode keeps
                // this compatibility adapter read-only until the SDK permission bridge is used.
                add("--permission-mode"); add("plan")
                if (!model.isNullOrBlank() && model != "default") { add("--model"); add(model) }
            }
        },
        supportsJsonOutput = false,
        supportsStreamJson = false,
    ),
    CODEX(
        executableNames = listOf("codex"),
        buildArgs = { prompt, model ->
            buildList {
                add("exec")
                add("--color"); add("never")
                add("--sandbox"); add("read-only")
                add("--skip-git-repo-check")
                if (!model.isNullOrBlank() && model != "default") { add("--model"); add(model) }
                add(prompt)
            }
        },
        supportsJsonOutput = false,
        supportsStreamJson = false,
    ),
    OPENCODE(
        executableNames = listOf("opencode"),
        buildArgs = { prompt, model ->
            buildList {
                add("run")
                add("--format"); add("json")
                // Diagnostics remain on bounded stderr and are reduced to coarse phase labels;
                // they are never persisted or sent to a model. Without this, OpenCode 1.18.x
                // can stay silent while the upstream free model queues, making local startup
                // indistinguishable from a model-side wait.
                add("--print-logs")
                add("--log-level"); add("INFO")
                if (!model.isNullOrBlank() && model != "default") {
                    add("--model"); add(model)
                }
                // Do not force title or agent flags: those can override the user's native CLI
                // configuration. The prompt remains the final positional argument.
                add(prompt)
            }
        },
        supportsJsonOutput = true,
        supportsStreamJson = true,
    ),
    KIMI(
        executableNames = listOf("kimi"),
        buildArgs = { prompt, model ->
            buildList {
                add("-p"); add(prompt)
                if (!model.isNullOrBlank() && model != "default" && model != "kimi-k2") {
                    add("-m"); add(model)
                }
            }
        },
        supportsJsonOutput = false,
        supportsStreamJson = false,
    ),
    GROK(
        executableNames = listOf("grok"),
        buildArgs = { prompt, model ->
            buildList {
                add("-p"); add(prompt)
                if (!model.isNullOrBlank() && model != "default" && model != "grok-build-0.1") {
                    add("-m"); add(model)
                }
            }
        },
        supportsJsonOutput = false,
        supportsStreamJson = false,
    ),
    PI(
        executableNames = listOf("pi"),
        buildArgs = { prompt, model ->
            buildList {
                add("-p"); add(prompt)
                if (!model.isNullOrBlank() && model != "default") {
                    add("--model"); add(model)
                }
                // OmniCode cannot safely proxy Pi's interactive approval prompts. The CLI
                // adapter is therefore read-only; users can still use Pi's own terminal UI for
                // file-changing sessions.
                add("--no-tools")
            }
        },
        supportsJsonOutput = false,
        supportsStreamJson = false,
    ),
    OMP(
        executableNames = listOf("omp"),
        buildArgs = { prompt, model ->
            buildList {
                add("--print"); add("--mode"); add("json")
                if (!model.isNullOrBlank() && model !in setOf("default", "auto")) { add("--model"); add(model) }
                add(prompt)
            }
        },
        supportsJsonOutput = true,
        supportsStreamJson = true,
    ),
    DSH(
        executableNames = listOf("dsh"),
        buildArgs = { _, _ -> emptyList() },
        supportsJsonOutput = true,
        supportsStreamJson = true,
    ),
    /** Legacy adapter retained only so old settings can migrate without crashing. */
    QODER(
        executableNames = listOf("qodercli", "qoder"),
        buildArgs = { prompt, model ->
            buildList {
                add("--print")
                add("-p"); add(prompt)
                if (!model.isNullOrBlank() && model != "default") {
                    add("--model"); add(model)
                }
            }
        },
        // Qoder's current non-interactive flag is --output-format, not --format. Plain text is
        // deliberately used until its JSON event schema is stable across releases.
        supportsJsonOutput = false,
        supportsStreamJson = false,
    ),
}

/**
 * Discovers the CLI executable path for a given [CliTool].
 * Checks: explicit setting in connection.baseUrl, the IDE process PATH, and common per-user
 * package-manager locations. IntelliJ launched from Finder often does not inherit the shell PATH.
 */
internal object CliToolDiscovery {
    fun resolveExecutable(tool: CliTool, explicitPath: String?): File? {
        if (!explicitPath.isNullOrBlank()) {
            val file = File(explicitPath)
            if (file.isFile && file.canExecute()) return file
        }
        for (name in tool.executableNames) {
            val found = findInPath(name)
            if (found != null) return found
        }
        return null
    }

    /**
     * Makes a child process see the same practical runtime locations used by discovery.
     *
     * An npm-installed CLI is often a `#!/usr/bin/env node` launcher. A Finder- or
     * Toolbox-launched IDE can find that launcher while still not exposing Node to it.
     */
    fun applyRuntimePath(environment: MutableMap<String, String>, executable: File? = null) {
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        environment[pathKey] = runtimePath(environment[pathKey], executable)
    }

    /**
     * Resolves the exact command line to launch. npm CLIs on macOS/Linux are frequently tiny
     * `#!/usr/bin/env node` scripts. Starting the discovered Node binary directly avoids the
     * split-PATH problem of GUI-launched IDEs without changing the user's shell configuration.
     */
    fun launchCommand(executable: File): List<String> {
        val node = resolveNodeRuntime()
        if (isWindows() && executable.extension.lowercase() in setOf("cmd", "bat")) {
            val target = windowsNpmNodeLauncherTarget(executable)
                ?: throw ProviderException(
                    "${executable.name} 是 Windows 命令脚本，但无法安全解析实际 Node 入口。" +
                        "请升级或重新安装该 CLI；OmniCode 不会把任务内容拼进 cmd.exe。",
                    retryableOverride = false,
                )
            val runtime = node ?: throw ProviderException(
                "已找到 ${executable.name}，但找不到 Node.js。请安装 Node.js 并在依赖页重新检测。",
                retryableOverride = false,
            )
            return listOf(runtime.absolutePath, target.absolutePath)
        }
        return if (!isWindows() && isNodeLauncher(executable) && node != null) {
            listOf(node.absolutePath, executable.absolutePath)
        } else listOf(executable.absolutePath)
    }

    /** Visible for focused tests; preserves ordering and removes empty/duplicate PATH entries. */
    internal fun runtimePath(existingPath: String?, executable: File? = null): String {
        val directories = linkedSetOf<String>().apply {
            existingPath.orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank).forEach(::add)
            executable?.parentFile?.path?.takeIf(String::isNotBlank)?.let(::add)
            addAll(commonRuntimeDirectories())
        }
        return directories.joinToString(File.pathSeparator)
    }

    private fun findInPath(name: String): File? =
        findInRuntimePath(name, runtimePath(System.getenv("PATH")))

    private fun resolveNodeRuntime(): File? =
        findInRuntimePath("node", runtimePath(System.getenv("PATH")))

    private fun findInRuntimePath(name: String, path: String): File? {
        // Windows cannot directly CreateProcess an extensionless npm shell shim. Prefer native
        // executables and cmd/bat launchers, matching PATHEXT resolution in an interactive shell.
        val names = if (isWindows()) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
        val directories = path
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)

        for (directory in directories) {
            for (candidateName in names) {
                File(directory, candidateName).takeIf { it.isFile && it.canExecute() }?.let { return it }
            }
        }
        return null
    }

    private fun commonRuntimeDirectories(): Set<String> = linkedSetOf<String>().apply {
        val home = System.getProperty("user.home").orEmpty()
        if (home.isNotBlank()) {
            addAll(listOf(
                "$home/.local/bin", "$home/.npm-global/bin", "$home/.npm/bin", "$home/bin",
                "$home/.volta/bin", "$home/.asdf/shims", "$home/.mise/shims", "$home/.local/share/mise/shims",
            ))
            addVersionedNodeBins(File(home, ".nvm/versions/node")) { version -> File(version, "bin") }
            addVersionedNodeBins(File(home, ".local/share/fnm/node-versions")) { version ->
                File(version, "installation/bin")
            }
        }
        if (isWindows()) {
            System.getenv("APPDATA")?.takeIf(String::isNotBlank)?.let { add("$it\\npm") }
            System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let {
                add("$it\\Programs\\nodejs")
                add("$it\\npm")
            }
            System.getenv("ProgramFiles")?.takeIf(String::isNotBlank)?.let { add("$it\\nodejs") }
            System.getenv("ProgramW6432")?.takeIf(String::isNotBlank)?.let { add("$it\\nodejs") }
        } else {
            addAll(listOf("/usr/local/bin", "/opt/homebrew/bin", "/opt/local/bin", "/usr/bin"))
        }
    }

    private fun isNodeLauncher(executable: File): Boolean = runCatching {
        executable.inputStream().use { input ->
            // Do not make a native CLI pay for a full binary scan just to decide whether it is
            // an npm wrapper. A POSIX shebang must be at the beginning of the file.
            val header = input.readNBytes(NODE_LAUNCHER_HEADER_BYTES).decodeToString()
            val firstLine = header.substringBefore('\n')
            firstLine.startsWith("#!") && Regex("\\bnode(?:\\s|$)").containsMatchIn(firstLine)
        }
    }.getOrDefault(false)

    /**
     * npm-generated Windows launchers point at a script below the launcher's own node_modules.
     * Resolve that fixed path instead of invoking cmd.exe with user-controlled prompt text.
     */
    internal fun windowsNpmNodeLauncherTarget(executable: File): File? = runCatching {
        if (executable.extension.lowercase() !in setOf("cmd", "bat") || executable.length() > MAX_WINDOWS_SHIM_BYTES) {
            return@runCatching null
        }
        val parent = executable.parentFile?.canonicalFile ?: return@runCatching null
        val text = executable.inputStream().use { input ->
            input.readNBytes(MAX_WINDOWS_SHIM_BYTES.toInt()).toString(StandardCharsets.UTF_8)
        }
        WINDOWS_NPM_TARGET.findAll(text).mapNotNull { match ->
            val relative = match.groupValues[1].trim().replace('\\', File.separatorChar).replace('/', File.separatorChar)
            if (!relative.startsWith("node_modules${File.separator}")) return@mapNotNull null
            val candidate = File(parent, relative).canonicalFile
            candidate.takeIf { it.toPath().startsWith(parent.toPath()) && it.isFile }
        }.lastOrNull()
    }.getOrNull()

    private fun MutableSet<String>.addVersionedNodeBins(
        root: File,
        binForVersion: (File) -> File,
    ) {
        runCatching {
            root.listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.sortedByDescending(File::lastModified)
                ?.take(6)
                ?.map(binForVersion)
                ?.filter(File::isDirectory)
                ?.forEach { add(it.path) }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")

    private const val NODE_LAUNCHER_HEADER_BYTES = 512
    private const val MAX_WINDOWS_SHIM_BYTES = 64 * 1_024L
    private val WINDOWS_NPM_TARGET = Regex("""(?i)%dp0%[\\/]([^\"\r\n]+)""")
}

/**
 * Provider adapter that wraps a local CLI coding agent.
 *
 * The CLI is launched as a subprocess per request. The prompt is passed as a command-line
 * argument. Output is parsed from JSON (when supported) or plain text.
 */
internal class CliToolProvider(
    private val connection: ProviderConnection,
    private val cliTool: CliTool,
    private val workingDirectory: Path? = null,
    private val localSession: LocalCliSessionContext? = null,
    private val agentMode: AgentMode = AgentMode.AGENT,
) : ModelProvider {
    override val id: String = connection.preset.id

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse = complete(request, onTextDelta) {}

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit,
    ): ModelResponse = withContext(Dispatchers.IO) {
        val waitPolicy = cliOutputWaitPolicy(connection.requestTimeoutSeconds)
        var lastOpenCodeDiagnostic: String? = null
        val trackedProgress: suspend (String) -> Unit = { detail ->
            if (detail.startsWith("OpenCode ") && !detail.startsWith("OpenCode 已连接任务模型")) {
                lastOpenCodeDiagnostic = detail
            }
            onProgress(detail)
        }
        try {
            withTimeout(waitPolicy.totalTimeoutSeconds * 1_000L) {
                executeCli(request, onTextDelta, trackedProgress, waitPolicy)
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val finalState = lastOpenCodeDiagnostic
                ?.removeSuffix("…")
                ?.let { " 最后检测状态：$it。" }
                .orEmpty()
            throw ProviderException(
                "${connection.preset.displayName} 超过 ${waitPolicy.totalTimeoutSeconds} 秒未完成请求。" +
                    finalState +
                    "本地 CLI 没有独立的 45 秒首输出限制；本次不会自动重试，避免重复请求或扣费。" +
                    "可切换模型或提高该供应商的请求超时后手动重试。",
                networkFailure = true,
                retryableOverride = false,
                cause = timeout,
            )
        }
    }

    private suspend fun executeCli(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit,
        waitPolicy: CliOutputWaitPolicy,
    ): ModelResponse {
        if (cliTool == CliTool.DSH) {
            throw ProviderException(
                "DSH 使用持久化 dsh web Host RPC，会话不能通过一次性命令安全启动。" +
                    "请在依赖页启动 DSH Host；OmniCode 不会退回到猜测式命令。",
                retryableOverride = false,
            )
        }
        val executable = CliToolDiscovery.resolveExecutable(cliTool, connection.baseUrl)
            ?: throw ProviderException(
                "找不到 ${connection.preset.displayName} 的可执行文件。" +
                    "请安装后重试，或在供应商设置中配置可执行文件路径。" +
                    " 尝试的名称：${cliTool.executableNames.joinToString(", ")}",
            )

        validateSelectedOpenCodeModel()
        val resumeSessionId = localSession?.resumeSessionId.takeIf { cliTool in NATIVE_RESUME_TOOLS }
        val prompt = cliConversationText(request, resumeNativeSession = resumeSessionId != null)
        val args = cliTool.buildArgs(prompt, connection.model)
            .let { base -> if (cliTool == CliTool.OMP) ompArgsWithReasoning(base, connection.reasoningEffort) else base }
            .let { base ->
            if (resumeSessionId == null) base
            else when (cliTool) {
                CliTool.OPENCODE -> openCodeArgsWithSession(base, resumeSessionId)
                CliTool.OMP -> ompArgsWithSession(base, resumeSessionId)
                else -> base
            }
            }
        val workDir = CliToolLaunch.resolveWorkingDirectory(workingDirectory)
        verifyRuntime(executable, workDir)

        return executeCliAttempt(
            executable = executable,
            args = args,
            workDir = workDir,
            onTextDelta = onTextDelta,
            onProgress = onProgress,
            waitPolicy = waitPolicy,
        )
    }

    /**
     * OpenCode 1.18.x can keep a one-shot process alive without stdout or stderr when a removed
     * model id is supplied. Validate an explicit selection through OpenCode's own authenticated
     * catalog before dispatch so the turn never enters a false running state or consumes quota.
     * `default` deliberately remains native: it is resolved by the user's OpenCode config.
     */
    private suspend fun validateSelectedOpenCodeModel() {
        if (cliTool != CliTool.OPENCODE || connection.model.isBlank() || connection.model == "default") return
        val catalog = OpenCodeCliModelDiscovery.discover(
            connection.copy(requestTimeoutSeconds = connection.requestTimeoutSeconds.coerceIn(5, 15)),
        )
        if (connection.model !in catalog.models) {
            val alternatives = catalog.models.take(6).joinToString("、")
            throw ProviderException(
                "OpenCode 模型 ${connection.model} 当前不可用；该失效模型会导致 CLI 无输出地持续等待。" +
                    "请在模型列表改选：$alternatives。",
                retryableOverride = false,
            )
        }
    }

    private suspend fun executeCliAttempt(
        executable: File,
        args: List<String>,
        workDir: File,
        onTextDelta: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit,
        waitPolicy: CliOutputWaitPolicy,
    ): ModelResponse {

        val processBuilder = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + args)
            .directory(workDir)
            .redirectErrorStream(false)
        CliToolDiscovery.applyRuntimePath(processBuilder.environment(), executable)
        applyCliRequestEnvironment(cliTool, processBuilder.environment())

        // Pass API key as environment variable if configured
        if (connection.apiKey.isNotBlank()) {
            cliCredentialEnvironmentVariables(cliTool, connection.model).forEach { variable ->
                processBuilder.environment()[variable] = connection.apiKey
            }
        }

        val process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw ProviderException(
                "启动 ${connection.preset.displayName} 失败：${e.message?.take(500)}",
                networkFailure = true,
                cause = e,
            )
        }
        try {
            closeOneShotCliInput(process)
        } catch (error: IOException) {
            terminateProcessTree(process)
            throw ProviderException(
                "无法关闭 ${connection.preset.displayName} 的标准输入，已停止以避免 CLI 一直等待交互输入。",
                retryableOverride = false,
                cause = error,
            )
        }
        onProgress(
            if (cliTool == CliTool.OPENCODE) {
                "OpenCode 正在初始化本地会话；会按会话终态完成，不使用固定启动截止时间…"
            } else {
                "本地 CLI 已启动，正在等待首个输出…"
            },
        )

        val stderr = BoundedCliStderr()
        // Drain stderr in the background so a verbose CLI cannot deadlock. It is retained only
        // long enough to categorize an exit for the current task; raw stderr is never persisted
        // or forwarded to a model.
        Thread {
            runCatching { stderr.collect(process.errorStream) }
        }.apply {
            name = "omnicode-cli-${cliTool.name.lowercase()}-stderr"
            isDaemon = true
            start()
        }

        return try {
            if (cliTool.supportsStreamJson) {
                parseStreamJsonOutput(process, onTextDelta, onProgress, waitPolicy, stderr::snapshot)
            } else {
                parsePlainTextOutput(process, onTextDelta, onProgress, waitPolicy, stderr::snapshot)
            }
        } finally {
            terminateProcessTree(process)
        }
    }

    /**
     * Parse line-delimited JSON streaming output (OpenCode, Qoder).
     * Each line is a JSON object with a "type" field indicating the event kind.
     */
    private suspend fun parseStreamJsonOutput(
        process: Process,
        onTextDelta: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit,
        waitPolicy: CliOutputWaitPolicy,
        stderr: () -> String,
    ): ModelResponse {
        val text = StringBuilder()
        var tokenUsage = TokenUsage()
        var stopReason = StopReason.UNKNOWN
        val pendingLine = StringBuilder()
        var modelOutputStarted = false
        var protocolCompleted = false
        var protocolCompletedAtNanos: Long? = null
        var openCodeSessionId: String? = null
        var ompSessionId: String? = null

        fun markProtocolCompleted() {
            protocolCompleted = true
            if (protocolCompletedAtNanos == null) protocolCompletedAtNanos = System.nanoTime()
        }

        suspend fun appendText(value: String) {
            // Some CLI events carry an incremental delta while others carry the complete text
            // accumulated so far. Show only the suffix in the latter case, otherwise the chat
            // would visibly repeat every response segment.
            val delta = when {
                value.isBlank() -> ""
                text.isNotEmpty() && value.startsWith(text.toString()) -> value.drop(text.length)
                else -> value
            }
            if (delta.isNotBlank()) {
                if (text.length + delta.length > MAX_CLI_OUTPUT_CHARS) {
                    throw ProviderException("${connection.preset.displayName} 输出超过安全上限。")
                }
                text.append(delta)
                onTextDelta(delta)
            }
        }

        suspend fun consumeLine(rawLine: String) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) return
            val json = runCatching { Json.parseObject(trimmed) }.getOrNull() ?: return
            if (cliTool == CliTool.OPENCODE) {
                val eventSessionId = openCodeEventSessionId(json)
                if (openCodeSessionId == null && eventSessionId != null) {
                    openCodeSessionId = eventSessionId
                    localSession?.onSessionStarted?.invoke(eventSessionId)
                    onProgress("OpenCode 会话已连接，正在等待模型事件…")
                } else if (eventSessionId != null && eventSessionId != openCodeSessionId) {
                    // A shared OpenCode runtime may emit maintenance events for another
                    // session. Never merge those deltas, tools, or terminal state into this
                    // OmniCode turn.
                    return
                }
            }
            val properties = json.jsonObjectOrNull("properties")
            val nestedPart = json.jsonObjectOrNull("part")
                ?: properties?.jsonObjectOrNull("part")
                ?: json.jsonObjectOrNull("data")?.jsonObjectOrNull("part")
            val type = json.stringOrNull("type")
                ?: json.stringOrNull("event")
                ?: nestedPart?.stringOrNull("type")
                ?: return
            val partType = nestedPart?.stringOrNull("type")
            val finishReason = nestedPart?.stringOrNull("reason")
                ?: properties?.stringOrNull("reason")
                ?: json.stringOrNull("reason")
            val content = json.stringOrNull("content")
                ?: json.stringOrNull("text")
                ?: json.stringOrNull("delta")
                ?: properties?.stringOrNull("content")
                ?: properties?.stringOrNull("text")
                ?: properties?.stringOrNull("delta")
                ?: nestedPart?.stringOrNull("content")
                ?: nestedPart?.stringOrNull("text")
                ?: nestedPart?.stringOrNull("delta")

            when (type) {
                "session" -> if (cliTool == CliTool.OMP) {
                    json.stringOrNull("id")
                        ?.trim()
                        ?.takeIf { it.matches(SAFE_NATIVE_CLI_SESSION_ID) }
                        ?.let { sessionId ->
                            if (ompSessionId == null) {
                                ompSessionId = sessionId
                                localSession?.onSessionStarted?.invoke(sessionId)
                                onProgress("OMP 会话已连接，正在等待模型事件…")
                            }
                        }
                }
                "text", "message", "content", "delta" -> {
                    modelOutputStarted = true
                    if (content != null) appendText(content)
                }
                "step_start" -> {
                    modelOutputStarted = true
                    onProgress("OpenCode 已开始生成回答…")
                }
                "message.part.updated", "message.part.delta" -> {
                    // OpenCode emits several part kinds (tool, reasoning, step-finish). Only
                    // a completed text part belongs in the visible assistant answer.
                    if (partType == "text" && content != null) appendText(content)
                    if (partType == "step-start") {
                        modelOutputStarted = true
                        onProgress("OpenCode 已开始生成回答…")
                    }
                }
                "message_update" -> {
                    val assistantEvent = json.jsonObjectOrNull("assistantMessageEvent")
                    when (assistantEvent?.stringOrNull("type")) {
                        "text_delta" -> assistantEvent.stringOrNull("delta")?.let { appendText(it) }
                        "thinking_delta" -> onProgress("OMP 正在推理…")
                    }
                    modelOutputStarted = true
                }
                "message_end" -> {
                    stopReason = StopReason.COMPLETE
                    markProtocolCompleted()
                }
                "usage" -> {
                    val usageSource = nestedPart ?: json
                    val promptTokens = usageSource.longOrZero("input_tokens").takeIf { it > 0 }
                        ?: usageSource.longOrZero("prompt_tokens")
                    val completionTokens = usageSource.longOrZero("output_tokens").takeIf { it > 0 }
                        ?: usageSource.longOrZero("completion_tokens")
                    tokenUsage = TokenUsage(promptTokens, completionTokens)
                }
                "complete", "done", "end", "finish", "stop", "session.idle" -> {
                    stopReason = StopReason.COMPLETE
                }
                "error" -> {
                    val message = json.stringOrNull("message") ?: json.stringOrNull("error") ?: "CLI error"
                    throw ProviderException("${connection.preset.displayName}: $message")
                }
                "permission.asked" -> throw ProviderException(
                    "${connection.preset.displayName} 正在等待其自身的权限确认；"
                        + "OmniCode 不会替本地 CLI 自动批准命令或文件修改。"
                        + "请在 CLI 中完成该次确认，或改用 OmniCode 的 API 供应商与审批流程。",
                    retryableOverride = false,
                )
                "question.asked" -> throw ProviderException(
                    "${connection.preset.displayName} 正在等待交互式回答；"
                        + "当前非交互调用无法安全代答。请在 CLI 终端完成会话后重试。",
                    retryableOverride = false,
                )
            }
            if (partType == "step-finish") {
                val tokens = nestedPart?.jsonObjectOrNull("tokens")
                if (tokens != null) {
                    tokenUsage = TokenUsage(
                        inputTokens = tokens.longOrZero("input"),
                        outputTokens = tokens.longOrZero("output"),
                    )
                }
                stopReason = StopReason.COMPLETE
            }
            if (type == "session.status" &&
                properties?.jsonObjectOrNull("status")?.stringOrNull("type") == "idle"
            ) {
                stopReason = StopReason.COMPLETE
                markProtocolCompleted()
            }
            if (openCodeProtocolEventCompletesRun(type, partType, finishReason)) {
                markProtocolCompleted()
            }
        }

        drainCliStdout(
            process = process,
            onProgress = onProgress,
            waitPolicy = waitPolicy,
            stderr = stderr,
            protocolCompleted = {
                cliProtocolOutputReady(
                    protocolCompleted = protocolCompleted,
                    outputChars = text.length,
                    completedAtNanos = protocolCompletedAtNanos,
                    nowNanos = System.nanoTime(),
                )
            },
            modelOutputStarted = { modelOutputStarted },
        ) { chunk ->
            pendingLine.append(chunk)
            if (pendingLine.length > MAX_CLI_JSON_LINE_CHARS) {
                throw ProviderException("${connection.preset.displayName} 输出行超过安全上限。")
            }
            while (true) {
                val end = pendingLine.indexOf("\n")
                if (end < 0) break
                val line = pendingLine.substring(0, end).trimEnd('\r')
                pendingLine.delete(0, end + 1)
                consumeLine(line)
            }
        }
        if (pendingLine.isNotBlank()) consumeLine(pendingLine.toString())

        val exitCode = cliProcessExitCode(process)
        if (exitCode != null && exitCode != 0 && text.isEmpty()) {
            invalidateNativeSessionIfRejected(stderr())
            throw ProviderException(cliExitFailureMessage(exitCode, stderr()))
        }

        if (stopReason == StopReason.UNKNOWN && text.isNotEmpty()) stopReason = StopReason.COMPLETE

        val responseText = text.toString().trim()
        return ModelResponse(
            blocks = listOfNotNull(responseText.takeIf { it.isNotBlank() }?.let(ContentBlock::Text)),
            usage = tokenUsage,
            stopReason = stopReason,
        )
    }

    /**
     * Parse plain text output (Kimi, Grok, Pi) and surface stdout incrementally.
     */
    private suspend fun parsePlainTextOutput(
        process: Process,
        onTextDelta: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit,
        waitPolicy: CliOutputWaitPolicy,
        stderr: () -> String,
    ): ModelResponse {
        val output = StringBuilder()
        drainCliStdout(process, onProgress, waitPolicy, stderr) { chunk ->
            if (output.length + chunk.length > MAX_CLI_OUTPUT_CHARS) {
                throw ProviderException("${connection.preset.displayName} 输出超过安全上限。")
            }
            output.append(chunk)
            onTextDelta(chunk)
        }
        val responseText = output.toString().trim()
        val exitCode = cliProcessExitCode(process)
        if (exitCode != null && exitCode != 0 && responseText.isEmpty()) {
            invalidateNativeSessionIfRejected(stderr())
            throw ProviderException(cliExitFailureMessage(exitCode, stderr()))
        }

        // For plain text output, we consider the whole output as the response.
        // CLI tools typically only output the final answer in print mode.
        return ModelResponse(
            blocks = listOfNotNull(responseText.takeIf { it.isNotBlank() }?.let(ContentBlock::Text)),
            usage = TokenUsage(),
            stopReason = if (responseText.isNotBlank()) StopReason.COMPLETE else StopReason.UNKNOWN,
        )
    }

    /**
     * Process stdout reads are blocking and do not reliably observe coroutine cancellation.
     * Polling ready characters gives cancellation a bounded point every few milliseconds; the
     * enclosing finally can then terminate the whole CLI process tree immediately.
     */
    private suspend fun drainCliStdout(
        process: Process,
        onProgress: suspend (String) -> Unit,
        waitPolicy: CliOutputWaitPolicy,
        stderr: () -> String,
        protocolCompleted: () -> Boolean = { false },
        modelOutputStarted: () -> Boolean = { false },
        onChunk: suspend (String) -> Unit,
    ) {
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(CLI_OUTPUT_BUFFER_CHARS)
            val startedAt = System.nanoTime()
            var nextProgressAt = startedAt + waitPolicy.progressIntervalSeconds * NANOS_PER_SECOND
            var nextDiagnosticAt = startedAt
            var lastDiagnostic: String? = null
            var openCodeSessionStarted = cliTool != CliTool.OPENCODE
            var openCodeModelRequestStarted = cliTool != CliTool.OPENCODE
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                var readAny = false
                while (reader.ready()) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    readAny = true
                    onChunk(String(buffer, 0, count))
                }
                val now = System.nanoTime()
                if (now >= nextDiagnosticAt) {
                    val stderrSnapshot = stderr()
                    if (!openCodeSessionStarted && openCodeSessionHasStarted(stderrSnapshot)) {
                        openCodeSessionStarted = true
                    }
                    if (!openCodeModelRequestStarted && openCodePrimaryModelHasStarted(stderrSnapshot)) {
                        openCodeModelRequestStarted = true
                    }
                    val diagnostic = cliStderrProgress(stderrSnapshot)
                    if (diagnostic != null && diagnostic != lastDiagnostic) {
                        onProgress(diagnostic)
                        lastDiagnostic = diagnostic
                    }
                    nextDiagnosticAt = now + CLI_STDERR_DIAGNOSTIC_INTERVAL_MILLIS * 1_000_000L
                }
                // OpenCode's JSON protocol reports the terminal model step before it disposes
                // project watchers, snapshots, and other local maintenance. The user's answer is
                // complete at this boundary, so do not keep the whole OmniCode task open while
                // the one-shot child performs non-essential cleanup. executeCliAttempt's finally
                // block still terminates the complete process tree.
                if (protocolCompleted()) return
                val elapsedSeconds = ((now - startedAt) / NANOS_PER_SECOND).coerceAtLeast(0L)
                if (now >= nextProgressAt) {
                    onProgress(cliHeartbeatProgress(
                        tool = cliTool,
                        sessionStarted = openCodeSessionStarted,
                        modelRequestStarted = openCodeModelRequestStarted,
                        modelOutputStarted = modelOutputStarted(),
                        elapsedSeconds = elapsedSeconds.coerceAtLeast(1L),
                    ))
                    nextProgressAt = now + waitPolicy.progressIntervalSeconds * NANOS_PER_SECOND
                }
                if (!readAny) delay(CLI_OUTPUT_POLL_MILLIS)
            }
            // A CLI parent can exit while a child still owns the inherited stdout pipe (for
            // example a Node worker doing cleanup). A blocking read here would keep the request
            // coroutine alive forever and prevent the finally block from terminating the tree.
            // Drain only data that is already ready during a short, cancellation-aware grace
            // window; anything produced later belongs to the child that will be terminated.
            val drainDeadline = System.nanoTime() + CLI_POST_EXIT_DRAIN_GRACE_MILLIS * 1_000_000L
            while (System.nanoTime() < drainDeadline) {
                currentCoroutineContext().ensureActive()
                var readAny = false
                while (reader.ready()) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    readAny = true
                    onChunk(String(buffer, 0, count))
                }
                if (!readAny) delay(CLI_POST_EXIT_DRAIN_POLL_MILLIS)
            }
        }
    }

    private fun terminateProcessTree(process: Process) {
        if (!process.isAlive) return
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        descendants.forEach { handle -> runCatching { handle.destroy() } }
        runCatching { process.destroy() }
        val stopped = runCatching { process.waitFor(CLI_PROCESS_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
            .getOrDefault(!process.isAlive)
        if (!stopped) {
            descendants.forEach { handle -> runCatching { handle.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(CLI_PROCESS_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * A discovered npm/Python launcher can still be unusable inside a GUI-launched IDE when its
     * interpreter is absent from PATH. Verify the exact inherited environment before sending a
     * prompt to a provider, and never surface raw stderr because it can contain credentials.
     */
    private suspend fun verifyRuntime(executable: File, workDir: File) {
        val probe = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + "--version")
            .directory(workDir)
            .redirectErrorStream(true)
        CliToolDiscovery.applyRuntimePath(probe.environment(), executable)
        val process = try {
            probe.start()
        } catch (error: IOException) {
            throw ProviderException(
                "无法启动 ${connection.preset.displayName} 的运行时。请重新检测 CLI 后重试。",
                networkFailure = true,
                cause = error,
            )
        }
        try {
            closeOneShotCliInput(process)
            val output = withTimeout(CLI_RUNTIME_PROBE_TIMEOUT_MILLIS) {
                readBoundedProcessOutput(process, CLI_RUNTIME_PROBE_MAX_CHARS)
            }
            if (process.exitValue() != 0) {
                throw ProviderException(cliRuntimeFailureMessage(output), retryableOverride = false)
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ProviderException(
                "${connection.preset.displayName} 的运行时预检超过 ${CLI_RUNTIME_PROBE_TIMEOUT_MILLIS / 1_000} 秒。" +
                    "请确认 CLI 及其 Node/Python 运行时已安装后重试。",
                networkFailure = true,
                cause = timeout,
            )
        } finally {
            terminateProcessTree(process)
        }
    }

    private fun cliRuntimeFailureMessage(output: String): String {
        val normalized = output.lowercase()
        return when {
            "node: no such file" in normalized || "node is not recognized" in normalized ->
                "${connection.preset.displayName} 已找到，但 Node.js 运行时不可用。" +
                    "请安装 Node.js，并在 CLI 页面点击“重新检测”。"
            "python" in normalized && ("not found" in normalized || "not recognized" in normalized) ->
                "${connection.preset.displayName} 已找到，但 Python 运行时不可用。" +
                    "请安装 Python，并在 CLI 页面点击“重新检测”。"
            else ->
                "${connection.preset.displayName} 无法完成运行时预检。" +
                    "请在系统终端执行 ${cliTool.executableNames.first()} --version，并在 CLI 页面重新检测。"
        }
    }

    private fun invalidateNativeSessionIfRejected(stderr: String) {
        if (localSession?.resumeSessionId == null) return
        val normalized = stderr.lowercase()
        if (("session" in normalized) && (
                "not found" in normalized || "does not exist" in normalized ||
                    "invalid session" in normalized || "unknown session" in normalized
                )) {
            localSession.onSessionInvalid()
        }
    }

    private fun cliExitFailureMessage(exitCode: Int, stderr: String): String {
        val normalized = stderr.lowercase()
        val providerFailure = cliStderrProgress(stderr)?.removeSuffix("…")
        return when {
            "no api key" in normalized || "api key" in normalized && "not found" in normalized ->
                "${connection.preset.displayName} 未登录或没有所选模型的 API Key。" +
                    "请在该 CLI 自身完成登录，或在 OmniCode 的 CLI 配置中保存对应供应商的 Key 后重试。"
            "login" in normalized && ("required" in normalized || "please" in normalized) ->
                "${connection.preset.displayName} 需要先在本机 CLI 完成登录。完成登录后点击“重新检测”再重试。"
            "model" in normalized && ("not found" in normalized || "not available" in normalized || "not authorized" in normalized) ->
                "${connection.preset.displayName} 无权使用当前模型或模型名已失效。请重新读取模型列表后选择可用模型。"
            "country" in normalized || "region" in normalized && "not available" in normalized ->
                "${connection.preset.displayName} 的当前模型在此账户或地区不可用。请切换已授权模型后重试。"
            "node: no such file" in normalized || "node is not recognized" in normalized ->
                "${connection.preset.displayName} 缺少可用的 Node.js 运行时。请在 CLI 页面重新检测并按修复指引处理。"
            "python" in normalized && ("not found" in normalized || "not recognized" in normalized) ->
                "${connection.preset.displayName} 缺少可用的 Python 运行时。请在 CLI 页面重新检测并按修复指引处理。"
            providerFailure != null ->
                "$providerFailure，且 OpenCode 重试后仍未完成。请切换模型或稍后重试。"
            else ->
                "${connection.preset.displayName} 退出码 $exitCode，未返回可显示结果。" +
                    "请在 CLI 页面重新检测；若仍失败，请在系统终端运行 ${cliTool.executableNames.first()} --version。"
        }
    }

    /**
     * A local coding CLI already receives the canonical opened project directory. Re-sending
     * OmniCode's Harness inventory and old tool transcripts makes it index the same project
     * twice and can delay first output dramatically. Keep only recent human-visible dialogue.
     */
    private fun cliConversationText(request: ModelRequest, resumeNativeSession: Boolean = false): String {
        val systemPolicy = request.messages.asSequence()
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.blocks.asSequence() }
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
            .trim()
            .take(MAX_CLI_SYSTEM_CHARS)
        val history = request.messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .let { messages ->
                if (resumeNativeSession) messages.indexOfLast { it.role == MessageRole.USER }
                    .takeIf { it >= 0 }
                    ?.let { listOf(messages[it]) }
                    .orEmpty()
                else messages.takeLast(MAX_CLI_HISTORY_MESSAGES)
            }
        val visible = buildString {
            history.forEach { message ->
                val text = message.blocks.mapNotNull { block ->
                    when (block) {
                        is ContentBlock.Text -> block.text
                        is ContentBlock.Image -> "[用户附加图片：${block.fileName}]"
                        // The CLI has the real project root as cwd and must not receive an
                        // unbounded duplicate of repository metadata or tool output.
                        is ContentBlock.TransientProjectContext,
                        is ContentBlock.ToolResult,
                        is ContentBlock.ToolCall,
                        -> null
                    }
                }.joinToString("\n").trim().take(MAX_CLI_MESSAGE_CHARS)
                if (text.isNotBlank()) {
                    if (message.role == MessageRole.ASSISTANT) append("Assistant: ")
                    append(text)
                    append("\n\n")
                }
            }
        }.trim().takeLast(MAX_CLI_PROMPT_CHARS)
        return buildString {
            appendLine("当前已在用户打开的项目根目录中运行。")
            appendLine("OmniCode 强制运行模式：${agentMode.name}。不得尝试绕过该模式或权限策略。")
            if (systemPolicy.isNotBlank()) {
                appendLine()
                appendLine("OmniCode 运行策略：")
                appendLine(systemPolicy)
            }
            appendLine()
            appendLine("请只处理以下对话请求：")
            append(visible)
        }
    }
}

internal fun openCodeArgsWithSession(base: List<String>, sessionId: String): List<String> {
    require(base.isNotEmpty() && base.first() == "run") { "Invalid OpenCode argv" }
    require(SAFE_OPENCODE_SESSION_ID.matches(sessionId)) { "Invalid OpenCode session id" }
    return base.dropLast(1) + listOf("--session", sessionId, base.last())
}

internal fun ompArgsWithSession(base: List<String>, sessionId: String): List<String> {
    require(base.size >= 2 && base.take(3) == listOf("--print", "--mode", "json")) { "Invalid OMP argv" }
    require(SAFE_NATIVE_CLI_SESSION_ID.matches(sessionId)) { "Invalid OMP session id" }
    return base.dropLast(1) + listOf("--resume", sessionId, base.last())
}

internal fun ompArgsWithReasoning(base: List<String>, effort: ReasoningEffort): List<String> {
    require(base.size >= 2 && base.take(3) == listOf("--print", "--mode", "json")) { "Invalid OMP argv" }
    val level = when (effort) {
        ReasoningEffort.AUTO -> null
        ReasoningEffort.NONE -> "off"
        else -> effort.persistedValue
    }
    return if (level == null) base else base.dropLast(1) + listOf("--thinking", level, base.last())
}

/**
 * Local coding CLIs use their working directory as the project boundary.  Falling back to the
 * IDE process directory is unsafe and can make a Finder-launched IDE scan the user's home
 * directory instead of the opened project.
 */
internal object CliToolLaunch {
    fun resolveWorkingDirectory(candidate: Path?): File {
        val directory = candidate ?: throw ProviderException(
            "本地 CLI 需要一个已打开的项目目录；为避免在 Home 目录运行，本次请求未启动。",
            retryableOverride = false,
        )
        val canonical = try {
            directory.toRealPath()
        } catch (error: IOException) {
            throw ProviderException(
                "当前项目目录不可用，本地 CLI 未启动。请重新打开本地项目后重试。",
                retryableOverride = false,
                cause = error,
            )
        }
        if (!Files.isDirectory(canonical)) {
            throw ProviderException(
                "当前项目目录不是文件夹，本地 CLI 未启动。",
                retryableOverride = false,
            )
        }
        return canonical.toFile()
    }
}

/**
 * Every CLI adapter in this file passes its prompt through argv and is intentionally
 * non-interactive. ProcessBuilder otherwise leaves a writable stdin pipe open. CLIs such as
 * OpenCode detect that pipe as non-TTY input and wait for EOF before creating a session, so the
 * parent must close it immediately after launch.
 */
@Throws(IOException::class)
internal fun closeOneShotCliInput(process: Process) {
    process.outputStream.close()
}

/** Null means the protocol completed before the one-shot process finished local cleanup. */
internal fun cliProcessExitCode(process: Process): Int? =
    if (process.isAlive) null else runCatching(process::exitValue).getOrNull()

/**
 * Local coding CLIs may legitimately stay silent while they start a local server, index the
 * project, or wait in a free-model queue. They therefore use the configured total request bound
 * instead of a shorter first-stdout deadline. The heartbeat is UI-only and never extends that
 * total bound; user cancellation still tears down the process tree immediately.
 */
internal data class CliOutputWaitPolicy(
    val totalTimeoutSeconds: Long,
    val progressIntervalSeconds: Long,
)

internal fun cliOutputWaitPolicy(totalTimeoutSeconds: Long): CliOutputWaitPolicy = CliOutputWaitPolicy(
    totalTimeoutSeconds = totalTimeoutSeconds.coerceIn(10L, MAX_CLI_TOTAL_TIMEOUT_SECONDS),
    progressIntervalSeconds = CLI_PROGRESS_INTERVAL_SECONDS,
)

/**
 * Produces one truthful heartbeat for a one-shot CLI request. OpenCode's process can be alive in
 * three materially different phases, so a generic "CLI still processing" label is misleading:
 * it previously closed the model stage in the UI even though the upstream model had not emitted
 * its first event yet.
 */
internal fun cliHeartbeatProgress(
    tool: CliTool,
    sessionStarted: Boolean,
    modelRequestStarted: Boolean,
    modelOutputStarted: Boolean,
    elapsedSeconds: Long,
): String {
    val elapsed = elapsedSeconds.coerceAtLeast(1L)
    if (tool != CliTool.OPENCODE) {
        return "本地 CLI 仍在处理 · ${elapsed}秒 · 可随时停止"
    }
    return when {
        !sessionStarted -> "OpenCode 仍在初始化本地会话 · ${elapsed}秒 · 可随时停止"
        modelOutputStarted -> "OpenCode 正在生成回答 · ${elapsed}秒 · 可随时停止"
        modelRequestStarted -> buildString {
            append("OpenCode 正在等待模型响应 · ").append(elapsed).append("秒")
            if (elapsed >= OPENCODE_QUEUE_HINT_SECONDS) append(" · 上游模型可能排队")
            append(" · 可随时停止")
        }
        else -> "OpenCode 正在准备项目快照 · ${elapsed}秒 · 可随时停止"
    }
}

/**
 * Only inject a saved key into the environment name the selected CLI/model understands. This
 * avoids the old Pi behavior that always placed an OpenAI or Gemini key in ANTHROPIC_API_KEY.
 */
internal fun cliCredentialEnvironmentVariables(tool: CliTool, model: String?): Set<String> = when (tool) {
    CliTool.CLAUDE -> setOf("ANTHROPIC_API_KEY")
    CliTool.CODEX -> setOf("OPENAI_API_KEY")
    CliTool.KIMI -> linkedSetOf("KIMI_API_KEY", "MOONSHOT_API_KEY")
    CliTool.GROK -> setOf("XAI_API_KEY")
    CliTool.QODER -> setOf("QODER_PERSONAL_ACCESS_TOKEN")
    CliTool.OMP,
    CliTool.DSH,
    -> emptySet()
    CliTool.OPENCODE,
    CliTool.PI,
    -> providerKeyEnvironmentVariable(model) ?: when (tool) {
        CliTool.OPENCODE -> setOf("OPENCODE_API_KEY")
        CliTool.PI -> setOf("GEMINI_API_KEY")
        else -> emptySet()
    }
}

private fun providerKeyEnvironmentVariable(model: String?): Set<String>? = when (
    model.orEmpty().substringBefore('/').trim().lowercase()
) {
    "anthropic", "claude" -> setOf("ANTHROPIC_API_KEY")
    "azure", "azure-openai" -> setOf("AZURE_OPENAI_API_KEY")
    "deepseek" -> setOf("DEEPSEEK_API_KEY")
    "gemini", "google" -> setOf("GEMINI_API_KEY")
    "groq" -> setOf("GROQ_API_KEY")
    "moonshot", "kimi" -> linkedSetOf("KIMI_API_KEY", "MOONSHOT_API_KEY")
    "openai" -> setOf("OPENAI_API_KEY")
    "openrouter" -> setOf("OPENROUTER_API_KEY")
    "xai", "grok" -> setOf("XAI_API_KEY")
    else -> null
}

/**
 * Keep local CLI authentication, provider configuration, model cache and session storage exactly
 * aligned with the user's terminal. Earlier versions replaced OpenCode's database with `:memory:`
 * and moved XDG cache/state on every IDE request. That made a successfully configured CLI look
 * like a fresh cold installation and prevented native session/history reuse.
 *
 * The hook remains as the single, testable place for future documented child-only variables, but
 * intentionally performs no mutation today.
 */
internal fun applyCliRequestEnvironment(
    tool: CliTool,
    environment: MutableMap<String, String>,
    isolationRoot: Path = openCodeRuntimeIsolationRoot(),
) {
    @Suppress("UNUSED_VARIABLE")
    val retainedForBinaryCompatibility = Triple(tool, environment, isolationRoot)
}

internal fun openCodeRuntimeIsolationRoot(): Path =
    Path.of(PathManager.getSystemPath()).resolve("omnicode/opencode-runtime")

/** Converts OpenCode's error-only stderr into a stable, non-sensitive task phase. */
internal fun cliStderrProgress(stderr: String): String? {
    val diagnostic = stderr.lineSequence()
        // Automatic title generation is not part of the user's task and may fail independently.
        .filterNot { line -> "small=true" in line && "agent=title" in line }
        .map(String::lowercase)
        .lastOrNull { line ->
            listOf(
                "message=created id=", "message=stream providerid=",
                "temporarily overloaded", "service unavailable", "endpoint is unavailable",
                "internal server error", "rate limit", "[429]", "socket connection was closed",
                "failed to fetch models.dev", "timed out", "timeout",
            ).any(line::contains)
        }
        ?: return null

    return when {
        "message=stream providerid=" in diagnostic && "small=false" in diagnostic ->
            "OpenCode 请求已提交，正在等待模型响应…"
        "message=created id=" in diagnostic ->
            "OpenCode 本地会话已创建，正在准备项目快照…"
        "temporarily overloaded" in diagnostic || "service unavailable" in diagnostic ||
            "endpoint is unavailable" in diagnostic || "internal server error" in diagnostic ->
            "OpenCode 上游模型暂时繁忙，正在重试…"
        "rate limit" in diagnostic || "[429]" in diagnostic ->
            "OpenCode 上游模型触发限流，正在等待重试…"
        "failed to fetch models.dev" in diagnostic ->
            "OpenCode 模型目录服务响应较慢；当前任务仍在继续…"
        "socket connection was closed" in diagnostic ->
            "OpenCode 上游连接中断，正在恢复…"
        "timed out" in diagnostic || "timeout" in diagnostic ->
            "OpenCode 上游请求超时，正在重试…"
        else -> null
    }
}

/** True only after OpenCode has created a task session or started the primary model stream. */
internal fun openCodeSessionHasStarted(stderr: String): Boolean = stderr.lineSequence().any { line ->
    val normalized = line.lowercase()
    "message=created id=" in normalized ||
        ("message=stream providerid=" in normalized && "small=false" in normalized)
}

/** True after OpenCode has submitted the primary request, before the first model event arrives. */
internal fun openCodePrimaryModelHasStarted(stderr: String): Boolean = stderr.lineSequence().any { line ->
    val normalized = line.lowercase()
    "message=stream providerid=" in normalized && "small=false" in normalized
}

/**
 * A `step_finish` with `tool-calls` or `unknown` is an intermediate ReAct step. `stop` and
 * `length` are terminal in OpenCode's own prompt loop, so the parent may safely return the answer
 * without waiting for project snapshot cleanup or process disposal.
 */
internal fun openCodeProtocolEventCompletesRun(
    eventType: String?,
    partType: String?,
    finishReason: String?,
): Boolean {
    val event = eventType.orEmpty().lowercase()
    if (event in setOf("complete", "done", "end", "finish", "stop", "session.idle")) return true
    val stepFinished = event == "step_finish" || partType.orEmpty().lowercase() == "step-finish"
    return stepFinished && finishReason.orEmpty().lowercase() in setOf("stop", "length")
}

/**
 * A terminal JSON event is not necessarily the last bytes written by a CLI. OpenCode and OMP
 * can flush the final text part just after `session.status=idle`/`message_end`; returning from
 * the stdout loop immediately used to lose that text and produce an apparently empty answer.
 * Once visible output exists we can finish immediately. For an output-less protocol completion,
 * retain a short grace period so tool-only turns still finish without waiting for process EOF.
 */
internal fun cliProtocolOutputReady(
    protocolCompleted: Boolean,
    outputChars: Int,
    completedAtNanos: Long?,
    nowNanos: Long,
): Boolean {
    if (!protocolCompleted) return false
    if (outputChars > 0) return true
    val completedAt = completedAtNanos ?: return false
    return nowNanos - completedAt >= CLI_PROTOCOL_OUTPUT_GRACE_MILLIS * NANOS_PER_MILLISECOND
}

/** Finds an OpenCode session id in a bounded event tree without retaining provider payloads. */
internal fun openCodeEventSessionId(event: JsonObject): String? {
    fun find(element: JsonElement?, depth: Int): String? {
        if (element == null || element.isJsonNull || depth > MAX_OPENCODE_SESSION_SEARCH_DEPTH) return null
        if (element.isJsonArray) {
            return element.asJsonArray.asSequence().mapNotNull { find(it, depth + 1) }.firstOrNull()
        }
        if (!element.isJsonObject) return null
        val objectValue = element.asJsonObject
        for (key in OPENCODE_SESSION_KEYS) {
            val value = objectValue.get(key)
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                val candidate = value.asString.trim()
                if (candidate.matches(SAFE_OPENCODE_SESSION_ID)) return candidate
            }
        }
        return objectValue.entrySet().asSequence()
            .mapNotNull { (_, value) -> find(value, depth + 1) }
            .firstOrNull()
    }
    return find(event, 0)
}

private class BoundedCliStderr {
    private val output = StringBuilder()

    fun collect(stream: java.io.InputStream) {
        stream.bufferedReader().use { reader ->
            val buffer = CharArray(1_024)
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                synchronized(output) {
                    output.append(buffer, 0, count)
                    if (output.length > MAX_CLI_STDERR_CHARS) {
                        output.delete(0, output.length - MAX_CLI_STDERR_CHARS)
                    }
                }
            }
        }
    }

    fun snapshot(): String = synchronized(output) { output.toString() }
}

internal suspend fun readBoundedProcessOutput(process: Process, maxCharacters: Int): String {
    val output = StringBuilder()
    process.inputStream.bufferedReader().use { reader ->
        val buffer = CharArray(1_024)
        while (process.isAlive || reader.ready()) {
            currentCoroutineContext().ensureActive()
            while (reader.ready()) {
                val count = reader.read(buffer)
                if (count <= 0) break
                if (output.length + count > maxCharacters) {
                    return output.append(buffer, 0, maxCharacters - output.length).toString()
                }
                output.append(buffer, 0, count)
            }
            if (process.isAlive) delay(CLI_OUTPUT_POLL_MILLIS)
        }
        // Do not perform an unbounded read after the process exits: a descendant may keep the
        // pipe open. The probe only needs bytes already available to classify the runtime.
        val drainDeadline = System.nanoTime() + CLI_POST_EXIT_DRAIN_GRACE_MILLIS * 1_000_000L
        while (System.nanoTime() < drainDeadline) {
            currentCoroutineContext().ensureActive()
            var readAny = false
            while (reader.ready()) {
                val count = reader.read(buffer)
                if (count <= 0) break
                readAny = true
                if (output.length + count > maxCharacters) {
                    output.append(buffer, 0, maxCharacters - output.length)
                    return output.toString()
                }
                output.append(buffer, 0, count)
            }
            if (!readAny) delay(CLI_POST_EXIT_DRAIN_POLL_MILLIS)
        }
    }
    return output.toString()
}

/** Compact visible dialogue is sent to a CLI; it already has the opened project as cwd. */
private const val MAX_CLI_PROMPT_CHARS = 12_000
private const val MAX_CLI_SYSTEM_CHARS = 8_000
private const val MAX_CLI_MESSAGE_CHARS = 4_000
private const val MAX_CLI_HISTORY_MESSAGES = 8
private const val MAX_CLI_OUTPUT_CHARS = 1_000_000
private const val MAX_CLI_JSON_LINE_CHARS = 256_000
private const val MAX_CLI_STDERR_CHARS = 4_096
private const val CLI_OUTPUT_BUFFER_CHARS = 8_192
private const val CLI_OUTPUT_POLL_MILLIS = 25L
private const val CLI_STDERR_DIAGNOSTIC_INTERVAL_MILLIS = 250L
private const val CLI_PROCESS_EXIT_GRACE_MILLIS = 500L
private const val CLI_POST_EXIT_DRAIN_GRACE_MILLIS = 250L
private const val CLI_POST_EXIT_DRAIN_POLL_MILLIS = 5L
private const val CLI_PROTOCOL_OUTPUT_GRACE_MILLIS = 500L
private const val CLI_PROGRESS_INTERVAL_SECONDS = 15L
private const val OPENCODE_QUEUE_HINT_SECONDS = 30L
private const val MAX_OPENCODE_SESSION_SEARCH_DEPTH = 6
private val OPENCODE_SESSION_KEYS = listOf("session_id", "sessionId", "sessionID")
private val SAFE_OPENCODE_SESSION_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private val SAFE_NATIVE_CLI_SESSION_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private val NATIVE_RESUME_TOOLS = setOf(CliTool.OPENCODE, CliTool.OMP)
private const val MAX_CLI_TOTAL_TIMEOUT_SECONDS = 3_600L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CLI_RUNTIME_PROBE_TIMEOUT_MILLIS = 8_000L
private const val CLI_RUNTIME_PROBE_MAX_CHARS = 4_096
