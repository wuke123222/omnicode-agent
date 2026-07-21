package dev.omnicode.ui

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
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
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.ImageIcon
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
) : StretchPanel(BorderLayout()) {
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
    private val completionRow = StretchPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(7)
        add(completionIcon, BorderLayout.WEST)
        add(completionLabel, BorderLayout.CENTER)
        add(completionDuration, BorderLayout.EAST)
        isVisible = false
    }
    private val recoveryRow = StretchPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
        isVisible = false
    }
    private val startedAtNanos = System.nanoTime()
    private val durationTimer = Timer(1_000) {
        currentStage?.updateElapsed(System.nanoTime())
    }.apply {
        initialDelay = 1_000
        start()
    }
    private var currentStage: StageSummaryRow? = null
    private var activeText: LightweightMarkdownPane? = null
    private val textBlocks = mutableListOf<LightweightMarkdownPane>()
    private val pendingToolsById = linkedMapOf<String, ToolCallCard>()
    private val pendingToolsWithoutId = mutableListOf<ToolCallCard>()
    private val completedToolIds = mutableSetOf<String>()
    private var delegateProgress: MultiAgentProgressCard? = null
    private var visibleTextCharacters = 0
    private var finished = false

    init {
        isOpaque = false

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        stack.add(StretchPanel(BorderLayout()).apply {
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
    }

    fun appendText(value: String) {
        if (value.isEmpty()) return
        val area = activeText ?: LightweightMarkdownPane().also {
            finishCurrentStage()
            addContent(it, topGap = if (content.componentCount > 0) 7 else 0)
            activeText = it
            textBlocks += it
        }
        area.appendRaw(value)
        visibleTextCharacters += value.length
        trimVisibleText()
        area.revalidate()
        refreshLayout()
    }

    fun startTool(name: String, summary: String, callId: String = ""): ToolCallCard {
        finishCurrentStage()
        activeText = null
        if (callId.isNotBlank()) pendingToolsById[callId]?.let { return it }
        val card = ToolCallCard(name, summary, callId, onOpenFile)
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
                addContent(it, topGap = if (content.componentCount > 0) 7 else 0)
            }
        card.complete(result, isError, cancelled)
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
        metaLabel.text = "${java.text.NumberFormat.getIntegerInstance().format(tokens)} tokens"
        metaLabel.isVisible = true
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

        completionIcon.icon = if (isError) AllIcons.General.Error else AllIcons.General.GreenCheckmark
        completionLabel.text = cleanStatus(label)
        completionLabel.foreground = if (isError) OmniCodeUiPalette.error else OmniCodeUiPalette.secondary
        completionDuration.text = formatElapsed(System.nanoTime() - startedAtNanos)
        completionRow.isVisible = true
        refreshLayout()
    }

    fun showRecoveryAction(
        label: String,
        tooltip: String,
        icon: Icon = AllIcons.Actions.Edit,
        action: () -> Unit,
    ) {
        recoveryRow.removeAll()
        recoveryRow.add(flatButton(label, tooltip).apply {
            this.icon = icon
            addActionListener { action() }
        })
        recoveryRow.isVisible = true
        refreshLayout()
    }

    fun clearRecoveryAction() {
        recoveryRow.removeAll()
        recoveryRow.isVisible = false
        refreshLayout()
    }

    private fun finishCurrentStage() {
        currentStage?.finish()
        currentStage = null
    }

    private fun addContent(component: JComponent, topGap: Int) {
        if (topGap > 0) content.add(Box.createVerticalStrut(JBUI.scale(topGap)))
        component.alignmentX = LEFT_ALIGNMENT
        content.add(StretchPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(13)
            add(component, BorderLayout.CENTER)
        })
        refreshLayout()
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
        omissionLabel.isVisible = true
    }

    private fun refreshLayout() {
        revalidate()
        repaint()
        parent?.revalidate()
    }

    private companion object {
        const val MAX_VISIBLE_TEXT_CHARACTERS = 320_000
        const val TEXT_TRIM_BUFFER = 8_000
    }
}

internal fun assistantTurnModeLabel(mode: AgentMode?): String = when (mode) {
    AgentMode.AGENT -> "Agent"
    AgentMode.PLAN -> "Plan"
    AgentMode.RESEARCH -> "Research"
    null -> "历史"
}

internal data class StagePresentation(
    val key: String,
    val runningText: String,
    val completedText: String,
)

internal fun stagePresentation(message: String): StagePresentation? {
    val normalized = cleanStatus(message)
    if (normalized.isBlank()) return null
    return when {
        normalized.startsWith("思考") -> StagePresentation("thinking", "思考中", "思考了")
        normalized.startsWith("Provider temporarily unavailable", ignoreCase = true) ->
            StagePresentation("provider-retry", "正在重试模型连接", "模型连接已重试")
        normalized.startsWith("正在通过") && normalized.endsWith("识别图片…") ->
            StagePresentation("vision", normalized, "图片识别完成")
        normalized == "运行中" || normalized.contains("模式 · 已锁定") ||
            normalized == "执行任务" || normalized == "制定计划" -> null
        else -> StagePresentation(normalized, normalized, normalized)
    }
}

private class StageSummaryRow(
    private val presentation: StagePresentation,
) : StretchPanel(BorderLayout(JBUI.scale(7), 0)) {
    val key: String get() = presentation.key
    private val startedAtNanos = System.nanoTime()
    private var completedAtNanos: Long? = null
    private val state = JBLabel().apply { icon = AnimatedIcon.Default() }
    private val label = JBLabel(presentation.runningText).apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2)
        add(state, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
    }

    fun updateElapsed(nowNanos: Long) {
        if (completedAtNanos == null) {
            label.text = "${presentation.runningText} · ${formatElapsed(nowNanos - startedAtNanos)}"
        }
    }

    fun finish() {
        if (completedAtNanos != null) return
        completedAtNanos = System.nanoTime()
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
    private val resultArea = LightweightMarkdownPane()
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
            val command = data?.getAsJsonArray("argv")?.joinToString(" ") { element ->
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

internal class ExecutionNavigationBar : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.timelineElevated,
    outlineColor = OmniCodeUiPalette.timelineBorder,
    radius = 8,
) {
    private val tasks = navigationItem("☷", "任务", selected = true)
    private val subagents = navigationItem("◉", "子代理", selected = false)
    private val edits = navigationItem("✎", "编辑", selected = false)

    init {
        layout = GridLayout(1, 3)
        border = JBUI.Borders.empty(3, 4)
        preferredSize = Dimension(0, JBUI.scale(36))
        minimumSize = Dimension(0, JBUI.scale(36))
        add(tasks)
        add(navDivider(subagents))
        add(navDivider(edits))
        accessibleContext?.accessibleName = "执行视图导航"
    }

    fun updateCounts(toolCount: Int, subagentCount: Int, editCount: Int, running: Boolean) {
        tasks.text = "☷  ${navigationText("任务", toolCount)}"
        subagents.text = "◉  ${navigationText("子代理", subagentCount)}"
        edits.text = "✎  ${navigationText("编辑", editCount)}"
        tasks.toolTipText = if (running) "当前任务正在运行" else "当前任务执行记录"
        subagents.toolTipText = "本次任务的子代理数量：$subagentCount"
        edits.toolTipText = "本次任务的文件修改数量：$editCount"
        listOf(tasks, subagents, edits).forEach { it.accessibleContext?.accessibleName = it.toolTipText }
    }

    private fun navigationItem(icon: String, label: String, selected: Boolean): JBLabel = JBLabel("$icon  $label").apply {
        horizontalAlignment = SwingConstants.CENTER
        font = JBFont.label().asBold()
        foreground = if (selected) OmniCodeUiPalette.primary else OmniCodeUiPalette.timelineMuted
    }

    private fun navDivider(component: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.customLine(OmniCodeUiPalette.timelineBorder, 0, 1, 0, 0)
        add(component, BorderLayout.CENTER)
    }
}

internal fun navigationText(label: String, count: Int): String = if (count > 0) "$label  $count" else label

/**
 * A safe, dependency-free renderer for the small Markdown subset commonly emitted by models.
 * Streaming remains append-only and cheap; formatting is applied once the block is complete.
 */
private class LightweightMarkdownPane : JTextPane() {
    private val raw = StringBuilder()
    private var formatted = false

    val rawLength: Int get() = raw.length

    init {
        isEditable = false
        isOpaque = false
        foreground = OmniCodeUiPalette.primary
        border = JBUI.Borders.empty()
        font = JBFont.label()
    }

    fun appendRaw(value: String) {
        if (value.isEmpty()) return
        if (formatted) {
            formatted = false
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
        renderPlain()
    }

    fun finalizeMarkdown() {
        if (formatted) return
        formatted = true
        LightweightMarkdownRenderer.render(raw.toString(), styledDocument, font)
        revalidate()
        repaint()
    }

    fun trimStart(characters: Int) {
        if (characters <= 0 || raw.isEmpty()) return
        raw.delete(0, characters.coerceAtMost(raw.length))
        if (formatted) {
            LightweightMarkdownRenderer.render(raw.toString(), styledDocument, font)
        } else {
            renderPlain()
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun renderPlain() {
        styledDocument.remove(0, styledDocument.length)
        styledDocument.insertString(0, raw.toString(), plainAttributes())
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
            append(document, value.substring(plainStart, cursor), base)
            val marked = value.substring(cursor + marker.length, end)
            val style = when (marker) {
                "**" -> SimpleAttributeSet(base).apply { StyleConstants.setBold(this, true) }
                "*" -> SimpleAttributeSet(base).apply { StyleConstants.setItalic(this, true) }
                else -> code
            }
            append(document, marked, style)
            cursor = end + marker.length
            plainStart = cursor
        }
        append(document, value.substring(plainStart), base)
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
        normalized.startsWith("Token budget", ignoreCase = true) -> "已达到 Token 预算"
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
