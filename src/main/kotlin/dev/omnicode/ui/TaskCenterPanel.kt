package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.OmniCodeProjectService
import dev.omnicode.service.UnifiedTaskEntry
import dev.omnicode.service.UnifiedTaskStatus
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

internal interface TaskCenterActions {
    fun continueTask(task: UnifiedTaskEntry)
    fun retryTask(task: UnifiedTaskEntry)
    fun copyTask(task: UnifiedTaskEntry)
    fun showReliability(task: UnifiedTaskEntry)
    fun restoreCheckpoint(task: UnifiedTaskEntry)
    fun returnToChat()
}

internal class TaskCenterPanel(
    private val service: OmniCodeProjectService,
    private val actions: TaskCenterActions,
) : JPanel(BorderLayout()), Disposable {
    private val content = ViewportWidthPanel()
    private val status = JBLabel("正在读取任务…")
    private val refreshTimer = Timer(TASK_CENTER_REFRESH_MILLIS) {
        if (taskCenterShouldPoll(isShowing, disposed)) refresh(showLoading = false)
    }.apply {
        isRepeats = true
        initialDelay = TASK_CENTER_REFRESH_MILLIS
    }
    private var refreshInFlight = false
    private var renderedTasks: List<UnifiedTaskEntry>? = null
    private var renderedActionsBlocked: Boolean? = null
    @Volatile
    private var disposed = false

    init {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        border = JBUI.Borders.empty(10)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 2, 10, 2)
            add(JBLabel("统一任务与历史").apply { font = JBFont.h2().asBold() }, BorderLayout.WEST)
            add(JButton("刷新").apply { addActionListener { refresh() } }, BorderLayout.EAST)
            add(status.apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                border = JBUI.Borders.emptyTop(4)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(18)
        }, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(8)).apply {
            isOpaque = false
            add(JButton("返回聊天").apply { addActionListener { actions.returnToChat() } })
        }, BorderLayout.SOUTH)
        addHierarchyListener { event ->
            val showingChanged = event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
            if (!showingChanged || disposed) return@addHierarchyListener
            if (isShowing) {
                refresh(showLoading = false)
                refreshTimer.start()
            } else {
                refreshTimer.stop()
            }
        }
        refresh()
    }

    override fun dispose() {
        disposed = true
        refreshTimer.stop()
    }

    internal fun refresh(showLoading: Boolean = true) {
        if (disposed || refreshInFlight) return
        refreshInFlight = true
        if (showLoading) {
            status.text = "正在读取任务…"
            status.toolTipText = boundedTooltipHtml(status.text)
        }
        service.listUnifiedTasks { tasks ->
            refreshInFlight = false
            if (disposed) return@listUnifiedTasks
            val actionsBlocked = service.isRunning()
            if (!showLoading && tasks == renderedTasks && actionsBlocked == renderedActionsBlocked) {
                return@listUnifiedTasks
            }
            render(tasks, actionsBlocked)
        }
    }

    private fun render(tasks: List<UnifiedTaskEntry>, actionsBlocked: Boolean) {
        renderedTasks = tasks.toList()
        renderedActionsBlocked = actionsBlocked
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        status.text = if (tasks.isEmpty()) "暂无任务。" else "${tasks.size} 个任务 · 运行、恢复、失败和完成记录统一展示"
        status.toolTipText = boundedTooltipHtml(status.text)
        if (tasks.isEmpty()) {
            content.add(JBLabel("发送一个任务后，这里会显示运行状态和恢复入口。"))
        } else {
            tasks.forEach { task ->
                content.add(taskCard(task, actionsBlocked))
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    private fun taskCard(task: UnifiedTaskEntry, actionsBlocked: Boolean): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = if (task.status == UnifiedTaskStatus.RUNNING) OmniCodeUiPalette.accent else OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = BorderLayout(JBUI.scale(8), JBUI.scale(5))
        border = JBUI.Borders.empty(10)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(task.title.take(140)).apply {
                font = JBFont.label().asBold()
                toolTipText = boundedTooltipHtml(task.title)
            }, BorderLayout.CENTER)
            add(JBLabel(taskStatusLabel(task.status)).apply {
                foreground = taskStatusColor(task.status)
                font = JBFont.small().asBold()
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        val metadata = taskMeta(task)
        add(JBLabel(metadata).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            toolTipText = boundedTooltipHtml(metadata)
        }, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(5), JBUI.scale(4)).apply {
            isOpaque = false
            if (task.canContinue) add(JButton("继续").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.continueTask(task) }
            })
            if (task.canRetry) add(JButton(
                if (task.requiredImageAttachments > 0) "补图后重试" else "重试",
            ).apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.retryTask(task) }
            })
            add(JButton("复制任务").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.copyTask(task) }
            })
            if (task.workflowId != null) add(JButton("可靠性").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.showReliability(task) }
            })
            if (task.workflowId != null || task.conversationId != null) {
                add(JButton("回到检查点").apply {
                    applyTaskActionAvailability(actionsBlocked)
                    addActionListener { actions.restoreCheckpoint(task) }
                })
            }
        }, BorderLayout.SOUTH)
    }

    private fun JButton.applyTaskActionAvailability(blocked: Boolean) {
        if (!blocked) return
        isEnabled = false
        toolTipText = TASK_ACTIONS_RUNNING_TOOLTIP
        accessibleContext?.accessibleDescription = TASK_ACTIONS_RUNNING_TOOLTIP
    }

    private fun taskMeta(task: UnifiedTaskEntry): String = buildString {
        append(task.mode.name.replace('_', ' ')).append(" · ").append(task.strategy.name)
        append(" · ").append(TIME_FORMAT.format(task.updatedAt.atZone(ZoneId.systemDefault())))
        if (task.iteration > 0) append(" · 第 ").append(task.iteration).append(" 轮")
        val tokens = task.inputTokens + task.outputTokens
        if (tokens > 0) append(" · ").append(tokens).append(" tokens")
        task.pendingToolName?.let { append(" · 待确认 ").append(it) }
        if (task.requiredImageAttachments > 0) append(" · 需补 ").append(task.requiredImageAttachments).append(" 张图片")
    }

    private fun taskStatusLabel(value: UnifiedTaskStatus): String = when (value) {
        UnifiedTaskStatus.RUNNING -> "运行中"
        UnifiedTaskStatus.WAITING_FOR_APPROVAL -> "待批准"
        UnifiedTaskStatus.PAUSED -> "已暂停"
        UnifiedTaskStatus.RECOVERABLE -> "待恢复"
        UnifiedTaskStatus.FAILED -> "失败"
        UnifiedTaskStatus.COMPLETED -> "已完成"
        UnifiedTaskStatus.CANCELLED -> "已取消"
        UnifiedTaskStatus.BUDGET_EXHAUSTED -> "有限模式已暂停"
    }

    private fun taskStatusColor(value: UnifiedTaskStatus) = when (value) {
        UnifiedTaskStatus.RUNNING,
        UnifiedTaskStatus.WAITING_FOR_APPROVAL,
        -> OmniCodeUiPalette.accent
        UnifiedTaskStatus.COMPLETED -> OmniCodeUiPalette.success
        UnifiedTaskStatus.FAILED -> OmniCodeUiPalette.error
        UnifiedTaskStatus.PAUSED,
        UnifiedTaskStatus.BUDGET_EXHAUSTED,
        -> OmniCodeUiPalette.warning
        UnifiedTaskStatus.RECOVERABLE,
        UnifiedTaskStatus.CANCELLED,
        -> OmniCodeUiPalette.secondary
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}

internal const val TASK_CENTER_REFRESH_MILLIS: Int = 2_000
internal const val TASK_ACTIONS_RUNNING_TOOLTIP: String = "当前任务正在运行，完成后可继续、重试、复制或恢复检查点。"

internal fun taskCenterShouldPoll(isShowing: Boolean, disposed: Boolean): Boolean = isShowing && !disposed
