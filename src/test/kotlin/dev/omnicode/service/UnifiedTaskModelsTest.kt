package dev.omnicode.service

import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.WorkflowBudgetSnapshot
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import dev.omnicode.persistence.WorkflowEventRecord
import dev.omnicode.persistence.WorkflowEventType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedTaskModelsTest {
    @Test
    fun `checkpoint and conversation merge into one live task`() {
        val conversation = conversation("conversation", "workflow", AgentRunStatus.FAILED)
        val checkpoint = checkpoint("workflow", WorkflowCheckpointState.INTERRUPTED)

        val tasks = mergeUnifiedTasks(listOf(conversation), listOf(checkpoint), activeWorkflowId = "workflow")

        assertEquals(1, tasks.size)
        assertEquals(UnifiedTaskStatus.RUNNING, tasks.single().status)
        assertEquals("Saved task", tasks.single().title)
        assertEquals(30, tasks.single().inputTokens + tasks.single().outputTokens)
    }

    @Test
    fun `multiple active conversations remain independently running`() {
        val conversations = listOf(
            conversation("conversation-a", "workflow-a", AgentRunStatus.FAILED),
            conversation("conversation-b", "workflow-b", AgentRunStatus.FAILED),
        )
        val checkpoints = listOf(
            checkpoint("workflow-a", WorkflowCheckpointState.INTERRUPTED),
            checkpoint("workflow-b", WorkflowCheckpointState.INTERRUPTED),
        )

        val tasks = mergeUnifiedTasks(
            conversations = conversations,
            checkpoints = checkpoints,
            activeWorkflowId = null,
            activeWorkflowIds = setOf("workflow-a", "workflow-b"),
        )

        assertEquals(2, tasks.size)
        assertTrue(tasks.all { it.status == UnifiedTaskStatus.RUNNING })
    }

    @Test
    fun `failed conversation without checkpoint remains retryable`() {
        val task = mergeUnifiedTasks(
            listOf(conversation("conversation", null, AgentRunStatus.FAILED)),
            emptyList(),
            activeWorkflowId = null,
        ).single()

        assertEquals(UnifiedTaskStatus.FAILED, task.status)
        assertTrue(task.canRetry)
    }

    @Test
    fun `task entry exposes the latest open stage and bounded reliability counters`() {
        val checkpoint = checkpoint("workflow", WorkflowCheckpointState.FAILED)
        val start = WorkflowEventRecord(
            id = "stage-start",
            workflowId = "workflow",
            runId = "workflow",
            projectId = "project",
            type = WorkflowEventType.STAGE_STARTED,
            stage = "execution",
            message = "执行阶段开始",
            recordedAt = Instant.EPOCH.plusSeconds(3),
        )
        val request = start.copy(
            id = "request",
            type = WorkflowEventType.MODEL_REQUEST,
            stage = "execution",
            message = "模型请求 #1",
            recordedAt = Instant.EPOCH.plusSeconds(4),
        )
        val retry = start.copy(
            id = "retry",
            type = WorkflowEventType.MODEL_RETRY,
            stage = "execution",
            message = "网络错误后重试",
            recordedAt = Instant.EPOCH.plusSeconds(5),
        )
        val failure = start.copy(
            id = "failure",
            type = WorkflowEventType.TOOL_FAILURE,
            stage = "execution",
            message = "工具失败",
            recordedAt = Instant.EPOCH.plusSeconds(6),
        )

        val task = mergeUnifiedTasks(
            conversations = emptyList(),
            checkpoints = listOf(checkpoint),
            activeWorkflowId = null,
            eventsByWorkflow = mapOf("workflow" to listOf(start, request, retry, failure)),
        ).single()

        assertEquals("execution", task.currentStage)
        assertEquals(3_000, task.currentStageDurationMillis)
        assertEquals(1, task.modelRequestCount)
        assertEquals(1, task.toolFailureCount)
        assertEquals(1, task.retryCount)
        assertEquals("工具失败", task.lastEventMessage)
    }

    private fun conversation(id: String, workflowId: String?, status: AgentRunStatus) = ConversationRecord(
        id = id,
        projectId = "project",
        title = "Saved task",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH.plusSeconds(2),
        messages = listOf(MessageSnapshot(SnapshotRole.USER, "Goal")),
        mode = AgentMode.AGENT,
        lastRunStatus = status,
        workflowId = workflowId,
        strategy = AgentExecutionStrategy.SINGLE,
    )

    private fun checkpoint(id: String, state: WorkflowCheckpointState) = WorkflowCheckpoint(
        workflowId = id,
        runId = id,
        projectId = "project",
        agentId = "lead",
        iteration = 2,
        messages = listOf(MessageSnapshot(SnapshotRole.USER, "Goal")),
        observations = emptyList(),
        budget = WorkflowBudgetSnapshot(inputTokens = 10, outputTokens = 20),
        state = state,
        mode = AgentMode.AGENT,
        strategy = AgentExecutionStrategy.SINGLE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH.plusSeconds(1),
    )
}
