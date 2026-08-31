package dev.omnicode.ui

import dev.omnicode.agent.AgentMode
import dev.omnicode.plan.PlanBoard
import dev.omnicode.plan.PlanReviewDecision
import dev.omnicode.plan.PlanStep
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanExecutionPromptsTest {
    @Test
    fun `approved execution prompt makes the one step boundary explicit`() {
        val prompt = planStepExecutionPrompt(board(), "2")

        assertTrue(prompt.contains("第 2/2 步"))
        assertTrue(prompt.contains("只完成本步骤"))
        assertTrue(prompt.contains("2. DRAFT · edit"))
    }

    @Test
    fun `approved execution uses a compact transcript label instead of the internal prompt`() {
        val text = planStepTranscriptText(board(), "2")

        assertEquals("执行计划步骤 2/2：edit", text)
        assertTrue(!text.contains("看板边界"))
        assertTrue(!text.contains("sourceFingerprint"))
    }

    private fun board() = PlanBoard(
        id = "board-1",
        title = "实施计划",
        sourceMode = AgentMode.CLAUDE_PLAN,
        sourceFingerprint = "abcdef",
        sourceText = "- [ ] inspect\n- [ ] edit",
        steps = listOf(PlanStep("1", "inspect"), PlanStep("2", "edit")),
        revision = 1,
        reviewDecision = PlanReviewDecision.PENDING,
        reviewRevision = 0,
        reviewedAt = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
