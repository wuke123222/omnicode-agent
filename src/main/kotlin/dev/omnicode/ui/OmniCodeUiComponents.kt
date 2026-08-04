package dev.omnicode.ui

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.UserAttachment
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.IdentityHashMap
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.JPopupMenu
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.JTextPane
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

internal object OmniCodeUiPalette {
    val canvas: Color = JBColor.namedColor(
        "ToolWindow.background",
        JBColor(Color(0xF7, 0xF8, 0xFA), Color(0x18, 0x19, 0x1C)),
    )
    val surface: Color = JBColor.namedColor(
        "Panel.background",
        JBColor(Color.WHITE, Color(0x22, 0x24, 0x28)),
    )
    val userBubble: Color = JBColor.namedColor(
        "EditorPane.inactiveBackground",
        JBColor(Color(0xEC, 0xEE, 0xF2), Color(0x34, 0x36, 0x3B)),
    )
    val codeBackground: Color = JBColor.namedColor(
        "EditorTextField.background",
        JBColor(Color(0xF0, 0xF1, 0xF3), Color(0x2A, 0x2D, 0x32)),
    )
    val border: Color = JBColor.namedColor(
        "Component.borderColor",
        JBColor(Color(0xD5, 0xD8, 0xDE), Color(0x3B, 0x3F, 0x46)),
    )
    val primary: Color = JBColor.namedColor(
        "Label.foreground",
        JBColor(Color(0x24, 0x27, 0x2D), Color(0xD7, 0xDA, 0xE0)),
    )
    val secondary: Color = JBColor.namedColor(
        "ContextHelp.foreground",
        JBColor(Color(0x5F, 0x65, 0x70), Color(0x9A, 0xA0, 0xAA)),
    )
    val accent: Color = JBColor.namedColor(
        "Link.activeForeground",
        JBColor(Color(0x36, 0x65, 0xD8), Color(0x7A, 0xA2, 0xF7)),
    )
    val success: Color = JBColor.namedColor(
        "Notifications.Green",
        JBColor(Color(0x2F, 0x7D, 0x4A), Color(0x73, 0xC9, 0x91)),
    )
    val error: Color = JBColor.namedColor(
        "Notifications.Red",
        JBColor(Color(0xB6, 0x2E, 0x3A), Color(0xF0, 0x75, 0x7D)),
    )
    val warning: Color = JBColor.namedColor(
        "Notifications.Yellow",
        JBColor(Color(0x9A, 0x62, 0x00), Color(0xE3, 0xAE, 0x63)),
    )
    val controlHover: Color = JBColor.namedColor(
        "ActionButton.hoverBackground",
        JBColor(Color(0xE9, 0xEB, 0xF0), Color(0x31, 0x34, 0x3A)),
    )
    val controlPressed: Color = JBColor.namedColor(
        "ActionButton.pressedBackground",
        JBColor(Color(0xDD, 0xE2, 0xEC), Color(0x3B, 0x40, 0x49)),
    )
    val controlSelected: Color = JBColor.namedColor(
        "EditorPane.inactiveBackground",
        JBColor(Color(0xE7, 0xED, 0xFB), Color(0x2B, 0x37, 0x4E)),
    )
    val controlWarning: Color = JBColor.namedColor(
        "ValidationTooltip.warningBackground",
        JBColor(Color(0xFF, 0xF3, 0xD6), Color(0x40, 0x35, 0x20)),
    )
    val timelineCard: Color = JBColor(Color.WHITE, Color(0x1E, 0x1E, 0x1E))
    val timelineElevated: Color = JBColor(Color(0xF2, 0xF3, 0xF5), Color(0x25, 0x25, 0x26))
    val timelineBorder: Color = JBColor(Color(0xD8, 0xDA, 0xDE), Color(0x33, 0x33, 0x33))
    val timelineMuted: Color = JBColor(Color(0x72, 0x76, 0x7D), Color(0x85, 0x85, 0x85))
    val timelineLink: Color = JBColor(Color(0x2F, 0x68, 0xB2), Color(0x4B, 0x90, 0xE2))
}

internal open class RoundedSurfacePanel(
    fillColor: Color,
    outlineColor: Color? = null,
    private val radius: Int = 12,
) : JPanel() {
    private var fillColor: Color = fillColor
    private var outlineColor: Color? = outlineColor

    var emphasizedOutlineColor: Color? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        isOpaque = false
    }

    fun updateSurfaceColors(fillColor: Color, outlineColor: Color?) {
        if (this.fillColor == fillColor && this.outlineColor == outlineColor) return
        this.fillColor = fillColor
        this.outlineColor = outlineColor
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = fillColor
            val arc = JBUI.scale(radius)
            g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }

    override fun paintBorder(graphics: Graphics) {
        val emphasized = emphasizedOutlineColor
        val color = emphasized ?: outlineColor ?: return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = color
            val strokeWidth = JBUI.scale(if (emphasized != null) 2 else 1)
            g.stroke = BasicStroke(strokeWidth.toFloat())
            val arc = JBUI.scale(radius)
            val inset = if (emphasized != null) JBUI.scale(1) else 0
            g.drawRoundRect(inset, inset, width - 1 - inset * 2, height - 1 - inset * 2, arc, arc)
        } finally {
            g.dispose()
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

internal class ConversationColumn : JPanel(), Scrollable {
    private val wrappers = java.util.IdentityHashMap<JComponent, JComponent>()
    private var viewportCenteredBlock: JComponent? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(18, 20, 4, 20)
    }

    fun addBlock(component: JComponent) {
        val wrapper = when (component) {
            is UserMessageCard -> RightAlignedMessageRow(component)
            is ViewportCenteredPanel -> ViewportFillingMessageRow(component).also {
                viewportCenteredBlock = component
            }
            else -> StretchPanel(BorderLayout()).apply {
                isOpaque = false
                add(component, BorderLayout.CENTER)
            }
        }.apply { border = JBUI.Borders.emptyBottom(16) }
        wrappers[component] = wrapper
        add(wrapper)
        revalidate()
        repaint()
    }

    fun removeBlock(component: JComponent) {
        val wrapper = wrappers.remove(component) ?: return
        remove(wrapper)
        if (viewportCenteredBlock === component) viewportCenteredBlock = null
        revalidate()
        repaint()
    }

    fun clearBlocks() {
        wrappers.clear()
        viewportCenteredBlock = null
        removeAll()
        revalidate()
        repaint()
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(
        visibleRect: java.awt.Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(18)

    override fun getScrollableBlockIncrement(
        visibleRect: java.awt.Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = (visibleRect.height - JBUI.scale(24)).coerceAtLeast(JBUI.scale(18))

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = shouldTrackCenteredViewport(
        hasCenteredBlock = viewportCenteredBlock != null,
        viewportHeight = parent?.height ?: 0,
        contentPreferredHeight = preferredSize.height,
    )
}

internal fun shouldTrackCenteredViewport(
    hasCenteredBlock: Boolean,
    viewportHeight: Int,
    contentPreferredHeight: Int,
): Boolean = hasCenteredBlock && viewportHeight > contentPreferredHeight

internal open class ViewportCenteredPanel(layout: java.awt.LayoutManager) : JPanel(layout) {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
}

private class ViewportFillingMessageRow(component: JComponent) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        add(component, BorderLayout.CENTER)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
}

private class RightAlignedMessageRow(component: JComponent) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        component.alignmentY = TOP_ALIGNMENT
        add(Box.createHorizontalGlue())
        add(component)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

internal open class StretchPanel(layout: java.awt.LayoutManager) : JPanel(layout) {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

internal open class GrowingTextArea(initialText: String = "") : JBTextArea(initialText) {
    init {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = OmniCodeUiPalette.primary
        border = JBUI.Borders.empty()
        font = JBFont.label()
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

internal class PromptTextArea(
    private val placeholder: String,
) : JBTextArea(4, 32) {
    init {
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = OmniCodeUiPalette.primary
        border = JBUI.Borders.empty(4, 4)
        font = JBFont.label()
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = repaint()
            override fun removeUpdate(event: DocumentEvent) = repaint()
            override fun changedUpdate(event: DocumentEvent) = repaint()
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = repaint()
            override fun focusLost(event: FocusEvent) = repaint()
        })
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (text.isNotEmpty()) return
        val g = graphics.create() as Graphics2D
        try {
            g.color = OmniCodeUiPalette.secondary
            g.font = font
            val metrics: FontMetrics = g.fontMetrics
            val insets = insets
            g.drawString(placeholder, insets.left, insets.top + metrics.ascent)
        } finally {
            g.dispose()
        }
    }
}

internal class UserMessageCard(
    text: String,
    attachments: List<UserAttachment> = emptyList(),
) : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.userBubble,
    outlineColor = null,
    radius = 12,
) {
    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(10, 12)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            if (text.isNotBlank()) add(GrowingTextArea(text))
            attachments.forEach { attachment ->
                if (text.isNotBlank() || attachment != attachments.first()) add(Box.createVerticalStrut(JBUI.scale(6)))
                add(ReadOnlyAttachmentLabel(attachment))
            }
        }, BorderLayout.CENTER)
    }

    override fun getMaximumSize(): Dimension = Dimension(JBUI.scale(860), preferredSize.height)
}

internal class AttachmentChip(
    private val attachment: UserAttachment,
    onRemove: () -> Unit,
) : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.controlSelected,
    outlineColor = OmniCodeUiPalette.border,
    radius = 8,
) {
    init {
        layout = BorderLayout(JBUI.scale(7), 0)
        border = JBUI.Borders.empty(4, 7, 4, 3)
        val preview = AttachmentPreviewCache.find(attachment)
        if (attachment.kind == AttachmentKind.IMAGE) {
            add(JBLabel().apply {
                icon = preview?.thumbnail?.let { ImageIcon(it) } ?: AllIcons.FileTypes.Image
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(JBUI.scale(74), JBUI.scale(54))
                toolTipText = attachmentTooltip(attachment)
            }, BorderLayout.WEST)
        }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(attachmentDisplayName(attachment.fileName)).apply {
                foreground = OmniCodeUiPalette.primary
                font = JBFont.small().asBold()
                toolTipText = attachment.fileName
            })
            add(JBLabel(attachmentDetailText(attachment)).apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                toolTipText = attachmentTooltip(attachment)
            })
        }, BorderLayout.CENTER)
        if (attachment.kind != AttachmentKind.IMAGE) {
            add(composerControlButton("预览", "查看 ${attachment.fileName} 的有界本地预览").apply {
                icon = AllIcons.FileTypes.Text
                font = JBFont.small()
                addActionListener { showAttachmentPreview(this, attachment) }
            }, BorderLayout.WEST)
        }
        add(composerControlButton("×", "移除 ${attachment.fileName}").apply {
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            minimumSize = preferredSize
            maximumSize = preferredSize
            font = JBFont.small().asBold()
            addActionListener { onRemove() }
        }, BorderLayout.EAST)
    }
}

private class ReadOnlyAttachmentLabel(attachment: UserAttachment) : JBLabel(attachmentChipText(attachment)) {
    init {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        icon = if (attachment.kind == AttachmentKind.IMAGE) AllIcons.FileTypes.Image else AllIcons.FileTypes.Text
        toolTipText = attachmentTooltip(attachment)
    }
}

internal fun attachmentChipText(attachment: UserAttachment): String {
    return "${attachmentDisplayName(attachment.fileName)} · ${attachmentDetailText(attachment)}"
}

internal fun attachmentDisplayName(fileName: String, maxLength: Int = 36): String {
    require(maxLength >= 8)
    if (fileName.length <= maxLength) return fileName
    val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 10 }
    val suffix = extension?.let { ".$it" }.orEmpty()
    val prefixLength = (maxLength - suffix.length - 1).coerceAtLeast(3)
    return fileName.take(prefixLength) + "…" + suffix
}

private fun attachmentTooltip(attachment: UserAttachment): String =
    "${attachment.fileName} · ${attachmentDetailText(attachment)}"

private fun showAttachmentPreview(invoker: JComponent, attachment: UserAttachment) {
    val preview = boundedAttachmentPreview(attachment.content)
    val popup = JPopupMenu().apply {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(JBLabel("${attachment.fileName} · ${attachmentDetailText(attachment)}").apply {
                font = JBFont.small().asBold()
            }, BorderLayout.NORTH)
            add(JBScrollPane(JBTextArea(preview.text).apply {
                isEditable = false
                lineWrap = false
                font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                caretPosition = 0
                accessibleContext.accessibleName = "${attachment.fileName} 内容预览"
            }).apply {
                preferredSize = Dimension(JBUI.scale(480), JBUI.scale(260))
            }, BorderLayout.CENTER)
            if (preview.truncated) {
                add(JBLabel("仅显示前 ${preview.displayedLines} 行 / ${MAX_TEXT_PREVIEW_CHARS} 字符以内").apply {
                    foreground = OmniCodeUiPalette.secondary
                    font = JBFont.small()
                }, BorderLayout.SOUTH)
            }
        })
    }
    popup.show(invoker, 0, invoker.height)
}

internal class AssistantTurnPanel(
    mode: AgentMode? = AgentMode.AGENT,
    private val onOpenFile: (ToolFileReference) -> Unit = {},
) : RoundedSurfacePanel(
    // Codex keeps assistant prose on the conversation canvas and reserves cards for tools,
    // diffs and approvals. A full-width outer card made long answers feel like one dense block.
    fillColor = OmniCodeUiPalette.canvas,
    outlineColor = null,
    radius = 0,
) {
    private val content = TimelineContentPanel()
    private val metaLabel = JBLabel().apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        isVisible = false
    }
    private val omissionLabel = JBLabel("较早的输出已省略").apply {
        icon = AllIcons.General.Warning
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        isVisible = false
    }
    private val completionIcon = JBLabel()
    private val completionLabel = JBLabel().apply {
        font = JBFont.small()
    }
    private val completionDuration = JBLabel().apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private val copyButton = flatButton("复制", "复制本轮助手回复").apply {
        isVisible = false
        addActionListener { copyAssistantReply() }
    }
    private val completionActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        add(copyButton)
        add(completionDuration)
    }
    private val elapsedLabel = JBLabel("处理中 · 0s").apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        horizontalAlignment = SwingConstants.RIGHT
    }
    private val completionRow = StretchPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(7)
        add(completionIcon, BorderLayout.WEST)
        add(completionLabel, BorderLayout.CENTER)
        add(completionActions, BorderLayout.EAST)
        isVisible = false
    }
    private val recoveryRow = StretchPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
        isVisible = false
    }
    private val startedAtNanos = System.nanoTime()
    private val durationTimer = Timer(1_000) {
        val now = System.nanoTime()
        currentStage?.updateElapsed(now)
        if (!finished) elapsedLabel.text = "处理中 · ${formatElapsed(now - startedAtNanos)}"
    }.apply {
        initialDelay = 1_000
        start()
    }
    /** Coalesces expensive BoxLayout/viewport invalidation while a model is streaming. */
    private val layoutTimer = Timer(STREAM_LAYOUT_FLUSH_MS) {
        refreshLayout()
    }.apply {
        isRepeats = false
    }
    private var currentStage: StageSummaryRow? = null
    private var activeText: LightweightMarkdownPane? = null
    private val textBlocks = mutableListOf<LightweightMarkdownPane>()
    private val toolCards = mutableListOf<ToolCallCard>()
    private val completedToolCards = ArrayDeque<ToolCallCard>()
    private val completedStageRows = ArrayDeque<StageSummaryRow>()
    private val contentWrappers = IdentityHashMap<JComponent, JComponent>()
    private val pendingToolsById = linkedMapOf<String, ToolCallCard>()
    private val pendingToolsWithoutId = mutableListOf<ToolCallCard>()
    private val completedToolIds = linkedSetOf<String>()
    private var delegateProgress: MultiAgentProgressCard? = null
    private var projectContextCard: ProjectContextSourcesCard? = null
    private var changeSummaryCard: InlineChangeSummaryCard? = null
    private var firstTextAtNanos: Long? = null
    private var toolCallCount = 0
    private var usageTokens = 0L
    private var visibleTextCharacters = 0
    private var finished = false
    private var recoveryActionsEnabled = true

    internal val visibleStageRowCount: Int
        get() = completedStageRows.size + if (currentStage == null) 0 else 1

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty(12, 14)

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        stack.add(StretchPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBLabel("OmniCode", AllIcons.Actions.Lightning, SwingConstants.LEADING).apply {
                    foreground = OmniCodeUiPalette.primary
                    font = JBFont.label().asBold()
                })
                add(JBLabel(assistantTurnModeLabel(mode)).apply {
                    foreground = OmniCodeUiPalette.secondary
                    font = JBFont.small()
                })
            }, BorderLayout.WEST)
            add(elapsedLabel, BorderLayout.EAST)
        })
        stack.add(Box.createVerticalStrut(JBUI.scale(7)))
        stack.add(StretchPanel(BorderLayout()).apply {
            isOpaque = false
            add(omissionLabel, BorderLayout.WEST)
        })
        stack.add(content)
        stack.add(completionRow)
        stack.add(recoveryRow)
        stack.add(StretchPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(metaLabel, BorderLayout.WEST)
        })
        add(stack, BorderLayout.CENTER)
    }

    override fun removeNotify() {
        durationTimer.stop()
        layoutTimer.stop()
        super.removeNotify()
    }

    fun updateStatus(message: String) {
        if (finished) return
        val presentation = stagePresentation(message) ?: return
        if (currentStage?.key == presentation.key) return
        finishCurrentStage()
        activeText = null
        val stage = StageSummaryRow(presentation)
        currentStage = stage
        addContent(stage, topGap = if (content.componentCount > 0) 6 else 0)
        trimCompletedStageRows()
    }

    fun appendText(value: String) {
        if (value.isEmpty()) return
        if (value.isNotBlank() && firstTextAtNanos == null) firstTextAtNanos = System.nanoTime()
        val area = activeText ?: LightweightMarkdownPane(onOpenFile).also {
            finishCurrentStage()
            addContent(it, topGap = if (content.componentCount > 0) 7 else 0)
            activeText = it
            textBlocks += it
            trimTextBlockComponents()
        }
        area.appendRaw(value)
        visibleTextCharacters += value.length
        trimVisibleText()
        area.revalidate()
        queueLayoutRefresh()
    }

    fun startTool(name: String, summary: String, callId: String = ""): ToolCallCard {
        finishCurrentStage()
        activeText = null
        if (callId.isNotBlank()) pendingToolsById[callId]?.let { return it }
        val card = ToolCallCard(name, summary, callId, onOpenFile)
        toolCards += card
        if (callId.isNotBlank()) pendingToolsById[callId] = card else pendingToolsWithoutId += card
        addContent(card, topGap = if (content.componentCount > 0) 7 else 0)
        return card
    }

    fun completeTool(
        name: String,
        result: String,
        isError: Boolean,
        callId: String = "",
        cancelled: Boolean = false,
    ) {
        if (callId.isNotBlank() && !completedToolIds.add(callId)) return
        val card = if (callId.isNotBlank()) {
            pendingToolsById.remove(callId)
        } else {
            pendingToolsWithoutId.indexOfLast { it.toolName == name }
                .takeIf { it >= 0 }
                ?.let { pendingToolsWithoutId.removeAt(it) }
        } ?: ToolCallCard(name, "", callId, onOpenFile).also {
                toolCards += it
                addContent(it, topGap = if (content.componentCount > 0) 7 else 0)
            }
        toolCallCount++
        card.complete(result, isError, cancelled)
        completedToolCards.addLast(card)
        trimCompletedToolCards()
        trimCompletedToolIds()
        activeText = null
        refreshLayout()
    }

    fun startDelegate(
        agentId: String,
        displayName: String,
        objective: String,
        role: String = "",
    ): Boolean {
        finishCurrentStage()
        activeText = null
        val card = delegateProgress ?: MultiAgentProgressCard().also {
            delegateProgress = it
            addContent(it, topGap = if (content.componentCount > 0) 7 else 0)
        }
        return card.startDelegate(agentId, displayName, objective, role)
    }

    fun completeDelegate(
        agentId: String,
        displayName: String,
        status: DelegateProgressStatus,
        summary: String,
        tokens: Long,
        role: String = "",
    ): Boolean {
        val card = delegateProgress ?: MultiAgentProgressCard().also {
            delegateProgress = it
            addContent(it, topGap = if (content.componentCount > 0) 7 else 0)
        }
        val added = card.completeDelegate(
            agentId = agentId,
            status = status,
            summary = summary,
            tokens = tokens,
            fallbackDisplayName = displayName,
            fallbackRole = role,
        )
        activeText = null
        refreshLayout()
        return added
    }

    fun updateUsage(tokens: Long) {
        usageTokens = maxOf(usageTokens, tokens)
        metaLabel.text = "${java.text.NumberFormat.getIntegerInstance().format(tokens)} tokens"
        metaLabel.isVisible = true
        refreshLayout()
    }

    fun showProjectContext(
        rulePaths: List<String>,
        pinnedPaths: List<String>,
        excludedPathCount: Int,
        estimatedContextTokens: Long,
        maxContextTokens: Long,
        truncated: Boolean,
    ) {
        projectContextCard?.let(::removeContent)
        val card = ProjectContextSourcesCard(
            rulePaths = rulePaths,
            pinnedPaths = pinnedPaths,
            excludedPathCount = excludedPathCount,
            estimatedContextTokens = estimatedContextTokens,
            maxContextTokens = maxContextTokens,
            truncated = truncated,
        )
        projectContextCard = card
        addContent(card, topGap = if (content.componentCount > 0) 7 else 0)
        refreshLayout()
    }

    fun showChangeSummary(
        files: List<dev.omnicode.review.TaskChangedFile>,
        onReview: () -> Unit,
    ) {
        changeSummaryCard?.let(::removeContent)
        changeSummaryCard = InlineChangeSummaryCard(files, onOpenFile, onReview)
        addContent(changeSummaryCard!!, topGap = if (content.componentCount > 0) 7 else 0)
        refreshLayout()
    }

    fun finish(label: String, isError: Boolean = false) {
        if (finished) return
        finished = true
        activeText = null
        finishCurrentStage()
        (pendingToolsById.values + pendingToolsWithoutId).forEach { card ->
            card.complete("任务结束前工具未返回结果。", isError = true)
        }
        pendingToolsById.clear()
        pendingToolsWithoutId.clear()
        delegateProgress?.finishPendingDelegates(cancelled = cleanStatus(label).contains("取消"))
        textBlocks.forEach(LightweightMarkdownPane::finalizeMarkdown)
        durationTimer.stop()
        elapsedLabel.text = "已处理 ${formatElapsed(System.nanoTime() - startedAtNanos)}"

        completionIcon.icon = if (isError) AllIcons.General.Error else AllIcons.General.GreenCheckmark
        completionLabel.text = cleanStatus(label)
        completionLabel.foreground = if (isError) OmniCodeUiPalette.error else OmniCodeUiPalette.secondary
        val elapsedNanos = System.nanoTime() - startedAtNanos
        completionDuration.text = buildList {
            add(formatElapsed(elapsedNanos))
            firstTextAtNanos?.let { add("首响应 ${formatLatency(it - startedAtNanos)}") }
            if (toolCallCount > 0) add("工具 $toolCallCount")
            if (usageTokens > 0) add("${java.text.NumberFormat.getIntegerInstance().format(usageTokens)} tokens")
        }.joinToString(" · ")
        copyButton.isVisible = textBlocks.any { it.rawText.isNotBlank() }
        completionRow.isVisible = true
        refreshLayout()
    }

    private fun copyAssistantReply() {
        val text = textBlocks.asSequence()
            .map(LightweightMarkdownPane::rawText)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .trim()
        if (text.isBlank()) return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        copyButton.text = "已复制"
        Timer(1_500) {
            copyButton.text = "复制"
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun showRecoveryAction(
        label: String,
        tooltip: String,
        icon: Icon = AllIcons.Actions.Edit,
        action: () -> Unit,
    ) {
        recoveryActionsEnabled = true
        recoveryRow.removeAll()
        addRecoveryAction(label, tooltip, icon, action)
    }

    fun addRecoveryAction(
        label: String,
        tooltip: String,
        icon: Icon = AllIcons.Actions.Edit,
        action: () -> Unit,
    ) {
        recoveryRow.add(flatButton(label, tooltip).apply {
            this.icon = icon
            isEnabled = recoveryActionsEnabled
            addActionListener { action() }
        })
        recoveryRow.isVisible = true
        refreshLayout()
    }

    fun clearRecoveryAction() {
        recoveryRow.removeAll()
        recoveryRow.isVisible = false
        recoveryActionsEnabled = true
        refreshLayout()
    }

    fun setRecoveryActionsEnabled(enabled: Boolean) {
        recoveryActionsEnabled = enabled
        recoveryRow.components.filterIsInstance<JComponent>().forEach { it.isEnabled = enabled }
        recoveryRow.revalidate()
        recoveryRow.repaint()
    }

    fun focusExecutionSection(target: ExecutionNavigationTarget): Boolean {
        val targetComponent = when (target) {
            ExecutionNavigationTarget.TASKS -> toolCards.lastOrNull()
            ExecutionNavigationTarget.SUBAGENTS -> delegateProgress
            ExecutionNavigationTarget.EDITS -> toolCards.lastOrNull {
                it.toolName == "apply_change" || it.toolName == "apply_patch"
            }
        } ?: return false
        targetComponent.isFocusable = true
        targetComponent.requestFocusInWindow()
        targetComponent.scrollRectToVisible(java.awt.Rectangle(0, 0, targetComponent.width, targetComponent.height))
        return true
    }

    private fun finishCurrentStage() {
        val stage = currentStage ?: return
        stage.finish()
        currentStage = null
        completedStageRows.addLast(stage)
        trimCompletedStageRows()
    }

    private fun addContent(component: JComponent, topGap: Int) {
        component.alignmentX = LEFT_ALIGNMENT
        val wrapper = StretchPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(topGap, 13, 0, 0)
            add(component, BorderLayout.CENTER)
        }
        contentWrappers[component] = wrapper
        content.add(wrapper)
        refreshLayout()
    }

    private fun removeContent(component: JComponent) {
        val wrapper = contentWrappers.remove(component) ?: return
        content.remove(wrapper)
    }

    private fun trimTextBlockComponents() {
        while (textBlocks.size > MAX_VISIBLE_TEXT_BLOCKS) {
            val oldest = textBlocks.first()
            if (oldest === activeText) break
            textBlocks.removeAt(0)
            visibleTextCharacters = (visibleTextCharacters - oldest.rawLength).coerceAtLeast(0)
            removeContent(oldest)
            omissionLabel.isVisible = true
        }
    }

    private fun trimCompletedToolCards() {
        while (completedToolCards.size > MAX_VISIBLE_TOOL_CARDS) {
            val oldest = completedToolCards.removeFirst()
            toolCards.remove(oldest)
            removeContent(oldest)
            omissionLabel.isVisible = true
        }
    }

    private fun trimCompletedStageRows() {
        val completedLimit = (MAX_VISIBLE_STAGE_ROWS - if (currentStage == null) 0 else 1).coerceAtLeast(0)
        while (completedStageRows.size > completedLimit) {
            removeContent(completedStageRows.removeFirst())
            omissionLabel.isVisible = true
        }
    }

    private fun trimCompletedToolIds() {
        while (completedToolIds.size > MAX_REMEMBERED_COMPLETED_TOOL_IDS) {
            val oldest = completedToolIds.firstOrNull() ?: break
            completedToolIds.remove(oldest)
        }
    }

    private fun trimVisibleText() {
        var toRemove = visibleTextCharacters - MAX_VISIBLE_TEXT_CHARACTERS
        if (toRemove <= 0) return
        toRemove += TEXT_TRIM_BUFFER
        for (area in textBlocks) {
            if (toRemove <= 0) break
            val remove = toRemove.coerceAtMost(area.rawLength)
            if (remove <= 0) continue
            area.trimStart(remove)
            visibleTextCharacters -= remove
            toRemove -= remove
        }
        textBlocks.toList().forEach { area ->
            if (area !== activeText && area.rawLength == 0) {
                textBlocks.remove(area)
                removeContent(area)
            }
        }
        omissionLabel.isVisible = true
    }

    private fun refreshLayout() {
        layoutTimer.stop()
        revalidate()
        repaint()
        parent?.revalidate()
    }

    private fun queueLayoutRefresh() {
        if (!layoutTimer.isRunning) layoutTimer.start()
    }

    private companion object {
        const val MAX_VISIBLE_TEXT_CHARACTERS = 180_000
        const val TEXT_TRIM_BUFFER = 8_000
        const val MAX_VISIBLE_TEXT_BLOCKS = 64
        const val MAX_VISIBLE_TOOL_CARDS = 64
        const val MAX_VISIBLE_STAGE_ROWS = 12
        const val MAX_REMEMBERED_COMPLETED_TOOL_IDS = 2_048
        const val STREAM_LAYOUT_FLUSH_MS = 50
    }
}

private class ProjectContextSourcesCard(
    rulePaths: List<String>,
    pinnedPaths: List<String>,
    excludedPathCount: Int,
    estimatedContextTokens: Long,
    maxContextTokens: Long,
    truncated: Boolean,
) : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.surface,
    outlineColor = OmniCodeUiPalette.border,
    radius = 9,
) {
    init {
        layout = BorderLayout(JBUI.scale(8), 0)
        border = JBUI.Borders.empty(7, 9)
        val percent = ((estimatedContextTokens.toDouble() / maxContextTokens.toDouble()) * 100)
            .toInt().coerceIn(0, 100)
        add(JBLabel("项目上下文", AllIcons.Nodes.Folder, SwingConstants.LEADING).apply {
            font = JBFont.small().asBold()
            foreground = OmniCodeUiPalette.primary
        }, BorderLayout.WEST)
        add(JBLabel(buildString {
            append("规则 ").append(rulePaths.size)
            append(" · 固定 ").append(pinnedPaths.size)
            append(" · 排除 ").append(excludedPathCount)
            append(" · ≈").append(java.text.NumberFormat.getIntegerInstance().format(estimatedContextTokens))
                .append(" tokens / ").append(percent).append('%')
            if (truncated) append(" · 已截断")
        }).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            toolTipText = buildString {
                append("本轮规则：")
                append(if (rulePaths.isEmpty()) "无" else rulePaths.joinToString("、"))
                append("；固定文件：")
                append(if (pinnedPaths.isEmpty()) "无" else pinnedPaths.joinToString("、"))
            }.take(1_000)
        }, BorderLayout.CENTER)
    }
}

internal fun assistantTurnModeLabel(mode: AgentMode?): String = when (mode) {
    AgentMode.AGENT -> "Agent"
    AgentMode.PLAN -> "Plan 看板"
    AgentMode.CLAUDE_PLAN -> "Claude Plan"
    AgentMode.RESEARCH -> "Research"
    null -> "历史"
}

internal data class StagePresentation(
    val key: String,
    val runningText: String,
    val completedText: String,
    val warning: Boolean = false,
)

internal fun stagePresentation(message: String): StagePresentation? {
    val normalized = cleanStatus(message)
    if (normalized.isBlank()) return null
    return when {
        normalized.startsWith("思考") || normalized.startsWith("Thinking", ignoreCase = true) ->
            StagePresentation("thinking", "思考中", "思考了")
        normalized.startsWith("模型请求") || normalized.startsWith("Provider request", ignoreCase = true) ->
            StagePresentation("provider-request", "正在请求模型", "模型请求完成")
        normalized.startsWith("阶段：") || normalized.startsWith("Stage:", ignoreCase = true) -> {
            val stage = (if (normalized.startsWith("阶段：")) {
                normalized.removePrefix("阶段：")
            } else {
                normalized.substringAfter(':')
            }).trim().removeSuffix("…").ifBlank { "任务" }
            StagePresentation("stage:$stage", "正在处理 · $stage", "已处理 · $stage")
        }
        normalized.startsWith("Provider temporarily unavailable", ignoreCase = true) ||
            normalized.startsWith("Provider attempt may have consumed quota", ignoreCase = true) ->
            StagePresentation("provider-retry", "正在重试模型连接", "模型连接已重试")
        normalized.startsWith("Provider output segment reached", ignoreCase = true) ||
            normalized.startsWith("Provider stream was interrupted", ignoreCase = true) ->
            StagePresentation("provider-continue", "正在衔接下一段模型输出", "已自动衔接模型输出")
        normalized.startsWith("正在通过") && normalized.endsWith("识别图片…") ->
            StagePresentation("vision", normalized, "图片识别完成")
        normalized.startsWith("Agent 正在处理") || normalized.startsWith("Plan 看板正在") ||
            normalized.startsWith("Claude Plan 正在") || normalized.startsWith("Research 正在") ||
            normalized.startsWith("正在建立安全恢复点") || normalized.startsWith("正在检查恢复状态") ||
            normalized.startsWith("正在加载模型配置") ->
            StagePresentation("preparing", "正在准备任务", "任务准备完成")
        normalized.startsWith("正在准备项目上下文") ->
            StagePresentation("project-context", "正在准备项目上下文", "项目上下文已就绪")
        normalized.startsWith("正在并行连接 MCP") ->
            StagePresentation("mcp-connect", "正在连接 MCP 服务", "MCP 服务连接完成")
        normalized.startsWith("MCP ") -> {
            val detail = normalized.removePrefix("MCP ").take(300)
            val server = detail.substringBefore(':').trim().take(80).ifBlank { "unknown" }
            StagePresentation(
                key = "mcp-warning:$server",
                runningText = "MCP 不可用 · $detail",
                completedText = "MCP 不可用 · $detail",
                warning = true,
            )
        }
        normalized.startsWith("检测到尚未解除的未知副作用恢复点") ->
            StagePresentation("recovery-warning", "检测到待确认的上次操作", "本轮已限制为安全工具", warning = true)
        normalized.startsWith("Checkpoint save failed", ignoreCase = true) ->
            StagePresentation("checkpoint-warning", "恢复点保存异常", "恢复点保存异常，请检查任务状态", warning = true)
        normalized.startsWith("Tool audit could not be persisted", ignoreCase = true) ->
            StagePresentation("audit-warning", "工具审计保存失败", "工具审计保存失败，请检查操作记录", warning = true)
        normalized.startsWith("Usage could not be persisted", ignoreCase = true) ->
            StagePresentation("usage-warning", "用量记录保存失败", "用量记录保存失败，任务结果不受影响", warning = true)
        normalized.startsWith("正在停止") ->
            StagePresentation("stopping", "正在安全停止", "已停止")
        else -> null
    }
}

private class StageSummaryRow(
    private val presentation: StagePresentation,
) : StretchPanel(BorderLayout(JBUI.scale(7), 0)) {
    val key: String get() = presentation.key
    private val startedAtNanos = System.nanoTime()
    private var completedAtNanos: Long? = null
    private val state = JBLabel().apply {
        icon = if (presentation.warning) AllIcons.General.Warning else AnimatedIcon.Default()
    }
    private val label = JBLabel(presentation.runningText).apply {
        foreground = if (presentation.warning) OmniCodeUiPalette.warning else OmniCodeUiPalette.secondary
        font = JBFont.small()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2)
        add(state, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
    }

    fun updateElapsed(nowNanos: Long) {
        if (completedAtNanos == null && !presentation.warning) {
            label.text = "${presentation.runningText} · ${formatElapsed(nowNanos - startedAtNanos)}"
        }
    }

    fun finish() {
        if (completedAtNanos != null) return
        completedAtNanos = System.nanoTime()
        if (presentation.warning) {
            label.text = presentation.completedText
            return
        }
        state.icon = AllIcons.General.ChevronRight
        val elapsed = formatElapsed(requireNotNull(completedAtNanos) - startedAtNanos)
        label.text = if (presentation.key == "thinking") "${presentation.completedText} $elapsed" else {
            "${presentation.completedText} · $elapsed"
        }
    }
}

internal class ToolCallCard(
    val toolName: String,
    summary: String,
    val callId: String = "",
    private val onOpenFile: (ToolFileReference) -> Unit = {},
) : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.timelineCard,
    outlineColor = OmniCodeUiPalette.timelineBorder,
    radius = 8,
) {
    private val presentation = toolCardPresentation(toolName, summary)
    private val startedAtNanos = System.nanoTime()
    private var completedAtNanos: Long? = null
    private val toolIcon = JBLabel().apply { icon = toolPresentationIcon(toolName) }
    private val titleLabel = JBLabel(presentation.title).apply {
        foreground = OmniCodeUiPalette.primary
        font = JBFont.label().asBold()
        toolTipText = toolName
    }
    private val detailLabel = ElidingLabel(presentation.detail).apply {
        foreground = if (presentation.fileReference != null) OmniCodeUiPalette.timelineLink else OmniCodeUiPalette.timelineMuted
        font = JBFont.label()
        toolTipText = presentation.detail.takeIf(String::isNotBlank)
        cursor = if (presentation.fileReference != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
    }
    private val statusDot = StatusDot(OmniCodeUiPalette.accent, "运行中")
    private val durationLabel = JBLabel("0s").apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private val footerStatusLabel = JBLabel("运行中").apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private val details = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val resultArea = LightweightMarkdownPane(
        onOpenFile = onOpenFile,
        allowBareFileReferences = toolName == "list_files",
    )
    private val resultScroll = JBScrollPane(resultArea).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(96))
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        isVisible = false
    }
    private val toggle = iconButton(AllIcons.General.ChevronDown, "展开工具详情")
    private val durationTimer = Timer(1_000) {
        updateDuration(System.nanoTime())
    }.apply {
        initialDelay = 1_000
        start()
    }
    private var expanded = false
    private var completionStatus = "运行中"

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(8, 13)

        val header = StretchPanel(BorderLayout(JBUI.scale(7), 0)).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            minimumSize = Dimension(0, JBUI.scale(22))
            add(toolIcon, BorderLayout.WEST)
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.WEST)
                add(detailLabel, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(7), 0)).apply {
                isOpaque = false
                add(statusDot)
                add(toggle)
            }, BorderLayout.EAST)
        }
        val toggleDetails = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1) setExpanded(!expanded)
            }
        }
        header.addMouseListener(toggleDetails)
        titleLabel.addMouseListener(toggleDetails)
        toggle.addActionListener { setExpanded(!expanded) }
        presentation.fileReference?.let { reference ->
            detailLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) onOpenFile(reference)
                }
            })
        }
        add(header, BorderLayout.NORTH)

        if (summary.isNotBlank()) {
            details.add(Box.createVerticalStrut(JBUI.scale(6)))
            details.add(GrowingTextArea(readableToolSummary(summary)).apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
            })
        }
        details.add(resultScroll)
        details.add(StretchPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(7)
            add(footerStatusLabel, BorderLayout.WEST)
            add(durationLabel, BorderLayout.EAST)
        })
        details.isVisible = false
        add(details, BorderLayout.CENTER)
    }

    override fun removeNotify() {
        durationTimer.stop()
        super.removeNotify()
    }

    fun complete(result: String, isError: Boolean, cancelled: Boolean = false) {
        if (completedAtNanos != null) return
        if (completedAtNanos == null) completedAtNanos = System.nanoTime()
        durationTimer.stop()
        updateDuration(requireNotNull(completedAtNanos))
        completionStatus = when {
            cancelled -> "已取消"
            isError -> "失败"
            else -> "完成"
        }
        statusDot.update(
            color = when {
                cancelled -> OmniCodeUiPalette.timelineMuted
                isError -> OmniCodeUiPalette.error
                else -> OmniCodeUiPalette.success
            },
            description = completionStatus,
        )
        footerStatusLabel.text = completionStatus
        footerStatusLabel.foreground = if (isError && !cancelled) OmniCodeUiPalette.error else OmniCodeUiPalette.secondary
        if (result.isNotBlank()) {
            resultArea.setRawText(result)
            resultArea.finalizeMarkdown()
            resultArea.caretPosition = 0
            resultArea.setSize((width - JBUI.scale(32)).coerceAtLeast(JBUI.scale(240)), JBUI.scale(10_000))
            val preferredHeight = resultArea.preferredSize.height.coerceAtLeast(JBUI.scale(34))
            resultScroll.preferredSize = Dimension(0, preferredHeight.coerceAtMost(JBUI.scale(180)))
            resultScroll.border = JBUI.Borders.emptyTop(6)
            resultScroll.isVisible = true
        }
        if (isError || cancelled) setExpanded(true)
        revalidate()
        repaint()
        parent?.revalidate()
    }

    private fun updateDuration(nowNanos: Long) {
        durationLabel.text = formatElapsed((completedAtNanos ?: nowNanos) - startedAtNanos)
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        details.isVisible = value
        toggle.icon = if (value) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown
        toggle.toolTipText = if (value) "收起工具详情" else "展开工具详情"
        toggle.accessibleContext.accessibleName = toggle.toolTipText
        revalidate()
        repaint()
        parent?.revalidate()
    }
}

internal data class ToolFileReference(
    val path: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

internal data class ToolFileReferenceSpan(
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val reference: ToolFileReference,
)

internal fun projectFileReferenceSpans(value: String): List<ToolFileReferenceSpan> =
    PROJECT_FILE_REFERENCE_PATTERN.findAll(value).mapNotNull { match ->
        val path = match.groupValues[1].replace('\\', '/')
        if (!isSafeProjectRelativeReference(path)) return@mapNotNull null
        val start = (match.groupValues[2].ifBlank { match.groupValues[4] }).toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return@mapNotNull null
        val end = (match.groupValues[3].ifBlank { match.groupValues[5] })
            .takeIf(String::isNotBlank)
            ?.toIntOrNull()
            ?.takeIf { it >= start }
            ?: if (match.groupValues[3].isNotBlank() || match.groupValues[5].isNotBlank()) return@mapNotNull null else null
        ToolFileReferenceSpan(
            startOffset = match.range.first,
            endOffsetExclusive = match.range.last + 1,
            reference = ToolFileReference(path, start, end),
        )
    }.toList()

/**
 * Parses the one-path-per-line format returned by list_files. Bare paths are
 * intentionally opt-in at the call site; accepting them in normal prose would
 * turn ordinary words such as README into unexpected editor navigation.
 */
internal fun projectBareFileReferenceSpans(value: String): List<ToolFileReferenceSpan> {
    val spans = mutableListOf<ToolFileReferenceSpan>()
    var lineStart = 0
    value.split('\n').forEach { line ->
        val leadingWhitespace = line.indexOfFirst { !it.isWhitespace() }
        if (leadingWhitespace < 0) {
            lineStart += line.length + 1
            return@forEach
        }
        val candidateStart = lineStart + leadingWhitespace
        val candidate = line.substring(leadingWhitespace)
            .trim()
            .removePrefix("- ")
            .removePrefix("• ")
            .trim('`')
        val candidateOffset = value.indexOf(candidate, candidateStart)
        if (candidate.isNotBlank() &&
            !candidate.endsWith('/') &&
            candidateOffset >= candidateStart &&
            isSafeProjectRelativeReference(candidate) &&
            isLikelyBareProjectFile(candidate)
        ) {
            spans += ToolFileReferenceSpan(
                startOffset = candidateOffset,
                endOffsetExclusive = candidateOffset + candidate.length,
                reference = ToolFileReference(candidate),
            )
        }
        lineStart += line.length + 1
    }
    return spans
}

private fun isLikelyBareProjectFile(path: String): Boolean {
    val fileName = path.substringAfterLast('/')
    return fileName.contains('.') || fileName in BARE_PROJECT_FILE_NAMES
}

private val BARE_PROJECT_FILE_NAMES = setOf(
    "Dockerfile",
    "Gemfile",
    "LICENSE",
    "Makefile",
    "Procfile",
    "README",
)

private fun isSafeProjectRelativeReference(path: String): Boolean {
    if (path.isBlank() || path.length > MAX_PROJECT_FILE_REFERENCE_CHARS) return false
    if (path.startsWith('/') || path.startsWith('~') || WINDOWS_DRIVE_PREFIX.matchesAt(path, 0)) return false
    val segments = path.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
    val fileName = segments.last()
    return (segments.size > 1 || fileName.contains('.')) && !fileName.endsWith('.')
}

internal data class ToolCardPresentation(
    val title: String,
    val detail: String,
    val fileReference: ToolFileReference? = null,
)

internal fun toolCardPresentation(toolName: String, rawSummary: String): ToolCardPresentation {
    val data = runCatching { JsonParser.parseString(rawSummary).asJsonObject }.getOrNull()
    fun string(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        data?.get(name)?.takeUnless { it.isJsonNull }?.runCatching { asString }?.getOrNull()
    }.orEmpty()
    fun positiveInt(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        data?.get(name)?.takeUnless { it.isJsonNull }?.runCatching { asInt }?.getOrNull()?.takeIf { it > 0 }
    }
    val path = string("path", "file", "filePath")
    val start = positiveInt("start_line", "startLine", "line", "start")
    val end = positiveInt("end_line", "endLine", "end")
    val lineSuffix = when {
        start != null && end != null -> ":$start-$end"
        start != null -> ":$start"
        else -> ""
    }
    return when (toolName) {
        "read_file" -> ToolCardPresentation("读取文件", "$path$lineSuffix", path.takeIf(String::isNotBlank)?.let { ToolFileReference(it, start, end) })
        "apply_change", "apply_patch" -> ToolCardPresentation(
            if (toolName == "apply_patch") "精确修改" else "修改文件",
            path,
            path.takeIf(String::isNotBlank)?.let { ToolFileReference(it) },
        )
        "list_files" -> ToolCardPresentation("文件匹配", path.ifBlank { string("glob", "pattern") })
        "search_text" -> {
            val query = string("query", "pattern", "text")
            ToolCardPresentation("文件匹配", listOf(query, path).filter(String::isNotBlank).joinToString("  ·  "))
        }
        "list_ide_problems" -> ToolCardPresentation("IDE 问题", "读取 Problems 索引")
        "run_command" -> {
            val command = data?.get("argv")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.joinToString(" ") { element ->
                runCatching { element.asString }.getOrElse { element.toString() }
            }.orEmpty().ifBlank { string("command") }
            ToolCardPresentation("运行命令", command)
        }
        "list_skills" -> ToolCardPresentation("浏览 Skill", string("query"))
        "load_skill" -> ToolCardPresentation("加载 Skill", string("name", "path"))
        else -> ToolCardPresentation(humanizeToolName(toolName), readableToolSummary(rawSummary).lineSequence().firstOrNull().orEmpty())
    }
}

private fun toolPresentationIcon(toolName: String) = when (toolName) {
    "run_command" -> AllIcons.Actions.Execute
    "read_file" -> AllIcons.FileTypes.Text
    "apply_change", "apply_patch" -> AllIcons.Actions.Edit
    "search_text", "list_files" -> AllIcons.Nodes.Folder
    "list_ide_problems" -> AllIcons.General.Warning
    else -> AllIcons.Nodes.Plugin
}

private class StatusDot(color: Color, description: String) : JComponent() {
    private var dotColor: Color = color

    init {
        val size = Dimension(JBUI.scale(10), JBUI.scale(20))
        preferredSize = size
        minimumSize = size
        maximumSize = size
        update(color, description)
    }

    fun update(color: Color, description: String) {
        dotColor = color
        toolTipText = description
        accessibleContext?.accessibleName = description
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = dotColor
            val diameter = JBUI.scale(8)
            g.fillOval((width - diameter) / 2, (height - diameter) / 2, diameter, diameter)
        } finally {
            g.dispose()
        }
    }
}

private class ElidingLabel(private val sourceText: String) : JBLabel(sourceText) {
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        text = elideMiddle(sourceText, getFontMetrics(font), width)
    }
}

internal fun elideMiddle(value: String, metrics: FontMetrics, width: Int): String {
    if (value.isBlank() || width <= 0 || metrics.stringWidth(value) <= width) return value
    val ellipsis = "…"
    var left = 0
    var right = value.length
    while (left < right && metrics.stringWidth(value.take(left) + ellipsis + value.takeLast(left)) <= width) left++
    val keep = (left - 1).coerceAtLeast(1)
    val leading = (keep * 2 / 3).coerceAtLeast(1)
    val trailing = (keep - leading).coerceAtLeast(1)
    return value.take(leading) + ellipsis + value.takeLast(trailing)
}

private class TimelineContentPanel : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (componentCount == 0) return
        val g = graphics.create() as Graphics2D
        try {
            g.color = OmniCodeUiPalette.timelineBorder
            g.stroke = BasicStroke(JBUI.scale(1).toFloat())
            val x = JBUI.scale(5)
            g.drawLine(x, 0, x, height)
        } finally {
            g.dispose()
        }
    }
}

internal class ExecutionNavigationBar() : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.timelineElevated,
    outlineColor = OmniCodeUiPalette.timelineBorder,
    radius = 8,
) {
    constructor(onNavigate: (ExecutionNavigationTarget) -> Unit) : this() {
        navigationHandler = onNavigate
    }

    private var navigationHandler: (ExecutionNavigationTarget) -> Unit = {}
    private val tasks = navigationItem(ExecutionNavigationTarget.TASKS, "☷", "任务")
    private val subagents = navigationItem(ExecutionNavigationTarget.SUBAGENTS, "◉", "子代理")
    private val edits = navigationItem(ExecutionNavigationTarget.EDITS, "✎", "编辑")
    private val items: List<JToggleButton> by lazy { listOf(tasks, subagents, edits) }
    private var selectedTarget: ExecutionNavigationTarget = ExecutionNavigationTarget.TASKS

    init {
        layout = GridLayout(1, 3)
        border = JBUI.Borders.empty(3, 4)
        preferredSize = Dimension(0, JBUI.scale(36))
        minimumSize = Dimension(0, JBUI.scale(36))
        add(tasks)
        add(navDivider(subagents))
        add(navDivider(edits))
        accessibleContext?.accessibleName = "执行视图导航"
        updateSelection()
    }

    fun updateCounts(toolCount: Int, subagentCount: Int, editCount: Int, running: Boolean) {
        // This control opens the workflow-level task center. A model tool-call count here looked
        // like a task count and made the destination appear inconsistent.
        tasks.text = "☷  任务"
        subagents.text = "◉  ${navigationText("子代理", subagentCount)}"
        edits.text = "✎  ${navigationText("编辑", editCount)}"
        tasks.toolTipText = if (running) {
            "打开统一任务与历史；当前执行已有 $toolCount 个工具步骤"
        } else {
            "打开统一任务与历史"
        }
        subagents.toolTipText = "定位本次任务的子代理：$subagentCount"
        edits.toolTipText = "定位或审阅本次任务的文件修改：$editCount"
        items.forEach { it.accessibleContext?.accessibleName = it.toolTipText }
    }

    internal fun select(target: ExecutionNavigationTarget, notify: Boolean = false) {
        selectedTarget = target
        updateSelection()
        item(target).requestFocusInWindow()
        if (notify) navigationHandler(target)
    }

    internal fun selectedTarget(): ExecutionNavigationTarget = selectedTarget

    private fun navigationItem(
        target: ExecutionNavigationTarget,
        icon: String,
        label: String,
    ): JToggleButton = JToggleButton("$icon  $label").apply {
        horizontalAlignment = SwingConstants.CENTER
        font = JBFont.label().asBold()
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { select(target, notify = true) }
        installArrowNavigation(this, target)
        installKeyboardActivation(this)
    }

    private fun installKeyboardActivation(button: JToggleButton) {
        listOf(KeyEvent.VK_ENTER, KeyEvent.VK_SPACE).forEach { key ->
            val actionName = "omnicode.executionNavigation.activate.$key"
            button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), actionName)
            button.actionMap.put(actionName, object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) = button.doClick()
            })
        }
    }

    private fun installArrowNavigation(button: JToggleButton, target: ExecutionNavigationTarget) {
        listOf(KeyEvent.VK_LEFT to -1, KeyEvent.VK_RIGHT to 1).forEach { (key, delta) ->
            val actionName = "omnicode.executionNavigation.$key"
            button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), actionName)
            button.actionMap.put(actionName, object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                    val index = ExecutionNavigationTarget.entries.indexOf(target)
                    val next = ExecutionNavigationTarget.entries[
                        (index + delta + ExecutionNavigationTarget.entries.size) % ExecutionNavigationTarget.entries.size
                    ]
                    select(next, notify = true)
                }
            })
        }
    }

    private fun item(target: ExecutionNavigationTarget): JToggleButton = when (target) {
        ExecutionNavigationTarget.TASKS -> tasks
        ExecutionNavigationTarget.SUBAGENTS -> subagents
        ExecutionNavigationTarget.EDITS -> edits
    }

    private fun updateSelection() {
        items.forEachIndexed { index, button ->
            val selected = ExecutionNavigationTarget.entries[index] == selectedTarget
            button.isSelected = selected
            button.foreground = if (selected) OmniCodeUiPalette.primary else OmniCodeUiPalette.timelineMuted
            button.background = if (selected) OmniCodeUiPalette.controlSelected else OmniCodeUiPalette.timelineElevated
        }
        repaint()
    }

    private fun navDivider(component: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.customLine(OmniCodeUiPalette.timelineBorder, 0, 1, 0, 0)
        add(component, BorderLayout.CENTER)
    }
}

internal enum class ExecutionNavigationTarget {
    TASKS,
    SUBAGENTS,
    EDITS,
}

internal fun navigationText(label: String, count: Int): String = if (count > 0) "$label  $count" else label

/**
 * A safe, dependency-free renderer for the small Markdown subset commonly emitted by models.
 * Streaming remains append-only and cheap; formatting is applied once the block is complete.
 */
internal class LightweightMarkdownPane(
    private val onOpenFile: (ToolFileReference) -> Unit = {},
    private val allowBareFileReferences: Boolean = false,
) : JTextPane() {
    private val raw = StringBuilder()
    private var formatted = false
    private var simplifiedLargeOutput = false

    val rawLength: Int get() = raw.length
    internal val rawText: String get() = raw.toString()

    init {
        isEditable = false
        isOpaque = false
        foreground = OmniCodeUiPalette.primary
        border = JBUI.Borders.empty()
        font = JBFont.label()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1) return
                activateFileReferenceAt(viewToModel2D(event.point))
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                cursor = if (fileReferenceAt(viewToModel2D(event.point)) != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                }
            }
        })
        // Keep references usable without a mouse as well.  This mirrors the
        // editor's normal "place the caret on a symbol and press Enter"
        // workflow and is especially useful in the narrow tool window.
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode != KeyEvent.VK_ENTER && event.keyCode != KeyEvent.VK_SPACE) return
                if (activateFileReferenceAtCaret()) {
                    event.consume()
                }
            }
        })
    }

    fun appendRaw(value: String) {
        if (value.isEmpty()) return
        if (formatted) {
            formatted = false
            simplifiedLargeOutput = false
            raw.append(value)
            renderPlain()
            return
        }
        raw.append(value)
        styledDocument.insertString(styledDocument.length, value, plainAttributes())
    }

    fun setRawText(value: String) {
        raw.setLength(0)
        raw.append(value)
        formatted = false
        simplifiedLargeOutput = false
        renderPlain()
    }

    fun finalizeMarkdown() {
        if (formatted) return
        formatted = true
        simplifiedLargeOutput = allowBareFileReferences || raw.length > MAX_SYNCHRONOUS_MARKDOWN_CHARACTERS
        if (simplifiedLargeOutput) {
            applyFileReferencesToPlainDocument()
        } else {
            LightweightMarkdownRenderer.render(raw.toString(), styledDocument, font)
        }
        revalidate()
        repaint()
    }

    fun trimStart(characters: Int) {
        if (characters <= 0 || raw.isEmpty()) return
        raw.delete(0, characters.coerceAtMost(raw.length))
        if (formatted && !simplifiedLargeOutput) {
            LightweightMarkdownRenderer.render(raw.toString(), styledDocument, font)
        } else {
            formatted = false
            simplifiedLargeOutput = false
            renderPlain()
        }
    }

    internal fun fileReferenceAt(offset: Int): ToolFileReference? {
        if (offset !in 0 until styledDocument.length) return null
        return styledDocument.getCharacterElement(offset).attributes
            .getAttribute(PROJECT_FILE_REFERENCE_ATTRIBUTE) as? ToolFileReference
    }

    internal fun activateFileReferenceAt(offset: Int): Boolean {
        val reference = fileReferenceAt(offset) ?: return false
        onOpenFile(reference)
        return true
    }

    internal fun activateFileReferenceAtCaret(): Boolean {
        if (styledDocument.length == 0) return false
        return activateFileReferenceAt(caretPosition.coerceIn(0, styledDocument.length - 1))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun renderPlain() {
        styledDocument.remove(0, styledDocument.length)
        styledDocument.insertString(0, raw.toString(), plainAttributes())
    }

    private fun applyFileReferencesToPlainDocument() {
        val references = buildList {
            addAll(projectFileReferenceSpans(raw.toString()))
            if (allowBareFileReferences) addAll(projectBareFileReferenceSpans(raw.toString()))
        }
            .distinctBy { it.startOffset }
            .sortedBy(ToolFileReferenceSpan::startOffset)
        references
            .take(MAX_LARGE_OUTPUT_FILE_REFERENCES)
            .forEach { span ->
                val link = SimpleAttributeSet().apply {
                    StyleConstants.setForeground(this, OmniCodeUiPalette.timelineLink)
                    StyleConstants.setUnderline(this, true)
                    addAttribute(PROJECT_FILE_REFERENCE_ATTRIBUTE, span.reference)
                }
                styledDocument.setCharacterAttributes(
                    span.startOffset,
                    span.endOffsetExclusive - span.startOffset,
                    link,
                    false,
                )
            }
    }

    private fun plainAttributes(): SimpleAttributeSet = SimpleAttributeSet().apply {
        StyleConstants.setFontFamily(this, font.family)
        StyleConstants.setFontSize(this, font.size)
        StyleConstants.setForeground(this, OmniCodeUiPalette.primary)
    }
}

private object LightweightMarkdownRenderer {
    fun render(source: String, document: StyledDocument, font: Font) {
        document.remove(0, document.length)
        val base = attributes(font)
        val code = SimpleAttributeSet(base).apply {
            StyleConstants.setFontFamily(this, Font.MONOSPACED)
            StyleConstants.setBackground(this, OmniCodeUiPalette.codeBackground)
        }
        var codeBlock = false
        var hasOutputLine = false

        source.split('\n').forEach { originalLine ->
            val trimmed = originalLine.trimStart()
            if (trimmed.startsWith("```")) {
                codeBlock = !codeBlock
                return@forEach
            }
            if (hasOutputLine) append(document, "\n", if (codeBlock) code else base)
            hasOutputLine = true

            when {
                codeBlock -> append(document, originalLine, code)
                trimmed == "---" || trimmed == "***" -> append(document, "", base)
                headingLevel(originalLine) > 0 -> {
                    val level = headingLevel(originalLine)
                    val heading = SimpleAttributeSet(base).apply {
                        StyleConstants.setBold(this, true)
                        StyleConstants.setFontSize(this, font.size + when (level) {
                            1 -> 4
                            2 -> 2
                            else -> 1
                        })
                    }
                    appendInline(document, originalLine.drop(level + 1), heading, code)
                }
                originalLine.startsWith("- ") || originalLine.startsWith("* ") -> {
                    append(document, "• ", SimpleAttributeSet(base).apply {
                        StyleConstants.setForeground(this, OmniCodeUiPalette.secondary)
                    })
                    appendInline(document, originalLine.drop(2), base, code)
                }
                originalLine.startsWith("> ") -> {
                    val quote = SimpleAttributeSet(base).apply {
                        StyleConstants.setItalic(this, true)
                        StyleConstants.setForeground(this, OmniCodeUiPalette.secondary)
                    }
                    append(document, "│ ", SimpleAttributeSet(quote).apply {
                        StyleConstants.setForeground(this, OmniCodeUiPalette.accent)
                    })
                    appendInline(document, originalLine.drop(2), quote, code)
                }
                else -> appendInline(document, originalLine, base, code)
            }
        }
    }

    private fun appendInline(
        document: StyledDocument,
        value: String,
        base: SimpleAttributeSet,
        code: SimpleAttributeSet,
    ) {
        var cursor = 0
        var plainStart = 0
        while (cursor < value.length) {
            val marker = when {
                value.startsWith("**", cursor) -> "**"
                value[cursor] == '`' -> "`"
                value[cursor] == '*' -> "*"
                else -> null
            }
            if (marker == null) {
                cursor++
                continue
            }
            val end = value.indexOf(marker, cursor + marker.length)
            if (end < 0) {
                cursor += marker.length
                continue
            }
            appendWithFileReferences(document, value.substring(plainStart, cursor), base)
            val marked = value.substring(cursor + marker.length, end)
            val style = when (marker) {
                "**" -> SimpleAttributeSet(base).apply { StyleConstants.setBold(this, true) }
                "*" -> SimpleAttributeSet(base).apply { StyleConstants.setItalic(this, true) }
                else -> code
            }
            appendWithFileReferences(document, marked, style)
            cursor = end + marker.length
            plainStart = cursor
        }
        appendWithFileReferences(document, value.substring(plainStart), base)
    }

    private fun appendWithFileReferences(
        document: StyledDocument,
        value: String,
        attributes: SimpleAttributeSet,
    ) {
        var cursor = 0
        projectFileReferenceSpans(value).forEach { span ->
            append(document, value.substring(cursor, span.startOffset), attributes)
            val link = SimpleAttributeSet(attributes).apply {
                StyleConstants.setForeground(this, OmniCodeUiPalette.timelineLink)
                StyleConstants.setUnderline(this, true)
                addAttribute(PROJECT_FILE_REFERENCE_ATTRIBUTE, span.reference)
            }
            append(document, value.substring(span.startOffset, span.endOffsetExclusive), link)
            cursor = span.endOffsetExclusive
        }
        append(document, value.substring(cursor), attributes)
    }

    private fun append(document: StyledDocument, value: String, attributes: SimpleAttributeSet) {
        if (value.isNotEmpty()) document.insertString(document.length, value, attributes)
    }

    private fun attributes(font: Font): SimpleAttributeSet = SimpleAttributeSet().apply {
        StyleConstants.setFontFamily(this, font.family)
        StyleConstants.setFontSize(this, font.size)
        StyleConstants.setForeground(this, OmniCodeUiPalette.primary)
    }

    private fun headingLevel(value: String): Int {
        val markers = value.takeWhile { it == '#' }.length
        return markers.takeIf { it in 1..3 && value.getOrNull(it) == ' ' } ?: 0
    }
}

private fun readableToolSummary(value: String): String {
    val trimmed = value.trim().take(MAX_TOOL_SUMMARY_CHARS)
    if (!trimmed.startsWith('{')) return trimmed.replace("**", "")
    val objectValue = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: return trimmed
    return buildString {
        objectValue.entrySet().take(MAX_TOOL_SUMMARY_FIELDS).forEachIndexed { index, entry ->
            if (index > 0) appendLine()
            val rendered = when {
                entry.value.isJsonNull -> "null"
                entry.value.isJsonPrimitive -> runCatching { entry.value.asString }.getOrElse { entry.value.toString() }
                else -> entry.value.toString()
            }.replace(Regex("\\s+"), " ").take(MAX_TOOL_SUMMARY_VALUE_CHARS)
            append(entry.key).append(": ").append(rendered)
        }
        if (objectValue.size() > MAX_TOOL_SUMMARY_FIELDS) {
            appendLine()
            append("… ${objectValue.size() - MAX_TOOL_SUMMARY_FIELDS} more")
        }
    }
}

private fun cleanStatus(value: String): String {
    val normalized = value.trim()
        .trimStart { it == '✓' || it == '!' || it == '›' || it == '•' }
        .trim()
    return when {
        normalized.equals("Thinking…", ignoreCase = true) ||
            normalized.equals("Thinking...", ignoreCase = true) -> "思考中"
        normalized.startsWith("Thinking · turn ", ignoreCase = true) ->
            "思考 · ${normalized.substringAfter("turn ")}"
        normalized.equals("Completed", ignoreCase = true) -> "完成"
        normalized.equals("Cancelled", ignoreCase = true) -> "已取消"
        normalized.equals("Failed", ignoreCase = true) -> "失败"
        normalized.startsWith("Token budget", ignoreCase = true) -> "任务已暂停"
        normalized.startsWith("Stopping", ignoreCase = true) -> "正在停止"
        else -> normalized
    }
}

private fun formatElapsed(nanos: Long): String {
    val seconds = (nanos.coerceAtLeast(0L) / 1_000_000_000L)
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3_600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3_600}时${(seconds % 3_600) / 60}分"
    }
}

private fun formatLatency(nanos: Long): String {
    val millis = nanos.coerceAtLeast(0L) / 1_000_000L
    return if (millis < 1_000L) {
        "${millis}ms"
    } else {
        "${millis / 1_000L}.${(millis % 1_000L) / 100L}s"
    }
}

private fun iconButton(icon: javax.swing.Icon, tooltip: String): JButton = JButton(icon).apply {
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = false
    isFocusPainted = true
    border = JBUI.Borders.empty(2, 4)
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    toolTipText = tooltip
    accessibleContext.accessibleName = tooltip
}

private const val MAX_TOOL_SUMMARY_CHARS = 2_000
private const val MAX_TOOL_SUMMARY_FIELDS = 6
private const val MAX_TOOL_SUMMARY_VALUE_CHARS = 280
private const val MAX_PROJECT_FILE_REFERENCE_CHARS = 512
private const val MAX_SYNCHRONOUS_MARKDOWN_CHARACTERS = 80_000
private const val MAX_LARGE_OUTPUT_FILE_REFERENCES = 256
private const val PROJECT_FILE_REFERENCE_ATTRIBUTE = "omnicode.projectFileReference"
private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:[\\\\/]")
private val PROJECT_FILE_REFERENCE_PATTERN = Regex(
    """(?<![\p{L}\p{N}_./\\~:-])((?:[\p{L}\p{N}_@.+~()\-]+[/\\])*[\p{L}\p{N}_@.+~()\-]+)(?:(?::|\s)([0-9]{1,9})(?:[-–—]([0-9]{1,9}))?|#L([0-9]{1,9})(?:[-–—]L?([0-9]{1,9}))?)(?![0-9])""",
)

internal enum class ComposerControlState {
    QUIET,
    SELECTED,
    WARNING,
}

internal enum class ComposerControlForeground {
    PRIMARY,
    ACCENT,
    WARNING,
}

internal data class ComposerControlStyle(
    val logicalHeight: Int,
    val horizontalPadding: Int,
    val cornerArc: Int,
    val paintsBackgroundAtRest: Boolean,
    val paintsOutlineAtRest: Boolean,
    val foreground: ComposerControlForeground,
)

internal fun composerControlStyle(state: ComposerControlState): ComposerControlStyle = ComposerControlStyle(
    logicalHeight = 32,
    horizontalPadding = 9,
    cornerArc = 10,
    paintsBackgroundAtRest = state != ComposerControlState.QUIET,
    paintsOutlineAtRest = state == ComposerControlState.WARNING,
    foreground = when (state) {
        ComposerControlState.QUIET -> ComposerControlForeground.PRIMARY
        ComposerControlState.SELECTED -> ComposerControlForeground.PRIMARY
        ComposerControlState.WARNING -> ComposerControlForeground.WARNING
    },
)

internal class ComposerControlButton(
    text: String,
    tooltip: String? = null,
    initialState: ComposerControlState = ComposerControlState.QUIET,
) : JButton(text) {
    var controlState: ComposerControlState = initialState
        set(value) {
            if (field == value) return
            field = value
            applySemanticForeground()
            repaint()
        }

    init {
        val style = composerControlStyle(initialState)
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = true
        isRolloverEnabled = true
        font = JBFont.small()
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        horizontalTextPosition = SwingConstants.TRAILING
        verticalTextPosition = SwingConstants.CENTER
        iconTextGap = JBUI.scale(4)
        margin = java.awt.Insets(0, 0, 0, 0)
        border = JBUI.Borders.empty(0, style.horizontalPadding)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = tooltip
        getAccessibleContext()?.accessibleName = tooltip ?: text
        alignmentY = CENTER_ALIGNMENT
        applySemanticForeground()
    }

    override fun getPreferredSize(): Dimension {
        val style = composerControlStyle(controlState)
        val height = JBUI.scale(style.logicalHeight)
        val currentIcon = icon
        val label = text.orEmpty()
        if (label.isBlank() && currentIcon != null) return Dimension(height, height)

        val textWidth = if (label.isBlank()) 0 else getFontMetrics(font).stringWidth(label)
        val iconWidth = currentIcon?.iconWidth ?: 0
        val gap = if (textWidth > 0 && iconWidth > 0) iconTextGap else 0
        val contentWidth = textWidth + iconWidth + gap + JBUI.scale(style.horizontalPadding * 2)
        return Dimension(contentWidth.coerceAtLeast(height), height)
    }

    override fun getMinimumSize(): Dimension = Dimension(
        JBUI.scale(composerControlStyle(controlState).logicalHeight),
        preferredSize.height,
    )

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val style = composerControlStyle(controlState)
        val fill = when {
            model.isPressed -> OmniCodeUiPalette.controlPressed
            !style.paintsBackgroundAtRest && model.isRollover -> OmniCodeUiPalette.controlHover
            !style.paintsBackgroundAtRest -> null
            controlState == ComposerControlState.SELECTED -> OmniCodeUiPalette.controlSelected
            else -> OmniCodeUiPalette.controlWarning
        }
        if (fill != null) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = fill
                val arc = JBUI.scale(style.cornerArc)
                g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g.dispose()
            }
        }
        super.paintComponent(graphics)
    }

    override fun paintBorder(graphics: Graphics) {
        val style = composerControlStyle(controlState)
        val outline = when {
            !isEnabled -> null
            controlState == ComposerControlState.WARNING -> OmniCodeUiPalette.warning
            style.paintsOutlineAtRest || hasFocus() -> OmniCodeUiPalette.accent
            else -> null
        } ?: return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = outline
            g.stroke = BasicStroke(JBUI.scale(if (hasFocus()) 2 else 1).toFloat())
            val inset = if (hasFocus()) JBUI.scale(1) else 0
            val arc = JBUI.scale(style.cornerArc)
            g.drawRoundRect(inset, inset, width - 1 - inset * 2, height - 1 - inset * 2, arc, arc)
        } finally {
            g.dispose()
        }
    }

    private fun applySemanticForeground() {
        foreground = when (composerControlStyle(controlState).foreground) {
            ComposerControlForeground.PRIMARY -> OmniCodeUiPalette.primary
            ComposerControlForeground.ACCENT -> OmniCodeUiPalette.accent
            ComposerControlForeground.WARNING -> OmniCodeUiPalette.warning
        }
    }
}

internal fun composerControlButton(
    text: String,
    tooltip: String? = null,
    state: ComposerControlState = ComposerControlState.QUIET,
): ComposerControlButton = ComposerControlButton(text, tooltip, state)

internal fun flatButton(text: String, tooltip: String? = null): JButton = object : JButton(text) {
    override fun getPreferredSize(): Dimension {
        val height = JBUI.scale(28)
        val labelWidth = getFontMetrics(font).stringWidth(this.text.orEmpty())
        val iconWidth = icon?.iconWidth ?: 0
        val gap = if (labelWidth > 0 && iconWidth > 0) iconTextGap else 0
        return Dimension((labelWidth + iconWidth + gap + JBUI.scale(10)).coerceAtLeast(height), height)
    }

    override fun getMaximumSize(): Dimension = preferredSize
}.apply {
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = false
    isFocusPainted = true
    foreground = OmniCodeUiPalette.primary
    border = JBUI.Borders.empty(3, 5)
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    toolTipText = tooltip
    accessibleContext.accessibleName = tooltip ?: text
}

internal fun footerRow(vararg components: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
    isOpaque = false
    components.forEachIndexed { index, component ->
        if (index > 0) {
            val previous = components[index - 1]
            add(object : JBLabel("  ·  ") {
                override fun isVisible(): Boolean = super.isVisible() && previous.isVisible
            }.apply { foreground = OmniCodeUiPalette.secondary })
        }
        add(component)
    }
}

internal fun humanizeToolName(name: String): String = when (name) {
    "list_files" -> "浏览文件"
    "read_file" -> "读取文件"
    "search_text" -> "搜索代码"
    "list_ide_problems" -> "读取 IDE 问题"
    "apply_change" -> "修改文件"
    "apply_patch" -> "精确修改"
    "run_command" -> "运行命令"
    "list_skills" -> "浏览 Skill"
    "load_skill" -> "加载 Skill"
    else -> name
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
