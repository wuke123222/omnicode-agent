package dev.omnicode.service

import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import java.time.Instant

enum class UnifiedTaskStatus {
    RUNNING,
    WAITING_FOR_APPROVAL,
    PAUSED,
    RECOVERABLE,
    FAILED,
    COMPLETED,
    CANCELLED,
    BUDGET_EXHAUSTED,
}

data class UnifiedTaskEntry(
    val taskId: String,
    val workflowId: String?,
    val conversationId: String?,
    val title: String,
    val status: UnifiedTaskStatus,
    val mode: AgentMode,
    val strategy: AgentExecutionStrategy,
    val updatedAt: Instant,
    val iteration: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val requiredImageAttachments: Int = 0,
    val pendingToolName: String? = null,
) {
    val canContinue: Boolean get() = status in setOf(
        UnifiedTaskStatus.PAUSED,
        UnifiedTaskStatus.RECOVERABLE,
        UnifiedTaskStatus.WAITING_FOR_APPROVAL,
    )
    val canRetry: Boolean get() = status in setOf(
        UnifiedTaskStatus.FAILED,
        UnifiedTaskStatus.CANCELLED,
        UnifiedTaskStatus.BUDGET_EXHAUSTED,
    )
}

internal fun mergeUnifiedTasks(
    conversations: List<ConversationRecord>,
    checkpoints: List<WorkflowCheckpoint>,
    activeWorkflowId: String?,
): List<UnifiedTaskEntry> {
    val conversationsByWorkflow = conversations
        .filter { !it.workflowId.isNullOrBlank() }
        .associateBy { requireNotNull(it.workflowId) }
    val checkpointEntries = checkpoints.map { checkpoint ->
        val conversation = conversationsByWorkflow[checkpoint.workflowId]
            ?: checkpoint.conversationId?.let { id -> conversations.firstOrNull { it.id == id } }
        checkpoint.toUnifiedTask(conversation, activeWorkflowId == checkpoint.workflowId)
    }
    val checkpointWorkflows = checkpoints.mapTo(mutableSetOf(), WorkflowCheckpoint::workflowId)
    val conversationOnly = conversations
        .filter { it.workflowId.isNullOrBlank() || it.workflowId !in checkpointWorkflows }
        .map(ConversationRecord::toUnifiedTask)
    return (checkpointEntries + conversationOnly)
        .distinctBy(UnifiedTaskEntry::taskId)
        .sortedWith(compareByDescending<UnifiedTaskEntry> { it.status == UnifiedTaskStatus.RUNNING }
            .thenByDescending(UnifiedTaskEntry::updatedAt))
}

private fun WorkflowCheckpoint.toUnifiedTask(
    conversation: ConversationRecord?,
    active: Boolean,
): UnifiedTaskEntry = UnifiedTaskEntry(
    taskId = workflowId,
    workflowId = workflowId,
    conversationId = conversationId ?: conversation?.id,
    title = conversation?.title?.takeIf(String::isNotBlank) ?: checkpointTaskTitle(this),
    status = if (active) UnifiedTaskStatus.RUNNING else checkpointStatus(state),
    mode = mode ?: conversation?.mode ?: AgentMode.AGENT,
    strategy = strategy ?: conversation?.strategy ?: AgentExecutionStrategy.SINGLE,
    updatedAt = maxOf(updatedAt, conversation?.updatedAt ?: updatedAt),
    iteration = iteration,
    inputTokens = budget.inputTokens + budget.reservedInputTokens,
    outputTokens = budget.outputTokens + budget.reservedOutputTokens,
    requiredImageAttachments = requiredImageAttachments,
    pendingToolName = pendingTool?.toolName,
)

private fun ConversationRecord.toUnifiedTask(): UnifiedTaskEntry = UnifiedTaskEntry(
    taskId = workflowId ?: id,
    workflowId = workflowId,
    conversationId = id,
    title = title.ifBlank { "OmniCode 任务" },
    status = conversationStatus(lastRunStatus),
    mode = mode ?: AgentMode.AGENT,
    strategy = strategy ?: AgentExecutionStrategy.SINGLE,
    updatedAt = updatedAt,
)

private fun checkpointStatus(state: WorkflowCheckpointState?): UnifiedTaskStatus = when (state) {
    WorkflowCheckpointState.RUNNING -> UnifiedTaskStatus.RECOVERABLE
    WorkflowCheckpointState.WAITING_FOR_APPROVAL -> UnifiedTaskStatus.WAITING_FOR_APPROVAL
    WorkflowCheckpointState.PAUSED -> UnifiedTaskStatus.PAUSED
    WorkflowCheckpointState.INTERRUPTED,
    null,
    -> UnifiedTaskStatus.RECOVERABLE
    WorkflowCheckpointState.COMPLETED -> UnifiedTaskStatus.COMPLETED
    WorkflowCheckpointState.FAILED -> UnifiedTaskStatus.FAILED
    WorkflowCheckpointState.CANCELLED -> UnifiedTaskStatus.CANCELLED
    WorkflowCheckpointState.BUDGET_EXHAUSTED -> UnifiedTaskStatus.BUDGET_EXHAUSTED
}

private fun conversationStatus(status: AgentRunStatus?): UnifiedTaskStatus = when (status) {
    AgentRunStatus.COMPLETED,
    null,
    -> UnifiedTaskStatus.COMPLETED
    AgentRunStatus.CANCELLED -> UnifiedTaskStatus.CANCELLED
    AgentRunStatus.FAILED -> UnifiedTaskStatus.FAILED
    AgentRunStatus.BUDGET_EXHAUSTED -> UnifiedTaskStatus.BUDGET_EXHAUSTED
}

private fun checkpointTaskTitle(checkpoint: WorkflowCheckpoint): String = checkpoint.messages.asReversed()
    .firstOrNull { snapshot ->
        snapshot.role == SnapshotRole.USER &&
            snapshot.text.isNotBlank() &&
            !snapshot.text.startsWith("恢复被 IDE 中断的任务") &&
            !snapshot.text.startsWith("[Image attachment:")
    }
    ?.text
    ?.lineSequence()
    ?.firstOrNull()
    ?.trim()
    ?.take(120)
    ?.ifBlank { null }
    ?: "OmniCode 任务"
