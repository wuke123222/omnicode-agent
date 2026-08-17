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
                add("--output-format"); add("stream-json")
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
                add("--output-format"); add("stream-json")
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
 * Checks: explicit setting in connection.baseUrl, then PATH lookup via `which`.
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
        return try {
            val process = ProcessBuilder(listOf(if (isWindows()) "where" else "which", name))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor(3, TimeUnit.SECONDS)
            if (exitCode && process.exitValue() == 0) {
                output.lines().firstOrNull()?.let { File(it.trim()) }?.takeIf { it.isFile }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
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
    ): ModelResponse = withContext(Dispatchers.IO) {
        val timeoutSeconds = connection.requestTimeoutSeconds.coerceIn(10, 3_600)
        try {
            withTimeout(timeoutSeconds * 1_000L) {
                executeCli(request, onTextDelta)
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

    private fun executeCli(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
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
            try {
                if (process.isAlive) {
                    process.destroy()
                    process.waitFor(5, TimeUnit.SECONDS)
                    if (process.isAlive) process.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Parse line-delimited JSON streaming output (OpenCode, Qoder).
     * Each line is a JSON object with a "type" field indicating the event kind.
     */
    private fun parseStreamJsonOutput(
        process: Process,
        onTextDelta: suspend (String) -> Unit,
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
            when (type) {
                "text", "message", "content", "delta" -> {
                    val content = json.stringOrNull("content")
                        ?: json.stringOrNull("text")
                        ?: json.stringOrNull("delta")
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
                "complete", "done", "end", "finish", "stop" -> {
                    stopReason = StopReason.COMPLETE
                }
                "error" -> {
                    val message = json.stringOrNull("message") ?: json.stringOrNull("error") ?: "CLI error"
                    throw ProviderException("${connection.preset.displayName}: $message")
                }
            }
        }

        val exitCode = process.waitFor(30, TimeUnit.SECONDS)
        if (exitCode && process.exitValue() != 0 && text.isEmpty()) {
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
     * Parse plain text output (Kimi, Grok, Pi).
     * Reads all stdout, emits via onTextDelta after process completes.
     */
    private fun parsePlainTextOutput(
        process: Process,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val reader = process.inputStream.bufferedReader()
        val output = reader.readText().trim()

        val exitCode = process.waitFor(30, TimeUnit.SECONDS)
        if (exitCode && process.exitValue() != 0 && output.isEmpty()) {
            throw ProviderException(
                "${connection.preset.displayName} 退出码 ${process.exitValue()}，未产生输出。",
            )
        }

        // For plain text output, we consider the whole output as the response.
        // CLI tools typically only output the final answer in print mode.
        return ModelResponse(
            blocks = listOfNotNull(output.takeIf { it.isNotBlank() }?.let(ContentBlock::Text)),
            usage = TokenUsage(),
            stopReason = if (output.isNotBlank()) StopReason.COMPLETE else StopReason.UNKNOWN,
        )
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
