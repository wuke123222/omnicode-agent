package dev.omnicode.agent

import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.TokenUsage
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class AgentLimits(
    val maxIterations: Int = 24,
    val maxToolCalls: Int = 32,
    val maxConsecutiveFailures: Int = 3,
    val maxRepeatedAction: Int = 2,
    val maxWallTime: Duration = Duration.ofMinutes(10),
    val maxToolTime: Duration = Duration.ofMinutes(5),
    val maxInputTokens: Long = 250_000,
    val maxOutputTokens: Long = 32_000,
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
    RESEARCH,
}

enum class AgentRunStatus {
    COMPLETED,
    CANCELLED,
    FAILED,
    BUDGET_EXHAUSTED,
}

enum class ToolApprovalOutcome {
    NOT_REQUIRED,
    NOT_REQUESTED,
    APPROVED,
    REJECTED,
}

data class AgentRunResult(
    val status: AgentRunStatus,
    val finalText: String,
    val messages: List<ConversationMessage>,
    val usage: TokenUsage,
    val error: Throwable? = null,
    val mode: AgentMode,
)

sealed interface AgentEvent {
    val at: Instant

    data class ModeSelected(val mode: AgentMode, override val at: Instant = Instant.now()) : AgentEvent
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
    ) : AgentEvent
    data class UsageUpdated(val usage: TokenUsage, override val at: Instant = Instant.now()) : AgentEvent
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
