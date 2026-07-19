package dev.omnicode.persistence

import com.google.gson.JsonParser
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

    private fun retention(
        maxUsageRecords: Int = 10,
        maxConversations: Int = 10,
        maxToolExecutionRecords: Int = 10,
        maxMessagesPerConversation: Int = 10,
        usageRetentionDays: Int = Int.MAX_VALUE,
    ) = PersistenceRetention(
        maxUsageRecords = maxUsageRecords,
        maxConversations = maxConversations,
        maxToolExecutionRecords = maxToolExecutionRecords,
        maxMessagesPerConversation = maxMessagesPerConversation,
        maxMessageChars = 2_000,
        maxToolSummaryChars = 2_000,
        usageRetentionDays = usageRetentionDays,
    )
}
