package dev.omnicode.persistence

import com.google.gson.JsonParser
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunStatus
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OmniCodeLocalStoreTest {
    private lateinit var root: Path

    @BeforeTest
    fun createStoreDirectory() {
        root = Files.createTempDirectory("omnicode-persistence-test")
    }

    @AfterTest
    fun deleteStoreDirectory() {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `usage summary aggregates tokens cost and daily trend`() {
        val store = OmniCodeLocalStore(root)
        assertTrue(store.recordUsage(usage("u1", "2026-07-16T12:00:00Z", 100, 20, "0.10")))
        assertTrue(store.recordUsage(usage("u2", "2026-07-17T12:00:00Z", 200, 30, null)))
        assertTrue(store.recordUsage(usage("u3", "2026-07-17T13:00:00Z", 300, 40, "0.25")))

        val summary = store.summarizeUsage(
            query = UsageQuery(projectId = "project-1"),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(3, summary.runCount)
        assertEquals(600, summary.inputTokens)
        assertEquals(90, summary.outputTokens)
        assertEquals(BigDecimal("0.35"), summary.estimatedCostUsd)
        assertEquals(2, summary.pricedRunCount)
        assertEquals(listOf(LocalDate.parse("2026-07-16"), LocalDate.parse("2026-07-17")), summary.daily.map { it.date })
        assertEquals(2, summary.daily.last().runCount)
        assertEquals(570, summary.daily.last().totalTokens)
    }

    @Test
    fun `usage append is idempotent and retention keeps newest records`() {
        val store = OmniCodeLocalStore(root, retention(maxUsageRecords = 2))
        assertTrue(store.recordUsage(usage("u1", "2026-07-16T10:00:00Z", 1, 1, null)))
        assertTrue(store.recordUsage(usage("u2", "2026-07-16T11:00:00Z", 2, 2, null)))
        assertTrue(store.recordUsage(usage("u3", "2026-07-16T12:00:00Z", 3, 3, null)))
        assertFalse(store.recordUsage(usage("u3", "2026-07-16T12:00:00Z", 3, 3, null)))

        assertEquals(listOf("u3", "u2"), store.queryUsage().map { it.id })
    }

    @Test
    fun `cumulative usage upsert replaces the same resumed run instead of double counting`() {
        val store = OmniCodeLocalStore(root)
        val initial = usage("usage:resumed", "2026-07-16T10:00:00Z", 100, 20, "0.10")
            .copy(runId = "resumed", workflowId = "resumed")
        store.saveUsage(initial)
        store.saveUsage(
            initial.copy(
                inputTokens = 160,
                outputTokens = 35,
                estimatedCostUsd = BigDecimal("0.18"),
                recordedAt = initial.recordedAt.plusSeconds(60),
            ),
        )

        val records = store.queryUsage(UsageQuery(workflowId = "resumed"))
        assertEquals(1, records.size)
        assertEquals(195, records.single().totalTokens)
        assertEquals(BigDecimal("0.18"), records.single().estimatedCostUsd)
    }

    @Test
    fun `usage business id is replay safe and remains queryable by workflow metadata`() {
        val store = OmniCodeLocalStore(root)
        val original = usage("usage:run-1", "2026-07-16T10:00:00Z", 12, 3, "0.01").copy(
            runId = "run-1",
            workflowId = " workflow-1 ",
            agentId = "lead-agent",
            strategy = AgentExecutionStrategy.TEAM,
        )

        assertTrue(store.recordUsage(original))
        assertFalse(store.recordUsage(original.copy(recordedAt = original.recordedAt.plusSeconds(1))))

        val records = store.queryUsage(
            UsageQuery(
                workflowId = "workflow-1",
                agentId = "lead-agent",
                strategy = AgentExecutionStrategy.TEAM,
            ),
        )
        assertEquals(listOf("usage:run-1"), records.map { it.id })
        assertEquals(15, store.summarizeUsage().totalTokens)
    }

    @Test
    fun `usage day retention prunes expired records from storage`() {
        val now = Instant.now()
        val seed = OmniCodeLocalStore(root)
        seed.recordUsage(usage("old", now.minusSeconds(40L * 86_400L).toString(), 1, 1, null))
        seed.recordUsage(usage("recent", now.minusSeconds(5L * 86_400L).toString(), 2, 2, null))

        val retained = OmniCodeLocalStore(root, retention(usageRetentionDays = 30))

        assertEquals(listOf("recent"), retained.queryUsage().map { it.id })
        assertFalse(Files.readString(root.resolve("usage.jsonl")).contains("\"id\":\"old\""))
    }

    @Test
    fun `corrupt jsonl lines are ignored and cleaned on the next write`() {
        val store = OmniCodeLocalStore(root, retention(maxUsageRecords = 4))
        store.recordUsage(usage("u1", "2026-07-16T10:00:00Z", 1, 1, null))
        Files.writeString(
            root.resolve("usage.jsonl"),
            "{ definitely-not-json\n",
            StandardOpenOption.APPEND,
        )

        assertEquals(listOf("u1"), store.queryUsage().map { it.id })
        assertTrue(store.recordUsage(usage("u2", "2026-07-16T11:00:00Z", 2, 2, null)))
        assertEquals(listOf("u2", "u1"), store.queryUsage().map { it.id })
        assertFalse(Files.readString(root.resolve("usage.jsonl")).contains("definitely-not-json"))
    }

    @Test
    fun `conversation snapshots upsert preserve system context and redact secrets`() {
        val secret = "opaque-provider-secret-123"
        val store = OmniCodeLocalStore(
            root,
            retention(maxMessagesPerConversation = 3),
            DefaultSensitiveDataRedactor(listOf(secret)),
        )
        val created = Instant.parse("2026-07-16T10:00:00Z")
        val initial = ConversationRecord(
            id = "conversation-1",
            projectId = "project-1",
            title = "Investigate API key=$secret",
            createdAt = created,
            updatedAt = created,
            messages = listOf(
                MessageSnapshot(SnapshotRole.SYSTEM, "System context"),
                MessageSnapshot(SnapshotRole.USER, "first"),
                MessageSnapshot(SnapshotRole.ASSISTANT, "second"),
                MessageSnapshot(SnapshotRole.USER, "Authorization: Bearer abcdefghijklmnop"),
                MessageSnapshot(SnapshotRole.ASSISTANT, "password=$secret"),
            ),
        )
        store.saveConversation(initial)
        store.saveConversation(initial.copy(updatedAt = created.plusSeconds(60), title = "Updated $secret"))

        val conversations = store.conversations(projectId = "project-1")
        assertEquals(1, conversations.size)
        val saved = assertNotNull(store.conversation("conversation-1"))
        assertEquals(3, saved.messages.size)
        assertEquals(SnapshotRole.SYSTEM, saved.messages.first().role)
        assertTrue(saved.title.contains("[REDACTED]"))

        val disk = Files.readString(root.resolve("conversations.jsonl"))
        assertFalse(disk.contains(secret))
        assertFalse(disk.contains("abcdefghijklmnop"))
    }

    @Test
    fun `tool audit supports filtering redaction and bounded retention`() {
        val secret = "another-opaque-secret"
        val store = OmniCodeLocalStore(
            root,
            retention(maxToolExecutionRecords = 2),
            DefaultSensitiveDataRedactor(listOf(secret)),
        )
        store.recordToolExecution(toolRecord("t1", "exec-1", ToolExecutionStatus.COMPLETED, "ok"))
        store.recordToolExecution(toolRecord("t2", "exec-2", ToolExecutionStatus.FAILED, "api_key=$secret"))
        store.recordToolExecution(toolRecord("t3", "exec-3", ToolExecutionStatus.FAILED, "Bearer sk-abcdefghijk"))

        val failures = store.queryToolExecutions(ToolExecutionQuery(status = ToolExecutionStatus.FAILED))
        assertEquals(listOf("t3", "t2"), failures.map { it.id })
        assertEquals(listOf("call-exec-3", "call-exec-2"), failures.map { it.toolCallId })
        assertFalse(failures.joinToString().contains(secret))
        assertFalse(Files.readString(root.resolve("tool-executions.jsonl")).contains(secret))
    }

    @Test
    fun `approved dangerous action is retained as an independent audit lifecycle record`() {
        val store = OmniCodeLocalStore(root)
        store.recordToolExecution(
            toolRecord("t1", "exec-approval", ToolExecutionStatus.REQUESTED, "requested"),
        )
        store.recordToolExecution(
            toolRecord("t2", "exec-approval", ToolExecutionStatus.APPROVED, "approved").copy(
                approvalDecision = ToolApprovalDecision.APPROVED,
                dangerous = true,
            ),
        )
        store.recordToolExecution(
            toolRecord("t3", "exec-approval", ToolExecutionStatus.COMPLETED, "completed").copy(
                approvalDecision = ToolApprovalDecision.APPROVED,
                dangerous = true,
            ),
        )

        val lifecycle = store.queryToolExecutions(ToolExecutionQuery(executionId = "exec-approval"))

        assertEquals(
            listOf(ToolExecutionStatus.COMPLETED, ToolExecutionStatus.APPROVED, ToolExecutionStatus.REQUESTED),
            lifecycle.map(ToolExecutionRecord::status),
        )
        assertEquals(ToolApprovalDecision.APPROVED, lifecycle[1].approvalDecision)
    }

    @Test
    fun `tool audit query isolates agents that reuse the same call id`() {
        val store = OmniCodeLocalStore(root)
        store.recordToolExecution(
            toolRecord("t1", "exec-agent-a", ToolExecutionStatus.COMPLETED, "agent a").copy(
                workflowId = " workflow-1 ",
                agentId = "agent-a",
                parentAgentId = "lead-agent",
                strategy = AgentExecutionStrategy.TEAM,
                toolCallId = "shared-call",
            ),
        )
        store.recordToolExecution(
            toolRecord("t2", "exec-agent-b", ToolExecutionStatus.COMPLETED, "agent b").copy(
                workflowId = "workflow-1",
                agentId = "agent-b",
                parentAgentId = "lead-agent",
                strategy = AgentExecutionStrategy.TEAM,
                toolCallId = "shared-call",
            ),
        )

        val agentA = store.queryToolExecutions(
            ToolExecutionQuery(workflowId = "workflow-1", agentId = "agent-a"),
        )
        val agentB = store.queryToolExecutions(
            ToolExecutionQuery(workflowId = " workflow-1 ", agentId = "agent-b"),
        )

        assertEquals(listOf("t1"), agentA.map { it.id })
        assertEquals(listOf("t2"), agentB.map { it.id })
        assertEquals(listOf("shared-call"), agentA.map { it.toolCallId })
        assertEquals(listOf("shared-call"), agentB.map { it.toolCallId })
    }

    @Test
    fun `concurrent appenders do not lose records`() {
        val store = OmniCodeLocalStore(root, retention(maxUsageRecords = 100))
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 4).map { worker ->
                executor.submit {
                    start.await()
                    repeat(20) { index ->
                        val id = "u-$worker-$index"
                        assertTrue(store.recordUsage(usage(id, "2026-07-17T12:00:00Z", 1, 1, null)))
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(80, store.queryUsage(UsageQuery(limit = 100)).size)
    }

    @Test
    fun `usage and tool audit queries isolate agent plan and research modes`() {
        val store = OmniCodeLocalStore(root)
        store.recordUsage(usage("agent", "2026-07-17T12:00:00Z", 10, 2, null).copy(mode = AgentMode.AGENT))
        store.recordUsage(usage("plan", "2026-07-17T12:01:00Z", 20, 3, null).copy(mode = AgentMode.PLAN))
        store.recordUsage(usage("research", "2026-07-17T12:02:00Z", 25, 4, null).copy(mode = AgentMode.RESEARCH))
        store.recordUsage(usage("legacy", "2026-07-17T12:03:00Z", 30, 4, null))
        store.recordToolExecution(
            toolRecord("t1", "exec-agent", ToolExecutionStatus.COMPLETED, "ok").copy(mode = AgentMode.AGENT),
        )
        store.recordToolExecution(
            toolRecord("t2", "exec-plan", ToolExecutionStatus.FAILED, "blocked").copy(mode = AgentMode.PLAN),
        )
        store.recordToolExecution(
            toolRecord("t3", "exec-research", ToolExecutionStatus.COMPLETED, "observed")
                .copy(mode = AgentMode.RESEARCH),
        )

        assertEquals(listOf("plan"), store.queryUsage(UsageQuery(mode = AgentMode.PLAN)).map { it.id })
        assertEquals(listOf("research"), store.queryUsage(UsageQuery(mode = AgentMode.RESEARCH)).map { it.id })
        assertEquals(
            listOf("t1"),
            store.queryToolExecutions(ToolExecutionQuery(mode = AgentMode.AGENT)).map { it.id },
        )
        assertEquals(
            listOf("t3"),
            store.queryToolExecutions(ToolExecutionQuery(mode = AgentMode.RESEARCH)).map { it.id },
        )
        assertEquals(4, store.queryUsage().size)
    }

    @Test
    fun `research conversation mode survives persistence round trip`() {
        val store = OmniCodeLocalStore(root)
        val timestamp = Instant.parse("2026-07-17T12:00:00Z")
        store.saveConversation(
            ConversationRecord(
                id = "research-conversation",
                projectId = "project-1",
                title = "Investigate a regression",
                createdAt = timestamp,
                updatedAt = timestamp,
                messages = listOf(MessageSnapshot(SnapshotRole.ASSISTANT, "Evidence summary")),
                mode = AgentMode.RESEARCH,
            ),
        )

        val reloaded = assertNotNull(OmniCodeLocalStore(root).conversation("research-conversation"))
        assertEquals(AgentMode.RESEARCH, reloaded.mode)
    }

    @Test
    fun `conversation records without checkpoint metadata remain readable and migrate when saved`() {
        val created = Instant.parse("2026-07-17T12:00:00Z")
        val legacy = ConversationRecord(
            id = "legacy-conversation",
            projectId = "project-1",
            title = "Legacy",
            createdAt = created,
            updatedAt = created,
            messages = listOf(MessageSnapshot(SnapshotRole.USER, "old message")),
            mode = AgentMode.AGENT,
        )
        val json = JsonParser.parseString(PersistenceJson.gson.toJson(legacy)).asJsonObject
        json.remove("mode")
        json.remove("lastRunStatus")
        Files.writeString(root.resolve("conversations.jsonl"), PersistenceJson.gson.toJson(json) + "\n")

        val store = OmniCodeLocalStore(root)
        val loaded = assertNotNull(store.conversation(legacy.id))
        assertEquals(null, loaded.mode)
        assertEquals(null, loaded.lastRunStatus)

        val migrated = store.saveConversation(loaded.copy(updatedAt = created.plusSeconds(1)))
        assertEquals(AgentMode.AGENT, migrated.mode)
        assertEquals(AgentRunStatus.COMPLETED, migrated.lastRunStatus)
        val reloaded = assertNotNull(store.conversation(legacy.id))
        assertEquals(AgentMode.AGENT, reloaded.mode)
        assertEquals(AgentRunStatus.COMPLETED, reloaded.lastRunStatus)
    }

    @Test
    fun `records without multi agent metadata remain readable`() {
        val timestamp = Instant.parse("2026-07-17T12:00:00Z")
        val legacyUsage = usage("legacy-usage", timestamp.toString(), 10, 2, null)
        val legacyConversation = ConversationRecord(
            id = "legacy-metadata-conversation",
            projectId = "project-1",
            title = "Legacy metadata",
            createdAt = timestamp,
            updatedAt = timestamp,
            messages = listOf(MessageSnapshot(SnapshotRole.USER, "old message")),
        )
        val legacyTool = toolRecord("t1", "legacy-execution", ToolExecutionStatus.COMPLETED, "ok")
        val metadataFields = listOf("workflowId", "agentId", "parentAgentId", "strategy")

        fun withoutMultiAgentMetadata(value: Any): String {
            val json = JsonParser.parseString(PersistenceJson.gson.toJson(value)).asJsonObject
            metadataFields.forEach { json.remove(it) }
            return PersistenceJson.gson.toJson(json) + "\n"
        }

        Files.writeString(root.resolve("usage.jsonl"), withoutMultiAgentMetadata(legacyUsage))
        Files.writeString(root.resolve("conversations.jsonl"), withoutMultiAgentMetadata(legacyConversation))
        Files.writeString(root.resolve("tool-executions.jsonl"), withoutMultiAgentMetadata(legacyTool))

        val store = OmniCodeLocalStore(root)
        val loadedUsage = assertNotNull(store.queryUsage().singleOrNull())
        val loadedConversation = assertNotNull(store.conversation(legacyConversation.id))
        val loadedTool = assertNotNull(store.queryToolExecutions().singleOrNull())

        listOf(loadedUsage.workflowId, loadedUsage.agentId, loadedUsage.parentAgentId, loadedUsage.strategy)
            .forEach { assertEquals(null, it) }
        listOf(
            loadedConversation.workflowId,
            loadedConversation.agentId,
            loadedConversation.parentAgentId,
            loadedConversation.strategy,
        ).forEach { assertEquals(null, it) }
        listOf(loadedTool.workflowId, loadedTool.agentId, loadedTool.parentAgentId, loadedTool.strategy)
            .forEach { assertEquals(null, it) }
    }

    @Test
    fun `workflow checkpoint round trip is bounded redacted and retains recovery state`() {
        val secret = "checkpoint-secret-123"
        val store = OmniCodeLocalStore(
            root,
            retention(maxMessagesPerConversation = 4),
            DefaultSensitiveDataRedactor(listOf(secret)),
        )
        val checkpoint = workflowCheckpoint("workflow-1").copy(
            state = WorkflowCheckpointState.WAITING_FOR_APPROVAL,
            pendingTool = PendingToolSnapshot(
                executionId = "execution-1",
                toolCallId = "call-1",
                toolName = "run_command",
                argumentsJson = "{\"api_key\":\"$secret\"}",
                dangerous = true,
                executionStarted = true,
            ),
            pendingApproval = PendingApprovalSnapshot(
                approvalId = "approval-1",
                toolCallId = "call-1",
                toolName = "run_command",
                title = "Run with $secret",
                risk = "Authorization: Bearer abcdefghijklmnop",
            ),
            requiredImageAttachments = 2,
            delegates = listOf(
                DelegateCheckpointSnapshot(
                    delegationId = "delegation-1",
                    agentId = "explorer-1",
                    parentAgentId = "lead",
                    role = "EXPLORER",
                    objective = "Inspect $secret",
                    state = WorkflowCheckpointState.RUNNING,
                ),
            ),
        )

        val saved = store.saveWorkflowCheckpoint(checkpoint)
        val reloaded = assertNotNull(OmniCodeLocalStore(root).workflowCheckpoint(" workflow-1 "))

        assertEquals(saved, reloaded)
        assertEquals(WorkflowCheckpointState.WAITING_FOR_APPROVAL, reloaded.state)
        assertEquals(7, reloaded.iteration)
        assertEquals(2, reloaded.budget.toolCalls)
        assertEquals("call-1", reloaded.pendingTool?.toolCallId)
        assertTrue(reloaded.pendingTool?.executionStarted == true)
        assertEquals(2, reloaded.requiredImageAttachments)
        assertEquals("explorer-1", reloaded.delegates.single().agentId)
        val disk = Files.readString(root.resolve("workflow-checkpoints.jsonl"))
        assertFalse(disk.contains(secret))
        assertFalse(disk.contains("abcdefghijklmnop"))
    }

    @Test
    fun `workflow checkpoint upsert interruption and completed cleanup are idempotent`() {
        val store = OmniCodeLocalStore(root, retention(maxWorkflowCheckpoints = 3))
        val created = workflowCheckpoint("workflow-running")
        store.saveWorkflowCheckpoint(created)
        val latest = created.copy(iteration = 8, updatedAt = created.updatedAt.plusSeconds(1))
        store.saveWorkflowCheckpoint(latest)
        store.saveWorkflowCheckpoint(created.copy(iteration = 2))
        store.saveWorkflowCheckpoint(
            workflowCheckpoint("workflow-completed").copy(state = WorkflowCheckpointState.COMPLETED),
        )

        assertEquals(2, store.workflowCheckpoints().size)
        assertEquals(8, assertNotNull(store.workflowCheckpoint("workflow-running")).iteration)
        val interruptedAt = created.updatedAt.plusSeconds(2)
        assertEquals(1, store.markUnfinishedWorkflowCheckpointsInterrupted(interruptedAt = interruptedAt))
        assertEquals(0, store.markUnfinishedWorkflowCheckpointsInterrupted(interruptedAt = interruptedAt.plusSeconds(1)))
        assertEquals(
            listOf("workflow-running"),
            store.unfinishedWorkflowCheckpoints().map(WorkflowCheckpoint::workflowId),
        )

        assertEquals(1, store.deleteCompletedWorkflowCheckpoints())
        assertEquals(0, store.deleteCompletedWorkflowCheckpoints())
        assertTrue(store.deleteWorkflowCheckpoint("workflow-running"))
        assertFalse(store.deleteWorkflowCheckpoint("workflow-running"))
    }

    @Test
    fun `legacy workflow checkpoint without state and version migrates to interrupted on save`() {
        val legacy = workflowCheckpoint("legacy-workflow")
        val json = JsonParser.parseString(PersistenceJson.gson.toJson(legacy)).asJsonObject
        json.remove("state")
        json.remove("version")
        Files.writeString(root.resolve("workflow-checkpoints.jsonl"), PersistenceJson.gson.toJson(json) + "\n")

        val store = OmniCodeLocalStore(root)
        val loaded = assertNotNull(store.workflowCheckpoint(legacy.workflowId))
        assertEquals(null, loaded.state)
        assertEquals(0, loaded.version)
        assertEquals(listOf(legacy.workflowId), store.unfinishedWorkflowCheckpoints().map { it.workflowId })

        val migrated = store.saveWorkflowCheckpoint(loaded.copy(updatedAt = loaded.updatedAt.plusSeconds(1)))
        assertEquals(WorkflowCheckpointState.INTERRUPTED, migrated.state)
        assertEquals(CURRENT_WORKFLOW_CHECKPOINT_VERSION, migrated.version)
        assertEquals(WorkflowCheckpointState.INTERRUPTED, store.workflowCheckpoint(legacy.workflowId)?.state)
    }

    @Test
    fun `corrupt workflow checkpoint records are ignored and cleaned`() {
        val store = OmniCodeLocalStore(root)
        store.saveWorkflowCheckpoint(workflowCheckpoint("valid-workflow"))
        Files.writeString(
            root.resolve("workflow-checkpoints.jsonl"),
            "{ broken-checkpoint\n",
            StandardOpenOption.APPEND,
        )

        assertEquals(listOf("valid-workflow"), store.workflowCheckpoints().map { it.workflowId })
        assertFalse(Files.readString(root.resolve("workflow-checkpoints.jsonl")).contains("broken-checkpoint"))
    }

    private fun usage(
        id: String,
        timestamp: String,
        input: Long,
        output: Long,
        cost: String?,
    ) = UsageRecord(
        id = id,
        runId = "run-$id",
        projectId = "project-1",
        providerId = "openai",
        model = "test-model",
        inputTokens = input,
        outputTokens = output,
        estimatedCostUsd = cost?.let(::BigDecimal),
        recordedAt = Instant.parse(timestamp),
    )

    private fun toolRecord(
        id: String,
        executionId: String,
        status: ToolExecutionStatus,
        output: String,
    ) = ToolExecutionRecord(
        id = id,
        executionId = executionId,
        runId = "run-1",
        projectId = "project-1",
        conversationId = "conversation-1",
        toolName = "read_file",
        toolCallId = "call-$executionId",
        status = status,
        outputSummary = output,
        recordedAt = Instant.parse("2026-07-17T12:00:0${id.last()}Z"),
    )

    private fun workflowCheckpoint(workflowId: String): WorkflowCheckpoint {
        val timestamp = Instant.parse("2026-07-17T12:00:00Z")
        return WorkflowCheckpoint(
            workflowId = workflowId,
            runId = "run-$workflowId",
            projectId = "project-1",
            conversationId = "conversation-1",
            agentId = "lead",
            iteration = 7,
            messages = listOf(
                MessageSnapshot(SnapshotRole.SYSTEM, "System context", timestamp),
                MessageSnapshot(SnapshotRole.USER, "Resume this task", timestamp.plusSeconds(1)),
            ),
            observations = listOf(
                WorkflowObservationSnapshot(
                    toolCallId = "previous-call",
                    toolName = "read_file",
                    text = "Observed evidence",
                    recordedAt = timestamp.plusSeconds(2),
                ),
            ),
            budget = WorkflowBudgetSnapshot(
                inputTokens = 100,
                outputTokens = 20,
                reservedInputTokens = 10,
                maxInputTokens = 1_000,
                maxOutputTokens = 500,
                maxTotalTokens = 1_500,
                toolCalls = 2,
                maxToolCalls = 20,
            ),
            state = WorkflowCheckpointState.RUNNING,
            createdAt = timestamp,
            updatedAt = timestamp.plusSeconds(3),
        )
    }

    private fun retention(
        maxUsageRecords: Int = 10,
        maxConversations: Int = 10,
        maxToolExecutionRecords: Int = 10,
        maxWorkflowCheckpoints: Int = 10,
        maxMessagesPerConversation: Int = 10,
        usageRetentionDays: Int = Int.MAX_VALUE,
    ) = PersistenceRetention(
        maxUsageRecords = maxUsageRecords,
        maxConversations = maxConversations,
        maxToolExecutionRecords = maxToolExecutionRecords,
        maxWorkflowCheckpoints = maxWorkflowCheckpoints,
        maxMessagesPerConversation = maxMessagesPerConversation,
        maxMessageChars = 2_000,
        maxToolSummaryChars = 2_000,
        usageRetentionDays = usageRetentionDays,
    )
}
