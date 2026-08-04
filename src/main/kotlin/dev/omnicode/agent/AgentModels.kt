package dev.omnicode.agent

import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.TokenUsage
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class AgentLimits(
    val maxIterations: Int = 24,
    val maxToolCalls: Int = 32,
    val maxToolCallsPerTurn: Int = 32,
    val maxConsecutiveFailures: Int = 3,
    val maxRepeatedAction: Int = 2,
    val maxWallTime: Duration = Duration.ofMinutes(10),
    val maxToolTime: Duration = Duration.ofMinutes(5),
    /**
     * Finite cumulative workflow guards are useful for tests and opt-in bounded runs. Production
     * continuous execution disables them while retaining cancellation, per-tool timeouts, retry
     * limits, repeated-action detection, consecutive-failure detection, approvals, and sandboxing.
     */
    val enforceWorkflowLimits: Boolean = true,
    val maxInputTokens: Long = Long.MAX_VALUE,
    val maxOutputTokens: Long = Long.MAX_VALUE,
    val maxOutputTokensPerTurn: Int = 8_192,
    val maxContextChars: Int = 180_000,
    val maxObservationChars: Int = 24_000,
    val providerMaxAttempts: Int = 3,
    val providerRetryBaseDelay: Duration = Duration.ofMillis(500),
    val providerRetryMaxDelay: Duration = Duration.ofSeconds(30),
    val providerRetryJitterRatio: Double = 0.2,
) {
    init {
        require(maxIterations > 0)
        require(maxToolCalls > 0)
        require(maxToolCallsPerTurn > 0)
        require(maxConsecutiveFailures > 0)
        require(maxRepeatedAction > 0)
        require(!maxWallTime.isNegative && !maxWallTime.isZero)
        require(!maxToolTime.isNegative && !maxToolTime.isZero)
        require(maxInputTokens > 0)
        require(maxOutputTokens > 0)
        require(maxOutputTokensPerTurn > 0)
        require(maxContextChars > 0)
        require(maxObservationChars > 0)
        require(providerMaxAttempts in 1..10)
        require(!providerRetryBaseDelay.isNegative)
        require(!providerRetryMaxDelay.isNegative)
        require(providerRetryMaxDelay >= providerRetryBaseDelay)
        require(providerRetryJitterRatio in 0.0..1.0)
    }
}

class AgentCostBudget(
    val maxUsd: BigDecimal? = null,
    warningRatio: Double = 0.8,
    private val estimator: (TokenUsage) -> BigDecimal? = { null },
) {
    val warningThresholdUsd: BigDecimal? = maxUsd?.multiply(BigDecimal.valueOf(warningRatio))

    init {
        require(maxUsd == null || maxUsd.signum() > 0)
        require(warningRatio in 0.0..1.0)
    }

    fun estimate(usage: TokenUsage): BigDecimal? = estimator(usage)?.takeIf { it.signum() >= 0 }
}

enum class AgentMode {
    AGENT,
    PLAN,
    CLAUDE_PLAN,
    RESEARCH,
}

enum class AgentExecutionStrategy {
    AUTO,
    SINGLE,
    TEAM,
}

enum class AgentRole {
    EXPLORER,
    PLANNER,
    REVIEWER,
    CUSTOM,
    LEAD,
}

data class AgentIdentity(
    val agentId: String = "lead",
    val parentAgentId: String? = null,
    val role: AgentRole = AgentRole.LEAD,
    val displayName: String = "Lead",
) {
    init {
        requireBoundedAgentId("agentId", agentId)
        parentAgentId?.let { requireBoundedAgentId("parentAgentId", it) }
        requireBoundedAgentText("displayName", displayName, MAX_AGENT_DISPLAY_NAME_CHARS)
    }
}

enum class AgentRunStatus {
    COMPLETED,
    CANCELLED,
    FAILED,
    BUDGET_EXHAUSTED,
}

/** Stable marker used by the UI to distinguish a provider's per-response cap from task guards. */
class ProviderOutputLimitReachedException : IllegalStateException("The provider response reached its output limit.")

/** Stable marker used to route an oversized user submission back to the composer. */
class UserMessageTooLargeException(maxChars: Int) : IllegalArgumentException(
    "The user message exceeds the maximum of $maxChars characters.",
)

enum class ToolApprovalOutcome {
    NOT_REQUIRED,
    NOT_REQUESTED,
    APPROVED,
    REJECTED,
}

data class DelegatedAgentSummary(
    val workflowId: String,
    val delegationId: String,
    val agentId: String,
    val parentAgentId: String?,
    val role: AgentRole,
    val displayName: String,
    val status: AgentRunStatus,
    val summary: String,
    val usage: TokenUsage = TokenUsage(),
) {
    init {
        requireBoundedAgentId("workflowId", workflowId)
        requireBoundedAgentId("delegationId", delegationId)
        requireBoundedAgentId("agentId", agentId)
        parentAgentId?.let { requireBoundedAgentId("parentAgentId", it) }
        requireBoundedAgentText("displayName", displayName, MAX_AGENT_DISPLAY_NAME_CHARS)
        requireBoundedAgentText("summary", summary, MAX_DELEGATED_AGENT_SUMMARY_CHARS, allowBlank = true)
    }
}

data class AgentRunResult(
    val status: AgentRunStatus,
    val finalText: String,
    val messages: List<ConversationMessage>,
    val usage: TokenUsage,
    val error: Throwable? = null,
    val mode: AgentMode,
    val strategy: AgentExecutionStrategy = AgentExecutionStrategy.SINGLE,
    val workflowId: String = "",
    val delegates: List<DelegatedAgentSummary> = emptyList(),
)

/** Provider-neutral durable execution state emitted at safe AgentEngine boundaries. */
data class AgentExecutionCheckpoint(
    /** Zero is the initialized run; provider turns are numbered from one. */
    val iteration: Int,
    val messages: List<ConversationMessage>,
    val usage: TokenUsage,
    val toolCalls: Int,
    val pendingTool: AgentPendingTool? = null,
    /** A provider request that may already have consumed quota but has no recorded response yet. */
    val pendingProviderAttempt: AgentPendingProviderAttempt? = null,
    val sharedBudget: SharedAgentBudgetSnapshot? = null,
) {
    init {
        require(iteration >= 0) { "iteration must not be negative" }
        require(toolCalls >= 0) { "toolCalls must not be negative" }
        require(usage.inputTokens >= 0 && usage.outputTokens >= 0) { "usage must not be negative" }
    }
}

data class AgentPendingProviderAttempt(
    val idempotencyKey: String,
    /** One-based transport attempt number for the current logical request. */
    val attempt: Int,
    val projectedUsage: TokenUsage,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        require(idempotencyKey.length <= MAX_PROVIDER_IDEMPOTENCY_KEY_CHARS) {
            "idempotencyKey exceeds $MAX_PROVIDER_IDEMPOTENCY_KEY_CHARS characters"
        }
        require(attempt > 0) { "attempt must be positive" }
        require(projectedUsage.inputTokens >= 0 && projectedUsage.outputTokens >= 0) {
            "projectedUsage must not be negative"
        }
    }
}

/**
 * A requested tool action that has not reached a known, recorded result. Once
 * [executionStarted] is true, cancellation must treat the side effect as unknown.
 */
data class AgentPendingTool(
    val callId: String,
    val name: String,
    val argumentsJson: String,
    val dangerous: Boolean,
    val executionStarted: Boolean,
) {
    init {
        require(callId.isNotBlank()) { "callId must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
    }
}

fun interface AgentCheckpointSink {
    suspend fun save(checkpoint: AgentExecutionCheckpoint)
}

private const val MAX_PROVIDER_IDEMPOTENCY_KEY_CHARS = 256

sealed interface AgentEvent {
    val at: Instant

    data class ModeSelected(val mode: AgentMode, override val at: Instant = Instant.now()) : AgentEvent
    data class ExecutionStrategySelected(
        val strategy: AgentExecutionStrategy,
        val workflowId: String,
        override val at: Instant = Instant.now(),
    ) : AgentEvent {
        init {
            requireBoundedAgentId("workflowId", workflowId)
        }
    }
    data class DelegatedAgentStarted(
        val workflowId: String,
        val delegationId: String,
        val agentId: String,
        val parentAgentId: String?,
        val role: AgentRole,
        val displayName: String,
        val objective: String,
        override val at: Instant = Instant.now(),
    ) : AgentEvent {
        init {
            requireBoundedAgentId("workflowId", workflowId)
            requireBoundedAgentId("delegationId", delegationId)
            requireBoundedAgentId("agentId", agentId)
            parentAgentId?.let { requireBoundedAgentId("parentAgentId", it) }
            requireBoundedAgentText("displayName", displayName, MAX_AGENT_DISPLAY_NAME_CHARS)
            requireBoundedAgentText("objective", objective, MAX_AGENT_OBJECTIVE_CHARS)
        }
    }
    data class DelegatedAgentCompleted(
        val workflowId: String,
        val delegationId: String,
        val agentId: String,
        val parentAgentId: String?,
        val role: AgentRole,
        val displayName: String,
        val status: AgentRunStatus,
        val usable: Boolean,
        val summary: String,
        val usage: TokenUsage,
        override val at: Instant = Instant.now(),
    ) : AgentEvent {
        init {
            requireBoundedAgentId("workflowId", workflowId)
            requireBoundedAgentId("delegationId", delegationId)
            requireBoundedAgentId("agentId", agentId)
            parentAgentId?.let { requireBoundedAgentId("parentAgentId", it) }
            requireBoundedAgentText("displayName", displayName, MAX_AGENT_DISPLAY_NAME_CHARS)
            requireBoundedAgentText("summary", summary, MAX_DELEGATED_AGENT_SUMMARY_CHARS, allowBlank = true)
        }
    }
    data class Status(val message: String, override val at: Instant = Instant.now()) : AgentEvent
    data class TextDelta(val text: String, override val at: Instant = Instant.now()) : AgentEvent
    data class ToolRequested(
        val name: String,
        val summary: String,
        override val at: Instant = Instant.now(),
        val callId: String = "",
    ) : AgentEvent
    data class ToolApprovalResolved(
        val name: String,
        val callId: String,
        val outcome: ToolApprovalOutcome,
        val requestTitle: String,
        override val at: Instant = Instant.now(),
    ) : AgentEvent
    data class ToolCompleted(
        val name: String,
        val result: String,
        val isError: Boolean,
        val approvalOutcome: ToolApprovalOutcome = ToolApprovalOutcome.NOT_REQUIRED,
        override val at: Instant = Instant.now(),
        val callId: String = "",
        val cancelled: Boolean = false,
        val durationMillis: Long? = null,
    ) : AgentEvent
    data class StageStarted(
        val stage: String,
        val iteration: Int = 0,
        override val at: Instant = Instant.now(),
    ) : AgentEvent
    data class StageCompleted(
        val stage: String,
        val success: Boolean,
        val durationMillis: Long,
        val detail: String = "",
        val iteration: Int = 0,
        override val at: Instant = Instant.now(),
    ) : AgentEvent {
        init {
            require(stage.isNotBlank() && stage.length <= 96)
            require(durationMillis >= 0)
        }
    }
    data class ProviderRequestStarted(
        val iteration: Int,
        val attempt: Int,
        val idempotencyKey: String,
        val projectedInputTokens: Long,
        val projectedOutputTokens: Long,
        override val at: Instant = Instant.now(),
    ) : AgentEvent
    data class ProviderRetryScheduled(
        val iteration: Int,
        val failedAttempt: Int,
        val nextAttempt: Int,
        val delayMillis: Long,
        val reason: String,
        override val at: Instant = Instant.now(),
    ) : AgentEvent {
        init {
            require(iteration >= 0)
            require(failedAttempt > 0 && nextAttempt > failedAttempt)
            require(delayMillis >= 0)
            require(reason.length <= 2_000)
        }
    }
    data class UsageUpdated(val usage: TokenUsage, override val at: Instant = Instant.now()) : AgentEvent
    data class ProjectContextPrepared(
        val rulePaths: List<String>,
        val pinnedPaths: List<String>,
        val excludedPathCount: Int,
        val includedCharacters: Int,
        val estimatedContextTokens: Long,
        val maxContextTokens: Long,
        val truncated: Boolean,
        override val at: Instant = Instant.now(),
    ) : AgentEvent {
        init {
            require(rulePaths.size <= 64)
            require(pinnedPaths.size <= 64)
            require(excludedPathCount >= 0)
            require(includedCharacters >= 0)
            require(estimatedContextTokens >= 0)
            require(maxContextTokens > 0)
        }
    }
    data class BudgetWarning(
        val estimatedCostUsd: BigDecimal,
        val maxCostUsd: BigDecimal,
        val projected: Boolean,
        override val at: Instant = Instant.now(),
    ) : AgentEvent
}

fun interface AgentEventSink {
    suspend fun emit(event: AgentEvent)
}

const val MAX_AGENT_ID_CHARS: Int = 128
const val MAX_AGENT_DISPLAY_NAME_CHARS: Int = 96
const val MAX_AGENT_OBJECTIVE_CHARS: Int = 8_000
const val MAX_DELEGATED_AGENT_SUMMARY_CHARS: Int = 16_000

private val SAFE_AGENT_ID = Regex("[A-Za-z0-9._:-]+")

private fun requireBoundedAgentId(field: String, value: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= MAX_AGENT_ID_CHARS) { "$field exceeds $MAX_AGENT_ID_CHARS characters" }
    require(SAFE_AGENT_ID.matches(value)) { "$field contains unsupported characters" }
}

private fun requireBoundedAgentText(field: String, value: String, limit: Int, allowBlank: Boolean = false) {
    require(allowBlank || value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= limit) { "$field exceeds $limit characters" }
}
