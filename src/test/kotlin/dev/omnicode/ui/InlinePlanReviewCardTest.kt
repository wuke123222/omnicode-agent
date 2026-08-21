package dev.omnicode.ui

import dev.omnicode.agent.AgentMode
import dev.omnicode.plan.PlanBoard
import dev.omnicode.plan.PlanReviewDecision
import dev.omnicode.plan.PlanStep
import dev.omnicode.plan.PlanStepState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlinePlanReviewCardTest {
    @Test
    fun `claude plan summary makes the approval boundary explicit`() {
        val summary = inlinePlanSummary(board())

        assertTrue(summary.contains("Claude Plan · 只读探索完成"))
        assertTrue(summary.contains("等待你的选择"))
        assertTrue(summary.contains("未批准前不会修改文件"))
    }

    @Test
    fun `step states use user facing labels`() {
        assertEquals("待选择", inlinePlanStepStatus(PlanStep("1", "inspect")))
        assertEquals("已选择", inlinePlanStepStatus(PlanStep("2", "edit", PlanStepState.APPROVED)))
        assertEquals("执行中", inlinePlanStepStatus(PlanStep("3", "test", PlanStepState.RUNNING)))
        assertEquals("失败 · 可重试", inlinePlanStepStatus(PlanStep("4", "verify", PlanStepState.FAILED)))
    }

    @Test
    fun `plan progress stays compact and readable`() {
        val board = board().copy(
            steps = listOf(
                PlanStep("1", "inspect", PlanStepState.COMPLETED),
                PlanStep("2", "edit", PlanStepState.APPROVED),
            ),
        )

        assertEquals("1/2 完成 · 1 已选", inlinePlanProgressText(board))
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
