package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.plan.PlanBoard
import dev.omnicode.plan.PlanBoardService
import dev.omnicode.plan.PlanExecutionPolicy
import dev.omnicode.plan.PlanExecutionRequest
import dev.omnicode.plan.PlanReviewAction
import dev.omnicode.plan.PlanReviewDecision
import dev.omnicode.plan.PlanStep
import dev.omnicode.plan.PlanStepState
import java.awt.BorderLayout
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

internal interface PlanBoardActions {
    /** Legacy automatic execution entry point retained for host compatibility. */
    fun executeApprovedSteps()

    /**
     * Hosts should override this method to honor MANUAL_STEP_CONFIRMATION as a single-step run.
     * The safe default never sends a manual request through the legacy automatic path.
     */
    fun executeApprovedSteps(request: PlanExecutionRequest) {
        if (request.policy == PlanExecutionPolicy.AUTO_AGENT) executeApprovedSteps()
    }

    fun pauseExecution()
    fun continuePlanning(board: PlanBoard)
    fun returnToChat()
}

internal class PlanBoardPanel(
    private val service: PlanBoardService,
    private val actions: PlanBoardActions,
) : JPanel(BorderLayout()), Disposable {
    private val content = ViewportWidthPanel()
    private val title = JBLabel("Plan → Agent 看板").apply {
        font = JBFont.h2().asBold()
        toolTipText = boundedTooltipHtml(text)
    }
    private val summary = JBLabel("先在 Plan 或 Claude Plan 模式生成计划。").apply {
        toolTipText = boundedTooltipHtml(text)
    }
    private val projectScope = JBLabel("当前项目 · ${service.projectDisplayName}").apply {
        foreground = OmniCodeUiPalette.accent
        font = JBFont.small().asBold()
    }
    private val manualExecutionButton = JButton("批准并逐步确认")
    private val automaticExecutionButton = JButton("批准并切换 Agent 自动执行")
    private val pauseButton = JButton("暂停")
    private val approveAllButton = JButton("全部批准")
    private val continuePlanningButton = JButton("继续规划")
    private val rejectButton = JButton("拒绝 · 保持规划")
    private val chatButton = JButton("返回聊天")
    private val stepEditors = linkedMapOf<String, JBTextArea>()
    @Volatile
    private var disposed = false

    init {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        border = JBUI.Borders.empty(10)
        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(18)
        }, BorderLayout.CENTER)
        add(buildActions(), BorderLayout.SOUTH)

        manualExecutionButton.addActionListener {
            approveAndRequestExecution(PlanReviewAction.APPROVE_MANUAL, PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION)
        }
        automaticExecutionButton.addActionListener {
            approveAndRequestExecution(PlanReviewAction.APPROVE_AUTO, PlanExecutionPolicy.AUTO_AGENT)
        }
        pauseButton.addActionListener {
            flushEditedSteps()
            actions.pauseExecution()
        }
        approveAllButton.addActionListener {
            flushEditedSteps()
            service.approveAll()
        }
        continuePlanningButton.addActionListener {
            flushEditedSteps()
            if (service.applyReviewAction(PlanReviewAction.CONTINUE_PLANNING)) {
                service.snapshot()?.let(actions::continuePlanning)
            }
        }
        rejectButton.addActionListener {
            flushEditedSteps()
            service.applyReviewAction(PlanReviewAction.REJECT_AND_KEEP_PLANNING)
        }
        chatButton.addActionListener {
            flushEditedSteps()
            actions.returnToChat()
        }
        service.addListener(this) { board ->
            if (disposed) return@addListener
            val refresh = Runnable { if (!disposed) render(board) }
            if (SwingUtilities.isEventDispatchThread()) refresh.run()
            else ApplicationManager.getApplication().invokeLater(refresh)
        }
        render(service.snapshot())
    }

    override fun dispose() {
        disposed = true
    }

    internal fun refresh() = render(service.snapshot())

    private fun buildHeader(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 10, 2)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(title.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(3)))
            add(projectScope.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(3)))
            add(summary.apply {
                alignmentX = LEFT_ALIGNMENT
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
            })
        }, BorderLayout.CENTER)
    }

    private fun buildActions(): JComponent = WrappingActionPanel(
        alignment = FlowLayout.RIGHT,
        horizontalGap = JBUI.scale(6),
        verticalGap = JBUI.scale(8),
    ).apply {
        isOpaque = false
        border = JBUI.Borders.customLine(OmniCodeUiPalette.border, 1, 0, 0, 0)
        add(chatButton)
        add(rejectButton)
        add(continuePlanningButton)
        add(approveAllButton)
        add(pauseButton)
        add(manualExecutionButton)
        add(automaticExecutionButton)
    }

    private fun render(board: PlanBoard?) {
        stepEditors.clear()
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        if (board == null) {
            title.text = "Plan → Agent 看板"
            summary.text = "这个看板只保存当前项目的规划；生成后可作为一个版本逐步处理。"
            content.add(emptyState())
            manualExecutionButton.isEnabled = false
            automaticExecutionButton.isEnabled = false
            pauseButton.isEnabled = false
            approveAllButton.isEnabled = false
            continuePlanningButton.isEnabled = false
            rejectButton.isEnabled = false
        } else {
            title.text = board.title
            summary.text = "${modeLabel(board)} · 版本 ${board.revision} · ${reviewSummary(board)} · " +
                "${board.completedCount}/${board.steps.size} 已完成"
            content.add(reviewStateCard(board))
            content.add(Box.createVerticalStrut(JBUI.scale(10)))
            board.steps.forEachIndexed { index, step ->
                content.add(stepCard(index + 1, step))
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
            val mayDecide = board.approvedCount > 0 && !board.hasRunningStep
            manualExecutionButton.isEnabled = mayDecide
            automaticExecutionButton.isEnabled = mayDecide
            manualExecutionButton.text = if (board.executionPolicy == PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION) {
                "确认执行下一步"
            } else {
                "批准并逐步确认"
            }
            pauseButton.isEnabled = board.hasRunningStep
            approveAllButton.isEnabled = !board.hasRunningStep && board.steps.any {
                it.state == PlanStepState.DRAFT || it.state == PlanStepState.FAILED || it.state == PlanStepState.PAUSED
            }
            continuePlanningButton.isEnabled = !board.hasRunningStep
            rejectButton.isEnabled = !board.hasRunningStep
        }
        title.toolTipText = boundedTooltipHtml(title.text)
        summary.toolTipText = boundedTooltipHtml(summary.text)
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
        revalidate()
        repaint()
    }

    private fun emptyState(): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 12,
    ).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(20)
        val message = "计划完成后会在这里显示可编辑步骤；可只批准部分步骤，再交给 Agent 执行。"
        add(JBLabel(message).apply { toolTipText = boundedTooltipHtml(message) }, BorderLayout.CENTER)
    }

    private fun reviewStateCard(board: PlanBoard): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = when (board.effectiveReviewDecision) {
            PlanReviewDecision.APPROVED_MANUAL,
            PlanReviewDecision.APPROVED_AUTO,
            -> OmniCodeUiPalette.success
            PlanReviewDecision.REJECTED -> OmniCodeUiPalette.warning
            else -> OmniCodeUiPalette.border
        },
        radius = 10,
    ).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10)
        val heading = JBLabel(reviewHeading(board)).apply {
            alignmentX = LEFT_ALIGNMENT
            font = JBFont.small().asBold()
            foreground = when (board.effectiveReviewDecision) {
                PlanReviewDecision.APPROVED_MANUAL,
                PlanReviewDecision.APPROVED_AUTO,
                -> OmniCodeUiPalette.success
                PlanReviewDecision.REJECTED -> OmniCodeUiPalette.warning
                else -> OmniCodeUiPalette.secondary
            }
        }
        val detail = JBTextArea(reviewDetail(board)).apply {
            alignmentX = LEFT_ALIGNMENT
            font = JBFont.small()
            foreground = OmniCodeUiPalette.secondary
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            border = JBUI.Borders.empty()
            toolTipText = boundedTooltipHtml(text)
        }
        add(heading)
        add(Box.createVerticalStrut(JBUI.scale(3)))
        add(detail)
    }

    private fun stepCard(number: Int, step: PlanStep): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = if (step.state == PlanStepState.RUNNING) OmniCodeUiPalette.accent else OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = BorderLayout(JBUI.scale(8), JBUI.scale(6))
        border = JBUI.Borders.empty(10)
        val approved = JBCheckBox("步骤 $number", step.state == PlanStepState.APPROVED).apply {
            isOpaque = false
            isEnabled = step.state !in setOf(PlanStepState.RUNNING, PlanStepState.COMPLETED, PlanStepState.SKIPPED)
            addActionListener {
                val selected = isSelected
                flushEditedSteps()
                service.approve(step.id, selected)
            }
        }
        val status = JBLabel(stepStatusLabel(step)).apply {
            font = JBFont.small().asBold()
            foreground = stepStatusColor(step.state)
        }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(approved, BorderLayout.WEST)
            add(status, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val editor = JBTextArea(step.text).apply {
            lineWrap = true
            wrapStyleWord = true
            rows = step.text.lineSequence().count().coerceIn(2, 7)
            isEditable = step.state !in setOf(PlanStepState.RUNNING, PlanStepState.COMPLETED)
            background = OmniCodeUiPalette.canvas
            toolTipText = boundedTooltipHtml(step.text)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(OmniCodeUiPalette.border),
                JBUI.Borders.empty(6),
            )
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(event: FocusEvent) {
                    service.updateStepText(step.id, text)
                }
            })
        }
        stepEditors[step.id] = editor
        add(JBScrollPane(editor).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        add(WrappingActionPanel(
            alignment = FlowLayout.RIGHT,
            horizontalGap = JBUI.scale(5),
            verticalGap = 0,
        ).apply {
            isOpaque = false
            when (step.state) {
                PlanStepState.DRAFT,
                PlanStepState.APPROVED,
                -> add(JButton("跳过").apply {
                    addActionListener {
                        flushEditedSteps()
                        service.skip(step.id)
                    }
                })
                PlanStepState.SKIPPED -> add(JButton("恢复").apply {
                    addActionListener {
                        flushEditedSteps()
                        service.restore(step.id)
                    }
                })
                PlanStepState.FAILED,
                PlanStepState.PAUSED,
                -> add(JButton("重试").apply {
                    addActionListener {
                        flushEditedSteps()
                        if (service.retry(step.id)) requestCurrentExecution()
                    }
                })
                PlanStepState.RUNNING,
                PlanStepState.COMPLETED,
                -> Unit
            }
        }, BorderLayout.SOUTH)
    }

    private fun stepStatusLabel(step: PlanStep): String = when (step.state) {
        PlanStepState.DRAFT -> "待批准"
        PlanStepState.APPROVED -> "已批准"
        PlanStepState.SKIPPED -> "已跳过"
        PlanStepState.RUNNING -> "执行中 · 第 ${step.attempts} 次"
        PlanStepState.COMPLETED -> "已完成"
        PlanStepState.FAILED -> "失败 · 可重试"
        PlanStepState.PAUSED -> "已暂停"
    }

    private fun stepStatusColor(state: PlanStepState) = when (state) {
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

    private fun modeLabel(board: PlanBoard): String = when (board.sourceMode) {
        dev.omnicode.agent.AgentMode.CLAUDE_PLAN -> "Claude Plan"
        else -> "Plan 看板"
    }

    private fun approveAndRequestExecution(action: PlanReviewAction, policy: PlanExecutionPolicy) {
        flushEditedSteps()
        if (!service.applyReviewAction(action)) return
        val request = service.requestExecution(policy) ?: return
        actions.executeApprovedSteps(request)
    }

    private fun requestCurrentExecution() {
        val policy = service.snapshot()?.executionPolicy ?: PlanExecutionPolicy.NONE
        val request = service.requestExecution(policy) ?: return
        actions.executeApprovedSteps(request)
    }

    /** Commits visible editor contents before any review or execution decision is recorded. */
    private fun flushEditedSteps() {
        // Updating the service re-renders the panel synchronously on the EDT, so snapshot the
        // editor values first instead of iterating a map that the listener is about to replace.
        val edits = stepEditors.map { (stepId, editor) -> stepId to editor.text }
        edits.forEach { (stepId, text) -> service.updateStepText(stepId, text) }
    }

    private fun reviewSummary(board: PlanBoard): String = when (board.effectiveReviewDecision) {
        PlanReviewDecision.PENDING -> "等待审阅 · ${board.approvedCount} 步已选择"
        PlanReviewDecision.CONTINUE_PLANNING -> "继续规划"
        PlanReviewDecision.APPROVED_MANUAL -> "每步确认"
        PlanReviewDecision.APPROVED_AUTO -> "Agent 自动执行"
        PlanReviewDecision.REJECTED -> "已拒绝"
    }

    private fun reviewHeading(board: PlanBoard): String = when (board.effectiveReviewDecision) {
        PlanReviewDecision.PENDING -> "尚未批准"
        PlanReviewDecision.CONTINUE_PLANNING -> "继续规划"
        PlanReviewDecision.APPROVED_MANUAL -> "计划已批准 · 每步确认"
        PlanReviewDecision.APPROVED_AUTO -> "计划已批准 · 自动执行"
        PlanReviewDecision.REJECTED -> "计划已拒绝"
    }

    private fun reviewDetail(board: PlanBoard): String = when (board.effectiveReviewDecision) {
        PlanReviewDecision.PENDING -> "计划仍可编辑；未明确批准前不会执行任何步骤。"
        PlanReviewDecision.CONTINUE_PLANNING -> "返回 Claude Plan / Plan 补充探索并修订；当前版本不会执行。"
        PlanReviewDecision.APPROVED_MANUAL -> "仅执行你每次明确确认的下一步，不会连续运行。"
        PlanReviewDecision.APPROVED_AUTO -> "切换到 Agent，并按已批准范围连续执行。"
        PlanReviewDecision.REJECTED -> "保留计划供编辑或参考，但禁止执行当前版本。"
    }
}
