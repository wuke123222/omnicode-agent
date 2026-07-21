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
    fun `failed conversation without checkpoint remains retryable`() {
        val task = mergeUnifiedTasks(
            listOf(conversation("conversation", null, AgentRunStatus.FAILED)),
            emptyList(),
            activeWorkflowId = null,
        ).single()

        assertEquals(UnifiedTaskStatus.FAILED, task.status)
        assertTrue(task.canRetry)
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
