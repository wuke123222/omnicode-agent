package dev.omnicode.service

import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.OmniCodeLocalStore
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.WorkflowBudgetSnapshot
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscardedRecoverableWorkflowTest {
    private lateinit var root: Path

    @BeforeTest
    fun createStoreDirectory() {
        root = Files.createTempDirectory("omnicode-discard-undo-test")
    }

    @AfterTest
    fun deleteStoreDirectory() {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `expired undo token is consumed without restoring data`() {
        val store = OmniCodeLocalStore(root)
        val token = DiscardedRecoverableWorkflow(
            checkpoint = checkpoint(),
            expiresAt = Instant.parse("2026-07-22T00:00:00Z"),
        )

        assertEquals(
            DiscardedWorkflowRestoreResult.EXPIRED,
            token.restoreTo(store, Instant.parse("2026-07-22T00:00:00Z")),
        )
        assertEquals(
            DiscardedWorkflowRestoreResult.ALREADY_CONSUMED,
            token.restoreTo(store, Instant.parse("2026-07-22T00:00:00Z")),
        )
        assertNull(store.workflowCheckpoint("workflow-undo"))
    }

    @Test
    fun `service undo token remains valid beyond the eight second UI window`() {
        val store = OmniCodeLocalStore(root)
        val createdAt = Instant.now()
        val token = DiscardedRecoverableWorkflow(checkpoint = checkpoint())

        assertEquals(
            DiscardedWorkflowRestoreResult.RESTORED,
            token.restoreTo(store, createdAt.plusMillis(8_500)),
        )
    }

    @Test
    fun `undo token restores at most once`() {
        val store = OmniCodeLocalStore(root)
        val token = DiscardedRecoverableWorkflow(
            checkpoint = checkpoint(),
            expiresAt = Instant.parse("2026-07-22T00:00:10Z"),
        )

        assertEquals(
            DiscardedWorkflowRestoreResult.RESTORED,
            token.restoreTo(store, Instant.parse("2026-07-22T00:00:01Z")),
        )
        store.deleteWorkflowCheckpoint("workflow-undo")
        assertEquals(
            DiscardedWorkflowRestoreResult.ALREADY_CONSUMED,
            token.restoreTo(store, Instant.parse("2026-07-22T00:00:02Z")),
        )
        assertNull(store.workflowCheckpoint("workflow-undo"))
    }

    @Test
    fun `concurrent undo requests consume the token exactly once`() {
        val store = OmniCodeLocalStore(root)
        val token = DiscardedRecoverableWorkflow(
            checkpoint = checkpoint(),
            expiresAt = Instant.parse("2026-07-22T00:00:10Z"),
        )
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val attempts = List(2) {
                executor.submit<DiscardedWorkflowRestoreResult> {
                    start.await()
                    token.restoreTo(store, Instant.parse("2026-07-22T00:00:01Z"))
                }
            }
            start.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it == DiscardedWorkflowRestoreResult.RESTORED })
            assertEquals(1, results.count { it == DiscardedWorkflowRestoreResult.ALREADY_CONSUMED })
            assertEquals("run-undo", store.workflowCheckpoint("workflow-undo")?.runId)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `undo token refuses to overwrite an equal timestamp same workflow checkpoint`() {
        val store = OmniCodeLocalStore(root)
        val original = checkpoint()
        val concurrent = original.copy(
            runId = "run-concurrent",
            iteration = original.iteration + 1,
        )
        store.saveWorkflowCheckpoint(concurrent)
        val token = DiscardedRecoverableWorkflow(
            checkpoint = original,
            expiresAt = Instant.parse("2026-07-22T00:00:10Z"),
        )

        assertEquals(
            DiscardedWorkflowRestoreResult.CONFLICT,
            token.restoreTo(store, Instant.parse("2026-07-22T00:00:01Z")),
        )
        assertEquals(concurrent, store.workflowCheckpoint(original.workflowId))
    }

    private fun checkpoint(): WorkflowCheckpoint {
        val timestamp = Instant.parse("2026-07-21T23:59:00Z")
        return WorkflowCheckpoint(
            workflowId = "workflow-undo",
            runId = "run-undo",
            projectId = "project-1",
            agentId = "lead",
            iteration = 2,
            messages = listOf(MessageSnapshot(SnapshotRole.USER, "continue", timestamp)),
            observations = emptyList(),
            budget = WorkflowBudgetSnapshot(),
            state = WorkflowCheckpointState.INTERRUPTED,
            createdAt = timestamp,
            updatedAt = timestamp.plusSeconds(1),
        )
    }
}
