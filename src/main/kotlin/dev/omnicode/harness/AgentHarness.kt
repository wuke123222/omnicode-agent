package dev.omnicode.harness

import dev.omnicode.agent.AgentEngine
import dev.omnicode.agent.AgentEngineHarnessBinding
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentEventSink
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentIdentity
import dev.omnicode.agent.AgentLimits
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunResult
import dev.omnicode.model.ConversationMessage
import dev.omnicode.tool.ToolEffect
import dev.omnicode.tool.ToolRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class HarnessPreflightStatus {
    READY,
    DEGRADED_READ_ONLY,
}

data class HarnessRunSpec(
    val workflowId: String,
    val attemptId: String,
    val identity: AgentIdentity,
    val mode: AgentMode,
    val strategy: AgentExecutionStrategy,
    val limits: AgentLimits,
    val initialIteration: Int = 0,
    val initialToolCalls: Int = 0,
    val recoveryRequiresReadOnly: Boolean = false,
)

data class HarnessPreflightReport(
    val status: HarnessPreflightStatus,
    val effectiveToolNames: List<String>,
    /** Identifies the bound AgentEngine limits and effective tool surface, not sandbox or billing policy. */
    val executionSurfaceDigest: String,
)

/**
 * Formal runtime boundary around AgentEngine. V1 deliberately delegates the proven execution loop
 * while making its preflight, effective tool surface, recovery degradation, and run-surface identity
 * explicit before the wrapped AgentEngine performs its primary Provider I/O. Auxiliary image
 * preparation and MCP discovery retain their existing independent preflight boundaries.
 */
class AgentHarness(
    private val spec: HarnessRunSpec,
    private val tools: ToolRegistry,
    private val engine: AgentEngine,
    private val events: AgentEventSink,
) {
    suspend fun run(
        userMessage: ConversationMessage,
        priorMessages: List<ConversationMessage>,
    ): AgentRunResult {
        val report = HarnessPreflight.inspect(spec, tools, engine.harnessRuntimeBinding())
        events.emit(
            AgentEvent.Status(
                "Harness · ${report.status} · ${report.effectiveToolNames.size} tools · " +
                    "surface ${report.executionSurfaceDigest.take(12)}",
            ),
        )
        return engine.run(
            userMessage = userMessage,
            priorMessages = priorMessages,
            mode = spec.mode,
            safeOnlyToolSurface = spec.recoveryRequiresReadOnly,
        )
    }
}

internal object HarnessPreflight {
    fun inspect(
        spec: HarnessRunSpec,
        tools: ToolRegistry,
        binding: AgentEngineHarnessBinding? = null,
    ): HarnessPreflightReport {
        require(spec.workflowId.isNotBlank()) { "Harness workflowId must not be blank" }
        require(spec.attemptId.isNotBlank()) { "Harness attemptId must not be blank" }
        require(spec.workflowId.length <= MAX_HARNESS_ID_CHARS && SAFE_HARNESS_ID.matches(spec.workflowId)) {
            "Harness workflowId is invalid"
        }
        require(spec.attemptId.length <= MAX_HARNESS_ID_CHARS && SAFE_HARNESS_ID.matches(spec.attemptId)) {
            "Harness attemptId is invalid"
        }
        require(spec.initialIteration >= 0) { "Harness initial iteration must not be negative" }
        require(spec.initialToolCalls >= 0) { "Harness initial tool count must not be negative" }
        binding?.let { engine ->
            require(engine.tools === tools) { "Harness ToolRegistry does not match AgentEngine" }
            require(engine.identity == spec.identity) { "Harness identity does not match AgentEngine" }
            require(engine.limits == spec.limits) { "Harness limits do not match AgentEngine" }
            require(engine.initialIteration == spec.initialIteration) {
                "Harness initial iteration does not match AgentEngine"
            }
            require(engine.initialToolCalls == spec.initialToolCalls) {
                "Harness initial tool count does not match AgentEngine"
            }
            require(!engine.recoveryRequiresReadOnly || spec.recoveryRequiresReadOnly) {
                "Harness cannot weaken AgentEngine recovery restrictions"
            }
        }
        val surface = tools.descriptorsFor(spec.mode, safeOnly = spec.recoveryRequiresReadOnly)
        val unsafe = surface.filter { it.effect != ToolEffect.READ_ONLY && !it.dangerous }
        require(unsafe.isEmpty()) {
            "Harness rejected non-read-only tools without approval classification: " +
                unsafe.joinToString { it.name }
        }
        if (spec.recoveryRequiresReadOnly) {
            require(surface.none { it.dangerous }) {
                "Harness recovery surface must not expose dangerous tools"
            }
        }
        val digestSource = buildString {
            append("harness-v1|")
            append(spec.workflowId).append('|').append(spec.attemptId).append('|')
            append(spec.identity.agentId).append('|').append(spec.identity.parentAgentId.orEmpty()).append('|')
            append(spec.identity.role).append('|').append(spec.mode).append('|').append(spec.strategy).append('|')
            append(if (spec.recoveryRequiresReadOnly) "safe-only" else "normal").append('|')
            append(spec.initialIteration).append(':').append(spec.initialToolCalls).append('|')
            append(spec.limits.enforceWorkflowLimits).append(':')
            append(spec.limits.maxIterations).append(':').append(spec.limits.maxToolCalls).append(':')
            append(spec.limits.maxToolCallsPerTurn).append(':')
            append(spec.limits.maxConsecutiveFailures).append(':').append(spec.limits.maxRepeatedAction).append(':')
            append(spec.limits.maxWallTime.toMillis()).append(':').append(spec.limits.maxToolTime.toMillis()).append(':')
            append(spec.limits.maxInputTokens).append(':').append(spec.limits.maxOutputTokens).append(':')
            append(spec.limits.maxOutputTokensPerTurn).append(':').append(spec.limits.maxContextChars).append(':')
            append(spec.limits.maxObservationChars).append(':').append(spec.limits.providerMaxAttempts).append(':')
            append(spec.limits.providerRetryBaseDelay.toMillis()).append(':')
            append(spec.limits.providerRetryMaxDelay.toMillis()).append(':')
            append(spec.limits.providerRetryJitterRatio.toBits()).append('|')
            surface.forEach { descriptor ->
                append(descriptor.name).append(':')
                    .append(descriptor.effect).append(':')
                    .append(descriptor.dangerous).append(':')
                    .append(descriptor.definitionDigest).append(';')
            }
        }
        return HarnessPreflightReport(
            status = if (spec.recoveryRequiresReadOnly) {
                HarnessPreflightStatus.DEGRADED_READ_ONLY
            } else {
                HarnessPreflightStatus.READY
            },
            effectiveToolNames = surface.map { it.name },
            executionSurfaceDigest = MessageDigest.getInstance("SHA-256")
                .digest(digestSource.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }
}

private const val MAX_HARNESS_ID_CHARS = 256
private val SAFE_HARNESS_ID = Regex("[A-Za-z0-9._:-]+")
