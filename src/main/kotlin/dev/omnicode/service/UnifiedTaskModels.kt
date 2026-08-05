package dev.omnicode.service

import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import dev.omnicode.persistence.WorkflowEventRecord
import dev.omnicode.persistence.WorkflowEventType
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
    /** Latest reliability stage, if the workflow has emitted stage events. */
    val currentStage: String? = null,
    /** Bounded duration of the latest open stage at the last durable checkpoint. */
    val currentStageDurationMillis: Long? = null,
    /** Number of provider requests recorded for this workflow. */
    val modelRequestCount: Int = 0,
    /** Number of tool failures recorded for this workflow. */
    val toolFailureCount: Int = 0,
    /** Number of provider retries recorded for this workflow. */
    val retryCount: Int = 0,
    /** Last bounded reliability message, useful when a task failed outside the chat view. */
    val lastEventMessage: String? = null,
) {
    val canContinue: Boolean get() = status in setOf(
        UnifiedTaskStatus.PAUSED,
        UnifiedTaskStatus.RECOVERABLE,
        UnifiedTaskStatus.WAITING_FOR_APPROVAL,
        UnifiedTaskStatus.BUDGET_EXHAUSTED,
        UnifiedTaskStatus.FAILED,
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
    eventsByWorkflow: Map<String, List<WorkflowEventRecord>> = emptyMap(),
): List<UnifiedTaskEntry> {
    val conversationsByWorkflow = conversations
        .filter { !it.workflowId.isNullOrBlank() }
        .associateBy { requireNotNull(it.workflowId) }
    val checkpointEntries = checkpoints.map { checkpoint ->
        val conversation = conversationsByWorkflow[checkpoint.workflowId]
            ?: checkpoint.conversationId?.let { id -> conversations.firstOrNull { it.id == id } }
        checkpoint.toUnifiedTask(
            conversation = conversation,
            active = activeWorkflowId == checkpoint.workflowId,
            events = eventsByWorkflow[checkpoint.workflowId].orEmpty(),
        )
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
    events: List<WorkflowEventRecord>,
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
    currentStage = events.latestOpenStage(),
    currentStageDurationMillis = events.latestOpenStageDurationMillis(updatedAt),
    modelRequestCount = events.count { it.type == WorkflowEventType.MODEL_REQUEST },
    toolFailureCount = events.count { it.type == WorkflowEventType.TOOL_FAILURE },
    retryCount = events.count { it.type == WorkflowEventType.MODEL_RETRY },
    lastEventMessage = events.asSequence()
        .sortedBy(WorkflowEventRecord::recordedAt)
        .map { it.message.trim() }
        .filter(String::isNotBlank)
        .lastOrNull(),
)

/**
 * A stage is open when its latest start has not been followed by a completion. This is derived
 * from the durable event stream rather than the in-memory run, so the task center remains useful
 * after an IDE restart or when a task failed before the final checkpoint was written.
 */
private fun List<WorkflowEventRecord>.latestOpenStage(): String? {
    if (isEmpty()) return null
    val ordered = sortedBy(WorkflowEventRecord::recordedAt)
    val latestByStage = linkedMapOf<String, WorkflowEventRecord>()
    ordered.forEach { event ->
        val stage = event.stage?.trim().orEmpty()
        if (stage.isBlank()) return@forEach
        when (event.type) {
            WorkflowEventType.STAGE_STARTED,
            WorkflowEventType.STAGE_COMPLETED,
            -> latestByStage[stage] = event
            else -> Unit
        }
    }
    return latestByStage.entries
        .filter { (_, event) -> event.type == WorkflowEventType.STAGE_STARTED }
        .maxByOrNull { (_, event) -> event.recordedAt }
        ?.key
}

private fun List<WorkflowEventRecord>.latestOpenStageDurationMillis(reference: Instant): Long? {
    if (isEmpty()) return null
    val ordered = sortedBy(WorkflowEventRecord::recordedAt)
    val latestByStage = linkedMapOf<String, WorkflowEventRecord>()
    ordered.forEach { event ->
        val stage = event.stage?.trim().orEmpty()
        if (stage.isBlank()) return@forEach
        if (event.type == WorkflowEventType.STAGE_STARTED || event.type == WorkflowEventType.STAGE_COMPLETED) {
            latestByStage[stage] = event
        }
    }
    val open = latestByStage.entries
        .filter { (_, event) -> event.type == WorkflowEventType.STAGE_STARTED }
        .maxByOrNull { (_, event) -> event.recordedAt }
        ?.value
        ?: return null
    // The asynchronous ledger writer may publish an event a little after the checkpoint that
    // caused it. Never show a negative/zero duration solely because those two durable streams
    // crossed; use the latest observed event as the reference when it is newer.
    val effectiveReference = maxOf(reference, ordered.last().recordedAt)
    return java.time.Duration.between(open.recordedAt, effectiveReference).toMillis().coerceAtLeast(0L)
}

data class WorkflowStageSummary(
    val stage: String,
    val durationMillis: Long,
    val success: Boolean?,
    val detail: String = "",
)

data class WorkflowReliabilitySnapshot(
    val workflowId: String,
    val totalDurationMillis: Long,
    val modelRequestCount: Int,
    val toolFailureCount: Int,
    val retryCount: Int = 0,
    val retryReasons: List<String>,
    val recoveryPointCount: Int,
    val stages: List<WorkflowStageSummary>,
    val events: List<WorkflowEventRecord>,
)

internal fun summarizeWorkflowReliability(
    workflowId: String,
    events: List<WorkflowEventRecord>,
): WorkflowReliabilitySnapshot {
    val ordered = events.sortedBy(WorkflowEventRecord::recordedAt)
    val starts = ordered.filter { it.type == WorkflowEventType.STAGE_STARTED }
        .associateBy { it.stage.orEmpty() }
    val stages = ordered.filter { it.type == WorkflowEventType.STAGE_COMPLETED }
        .map { event ->
            WorkflowStageSummary(
                stage = event.stage.orEmpty().ifBlank { "未命名阶段" },
                durationMillis = event.durationMillis ?: 0,
                success = event.success,
                detail = event.message,
            )
        }
        .ifEmpty {
            starts.values.map { WorkflowStageSummary(it.stage.orEmpty(), 0, null, "阶段尚未完成") }
        }
    val total = if (ordered.size < 2) 0L else java.time.Duration.between(
        ordered.first().recordedAt,
        ordered.last().recordedAt,
    ).toMillis().coerceAtLeast(0L)
    return WorkflowReliabilitySnapshot(
        workflowId = workflowId,
        totalDurationMillis = total,
        modelRequestCount = ordered.count { it.type == WorkflowEventType.MODEL_REQUEST },
        toolFailureCount = ordered.count { it.type == WorkflowEventType.TOOL_FAILURE },
        retryCount = ordered.count { it.type == WorkflowEventType.MODEL_RETRY },
        retryReasons = ordered.filter { it.type == WorkflowEventType.MODEL_RETRY }
            .map { it.message }
            .filter(String::isNotBlank)
            .distinct()
            .take(16),
        recoveryPointCount = ordered.count { it.type == WorkflowEventType.RECOVERY_POINT },
        stages = stages.take(32),
        events = ordered.takeLast(256),
    )
}

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
