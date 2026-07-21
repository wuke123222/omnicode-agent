package dev.omnicode.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiAgentProgressCardTest {
    @Test
    fun `delegate card aggregates idempotent starts and terminal usage`() {
        val card = MultiAgentProgressCard()

        assertTrue(card.startDelegate("agent-1", "Explorer", "定位认证流程"))
        assertFalse(card.startDelegate("agent-1", "Explorer", "重复事件"))
        assertFalse(card.completeDelegate(
            agentId = "agent-1",
            status = DelegateProgressStatus.COMPLETED,
            summary = "已定位入口",
            tokens = 1_234,
        ))

        assertEquals(1, card.delegateCount)
        assertEquals(
            DelegateProgressSnapshot(
                agentId = "agent-1",
                displayName = "Explorer",
                objective = "定位认证流程",
                role = "",
                status = DelegateProgressStatus.COMPLETED,
                summary = "已定位入口",
                tokens = 1_234,
            ),
            card.snapshots().single(),
        )
    }

    @Test
    fun `completion without a start still creates one bounded delegate row`() {
        val card = MultiAgentProgressCard()

        assertTrue(card.completeDelegate(
            agentId = "agent-2",
            status = DelegateProgressStatus.FAILED,
            summary = "测试失败",
            tokens = -10,
            fallbackDisplayName = "Tester",
            fallbackObjective = "运行回归测试",
        ))

        val snapshot = card.snapshots().single()
        assertEquals("Tester", snapshot.displayName)
        assertEquals(DelegateProgressStatus.FAILED, snapshot.status)
        assertEquals(0, snapshot.tokens)
    }

    @Test
    fun `agent identity keeps sibling specialists separate and bounds model text`() {
        val card = MultiAgentProgressCard()
        val longObjective = "调查".repeat(400)
        val longSummary = "结论".repeat(800)

        assertTrue(card.startDelegate("agent-a", "Explorer", longObjective))
        assertTrue(card.startDelegate("agent-b", "Reviewer", longObjective))
        card.completeDelegate("agent-a", DelegateProgressStatus.COMPLETED, longSummary, 10)
        card.completeDelegate("agent-b", DelegateProgressStatus.COMPLETED, longSummary, 20)

        assertEquals(2, card.delegateCount)
        card.snapshots().forEach { snapshot ->
            assertTrue(snapshot.objective.length <= 500)
            assertTrue(snapshot.summary.length <= 1_200)
            assertTrue(snapshot.objective.endsWith("已截断）"))
            assertTrue(snapshot.summary.endsWith("已截断）"))
        }
    }
}
