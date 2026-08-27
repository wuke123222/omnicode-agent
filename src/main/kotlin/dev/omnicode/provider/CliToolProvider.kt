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
        buildArgs = { prompt, _ ->
            listOf("-p", prompt)
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
        buildArgs = { prompt, _ ->
            listOf("-p", prompt)
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
                add("--format"); add("json")
                add("--yolo")
                if (!model.isNullOrBlank() && model != "default") {
                    add("--model"); add(model)
                }
            }
        },
        supportsJsonOutput = true,
        supportsStreamJson = true,
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

    private fun findInPath(name: String): File? {
        val names = if (isWindows()) listOf(name, "$name.exe", "$name.cmd") else listOf(name)
        val directories = linkedSetOf<String>().apply {
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { add(it) }
            val home = System.getProperty("user.home").orEmpty()
            if (home.isNotBlank()) {
                add("$home/.local/bin")
                add("$home/.npm-global/bin")
                add("$home/.npm/bin")
                add("$home/bin")
            }
            if (isWindows()) {
                add(System.getenv("APPDATA").orEmpty() + "\\npm")
                add(System.getenv("LOCALAPPDATA").orEmpty() + "\\Programs\\nodejs")
            } else {
                add("/usr/local/bin")
                add("/opt/homebrew/bin")
                add("/opt/local/bin")
            }
        }.filter(String::isNotBlank)

        for (directory in directories) {
            for (candidateName in names) {
                File(directory, candidateName).takeIf { it.isFile && it.canExecute() }?.let { return it }
            }
        }
        return null
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")
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

        val prompt = conversationText(request)
        val args = cliTool.buildArgs(prompt, connection.model)
        val workDir = File(System.getProperty("user.dir", "."))

        val processBuilder = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(false)

        // Pass API key as environment variable if configured
        if (connection.apiKey.isNotBlank()) {
            val envVar = when (cliTool) {
                CliTool.OPENCODE -> "OPENCODE_API_KEY"
                CliTool.KIMI -> "MOONSHOT_API_KEY"
                CliTool.GROK -> "XAI_API_KEY"
                CliTool.PI -> "ANTHROPIC_API_KEY"
                CliTool.QODER -> "QODER_PERSONAL_ACCESS_TOKEN"
            }
            processBuilder.environment()[envVar] = connection.apiKey
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

        // Drain stderr in background (never forward to model)
        Thread {
            runCatching { process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { /* discard */ }
            } }
        }.apply {
            name = "omnicode-cli-${cliTool.name.lowercase()}-stderr"
            isDaemon = true
            start()
        }

        return try {
            if (cliTool.supportsStreamJson) {
                parseStreamJsonOutput(process, onTextDelta)
            } else {
                parsePlainTextOutput(process, onTextDelta)
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

        drainCliStdout(process) { chunk ->
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
            throw ProviderException(
                "${connection.preset.displayName} 退出码 ${process.exitValue()}，未产生输出。",
            )
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
    ): ModelResponse {
        val output = StringBuilder()
        drainCliStdout(process) { chunk ->
            if (output.length + chunk.length > MAX_CLI_OUTPUT_CHARS) {
                throw ProviderException("${connection.preset.displayName} 输出超过安全上限。")
            }
            output.append(chunk)
            onTextDelta(chunk)
        }
        val responseText = output.toString().trim()
        if (process.exitValue() != 0 && responseText.isEmpty()) {
            throw ProviderException(
                "${connection.preset.displayName} 退出码 ${process.exitValue()}，未产生输出。",
            )
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
        onChunk: suspend (String) -> Unit,
    ) {
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(CLI_OUTPUT_BUFFER_CHARS)
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                var readAny = false
                while (reader.ready()) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    readAny = true
                    onChunk(String(buffer, 0, count))
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

    private fun conversationText(request: ModelRequest): String = buildString {
        request.messages
            .filter { it.role != MessageRole.SYSTEM }
            .forEach { message ->
                val text = message.blocks.mapNotNull { block ->
                    when (block) {
                        is ContentBlock.Text -> block.text
                        is ContentBlock.TransientProjectContext -> block.text
                        is ContentBlock.ToolResult -> "[tool result] ${block.content}"
                        is ContentBlock.ToolCall -> "[tool call] ${block.name}"
                        is ContentBlock.Image -> "[image: ${block.fileName}]"
                    }
                }.joinToString("\n")
                if (text.isNotBlank()) {
                    if (message.role == MessageRole.ASSISTANT) {
                        append("Assistant: $text\n\n")
                    } else {
                        append("$text\n\n")
                    }
                }
            }
    }.trim().take(MAX_PROMPT_CHARS)
}

/** Maximum prompt characters sent to CLI tools to avoid argument-length limits. */
private const val MAX_PROMPT_CHARS = 30_000
private const val MAX_CLI_OUTPUT_CHARS = 1_000_000
private const val MAX_CLI_JSON_LINE_CHARS = 256_000
private const val CLI_OUTPUT_BUFFER_CHARS = 8_192
private const val CLI_OUTPUT_POLL_MILLIS = 25L
private const val CLI_PROCESS_EXIT_GRACE_MILLIS = 500L
