package dev.omnicode.persistence

import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomaticRecoveryTest {
    private val root = Files.createTempDirectory("omnicode-auto-recovery")
    private val at = Instant.parse("2026-10-27T00:00:00Z")

    @AfterTest fun cleanup() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun checkpoint() = WorkflowCheckpoint(
        workflowId = "workflow", runId = "run", projectId = "project", agentId = "lead",
        conversationId = "conversation", iteration = 3,
        messages = listOf(MessageSnapshot(SnapshotRole.TOOL, "{}", at, toolName = "run_command", toolCallId = "call")),
        observations = emptyList(), budget = WorkflowBudgetSnapshot(inputTokens = 200, toolCalls = 4),
        pendingTool = PendingToolSnapshot("execution", "call", "run_command", "{}", true, true, at),
        state = WorkflowCheckpointState.INTERRUPTED, createdAt = at, updatedAt = at,
    )

    private fun receipt() = ToolExecutionRecord(
        executionId = "execution", runId = "run", toolName = "run_command",
        status = ToolExecutionStatus.COMPLETED, projectId = "project", workflowId = "workflow",
        agentId = "lead", conversationId = "conversation", toolCallId = "call",
        dangerous = true, approvalDecision = ToolApprovalDecision.APPROVED,
        outputSummary = "Exit code: 0", recordedAt = at.plusSeconds(1), outcomeKnown = true,
    )

    @Test fun `durable completion recovers without deleting history replaying or resetting budget`() {
        val store = OmniCodeLocalStore(root)
        val original = store.saveWorkflowCheckpoint(checkpoint())
        store.recordToolExecution(receipt())
        assertEquals(1, store.reconcileCompletedToolCheckpoints("project"))
        val recovered = assertNotNull(OmniCodeLocalStore(root).workflowCheckpoint("workflow"))
        assertNull(recovered.pendingTool)
        assertEquals(original.budget, recovered.budget)
        assertEquals(original.state, recovered.state)
        assertEquals(original.iteration, recovered.iteration)
        assertEquals("Exit code: 0", recovered.observations.single().text)
        assertEquals("call", recovered.messages.last().toolCallId)
        assertEquals(0, store.reconcileCompletedToolCheckpoints("project"))
    }

    @Test fun `definite failed result also releases stale pending state`() {
        val store = OmniCodeLocalStore(root)
        store.saveWorkflowCheckpoint(checkpoint())
        store.recordToolExecution(receipt().copy(status = ToolExecutionStatus.FAILED, outputSummary = "COMMAND_NOT_STARTED"))
        assertEquals(1, store.reconcileCompletedToolCheckpoints("project"))
        assertTrue(assertNotNull(store.workflowCheckpoint("workflow")).observations.single().isError)
    }

    @Test fun `missing uncertain mismatched or stale receipts never clear recovery`() {
        val valid = receipt()
        val variants = listOf(
            null,
            valid.copy(outcomeKnown = false),
            valid.copy(status = ToolExecutionStatus.FAILED, outcomeKnown = null),
            valid.copy(status = ToolExecutionStatus.CANCELLED),
            valid.copy(status = ToolExecutionStatus.APPROVED),
            valid.copy(approvalDecision = ToolApprovalDecision.NOT_REQUESTED),
            valid.copy(dangerous = false),
            valid.copy(projectId = "other"), valid.copy(workflowId = "other"),
            valid.copy(runId = "other"), valid.copy(agentId = "other"),
            valid.copy(conversationId = "other"), valid.copy(toolName = "other"),
            valid.copy(toolCallId = "other"), valid.copy(executionId = "reused-call-id"),
            valid.copy(recordedAt = at.minusSeconds(1)),
        )
        variants.forEachIndexed { index, evidence ->
            val store = OmniCodeLocalStore(root.resolve("case-$index"))
            val expected = store.saveWorkflowCheckpoint(checkpoint())
            evidence?.let(store::recordToolExecution)
            assertEquals(0, store.reconcileCompletedToolCheckpoints("project"), "case $index")
            assertEquals(expected, store.workflowCheckpoint("workflow"), "case $index")
        }
    }

    @Test fun `legacy success requires exact scope and cannot use legacy failure text as proof`() {
        val store = OmniCodeLocalStore(root)
        val legacy = checkpoint().copy(pendingTool = checkpoint().pendingTool!!.copy(executionId = "run:call"))
        store.saveWorkflowCheckpoint(legacy)
        store.recordToolExecution(receipt().copy(status = ToolExecutionStatus.FAILED, outcomeKnown = null, outputSummary = "success and no side effects"))
        assertEquals(0, store.reconcileCompletedToolCheckpoints("project"))
        store.recordToolExecution(receipt().copy(outcomeKnown = null, recordedAt = at.plusSeconds(2)))
        assertEquals(1, store.reconcileCompletedToolCheckpoints("project"))
    }

    @Test fun `later unknown and equal time contradictory records override completion`() {
        for (seconds in listOf(1L, 2L)) {
            val store = OmniCodeLocalStore(root.resolve("time-$seconds"))
            val original = store.saveWorkflowCheckpoint(checkpoint())
            store.recordToolExecution(receipt())
            store.recordToolExecution(receipt().copy(status = ToolExecutionStatus.CANCELLED,
                outcomeKnown = false, recordedAt = at.plusSeconds(seconds)))
            assertEquals(0, store.reconcileCompletedToolCheckpoints("project"))
            assertEquals(original, store.workflowCheckpoint("workflow"))
        }
    }

    @Test fun `concurrent recovery preserves other projects and applies receipt only once`() {
        val store = OmniCodeLocalStore(root)
        store.saveWorkflowCheckpoint(checkpoint())
        val other = store.saveWorkflowCheckpoint(checkpoint().copy(workflowId = "other", projectId = "other"))
        store.recordToolExecution(receipt())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val counts = executor.invokeAll(List(2) { Callable { OmniCodeLocalStore(root).reconcileCompletedToolCheckpoints("project") } })
            assertEquals(1, counts.sumOf { it.get() })
            assertNull(store.workflowCheckpoint("workflow")!!.pendingTool)
            assertEquals(other, store.workflowCheckpoint("other"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `contradictory definite receipts at the same time do not unlock`() {
        val store = OmniCodeLocalStore(root)
        val original = store.saveWorkflowCheckpoint(checkpoint())
        store.recordToolExecution(receipt())
        store.recordToolExecution(receipt().copy(status = ToolExecutionStatus.FAILED))
        assertEquals(0, store.reconcileCompletedToolCheckpoints("project"))
        assertEquals(original, store.workflowCheckpoint("workflow"))
    }

    @Test fun `reused provider call id preserves the earlier exchange`() {
        val store = OmniCodeLocalStore(root)
        val call = checkpoint().messages.single()
        val earlierResult = MessageSnapshot(SnapshotRole.TOOL, "Earlier result", at, toolCallId = "call")
        val pendingResult = MessageSnapshot(SnapshotRole.TOOL, "Result uncertain", at, toolCallId = "call", isError = true)
        store.saveWorkflowCheckpoint(checkpoint().copy(messages = listOf(call, earlierResult, call, pendingResult)))
        store.recordToolExecution(receipt())
        assertEquals(1, store.reconcileCompletedToolCheckpoints("project"))
        val messages = store.workflowCheckpoint("workflow")!!.messages
        assertEquals(listOf("{}", "Earlier result", "{}", "Exit code: 0"), messages.map { it.text })
    }

    @Test fun `trimmed tool call retains readable evidence without inventing a replayable call`() {
        val store = OmniCodeLocalStore(root)
        store.saveWorkflowCheckpoint(checkpoint().copy(messages = emptyList()))
        store.recordToolExecution(receipt())
        assertEquals(1, store.reconcileCompletedToolCheckpoints("project"))
        val message = store.workflowCheckpoint("workflow")!!.messages.single()
        assertEquals(SnapshotRole.ASSISTANT, message.role)
        assertTrue(message.text.contains("Exit code: 0"))
        assertNull(message.toolCallId)
    }
}
