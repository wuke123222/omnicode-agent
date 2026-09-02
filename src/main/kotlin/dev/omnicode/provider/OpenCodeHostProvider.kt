package dev.omnicode.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCode's supported headless-server adapter.
 *
 * A protected loopback server is reused per canonical project/runtime. Each turn subscribes to
 * `/event` before starting the synchronous prompt request and filters every event by the exact
 * session id. The prompt response is the authoritative terminal signal; SSE idle is deliberately
 * not trusted because supported OpenCode builds can leave status busy or emit stale idle events.
 * The server process is expected to remain alive across turns.
 */
internal class OpenCodeHostProvider(
    private val connection: ProviderConnection,
    private val workingDirectory: Path?,
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
        val totalSeconds = connection.requestTimeoutSeconds.coerceIn(MIN_TURN_TIMEOUT_SECONDS, MAX_TURN_TIMEOUT_SECONDS)
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
                "OpenCode Server 请求失败：${safeOpenCodeFailure(error)}",
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
        val executable = CliToolDiscovery.resolveExecutable(CliTool.OPENCODE, connection.baseUrl)
            ?: throw ProviderException(
                "找不到 OpenCode CLI。请在设置 → 依赖中安装或重新检测 OpenCode。",
                retryableOverride = false,
            )
        val runtime = OpenCodeHostSupervisor.ensureAvailable(executable, workDir, connection, onProgress)
        val client = OpenCodeHostClient(runtime)
        var sessionId = resolveSession(client, workDir)
        var promptAccepted = false
        var settled = false
        val oldMessageIds = client.messageIds(sessionId, workDir)
        var output = StringBuilder()
        var usage = TokenUsage()
        var finalMessageAtIdle: OpenCodeAssistantMessage? = null

        onProgress("OpenCode 会话已就绪，正在订阅实时事件…")
        val stream = client.openEventStream(workDir)
        try {
            // OpenCode documents server.connected as the first SSE event. There is no separate
            // 15/30/45-second initialization deadline: this remains under the user's total turn
            // timeout and can always be cancelled.
            while (true) {
                currentCoroutineContext().ensureActive()
                val connected = stream.nextJson()
                    ?: throw ProviderException(
                        "OpenCode 事件流在订阅确认前关闭。会话已保留，可直接重试。",
                        networkFailure = true,
                        retryableOverride = false,
                    )
                if (connected.stringOrNull("type") == "server.connected") break
            }

            coroutineScope {
                val prompt = async {
                    client.prompt(sessionId, workDir, openCodePromptBody(request, connection, agentMode))
                }
                promptAccepted = true
                onProgress("OpenCode 已接收任务，正在等待模型响应…")
                try {
                    while (!prompt.isCompleted) {
                        currentCoroutineContext().ensureActive()
                        val read = withTimeoutOrNull(OPENCODE_EVENT_POLL_MILLIS) {
                            OpenCodeSseRead(stream.nextJson())
                        } ?: continue
                        val event = read.event ?: throw ProviderException(
                            "OpenCode 事件流在任务完成前关闭。会话已保留，可直接重试。",
                            networkFailure = true,
                            retryableOverride = false,
                        )
                        val type = event.stringOrNull("type").orEmpty()
                        val properties = event.objectOrNull("properties") ?: JsonObject()
                        val eventSessionId = openCodeHostEventSessionId(event)
                        if (eventSessionId != sessionId) continue

                        when (type) {
                            "session.status" -> when (properties.objectOrNull("status")?.stringOrNull("type")) {
                                "busy" -> onProgress("OpenCode 模型正在处理…")
                                "retry" -> onProgress("OpenCode 上游暂时不可用，正在按模型策略重试…")
                                "idle" -> onProgress("OpenCode 正在整理最终响应…")
                            }
                            "session.idle" -> onProgress("OpenCode 正在整理最终响应…")
                            "session.error" -> {
                                val detail = openCodeEventError(properties)
                                throw ProviderException("OpenCode 任务失败：$detail", retryableOverride = false)
                            }
                            "session.next.text.delta" -> {
                                properties.stringOrNull("delta")?.let { delta ->
                                    appendOpenCodeDelta(output, delta, onTextDelta)
                                }
                            }
                            "message.part.delta" -> {
                                // Kept for older OpenCode builds. The synchronous response is
                                // authoritative, so duplicate legacy text chunks are not rendered.
                            }
                            "message.part.updated" -> {
                                val part = properties.objectOrNull("part")
                                when (part?.stringOrNull("type")) {
                                    "tool" -> onProgress(
                                        "OpenCode 正在调用 ${part.stringOrNull("tool")?.take(MAX_TOOL_NAME_CHARS) ?: "工具"}…",
                                    )
                                    "reasoning" -> onProgress("OpenCode 正在推理…")
                                }
                            }
                            "message.updated" -> {
                                properties.objectOrNull("info")?.let { info ->
                                    usage = usage.maxWith(openCodeUsage(info.objectOrNull("tokens")))
                                    info.get("error")?.takeUnless(JsonElement::isJsonNull)?.let {
                                        throw ProviderException(
                                            "OpenCode 模型返回错误：${openCodeEventError(info)}",
                                            retryableOverride = false,
                                        )
                                    }
                                }
                            }
                            "permission.asked", "permission.v2.asked" -> {
                                settlePermission(client, type, properties, sessionId, workDir, onProgress)
                            }
                            "question.asked", "question.v2.asked" -> {
                                settleQuestion(client, type, properties, sessionId, workDir, onProgress)
                            }
                        }
                    }
                    finalMessageAtIdle = prompt.await()
                    settled = true
                } catch (error: Throwable) {
                    prompt.cancel()
                    throw error
                }
            }

            val finalMessage = finalMessageAtIdle
                ?: client.latestNewAssistantMessage(sessionId, workDir, oldMessageIds)
            if (finalMessage != null) {
                usage = usage.maxWith(finalMessage.usage)
                val finalText = finalMessage.text.trim()
                if (finalText.isNotBlank()) {
                    val suffix = when {
                        output.isEmpty() -> finalText
                        finalText.startsWith(output.toString()) -> finalText.drop(output.length)
                        else -> ""
                    }
                    if (suffix.isNotEmpty()) {
                        appendOpenCodeDelta(output, suffix, onTextDelta)
                    }
                    // The persisted session message is authoritative even if an older event
                    // protocol used a different chunking scheme.
                    output = StringBuilder(finalText)
                }
            }
        } finally {
            stream.close()
            if (promptAccepted && !settled) {
                withContext(NonCancellable) {
                    runCatching { client.abort(sessionId, workDir) }
                }
            }
        }

        return ModelResponse(
            blocks = listOfNotNull(output.toString().trim().takeIf(String::isNotBlank)?.let(ContentBlock::Text)),
            usage = usage,
            stopReason = StopReason.COMPLETE,
        )
    }

    private suspend fun resolveSession(client: OpenCodeHostClient, workDir: File): String {
        localSession?.resumeSessionId?.takeIf(SAFE_OPENCODE_HOST_SESSION_ID::matches)?.let { existing ->
            val resumed = client.session(existing, workDir)
            if (resumed != null && resumed.stringOrNull("directory")?.let(::sameCanonicalDirectory) != false) {
                return existing
            }
            localSession.onSessionInvalid()
        }
        val created = client.createSession(workDir)
        val sessionId = created.stringOrNull("id")
            ?.takeIf(SAFE_OPENCODE_HOST_SESSION_ID::matches)
            ?: throw ProviderException("OpenCode 未返回有效的 session id。", retryableOverride = false)
        localSession?.onSessionStarted?.invoke(sessionId)
        return sessionId
    }

    private fun sameCanonicalDirectory(value: String): Boolean = runCatching {
        File(value).canonicalFile == CliToolLaunch.resolveWorkingDirectory(workingDirectory).canonicalFile
    }.getOrDefault(false)

    private suspend fun settlePermission(
        client: OpenCodeHostClient,
        eventType: String,
        properties: JsonObject,
        sessionId: String,
        workDir: File,
        onProgress: suspend (String) -> Unit,
    ) {
        val requestId = properties.stringOrNull("id")?.takeIf(SAFE_OPENCODE_REQUEST_ID::matches) ?: return
        val action = (properties.stringOrNull("permission") ?: properties.stringOrNull("action") ?: "tool")
            .take(MAX_TOOL_NAME_CHARS)
        val resources = properties.arrayOrNull("patterns") ?: properties.arrayOrNull("resources")
        val detail = resources?.asSequence()?.mapNotNull(JsonElement::primitiveStringOrNull)
            ?.joinToString(", ")?.take(MAX_APPROVAL_DETAIL_CHARS)
            .orEmpty().ifBlank { "OpenCode 请求执行受保护操作。" }
        if (!localHostApprovalAllowed(agentMode)) {
            client.replyPermission(eventType, requestId, sessionId, workDir, false)
            onProgress("${agentMode.displayName()} 为只读模式，已拒绝 OpenCode 操作 $action。")
            return
        }
        val allowed = approvalGate?.approve(
            ApprovalRequest(
                toolName = "opencode:$action",
                title = "OpenCode 请求执行 $action",
                details = detail,
                risk = "操作由本机 OpenCode 在当前项目中执行；授权仅限本次，不会保存永久放行规则。",
            ),
        ) ?: false
        client.replyPermission(eventType, requestId, sessionId, workDir, allowed)
        onProgress(if (allowed) "已允许 OpenCode 操作 $action 执行一次。" else "已拒绝 OpenCode 操作 $action。")
    }

    private suspend fun settleQuestion(
        client: OpenCodeHostClient,
        eventType: String,
        properties: JsonObject,
        sessionId: String,
        workDir: File,
        onProgress: suspend (String) -> Unit,
    ) {
        val requestId = properties.stringOrNull("id")?.takeIf(SAFE_OPENCODE_REQUEST_ID::matches) ?: return
        client.rejectQuestion(eventType, requestId, sessionId, workDir)
        onProgress("OpenCode 请求了尚未桥接的交互式问答，已安全拒绝；任务不会卡在等待输入。")
    }
}

internal data class OpenCodeAssistantMessage(
    val text: String,
    val usage: TokenUsage,
)

internal fun openCodeAssistantMessage(message: JsonObject): OpenCodeAssistantMessage? {
    val info = message.objectOrNull("info") ?: return null
    if (info.stringOrNull("role") != "assistant") return null
    val text = message.arrayOrNull("parts")?.asSequence().orEmpty().mapNotNull { partElement ->
        partElement.objectOrNull()?.takeIf { it.stringOrNull("type") == "text" }?.stringOrNull("text")
    }.joinToString("").take(MAX_OPENCODE_OUTPUT_CHARS)
    return OpenCodeAssistantMessage(text, openCodeUsage(info.objectOrNull("tokens")))
}

/** Bounded loopback HTTP client; it never accepts a user-supplied remote origin. */
internal class OpenCodeHostClient(private val runtime: OpenCodeHostRuntime) {
    suspend fun health(): Boolean = runCatching {
        val response = request("GET", "/global/health", null, setOf(200), HEALTH_TIMEOUT_MILLIS)
        response.body.objectOrNull()?.get("healthy")?.asBoolean == true
    }.getOrDefault(false)

    suspend fun createSession(directory: File): JsonObject = request(
        "POST",
        "/session?directory=${encodedDirectory(directory)}",
        openCodeSessionBody(),
        setOf(200),
    ).body.objectOrNull() ?: throw ProviderException("OpenCode 创建会话时返回了无效响应。", retryableOverride = false)

    suspend fun session(sessionId: String, directory: File): JsonObject? {
        require(SAFE_OPENCODE_HOST_SESSION_ID.matches(sessionId))
        val response = request(
            "GET",
            "/session/$sessionId?directory=${encodedDirectory(directory)}",
            null,
            setOf(200, 404),
        )
        if (response.statusCode == 404) return null
        return response.body.objectOrNull()
    }

    suspend fun messageIds(sessionId: String, directory: File): Set<String> = messages(sessionId, directory)
        .mapNotNull { it.objectOrNull()?.objectOrNull("info")?.stringOrNull("id") }
        .filterTo(linkedSetOf(), SAFE_OPENCODE_MESSAGE_ID::matches)

    suspend fun latestNewAssistantMessage(
        sessionId: String,
        directory: File,
        previousIds: Set<String>,
    ): OpenCodeAssistantMessage? = messages(sessionId, directory).asReversed().firstNotNullOfOrNull { element ->
        val message = element.objectOrNull() ?: return@firstNotNullOfOrNull null
        val info = message.objectOrNull("info") ?: return@firstNotNullOfOrNull null
        val messageId = info.stringOrNull("id") ?: return@firstNotNullOfOrNull null
        if (messageId in previousIds || info.stringOrNull("role") != "assistant") return@firstNotNullOfOrNull null
        openCodeAssistantMessage(message)
    }

    suspend fun prompt(sessionId: String, directory: File, body: JsonObject): OpenCodeAssistantMessage {
        require(SAFE_OPENCODE_HOST_SESSION_ID.matches(sessionId))
        val message = request(
            "POST",
            "/session/$sessionId/message?directory=${encodedDirectory(directory)}",
            body,
            setOf(200),
        ).body.objectOrNull() ?: throw ProviderException(
            "OpenCode 完成请求后返回了无效响应。会话已保留，可直接重试。",
            retryableOverride = false,
        )
        return openCodeAssistantMessage(message) ?: throw ProviderException(
            "OpenCode 完成请求后没有返回助手消息。会话已保留，可直接重试。",
            retryableOverride = false,
        )
    }

    suspend fun abort(sessionId: String, directory: File) {
        if (!SAFE_OPENCODE_HOST_SESSION_ID.matches(sessionId)) return
        request(
            "POST",
            "/session/$sessionId/abort?directory=${encodedDirectory(directory)}",
            JsonObject(),
            setOf(200, 204, 404),
            ABORT_TIMEOUT_MILLIS,
        )
    }

    suspend fun replyPermission(
        eventType: String,
        requestId: String,
        sessionId: String,
        directory: File,
        allowed: Boolean,
    ) {
        require(SAFE_OPENCODE_REQUEST_ID.matches(requestId))
        val path = if (eventType == "permission.v2.asked") {
            "/api/session/$sessionId/permission/$requestId/reply"
        } else {
            "/permission/$requestId/reply"
        }
        request(
            "POST",
            "$path?directory=${encodedDirectory(directory)}",
            JsonObject().apply { addProperty("reply", if (allowed) "once" else "reject") },
            setOf(200, 204),
        )
    }

    suspend fun rejectQuestion(
        eventType: String,
        requestId: String,
        sessionId: String,
        directory: File,
    ) {
        require(SAFE_OPENCODE_REQUEST_ID.matches(requestId))
        val path = if (eventType == "question.v2.asked") {
            "/api/session/$sessionId/question/$requestId/reject"
        } else {
            "/question/$requestId/reject"
        }
        request("POST", "$path?directory=${encodedDirectory(directory)}", null, setOf(200, 204))
    }

    suspend fun openEventStream(directory: File): OpenCodeSseStream {
        val request = requestBuilder("/event?directory=${encodedDirectory(directory)}")
            .GET()
            .header("Accept", "text/event-stream")
            .build()
        val response = try {
            OPENCODE_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (error: Throwable) {
            throw ProviderException(
                "无法订阅 OpenCode 事件流：${safeOpenCodeFailure(error)}",
                networkFailure = true,
                retryableOverride = false,
                cause = error,
            )
        }
        if (response.statusCode() != 200) {
            response.body().close()
            throw ProviderException(
                "OpenCode 事件流返回 HTTP ${response.statusCode()}。",
                statusCode = response.statusCode(),
                retryableOverride = false,
            )
        }
        return OpenCodeSseStream(response.body())
    }

    private suspend fun messages(sessionId: String, directory: File): List<JsonElement> {
        require(SAFE_OPENCODE_HOST_SESSION_ID.matches(sessionId))
        val body = request(
            "GET",
            "/session/$sessionId/message?directory=${encodedDirectory(directory)}&limit=$MAX_SESSION_MESSAGES",
            null,
            setOf(200),
        ).body
        return body.arrayOrNull()?.takeLast(MAX_SESSION_MESSAGES).orEmpty()
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject?,
        accepted: Set<Int>,
        timeoutMillis: Long = runtime.requestTimeoutMillis,
    ): OpenCodeHttpResult {
        require(path.startsWith('/') && !path.startsWith("//"))
        val publisher = body?.toString()?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody()
        val builder = requestBuilder(path)
            .timeout(Duration.ofMillis(timeoutMillis.coerceIn(MIN_HTTP_TIMEOUT_MILLIS, MAX_HTTP_TIMEOUT_MILLIS)))
            .method(method, publisher)
        if (body != null) builder.header("Content-Type", "application/json")
        val response = try {
            OPENCODE_HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (error: Throwable) {
            throw ProviderException(
                "OpenCode 本地服务连接失败：${safeOpenCodeFailure(error)}",
                networkFailure = true,
                retryableOverride = false,
                cause = error,
            )
        }
        val responseBody = response.body().use { readBounded(it, MAX_HTTP_RESPONSE_BYTES) }
        if (response.statusCode() !in accepted) {
            val detail = responseBody.objectOrNull()?.let(::openCodeEventError).orEmpty()
            throw ProviderException(
                buildString {
                    append("OpenCode 本地服务返回 HTTP ").append(response.statusCode()).append('.')
                    if (detail.isNotBlank() && detail != "未知错误") append(' ').append(detail)
                },
                statusCode = response.statusCode(),
                retryableOverride = false,
            )
        }
        return OpenCodeHttpResult(response.statusCode(), responseBody)
    }

    private fun requestBuilder(path: String): HttpRequest.Builder = HttpRequest.newBuilder(runtime.origin.resolve(path))
        .header("Authorization", runtime.authorization)
        .header("User-Agent", "OmniCode/3.0 OpenCodeBridge")

    private fun encodedDirectory(directory: File): String = URLEncoder.encode(
        directory.canonicalPath,
        StandardCharsets.UTF_8,
    )
}

internal data class OpenCodeHttpResult(val statusCode: Int, val body: String)

private data class OpenCodeSseRead(val event: JsonObject?)

/** Parses SSE incrementally and bounds both one line and one JSON event. */
internal class OpenCodeSseStream(input: InputStream) : Closeable {
    private val source = input
    private val reader = InputStreamReader(input, StandardCharsets.UTF_8)
    private val events = Channel<Result<JsonObject?>>(MAX_PENDING_SSE_EVENTS)

    init {
        Thread {
            try {
                while (true) {
                    val next = readNextJsonBlocking()
                    runBlocking { events.send(Result.success(next)) }
                    if (next == null) break
                }
            } catch (error: Throwable) {
                runCatching { runBlocking { events.send(Result.failure(error)) } }
            } finally {
                events.close()
            }
        }.apply {
            name = "omnicode-opencode-sse"
            isDaemon = true
            start()
        }
    }

    suspend fun nextJson(): JsonObject? {
        val result = events.receiveCatching().getOrNull() ?: return null
        return result.getOrThrow()
    }

    private fun readNextJsonBlocking(): JsonObject? {
        val data = StringBuilder()
        while (true) {
            val line = readBoundedLine(reader, MAX_SSE_LINE_CHARS) ?: return null
            if (line.isEmpty()) {
                if (data.isEmpty()) continue
                return runCatching { JsonParser.parseString(data.toString()).objectOrNull() }.getOrNull()
            }
            if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                if (data.length > MAX_SSE_EVENT_CHARS) {
                    throw ProviderException("OpenCode 事件超过安全上限。", retryableOverride = false)
                }
            }
        }
    }

    override fun close() {
        events.close()
        runCatching { source.close() }
    }
}

internal data class OpenCodeHostRuntime(
    val origin: URI,
    val authorization: String,
    val process: Process,
    val requestTimeoutMillis: Long,
)

/** Owns authenticated loopback runtimes without persisting credentials or process output. */
internal object OpenCodeHostSupervisor {
    private val runtimes = ConcurrentHashMap<String, OpenCodeHostRuntime>()
    private val startupLocks = ConcurrentHashMap<String, Mutex>()
    private val lifecycleRegistered = AtomicBoolean(false)

    suspend fun ensureAvailable(
        executable: File,
        workDir: File,
        connection: ProviderConnection,
        onProgress: suspend (String) -> Unit,
    ): OpenCodeHostRuntime {
        val key = runtimeKey(executable, workDir, connection.apiKey)
        registerLifecycleCleanup()
        val lock = startupLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            runtimes[key]?.takeIf { it.process.isAlive && OpenCodeHostClient(it).health() }?.let {
                return@withLock it
            }
            runtimes.remove(key)?.let(::terminateOpenCodeRuntime)

            onProgress("正在启动受保护的 OpenCode 本地服务…")
            val runtime = startRuntime(executable, workDir, connection)
            runtimes[key] = runtime
            runtime.process.onExit().thenRun { runtimes.remove(key, runtime) }
            try {
                withTimeout(HOST_STARTUP_TIMEOUT_MILLIS) {
                    val startedAt = System.nanoTime()
                    var nextProgressAt = startedAt + HOST_STARTUP_PROGRESS_INTERVAL_MILLIS * 1_000_000L
                    while (runtime.process.isAlive) {
                        currentCoroutineContext().ensureActive()
                        if (OpenCodeHostClient(runtime).health()) {
                            onProgress("OpenCode 本地服务已连接。")
                            return@withTimeout runtime
                        }
                        val now = System.nanoTime()
                        if (now >= nextProgressAt) {
                            val elapsedSeconds = ((now - startedAt) / 1_000_000_000L).coerceAtLeast(1L)
                            onProgress(openCodeHostStartupProgress(elapsedSeconds))
                            nextProgressAt = now + HOST_STARTUP_PROGRESS_INTERVAL_MILLIS * 1_000_000L
                        }
                        kotlinx.coroutines.delay(HOST_HEALTH_POLL_MILLIS)
                    }
                    throw ProviderException(
                        "OpenCode 本地服务启动后立即退出。请在依赖页运行诊断并确认 CLI 可以在系统终端启动。",
                        retryableOverride = false,
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw ProviderException(
                    "OpenCode 本地服务在 ${HOST_STARTUP_TIMEOUT_MILLIS / 1_000} 秒内未就绪；尚未发送模型请求。" +
                        "请在依赖页重新检测 OpenCode，或先在系统终端运行 opencode serve 检查登录、网络和权限。",
                    networkFailure = true,
                    retryableOverride = false,
                    cause = timeout,
                )
            } catch (error: Throwable) {
                runtimes.remove(key, runtime)
                terminateOpenCodeRuntime(runtime)
                throw error
            }
        }
    }

    private fun startRuntime(
        executable: File,
        workDir: File,
        connection: ProviderConnection,
    ): OpenCodeHostRuntime {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val username = "omnicode"
        val password = randomServerPassword()
        val command = CliToolDiscovery.launchCommand(executable) + listOf(
            "serve", "--hostname", "127.0.0.1", "--port", port.toString(),
        )
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(false)
        CliToolDiscovery.applyRuntimePath(builder.environment(), executable)
        applyCliRequestEnvironment(CliTool.OPENCODE, builder.environment())
        builder.environment()["OPENCODE_SERVER_USERNAME"] = username
        builder.environment()["OPENCODE_SERVER_PASSWORD"] = password
        if (connection.apiKey.isNotBlank()) {
            cliCredentialEnvironmentVariables(CliTool.OPENCODE, connection.model).forEach { variable ->
                builder.environment()[variable] = connection.apiKey
            }
        }
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw ProviderException(
                "启动 OpenCode 本地服务失败：${safeOpenCodeFailure(error)}",
                networkFailure = true,
                retryableOverride = false,
                cause = error,
            )
        }
        drainWithoutRetention(process.inputStream, "omnicode-opencode-stdout")
        drainWithoutRetention(process.errorStream, "omnicode-opencode-stderr")
        val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
        return OpenCodeHostRuntime(
            origin = URI("http://127.0.0.1:$port/"),
            authorization = "Basic $credentials",
            process = process,
            requestTimeoutMillis = connection.requestTimeoutSeconds
                .coerceIn(MIN_TURN_TIMEOUT_SECONDS, MAX_TURN_TIMEOUT_SECONDS) * 1_000L,
        )
    }

    private fun runtimeKey(executable: File, workDir: File, apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "${executable.canonicalPath}\u0000${workDir.canonicalPath}\u0000$digest"
    }

    private fun registerLifecycleCleanup() {
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        Disposer.register(ApplicationManager.getApplication(), Disposable { shutdownAll() })
    }

    internal fun shutdownAll() {
        val owned = runtimes.values.toSet()
        runtimes.clear()
        owned.forEach(::terminateOpenCodeRuntime)
    }
}

internal fun openCodeHostStartupProgress(elapsedSeconds: Long): String =
    "OpenCode 本地服务仍在启动 · ${elapsedSeconds.coerceAtLeast(1L)}秒 / " +
        "${HOST_STARTUP_TIMEOUT_MILLIS / 1_000}秒 · 可随时停止"

private fun openCodeSessionBody(): JsonObject = JsonObject().apply {
    // Unknown and side-effecting tools fail into a one-shot approval. Only bounded project reads
    // are pre-approved; external paths remain denied even if OpenCode configuration is looser.
    add("permission", JsonArray().apply {
        add(permissionRule("*", "*", "ask"))
        listOf("read", "glob", "grep", "list").forEach { add(permissionRule(it, "*", "allow")) }
        add(permissionRule("external_directory", "*", "deny"))
    })
}

private fun permissionRule(permission: String, pattern: String, action: String): JsonObject = JsonObject().apply {
    addProperty("permission", permission)
    addProperty("pattern", pattern)
    addProperty("action", action)
}

internal fun openCodePromptBody(
    request: ModelRequest,
    connection: ProviderConnection,
    agentMode: AgentMode,
): JsonObject = JsonObject().apply {
    val model = connection.model.trim()
    if (model.isNotBlank() && model !in setOf("default", "auto")) {
        val slash = model.indexOf('/')
        if (slash <= 0 || slash == model.lastIndex) {
            throw ProviderException(
                "OpenCode 模型必须使用 provider/model 格式；请刷新模型列表后重新选择。",
                retryableOverride = false,
            )
        }
        add("model", JsonObject().apply {
            addProperty("providerID", model.substring(0, slash))
            addProperty("modelID", model.substring(slash + 1))
        })
    }
    connection.reasoningEffort.takeUnless { it == ReasoningEffort.AUTO }?.let {
        addProperty("variant", it.persistedValue)
    }
    if (agentMode != AgentMode.AGENT) {
        // OpenCode's built-in plan agent is an additional product-level guard. OmniCode still
        // rejects every side-effect approval below, so a user-modified OpenCode agent cannot
        // silently turn a Plan/Research turn into a mutation.
        addProperty("agent", "plan")
    }
    add("parts", JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", openCodeConversationText(request, agentMode))
        })
        var imageBytes = 0L
        request.messages.asSequence().flatMap { it.blocks.asSequence() }
            .filterIsInstance<ContentBlock.Image>()
            .take(MAX_OPENCODE_IMAGES)
            .forEach { image ->
                imageBytes += image.byteSize.coerceAtLeast(0)
                if (imageBytes <= MAX_OPENCODE_IMAGE_BYTES) {
                    add(JsonObject().apply {
                        addProperty("type", "file")
                        addProperty("mime", image.mediaType.take(MAX_MIME_CHARS))
                        addProperty("filename", image.fileName.take(MAX_ATTACHMENT_NAME_CHARS))
                        addProperty("url", "data:${image.mediaType};base64,${image.base64Data}")
                    })
                }
            }
    })
}

private fun openCodeConversationText(request: ModelRequest, agentMode: AgentMode): String {
    val systemPolicy = request.messages.asSequence()
        .filter { it.role == MessageRole.SYSTEM }
        .flatMap { it.blocks.asSequence() }
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
        .trim()
        .take(MAX_OPENCODE_SYSTEM_CHARS)
    val visible = buildString {
        request.messages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(MAX_OPENCODE_HISTORY_MESSAGES)
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
                }.joinToString("\n").trim().take(MAX_OPENCODE_MESSAGE_CHARS)
                if (text.isNotBlank()) {
                    if (message.role == MessageRole.ASSISTANT) append("Assistant: ")
                    append(text).append("\n\n")
                }
            }
    }.trim().takeLast(MAX_OPENCODE_PROMPT_CHARS)
    return buildString {
        appendLine("当前已在用户打开的项目根目录中运行。")
        appendLine("OmniCode 强制运行模式：${agentMode.name}。不得尝试绕过该模式或权限审批。")
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

internal fun localHostApprovalAllowed(mode: AgentMode): Boolean = mode == AgentMode.AGENT

private fun AgentMode.displayName(): String = when (this) {
    AgentMode.AGENT -> "Agent"
    AgentMode.PLAN -> "Plan"
    AgentMode.CLAUDE_PLAN -> "Claude Plan"
    AgentMode.RESEARCH -> "Research"
}

private suspend fun appendOpenCodeDelta(
    output: StringBuilder,
    delta: String,
    onTextDelta: suspend (String) -> Unit,
) {
    if (delta.isEmpty()) return
    if (output.length + delta.length > MAX_OPENCODE_OUTPUT_CHARS) {
        throw ProviderException("OpenCode 输出超过安全上限。", retryableOverride = false)
    }
    output.append(delta)
    onTextDelta(delta)
}

private fun openCodeUsage(tokens: JsonObject?): TokenUsage {
    if (tokens == null) return TokenUsage()
    val cache = tokens.objectOrNull("cache")
    val input = tokens.longOrZero("input") + cache.longOrZero("read") + cache.longOrZero("write")
    return TokenUsage(input.coerceAtLeast(0), tokens.longOrZero("output").coerceAtLeast(0))
}

private fun TokenUsage.maxWith(other: TokenUsage): TokenUsage = TokenUsage(
    maxOf(inputTokens, other.inputTokens),
    maxOf(outputTokens, other.outputTokens),
)

internal fun openCodeHostEventSessionId(event: JsonObject): String? {
    val properties = event.objectOrNull("properties") ?: return null
    return sequenceOf(
        properties.stringOrNull("sessionID"),
        properties.stringOrNull("sessionId"),
        properties.objectOrNull("info")?.stringOrNull("sessionID"),
        properties.objectOrNull("part")?.stringOrNull("sessionID"),
    ).filterNotNull().firstOrNull(SAFE_OPENCODE_HOST_SESSION_ID::matches)
}

private fun openCodeEventError(value: JsonObject): String {
    fun find(element: JsonElement?, depth: Int): String? {
        if (element == null || element.isJsonNull || depth > MAX_ERROR_SEARCH_DEPTH) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return element.asString.trim().take(MAX_ERROR_CHARS).takeIf(String::isNotBlank)
        }
        if (!element.isJsonObject) return null
        val objectValue = element.asJsonObject
        listOf("message", "name", "code", "error").forEach { key ->
            find(objectValue.get(key), depth + 1)?.let { return it }
        }
        return null
    }
    return find(value, 0) ?: "未知错误"
}

private fun terminateOpenCodeRuntime(runtime: OpenCodeHostRuntime) {
    val process = runtime.process
    runCatching { process.toHandle().descendants().forEach { it.destroy() } }
    process.destroy()
    runCatching {
        if (!process.waitFor(PROCESS_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}

private fun drainWithoutRetention(input: InputStream, threadName: String) {
    Thread {
        runCatching { input.use { stream -> stream.transferTo(java.io.OutputStream.nullOutputStream()) } }
    }.apply {
        name = threadName
        isDaemon = true
        start()
    }
}

private fun randomServerPassword(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun readBounded(input: InputStream, maxBytes: Int): String {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8_192))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw ProviderException("OpenCode 响应超过安全上限。", retryableOverride = false)
        output.write(buffer, 0, count)
    }
    return output.toString(StandardCharsets.UTF_8)
}

private fun readBoundedLine(reader: InputStreamReader, maxChars: Int): String? {
    val result = StringBuilder()
    while (true) {
        val value = reader.read()
        if (value < 0) return result.takeIf(StringBuilder::isNotEmpty)?.toString()
        if (value == '\n'.code) return result.toString().removeSuffix("\r")
        if (result.length >= maxChars) {
            throw ProviderException("OpenCode SSE 行超过安全上限。", retryableOverride = false)
        }
        result.append(value.toChar())
    }
}

private fun String.objectOrNull(): JsonObject? = runCatching { JsonParser.parseString(this).objectOrNull() }.getOrNull()
private fun String.arrayOrNull(): List<JsonElement>? = runCatching {
    JsonParser.parseString(this).takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList()
}.getOrNull()
private fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf(JsonElement::isJsonObject)?.asJsonObject
private fun JsonElement.primitiveStringOrNull(): String? = takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
    ?.takeIf { it.isString }?.asString
private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name).objectOrNull()
private fun JsonObject.arrayOrNull(name: String): JsonArray? = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
private fun JsonObject?.longOrZero(name: String): Long = this?.get(name)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isNumber
}?.asLong ?: 0L

private fun safeOpenCodeFailure(error: Throwable?): String = error?.message?.trim()?.take(MAX_ERROR_CHARS)
    ?.takeIf(String::isNotBlank) ?: error?.javaClass?.simpleName ?: "未知错误"

private val OPENCODE_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()
private val SAFE_OPENCODE_HOST_SESSION_ID = Regex("ses_[A-Za-z0-9._:-]{1,252}")
private val SAFE_OPENCODE_REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private val SAFE_OPENCODE_MESSAGE_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private const val MIN_TURN_TIMEOUT_SECONDS = 10L
private const val MAX_TURN_TIMEOUT_SECONDS = 24 * 60 * 60L
private const val MIN_HTTP_TIMEOUT_MILLIS = 1_000L
private const val MAX_HTTP_TIMEOUT_MILLIS = 15 * 60_000L
private const val HEALTH_TIMEOUT_MILLIS = 2_000L
private const val ABORT_TIMEOUT_MILLIS = 5_000L
private const val HOST_HEALTH_POLL_MILLIS = 250L
private const val HOST_STARTUP_TIMEOUT_MILLIS = 60_000L
private const val HOST_STARTUP_PROGRESS_INTERVAL_MILLIS = 5_000L
private const val OPENCODE_EVENT_POLL_MILLIS = 250L
private const val PROCESS_STOP_GRACE_MILLIS = 2_000L
private const val MAX_HTTP_RESPONSE_BYTES = 4 * 1_024 * 1_024
private const val MAX_SSE_LINE_CHARS = 256 * 1_024
private const val MAX_SSE_EVENT_CHARS = 512 * 1_024
private const val MAX_PENDING_SSE_EVENTS = 256
private const val MAX_SESSION_MESSAGES = 200
private const val MAX_OPENCODE_OUTPUT_CHARS = 4 * 1_024 * 1_024
private const val MAX_OPENCODE_PROMPT_CHARS = 120_000
private const val MAX_OPENCODE_SYSTEM_CHARS = 12_000
private const val MAX_OPENCODE_MESSAGE_CHARS = 40_000
private const val MAX_OPENCODE_HISTORY_MESSAGES = 12
private const val MAX_OPENCODE_IMAGES = 8
private const val MAX_OPENCODE_IMAGE_BYTES = 20L * 1_024 * 1_024
private const val MAX_MIME_CHARS = 128
private const val MAX_ATTACHMENT_NAME_CHARS = 240
private const val MAX_TOOL_NAME_CHARS = 160
private const val MAX_APPROVAL_DETAIL_CHARS = 4_000
private const val MAX_ERROR_SEARCH_DEPTH = 4
private const val MAX_ERROR_CHARS = 1_000
