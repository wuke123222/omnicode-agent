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
    fun executeApprovedSteps()
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
    private val executeButton = JButton("执行已批准步骤")
    private val pauseButton = JButton("暂停")
    private val approveAllButton = JButton("全部批准")
    private val continuePlanningButton = JButton("继续规划")
    private val chatButton = JButton("返回聊天")
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

        executeButton.addActionListener { actions.executeApprovedSteps() }
        pauseButton.addActionListener { actions.pauseExecution() }
        approveAllButton.addActionListener { service.approveAll() }
        continuePlanningButton.addActionListener { service.snapshot()?.let(actions::continuePlanning) }
        chatButton.addActionListener { actions.returnToChat() }
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
        add(continuePlanningButton)
        add(approveAllButton)
        add(pauseButton)
        add(executeButton)
    }

    private fun render(board: PlanBoard?) {
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        if (board == null) {
            title.text = "Plan → Agent 看板"
            summary.text = "先在 Plan 看板或 Claude Plan 模式生成计划。"
            content.add(emptyState())
            executeButton.isEnabled = false
            pauseButton.isEnabled = false
            approveAllButton.isEnabled = false
            continuePlanningButton.isEnabled = false
        } else {
            title.text = board.title
            summary.text = "${modeLabel(board)} · ${board.completedCount}/${board.steps.size} 已完成 · ${board.approvedCount} 待执行"
            board.steps.forEachIndexed { index, step ->
                content.add(stepCard(index + 1, step))
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
            executeButton.isEnabled = board.approvedCount > 0 && !board.hasRunningStep
            pauseButton.isEnabled = board.hasRunningStep
            approveAllButton.isEnabled = !board.hasRunningStep && board.steps.any {
                it.state == PlanStepState.DRAFT || it.state == PlanStepState.FAILED || it.state == PlanStepState.PAUSED
            }
            continuePlanningButton.isEnabled = !board.hasRunningStep
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
            addActionListener { service.approve(step.id, isSelected) }
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
                -> add(JButton("跳过").apply { addActionListener { service.skip(step.id) } })
                PlanStepState.SKIPPED -> add(JButton("恢复").apply { addActionListener { service.restore(step.id) } })
                PlanStepState.FAILED,
                PlanStepState.PAUSED,
                -> add(JButton("重试").apply {
                    addActionListener {
                        if (service.retry(step.id)) actions.executeApprovedSteps()
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
}
