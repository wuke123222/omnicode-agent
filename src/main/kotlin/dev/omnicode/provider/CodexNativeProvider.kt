package dev.omnicode.provider

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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

/** A bounded lifecycle observation emitted by Codex's native collaboration protocol. */
internal data class CodexNativeSubagentEvent(
    val threadId: String,
    val status: String,
    val tool: String = "",
    val prompt: String = "",
    val detail: String = "",
)

/** Result of one native Codex collaboration turn, including real child-thread observations. */
internal data class CodexNativeCollaborationResult(
    val status: dev.omnicode.agent.AgentRunStatus,
    val finalText: String,
    val usage: TokenUsage,
    val parentThreadId: String? = null,
    val subagents: List<CodexNativeSubagentEvent> = emptyList(),
    val error: Throwable? = null,
)

/** A server-side reconnect notice is not a failed turn yet. */
internal data class CodexNativeReconnectNotice(
    val attempt: Int? = null,
    val total: Int? = null,
)

private const val CODEX_RECONNECT_GRACE_MILLIS = 15_000L
private const val CODEX_EVENT_POLL_MILLIS = 500L
private const val CODEX_PROGRESS_INTERVAL_MILLIS = 5_000L
private val CODEX_RECONNECT_PROGRESS = Regex("(\\d+)\\s*/\\s*(\\d+)")

/**
 * App Server versions use a nested error object for a dropped response stream. Keep this
 * classifier deliberately narrow: ordinary model, authentication, and tool errors must still
 * fail immediately instead of being hidden behind a reconnect spinner.
 */
internal fun codexNativeReconnectNotice(payload: JsonObject): CodexNativeReconnectNotice? {
    val error = payload.jsonObjectOrNull("error") ?: payload
    val info = error.jsonObjectOrNull("codexErrorInfo")
    val message = error.stringOrNull("message").orEmpty()
    val additionalDetails = error.stringOrNull("additionalDetails").orEmpty()
    val willRetry = error.get("willRetry")
        ?.takeIf { it.isJsonPrimitive }
        ?.runCatching { asBoolean }
        ?.getOrDefault(false) == true
    val responseStreamDisconnected = info?.jsonObjectOrNull("responseStreamDisconnected") != null
    val reconnecting = message.contains("reconnecting", ignoreCase = true)
    val timedOut = additionalDetails.contains("timed out", ignoreCase = true) ||
        message.contains("timed out", ignoreCase = true)
    if (!responseStreamDisconnected && !(willRetry && (reconnecting || timedOut))) return null
    val progress = CODEX_RECONNECT_PROGRESS.find(message)
    return CodexNativeReconnectNotice(
        attempt = progress?.groupValues?.getOrNull(1)?.toIntOrNull(),
        total = progress?.groupValues?.getOrNull(2)?.toIntOrNull(),
    )
}

internal fun codexNativeReconnectStatus(notice: CodexNativeReconnectNotice): String = buildString {
    append("Codex 原生响应流已断开，App Server 正在重连")
    if (notice.attempt != null && notice.total != null) append("（${notice.attempt}/${notice.total}）")
    append("…")
}

internal fun codexNativeReconnectFailureMessage(): String =
    "Codex 原生响应流重连未完成；本轮未提交文件修改。请检查 Codex 登录、网络代理后重试。"

/** Only a definitely missing local rollout may fall back to a fresh thread without user input. */
internal fun codexNativeResumeSessionMissing(error: Throwable): Boolean {
    val message = error.message.orEmpty().lowercase()
    if (!message.contains("thread/resume")) return false
    return listOf(
        "not found",
        "does not exist",
        "unknown thread",
        "invalid thread id",
        "no rollout found",
        "rollout not found",
    ).any(message::contains)
}

/**
 * App Server emits multiple lifecycle observations for one child thread (for example running,
 * then completed). Keep insertion order while making the last observation authoritative.
 */
internal fun latestNativeSubagentEvents(
    events: List<CodexNativeSubagentEvent>,
): List<CodexNativeSubagentEvent> = buildMap {
    events.forEach { event ->
        if (event.threadId.isNotBlank()) put(event.threadId, event)
    }
}.values.toList()

/**
 * Runs one parent Codex App Server turn and lets Codex itself call its native collaboration
 * tools. This is intentionally different from launching one unrelated Codex thread per role.
 */
internal suspend fun runCodexNativeCollaboration(
    connection: ProviderConnection,
    context: CodexNativeExecutionContext,
    prompt: String,
    onTextDelta: suspend (String) -> Unit = {},
    onSubagentEvent: suspend (CodexNativeSubagentEvent) -> Unit = {},
    onProgress: suspend (String) -> Unit = {},
): CodexNativeCollaborationResult {
    val session = CodexNativeSession(connection, context)
    return try {
        withTimeout(connection.requestTimeoutSeconds.coerceIn(5, 1_800) * 1_000L) {
            session.runCollaboration(prompt, onTextDelta, onSubagentEvent, onProgress)
        }
    } catch (timeout: TimeoutCancellationException) {
        CodexNativeCollaborationResult(
            status = dev.omnicode.agent.AgentRunStatus.FAILED,
            finalText = "",
            usage = TokenUsage(),
            error = ProviderException(
                "Codex 原生协作超过 ${connection.requestTimeoutSeconds} 秒未完成。",
                networkFailure = true,
                cause = timeout,
            ),
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        CodexNativeCollaborationResult(
            status = dev.omnicode.agent.AgentRunStatus.FAILED,
            finalText = "",
            usage = TokenUsage(),
            error = error,
        )
    }
}

/** Creates the hidden backend connection used for Team specialists. It is never shown as a model. */
internal fun codexNativeSubagentConnection(template: ProviderConnection): ProviderConnection =
    template.copy(
        preset = ProviderPresets.codexNativeSubagent,
        baseUrl = ProviderPresets.codexNativeSubagent.defaultBaseUrl,
        model = ProviderPresets.codexNativeSubagent.defaultModel,
        apiKey = "",
        // Native collaboration is Codex's Ultra path; it is not the lead provider's model picker.
        reasoningEffort = ReasoningEffort.MAX,
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
    private val localSession: LocalCliSessionContext? = null,
) : ModelProvider {
    override val id: String = connection.preset.id

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse = withContext(Dispatchers.IO) {
        try {
            withTimeout(connection.requestTimeoutSeconds.coerceIn(5, 1_800) * 1_000L) {
                CodexNativeSession(connection, context, localSession).run(request, onTextDelta)
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
    private val localSession: LocalCliSessionContext? = null,
) {
    private val nextId = AtomicLong(0)
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    /**
     * The app-server stdout reader must not run on the request coroutine.  A blocking
     * BufferedReader.readLine() ignores coroutine cancellation and was the source of native
     * turns that stayed in "running" after the user pressed stop.  The channel gives the
     * suspend side a cancellation-aware receive while the dedicated reader can be interrupted
     * by closing the process streams.
     */
    private var outputLines: Channel<String>? = null
    private var outputReaderThread: Thread? = null
    private var terminalTurnSeen = false
    private var responseText = StringBuilder()
    private val childText = linkedMapOf<String, StringBuilder>()
    private val nativeSubagentEvents = mutableListOf<CodexNativeSubagentEvent>()
    /** Child ids are the only safe attribution key; unknown-id deltas are never copied to lead text. */
    private val knownChildThreadIds = linkedSetOf<String>()
    private var usage = TokenUsage()
    private var turnId: String? = null
    private var threadId: String? = null
    private var collaborationEvents: suspend (CodexNativeSubagentEvent) -> Unit = {}

    suspend fun run(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        // `receiveCatching` is cancellation-aware, but a native app-server can still be inside
        // a blocking socket read on the dedicated reader thread. Register the process teardown
        // on the request Job as well, so timeout/cancel does not have to wait for another JSON-RPC
        // line before closing the pipe and reaping the child tree.
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) closeProcess()
        }
        try {
            startProcess()
            initialize()
            val resumeId = localSession?.resumeSessionId
            var resumed = false
            val thread = if (resumeId != null) {
                try {
                    request("thread/resume", threadResumeParams(resumeId))
                        .also { resumed = true }
                } catch (error: Throwable) {
                    if (!codexNativeResumeSessionMissing(error)) throw error
                    localSession.onSessionInvalid()
                    request("thread/start", threadParams(request))
                }
            } else {
                request("thread/start", threadParams(request))
            }
            val threadId = thread.jsonObjectOrNull("thread")?.stringOrNull("id")
                ?: thread.stringOrNull("threadId")
                ?: resumeId.takeIf { resumed }
                ?: throw ProviderException("Codex 原生 App Server 未返回 thread id")
            this.threadId = threadId
            localSession?.onSessionStarted?.invoke(threadId)
            request(
                "turn/start",
                turnParams(threadId, request, resumed),
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
            cancellationHandle?.dispose()
            closeProcess()
        }
    }

    suspend fun runCollaboration(
        prompt: String,
        onTextDelta: suspend (String) -> Unit,
        onSubagentEvent: suspend (CodexNativeSubagentEvent) -> Unit,
        onProgress: suspend (String) -> Unit,
    ): CodexNativeCollaborationResult {
        collaborationEvents = onSubagentEvent
        nativeSubagentEvents.clear()
        knownChildThreadIds.clear()
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) closeProcess()
        }
        return try {
            startProcess()
            initialize(onProgress)
            val request = ModelRequest(
                messages = listOf(ConversationMessage(MessageRole.USER, prompt.take(MAX_PROMPT_CHARS))),
                tools = emptyList(),
                maxOutputTokens = MAX_NATIVE_TEAM_OUTPUT_TOKENS,
                temperature = 0.0,
            )
            val thread = request("thread/start", threadParams(request), onProgress = onProgress)
            val parentThreadId = thread.jsonObjectOrNull("thread")?.stringOrNull("id")
                ?: thread.stringOrNull("threadId")
                ?: throw ProviderException("Codex 原生协作未返回父线程 id")
            threadId = parentThreadId
            request(
                "turn/start",
                turnParams(parentThreadId, request).apply {
                    addProperty("effort", "ultra")
                },
                waitForTurn = true,
                onTextDelta = onTextDelta,
                onProgress = onProgress,
            )
            return CodexNativeCollaborationResult(
                status = if (responseText.isNotBlank()) dev.omnicode.agent.AgentRunStatus.COMPLETED
                else dev.omnicode.agent.AgentRunStatus.FAILED,
                finalText = responseText.toString(),
                usage = usage,
                parentThreadId = parentThreadId,
                subagents = nativeSubagentEvents.toList(),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Keep partial child observations when the stream fails. The lead can still show
            // which specialists actually started instead of turning every failure into a blank
            // card, and the caller can decide whether retrying is safe.
            CodexNativeCollaborationResult(
                status = dev.omnicode.agent.AgentRunStatus.FAILED,
                finalText = responseText.toString(),
                usage = usage,
                parentThreadId = threadId,
                subagents = nativeSubagentEvents.toList(),
                error = error,
            )
        } finally {
            cancellationHandle?.dispose()
            closeProcess()
        }
    }

    private fun startProcess() {
        val failures = mutableListOf<String>()
        val candidates = CodexExecutable.candidates()
        for (candidate in candidates) {
            try {
                val builder = ProcessBuilder(
                    candidate + CodexExecutable.mcpDisableOverrides() + listOf("app-server", "--stdio"),
                )
                    .directory(context.workingDirectory.toFile())
                CliToolDiscovery.applyRuntimePath(builder.environment())
                val started = builder.start()
                process = started
                input = started.outputStream.bufferedWriter()
                output = started.inputStream.bufferedReader()
                val lines = Channel<String>(capacity = 128)
                outputLines = lines
                // Keep stderr drained without ever forwarding it to the model transcript.
                Thread {
                    runCatching { started.errorStream.bufferedReader().useLines { it.forEach { _ -> } } }
                }.apply {
                    name = "omnicode-codex-stderr"
                    isDaemon = true
                    start()
                }
                outputReaderThread = Thread {
                    try {
                        val reader = output ?: return@Thread
                        while (true) {
                            val line = reader.readLine() ?: break
                            // Never block this reader thread on a suspended coroutine.  A native
                            // turn can legitimately pause while an approval callback is being
                            // handled; `runBlocking { send(...) }` used to make cancellation
                            // unable to reach closeProcess(), leaving the IDE spinner forever.
                            // Keep the queue bounded and fail the request explicitly if a peer
                            // floods it instead of silently dropping JSON-RPC messages.
                            if (lines.trySend(line).isFailure) {
                                lines.close(IllegalStateException("Codex App Server event queue is full"))
                                break
                            }
                        }
                    } catch (_: Throwable) {
                        // Closing the channel is observed by request() as an early process exit.
                    } finally {
                        lines.close()
                    }
                }.apply {
                    name = "omnicode-codex-stdout"
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

    private suspend fun initialize(onProgress: suspend (String) -> Unit = {}) {
        request(
            "initialize",
            JsonObject().apply {
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "omnicode-agent")
                    addProperty("title", "OmniCode Agent")
                    addProperty("version", CLIENT_VERSION)
                })
            },
            onProgress = onProgress,
        )
        sendNotification("initialized", JsonObject())
    }

    private fun threadParams(request: ModelRequest): JsonObject = JsonObject().apply {
        addProperty("cwd", context.workingDirectory.toAbsolutePath().normalize().toString())
        // A conversation-scoped CLI connection must persist its native rollout. Diagnostics and
        // one-off specialist runs deliberately remain ephemeral.
        addProperty("ephemeral", localSession == null)
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

    private fun threadResumeParams(threadId: String): JsonObject = JsonObject().apply {
        addProperty("threadId", threadId)
        addProperty("cwd", context.workingDirectory.toAbsolutePath().normalize().toString())
        addProperty("approvalPolicy", if (context.mode == AgentMode.AGENT) "on-request" else "never")
        addProperty("sandbox", sandboxMode())
        val model = connection.model.trim().takeUnless { it.isBlank() || it == "codex-default" }
        if (model != null) addProperty("model", model)
    }

    private fun turnParams(threadId: String, request: ModelRequest, resumed: Boolean = false): JsonObject = JsonObject().apply {
        addProperty("threadId", threadId)
        add("input", turnInput(request, resumed))
        connection.requireReasoningResolution().wireValue?.let { addProperty("effort", it) }
    }

    /** Maps the provider-neutral request to native UserInput without exposing unbounded data. */
    private fun turnInput(request: ModelRequest, resumed: Boolean = false): com.google.gson.JsonArray =
        com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", conversationText(request, resumed).take(MAX_PROMPT_CHARS))
            })
            turnMessages(request, resumed).asSequence()
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

    private fun turnMessages(request: ModelRequest, resumed: Boolean): List<ConversationMessage> {
        val visible = request.messages.filter { it.role != MessageRole.SYSTEM }
        return if (resumed) visible.takeLast(1) else visible
    }

    private fun conversationText(request: ModelRequest, resumed: Boolean = false): String = buildString {
        turnMessages(request, resumed)
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
        onProgress: suspend (String) -> Unit = {},
    ): JsonObject {
        val id = nextId.incrementAndGet().toString()
        sendRequest(id, method, params)
        var result: JsonObject? = null
        var reconnectUntilNanos: Long? = null
        val requestStartedAtNanos = System.nanoTime()
        var nextProgressAtNanos = requestStartedAtNanos + CODEX_PROGRESS_INTERVAL_MILLIS * 1_000_000L
        while (true) {
            val remainingReconnectMillis = reconnectUntilNanos?.let { deadline ->
                ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
            }
            val channel = outputLines ?: throw ProviderException(
                "Codex 原生 App Server 尚未建立事件通道。",
                networkFailure = true,
                retryableOverride = true,
            )
            // Never suspend forever on an otherwise healthy but silent app-server. Polling keeps
            // coroutine cancellation responsive and gives the UI an honest heartbeat while the
            // upstream model is queued or the local CLI is indexing the project.
            val line = withTimeoutOrNull(
                remainingReconnectMillis?.coerceAtMost(CODEX_EVENT_POLL_MILLIS.toLong())
                    ?: CODEX_EVENT_POLL_MILLIS.toLong(),
            ) { channel.receiveCatching().getOrNull() }
            if (line == null) {
                if (channel.isClosedForReceive) throw ProviderException(
                    if (remainingReconnectMillis == null) {
                        "Codex 原生 App Server 在 $method 期间提前退出。"
                    } else {
                        codexNativeReconnectFailureMessage()
                    },
                    networkFailure = true,
                    retryableOverride = true,
                )
                val now = System.nanoTime()
                if (now >= nextProgressAtNanos) {
                    val elapsed = ((now - requestStartedAtNanos) / 1_000_000_000L).coerceAtLeast(1L)
                    onProgress("Codex 原生 ${methodLabel(method)}仍在等待事件 · ${elapsed}秒 · 可随时停止")
                    nextProgressAtNanos = now + CODEX_PROGRESS_INTERVAL_MILLIS * 1_000_000L
                }
                continue
            }
            val message = runCatching { Json.parseObject(line) }.getOrElse {
                throw ProviderException("Codex 原生 App Server 返回了无效 JSON。", cause = it)
            }
            val messageId = message.get("id")?.takeUnless { it.isJsonNull }?.toString()?.trim('"')
            // JSON-RPC permits the two directions to use overlapping ids. A server approval
            // request has an id too, but is not a response until it carries result or error.
            if (messageId == id && (message.has("result") || message.has("error"))) {
                val error = message.jsonObjectOrNull("error")
                if (error != null) {
                    val reconnect = codexNativeReconnectNotice(error)
                    if (reconnect != null) {
                        onProgress(codexNativeReconnectStatus(reconnect))
                        if (reconnect.attempt != null && reconnect.total != null &&
                            reconnect.attempt >= reconnect.total
                        ) throw ProviderException(
                            codexNativeReconnectFailureMessage(),
                            networkFailure = true,
                            retryableOverride = true,
                        )
                        reconnectUntilNanos = System.nanoTime() + CODEX_RECONNECT_GRACE_MILLIS * 1_000_000L
                        continue
                    }
                    throw ProviderException(
                        "Codex 原生 App Server 请求 $method 失败：${error.stringOrNull("message") ?: "unknown error"}",
                    )
                }
                result = message.jsonObjectOrNull("result") ?: JsonObject()
                if (!waitForTurn) return result
                if (terminalTurnSeen) return result
                continue
            }
            val reconnect = handleMessage(message, onTextDelta)
            if (reconnect != null) {
                onProgress(codexNativeReconnectStatus(reconnect))
                if (reconnect.attempt != null && reconnect.total != null &&
                    reconnect.attempt >= reconnect.total
                ) throw ProviderException(
                    codexNativeReconnectFailureMessage(),
                    networkFailure = true,
                    retryableOverride = true,
                )
                reconnectUntilNanos = System.nanoTime() + CODEX_RECONNECT_GRACE_MILLIS * 1_000_000L
            } else {
                // Any valid event means the stream recovered. Do not keep applying the grace
                // timeout to unrelated later events in a long-running turn.
                reconnectUntilNanos = null
            }
            if (waitForTurn && terminalTurnSeen && result != null) return result
        }
    }

    private fun methodLabel(method: String): String = when (method) {
        "initialize" -> "正在初始化"
        "thread/start" -> "正在建立会话"
        "thread/resume" -> "正在恢复会话"
        "turn/start" -> "模型请求"
        else -> method
    }

    private suspend fun handleMessage(
        message: JsonObject,
        onTextDelta: suspend (String) -> Unit,
    ): CodexNativeReconnectNotice? {
        val method = message.stringOrNull("method") ?: return null
        val params = message.jsonObjectOrNull("params") ?: JsonObject()
        when {
            method == "item/agentMessage/delta" -> params.stringOrNull("delta")?.let { delta ->
                val sourceThreadId = params.stringOrNull("threadId")
                // Before Codex announces a child, a missing thread id can only plausibly be the
                // parent event. Once child ids are known, an id-less delta is ambiguous and must
                // be dropped rather than contaminating the lead response with child text.
                if (sourceThreadId == threadId || (sourceThreadId == null && knownChildThreadIds.isEmpty())) {
                    responseText.append(delta)
                    onTextDelta(delta)
                } else if (sourceThreadId != null) {
                    childText.getOrPut(sourceThreadId) { StringBuilder() }.append(delta)
                }
            }
            method == "item/started" -> handleNativeItem(params, started = true)
            method == "item/completed" -> handleNativeItem(params, started = false)
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
            method == "error" -> {
                val reconnect = codexNativeReconnectNotice(params)
                if (reconnect != null) return reconnect
                throw ProviderException(
                    "Codex 原生 App Server 错误：${params.stringOrNull("message") ?: Json.stringify(params).take(1_000)}",
                )
            }
            message.get("id") != null -> handleServerRequest(message, method, params)
        }
        return null
    }

    private suspend fun handleNativeItem(params: JsonObject, started: Boolean) {
        val item = params.jsonObjectOrNull("item") ?: return
        when (item.stringOrNull("type")) {
            "collabAgentToolCall" -> {
                val tool = item.stringOrNull("tool").orEmpty()
                val prompt = item.stringOrNull("prompt").orEmpty().take(MAX_SUBAGENT_DETAIL_CHARS)
                val ids = item.get("receiverThreadIds")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { element -> element.takeUnless { it.isJsonNull }?.asString }
                    .orEmpty()
                knownChildThreadIds += ids
                val status = if (started) "running" else item.stringOrNull("status") ?: "completed"
                ids.forEach { id ->
                    val event = CodexNativeSubagentEvent(
                        threadId = id,
                        status = status,
                        tool = tool,
                        prompt = prompt,
                        detail = childText[id]?.toString()?.take(MAX_SUBAGENT_DETAIL_CHARS).orEmpty()
                            .ifBlank { "Codex 子线程状态：$status" },
                    )
                    nativeSubagentEvents += event
                    collaborationEvents(event)
                }
            }
            "subAgentActivity" -> {
                val id = item.stringOrNull("agentThreadId") ?: return
                knownChildThreadIds += id
                val kind = item.stringOrNull("kind") ?: if (started) "started" else "interrupted"
                val event = CodexNativeSubagentEvent(
                        threadId = id,
                        status = if (kind == "interrupted") "interrupted" else if (started) "running" else "completed",
                        detail = childText[id]?.toString()?.take(MAX_SUBAGENT_DETAIL_CHARS).orEmpty(),
                )
                nativeSubagentEvents += event
                collaborationEvents(event)
            }
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

    @Synchronized
    private fun closeProcess() {
        val lines = outputLines
        outputLines = null
        lines?.close()
        runCatching { input?.close() }
        runCatching { output?.close() }
        process?.let { current ->
            // Destroy descendants first. The app-server may spawn a Node helper which keeps
            // stdout inherited; killing only the parent leaves the reader blocked indefinitely.
            val descendants = runCatching { current.toHandle().descendants().toList() }
                .getOrDefault(emptyList())
            descendants.forEach { handle -> runCatching { handle.destroy() } }
            if (current.isAlive) runCatching { current.destroy() }
            runCatching { current.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            // The parent may already be gone while a helper still owns stdout. Reap every
            // surviving descendant instead of treating the dead parent as a clean shutdown.
            descendants.filter { it.isAlive }.forEach { handle -> runCatching { handle.destroyForcibly() } }
            if (current.isAlive) runCatching { current.destroyForcibly() }
            runCatching { current.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        outputReaderThread?.let { thread ->
            if (thread !== Thread.currentThread()) {
                thread.interrupt()
                runCatching { thread.join(500) }
            }
        }
        outputReaderThread = null
        input = null
        output = null
        process = null
    }

    private object CodexExecutable {
        /**
         * IntelliJ launched from Finder/Toolbox does not inherit the user's login-shell PATH.
         * Resolve the same npm/Volta/nvm/Windows locations as the CLI dependency page and start
         * Node shebang launchers through their interpreter, never through a shell.
         */
        fun candidates(): List<List<String>> = buildList {
            val explicit = System.getenv("OMNICODE_CODEX_PATH")?.trim()?.takeIf(String::isNotBlank)
            val executable = CliToolDiscovery.resolveExecutable(CliTool.CODEX, explicit)
            if (executable != null) {
                runCatching { add(CliToolDiscovery.launchCommand(executable)) }
            }
            add(listOf("codex"))
            add(listOf("codex.exe"))
            add(listOf("/Applications/ChatGPT.app/Contents/Resources/codex"))
        }.distinct()

        /**
         * Native OmniCode specialists are explicitly read-only and must not call MCP. Codex
         * app-server still starts every globally configured MCP server before it receives the
         * first turn, so a slow remote server can make Reviewer look stuck. Disable only the
         * discovered server names through CLI overrides; never edit the user's config or copy
         * credentials. ProcessBuilder keeps the values out of a shell.
        */
        fun mcpDisableOverrides(): List<String> {
            val codexHome = System.getenv("CODEX_HOME")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".codex")
            val config = codexHome.resolve("config.toml")
            if (!Files.isRegularFile(config)) return emptyList()
            val names = runCatching {
                Files.newBufferedReader(config, StandardCharsets.UTF_8).useLines { lines ->
                    lines.take(MAX_CONFIG_LINES)
                        .mapNotNull { line -> MCP_SERVER_SECTION.matchEntire(line.trim())?.groupValues?.getOrNull(1) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .toList()
                }
            }.getOrDefault(emptyList())
            return names.flatMap { name -> listOf("-c", "mcp_servers.$name.enabled=false") }
        }

        private val MCP_SERVER_SECTION = Regex("\\[mcp_servers\\.([A-Za-z0-9_-]{1,80})\\]")
        private const val MAX_CONFIG_LINES = 4_096
    }

    private companion object {
        const val CLIENT_VERSION = "1.6.4"
        const val MAX_PROMPT_CHARS = 120_000
        const val MAX_INSTRUCTIONS_CHARS = 24_000
        const val MAX_IMAGES_PER_TURN = 4
        const val MAX_IMAGE_BASE64_CHARS = 12 * 1024 * 1024
        const val MAX_NATIVE_TEAM_OUTPUT_TOKENS = 65_536
        const val MAX_SUBAGENT_DETAIL_CHARS = 4_000
    }
}
