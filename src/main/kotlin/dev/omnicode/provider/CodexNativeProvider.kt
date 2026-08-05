package dev.omnicode.provider

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.settings.SandboxMode
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** Context required to invoke Codex's native local app-server safely from a project run. */
data class CodexNativeExecutionContext(
    val project: Project,
    val workingDirectory: Path,
    val approvalGate: ApprovalGate,
    val mode: AgentMode,
    val sandboxMode: SandboxMode,
)

/** Creates the hidden backend connection used for Team specialists. It is never shown as a model. */
internal fun codexNativeSubagentConnection(template: ProviderConnection): ProviderConnection =
    template.copy(
        preset = ProviderPresets.codexNativeSubagent,
        baseUrl = ProviderPresets.codexNativeSubagent.defaultBaseUrl,
        model = ProviderPresets.codexNativeSubagent.defaultModel,
        apiKey = "",
    )

/**
 * Provider adapter for the native Codex app-server.
 *
 * This is deliberately used as an internal Codex subagent backend. It does not fall back to an
 * HTTP API or to `codex exec`: the local app-server owns the native thread/turn/tool loop, while
 * approval requests are routed back through OmniCode's existing approval dialog. The process is
 * short-lived per provider turn so a cancelled IDE task cannot leave a hidden native session.
 */
class CodexNativeProvider(
    private val connection: ProviderConnection,
    private val context: CodexNativeExecutionContext,
) : ModelProvider {
    override val id: String = connection.preset.id

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse = withContext(Dispatchers.IO) {
        try {
            withTimeout(connection.requestTimeoutSeconds.coerceIn(5, 1_800) * 1_000L) {
                CodexNativeSession(connection, context).run(request, onTextDelta)
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ProviderException(
                "Codex 原生 App Server 超过 ${connection.requestTimeoutSeconds} 秒未完成请求。",
                networkFailure = true,
                cause = timeout,
            )
        }
    }
}

/** Codex app-server model discovery is local to the user's authenticated Codex installation. */
internal object CodexNativeModelDiscovery {
    suspend fun discover(connection: ProviderConnection): ModelDiscoveryResult = ModelDiscoveryResult(
        models = listOf(connection.model).filter(String::isNotBlank),
        discoveredRemotely = false,
        status = "Codex 原生模型由本机 Codex 登录和配置管理；保留当前模型入口。",
    )
}

private class CodexNativeSession(
    private val connection: ProviderConnection,
    private val context: CodexNativeExecutionContext,
) {
    private val nextId = AtomicLong(0)
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private var terminalTurnSeen = false
    private var responseText = StringBuilder()
    private var usage = TokenUsage()
    private var turnId: String? = null

    suspend fun run(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        try {
            startProcess()
            initialize()
            val thread = request("thread/start", threadParams(request))
            val threadId = thread.jsonObjectOrNull("thread")?.stringOrNull("id")
                ?: thread.stringOrNull("threadId")
                ?: throw ProviderException("Codex 原生 App Server 未返回 thread id")
            request(
                "turn/start",
                turnParams(threadId, request),
                waitForTurn = true,
                onTextDelta = onTextDelta,
            )
            val stopReason = when {
                terminalTurnSeen -> StopReason.COMPLETE
                responseText.isNotEmpty() -> StopReason.COMPLETE
                else -> StopReason.UNKNOWN
            }
            return ModelResponse(
                blocks = listOfNotNull(responseText.toString().takeIf { it.isNotBlank() }?.let(ContentBlock::Text)),
                usage = usage,
                stopReason = stopReason,
                providerRequestId = turnId,
            )
        } finally {
            closeProcess()
        }
    }

    private fun startProcess() {
        val failures = mutableListOf<String>()
        val candidates = CodexExecutable.candidates()
        for (candidate in candidates) {
            try {
                val builder = ProcessBuilder(candidate, "app-server", "--stdio")
                    .directory(context.workingDirectory.toFile())
                val started = builder.start()
                process = started
                input = started.outputStream.bufferedWriter()
                output = started.inputStream.bufferedReader()
                // Keep stderr drained without ever forwarding it to the model transcript.
                Thread {
                    runCatching { started.errorStream.bufferedReader().useLines { it.forEach { _ -> } } }
                }.apply {
                    name = "omnicode-codex-stderr"
                    isDaemon = true
                    start()
                }
                return
            } catch (error: IOException) {
                failures += "$candidate: ${error.message.orEmpty().take(200)}"
            }
        }
        throw ProviderException(
            "找不到可用的 Codex 原生 App Server。请安装并登录 Codex，或设置 OMNICODE_CODEX_PATH。" +
                " 尝试：${failures.joinToString("; ")}".take(2_000),
        )
    }

    private suspend fun initialize() {
        request(
            "initialize",
            JsonObject().apply {
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "omnicode-agent")
                    addProperty("title", "OmniCode Agent")
                    addProperty("version", CLIENT_VERSION)
                })
            },
        )
        sendNotification("initialized", JsonObject())
    }

    private fun threadParams(request: ModelRequest): JsonObject = JsonObject().apply {
        addProperty("cwd", context.workingDirectory.toAbsolutePath().normalize().toString())
        addProperty("ephemeral", true)
        addProperty("approvalPolicy", if (context.mode == AgentMode.AGENT) "on-request" else "never")
        addProperty("sandbox", sandboxMode())
        val model = connection.model.trim().takeUnless { it.isBlank() || it == "codex-default" }
        if (model != null) addProperty("model", model)
        val instructions = request.messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { message -> message.blocks.mapNotNull { (it as? ContentBlock.Text)?.text } }
            .joinToString("\n\n")
            .take(MAX_INSTRUCTIONS_CHARS)
        if (instructions.isNotBlank()) {
            addProperty(
                "baseInstructions",
                "$instructions\n\nYou are the native Codex execution backend inside OmniCode. Keep all work inside the selected workspace.",
            )
        }
    }

    private fun turnParams(threadId: String, request: ModelRequest): JsonObject = JsonObject().apply {
        addProperty("threadId", threadId)
        add("input", turnInput(request))
        connection.requireReasoningResolution().wireValue?.let { addProperty("effort", it) }
    }

    /** Maps the provider-neutral request to native UserInput without exposing unbounded data. */
    private fun turnInput(request: ModelRequest): com.google.gson.JsonArray =
        com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", conversationText(request).take(MAX_PROMPT_CHARS))
            })
            request.messages.asSequence()
                .flatMap { it.blocks.asSequence() }
                .filterIsInstance<ContentBlock.Image>()
                .filter { it.base64Data.length <= MAX_IMAGE_BASE64_CHARS }
                .take(MAX_IMAGES_PER_TURN)
                .forEach { image ->
                    add(JsonObject().apply {
                        addProperty("type", "image")
                        addProperty(
                            "url",
                            "data:${image.mediaType};base64,${image.base64Data}",
                        )
                    })
                }
        }

    private fun conversationText(request: ModelRequest): String = buildString {
        request.messages
            .filter { it.role != MessageRole.SYSTEM }
            .forEach { message ->
                val label = message.role.name.lowercase()
                val text = message.blocks.mapNotNull { block ->
                    when (block) {
                        is ContentBlock.Text -> block.text
                        is ContentBlock.TransientProjectContext -> block.text
                        is ContentBlock.ToolResult -> "[tool result] ${block.content}"
                        is ContentBlock.ToolCall -> "[tool call] ${block.name} ${Json.stringify(block.arguments)}"
                        is ContentBlock.Image -> "[image attachment: ${block.fileName}]"
                    }
                }.joinToString("\n")
                if (text.isNotBlank()) append("[$label]\n$text\n\n")
            }
    }.trim()

    private fun sandboxMode(): String = when {
        context.mode != AgentMode.AGENT -> "read-only"
        context.sandboxMode == SandboxMode.DANGER_FULL_ACCESS -> "danger-full-access"
        else -> "workspace-write"
    }

    private suspend fun request(
        method: String,
        params: JsonObject,
        waitForTurn: Boolean = false,
        onTextDelta: suspend (String) -> Unit = {},
    ): JsonObject {
        val id = nextId.incrementAndGet().toString()
        sendRequest(id, method, params)
        var result: JsonObject? = null
        while (true) {
            val line = output?.readLine() ?: throw ProviderException(
                "Codex 原生 App Server 在 $method 期间提前退出。",
                networkFailure = true,
            )
            val message = runCatching { Json.parseObject(line) }.getOrElse {
                throw ProviderException("Codex 原生 App Server 返回了无效 JSON。", cause = it)
            }
            val messageId = message.get("id")?.takeUnless { it.isJsonNull }?.toString()?.trim('"')
            // JSON-RPC permits the two directions to use overlapping ids. A server approval
            // request has an id too, but is not a response until it carries result or error.
            if (messageId == id && (message.has("result") || message.has("error"))) {
                val error = message.jsonObjectOrNull("error")
                if (error != null) {
                    throw ProviderException(
                        "Codex 原生 App Server 请求 $method 失败：${error.stringOrNull("message") ?: "unknown error"}",
                    )
                }
                result = message.jsonObjectOrNull("result") ?: JsonObject()
                if (!waitForTurn) return result
                if (terminalTurnSeen) return result
                continue
            }
            handleMessage(message, onTextDelta)
            if (waitForTurn && terminalTurnSeen && result != null) return result
        }
    }

    private suspend fun handleMessage(message: JsonObject, onTextDelta: suspend (String) -> Unit) {
        val method = message.stringOrNull("method") ?: return
        val params = message.jsonObjectOrNull("params") ?: JsonObject()
        when {
            method == "item/agentMessage/delta" -> params.stringOrNull("delta")?.let {
                responseText.append(it)
                onTextDelta(it)
            }
            method == "thread/tokenUsage/updated" -> usage = usageFrom(params)
            method == "turn/completed" -> {
                terminalTurnSeen = true
                val turn = params.jsonObjectOrNull("turn")
                turnId = turn?.stringOrNull("id") ?: turnId
                usage = usageFrom(turn ?: params).takeIf { it.inputTokens > 0 || it.outputTokens > 0 } ?: usage
                val status = turn?.stringOrNull("status")
                if (status != null && status !in setOf("completed", "complete")) {
                    throw ProviderException("Codex 原生 turn 结束状态：$status")
                }
            }
            method == "error" -> throw ProviderException(
                "Codex 原生 App Server 错误：${params.stringOrNull("message") ?: Json.stringify(params).take(1_000)}",
            )
            message.get("id") != null -> handleServerRequest(message, method, params)
        }
    }

    private suspend fun handleServerRequest(message: JsonObject, method: String, params: JsonObject) {
        val id = message.get("id") ?: return
        val accepted = when (method) {
            "item/commandExecution/requestApproval" -> context.approvalGate.approve(
                ApprovalRequest(
                    toolName = "codex.command",
                    title = "Codex 原生请求执行命令",
                    details = listOfNotNull(
                        params.stringOrNull("command")?.let { "命令：$it" },
                        params.stringOrNull("cwd")?.let { "工作目录：$it" },
                        params.stringOrNull("reason")?.let { "原因：$it" },
                    ).joinToString("\n").take(4_000),
                    risk = "Codex 原生工具将在本机沙箱中执行；仍受当前沙箱模式限制。",
                ),
            )
            "item/fileChange/requestApproval" -> context.approvalGate.approve(
                ApprovalRequest(
                    toolName = "codex.file_change",
                    title = "Codex 原生请求修改文件",
                    details = listOfNotNull(
                        params.stringOrNull("grantRoot")?.let { "授权目录：$it" },
                        params.stringOrNull("reason")?.let { "原因：$it" },
                    ).joinToString("\n").take(4_000),
                    risk = "文件修改会进入当前任务的变更审阅流程；请确认目录和目的。",
                ),
            )
            else -> false
        }
        val response = JsonObject().apply {
            addProperty("decision", if (accepted) "accept" else "decline")
        }
        sendResponse(id, response)
    }

    private fun usageFrom(payload: JsonObject): TokenUsage {
        val usageObject = payload.jsonObjectOrNull("usage") ?: payload.jsonObjectOrNull("tokenUsage") ?: payload
        return TokenUsage(
            inputTokens = usageObject.longOrZero("inputTokens").takeIf { it > 0 }
                ?: usageObject.longOrZero("input_tokens"),
            outputTokens = usageObject.longOrZero("outputTokens").takeIf { it > 0 }
                ?: usageObject.longOrZero("output_tokens"),
        )
    }

    private fun sendRequest(id: String, method: String, params: JsonObject) {
        val message = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        write(message)
    }

    private fun sendResponse(id: com.google.gson.JsonElement, result: JsonObject) {
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        })
    }

    private fun sendNotification(method: String, params: JsonObject) {
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        })
    }

    private fun write(message: JsonObject) {
        val writer = input ?: throw ProviderException("Codex 原生 App Server stdin 不可用")
        writer.write(Json.stringify(message))
        writer.newLine()
        writer.flush()
    }

    private fun closeProcess() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        process?.let { runCatching { it.destroy() }; runCatching { if (it.isAlive) it.destroyForcibly() } }
        input = null
        output = null
        process = null
    }

    private object CodexExecutable {
        fun candidates(): List<String> = buildList {
            System.getenv("OMNICODE_CODEX_PATH")?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            add("codex")
            add("codex.exe")
            add("/Applications/ChatGPT.app/Contents/Resources/codex")
        }.distinct()
    }

    private companion object {
        const val CLIENT_VERSION = "1.6.2"
        const val MAX_PROMPT_CHARS = 120_000
        const val MAX_INSTRUCTIONS_CHARS = 24_000
        const val MAX_IMAGES_PER_TURN = 4
        const val MAX_IMAGE_BASE64_CHARS = 12 * 1024 * 1024
    }
}
