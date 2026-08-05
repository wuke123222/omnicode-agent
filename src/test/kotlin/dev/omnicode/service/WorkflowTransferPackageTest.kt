package dev.omnicode.service

import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.persistence.DelegateCheckpointSnapshot
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.WorkflowBudgetSnapshot
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import dev.omnicode.persistence.WorkflowObservationSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkflowTransferPackageTest {
    private val checkpoint = WorkflowCheckpoint(
        workflowId = "workflow-source",
        runId = "run-source",
        projectId = "source-project",
        agentId = "lead",
        iteration = 3,
        messages = listOf(MessageSnapshot(SnapshotRole.USER, "分析这个项目")),
        observations = listOf(WorkflowObservationSnapshot("tool-1", "read_file", "README.md")),
        budget = WorkflowBudgetSnapshot(inputTokens = 10, outputTokens = 20),
        state = WorkflowCheckpointState.PAUSED,
        mode = AgentMode.AGENT,
        strategy = AgentExecutionStrategy.SINGLE,
        delegates = listOf(
            DelegateCheckpointSnapshot(
                delegationId = "delegate-1",
                agentId = "explorer",
                role = "explorer",
                objective = "只读检查",
                state = WorkflowCheckpointState.COMPLETED,
                summary = "完成",
            ),
        ),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:01:00Z"),
    )

    @Test
    fun `round trip is encrypted and receives fresh identity`() {
        val transfer = WorkflowTransferPackage()
        val bytes = transfer.export(checkpoint, "a sufficiently long passphrase".toCharArray(), "source-fp")

        assertTrue(bytes.isNotEmpty())
        assertTrue(bytes.toString(Charsets.UTF_8).contains("ciphertext"))
        assertTrue(!bytes.toString(Charsets.UTF_8).contains("分析这个项目"))

        val imported = transfer.import(bytes, "a sufficiently long passphrase".toCharArray(), "target-fp")
        assertNotEquals(checkpoint.workflowId, imported.workflowId)
        assertNotEquals(checkpoint.runId, imported.runId)
        assertEquals("target-fp", imported.projectId)
        assertEquals(WorkflowCheckpointState.INTERRUPTED, imported.state)
        assertEquals(checkpoint.messages, imported.messages)
        assertEquals(checkpoint.delegates, imported.delegates)
    }

    @Test
    fun `wrong passphrase and tampering fail closed`() {
        val transfer = WorkflowTransferPackage()
        val bytes = transfer.export(checkpoint, "a sufficiently long passphrase".toCharArray(), "source-fp")

        assertFailsWith<SecurityException> {
            transfer.import(bytes, "another sufficiently long passphrase".toCharArray(), "target-fp")
        }
        val tampered = bytes.copyOf().also { it[it.lastIndex] = if (it[it.lastIndex].toInt() == 'A'.code) 'B'.code.toByte() else 'A'.code.toByte() }
        assertFailsWith<IllegalArgumentException> {
            transfer.import(tampered, "a sufficiently long passphrase".toCharArray(), "target-fp")
        }
    }

    @Test
    fun `source fingerprint can be pinned`() {
        val transfer = WorkflowTransferPackage()
        val bytes = transfer.export(checkpoint, "a sufficiently long passphrase".toCharArray(), "source-fp")

        assertFailsWith<IllegalArgumentException> {
            transfer.import(
                bytes,
                "a sufficiently long passphrase".toCharArray(),
                "target-fp",
                expectedSourceProjectFingerprint = "other-fp",
            )
        }
    }

    @Test
    fun `image checkpoints cannot be exported`() {
        val transfer = WorkflowTransferPackage()
        assertFailsWith<IllegalArgumentException> {
            transfer.export(
                checkpoint.copy(requiredImageAttachments = 1),
                "a sufficiently long passphrase".toCharArray(),
                "source-fp",
            )
        }
    }
}
