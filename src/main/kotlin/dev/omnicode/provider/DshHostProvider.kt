package dev.omnicode.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.util.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native DSH adapter for the persistent `dsh web` host.
 *
 * DSH is not a one-shot text CLI: a turn is scoped to a workspace/session and completes from the
 * mux event stream. The socket is subscribed before `session.prompt`, frames are filtered by the
 * exact session id, and host-minted approvals are routed through OmniCode's existing approval
 * gate. Interactive questions currently fail closed with an empty answer instead of parking a
 * background host request forever.
 */
internal class DshHostProvider(
    private val connection: ProviderConnection,
    private val workingDirectory: java.nio.file.Path?,
    private val localSession: LocalCliSessionContext?,
    private val approvalGate: ApprovalGate?,
    private val agentMode: AgentMode,
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
        val totalSeconds = connection.requestTimeoutSeconds.coerceIn(10, MAX_TURN_TIMEOUT_SECONDS)
        try {
            withTimeout(totalSeconds * 1_000L) {
                completeTurn(request, onTextDelta, onProgress)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProviderException) {
            throw error
        } catch (error: Throwable) {
            throw ProviderException(
                "DSH Host 请求失败：${safeFailure(error)}",
                networkFailure = error is IOException,
                retryableOverride = false,
                cause = error,
            )
        }
    }

    private suspend fun completeTurn(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit,
    ): ModelResponse {
        val workDir = CliToolLaunch.resolveWorkingDirectory(workingDirectory)
        val origin = dshOrigin(connection.baseUrl)
        val client = DshHostClient(origin, connection.requestTimeoutSeconds)
        DshHostSupervisor.ensureAvailable(client, workDir, connection.baseUrl, approvalGate, onProgress)

        val workspace = client.call("workspace.create", jsonObject("path" to workDir.absolutePath))
        val workspaceId = workspace.objectOrNull("workspace")?.stringOrNull("workspaceId")
            ?: throw ProviderException("DSH workspace.create 未返回 workspaceId。", retryableOverride = false)
        var sessionId = localSession?.resumeSessionId.orEmpty()
        if (sessionId.isBlank()) {
            val created = client.call("session.create", jsonObject("workspaceId" to workspaceId))
            sessionId = created.stringOrNull("sessionId")
                ?: throw ProviderException("DSH session.create 未返回 sessionId。", retryableOverride = false)
            if (!SAFE_DSH_SESSION_ID.matches(sessionId)) {
                throw ProviderException("DSH 返回了无效 sessionId。", retryableOverride = false)
            }
            localSession?.onSessionStarted?.invoke(sessionId)
        }

        selectModelIfExplicit(client, sessionId)
        onProgress("DSH 会话已就绪，正在订阅实时事件…")
        val listener = DshMuxListener()
        val socket = try {
            withTimeout(MUX_OPEN_TIMEOUT_MILLIS) {
                DSH_HTTP_CLIENT.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(MUX_OPEN_TIMEOUT_MILLIS))
                    .buildAsync(origin.muxUri, listener)
                    .awaitCancellable()
            }
        } catch (error: Throwable) {
            throw ProviderException(
                "DSH 实时事件连接失败：${safeFailure(error)}。Host 会话已保留，可修复连接后重试。",
                networkFailure = true,
                retryableOverride = false,
                cause = error,
            )
        }

        var promptAccepted = false
        var settled = false
        val output = StringBuilder()
        var inputTokens = 0L
        var outputTokens = 0L
        var terminalError: String? = null
        val settlement = DshGoalSettlement()
        try {
            val promptPayload = JsonObject().apply {
                addProperty("sessionId", sessionId)
                addProperty("mode", "queue")
                add("content", dshPromptContent(request))
            }
            val acknowledgement = client.call("session.prompt", promptPayload)
            if (acknowledgement.booleanOrNull("accepted") == false) {
                throw ProviderException(
                    "DSH 拒绝了本次请求：${acknowledgement.stringOrNull("reason") ?: "未知原因"}",
                    retryableOverride = false,
                )
            }
            promptAccepted = true
            onProgress("DSH 已接收任务，正在等待模型响应…")

            while (!settled) {
                currentCoroutineContext().ensureActive()
                when (val socketEvent = listener.events.receive()) {
                    is DshSocketEvent.Failure -> throw ProviderException(
                        "DSH 实时事件连接中断：${safeFailure(socketEvent.error)}。会话已保留，可直接重试。",
                        networkFailure = true,
                        retryableOverride = false,
                        cause = socketEvent.error,
                    )
                    DshSocketEvent.Closed -> throw ProviderException(
                        "DSH 实时事件连接在任务完成前关闭。会话已保留，可直接重试。",
                        networkFailure = true,
                        retryableOverride = false,
                    )
                    is DshSocketEvent.Text -> {
                        val raw = parseObjectOrNull(socketEvent.value) ?: continue
                        val projected = unwrapDshFrame(raw)
                        if (projected.sessionId != sessionId) continue
                        val frame = projected.frame
                        when (frame.stringOrNull("type")) {
                            "session/event" -> {
                                val event = frame.objectOrNull("event") ?: frame
                                val data = event.objectOrNull("data") ?: JsonObject()
                                when (event.stringOrNull("type")) {
                                    "turn/start" -> {
                                        settlement.turnStarted()
                                        onProgress("DSH 模型正在处理…")
                                    }
                                    "assistant/chunk" -> {
                                        val chunk = data.objectOrNull("chunk") ?: data
                                        when (chunk.stringOrNull("type")) {
                                            "text-delta" -> chunk.stringOrNull("text")?.takeIf(String::isNotEmpty)?.let { delta ->
                                                if (output.length + delta.length > MAX_DSH_OUTPUT_CHARS) {
                                                    throw ProviderException("DSH 输出超过安全上限。", retryableOverride = false)
                                                }
                                                output.append(delta)
                                                onTextDelta(delta)
                                            }
                                            "reasoning-delta" -> onProgress("DSH 正在推理…")
                                            "usage" -> {
                                                val usage = chunk.objectOrNull("usage") ?: chunk
                                                inputTokens = maxOf(inputTokens, usage.longFrom("uncachedInputTokens", "inputTokens", "input"))
                                                outputTokens = maxOf(outputTokens, usage.longFrom("outputTokens", "output"))
                                            }
                                        }
                                    }
                                    "tool/call" -> onProgress(
                                        "DSH 正在调用 ${data.stringOrNull("name")?.take(MAX_TOOL_NAME_CHARS) ?: "工具"}…",
                                    )
                                    "tool/result" -> onProgress("DSH 工具调用已返回。")
                                    "goal/change" -> if (settlement.goalChanged(data)) settled = true
                                    "turn/end" -> {
                                        val reason = data.objectOrNull("reason")
                                        val kind = reason?.stringOrNull("kind").orEmpty().lowercase()
                                        if (kind in FAILURE_TURN_END_KINDS) {
                                            terminalError = reason?.objectOrNull("error")?.stringOrNull("message")
                                                ?: reason?.objectOrNull("error")?.stringOrNull("code")
                                                ?: kind.ifBlank { "DSH turn failed" }
                                            settled = true
                                        } else if (settlement.turnCompleted()) {
                                            settled = true
                                        }
                                    }
                                }
                            }
                            "session/projection" -> if (frame.stringOrNull("key") == "tokenUsage") {
                                val usage = frame.objectOrNull("value") ?: JsonObject()
                                inputTokens = maxOf(inputTokens, usage.longFrom("uncachedInputTokens", "inputTokens", "input"))
                                outputTokens = maxOf(outputTokens, usage.longFrom("outputTokens", "output"))
                            }
                            "approval/requested" -> settleApproval(client, frame, projected.rpcId, sessionId, onProgress)
                            "question/requested" -> settleQuestion(client, projected.rpcId, sessionId, onProgress)
                        }
                    }
                }
            }
        } catch (error: DshRpcException) {
            if (localSession?.resumeSessionId != null && error.looksLikeInvalidSession()) {
                localSession.onSessionInvalid()
            }
            throw error.asProviderException()
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "turn finished")
            listener.events.close()
            if (promptAccepted && !settled) {
                withContext(NonCancellable) {
                    runCatching { client.call("session.cancel", jsonObject("sessionId" to sessionId), RPC_CANCEL_TIMEOUT_MILLIS) }
                }
            }
        }

        terminalError?.let { message ->
            throw ProviderException("DSH 任务失败：${message.take(MAX_ERROR_CHARS)}", retryableOverride = false)
        }
        return ModelResponse(
            blocks = listOf(ContentBlock.Text(output.toString())),
            usage = TokenUsage(inputTokens, outputTokens),
            stopReason = StopReason.COMPLETE,
        )
    }

    private suspend fun selectModelIfExplicit(client: DshHostClient, sessionId: String) {
        val model = connection.model.trim()
        if (model.isBlank() || model in setOf("default", "auto", "dsh-default")) return
        val slash = model.indexOf('/')
        if (slash <= 0 || slash == model.lastIndex) {
            throw ProviderException(
                "DSH 模型必须使用 provider/model 格式；请刷新模型列表后重新选择。",
                retryableOverride = false,
            )
        }
        client.call(
            "session.selectModel",
            JsonObject().apply {
                addProperty("sessionId", sessionId)
                addProperty("provider", model.substring(0, slash))
                addProperty("model", model.substring(slash + 1))
                connection.reasoningEffort.takeUnless { it == ReasoningEffort.AUTO }?.let {
                    addProperty("reasoningEffort", it.persistedValue)
                }
            },
        )
    }

    private suspend fun settleApproval(
        client: DshHostClient,
        frame: JsonObject,
        rpcId: String?,
        sessionId: String,
        onProgress: suspend (String) -> Unit,
    ) {
        if (rpcId.isNullOrBlank()) return
        val payload = frame.objectOrNull("payload") ?: JsonObject()
        val approvalId = frame.stringOrNull("approvalId") ?: payload.stringOrNull("approvalId") ?: return
        val toolName = (
            frame.stringOrNull("toolName") ?: frame.stringOrNull("tool") ?: payload.stringOrNull("toolName")
            ?: payload.stringOrNull("tool") ?: "dsh-tool"
            ).take(MAX_TOOL_NAME_CHARS)
        val reason = (frame.stringOrNull("reason") ?: payload.stringOrNull("reason")
            ?: payload.stringOrNull("message") ?: "DSH 请求执行本地工具。")
            .take(MAX_APPROVAL_DETAIL_CHARS)
        if (!localHostApprovalAllowed(agentMode)) {
            client.respond(
                rpcId,
                JsonObject().apply {
                    addProperty("sessionId", sessionId)
                    addProperty("approvalId", approvalId)
                    addProperty("outcome", "rejected")
                },
            )
            onProgress("${agentMode.name} 为只读模式，已拒绝 DSH 工具 $toolName。")
            return
        }
        val allowed = approvalGate?.approve(
            ApprovalRequest(
                toolName = toolName,
                title = "DSH 请求执行 $toolName",
                details = reason,
                risk = "该工具由本机 DSH Host 执行，可能读取项目、修改文件或运行命令；仅本次授权。",
            ),
        ) ?: false
        client.respond(
            rpcId,
            JsonObject().apply {
                addProperty("sessionId", sessionId)
                addProperty("approvalId", approvalId)
                addProperty("outcome", if (allowed) "allowed-once" else "rejected")
            },
        )
        onProgress(if (allowed) "已允许 DSH 工具 $toolName 执行一次。" else "已拒绝 DSH 工具 $toolName。")
    }

    private suspend fun settleQuestion(
        client: DshHostClient,
        rpcId: String?,
        sessionId: String,
        onProgress: suspend (String) -> Unit,
    ) {
        if (rpcId.isNullOrBlank()) return
        client.respond(
            rpcId,
            JsonObject().apply {
                addProperty("sessionId", sessionId)
                add("answer", JsonObject().apply { add("answers", com.google.gson.JsonArray()) })
            },
        )
        onProgress("DSH 请求了当前桥接尚不支持的交互式问答，已安全跳过；任务不会因此卡住。")
    }
}

/** Loopback-only DSH unary RPC client. */
internal class DshHostClient(
    val origin: DshOrigin,
    timeoutSeconds: Long,
) {
    private val timeoutMillis = timeoutSeconds.coerceIn(3, MAX_TURN_TIMEOUT_SECONDS) * 1_000L

    suspend fun describe(timeoutMillis: Long = DESCRIBE_TIMEOUT_MILLIS): JsonObject =
        call("host.describe", JsonObject(), timeoutMillis)

    suspend fun call(method: String, payload: JsonObject, timeoutMillis: Long = this.timeoutMillis): JsonObject {
        require(DSH_RPC_METHOD.matches(method)) { "Invalid DSH RPC method" }
        val rpcId = UUID.randomUUID().toString()
        val requestBody = JsonObject().apply {
            addProperty("type", "client-request")
            addProperty("rpcId", rpcId)
            addProperty("method", method)
            add("payload", payload)
        }
        val response = post("/api/$method", requestBody, timeoutMillis)
        if (response.stringOrNull("type") != "server-response") {
            throw ProviderException("DSH $method 返回了无效响应类型。", retryableOverride = false)
        }
        response.stringOrNull("rpcId")?.takeIf(String::isNotBlank)?.let { returned ->
            if (returned != rpcId) throw ProviderException("DSH $method 响应标识不匹配。", retryableOverride = false)
        }
        val result = response.objectOrNull("result")
            ?: throw ProviderException("DSH $method 响应缺少 result。", retryableOverride = false)
        if (result.booleanOrNull("ok") == true) return result.objectOrNull("value") ?: JsonObject()
        val error = result.objectOrNull("error") ?: JsonObject()
        throw DshRpcException(
            method = method,
            code = error.stringOrNull("code") ?: "unknown",
            detail = error.stringOrNull("message") ?: "unknown DSH error",
        )
    }

    suspend fun respond(rpcId: String, value: JsonObject) {
        if (!SAFE_DSH_RPC_ID.matches(rpcId)) return
        post(
            "/api/respond",
            JsonObject().apply {
                addProperty("type", "client-response")
                addProperty("rpcId", rpcId)
                add("result", JsonObject().apply {
                    addProperty("ok", true)
                    add("value", value)
                })
            },
            RPC_CANCEL_TIMEOUT_MILLIS,
        )
    }

    private suspend fun post(path: String, body: JsonObject, timeoutMillis: Long): JsonObject {
        val request = HttpRequest.newBuilder(origin.httpUri.resolve(path))
            .timeout(Duration.ofMillis(timeoutMillis.coerceIn(500, MAX_RPC_TIMEOUT_MILLIS)))
            .header("Content-Type", "application/json")
            .header("User-Agent", "OmniCode-Agent/3 DSH-Bridge")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
            .build()
        val response = try {
            withTimeout(timeoutMillis.coerceIn(500, MAX_RPC_TIMEOUT_MILLIS)) {
                DSH_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).awaitCancellable()
            }
        } catch (error: Throwable) {
            throw ProviderException(
                "DSH ${path.substringAfterLast('/')} 连接失败：${safeFailure(error)}",
                networkFailure = true,
                retryableOverride = false,
                cause = error,
            )
        }
        val bytes = response.body().use { it.readNBytes(MAX_DSH_RPC_BYTES + 1) }
        if (bytes.size > MAX_DSH_RPC_BYTES) {
            throw ProviderException("DSH RPC 响应超过安全上限。", retryableOverride = false)
        }
        val text = bytes.toString(StandardCharsets.UTF_8)
        if (response.statusCode() !in 200..299) {
            throw ProviderException(
                "DSH Host 返回 HTTP ${response.statusCode()}：${text.take(MAX_ERROR_CHARS)}",
                statusCode = response.statusCode(),
                retryableOverride = false,
            )
        }
        return parseObjectOrNull(text)
            ?: throw ProviderException("DSH Host 返回了无效 JSON。", retryableOverride = false)
    }
}

internal data class DshOrigin(val httpUri: URI, val muxUri: URI)

internal fun dshOrigin(configuredBaseUrl: String?): DshOrigin {
    val configured = configuredBaseUrl.orEmpty().trim()
    val candidate = configured.takeIf { it.startsWith("http://", ignoreCase = true) }
        ?: "http://${System.getenv("DSH_HOST")?.trim().takeUnless { it.isNullOrBlank() } ?: "127.0.0.1"}:" +
        (System.getenv("DSH_PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_DSH_PORT)
    val uri = runCatching { URI.create(candidate.trimEnd('/') + "/") }.getOrElse {
        throw ProviderException("DSH Host 地址无效。", retryableOverride = false)
    }
    val host = uri.host.orEmpty().lowercase()
    if (uri.scheme != "http" || host !in LOOPBACK_HOSTS || uri.userInfo != null || uri.query != null || uri.fragment != null) {
        throw ProviderException(
            "DSH Host 仅允许本机 http://127.0.0.1、http://localhost 或 http://[::1] 地址。",
            retryableOverride = false,
        )
    }
    val normalized = URI("http", null, host, uri.port.takeIf { it > 0 } ?: DEFAULT_DSH_PORT, "/", null, null)
    return DshOrigin(
        httpUri = normalized,
        muxUri = URI("ws", null, host, normalized.port, "/api/events.mux", null, null),
    )
}

internal object DshHostSupervisor {
    private val managedHosts = ConcurrentHashMap<String, Process>()

    suspend fun ensureAvailable(
        client: DshHostClient,
        workDir: File,
        explicitExecutable: String?,
        approvalGate: ApprovalGate?,
        onProgress: suspend (String) -> Unit,
    ) {
        if (runCatching { client.describe() }.isSuccess) {
            onProgress("已连接本机 DSH Host。")
            return
        }
        val executable = CliToolDiscovery.resolveExecutable(CliTool.DSH, explicitExecutable)
            ?: throw ProviderException(
                "找不到 DSH CLI。请先安装并在依赖页确认 dsh --version。",
                retryableOverride = false,
            )
        val approved = approvalGate?.approve(
            ApprovalRequest(
                toolName = "dsh_host",
                title = "启动本机 DSH Host",
                details = "${executable.absolutePath} web --host ${client.origin.httpUri.host} --port ${client.origin.httpUri.port}",
                risk = "将启动一个仅监听本机回环地址的持久进程；后续危险工具仍会逐次审批。",
            ),
        ) ?: false
        if (!approved) {
            throw ProviderException(
                "DSH Host 尚未运行，且本次启动未获批准。你也可以在终端手动运行 dsh web --host 127.0.0.1 --port 3080。",
                retryableOverride = false,
            )
        }
        onProgress("正在启动本机 DSH Host…")
        val process = try {
            ProcessBuilder(
                CliToolDiscovery.launchCommand(executable) + listOf(
                    "web", "--host", client.origin.httpUri.host, "--port", client.origin.httpUri.port.toString(),
                ),
            )
                .directory(workDir)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { CliToolDiscovery.applyRuntimePath(it.environment(), executable) }
                .start()
        } catch (error: IOException) {
            throw ProviderException("无法启动 DSH Host：${safeFailure(error)}", retryableOverride = false, cause = error)
        }
        runCatching { process.outputStream.close() }
        managedHosts[client.origin.httpUri.toString()] = process
        val readyMillis = if (System.getProperty("os.name", "").contains("windows", true)) {
            WINDOWS_HOST_READY_TIMEOUT_MILLIS
        } else HOST_READY_TIMEOUT_MILLIS
        val deadline = System.nanoTime() + readyMillis * 1_000_000
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            if (!process.isAlive) break
            try {
                client.describe(HOST_POLL_RPC_TIMEOUT_MILLIS)
                onProgress("DSH Host 已启动。")
                return
            } catch (error: Throwable) {
                lastFailure = error
                delay(HOST_POLL_INTERVAL_MILLIS)
            }
        }
        managedHosts.remove(client.origin.httpUri.toString(), process)
        if (process.isAlive) process.destroy()
        throw ProviderException(
            "DSH Host 未能在 ${readyMillis / 1_000} 秒内就绪：${safeFailure(lastFailure)}。" +
                "请在终端运行 dsh web --host 127.0.0.1 --port 3080 查看详细错误。",
            retryableOverride = false,
            cause = lastFailure,
        )
    }
}

private sealed interface DshSocketEvent {
    data class Text(val value: String) : DshSocketEvent
    data class Failure(val error: Throwable) : DshSocketEvent
    data object Closed : DshSocketEvent
}

private class DshMuxListener : WebSocket.Listener {
    val events = Channel<DshSocketEvent>(capacity = MAX_PENDING_SOCKET_EVENTS)
    private val text = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
        synchronized(text) {
            if (text.length + data.length > MAX_DSH_SOCKET_MESSAGE_CHARS) {
                events.trySend(DshSocketEvent.Failure(IOException("DSH mux frame exceeded safe limit")))
                webSocket.abort()
                return CompletableFuture.completedFuture(null)
            }
            text.append(data)
            if (last) {
                if (events.trySend(DshSocketEvent.Text(text.toString())).isFailure) {
                    events.close(IOException("DSH mux event queue overflow"))
                    webSocket.abort()
                }
                text.setLength(0)
            }
        }
        webSocket.request(1)
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        events.trySend(DshSocketEvent.Failure(error))
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
        events.trySend(DshSocketEvent.Closed)
        return CompletableFuture.completedFuture(null)
    }
}

private data class ProjectedDshFrame(
    val frame: JsonObject,
    val rpcId: String?,
    val sessionId: String,
)

private fun unwrapDshFrame(raw: JsonObject): ProjectedDshFrame {
    val rpcId = raw.stringOrNull("rpcId")
    val frame = if (raw.stringOrNull("type") == "server-request") raw.objectOrNull("payload") ?: JsonObject() else raw
    val sessionId = raw.stringOrNull("sessionId")
        ?: raw.objectOrNull("payload")?.stringOrNull("sessionId")
        ?: frame.stringOrNull("sessionId")
        ?: ""
    return ProjectedDshFrame(frame, rpcId, sessionId)
}

private class DshGoalSettlement {
    private var phase: String? = null
    private var awaitingIdle = false

    fun turnStarted() {
        awaitingIdle = false
    }

    fun turnCompleted(): Boolean {
        if (phase == "active") {
            awaitingIdle = true
            return false
        }
        return true
    }

    fun goalChanged(data: JsonObject): Boolean {
        val goal = data.objectOrNull("goal")
        val operation = (data.stringOrNull("operation") ?: goal?.stringOrNull("operation")).orEmpty().lowercase()
        if (operation == "clear" || (data.has("goal") && data.get("goal").isJsonNull)) {
            phase = null
        } else {
            val next = (goal?.stringOrNull("phase") ?: data.stringOrNull("phase")).orEmpty().lowercase()
            if (next in DSH_GOAL_PHASES) phase = if (next == "completed") "complete" else next
        }
        if (awaitingIdle && phase != "active") {
            awaitingIdle = false
            return true
        }
        return false
    }
}

private class DshRpcException(
    private val method: String,
    private val code: String,
    private val detail: String,
) : RuntimeException("DSH $method [$code]: $detail") {
    fun looksLikeInvalidSession(): Boolean = "$code $detail".lowercase().let {
        "session" in it && ("not found" in it || "invalid" in it || "unknown" in it || "does not exist" in it)
    }

    fun asProviderException(): ProviderException = ProviderException(
        "DSH $method 失败 [$code]：${detail.take(MAX_ERROR_CHARS)}",
        retryableOverride = false,
        cause = this,
    )
}

internal object DshHostModelDiscovery {
    suspend fun discover(connection: ProviderConnection): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val client = DshHostClient(dshOrigin(connection.baseUrl), connection.requestTimeoutSeconds)
        val catalog = try {
            client.call("llm.models", JsonObject(), connection.requestTimeoutSeconds.coerceIn(3, 15) * 1_000L)
        } catch (error: Throwable) {
            throw ProviderException(
                "无法从 DSH Host 读取模型。请先启动 dsh web --host 127.0.0.1 --port 3080，再刷新模型。",
                retryableOverride = false,
                cause = error,
            )
        }
        val models = catalog.arrayOrNull("groups")?.asSequence().orEmpty().flatMap { groupElement ->
            val group = groupElement.objectOrNull() ?: return@flatMap emptySequence()
            val provider = group.stringOrNull("id")?.trim().orEmpty()
            if (provider.isBlank()) return@flatMap emptySequence()
            group.arrayOrNull("models")?.asSequence().orEmpty().mapNotNull { modelElement ->
                val model = modelElement.objectOrNull()?.stringOrNull("id")?.trim().orEmpty()
                "$provider/$model".takeIf { model.isNotBlank() && SAFE_DSH_MODEL_ID.matches(it) }
            }
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).take(MAX_DSH_MODELS).toList()
        if (models.isEmpty()) {
            throw ProviderException("DSH Host 没有返回可用模型。请先在 DSH 中完成供应商登录。", retryableOverride = false)
        }
        ModelDiscoveryResult(models, discoveredRemotely = false, status = "已从本机 DSH Host 读取 ${models.size} 个模型。")
    }
}

private fun dshPromptContent(request: ModelRequest): com.google.gson.JsonArray = com.google.gson.JsonArray().apply {
    add(JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", dshConversationText(request))
    })
    request.messages.asSequence().flatMap { it.blocks.asSequence() }.filterIsInstance<ContentBlock.Image>().forEach { image ->
        add(JsonObject().apply {
            addProperty("type", "image")
            addProperty("mediaType", image.mediaType)
            addProperty("data", image.base64Data)
            image.fileName.takeIf(String::isNotBlank)?.let { addProperty("name", it.take(MAX_ATTACHMENT_NAME_CHARS)) }
        })
    }
}

private fun dshConversationText(request: ModelRequest): String {
    val visible = buildString {
        request.messages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(MAX_DSH_HISTORY_MESSAGES)
            .forEach { message ->
                val text = message.blocks.mapNotNull { block ->
                    when (block) {
                        is ContentBlock.Text -> block.text
                        is ContentBlock.Image -> "[用户附加图片：${block.fileName.take(MAX_ATTACHMENT_NAME_CHARS)}]"
                        is ContentBlock.TransientProjectContext,
                        is ContentBlock.ToolCall,
                        is ContentBlock.ToolResult,
                        -> null
                    }
                }.joinToString("\n").trim().take(MAX_DSH_MESSAGE_CHARS)
                if (text.isNotBlank()) {
                    if (message.role == MessageRole.ASSISTANT) append("Assistant: ")
                    append(text).append("\n\n")
                }
            }
    }.trim().takeLast(MAX_DSH_PROMPT_CHARS)
    return "当前已绑定用户打开的项目根目录。请只处理以下对话请求：\n\n$visible"
}

private fun jsonObject(vararg values: Pair<String, String>): JsonObject = JsonObject().apply {
    values.forEach { (key, value) -> addProperty(key, value) }
}

private fun parseObjectOrNull(value: String): JsonObject? = try {
    JsonParser.parseString(value).objectOrNull()
} catch (_: JsonParseException) {
    null
}

private fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name).objectOrNull()
private fun JsonObject.arrayOrNull(name: String): com.google.gson.JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
private fun JsonObject.booleanOrNull(name: String): Boolean? = get(name)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
}?.asBoolean
private fun JsonObject.longFrom(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}?.coerceAtLeast(0) ?: 0

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) continuation.resume(value) else continuation.resumeWithException(error)
    }
    continuation.invokeOnCancellation { cancel(true) }
}

private fun safeFailure(error: Throwable?): String = error?.message?.trim()?.take(MAX_ERROR_CHARS)
    ?.takeIf(String::isNotBlank) ?: error?.javaClass?.simpleName ?: "未知错误"

private val DSH_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
private val DSH_RPC_METHOD = Regex("[A-Za-z][A-Za-z0-9.]{0,63}")
private val SAFE_DSH_SESSION_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private val SAFE_DSH_RPC_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private val SAFE_DSH_MODEL_ID = Regex("[A-Za-z0-9._:@/+\\-]{3,256}")
private val FAILURE_TURN_END_KINDS = setOf("cancelled", "aborted", "error", "failed")
private val DSH_GOAL_PHASES = setOf("active", "paused", "blocked", "complete", "completed")
private const val DEFAULT_DSH_PORT = 3080
private const val DESCRIBE_TIMEOUT_MILLIS = 3_000L
private const val MUX_OPEN_TIMEOUT_MILLIS = 15_000L
private const val HOST_READY_TIMEOUT_MILLIS = 20_000L
private const val WINDOWS_HOST_READY_TIMEOUT_MILLIS = 45_000L
private const val HOST_POLL_RPC_TIMEOUT_MILLIS = 2_000L
private const val HOST_POLL_INTERVAL_MILLIS = 250L
private const val RPC_CANCEL_TIMEOUT_MILLIS = 5_000L
private const val MAX_RPC_TIMEOUT_MILLIS = 15 * 60_000L
private const val MAX_TURN_TIMEOUT_SECONDS = 24 * 60 * 60L
private const val MAX_DSH_RPC_BYTES = 4 * 1_024 * 1_024
private const val MAX_DSH_SOCKET_MESSAGE_CHARS = 4 * 1_024 * 1_024
private const val MAX_PENDING_SOCKET_EVENTS = 256
private const val MAX_DSH_OUTPUT_CHARS = 4 * 1_024 * 1_024
private const val MAX_DSH_PROMPT_CHARS = 120_000
private const val MAX_DSH_MESSAGE_CHARS = 40_000
private const val MAX_DSH_HISTORY_MESSAGES = 12
private const val MAX_DSH_MODELS = 5_000
private const val MAX_TOOL_NAME_CHARS = 160
private const val MAX_APPROVAL_DETAIL_CHARS = 4_000
private const val MAX_ATTACHMENT_NAME_CHARS = 240
private const val MAX_ERROR_CHARS = 1_000
