package dev.omnicode.provider

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.ToolDefinition
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.settings.SandboxMode
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    /** App Server protocol v2 places this required flag beside `error`, not inside it. */
    val willRetry: Boolean? = null,
    val httpStatusCode: Int? = null,
)

private const val CODEX_RECONNECT_LEGACY_GRACE_MILLIS = 30_000L
private const val CODEX_EVENT_POLL_MILLIS = 500L
private const val CODEX_TERMINAL_ACK_GRACE_MILLIS = 2_000L
private const val CODEX_TOOL_HANDOFF_GRACE_MILLIS = 2_000L
private const val CODEX_PROGRESS_INTERVAL_MILLIS = 5_000L
private const val CODEX_EVENT_QUEUE_CAPACITY = 512
private const val CODEX_MAX_EVENT_CHARS = 1_000_000
private const val CODEX_COLLABORATION_CLEANUP_MILLIS = 5_000L
private const val MAX_NATIVE_COLLABORATION_SECONDS = 1_800L
private val CODEX_RECONNECT_PROGRESS = Regex("(\\d+)\\s*/\\s*(\\d+)")

/** A completed parent turn is authoritative even if its JSON-RPC acknowledgement is lost. */
internal fun codexNativeTurnCanFinish(
    terminalSeen: Boolean,
    responseSeen: Boolean,
    terminalAtNanos: Long?,
    nowNanos: Long,
): Boolean = terminalSeen && (responseSeen ||
    (terminalAtNanos != null && nowNanos - terminalAtNanos >= CODEX_TERMINAL_ACK_GRACE_MILLIS * 1_000_000L))

internal fun codexNativeCurrentTurnMatches(eventTurnId: String?, currentTurnId: String?): Boolean =
    currentTurnId == null || eventTurnId == null || eventTurnId == currentTurnId

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
    val willRetry = payload.booleanOrNull("willRetry") ?: error.booleanOrNull("willRetry")
    val streamDetails = info?.jsonObjectOrNull("responseStreamDisconnected")
        ?: info?.jsonObjectOrNull("responseStreamConnectionFailed")
        ?: info?.jsonObjectOrNull("responseTooManyFailedAttempts")
    val responseStreamFailure = streamDetails != null
    val reconnecting = message.contains("reconnecting", ignoreCase = true)
    val timedOut = additionalDetails.contains("timed out", ignoreCase = true) ||
        message.contains("timed out", ignoreCase = true)
    if (!responseStreamFailure && !(willRetry == true && (reconnecting || timedOut))) return null
    val progress = CODEX_RECONNECT_PROGRESS.find(message)
    return CodexNativeReconnectNotice(
        attempt = progress?.groupValues?.getOrNull(1)?.toIntOrNull(),
        total = progress?.groupValues?.getOrNull(2)?.toIntOrNull(),
        willRetry = willRetry,
        httpStatusCode = streamDetails?.intOrNull("httpStatusCode"),
    )
}

private fun JsonObject.booleanOrNull(name: String): Boolean? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
    ?.asBoolean

internal fun codexNativeReconnectIsTerminal(notice: CodexNativeReconnectNotice): Boolean =
    notice.willRetry == false || (
        notice.willRetry == null &&
            notice.attempt != null && notice.total != null && notice.attempt >= notice.total
        )

internal fun codexNativeReconnectStatus(notice: CodexNativeReconnectNotice): String = buildString {
    append("Codex 原生上游响应流已断开")
    if (notice.willRetry != false) append("，App Server 正在自动重连")
    if (notice.attempt != null && notice.total != null) append("（${notice.attempt}/${notice.total}）")
    notice.httpStatusCode?.let { append(" · HTTP $it") }
    append("…")
}

internal fun codexNativeReconnectFailureMessage(): String =
    "Codex 登录有效，但本轮上游响应流在自动重试后仍未恢复。" +
        " 已收到的文本或文件修改会保留在当前任务与变更审阅中；可从本轮重试。" +
        " 若反复出现，请检查代理、VPN、TLS 证书或防火墙，而无需重新登录。"

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

/** Older App Servers may reject only the new field; never hide a model/auth/transport failure. */
internal fun codexNativeDynamicToolsUnsupported(error: Throwable): Boolean {
    val message = error.message.orEmpty().lowercase()
    return message.contains("thread/start") && message.contains("dynamictools") &&
        listOf("unknown field", "unrecognized", "unsupported", "unexpected field", "invalid params")
            .any(message::contains)
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

/** Exact initialize shape generated by the installed App Server schema. */
internal fun codexNativeInitializeParams(
    name: String,
    title: String,
    version: String,
    experimentalApi: Boolean,
): JsonObject = JsonObject().apply {
    add("clientInfo", JsonObject().apply {
        addProperty("name", name)
        addProperty("title", title)
        addProperty("version", version)
    })
    add("capabilities", JsonObject().apply {
        addProperty("experimentalApi", experimentalApi)
        addProperty("requestAttestation", false)
    })
}

/** App Server multiplexes parent and child notifications over the same stdout stream. */
internal fun codexNativeIsParentThreadEvent(eventThreadId: String?, parentThreadId: String?): Boolean =
    parentThreadId != null && eventThreadId == parentThreadId

/**
 * A resumed Codex thread may retain a dynamic-tool catalog from another client. OmniCode has
 * not registered those tools with AgentEngine's approval, checkpoint and audit pipeline, so a
 * callback must be rejected in the response shape required by App Server. A generic approval
 * `decision` here is invalid and can leave the native turn waiting indefinitely.
 */
internal fun codexNativeUnavailableDynamicToolResult(): JsonObject = JsonObject().apply {
    addProperty("success", false)
    add("contentItems", com.google.gson.JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "inputText")
            addProperty("text", "OmniCode did not expose this host tool to the Codex session. No action was run.")
        })
    })
}

/** App Server's generated DynamicToolSpec shape; only the current AgentEngine request is exposed. */
internal fun codexNativeDynamicToolSpecs(tools: List<ToolDefinition>): JsonArray = JsonArray().apply {
    tools.asSequence().filter { nativeHostBridgeToolName(it.name) }
        .filter { Json.stringify(it.inputSchema).length <= 16_384 }
        .take(12).forEach { tool ->
        add(JsonObject().apply {
            addProperty("type", "function")
            addProperty("name", tool.name)
            addProperty("description", tool.description.take(1_200))
            add("inputSchema", tool.inputSchema.deepCopy())
        })
    }
}

/** A native callback is a proposal, never permission to run a tool inside the provider. */
internal fun codexNativeDynamicToolHandoff(
    params: JsonObject,
    activeThreadId: String?,
    activeTurnId: String?,
    exposedTools: List<ToolDefinition>,
): ContentBlock.ToolCall? {
    if (activeThreadId.isNullOrBlank() || activeTurnId.isNullOrBlank() ||
        params.stringOrNull("threadId") != activeThreadId ||
        params.stringOrNull("turnId") != activeTurnId ||
        !params.stringOrNull("namespace").isNullOrBlank()
    ) return null
    val callId = params.stringOrNull("callId")?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
        ?: return null
    val name = params.stringOrNull("tool") ?: return null
    if (exposedTools.none { it.name == name && nativeHostBridgeToolName(name) }) return null
    val arguments = params.get("arguments")?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
    if (Json.stringify(arguments).length > 16_384) return null
    return ContentBlock.ToolCall(callId, name, arguments.deepCopy())
}

/** Never prompt for a request belonging to another turn or for an undisclosed escalation. */
internal fun codexNativeApprovalEligible(
    method: String,
    params: JsonObject,
    activeThreadId: String?,
    activeTurnId: String?,
    mode: AgentMode,
): Boolean {
    if (mode != AgentMode.AGENT || activeThreadId.isNullOrBlank() || activeTurnId.isNullOrBlank()) return false
    if (params.stringOrNull("threadId") != activeThreadId || params.stringOrNull("turnId") != activeTurnId) return false
    return when (method) {
        "item/commandExecution/requestApproval" -> {
            val kind = params.stringOrNull("kind")
            val rawDecisions = params.get("availableDecisions")
            val decisions = rawDecisions?.takeIf { it.isJsonArray }?.asJsonArray
            (kind == null || kind == "command") &&
                !params.stringOrNull("command").isNullOrBlank() &&
                params.get("additionalPermissions")?.isJsonNull != false &&
                params.get("environmentId")?.isJsonNull != false &&
                (rawDecisions == null || rawDecisions.isJsonNull || decisions != null) &&
                (decisions == null || decisions.any { it.isJsonPrimitive && it.asString == "accept" })
        }
        "item/fileChange/requestApproval" -> params.get("grantRoot")?.isJsonNull != false
        else -> false
    }
}

internal data class CodexNativeTurnUsageSnapshot(
    val baseline: TokenUsage,
    val usage: TokenUsage,
)

/** `total` is thread-cumulative; `last` is only the latest model increment. */
internal fun codexNativeTurnUsageUpdate(
    params: JsonObject,
    activeTurnId: String?,
    previous: CodexNativeTurnUsageSnapshot?,
): CodexNativeTurnUsageSnapshot? {
    if (activeTurnId.isNullOrBlank() || params.stringOrNull("turnId") != activeTurnId) return null
    val tokenUsage = params.jsonObjectOrNull("tokenUsage") ?: return null
    val total = tokenUsage.jsonObjectOrNull("total") ?: return null
    val last = tokenUsage.jsonObjectOrNull("last") ?: return null
    if (!total.has("inputTokens") || !total.has("outputTokens") ||
        !last.has("inputTokens") || !last.has("outputTokens")
    ) return null
    val absolute = TokenUsage(total.longOrZero("inputTokens"), total.longOrZero("outputTokens"))
    val increment = TokenUsage(last.longOrZero("inputTokens"), last.longOrZero("outputTokens"))
    if (absolute.inputTokens < 0 || absolute.outputTokens < 0 ||
        increment.inputTokens < 0 || increment.outputTokens < 0
    ) return null
    val baseline = previous?.baseline ?: TokenUsage(
        (absolute.inputTokens - increment.inputTokens).coerceAtLeast(0),
        (absolute.outputTokens - increment.outputTokens).coerceAtLeast(0),
    )
    val next = TokenUsage(
        (absolute.inputTokens - baseline.inputTokens).coerceAtLeast(0),
        (absolute.outputTokens - baseline.outputTokens).coerceAtLeast(0),
    )
    if (previous != null && (next.inputTokens < previous.usage.inputTokens ||
            next.outputTokens < previous.usage.outputTokens)
    ) return null
    return CodexNativeTurnUsageSnapshot(baseline, next)
}

/** Normalize both collaboration-tool and child-activity states for the conversation timeline. */
internal fun codexNativeSubagentStatus(rawStatus: String?, itemStarted: Boolean = false): String =
    when (rawStatus?.trim()?.lowercase()) {
        "pendinginit", "inprogress", "running", "started", "interacted" -> "running"
        "completed", "complete", "shutdown" -> "completed"
        "interrupted", "cancelled", "canceled" -> "interrupted"
        "failed", "errored", "notfound" -> "failed"
        else -> if (itemStarted) "running" else "completed"
    }

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
        // The lead provider's Auto/Low timeout is commonly 120 seconds. A native Team turn can
        // legitimately spend longer while child threads inspect a large repository, and App
        // Server already emits cancellation-aware heartbeats. Do not inherit the shorter lead
        // timeout and then report a healthy Codex login as failed.
        requestTimeoutSeconds = maxOf(template.requestTimeoutSeconds, MAX_NATIVE_COLLABORATION_SECONDS),
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
        val bridgeTools = nativeHostBridgeTools(request)
        val deltaFilter = bridgeTools.takeIf { it.isNotEmpty() }?.let { CliBridgeDeltaFilter(onTextDelta) }
        try {
            withTimeout(connection.requestTimeoutSeconds.coerceIn(5, 1_800) * 1_000L) {
                CodexNativeSession(connection, context, localSession).run(
                    request,
                    deltaFilter?.let { filter -> { delta -> filter.accept(delta) } } ?: onTextDelta,
                ).let { parseCliBridgeResponse(it, bridgeTools) }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ProviderException(
                "Codex 原生 App Server 超过 ${connection.requestTimeoutSeconds} 秒未完成请求。",
                networkFailure = true,
                cause = timeout,
            )
        } finally {
            deltaFilter?.finish()
        }
    }
}

/** Codex app-server model discovery is local to the user's authenticated Codex installation. */
internal object CodexNativeModelDiscovery {
    suspend fun discover(connection: ProviderConnection): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val executable = CliToolDiscovery.resolveExecutable(CliTool.CODEX, connection.baseUrl)
            ?: throw ProviderException(
                "找不到 Codex CLI。请先在终端确认 codex --version 并完成登录。",
                retryableOverride = false,
            )
        val builder = ProcessBuilder(
            CliToolDiscovery.launchCommand(executable) + listOf("app-server", "--stdio"),
        ).directory(Path.of(System.getProperty("user.dir", ".")).toFile())
        CliToolDiscovery.applyRuntimePath(builder.environment(), executable)
        val process = runCatching { builder.start() }.getOrElse { error ->
            throw ProviderException(
                "无法启动 Codex App Server 读取模型。请在 CLI 页面重新检测 Codex。",
                networkFailure = true,
                cause = error,
            )
        }
        val lines = Channel<String>(capacity = MODEL_EVENT_QUEUE_CAPACITY)
        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.length > MAX_MODEL_EVENT_CHARS) {
                            lines.close(IllegalStateException("Codex App Server model event is too large"))
                            break
                        }
                        if (lines.trySend(line).isFailure) {
                            lines.close(IllegalStateException("Codex App Server model event queue is full"))
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
                // Process teardown closes the stream. The channel failure below is enough for UI diagnostics.
            } finally {
                lines.close()
            }
        }.apply {
            name = "omnicode-codex-models-stdout"
            isDaemon = true
            start()
        }
        Thread {
            runCatching { process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { it.forEach { _ -> } } }
        }.apply {
            name = "omnicode-codex-models-stderr"
            isDaemon = true
            start()
        }

        try {
            val timeoutSeconds = connection.requestTimeoutSeconds.coerceIn(3L, MAX_MODEL_DISCOVERY_SECONDS)
            withTimeout(timeoutSeconds * 1_000L) {
                val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                var nextId = 0L
                fun write(message: JsonObject) {
                    writer.write(Json.stringify(message))
                    writer.newLine()
                    writer.flush()
                }
                fun request(method: String, params: JsonObject): String {
                    val id = (++nextId).toString()
                    write(JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        addProperty("id", id)
                        addProperty("method", method)
                        add("params", params)
                    })
                    return id
                }
                fun notify(method: String, params: JsonObject) {
                    write(JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        addProperty("method", method)
                        add("params", params)
                    })
                }
                suspend fun await(id: String): JsonObject {
                    while (true) {
                        val received = lines.receiveCatching()
                        val line = received.getOrNull() ?: run {
                            throw ProviderException(
                                "Codex App Server 在返回模型列表前已退出。请检查 Codex 登录状态。",
                                retryableOverride = false,
                                cause = received.exceptionOrNull(),
                            )
                        }
                        val payload = runCatching { Json.parseObject(line) }.getOrNull() ?: continue
                        val responseId = payload.get("id")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                        if (responseId != id) continue
                        payload.jsonObjectOrNull("error")?.let { error ->
                            val message = error.stringOrNull("message")
                                ?.take(MAX_MODEL_ERROR_CHARS)
                                .orEmpty()
                            throw ProviderException(
                                "Codex App Server 无法读取模型${message.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}",
                                retryableOverride = false,
                            )
                        }
                        return payload.jsonObjectOrNull("result")
                            ?: throw ProviderException("Codex App Server 返回了无效的模型响应。", retryableOverride = false)
                    }
                }

                val initializeId = request(
                    "initialize",
                    codexNativeInitializeParams(
                        name = "omnicode-model-discovery",
                        title = "OmniCode model discovery",
                        version = "3.0.0",
                        experimentalApi = false,
                    ),
                )
                await(initializeId)
                notify("initialized", JsonObject())

                val models = linkedSetOf<String>()
                var cursor: String? = null
                repeat(MAX_MODEL_PAGES) {
                    val responseId = request("model/list", JsonObject().apply {
                        addProperty("includeHidden", false)
                        addProperty("limit", MODEL_PAGE_SIZE)
                        cursor?.let { addProperty("cursor", it) }
                    })
                    val result = await(responseId)
                    models += parseModels(result)
                    val next = result.stringOrNull("nextCursor")?.trim().orEmpty()
                    if (next.isBlank()) {
                        if (models.isEmpty()) {
                            throw ProviderException(
                                "Codex 登录态没有返回可选模型。请先在终端运行 codex 并完成登录。",
                                retryableOverride = false,
                            )
                        }
                        return@withTimeout ModelDiscoveryResult(
                            models = models.take(MAX_MODELS),
                            discoveredRemotely = false,
                            status = "已从本机 Codex 登录读取 ${models.size.coerceAtMost(MAX_MODELS)} 个可用模型。",
                        )
                    }
                    if (next == cursor) throw ProviderException("Codex 模型目录返回了重复游标。")
                    cursor = next
                }
                throw ProviderException("Codex 模型目录超过 $MAX_MODEL_PAGES 页，已停止读取。")
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ProviderException(
                "Codex App Server 未在限定时间内返回模型列表。请检查登录状态后重试。",
                networkFailure = true,
                retryableOverride = false,
                cause = timeout,
            )
        } finally {
            lines.close()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            terminateCodexModelProcess(process)
            stdoutThread.interrupt()
            runCatching { stdoutThread.join(500) }
        }
    }

    internal fun parseModels(result: JsonObject): List<String> = result.get("data")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.asSequence()
        ?.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { model ->
                model.stringOrNull("model") ?: model.stringOrNull("id")
            }
        }
        ?.map(String::trim)
        ?.filter(MODEL_ID::matches)
        ?.distinct()
        ?.take(MAX_MODELS)
        ?.toList()
        .orEmpty()

    private fun terminateCodexModelProcess(process: Process) {
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        descendants.forEach { handle -> runCatching { handle.destroy() } }
        if (process.isAlive) runCatching { process.destroy() }
        runCatching { process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        descendants.filter { it.isAlive }.forEach { handle -> runCatching { handle.destroyForcibly() } }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
    private const val MODEL_PAGE_SIZE = 100
    private const val MAX_MODEL_PAGES = 8
    private const val MAX_MODELS = 512
    private const val MODEL_EVENT_QUEUE_CAPACITY = 128
    private const val MAX_MODEL_EVENT_CHARS = 1_000_000
    private const val MAX_MODEL_ERROR_CHARS = 400
    private const val MAX_MODEL_DISCOVERY_SECONDS = 20L
}

/** Shared formatter for new and resumed native turns; approved host results are replayed as data. */
internal fun codexNativeConversationText(messages: List<ConversationMessage>): String = buildString {
    messages.forEach { message ->
        val label = message.role.name.lowercase()
        val text = message.blocks.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> block.text
                is ContentBlock.TransientProjectContext -> block.text
                is ContentBlock.ToolResult -> cliConversationBlockText(block)
                is ContentBlock.ToolCall -> "[tool call] ${block.name} ${Json.stringify(block.arguments)}"
                is ContentBlock.Image -> "[image attachment: ${block.fileName}]"
            }
        }.joinToString("\n")
        if (text.isNotBlank()) append("[$label]\n$text\n\n")
    }
}.trim()

internal class CodexNativeSession(
    private val connection: ProviderConnection,
    private val context: CodexNativeExecutionContext,
    private val localSession: LocalCliSessionContext? = null,
    /** Deterministic protocol fixtures may supply a local fake; production always resolves Codex. */
    private val launchCandidates: List<List<String>>? = null,
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
    private var turnUsageSnapshot: CodexNativeTurnUsageSnapshot? = null
    private var turnId: String? = null
    private var threadId: String? = null
    private var exposedHostTools: List<ToolDefinition> = emptyList()
    private var nativeToolsRegistered = false
    private var handoffToolCall: ContentBlock.ToolCall? = null
    private var handoffRequestedAtNanos: Long? = null
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
            exposedHostTools = if (context.mode == AgentMode.AGENT) {
                nativeHostBridgeTools(request).filter { Json.stringify(it.inputSchema).length <= 16_384 }
            } else {
                emptyList()
            }
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
                    startThread(request)
                }
            } else {
                startThread(request)
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
                handoffToolCall != null -> StopReason.TOOL_USE
                terminalTurnSeen -> StopReason.COMPLETE
                responseText.isNotEmpty() -> StopReason.COMPLETE
                else -> StopReason.UNKNOWN
            }
            return ModelResponse(
                blocks = listOfNotNull(
                    responseText.toString().takeIf { handoffToolCall == null && it.isNotBlank() }?.let(ContentBlock::Text),
                    handoffToolCall,
                ),
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
        return try {
            startProcess()
            initialize(onProgress)
            val request = ModelRequest(
                messages = listOf(ConversationMessage(MessageRole.USER, prompt.take(MAX_PROMPT_CHARS))),
                tools = emptyList(),
                maxOutputTokens = MAX_NATIVE_TEAM_OUTPUT_TOKENS,
                temperature = 0.0,
            )
            // Native spawn resolves the parent through Codex's thread store. An ephemeral parent
            // is invisible there and makes the first child fail with "no thread with id".
            val thread = request(
                "thread/start",
                threadParams(request, ephemeral = false),
                onProgress = onProgress,
            )
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
            val result = CodexNativeCollaborationResult(
                status = if (responseText.isNotBlank()) dev.omnicode.agent.AgentRunStatus.COMPLETED
                else dev.omnicode.agent.AgentRunStatus.FAILED,
                finalText = responseText.toString(),
                usage = usage,
                parentThreadId = parentThreadId,
                subagents = nativeSubagentEvents.toList(),
            )
            cleanupCollaborationThread(interruptActiveTurn = false)
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            cleanupCollaborationThread(interruptActiveTurn = true)
            throw cancelled
        } catch (error: Throwable) {
            // Keep partial child observations when the stream fails. The lead can still show
            // which specialists actually started instead of turning every failure into a blank
            // card, and the caller can decide whether retrying is safe.
            val result = CodexNativeCollaborationResult(
                status = dev.omnicode.agent.AgentRunStatus.FAILED,
                finalText = responseText.toString(),
                usage = usage,
                parentThreadId = threadId,
                subagents = nativeSubagentEvents.toList(),
                error = error,
            )
            cleanupCollaborationThread(interruptActiveTurn = !terminalTurnSeen)
            result
        } finally {
            closeProcess()
        }
    }

    private fun startProcess() {
        val failures = mutableListOf<String>()
        val candidates = launchCandidates ?: CodexExecutable.candidates()
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
                val lines = Channel<String>(capacity = CODEX_EVENT_QUEUE_CAPACITY)
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
                            if (line.length > CODEX_MAX_EVENT_CHARS) {
                                lines.close(IllegalStateException("Codex App Server event exceeds the safety limit"))
                                break
                            }
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
            codexNativeInitializeParams(
                name = "omnicode-agent",
                title = "OmniCode Agent",
                version = CLIENT_VERSION,
                experimentalApi = true,
            ),
            onProgress = onProgress,
        )
        sendNotification("initialized", JsonObject())
    }

    private suspend fun startThread(request: ModelRequest): JsonObject {
        if (exposedHostTools.isEmpty()) return request("thread/start", threadParams(request))
        return try {
            request("thread/start", threadParams(request)).also { nativeToolsRegistered = true }
        } catch (error: ProviderException) {
            if (!codexNativeDynamicToolsUnsupported(error)) throw error
            // Only an explicit unsupported-field response permits the bounded marker fallback.
            request("thread/start", threadParams(request, includeDynamicTools = false))
        }
    }

    private fun threadParams(
        request: ModelRequest,
        ephemeral: Boolean = localSession == null,
        includeDynamicTools: Boolean = true,
    ): JsonObject = JsonObject().apply {
        addProperty("cwd", context.workingDirectory.toAbsolutePath().normalize().toString())
        addProperty("ephemeral", ephemeral)
        addProperty("approvalPolicy", if (context.mode == AgentMode.AGENT) "on-request" else "never")
        addProperty("sandbox", sandboxMode())
        if (includeDynamicTools) {
            codexNativeDynamicToolSpecs(exposedHostTools).takeIf { it.size() > 0 }?.let { add("dynamicTools", it) }
        }
        val model = connection.model.trim().takeUnless {
            it.isBlank() || isLocalCliModelPlaceholder(connection.preset.protocol, it)
        }
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
        val model = connection.model.trim().takeUnless {
            it.isBlank() || isLocalCliModelPlaceholder(connection.preset.protocol, it)
        }
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
                addProperty(
                    "text",
                    if (nativeToolsRegistered) conversationText(request, resumed).take(MAX_PROMPT_CHARS)
                    else nativeHostBridgePrompt(conversationText(request, resumed), nativeHostBridgeTools(request), MAX_PROMPT_CHARS),
                )
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

    private fun conversationText(request: ModelRequest, resumed: Boolean = false): String =
        codexNativeConversationText(turnMessages(request, resumed))

    private fun sandboxMode(): String = when {
        context.mode != AgentMode.AGENT -> "read-only"
        context.sandboxMode == SandboxMode.DANGER_FULL_ACCESS -> "danger-full-access"
        else -> "workspace-write"
    }

    /** Remove OmniCode's internal parent and every spawned descendant after the bounded run. */
    private suspend fun cleanupCollaborationThread(interruptActiveTurn: Boolean) {
        val parentThreadId = threadId ?: return
        withContext(NonCancellable) {
            withTimeoutOrNull(CODEX_COLLABORATION_CLEANUP_MILLIS) {
                if (interruptActiveTurn) {
                    turnId?.let { activeTurnId ->
                        try {
                            request("turn/interrupt", JsonObject().apply {
                                addProperty("threadId", parentThreadId)
                                addProperty("turnId", activeTurnId)
                            })
                        } catch (_: Throwable) {
                            // Deletion below remains authoritative and recursively removes children.
                        }
                    }
                }
                try {
                    request("thread/delete", JsonObject().apply {
                        addProperty("threadId", parentThreadId)
                    })
                } catch (_: Throwable) {
                    // Some Codex releases can run collaboration while their local state DB is
                    // still missing a newer deletion table. Archive keeps the internal thread
                    // out of user history; process-tree teardown remains the fail-closed fallback.
                    try {
                        request("thread/archive", JsonObject().apply {
                            addProperty("threadId", parentThreadId)
                        })
                    } catch (_: Throwable) {
                        // Best effort only: never turn a successful model result into cleanup failure.
                    }
                }
            }
        }
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
        var terminalObservedAtNanos: Long? = null
        var reconnectUntilNanos: Long? = null
        var pendingReconnect: CodexNativeReconnectNotice? = null
        val requestStartedAtNanos = System.nanoTime()
        var nextProgressAtNanos = requestStartedAtNanos + CODEX_PROGRESS_INTERVAL_MILLIS * 1_000_000L
        while (true) {
            if (waitForTurn && handoffToolCall != null && (terminalTurnSeen ||
                    handoffRequestedAtNanos?.let {
                        System.nanoTime() - it >= CODEX_TOOL_HANDOFF_GRACE_MILLIS * 1_000_000L
                    } == true
                )) return result ?: JsonObject()
            if (waitForTurn && terminalTurnSeen) {
                if (terminalObservedAtNanos == null) terminalObservedAtNanos = System.nanoTime()
                if (codexNativeTurnCanFinish(true, result != null, terminalObservedAtNanos, System.nanoTime())) {
                    return result ?: JsonObject()
                }
            }
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
            val received = withTimeoutOrNull(
                remainingReconnectMillis?.coerceAtMost(CODEX_EVENT_POLL_MILLIS.toLong())
                    ?: CODEX_EVENT_POLL_MILLIS.toLong(),
            ) { channel.receiveCatching() }
            if (received == null) {
                val now = System.nanoTime()
                if (reconnectUntilNanos != null && now >= reconnectUntilNanos) throw ProviderException(
                    codexNativeReconnectFailureMessage(),
                    networkFailure = true,
                    retryableOverride = true,
                )
                if (now >= nextProgressAtNanos) {
                    val elapsed = ((now - requestStartedAtNanos) / 1_000_000_000L).coerceAtLeast(1L)
                    onProgress("Codex 原生 ${methodLabel(method)}仍在等待事件 · ${elapsed}秒 · 可随时停止")
                    nextProgressAtNanos = now + CODEX_PROGRESS_INTERVAL_MILLIS * 1_000_000L
                }
                continue
            }
            val line = received.getOrNull()
            if (line == null) {
                if (waitForTurn && handoffToolCall != null) return result ?: JsonObject()
                if (waitForTurn && terminalTurnSeen) return result ?: JsonObject()
                val channelFailure = received.exceptionOrNull()
                val detail = channelFailure?.message.orEmpty()
                val message = when {
                    detail.contains("queue is full", ignoreCase = true) ->
                        "Codex 原生事件速度超过界面消费能力，已安全停止本轮；请从本轮重试。"
                    detail.contains("safety limit", ignoreCase = true) ->
                        "Codex 原生返回了超过安全上限的事件，已安全停止本轮。"
                    pendingReconnect != null -> codexNativeReconnectFailureMessage()
                    else -> "Codex 原生 App Server 在 $method 期间提前退出。登录状态通常不受影响，可直接重试。"
                }
                throw ProviderException(
                    message,
                    networkFailure = channelFailure == null || pendingReconnect != null,
                    retryableOverride = channelFailure == null || pendingReconnect != null,
                    cause = channelFailure,
                )
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
                    // ErrorNotification places `willRetry` beside the nested TurnError. Pass the
                    // complete envelope or a valid retry would be mistaken for a terminal error.
                    val reconnect = codexNativeReconnectNotice(message)
                    if (reconnect != null) {
                        onProgress(codexNativeReconnectStatus(reconnect))
                        if (codexNativeReconnectIsTerminal(reconnect)) throw ProviderException(
                            codexNativeReconnectFailureMessage(),
                            networkFailure = true,
                            retryableOverride = true,
                        )
                        pendingReconnect = reconnect
                        // Protocol v2 makes `willRetry` authoritative. Let App Server finish its
                        // own bounded retry loop; the provider's total request timeout remains the
                        // outer hard stop. Legacy notices get a separate bounded grace period.
                        reconnectUntilNanos = if (reconnect.willRetry == true) null else {
                            System.nanoTime() + CODEX_RECONNECT_LEGACY_GRACE_MILLIS * 1_000_000L
                        }
                        continue
                    }
                    throw ProviderException(
                        "Codex 原生 App Server 请求 $method 失败：${error.stringOrNull("message") ?: "unknown error"}",
                    )
                }
                result = message.jsonObjectOrNull("result") ?: JsonObject()
                if (method == "turn/start") {
                    turnId = result.jsonObjectOrNull("turn")?.stringOrNull("id") ?: turnId
                }
                if (!waitForTurn) return result
                if (terminalTurnSeen) return result
                continue
            }
            val reconnect = handleMessage(message, onTextDelta, onProgress)
            if (reconnect != null) {
                onProgress(codexNativeReconnectStatus(reconnect))
                if (codexNativeReconnectIsTerminal(reconnect)) throw ProviderException(
                    codexNativeReconnectFailureMessage(),
                    networkFailure = true,
                    retryableOverride = true,
                )
                pendingReconnect = reconnect
                reconnectUntilNanos = if (reconnect.willRetry == true) null else {
                    System.nanoTime() + CODEX_RECONNECT_LEGACY_GRACE_MILLIS * 1_000_000L
                }
            } else {
                // Any valid event means the stream recovered. Do not keep applying the grace
                // timeout to unrelated later events in a long-running turn.
                reconnectUntilNanos = null
                pendingReconnect = null
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
        onProgress: suspend (String) -> Unit,
    ): CodexNativeReconnectNotice? {
        val method = message.stringOrNull("method") ?: return null
        val params = message.jsonObjectOrNull("params") ?: JsonObject()
        when {
            method == "item/agentMessage/delta" -> params.stringOrNull("delta")?.let { delta ->
                if (handoffToolCall != null) return@let
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
            method == "thread/tokenUsage/updated" -> {
                if (codexNativeIsParentThreadEvent(params.stringOrNull("threadId"), threadId)) {
                    codexNativeTurnUsageUpdate(params, turnId, turnUsageSnapshot)?.let { update ->
                        turnUsageSnapshot = update
                        usage = update.usage
                    }
                }
            }
            method == "turn/started" -> {
                if (codexNativeIsParentThreadEvent(params.stringOrNull("threadId"), threadId)) {
                    turnId = params.jsonObjectOrNull("turn")?.stringOrNull("id") ?: turnId
                }
            }
            method == "turn/completed" -> {
                val sourceThreadId = params.stringOrNull("threadId")
                val turn = params.jsonObjectOrNull("turn")
                val status = turn?.stringOrNull("status")
                if (codexNativeIsParentThreadEvent(sourceThreadId, threadId) &&
                    codexNativeCurrentTurnMatches(turn?.stringOrNull("id"), turnId)
                ) {
                    terminalTurnSeen = true
                    turnId = turn?.stringOrNull("id") ?: turnId
                    if (turnUsageSnapshot == null) {
                        usage = usageFrom(turn ?: params)
                            .takeIf { it.inputTokens > 0 || it.outputTokens > 0 } ?: usage
                    }
                    if (handoffToolCall == null && status != null && status !in setOf("completed", "complete")) {
                        throw ProviderException("Codex 原生 turn 结束状态：$status")
                    }
                } else if (sourceThreadId != null && sourceThreadId in knownChildThreadIds) {
                    val normalized = codexNativeSubagentStatus(status)
                    val event = CodexNativeSubagentEvent(
                        threadId = sourceThreadId,
                        status = normalized,
                        detail = childText[sourceThreadId]?.toString()
                            ?.take(MAX_SUBAGENT_DETAIL_CHARS).orEmpty()
                            .ifBlank { "Codex 子线程状态：$normalized" },
                    )
                    nativeSubagentEvents += event
                    collaborationEvents(event)
                }
            }
            method == "error" -> {
                val reconnect = codexNativeReconnectNotice(params)
                val sourceThreadId = params.stringOrNull("threadId")
                if (sourceThreadId != null && !codexNativeIsParentThreadEvent(sourceThreadId, threadId)) {
                    if (sourceThreadId in knownChildThreadIds && (reconnect == null || codexNativeReconnectIsTerminal(reconnect))) {
                        val event = CodexNativeSubagentEvent(
                            threadId = sourceThreadId,
                            status = "failed",
                            detail = params.stringOrNull("message").orEmpty().take(MAX_SUBAGENT_DETAIL_CHARS),
                        )
                        nativeSubagentEvents += event
                        collaborationEvents(event)
                    }
                    reconnect?.let { onProgress("Codex 子线程 ${sourceThreadId.take(8)} 正在恢复响应流…") }
                    return null
                }
                if (reconnect != null) return reconnect
                throw ProviderException(
                    "Codex 原生 App Server 错误：${params.stringOrNull("message") ?: Json.stringify(params).take(1_000)}",
                )
            }
            method == "warning" -> params.stringOrNull("message")?.let { warning ->
                if (warning.contains("falling back", ignoreCase = true) ||
                    warning.contains("https", ignoreCase = true)
                ) {
                    onProgress("Codex WebSocket 不可用，App Server 已切换 HTTPS，任务继续运行…")
                }
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
                val status = codexNativeSubagentStatus(item.stringOrNull("status"), started)
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
                val kind = item.stringOrNull("kind") ?: if (started) "started" else null
                val event = CodexNativeSubagentEvent(
                        threadId = id,
                        status = codexNativeSubagentStatus(kind, started),
                        detail = childText[id]?.toString()?.take(MAX_SUBAGENT_DETAIL_CHARS).orEmpty(),
                )
                nativeSubagentEvents += event
                collaborationEvents(event)
            }
        }
    }

    private suspend fun handleServerRequest(message: JsonObject, method: String, params: JsonObject) {
        val id = message.get("id") ?: return
        if (method == "item/tool/call") {
            val call = if (handoffToolCall == null) {
                codexNativeDynamicToolHandoff(params, threadId, turnId, exposedHostTools)
            } else null
            if (call == null) {
                sendResponse(id, codexNativeUnavailableDynamicToolResult())
                return
            }
            // App Server cannot pause a dynamic callback while AgentEngine runs approval and
            // checkpoints. End this native turn, then let AgentEngine execute the proposed call
            // through its existing tool path and return the result in the next model turn.
            sendResponse(id, JsonObject().apply {
                addProperty("success", false)
                add("contentItems", JsonArray().apply { add(JsonObject().apply {
                    addProperty("type", "inputText")
                    addProperty("text", "OmniCode has taken over this tool request for approval and execution. Do not retry it in this turn.")
                }) })
            })
            sendRequest(nextId.incrementAndGet().toString(), "turn/interrupt", JsonObject().apply {
                addProperty("threadId", threadId)
                addProperty("turnId", turnId)
            })
            handoffToolCall = call
            handoffRequestedAtNanos = System.nanoTime()
            return
        }
        if (method != "item/commandExecution/requestApproval" &&
            method != "item/fileChange/requestApproval"
        ) {
            sendError(id, -32601, "Unsupported Codex App Server request: ${method.take(128)}")
            return
        }
        val accepted = if (!codexNativeApprovalEligible(method, params, threadId, turnId, context.mode)) {
            false
        } else when (method) {
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

    private fun sendError(id: com.google.gson.JsonElement, code: Int, message: String) {
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
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
