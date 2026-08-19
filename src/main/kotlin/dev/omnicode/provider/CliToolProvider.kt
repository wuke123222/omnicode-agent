package dev.omnicode.provider

import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Describes one CLI-based coding agent and how to invoke it.
 */
internal enum class CliTool(
    val executableNames: List<String>,
    val buildArgs: (prompt: String, model: String?) -> List<String>,
    val supportsJsonOutput: Boolean,
    val supportsStreamJson: Boolean,
    val supportsModelArgument: Boolean,
    val suggestedModels: List<String>,
    /** Read-only argv that prints one available model id per line, or null when unsupported. */
    val modelListArgs: List<String>? = null,
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
                // Verified against opencode 1.18.18: `run` accepts --format default|json;
                // unknown flags such as --output-format make yargs exit 1 with usage on stderr.
                add("--format"); add("json")
            }
        },
        supportsJsonOutput = true,
        supportsStreamJson = true,
        supportsModelArgument = true,
        suggestedModels = listOf("default"),
        modelListArgs = listOf("models"),
    ),
    KIMI(
        executableNames = listOf("kimi"),
        buildArgs = { prompt, _ ->
            listOf("-p", prompt)
        },
        supportsJsonOutput = false,
        supportsStreamJson = false,
        supportsModelArgument = false,
        suggestedModels = listOf("kimi-k2"),
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
        supportsModelArgument = true,
        suggestedModels = listOf("grok-build-0.1"),
    ),
    PI(
        executableNames = listOf("pi"),
        buildArgs = { prompt, _ ->
            listOf("-p", prompt)
        },
        supportsJsonOutput = false,
        supportsStreamJson = false,
        supportsModelArgument = false,
        suggestedModels = listOf("default"),
    ),
    QODER(
        executableNames = listOf("qodercli", "qoder"),
        buildArgs = { prompt, model ->
            buildList {
                add("--print")
                add("-p"); add(prompt)
                add("--output-format"); add("stream-json")
                add("--yolo")
                if (!model.isNullOrBlank() && model != "default") {
                    add("--model"); add(model)
                }
            }
        },
        supportsJsonOutput = true,
        supportsStreamJson = true,
        supportsModelArgument = true,
        suggestedModels = listOf("default"),
    ),
}

/** Stable settings-profile id for a CLI tool, matching the presets in [ProviderPresets]. */
internal fun CliTool.cliProviderId(): String = when (this) {
    CliTool.OPENCODE -> "cli-opencode"
    CliTool.KIMI -> "cli-kimi"
    CliTool.GROK -> "cli-grok"
    CliTool.PI -> "cli-pi"
    CliTool.QODER -> "cli-qoder"
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
        for (directory in searchDirectories()) {
            for (candidateName in names) {
                File(directory, candidateName).takeIf { it.isFile && it.canExecute() }?.let { return it }
            }
        }
        return null
    }

    /**
     * PATH value for launching a CLI child process. IDEs started from Finder/Dock inherit a
     * minimal PATH, so wrapper scripts with `#!/usr/bin/env node` fail with
     * "env: node: No such file or directory" even when the wrapper itself was found. Prepend the
     * executable's own directory (node usually lives next to npm-installed wrappers) and append
     * the same well-known per-user and package-manager directories used for discovery.
     */
    fun launchPath(executable: File): String {
        val directories = linkedSetOf<String>().apply {
            executable.parentFile?.absolutePath?.let(::add)
            addAll(searchDirectories())
        }
        return directories.joinToString(File.pathSeparator)
    }

    private fun searchDirectories(): List<String> = linkedSetOf<String>().apply {
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

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")
}

/**
 * Lists model ids from a CLI's read-only model command with bounded output and runtime.
 * Only tools that declare [CliTool.modelListArgs] participate; everything else returns empty.
 */
internal object CliModelDiscovery {
    private const val MAX_RAW_LINES = 500
    private const val MAX_MODELS = 200
    private const val TIMEOUT_SECONDS = 20L

    fun listModels(tool: CliTool, explicitPath: String? = null): List<String> {
        val args = tool.modelListArgs ?: return emptyList()
        val executable = CliToolDiscovery.resolveExecutable(tool, explicitPath) ?: return emptyList()
        val process = try {
            ProcessBuilder(listOf(executable.absolutePath) + args)
                .redirectErrorStream(false)
                .apply { environment()["PATH"] = CliToolDiscovery.launchPath(executable) }
                .start()
        } catch (_: IOException) {
            return emptyList()
        }
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { sequence ->
                    sequence.forEach { line ->
                        if (lines.size < MAX_RAW_LINES) lines.add(line)
                    }
                }
            }
        }.apply {
            name = "omnicode-cli-${tool.name.lowercase()}-models"
            isDaemon = true
            start()
        }
        Thread {
            runCatching { process.errorStream.bufferedReader().useLines { it.forEach { /* discard */ } } }
        }.apply {
            name = "omnicode-cli-${tool.name.lowercase()}-models-stderr"
            isDaemon = true
            start()
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return emptyList()
        }
        reader.join(2_000)
        return synchronized(lines) { lines.toList() }
            .mapNotNull(::normalizeCliModelLine)
            .distinct()
            .take(MAX_MODELS)
    }
}

/** Accepts plain model-id lines and drops headers, prose, and control characters. */
internal fun normalizeCliModelLine(line: String): String? {
    val value = line.trim()
    if (value.isEmpty() || value.length > 128) return null
    if (!CLI_MODEL_LINE.matches(value)) return null
    return value
}

private val CLI_MODEL_LINE = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]*$")

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
    ): ModelResponse = withContext(Dispatchers.IO) {
        val timeoutSeconds = connection.requestTimeoutSeconds.takeIf { it > 0 }
            ?.coerceIn(10, 3_600)
            ?: DEFAULT_CLI_TIMEOUT_SECONDS
        val launched = startCliProcess(request)
        val timedOut = AtomicBoolean(false)
        // Pipe reads on the parser thread do not respond to coroutine timeouts or thread
        // interruption. The watchdog kills the subprocess on deadline, and its finally block
        // also kills it when this scope is cancelled (user pressed stop), which closes the
        // pipes and reliably unblocks the parser.
        val watchdog = launch {
            try {
                delay(timeoutSeconds * 1_000L)
                timedOut.set(true)
            } finally {
                launched.process.destroyForcibly()
            }
        }
        try {
            val response = runInterruptible {
                if (cliTool.supportsStreamJson) {
                    parseStreamJsonOutput(launched.process, onTextDelta, launched.stderrTail)
                } else {
                    parsePlainTextOutput(launched.process, onTextDelta, launched.stderrTail)
                }
            }
            if (timedOut.get()) throw cliTimeoutException(timeoutSeconds, cause = null)
            response
        } catch (error: ProviderException) {
            currentCoroutineContext().ensureActive()
            if (timedOut.get()) throw cliTimeoutException(timeoutSeconds, cause = error)
            throw error
        } finally {
            watchdog.cancel()
            try {
                if (launched.process.isAlive) {
                    launched.process.destroy()
                    launched.process.waitFor(5, TimeUnit.SECONDS)
                    if (launched.process.isAlive) launched.process.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    private fun cliTimeoutException(timeoutSeconds: Long, cause: Throwable?): ProviderException =
        ProviderException(
            "${connection.preset.displayName} 超过 $timeoutSeconds 秒未完成请求，已终止 CLI 进程。" +
                "可在供应商设置中调大请求超时。",
            networkFailure = true,
            cause = cause,
        )

    private class LaunchedCliProcess(
        val process: Process,
        val stderrTail: () -> String,
    )

    private fun startCliProcess(request: ModelRequest): LaunchedCliProcess {
        val executable = CliToolDiscovery.resolveExecutable(cliTool, connection.baseUrl)
            ?: throw ProviderException(
                "找不到 ${connection.preset.displayName} 的可执行文件。" +
                    "请安装后重试，或在供应商设置中配置可执行文件路径。" +
                    " 尝试的名称：${cliTool.executableNames.joinToString(", ")}",
            )

        val prompt = conversationText(request)
        val args = cliTool.buildArgs(prompt, connection.model)
        // Run in the project root: coding CLIs treat the working directory as the workspace
        // (OpenCode even snapshots it), so the IDE process directory is both wrong and slow.
        val workDir = connection.workingDirectory
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.dir", "."))

        val processBuilder = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(false)
        processBuilder.environment()["PATH"] = CliToolDiscovery.launchPath(executable)

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

        // Keep a bounded stderr tail for diagnostics (never forwarded to the model). A discarded
        // stderr made failures like an unknown CLI flag surface only as "exit 1, no output".
        val stderrTail = StringBuilder()
        Thread {
            runCatching {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrTail) {
                            stderrTail.append(line).append('\n')
                            if (stderrTail.length > MAX_STDERR_TAIL_CHARS * 2) {
                                stderrTail.delete(0, stderrTail.length - MAX_STDERR_TAIL_CHARS)
                            }
                        }
                    }
                }
            }
        }.apply {
            name = "omnicode-cli-${cliTool.name.lowercase()}-stderr"
            isDaemon = true
            start()
        }
        return LaunchedCliProcess(process) {
            synchronized(stderrTail) { stderrTail.toString().trim().takeLast(MAX_STDERR_TAIL_CHARS) }
        }
    }

    /**
     * Parse line-delimited JSON streaming output (OpenCode, Qoder).
     * Each line is a JSON object with a "type" field indicating the event kind.
     */
    private fun parseStreamJsonOutput(
        process: Process,
        onTextDelta: suspend (String) -> Unit,
        stderrTail: () -> String,
    ): ModelResponse {
        val reader = process.inputStream.bufferedReader()
        val text = StringBuilder()
        var tokenUsage = TokenUsage()
        var stopReason = StopReason.UNKNOWN

        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@forEachLine
            val json = runCatching { Json.parseObject(trimmed) }.getOrNull() ?: return@forEachLine

            val type = json.stringOrNull("type") ?: json.stringOrNull("event") ?: return@forEachLine
            // OpenCode nests the payload under "part" (e.g. {"type":"text","part":{"text":…}});
            // Claude Code style CLIs put content at the top level. Accept both.
            val part = json.jsonObjectOrNull("part")
            when (type) {
                "text", "message", "content", "delta" -> {
                    val content = json.stringOrNull("content")
                        ?: json.stringOrNull("text")
                        ?: json.stringOrNull("delta")
                        ?: part?.stringOrNull("text")
                        ?: return@forEachLine
                    if (content.isNotBlank()) {
                        text.append(content)
                        // Note: onTextDelta is a suspend function but forEachLine is not;
                        // we buffer and emit after the process completes.
                    }
                }
                "usage" -> {
                    val promptTokens = json.longOrZero("input_tokens").takeIf { it > 0 }
                        ?: json.longOrZero("prompt_tokens")
                    val completionTokens = json.longOrZero("output_tokens").takeIf { it > 0 }
                        ?: json.longOrZero("completion_tokens")
                    tokenUsage = TokenUsage(promptTokens, completionTokens)
                }
                "step_finish", "step-finish" -> {
                    part?.jsonObjectOrNull("tokens")?.let { tokens ->
                        tokenUsage = TokenUsage(tokens.longOrZero("input"), tokens.longOrZero("output"))
                    }
                    if (part?.stringOrNull("reason") == "stop") stopReason = StopReason.COMPLETE
                }
                "complete", "done", "end", "finish", "stop" -> {
                    stopReason = StopReason.COMPLETE
                }
                "error" -> {
                    val message = json.stringOrNull("message")
                        ?: json.stringOrNull("error")
                        ?: part?.stringOrNull("message")
                        ?: "CLI error"
                    throw ProviderException("${connection.preset.displayName}: $message")
                }
            }
        }

        val exitCode = process.waitFor(30, TimeUnit.SECONDS)
        if (exitCode && process.exitValue() != 0 && text.isEmpty()) {
            throw ProviderException(cliExitFailureMessage(process.exitValue(), stderrTail()))
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
     * Parse plain text output (Kimi, Grok, Pi).
     * Reads all stdout, emits via onTextDelta after process completes.
     */
    private fun parsePlainTextOutput(
        process: Process,
        onTextDelta: suspend (String) -> Unit,
        stderrTail: () -> String,
    ): ModelResponse {
        val reader = process.inputStream.bufferedReader()
        val output = reader.readText().trim()

        val exitCode = process.waitFor(30, TimeUnit.SECONDS)
        if (exitCode && process.exitValue() != 0 && output.isEmpty()) {
            throw ProviderException(cliExitFailureMessage(process.exitValue(), stderrTail()))
        }

        // For plain text output, we consider the whole output as the response.
        // CLI tools typically only output the final answer in print mode.
        return ModelResponse(
            blocks = listOfNotNull(output.takeIf { it.isNotBlank() }?.let(ContentBlock::Text)),
            usage = TokenUsage(),
            stopReason = if (output.isNotBlank()) StopReason.COMPLETE else StopReason.UNKNOWN,
        )
    }

    private fun cliExitFailureMessage(exitValue: Int, stderr: String): String = buildString {
        append("${connection.preset.displayName} 退出码 $exitValue，未产生输出。")
        if (stderr.isNotBlank()) {
            append("\nCLI 错误输出（截断）：\n")
            append(stderr)
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

/** Bounded stderr tail kept for diagnostics when a CLI exits without stdout. */
private const val MAX_STDERR_TAIL_CHARS = 2_000

/** CLI coding agents legitimately run for minutes; used when no explicit timeout is configured. */
private const val DEFAULT_CLI_TIMEOUT_SECONDS = 600L
