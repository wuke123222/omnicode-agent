package dev.omnicode.service

import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.ToolApprovalOutcome
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.ToolExecutionStatus
import dev.omnicode.persistence.PendingToolSnapshot
import dev.omnicode.persistence.PendingProviderAttemptSnapshot
import dev.omnicode.persistence.WorkflowBudgetSnapshot
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationReplayTest {
    @Test
    fun `only versioned projected workflow cost is trusted on recovery`() {
        val trusted = WorkflowBudgetSnapshot(
            inputTokens = 100,
            reservedInputTokens = 25,
            estimatedCostUsd = BigDecimal("0.40"),
            projectedCostUsd = BigDecimal("0.55"),
            costBasisVersion = 1,
        )
        val legacy = trusted.copy(costBasisVersion = 0)
        val missingProjected = trusted.copy(projectedCostUsd = null)
        val undercountedProjected = trusted.copy(projectedCostUsd = BigDecimal("0.30"))

        assertEquals(BigDecimal("0.55"), conservativeResumedCost(trusted))
        assertEquals(null, conservativeResumedCost(legacy))
        assertEquals(null, conservativeResumedCost(missingProjected))
        assertEquals(null, conservativeResumedCost(undercountedProjected))
    }

    @Test
    fun `ephemeral project context is removed before conversation persistence`() {
        val messages = listOf(
            ConversationMessage(
                MessageRole.USER,
                listOf(
                    ContentBlock.Text("Fix the regression"),
                    ContentBlock.Text("[OMNICODE_PROJECT_CONTEXT_V1] is ordinary user text"),
                    ContentBlock.TransientProjectContext("untrusted project rules"),
                ),
            ),
            ConversationMessage(MessageRole.ASSISTANT, "Done"),
        )

        val sanitized = stripEphemeralProjectContext(messages)

        assertEquals(2, sanitized.size)
        assertEquals(
            listOf(
                ContentBlock.Text("Fix the regression"),
                ContentBlock.Text("[OMNICODE_PROJECT_CONTEXT_V1] is ordinary user text"),
            ),
            sanitized.first().blocks,
        )
        assertEquals("Done", assertIs<ContentBlock.Text>(sanitized.last().blocks.single()).text)
    }

    @Test
    fun `terminal checkpoints retain failed and cancelled work with user or tool observations`() {
        val userOnly = listOf(ConversationMessage(MessageRole.USER, "Retry this task"))
        val toolObservation = listOf(
            ConversationMessage(
                MessageRole.ASSISTANT,
                listOf(ContentBlock.ToolResult("call-1", "file changed")),
            ),
        )
        val assistantOnly = listOf(ConversationMessage(MessageRole.ASSISTANT, "orphan response"))

        assertTrue(hasConversationCheckpoint(userOnly))
        assertTrue(hasConversationCheckpoint(toolObservation))
        assertTrue(!hasConversationCheckpoint(assistantOnly))
    }

    @Test
    fun `restore preserves paired tool call and observation blocks`() {
        val record = conversation(
            MessageSnapshot(SnapshotRole.USER, "Inspect the project"),
            MessageSnapshot(SnapshotRole.ASSISTANT, "I will inspect it."),
            MessageSnapshot(
                role = SnapshotRole.TOOL,
                text = "{\"path\":\"src/Main.kt\"}",
                toolName = "read_file",
                toolCallId = "call-1",
            ),
            MessageSnapshot(
                role = SnapshotRole.TOOL,
                text = "1\\tfun main() = Unit",
                toolCallId = "call-1",
            ),
            MessageSnapshot(SnapshotRole.ASSISTANT, "The file is valid."),
        )

        val messages = messagesFromConversationRecord(record)

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals(2, messages[1].blocks.size)
        val call = assertIs<ContentBlock.ToolCall>(messages[1].blocks[1])
        assertEquals("call-1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("src/Main.kt", call.arguments.get("path").asString)
        val result = assertIs<ContentBlock.ToolResult>(messages[2].blocks.single())
        assertEquals("call-1", result.toolCallId)
        assertTrue(!result.isError)
    }

    @Test
    fun `restore drops orphaned or malformed tool snapshots`() {
        val record = conversation(
            MessageSnapshot(SnapshotRole.USER, "Continue"),
            MessageSnapshot(SnapshotRole.TOOL, "orphan result", toolCallId = "missing-call"),
            MessageSnapshot(
                SnapshotRole.TOOL,
                "not-json",
                toolName = "read_file",
                toolCallId = "malformed-call",
            ),
            MessageSnapshot(SnapshotRole.TOOL, "result", toolCallId = "malformed-call"),
            MessageSnapshot(SnapshotRole.ASSISTANT, "Recovered"),
        )

        val messages = messagesFromConversationRecord(record)

        assertEquals(2, messages.size)
        assertTrue(messages.flatMap { it.blocks }.none { it is ContentBlock.ToolCall || it is ContentBlock.ToolResult })
    }

    @Test
    fun `cancelled completion maps to cancelled audit status before generic failure`() {
        val event = AgentEvent.ToolCompleted(
            name = "run_command",
            result = "cancelled",
            isError = true,
            approvalOutcome = ToolApprovalOutcome.APPROVED,
            callId = "call-2",
            cancelled = true,
        )

        assertEquals(ToolExecutionStatus.CANCELLED, toolExecutionStatus(event))
    }

    @Test
    fun `resume instruction never treats an interrupted side effect as completed`() {
        val checkpoint = workflowCheckpoint().copy(
            pendingTool = PendingToolSnapshot(
                executionId = "execution-1",
                toolCallId = "call-write",
                toolName = "apply_patch",
                argumentsJson = "{}",
                dangerous = true,
            ),
        )

        val instruction = resumeWorkflowInstruction(checkpoint)

        assertTrue(instruction.contains("不要自动重放"))
        assertTrue(instruction.contains("重新审批"))
        assertTrue(instruction.contains("apply_patch"))
    }

    @Test
    fun `workflow checkpoint replay drops an orphan pending call but keeps completed observations`() {
        val checkpoint = workflowCheckpoint()

        val replayed = messagesFromWorkflowCheckpoint(checkpoint)
        val blocks = replayed.flatMap(ConversationMessage::blocks)

        assertTrue(blocks.filterIsInstance<ContentBlock.ToolCall>().any { it.id == "call-read" })
        assertTrue(blocks.filterIsInstance<ContentBlock.ToolResult>().any { it.toolCallId == "call-read" })
        assertTrue(blocks.none { it is ContentBlock.ToolCall && it.id == "call-pending" })
    }

    @Test
    fun `agent terminal states map to durable checkpoint states`() {
        assertEquals(WorkflowCheckpointState.COMPLETED, workflowCheckpointState(dev.omnicode.agent.AgentRunStatus.COMPLETED))
        assertEquals(WorkflowCheckpointState.CANCELLED, workflowCheckpointState(dev.omnicode.agent.AgentRunStatus.CANCELLED))
        assertEquals(WorkflowCheckpointState.FAILED, workflowCheckpointState(dev.omnicode.agent.AgentRunStatus.FAILED))
        assertEquals(
            WorkflowCheckpointState.BUDGET_EXHAUSTED,
            workflowCheckpointState(dev.omnicode.agent.AgentRunStatus.BUDGET_EXHAUSTED),
        )
        assertEquals(
            WorkflowCheckpointState.INTERRUPTED,
            terminalWorkflowCheckpointState(dev.omnicode.agent.AgentRunStatus.CANCELLED, keepRecoverable = true),
        )
        assertEquals(
            WorkflowCheckpointState.INTERRUPTED,
            terminalWorkflowCheckpointState(dev.omnicode.agent.AgentRunStatus.FAILED, keepRecoverable = true),
        )
    }

    @Test
    fun `recovery folds an in flight provider reservation into usage exactly once`() {
        val budget = WorkflowBudgetSnapshot(
            inputTokens = 100,
            outputTokens = 20,
            reservedInputTokens = 11,
            reservedOutputTokens = 7,
        )

        assertEquals(dev.omnicode.model.TokenUsage(111, 27), conservativeResumedUsage(budget))
        assertEquals(dev.omnicode.model.TokenUsage(111, 27), conservativeResumedUsage(budget))
        assertEquals(
            Long.MAX_VALUE,
            conservativeResumedUsage(
                budget.copy(inputTokens = Long.MAX_VALUE - 2, reservedInputTokens = 10),
            ).inputTokens,
        )
    }

    @Test
    fun `resume instruction reports an uncertain provider charge`() {
        val checkpoint = workflowCheckpoint().copy(
            pendingProviderAttempt = PendingProviderAttemptSnapshot(
                idempotencyKey = "omnicode-attempt",
                attempt = 1,
                projectedInputTokens = 50,
                projectedOutputTokens = 25,
            ),
        )

        assertTrue(resumeWorkflowInstruction(checkpoint).contains("计费状态未知"))
    }

    private fun conversation(vararg messages: MessageSnapshot): ConversationRecord = ConversationRecord(
        id = "conversation-1",
        projectId = "project-1",
        title = "Replay",
        createdAt = Instant.parse("2026-07-17T00:00:00Z"),
        updatedAt = Instant.parse("2026-07-17T00:01:00Z"),
        messages = messages.toList(),
    )

    private fun workflowCheckpoint(): WorkflowCheckpoint {
        val timestamp = Instant.parse("2026-07-17T00:00:00Z")
        return WorkflowCheckpoint(
            workflowId = "workflow-resume",
            runId = "workflow-resume",
            projectId = "project-1",
            conversationId = "conversation-1",
            agentId = "lead",
            iteration = 3,
            messages = listOf(
                MessageSnapshot(SnapshotRole.USER, "Fix the regression", timestamp),
                MessageSnapshot(
                    SnapshotRole.TOOL,
                    "{\"path\":\"src/Main.kt\"}",
                    timestamp.plusSeconds(1),
                    toolName = "read_file",
                    toolCallId = "call-read",
                ),
                MessageSnapshot(
                    SnapshotRole.TOOL,
                    "file content",
                    timestamp.plusSeconds(2),
                    toolCallId = "call-read",
                ),
                MessageSnapshot(
                    SnapshotRole.TOOL,
                    "{}",
                    timestamp.plusSeconds(3),
                    toolName = "apply_patch",
                    toolCallId = "call-pending",
                ),
            ),
            observations = emptyList(),
            budget = WorkflowBudgetSnapshot(),
            state = WorkflowCheckpointState.INTERRUPTED,
            createdAt = timestamp,
            updatedAt = timestamp.plusSeconds(4),
        )
    }
}
