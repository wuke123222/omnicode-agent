package dev.omnicode.agent

import com.google.gson.JsonObject
import dev.omnicode.util.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Stable, provider-neutral UI event contract used by both live runs and restored history.
 *
 * The WebView only receives this bounded envelope; provider payloads and hidden reasoning never
 * cross the bridge. Stable block ids let the frontend merge streaming deltas, tool completion,
 * and delegated-agent progress without rebuilding the whole transcript.
 */
data class ChatEventEnvelopeV1(
    val schemaVersion: Int = SCHEMA_VERSION,
    val pageGeneration: Long,
    val sessionId: String,
    val turnId: String,
    val blockId: String,
    val parentId: String? = null,
    val sequence: Long,
    val kind: String,
    val phase: String,
    val at: Instant,
    val payload: JsonObject,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(pageGeneration >= 0)
        require(sequence >= 0)
        requireSafeEnvelopeId("sessionId", sessionId)
        requireSafeEnvelopeId("turnId", turnId)
        requireSafeEnvelopeId("blockId", blockId)
        parentId?.let { requireSafeEnvelopeId("parentId", it) }
        require(kind.matches(SAFE_EVENT_NAME)) { "Unsupported event kind" }
        require(phase.matches(SAFE_EVENT_NAME)) { "Unsupported event phase" }
        require(Json.stringify(payload).length <= MAX_EVENT_PAYLOAD_CHARS) { "Event payload exceeds limit" }
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", schemaVersion)
        addProperty("pageGeneration", pageGeneration)
        addProperty("sessionId", sessionId)
        addProperty("turnId", turnId)
        addProperty("blockId", blockId)
        parentId?.let { addProperty("parentId", it) }
        addProperty("sequence", sequence)
        addProperty("kind", kind)
        addProperty("phase", phase)
        addProperty("at", at.toString())
        add("payload", payload.deepCopy())
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val MAX_EVENT_PAYLOAD_CHARS: Int = 32_000
    }
}

/** One mapper belongs to one visible turn and is never shared across sessions. */
class ChatEventEnvelopeMapper(
    private val pageGeneration: Long,
    private val sessionId: String,
    private val turnId: String,
) {
    private val sequence = AtomicLong()
    private val stableBlocks = ConcurrentHashMap<String, String>()

    fun map(event: AgentEvent): ChatEventEnvelopeV1 {
        val mapped = event.presentation()
        val blockId = stableBlocks.computeIfAbsent(mapped.stableKey) {
            safeEnvelopeId("$turnId-${mapped.stableKey}")
        }
        return ChatEventEnvelopeV1(
            pageGeneration = pageGeneration,
            sessionId = sessionId,
            turnId = turnId,
            blockId = blockId,
            parentId = mapped.parentId?.let(::safeEnvelopeId),
            sequence = sequence.incrementAndGet(),
            kind = mapped.kind,
            phase = mapped.phase,
            at = event.at,
            payload = mapped.payload,
        )
    }
}

private data class EventPresentation(
    val stableKey: String,
    val kind: String,
    val phase: String,
    val payload: JsonObject,
    val parentId: String? = null,
)

private fun AgentEvent.presentation(): EventPresentation = when (this) {
    is AgentEvent.ModeSelected -> event("mode", "run.mode", "completed") {
        addProperty("mode", mode.name)
        addProperty("title", mode.name.lowercase().replaceFirstChar(Char::uppercase))
    }
    is AgentEvent.ExecutionStrategySelected -> event("strategy", "run.strategy", "completed") {
        addProperty("strategy", strategy.name)
        addProperty("workflowId", workflowId)
        addProperty("title", "执行策略 · ${strategy.name}")
    }
    is AgentEvent.DelegatedAgentStarted -> agentEvent("started", "running", objective)
    is AgentEvent.DelegatedAgentProgress -> agentEvent("progress", "running", detail)
    is AgentEvent.DelegatedAgentCompleted -> agentEvent(
        suffix = "completed",
        phase = if (status == AgentRunStatus.COMPLETED && usable) "completed" else "failed",
        detail = detail.ifBlank { summary },
    )
    is AgentEvent.Status -> event("status-${at.toEpochMilli()}", "status", statusPhase(message)) {
        addProperty("title", statusTitle(message))
        addProperty("message", message.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
    }
    is AgentEvent.TextDelta -> event("assistant", "message.assistant.delta", "running") {
        addProperty("text", text.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
    }
    is AgentEvent.ToolRequested -> event(toolKey(name, callId), "tool.requested", "running") {
        addProperty("name", name.take(128))
        addProperty("title", toolDisplayName(name))
        addProperty("text", summary.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
        addProperty("callId", callId.take(256))
    }
    is AgentEvent.ToolApprovalResolved -> event(toolKey(name, callId), "tool.approval", when (outcome) {
        ToolApprovalOutcome.REJECTED -> "failed"
        ToolApprovalOutcome.APPROVED -> "running"
        else -> "completed"
    }) {
        addProperty("name", name.take(128))
        addProperty("title", requestTitle.take(256))
        addProperty("outcome", outcome.name)
        addProperty("callId", callId.take(256))
    }
    is AgentEvent.ToolCompleted -> event(toolKey(name, callId), "tool.completed", if (isError) "failed" else "completed") {
        addProperty("name", name.take(128))
        addProperty("title", toolDisplayName(name))
        addProperty("text", result.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
        addProperty("callId", callId.take(256))
        addProperty("isError", isError)
        addProperty("cancelled", cancelled)
        durationMillis?.let { addProperty("durationMillis", it) }
    }
    is AgentEvent.StageStarted -> event(stageKey(stage, iteration), "stage.started", "running") {
        addProperty("stage", stage.take(96))
        addProperty("title", stageDisplayName(stage))
        addProperty("iteration", iteration)
    }
    is AgentEvent.StageCompleted -> event(stageKey(stage, iteration), "stage.completed", if (success) "completed" else "failed") {
        addProperty("stage", stage.take(96))
        addProperty("title", stageDisplayName(stage))
        addProperty("text", detail.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
        addProperty("success", success)
        addProperty("durationMillis", durationMillis)
        addProperty("iteration", iteration)
    }
    is AgentEvent.ProviderRequestStarted -> event("provider-$iteration", "provider.requested", "running") {
        addProperty("title", "模型请求 #$iteration")
        addProperty("iteration", iteration)
        addProperty("attempt", attempt)
        addProperty("projectedInputTokens", projectedInputTokens)
        addProperty("projectedOutputTokens", projectedOutputTokens)
    }
    is AgentEvent.ProviderRetryScheduled -> event("provider-$iteration", "provider.retry", "warning") {
        addProperty("title", "模型请求重试")
        addProperty("message", reason.take(2_000))
        addProperty("iteration", iteration)
        addProperty("failedAttempt", failedAttempt)
        addProperty("nextAttempt", nextAttempt)
        addProperty("delayMillis", delayMillis)
    }
    is AgentEvent.UsageUpdated -> event("usage", "usage.updated", "completed") {
        addProperty("inputTokens", usage.inputTokens)
        addProperty("outputTokens", usage.outputTokens)
        addProperty("totalTokens", usage.totalTokens)
    }
    is AgentEvent.ProjectContextPrepared -> event("context", "context.prepared", "completed") {
        addProperty("title", "项目上下文")
        addProperty("message", "规则 ${rulePaths.size} · 固定 ${pinnedPaths.size} · 排除 $excludedPathCount · ~$estimatedContextTokens tokens")
        addProperty("estimatedContextTokens", estimatedContextTokens)
        addProperty("maxContextTokens", maxContextTokens)
        addProperty("truncated", truncated)
    }
    is AgentEvent.BudgetWarning -> event("budget", "budget.warning", "warning") {
        addProperty("title", "用量提醒")
        addProperty("message", "预计费用 $estimatedCostUsd / 上限 $maxCostUsd")
        addProperty("projected", projected)
    }
}

private fun AgentEvent.DelegatedAgentStarted.agentEvent(suffix: String, phase: String, detail: String) =
    event("agent-$delegationId", "agent.$suffix", phase, parentAgentId) {
        addProperty("title", "$displayName · ${role.name.lowercase()}")
        addProperty("text", detail.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
        addProperty("agentId", agentId)
        addProperty("delegationId", delegationId)
        addProperty("backend", backend.take(128))
        nativeThreadId?.let { addProperty("nativeThreadId", it) }
    }

private fun AgentEvent.DelegatedAgentProgress.agentEvent(suffix: String, phase: String, detail: String) =
    event("agent-$delegationId", "agent.$suffix", phase, parentAgentId) {
        addProperty("title", "$displayName · ${role.name.lowercase()}")
        addProperty("text", detail.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
        addProperty("agentId", agentId)
        addProperty("delegationId", delegationId)
        addProperty("backend", backend.take(128))
        nativeThreadId?.let { addProperty("nativeThreadId", it) }
    }

private fun AgentEvent.DelegatedAgentCompleted.agentEvent(suffix: String, phase: String, detail: String) =
    event("agent-$delegationId", "agent.$suffix", phase, parentAgentId) {
        addProperty("title", "$displayName · ${role.name.lowercase()}")
        addProperty("text", detail.take(ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS / 2))
        addProperty("agentId", agentId)
        addProperty("delegationId", delegationId)
        addProperty("backend", backend.take(128))
        addProperty("usable", usable)
        addProperty("status", status.name)
        addProperty("inputTokens", usage.inputTokens)
        addProperty("outputTokens", usage.outputTokens)
        nativeThreadId?.let { addProperty("nativeThreadId", it) }
    }

private inline fun event(
    stableKey: String,
    kind: String,
    phase: String,
    parentId: String? = null,
    payload: JsonObject.() -> Unit,
) = EventPresentation(stableKey, kind, phase, JsonObject().apply(payload), parentId)

private fun toolKey(name: String, callId: String): String = "tool-${callId.ifBlank { name }}"
private fun stageKey(stage: String, iteration: Int): String = "stage-$iteration-$stage"
private fun statusPhase(message: String): String = when {
    message.contains("失败") || message.contains("错误") -> "failed"
    message.contains("不可用") || message.contains("警告") -> "warning"
    else -> "running"
}
private fun statusTitle(message: String): String = message.substringBefore('·').substringBefore('：').take(96).ifBlank { "运行状态" }
private fun stageDisplayName(stage: String): String = stage.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar(Char::uppercase)
private fun toolDisplayName(name: String): String = name.replace('_', ' ').trim().replaceFirstChar(Char::uppercase)

private fun safeEnvelopeId(value: String): String = value
    .replace(Regex("[^A-Za-z0-9._:-]"), "-")
    .trim('-')
    .take(MAX_EVENT_ID_CHARS)
    .ifBlank { "event" }

private fun requireSafeEnvelopeId(field: String, value: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= MAX_EVENT_ID_CHARS) { "$field exceeds $MAX_EVENT_ID_CHARS characters" }
    require(value.matches(SAFE_EVENT_ID)) { "$field contains unsupported characters" }
}

private const val MAX_EVENT_ID_CHARS = 256
private val SAFE_EVENT_ID = Regex("[A-Za-z0-9._:-]+")
private val SAFE_EVENT_NAME = Regex("[a-z][a-z0-9._-]{0,63}")
