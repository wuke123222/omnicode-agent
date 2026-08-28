package dev.omnicode.provider

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
    OPENCODE(
        executableNames = listOf("opencode"),
        buildArgs = { prompt, model ->
            buildList {
                add("run")
                add(prompt)
                if (!model.isNullOrBlank() && model != "default") {
                    add("--model"); add(model)
                }
                // OpenCode uses --format json for newline-delimited JSON events. The older
                // --output-format stream-json spelling exits with code 1 without stdout.
                add("--format"); add("json")
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
        return if (!isWindows() && isNodeLauncher(executable) && node != null) {
            listOf(node.absolutePath, executable.absolutePath)
        } else {
            listOf(executable.absolutePath)
        }
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
        val names = if (isWindows()) listOf(name, "$name.exe", "$name.cmd") else listOf(name)
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
        val timeoutSeconds = connection.requestTimeoutSeconds.coerceIn(10, 3_600)
        try {
            withTimeout(timeoutSeconds * 1_000L) {
                executeCli(request, onTextDelta, onProgress)
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ProviderException(
                "${connection.preset.displayName} 超过 ${timeoutSeconds} 秒未完成请求。",
                networkFailure = true,
                cause = timeout,
            )
        }
    }

    private suspend fun executeCli(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit,
    ): ModelResponse {
        val executable = CliToolDiscovery.resolveExecutable(cliTool, connection.baseUrl)
            ?: throw ProviderException(
                "找不到 ${connection.preset.displayName} 的可执行文件。" +
                    "请安装后重试，或在供应商设置中配置可执行文件路径。" +
                    " 尝试的名称：${cliTool.executableNames.joinToString(", ")}",
            )

        val prompt = cliConversationText(request)
        val args = cliTool.buildArgs(prompt, connection.model)
        val workDir = CliToolLaunch.resolveWorkingDirectory(workingDirectory)
        verifyRuntime(executable, workDir)

        val processBuilder = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + args)
            .directory(workDir)
            .redirectErrorStream(false)
        CliToolDiscovery.applyRuntimePath(processBuilder.environment(), executable)

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
        onProgress("本地 CLI 已启动，正在等待首个输出…")

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
                parseStreamJsonOutput(process, onTextDelta, stderr::snapshot)
            } else {
                parsePlainTextOutput(process, onTextDelta, stderr::snapshot)
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
        stderr: () -> String,
    ): ModelResponse {
        val text = StringBuilder()
        var tokenUsage = TokenUsage()
        var stopReason = StopReason.UNKNOWN
        val pendingLine = StringBuilder()

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
            val properties = json.jsonObjectOrNull("properties")
            val nestedPart = json.jsonObjectOrNull("part")
                ?: properties?.jsonObjectOrNull("part")
                ?: json.jsonObjectOrNull("data")?.jsonObjectOrNull("part")
            val type = json.stringOrNull("type")
                ?: json.stringOrNull("event")
                ?: nestedPart?.stringOrNull("type")
                ?: return
            val partType = nestedPart?.stringOrNull("type")
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
                "text", "message", "content", "delta" -> if (content != null) appendText(content)
                "message.part.updated", "message.part.delta" -> {
                    // OpenCode emits several part kinds (tool, reasoning, step-finish). Only
                    // a completed text part belongs in the visible assistant answer.
                    if (partType == "text" && content != null) appendText(content)
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
            }
        }

        drainCliStdout(process, cliFirstOutputTimeoutSeconds(connection.requestTimeoutSeconds)) { chunk ->
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

        if (process.exitValue() != 0 && text.isEmpty()) {
            throw ProviderException(cliExitFailureMessage(process.exitValue(), stderr()))
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
        stderr: () -> String,
    ): ModelResponse {
        val output = StringBuilder()
        drainCliStdout(process, cliFirstOutputTimeoutSeconds(connection.requestTimeoutSeconds)) { chunk ->
            if (output.length + chunk.length > MAX_CLI_OUTPUT_CHARS) {
                throw ProviderException("${connection.preset.displayName} 输出超过安全上限。")
            }
            output.append(chunk)
            onTextDelta(chunk)
        }
        val responseText = output.toString().trim()
        if (process.exitValue() != 0 && responseText.isEmpty()) {
            throw ProviderException(cliExitFailureMessage(process.exitValue(), stderr()))
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
        firstOutputTimeoutSeconds: Long,
        onChunk: suspend (String) -> Unit,
    ) {
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(CLI_OUTPUT_BUFFER_CHARS)
            val firstOutputDeadline = System.nanoTime() + firstOutputTimeoutSeconds * 1_000_000_000L
            var receivedOutput = false
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                var readAny = false
                while (reader.ready()) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    readAny = true
                    receivedOutput = true
                    onChunk(String(buffer, 0, count))
                }
                if (!receivedOutput && System.nanoTime() >= firstOutputDeadline) {
                    throw ProviderException(
                        "${connection.preset.displayName} 在 ${firstOutputTimeoutSeconds} 秒内没有返回任何输出。" +
                            "已停止本次请求；请检查 CLI 登录/供应商状态，或换用可用模型后重试。",
                        networkFailure = true,
                        retryableOverride = false,
                    )
                }
                if (!readAny) delay(CLI_OUTPUT_POLL_MILLIS)
            }
            // After process exit, stdout has reached EOF so this final drain cannot wait for a
            // future producer. It preserves a trailing line that did not end in a newline.
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count <= 0) break
                onChunk(String(buffer, 0, count))
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

    private fun cliExitFailureMessage(exitCode: Int, stderr: String): String {
        val normalized = stderr.lowercase()
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
    private fun cliConversationText(request: ModelRequest): String {
        val history = request.messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(MAX_CLI_HISTORY_MESSAGES)
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
        return "当前已在用户打开的项目根目录中运行。请只处理以下对话请求：\n\n$visible"
    }
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

/** The first byte has its own bound so a free/queued CLI model cannot look live indefinitely. */
internal fun cliFirstOutputTimeoutSeconds(totalTimeoutSeconds: Long): Long =
    minOf(totalTimeoutSeconds.coerceIn(10L, MAX_CLI_TOTAL_TIMEOUT_SECONDS), CLI_FIRST_OUTPUT_TIMEOUT_SECONDS)

/**
 * Only inject a saved key into the environment name the selected CLI/model understands. This
 * avoids the old Pi behavior that always placed an OpenAI or Gemini key in ANTHROPIC_API_KEY.
 */
internal fun cliCredentialEnvironmentVariables(tool: CliTool, model: String?): Set<String> = when (tool) {
    CliTool.KIMI -> linkedSetOf("KIMI_API_KEY", "MOONSHOT_API_KEY")
    CliTool.GROK -> setOf("XAI_API_KEY")
    CliTool.QODER -> setOf("QODER_PERSONAL_ACCESS_TOKEN")
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

private class BoundedCliStderr {
    private val output = StringBuilder()

    fun collect(stream: java.io.InputStream) {
        stream.bufferedReader().use { reader ->
            val buffer = CharArray(1_024)
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                synchronized(output) {
                    if (output.length < MAX_CLI_STDERR_CHARS) {
                        output.append(buffer, 0, minOf(count, MAX_CLI_STDERR_CHARS - output.length))
                    }
                }
            }
        }
    }

    fun snapshot(): String = synchronized(output) { output.toString() }
}

private suspend fun readBoundedProcessOutput(process: Process, maxCharacters: Int): String {
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
        while (true) {
            val count = reader.read(buffer)
            if (count <= 0) break
            if (output.length + count > maxCharacters) break
            output.append(buffer, 0, count)
        }
    }
    return output.toString()
}

/** Compact visible dialogue is sent to a CLI; it already has the opened project as cwd. */
private const val MAX_CLI_PROMPT_CHARS = 12_000
private const val MAX_CLI_MESSAGE_CHARS = 4_000
private const val MAX_CLI_HISTORY_MESSAGES = 8
private const val MAX_CLI_OUTPUT_CHARS = 1_000_000
private const val MAX_CLI_JSON_LINE_CHARS = 256_000
private const val MAX_CLI_STDERR_CHARS = 4_096
private const val CLI_OUTPUT_BUFFER_CHARS = 8_192
private const val CLI_OUTPUT_POLL_MILLIS = 25L
private const val CLI_PROCESS_EXIT_GRACE_MILLIS = 500L
private const val CLI_FIRST_OUTPUT_TIMEOUT_SECONDS = 45L
private const val MAX_CLI_TOTAL_TIMEOUT_SECONDS = 3_600L
private const val CLI_RUNTIME_PROBE_TIMEOUT_MILLIS = 8_000L
private const val CLI_RUNTIME_PROBE_MAX_CHARS = 4_096
