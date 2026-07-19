package dev.omnicode.settings

import dev.omnicode.agent.AgentMode
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.ToolApprovalDecision
import dev.omnicode.persistence.ToolExecutionRecord
import dev.omnicode.persistence.ToolExecutionStatus
import dev.omnicode.persistence.UsageRecord
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OmniCodeInsightsConfigurableTest {
    @Test
    fun `usage details group each day by agent plan research and legacy mode`() {
        val at = Instant.parse("2026-07-17T08:00:00Z")
        val records = listOf(
            usageRecord("agent-1", AgentMode.AGENT, 100, 20, "0.10", at),
            usageRecord("agent-2", AgentMode.AGENT, 50, 10, null, at.plusSeconds(60)),
            usageRecord("plan-1", AgentMode.PLAN, 40, 8, "0.04", at.plusSeconds(120)),
            usageRecord("research-1", AgentMode.RESEARCH, 60, 12, "0.06", at.plusSeconds(180)),
            usageRecord("legacy-1", null, 20, 4, null, at.plusSeconds(240)),
        )

        val rows = usageRowsByMode(records, ZoneOffset.UTC)

        assertEquals(listOf("Agent", "Plan", "Research", "—"), rows.map { displayAgentMode(it.mode) })
        assertEquals(2, rows[0].runCount)
        assertEquals(150, rows[0].inputTokens)
        assertEquals(30, rows[0].outputTokens)
        assertEquals(BigDecimal("0.10"), rows[0].estimatedCostUsd)
    }

    @Test
    fun `insights tables expose mode columns with legacy fallback`() {
        val daily = DailyUsageTableModel().apply {
            setRows(
                listOf(
                    ModeDailyUsageRow(
                        date = java.time.LocalDate.parse("2026-07-17"),
                        mode = AgentMode.PLAN,
                        runCount = 1,
                        inputTokens = 10,
                        outputTokens = 2,
                        estimatedCostUsd = null,
                    ),
                ),
            )
        }
        val audit = ToolAuditTableModel().apply {
            setRows(
                listOf(
                    toolRecord("plan", AgentMode.PLAN),
                    toolRecord("research", AgentMode.RESEARCH),
                    toolRecord("legacy", null),
                ),
            )
        }

        assertEquals("模式", daily.getColumnName(1))
        assertEquals("Plan", daily.getValueAt(0, 1))
        assertEquals("模式", audit.getColumnName(2))
        assertEquals("Plan", audit.getValueAt(0, 2))
        assertEquals("Research", audit.getValueAt(1, 2))
        assertEquals("—", audit.getValueAt(2, 2))
    }

    @Test
    fun `pricing rows normalize globs and rates`() {
        val pricing = normalizePricingRows(
            listOf(
                PricingRow(" openai ", " gpt-4o-* ", "2.50", "10"),
                PricingRow("anthropic", " ", "3", "15.0"),
            ),
        )

        assertEquals(
            listOf(
                ModelPricing("openai", "gpt-4o-*", 2.5, 10.0),
                ModelPricing("anthropic", "*", 3.0, 15.0),
            ),
            pricing,
        )
    }

    @Test
    fun `pricing rows reject invalid and duplicate rules`() {
        assertFailsWith<IllegalArgumentException> {
            normalizePricingRows(listOf(PricingRow("openai", "*", "-1", "2")))
        }
        assertFailsWith<IllegalArgumentException> {
            normalizePricingRows(listOf(PricingRow("openai", "*", "free", "2")))
        }
        assertFailsWith<IllegalArgumentException> {
            normalizePricingRows(
                listOf(
                    PricingRow("openai", "gpt-*", "1", "2"),
                    PricingRow("openai", "gpt-*", "3", "4"),
                ),
            )
        }
    }

    @Test
    fun `conversation summary includes metadata roles and recent messages`() {
        val timestamp = Instant.parse("2026-07-17T08:00:00Z")
        val summary = conversationSummary(
            ConversationRecord(
                id = "conversation-1",
                projectId = "project-a",
                title = "Inspect persistence",
                createdAt = timestamp,
                updatedAt = timestamp.plusSeconds(30),
                messages = listOf(
                    MessageSnapshot(SnapshotRole.USER, "Please inspect it", timestamp),
                    MessageSnapshot(SnapshotRole.ASSISTANT, "Done", timestamp.plusSeconds(30)),
                ),
            ),
        )

        assertTrue(summary.contains("Inspect persistence"))
        assertTrue(summary.contains("项目：project-a"))
        assertTrue(summary.contains("user: 1"))
        assertTrue(summary.contains("assistant: 1"))
        assertTrue(summary.contains("Please inspect it"))
    }

    private fun usageRecord(
        id: String,
        mode: AgentMode?,
        input: Long,
        output: Long,
        cost: String?,
        recordedAt: Instant,
    ): UsageRecord = UsageRecord(
        id = id,
        runId = "run-$id",
        providerId = "openai",
        model = "test-model",
        inputTokens = input,
        outputTokens = output,
        estimatedCostUsd = cost?.let(::BigDecimal),
        recordedAt = recordedAt,
        mode = mode,
    )

    private fun toolRecord(id: String, mode: AgentMode?): ToolExecutionRecord = ToolExecutionRecord(
        id = id,
        executionId = "execution-$id",
        runId = "run-$id",
        toolName = "read_file",
        status = ToolExecutionStatus.COMPLETED,
        approvalDecision = ToolApprovalDecision.NOT_REQUIRED,
        recordedAt = Instant.parse("2026-07-17T08:00:00Z"),
        mode = mode,
    )
}
