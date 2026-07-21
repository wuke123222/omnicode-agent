package dev.omnicode.persistence

import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PersistenceRetention(
    val maxUsageRecords: Int = 10_000,
    val maxConversations: Int = 200,
    val maxToolExecutionRecords: Int = 20_000,
    val maxMessagesPerConversation: Int = 400,
    val maxMessageChars: Int = 64_000,
    val maxToolSummaryChars: Int = 16_000,
    val usageRetentionDays: Int = Int.MAX_VALUE,
) {
    init {
        require(maxUsageRecords > 0)
        require(maxConversations > 0)
        require(maxToolExecutionRecords > 0)
        require(maxMessagesPerConversation > 0)
        require(maxMessageChars > 0)
        require(maxToolSummaryChars > 0)
        require(usageRetentionDays > 0)
    }
}

data class UsageRecord(
    val runId: String,
    val providerId: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: BigDecimal? = null,
    val projectId: String = "",
    val recordedAt: Instant = Instant.now(),
    val id: String = UUID.randomUUID().toString(),
    /** Null identifies legacy and non-agent records. New agent runs always set this. */
    val mode: AgentMode? = null,
    /** Null identifies usage written before workflow-level execution metadata existed. */
    val workflowId: String? = null,
    /** Null identifies usage that cannot be attributed to one agent. */
    val agentId: String? = null,
    /** Null identifies root, single-agent, and legacy usage. */
    val parentAgentId: String? = null,
    /** Null identifies usage written before execution strategies were persisted. */
    val strategy: AgentExecutionStrategy? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class UsageQuery(
    val projectId: String? = null,
    val providerId: String? = null,
    val model: String? = null,
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val limit: Int = 1_000,
    val mode: AgentMode? = null,
    val workflowId: String? = null,
    val agentId: String? = null,
    val strategy: AgentExecutionStrategy? = null,
)

data class DailyUsageSummary(
    val date: LocalDate,
    val runCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: BigDecimal?,
    val pricedRunCount: Int,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

data class UsageSummary(
    val runCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: BigDecimal?,
    val pricedRunCount: Int,
    val daily: List<DailyUsageSummary>,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

enum class SnapshotRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

data class MessageSnapshot(
    val role: SnapshotRole,
    val text: String,
    val recordedAt: Instant = Instant.now(),
    val toolName: String? = null,
    val toolCallId: String? = null,
    val isError: Boolean = false,
)

data class ConversationRecord(
    val id: String,
    val projectId: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messages: List<MessageSnapshot>,
    /** Last completed run mode. Null is accepted only when reading pre-mode records. */
    val mode: AgentMode? = AgentMode.AGENT,
    /** Terminal state of the latest checkpoint. Null identifies records written before checkpoint status existed. */
    val lastRunStatus: AgentRunStatus? = AgentRunStatus.COMPLETED,
    /** Null identifies conversations written before workflow-level execution metadata existed. */
    val workflowId: String? = null,
    /** Null identifies conversations without an attributable agent checkpoint. */
    val agentId: String? = null,
    /** Null identifies root, single-agent, and legacy conversation checkpoints. */
    val parentAgentId: String? = null,
    /** Null identifies conversations written before execution strategies were persisted. */
    val strategy: AgentExecutionStrategy? = null,
)

enum class ToolExecutionStatus {
    REQUESTED,
    APPROVED,
    RUNNING,
    COMPLETED,
    FAILED,
    REJECTED,
    CANCELLED,
}

enum class ToolApprovalDecision {
    NOT_REQUIRED,
    NOT_REQUESTED,
    APPROVED,
    REJECTED,
}

data class ToolExecutionRecord(
    val executionId: String,
    val runId: String,
    val toolName: String,
    val status: ToolExecutionStatus,
    val projectId: String = "",
    val conversationId: String? = null,
    val dangerous: Boolean = false,
    val approvalDecision: ToolApprovalDecision = ToolApprovalDecision.NOT_REQUIRED,
    val inputSummary: String? = null,
    val outputSummary: String? = null,
    val errorMessage: String? = null,
    val durationMillis: Long? = null,
    val recordedAt: Instant = Instant.now(),
    val id: String = UUID.randomUUID().toString(),
    val toolCallId: String? = null,
    /** Null is retained for audit records written before execution modes existed. */
    val mode: AgentMode? = null,
    /** Null identifies audit records written before workflow-level execution metadata existed. */
    val workflowId: String? = null,
    /** Null identifies audit records that cannot be attributed to one agent. */
    val agentId: String? = null,
    /** Null identifies root, single-agent, and legacy audit records. */
    val parentAgentId: String? = null,
    /** Null identifies audit records written before execution strategies were persisted. */
    val strategy: AgentExecutionStrategy? = null,
)

data class ToolExecutionQuery(
    val projectId: String? = null,
    val conversationId: String? = null,
    val runId: String? = null,
    val executionId: String? = null,
    val toolName: String? = null,
    val status: ToolExecutionStatus? = null,
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val limit: Int = 1_000,
    val mode: AgentMode? = null,
    val workflowId: String? = null,
    val agentId: String? = null,
)
