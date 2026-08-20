package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.review.TaskChangeHunk
import dev.omnicode.review.TaskChangedFile
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * A Codex-style result summary kept inside the conversation. Each file expands to a bounded,
 * line-colored diff so reviewing does not require leaving the chat; the review center stays the
 * place for keep/rollback decisions and unbounded diffs.
 */
internal class InlineChangeSummaryCard(
    files: List<TaskChangedFile>,
    private val onOpenFile: (ToolFileReference) -> Unit,
    private val onReview: () -> Unit,
    private val onCompare: ((TaskChangedFile) -> Unit)? = null,
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
            add(FileDiffRow(file))
            if (index < minOf(files.size, MAX_VISIBLE_FILES) - 1) {
                add(Box.createVerticalStrut(JBUI.scale(2)))
            }
        }
        if (files.size > MAX_VISIBLE_FILES) {
            add(JBLabel("其余 ${files.size - MAX_VISIBLE_FILES} 个文件请在审阅中心查看…").apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                border = JBUI.Borders.emptyTop(4)
            })
        }
    }

    /** One file: toggle row plus a lazily built, bounded inline diff. */
    private inner class FileDiffRow(private val file: TaskChangedFile) : JPanel() {
        private val toggle = JButton("▸").apply {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            foreground = OmniCodeUiPalette.timelineLink
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "展开/收起行级变更"
            accessibleContext.accessibleName = "展开 ${file.relativePath} 的变更"
            margin = JBUI.emptyInsets()
        }
        private var diffView: JComponent? = null
        private var expanded = false

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(toggle, BorderLayout.WEST)
                add(fileButton(file), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(JBLabel(fileStat(file)).apply {
                        foreground = OmniCodeUiPalette.secondary
                        font = JBFont.small()
                    })
                    onCompare?.let { compare ->
                        add(JButton(AllIcons.Actions.Diff).apply {
                            isOpaque = false
                            isContentAreaFilled = false
                            isBorderPainted = false
                            isFocusPainted = false
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            toolTipText = "在 IDE 差异视图中对比修改前后"
                            accessibleContext.accessibleName = "在 IDE 中对比 ${file.relativePath}"
                            margin = JBUI.emptyInsets()
                            addActionListener { compare(file) }
                        })
                    }
                }, BorderLayout.EAST)
            })
            toggle.addActionListener { setExpanded(!expanded) }
        }

        private fun setExpanded(value: Boolean) {
            expanded = value
            toggle.text = if (value) "▾" else "▸"
            if (value && diffView == null) {
                diffView = inlineDiffView(file).also(::add)
            }
            diffView?.isVisible = value
            revalidate()
            repaint()
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

    private fun fileStat(file: TaskChangedFile): String {
        val added = file.hunks.sumOf { it.afterLineCount }
        val removed = file.hunks.sumOf { it.beforeLineCount }
        return "+$added −$removed"
    }

    private fun changeSummary(): String {
        if (files.size == 1) return "点击 ▸ 直接查看行级变更，文件名跳转 IDE"
        val added = files.sumOf { file -> file.hunks.sumOf { it.afterLineCount } }
        val removed = files.sumOf { file -> file.hunks.sumOf { it.beforeLineCount } }
        return "+$added  −$removed · 点击 ▸ 直接查看行级变更，文件名跳转 IDE"
    }

    private companion object {
        const val MAX_VISIBLE_FILES = 8
    }
}

/** Bounded, line-colored diff for one file, rendered directly inside the conversation. */
internal fun inlineDiffView(file: TaskChangedFile): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = JComponent.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(4, 24, 4, 4)
    var renderedLines = 0
    file.hunks.forEachIndexed { index, hunk ->
        if (renderedLines >= MAX_INLINE_DIFF_LINES) return@forEachIndexed
        add(JBLabel(hunkTitle(index, hunk)).apply {
            foreground = OmniCodeUiPalette.timelineMuted
            font = JBFont.small()
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(if (index == 0) 0 else 6, 0, 2, 0)
        })
        val budget = MAX_INLINE_DIFF_LINES - renderedLines
        val pane = hunkDiffPane(hunk, budget)
        renderedLines += pane.second
        add(pane.first.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
    }
    val totalLines = file.hunks.sumOf { hunk ->
        hunk.beforeText.lineCountOrZero() + hunk.afterText.lineCountOrZero()
    }
    if (totalLines > MAX_INLINE_DIFF_LINES) {
        add(JBLabel("已截断，完整差异请用 IDE 对比或打开审阅中心。").apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(4)
        })
    }
}

private fun hunkTitle(index: Int, hunk: TaskChangeHunk): String = buildString {
    append("变更块 ${index + 1}")
    if (hunk.beforeLineCount > 0) append(" · 原 ${hunk.beforeStartLine}-${hunk.beforeStartLine + hunk.beforeLineCount - 1} 行")
    if (hunk.afterLineCount > 0) append(" · 现 ${hunk.afterStartLine}-${hunk.afterStartLine + hunk.afterLineCount - 1} 行")
}

/** Returns the pane plus how many diff lines it rendered. */
private fun hunkDiffPane(hunk: TaskChangeHunk, lineBudget: Int): Pair<JComponent, Int> {
    val pane = JTextPane().apply {
        isEditable = false
        isOpaque = true
        background = OmniCodeUiPalette.timelineElevated
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size)
        border = JBUI.Borders.empty(4, 6)
    }
    val document = pane.styledDocument
    var rendered = 0

    fun appendLines(text: String, prefix: String, fill: java.awt.Color?): Boolean {
        text.ifBlank { return true }.lines().forEach { line ->
            if (rendered >= lineBudget) return false
            val attributes = SimpleAttributeSet().apply {
                fill?.let { StyleConstants.setBackground(this, it) }
            }
            document.insertString(document.length, "$prefix $line\n", attributes)
            rendered++
        }
        return true
    }

    val complete = appendLines(hunk.beforeText, "-", OmniCodeUiPalette.diffRemovedFill) &&
        appendLines(hunk.afterText, "+", OmniCodeUiPalette.diffAddedFill)
    if (!complete) {
        document.insertString(document.length, "…\n", SimpleAttributeSet())
    }
    if (document.length > 0) {
        // Trim the trailing newline so the pane does not reserve an empty line.
        document.remove(document.length - 1, 1)
    }
    return pane to rendered
}

private fun String.lineCountOrZero(): Int = if (isBlank()) 0 else lines().size

private const val MAX_INLINE_DIFF_LINES = 160
