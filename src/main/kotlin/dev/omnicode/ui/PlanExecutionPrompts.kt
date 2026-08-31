package dev.omnicode.ui

import dev.omnicode.agent.AgentEngine
import dev.omnicode.plan.PlanBoard

/**
 * Builds the bounded prompt used when a user approves one inline plan step.
 *
 * Kept outside any UI implementation so the WebView shell does not depend on the
 * retired Swing chat panel. The board itself remains the authority for which step
 * may execute; the text only communicates that already-approved boundary to the
 * model.
 */
internal fun planStepExecutionPrompt(board: PlanBoard, stepId: String): String {
    val targetIndex = board.steps.indexOfFirst { it.id == stepId }
    require(targetIndex >= 0) { "Plan step is no longer present" }
    val target = board.steps[targetIndex]
    return buildString {
        appendLine("执行已批准计划 ${board.sourceFingerprint} 的第 ${targetIndex + 1}/${board.steps.size} 步。")
        appendLine("只完成本步骤，不要提前执行其他待批准、草稿或已跳过步骤。")
        appendLine("开始前重新读取相关文件；完成后运行本步骤最窄的有效验证并汇报证据。")
        appendLine()
        appendLine("当前步骤：")
        appendLine(target.text)
        appendLine()
        appendLine("看板边界：")
        board.steps.forEachIndexed { index, step ->
            append(index + 1).append(". ").append(step.state.name).append(" · ")
                .append(step.text.replace('\n', ' ').take(360)).appendLine()
        }
    }.take(AgentEngine.MAX_USER_MESSAGE_CHARS)
}

internal fun planStepTranscriptText(board: PlanBoard, stepId: String): String {
    val targetIndex = board.steps.indexOfFirst { it.id == stepId }
    require(targetIndex >= 0) { "Plan step is no longer present" }
    val compact = board.steps[targetIndex].text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .take(320)
    return "执行计划步骤 ${targetIndex + 1}/${board.steps.size}：$compact"
}
