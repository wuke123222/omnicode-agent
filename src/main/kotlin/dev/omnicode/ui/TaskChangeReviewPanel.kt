package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.review.TaskChangeDecision
import dev.omnicode.review.TaskChangeHunk
import dev.omnicode.review.TaskChangeReviewService
import dev.omnicode.review.TaskChangedFile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class TaskChangeReviewPanel(
    private val reviewService: TaskChangeReviewService,
    private val preferredWorkflowId: () -> String?,
    private val canModify: () -> Boolean,
    private val beginMutation: () -> Boolean,
    private val endMutation: () -> Unit,
    private val returnToChat: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val content = ViewportWidthPanel()
    private val workflowSelector = ComboBox<String>()
    private val status = JBLabel("")
    private val rollbackTaskButton = JButton("回退全部已记录修改")
    private val actionRunning = AtomicBoolean(false)
    private val mutationGateHeld = AtomicBoolean(false)
    private val mutationButtons = mutableListOf<JButton>()
    @Volatile
    private var disposed = false
    private var selectedWorkflowId: String? = null

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
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(8)).apply {
            isOpaque = false
            add(JButton("返回聊天").apply { addActionListener { returnToChat() } })
            add(rollbackTaskButton)
        }, BorderLayout.SOUTH)
        workflowSelector.addActionListener {
            selectedWorkflowId = workflowSelector.selectedItem as? String
            workflowSelector.toolTipText = boundedTooltipHtml(selectedWorkflowId.orEmpty())
            renderSelected()
        }
        rollbackTaskButton.addActionListener { rollbackWholeTask() }
        refresh()
    }

    override fun dispose() {
        disposed = true
        scope.cancel()
    }

    internal fun refresh(workflowId: String? = preferredWorkflowId()) {
        val ids = reviewService.workflowIds()
        val target = workflowId?.takeIf(ids::contains) ?: selectedWorkflowId?.takeIf(ids::contains) ?: ids.firstOrNull()
        workflowSelector.removeAllItems()
        ids.forEach(workflowSelector::addItem)
        selectedWorkflowId = target
        workflowSelector.selectedItem = target
        renderSelected()
    }

    private fun buildHeader(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 10, 2)
        add(JBLabel("任务级变更审阅中心").apply {
            font = JBFont.h2().asBold()
            alignmentX = LEFT_ALIGNMENT
        })
        val scopeDescription = "覆盖 apply_patch / apply_change；命令与 MCP 产物不纳入一键回退。外部修改冲突时失败关闭。"
        add(JBLabel(scopeDescription).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            alignmentX = LEFT_ALIGNMENT
            toolTipText = boundedTooltipHtml(scopeDescription)
        })
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(5), JBUI.scale(4)).apply {
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel("任务"))
            add(workflowSelector.apply { preferredSize = Dimension(JBUI.scale(220), preferredSize.height) })
            add(JButton("刷新").apply { addActionListener { refresh() } })
        })
        add(status.apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            border = JBUI.Borders.emptyTop(5)
        })
    }

    private fun renderSelected() {
        val workflowId = selectedWorkflowId
        val review = workflowId?.let(reviewService::review)
        content.removeAll()
        mutationButtons.clear()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        if (review == null || review.files.isEmpty()) {
            status.text = "当前会话没有可审阅的 Agent 文件修改。"
            rollbackTaskButton.isEnabled = false
            val message = "Agent 通过 apply_patch / apply_change 修改文件后，会在这里生成任务级差异账本。"
            content.add(JBLabel(message).apply { toolTipText = boundedTooltipHtml(message) })
        } else {
            val hunks = review.files.sumOf { it.hunks.size }
            status.text = "${review.files.size} 个文件 · $hunks 个变更块 · 审阅状态仅保存在当前 IDE 会话"
            rollbackTaskButton.isEnabled = canModify() && !actionRunning.get()
            review.files.forEach { file ->
                content.add(fileCard(workflowId, file))
                content.add(Box.createVerticalStrut(JBUI.scale(9)))
            }
        }
        status.toolTipText = boundedTooltipHtml(status.text)
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    private fun fileCard(workflowId: String, file: TaskChangedFile): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = if (file.decision == TaskChangeDecision.ROLLED_BACK) OmniCodeUiPalette.warning else OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = BorderLayout(JBUI.scale(8), JBUI.scale(6))
        border = JBUI.Borders.empty(10)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(file.relativePath).apply {
                font = JBFont.label().asBold()
                toolTipText = boundedTooltipHtml(file.relativePath)
            }, BorderLayout.CENTER)
            add(JBLabel(decisionLabel(file.decision)).apply {
                foreground = decisionColor(file.decision)
                font = JBFont.small().asBold()
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            file.hunks.forEachIndexed { index, hunk ->
                add(hunkCard(workflowId, file.relativePath, index + 1, hunk))
                if (index < file.hunks.lastIndex) add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(5), JBUI.scale(4)).apply {
            isOpaque = false
            val enabled = canModify() && !actionRunning.get()
            add(JButton("保留文件").apply {
                isEnabled = enabled
                mutationButtons += this
                addActionListener { runReviewAction("正在保留文件…") { reviewService.keepFile(workflowId, file.relativePath) } }
            })
            add(JButton("回退文件").apply {
                isEnabled = enabled
                mutationButtons += this
                addActionListener { runReviewAction("正在回退文件…") { reviewService.rollbackFile(workflowId, file.relativePath) } }
            })
        }, BorderLayout.SOUTH)
    }

    private fun hunkCard(
        workflowId: String,
        path: String,
        number: Int,
        hunk: TaskChangeHunk,
    ): JComponent {
        val preview = hunkPreview(hunk)
        return JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(4))).apply {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(OmniCodeUiPalette.border),
            JBUI.Borders.empty(7),
        )
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("变更块 $number · -${hunk.beforeLineCount} / +${hunk.afterLineCount}").apply {
                font = JBFont.small().asBold()
            }, BorderLayout.WEST)
            add(JBLabel(decisionLabel(hunk.decision)).apply {
                foreground = decisionColor(hunk.decision)
                font = JBFont.small()
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(JBTextArea(preview).apply {
            isEditable = false
            lineWrap = false
            rows = preview.lineSequence().count().coerceIn(3, 12)
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            background = OmniCodeUiPalette.surface
            toolTipText = boundedTooltipHtml(preview)
        }).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4)).apply {
            isOpaque = false
            val enabled = canModify() && !actionRunning.get()
            add(JButton("保留此块").apply {
                isEnabled = enabled
                mutationButtons += this
                addActionListener { runReviewAction("正在保留变更块…") { reviewService.keepHunk(workflowId, path, hunk.id) } }
            })
            add(JButton("回退此块").apply {
                isEnabled = enabled
                mutationButtons += this
                addActionListener { runReviewAction("正在回退变更块…") { reviewService.rollbackHunk(workflowId, path, hunk.id) } }
            })
        }, BorderLayout.SOUTH)
        }
    }

    private fun rollbackWholeTask() {
        val workflowId = selectedWorkflowId ?: return
        if (!canModify()) return
        if (Messages.showYesNoDialog(
                "将回退此任务记录的全部文件修改。若文件已被外部修改，操作会整体拒绝且不会写入。是否继续？",
                "回退全部已记录修改",
                "撤销全部",
                "取消",
                Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        runReviewAction("正在原子预检并回退全部修改…") { reviewService.rollbackTask(workflowId) }
    }

    private fun runReviewAction(message: String, action: () -> Any?) {
        if (!actionRunning.compareAndSet(false, true)) return
        if (!beginMutation()) {
            actionRunning.set(false)
            renderSelected()
            status.text = "Agent 正在运行或另一个审阅操作尚未结束，请稍后重试。"
            status.toolTipText = boundedTooltipHtml(status.text)
            status.foreground = OmniCodeUiPalette.warning
            return
        }
        mutationGateHeld.set(true)
        status.text = message
        status.toolTipText = boundedTooltipHtml(message)
        setMutationControlsEnabled(false)
        val job = scope.launch {
            val result = try {
                runCatching(action)
            } finally {
                releaseMutationGate()
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                renderSelected()
                val message = result.fold(
                    onSuccess = { "审阅操作已完成，文件哈希与审阅账本已同步。" },
                    onFailure = { it.message ?: "审阅操作失败" },
                )
                status.text = message
                status.toolTipText = boundedTooltipHtml(message)
                status.foreground = if (result.isSuccess) OmniCodeUiPalette.success else OmniCodeUiPalette.error
            }
        }
        job.invokeOnCompletion { releaseMutationGate() }
    }

    private fun releaseMutationGate() {
        if (mutationGateHeld.compareAndSet(true, false)) endMutation()
        actionRunning.set(false)
    }

    private fun setMutationControlsEnabled(enabled: Boolean) {
        rollbackTaskButton.isEnabled = enabled
        mutationButtons.forEach { it.isEnabled = enabled }
    }

    private fun hunkPreview(hunk: TaskChangeHunk): String {
        val output = StringBuilder(minOf(MAX_HUNK_PREVIEW_CHARS, 4_096))
        fun appendLines(prefix: String, value: String): Boolean {
            for (line in value.lineSequence()) {
                val remaining = MAX_HUNK_PREVIEW_CHARS - output.length
                if (remaining <= 0) return false
                val rendered = "$prefix$line\n"
                output.append(rendered, 0, minOf(rendered.length, remaining))
                if (rendered.length > remaining) return false
            }
            return true
        }
        val complete = appendLines("- ", hunk.beforeText) && appendLines("+ ", hunk.afterText)
        if (!complete && output.length + HUNK_TRUNCATED_MARKER.length <= MAX_HUNK_PREVIEW_CHARS) {
            output.append(HUNK_TRUNCATED_MARKER)
        }
        return output.toString()
    }

    private fun decisionLabel(value: TaskChangeDecision): String = when (value) {
        TaskChangeDecision.PENDING -> "待审阅"
        TaskChangeDecision.KEPT -> "已保留"
        TaskChangeDecision.ROLLED_BACK -> "已回退"
        TaskChangeDecision.MIXED -> "部分保留"
    }

    private fun decisionColor(value: TaskChangeDecision) = when (value) {
        TaskChangeDecision.KEPT -> OmniCodeUiPalette.success
        TaskChangeDecision.ROLLED_BACK -> OmniCodeUiPalette.warning
        TaskChangeDecision.MIXED -> OmniCodeUiPalette.accent
        TaskChangeDecision.PENDING -> OmniCodeUiPalette.secondary
    }

    private companion object {
        const val MAX_HUNK_PREVIEW_CHARS = 12_000
        const val HUNK_TRUNCATED_MARKER = "\n… diff preview truncated …"
    }
}
