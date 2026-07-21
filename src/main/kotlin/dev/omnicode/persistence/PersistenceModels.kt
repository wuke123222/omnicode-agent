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
    val maxWorkflowCheckpoints: Int = 200,
    val maxMessagesPerConversation: Int = 400,
    val maxMessageChars: Int = 64_000,
    val maxToolSummaryChars: Int = 16_000,
    val usageRetentionDays: Int = Int.MAX_VALUE,
) {
    init {
        require(maxUsageRecords > 0)
        require(maxConversations > 0)
        require(maxToolExecutionRecords > 0)
        require(maxWorkflowCheckpoints > 0)
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

const val CURRENT_WORKFLOW_CHECKPOINT_VERSION: Int = 1

/**
 * Durable state of one workflow. A checkpoint is recoverable until it reaches a terminal state.
 * INTERRUPTED is deliberately recoverable: startup code may use it to require an explicit resume
 * instead of silently replaying a pending tool call.
 */
enum class WorkflowCheckpointState {
    RUNNING,
    WAITING_FOR_APPROVAL,
    PAUSED,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,
    ;

    val isTerminal: Boolean
        get() = when (this) {
            COMPLETED,
            FAILED,
            CANCELLED,
            BUDGET_EXHAUSTED,
            -> true

            RUNNING,
            WAITING_FOR_APPROVAL,
            PAUSED,
            INTERRUPTED,
            -> false
        }
}

data class WorkflowObservationSnapshot(
    val toolCallId: String,
    val toolName: String,
    val text: String,
    val isError: Boolean = false,
    val recordedAt: Instant = Instant.now(),
)

data class WorkflowBudgetSnapshot(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reservedInputTokens: Long = 0,
    val reservedOutputTokens: Long = 0,
    val maxInputTokens: Long = Long.MAX_VALUE,
    val maxOutputTokens: Long = Long.MAX_VALUE,
    val maxTotalTokens: Long = Long.MAX_VALUE,
    val toolCalls: Int = 0,
    val maxToolCalls: Int = Int.MAX_VALUE,
    val estimatedCostUsd: BigDecimal? = null,
    val maxCostUsd: BigDecimal? = null,
)

/** Raw arguments remain bounded and redacted by [OmniCodeLocalStore] before persistence. */
data class PendingToolSnapshot(
    val executionId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsJson: String,
    val dangerous: Boolean = false,
    /** True means cancellation cannot prove whether the side effect completed. */
    val executionStarted: Boolean = false,
    val requestedAt: Instant = Instant.now(),
)

data class PendingApprovalSnapshot(
    val approvalId: String,
    val toolCallId: String,
    val toolName: String,
    val title: String,
    val risk: String = "",
    val requestedAt: Instant = Instant.now(),
)

data class DelegateCheckpointSnapshot(
    val delegationId: String,
    val agentId: String,
    val parentAgentId: String? = null,
    val role: String,
    val objective: String,
    val state: WorkflowCheckpointState,
    val summary: String = "",
    val iteration: Int = 0,
)

/**
 * Latest replay-safe execution snapshot for one workflow. Images and other binary attachments are
 * intentionally excluded; [messages] and [observations] contain only bounded textual snapshots.
 * Nullable [state] is accepted solely for records written before checkpoint versioning existed.
 */
data class WorkflowCheckpoint(
    val workflowId: String,
    val runId: String,
    val projectId: String,
    val agentId: String,
    val iteration: Int,
    val messages: List<MessageSnapshot>,
    val observations: List<WorkflowObservationSnapshot>,
    val budget: WorkflowBudgetSnapshot,
    val state: WorkflowCheckpointState? = WorkflowCheckpointState.RUNNING,
    val version: Int = CURRENT_WORKFLOW_CHECKPOINT_VERSION,
    val conversationId: String? = null,
    val parentAgentId: String? = null,
    val mode: AgentMode? = null,
    val strategy: AgentExecutionStrategy? = null,
    val pendingTool: PendingToolSnapshot? = null,
    val pendingApproval: PendingApprovalSnapshot? = null,
    /** Binary images are not persisted; this count makes reattachment a fail-closed resume gate. */
    val requiredImageAttachments: Int = 0,
    val delegates: List<DelegateCheckpointSnapshot> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
) {
    val isTerminal: Boolean get() = state?.isTerminal == true
}

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
