package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.agent.AgentMode
import dev.omnicode.plan.PlanBoard
import dev.omnicode.plan.PlanBoardService
import dev.omnicode.plan.PlanExecutionPolicy
import dev.omnicode.plan.PlanExecutionRequest
import dev.omnicode.plan.PlanReviewAction
import dev.omnicode.plan.PlanReviewDecision
import dev.omnicode.plan.PlanStep
import dev.omnicode.plan.PlanStepState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

internal interface InlinePlanReviewActions {
    fun execute(request: PlanExecutionRequest)
    fun continuePlanning(board: PlanBoard)
    fun openFullBoard()
    fun showMessage(message: String, isError: Boolean = false)
}

/**
 * Keeps the Plan -> Agent approval boundary in the conversation where the user asked the question.
 * The full board remains available for power users, but completing a plan never navigates away.
 */
internal class InlinePlanReviewCard(
    private val service: PlanBoardService,
    private val boardId: String,
    private val actions: InlinePlanReviewActions,
) : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.timelineCard,
    outlineColor = OmniCodeUiPalette.border,
    radius = 12,
), Disposable {
    private val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val title = JBLabel("计划待审阅").apply { font = JBFont.label().asBold() }
    private val summary = JBLabel().apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private val selectAllButton = JButton("全选")
    private val continueButton = JButton("继续规划")
    private val manualButton = JButton("逐步执行选中项")
    private val automaticButton = JButton("批准全部并执行")
    private val fullBoardButton = JButton("完整看板")
    private val editors = linkedMapOf<String, JBTextArea>()
    private var superseded = false
    @Volatile
    private var disposed = false

    init {
        layout = BorderLayout(0, JBUI.scale(9))
        border = JBUI.Borders.empty(12)
        add(header(), BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        add(actionsRow(), BorderLayout.SOUTH)

        selectAllButton.addActionListener {
            flushEdits()
            service.approveAll()
        }
        continueButton.addActionListener {
            flushEdits()
            if (service.applyReviewAction(PlanReviewAction.CONTINUE_PLANNING)) {
                service.snapshot()?.takeIf { it.id == boardId }?.let(actions::continuePlanning)
            }
        }
        manualButton.addActionListener {
            approveAndExecute(PlanReviewAction.APPROVE_MANUAL, PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION)
        }
        automaticButton.addActionListener {
            flushEdits()
            if (service.snapshot()?.takeIf { it.id == boardId }?.approvedCount == 0) service.approveAll()
            approveAndExecute(PlanReviewAction.APPROVE_AUTO, PlanExecutionPolicy.AUTO_AGENT)
        }
        fullBoardButton.addActionListener { actions.openFullBoard() }

        service.addListener(this) { board ->
            if (disposed || superseded) return@addListener
            val refresh = Runnable { if (!disposed && !superseded) render(board?.takeIf { it.id == boardId }) }
            if (SwingUtilities.isEventDispatchThread()) refresh.run()
            else ApplicationManager.getApplication().invokeLater(refresh)
        }
        render(service.snapshot()?.takeIf { it.id == boardId })
    }

    override fun dispose() {
        disposed = true
        editors.clear()
    }

    fun markSuperseded() {
        superseded = true
        editors.values.forEach { it.isEditable = false }
        listOf(selectAllButton, continueButton, manualButton, automaticButton).forEach { it.isEnabled = false }
        summary.text = "已有更新的计划版本；此卡片仅保留为历史记录。"
        summary.toolTipText = boundedTooltipHtml(summary.text)
        revalidate()
        repaint()
    }

    private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(title.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(3)))
            add(summary.apply { alignmentX = LEFT_ALIGNMENT })
        }, BorderLayout.CENTER)
        add(fullBoardButton, BorderLayout.EAST)
    }

    private fun actionsRow(): JComponent = WrappingActionPanel(
        alignment = FlowLayout.RIGHT,
        horizontalGap = JBUI.scale(6),
        verticalGap = JBUI.scale(6),
    ).apply {
        isOpaque = false
        border = JBUI.Borders.customLine(OmniCodeUiPalette.border, 1, 0, 0, 0)
        add(selectAllButton)
        add(continueButton)
        add(manualButton)
        add(automaticButton)
    }

    private fun render(board: PlanBoard?) {
        editors.clear()
        body.removeAll()
        if (board == null) {
            title.text = "计划已不可用"
            summary.text = "计划可能已被清除或替换；请重新运行 /plan。"
            listOf(selectAllButton, continueButton, manualButton, automaticButton).forEach { it.isEnabled = false }
        } else {
            title.text = board.title
            summary.text = inlinePlanSummary(board)
            board.steps.forEachIndexed { index, step ->
                body.add(stepRow(index + 1, step))
                if (index != board.steps.lastIndex) body.add(Box.createVerticalStrut(JBUI.scale(6)))
            }
            val editable = !board.hasRunningStep && !superseded
            selectAllButton.isEnabled = editable && board.steps.any {
                it.state == PlanStepState.DRAFT || it.state == PlanStepState.FAILED || it.state == PlanStepState.PAUSED
            }
            continueButton.isEnabled = editable
            manualButton.isEnabled = editable && board.approvedCount > 0
            automaticButton.isEnabled = editable && board.steps.any {
                it.state == PlanStepState.DRAFT || it.state == PlanStepState.APPROVED ||
                    it.state == PlanStepState.FAILED || it.state == PlanStepState.PAUSED
            }
            automaticButton.text = when {
                board.executionPolicy == PlanExecutionPolicy.AUTO_AGENT -> "继续自动执行"
                board.approvedCount > 0 -> "自动执行已选 ${board.approvedCount} 项"
                else -> "批准全部并执行"
            }
        }
        title.toolTipText = boundedTooltipHtml(title.text)
        summary.toolTipText = boundedTooltipHtml(summary.text)
        body.revalidate()
        body.repaint()
        revalidate()
        repaint()
    }

    private fun stepRow(number: Int, step: PlanStep): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.timelineElevated,
        outlineColor = if (step.state == PlanStepState.RUNNING) OmniCodeUiPalette.accent else OmniCodeUiPalette.timelineBorder,
        radius = 9,
    ).apply {
        layout = BorderLayout(JBUI.scale(8), JBUI.scale(5))
        border = JBUI.Borders.empty(8)
        val selectable = step.state !in setOf(PlanStepState.RUNNING, PlanStepState.COMPLETED, PlanStepState.SKIPPED)
        val checkBox = JBCheckBox("步骤 $number", step.state == PlanStepState.APPROVED).apply {
            isOpaque = false
            isEnabled = selectable && !superseded
            addActionListener {
                val selected = isSelected
                flushEdits()
                service.approve(step.id, selected)
            }
        }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(checkBox, BorderLayout.WEST)
            add(JBLabel(inlinePlanStepStatus(step)).apply {
                font = JBFont.small().asBold()
                foreground = inlinePlanStepColor(step.state)
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val editor = JBTextArea(step.text).apply {
            lineWrap = true
            wrapStyleWord = true
            rows = step.text.lineSequence().count().coerceIn(1, 4)
            isEditable = selectable && !superseded
            background = OmniCodeUiPalette.canvas
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(OmniCodeUiPalette.border),
                JBUI.Borders.empty(5),
            )
            toolTipText = boundedTooltipHtml(step.text)
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(event: FocusEvent) {
                    service.updateStepText(step.id, text)
                }
            })
        }
        editors[step.id] = editor
        add(JBScrollPane(editor).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)
    }

    private fun flushEdits() {
        val pending = editors.map { (stepId, editor) -> stepId to editor.text }
        pending.forEach { (stepId, text) -> service.updateStepText(stepId, text) }
    }

    private fun approveAndExecute(action: PlanReviewAction, policy: PlanExecutionPolicy) {
        flushEdits()
        if (!service.applyReviewAction(action)) {
            actions.showMessage("计划没有可执行的已选步骤。", isError = true)
            return
        }
        val request = service.requestExecution(policy)
        if (request == null) {
            actions.showMessage("计划刚刚发生变化，请重新确认当前步骤。", isError = true)
            return
        }
        actions.execute(request)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

internal fun inlinePlanSummary(board: PlanBoard): String {
    val mode = if (board.sourceMode == AgentMode.CLAUDE_PLAN) "Claude Plan · 只读探索完成" else "Plan · 规划完成"
    val decision = when (board.effectiveReviewDecision) {
        PlanReviewDecision.PENDING -> "等待你的选择"
        PlanReviewDecision.CONTINUE_PLANNING -> "继续规划"
        PlanReviewDecision.APPROVED_MANUAL -> "每步确认"
        PlanReviewDecision.APPROVED_AUTO -> "Agent 自动执行"
        PlanReviewDecision.REJECTED -> "当前版本未批准"
    }
    return "$mode · $decision · ${board.completedCount}/${board.steps.size} 已完成；未批准前不会修改文件"
}

internal fun inlinePlanStepStatus(step: PlanStep): String = when (step.state) {
    PlanStepState.DRAFT -> "待选择"
    PlanStepState.APPROVED -> "已选择"
    PlanStepState.SKIPPED -> "已跳过"
    PlanStepState.RUNNING -> "执行中"
    PlanStepState.COMPLETED -> "已完成"
    PlanStepState.FAILED -> "失败 · 可重试"
    PlanStepState.PAUSED -> "已暂停"
}

private fun inlinePlanStepColor(state: PlanStepState) = when (state) {
    PlanStepState.COMPLETED -> OmniCodeUiPalette.success
    PlanStepState.FAILED -> OmniCodeUiPalette.error
    PlanStepState.RUNNING,
    PlanStepState.APPROVED,
    -> OmniCodeUiPalette.accent
    PlanStepState.PAUSED -> OmniCodeUiPalette.warning
    PlanStepState.DRAFT,
    PlanStepState.SKIPPED,
    -> OmniCodeUiPalette.secondary
}
