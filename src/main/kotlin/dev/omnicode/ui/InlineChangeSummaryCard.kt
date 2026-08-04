package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.review.TaskChangedFile
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * A compact, Codex-style result summary kept inside the conversation.  The full
 * review center remains the source of truth; this card is only the fast path
 * from "the agent edited files" to the exact file/range the user wants to see.
 */
internal class InlineChangeSummaryCard(
    files: List<TaskChangedFile>,
    private val onOpenFile: (ToolFileReference) -> Unit,
    private val onReview: () -> Unit,
) : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.timelineCard,
    outlineColor = OmniCodeUiPalette.timelineBorder,
    radius = 11,
) {
    private val files = files.sortedBy(TaskChangedFile::relativePath)

    init {
        layout = BorderLayout(0, JBUI.scale(8))
        border = JBUI.Borders.empty(10, 12)
        add(header(), BorderLayout.NORTH)
        add(fileList(), BorderLayout.CENTER)
        add(actions(), BorderLayout.SOUTH)
    }

    private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(JBLabel(AllIcons.Actions.Edit), BorderLayout.WEST)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("已编辑 ${files.size} 个文件").apply {
                font = JBFont.label().asBold()
            })
            add(JBLabel(changeSummary()).apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
            })
        }, BorderLayout.CENTER)
    }

    private fun fileList(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        files.take(MAX_VISIBLE_FILES).forEachIndexed { index, file ->
            add(fileButton(file))
            if (index < minOf(files.size, MAX_VISIBLE_FILES) - 1) {
                add(Box.createVerticalStrut(JBUI.scale(2)))
            }
        }
        if (files.size > MAX_VISIBLE_FILES) {
            add(JBLabel("再显示 ${files.size - MAX_VISIBLE_FILES} 个文件…").apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                border = JBUI.Borders.emptyTop(4)
            })
        }
    }

    private fun fileButton(file: TaskChangedFile): JButton {
        val reference = fileReference(file)
        return JButton(referenceLabel(reference)).apply {
            icon = AllIcons.FileTypes.Text
            horizontalAlignment = SwingConstants.LEFT
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = true
            foreground = OmniCodeUiPalette.timelineLink
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "在 IDE 中打开 ${referenceLabel(reference)}"
            accessibleContext.accessibleName = "打开 ${referenceLabel(reference)}"
            addActionListener { onOpenFile(reference) }
        }
    }

    private fun actions(): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(flatButton("审阅变更", "逐文件或逐块保留、回退本次修改").apply {
            icon = AllIcons.Actions.Diff
            addActionListener { onReview() }
        })
    }

    private fun fileReference(file: TaskChangedFile): ToolFileReference {
        val hunk = file.hunks.firstOrNull()
        val start = hunk?.afterStartLine?.takeIf { it > 0 } ?: hunk?.beforeStartLine?.takeIf { it > 0 }
        val count = hunk?.afterLineCount?.takeIf { it > 0 } ?: hunk?.beforeLineCount?.takeIf { it > 0 }
        val end = if (start != null && count != null) start + count - 1 else null
        return ToolFileReference(file.relativePath, start, end)
    }

    private fun referenceLabel(reference: ToolFileReference): String = buildString {
        append(reference.path)
        reference.startLine?.let {
            append(" ").append(it)
            reference.endLine?.takeIf { end -> end >= it }?.let { end -> append("-").append(end) }
        }
    }

    private fun changeSummary(): String {
        val added = files.sumOf { file -> file.hunks.sumOf { it.afterLineCount } }
        val removed = files.sumOf { file -> file.hunks.sumOf { it.beforeLineCount } }
        return "+$added  -$removed · 点击文件名跳转，或打开审阅中心逐块处理"
    }

    private companion object {
        const val MAX_VISIBLE_FILES = 8
    }
}
