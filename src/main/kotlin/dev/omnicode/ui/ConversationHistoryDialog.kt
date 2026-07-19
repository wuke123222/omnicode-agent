package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.SnapshotRole
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class ConversationHistoryDialog(
    private val project: Project,
    records: List<ConversationRecord>,
    private val deleteConversation: (String, (Boolean) -> Unit) -> Unit,
) : DialogWrapper(project) {
    private val allRecords = records.toMutableList()
    private val model = DefaultListModel<ConversationRecord>()
    private val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search titles or projects"
    }
    private val resultCount = JBLabel().apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private val listCards = CardLayout()
    private val listContainer = JPanel(listCards)
    private val listEmpty = emptyState("No conversations", "Saved conversations will appear here.")
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(58)
        cellRenderer = ConversationCellRenderer()
        border = JBUI.Borders.empty(3)
    }
    private val previewCards = CardLayout()
    private val previewContainer = JPanel(previewCards)
    private val previewContent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(16)
    }
    private val previewScroll = JBScrollPane(previewContent).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        verticalScrollBar.unitIncrement = JBUI.scale(18)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val previewEmpty = emptyState(
        "Select a conversation",
        "Choose an item on the left to preview its messages.",
    )
    private val deleteButton = JButton("Delete", AllIcons.General.Delete).apply {
        foreground = OmniCodeUiPalette.error
        toolTipText = "Permanently delete the selected conversation"
        accessibleContext.accessibleName = toolTipText
    }
    private val actionStatus = JBLabel().apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }

    val selectedConversationId: String?
        get() = list.selectedValue?.id

    init {
        title = "OmniCode History"
        setOKButtonText("Open")
        listContainer.isOpaque = false
        listContainer.add(JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }, LIST_CARD)
        listContainer.add(listEmpty, EMPTY_CARD)
        previewContainer.isOpaque = false
        previewContainer.add(previewScroll, PREVIEW_CARD)
        previewContainer.add(previewEmpty, EMPTY_CARD)

        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = applyFilter()
            override fun removeUpdate(event: DocumentEvent) = applyFilter()
            override fun changedUpdate(event: DocumentEvent) = applyFilter()
        })
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) updatePreview()
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2 && list.selectedValue != null) {
                    doOKAction()
                }
            }
        })
        deleteButton.addActionListener { deleteSelected() }

        applyFilter()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(330), 0)
            border = JBUI.Borders.empty(12, 12, 8, 10)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel("Conversations").apply {
                    foreground = OmniCodeUiPalette.primary
                    font = JBFont.label().asBold()
                }, BorderLayout.WEST)
                add(resultCount, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(2)
                add(search, BorderLayout.NORTH)
                add(listContainer, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(1)
            add(previewContainer, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(560))
            add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
                resizeWeight = 0.36
                dividerSize = JBUI.scale(1)
                isContinuousLayout = true
                border = JBUI.Borders.customLine(OmniCodeUiPalette.border, 1, 0, 0, 0)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(8)
                add(deleteButton, BorderLayout.WEST)
                add(actionStatus, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun getDimensionServiceKey(): String = "OmniCode.History"

    private fun applyFilter(preferredId: String? = selectedConversationId) {
        val query = search.text.trim()
        val filtered = allRecords.filter { record ->
            query.isBlank() ||
                record.title.contains(query, ignoreCase = true) ||
                record.projectId.contains(query, ignoreCase = true)
        }
        model.clear()
        filtered.forEach(model::addElement)
        resultCount.text = if (query.isBlank()) "${filtered.size}" else "${filtered.size} of ${allRecords.size}"

        if (filtered.isEmpty()) {
            listEmpty.removeAll()
            listEmpty.layout = GridBagLayout()
            listEmpty.add(emptyStateContent(
                if (allRecords.isEmpty()) "No conversations" else "No matches",
                if (allRecords.isEmpty()) {
                    "Saved conversations will appear here."
                } else {
                    "Try a different title or project."
                },
            ))
            listEmpty.revalidate()
            listCards.show(listContainer, EMPTY_CARD)
            list.clearSelection()
        } else {
            listCards.show(listContainer, LIST_CARD)
            val selectedIndex = preferredId
                ?.let { id -> filtered.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?: 0
            list.selectedIndex = selectedIndex
            list.ensureIndexIsVisible(selectedIndex)
        }
        updatePreview()
    }

    private fun updatePreview() {
        val record = list.selectedValue
        deleteButton.isEnabled = record != null
        isOKActionEnabled = record != null
        if (record == null) {
            previewCards.show(previewContainer, EMPTY_CARD)
            return
        }

        previewContent.removeAll()
        previewContent.add(GrowingTextArea(record.title.ifBlank { "Untitled conversation" }).apply {
            foreground = OmniCodeUiPalette.primary
            font = JBFont.label().asBold().deriveFont(JBFont.label().size2D + 2f)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        previewContent.add(Box.createVerticalStrut(JBUI.scale(5)))
        previewContent.add(JBLabel(
            "${record.projectId}  ·  Updated ${DATE_FORMAT.format(record.updatedAt.atZone(ZoneId.systemDefault()))}" +
                "  ·  ${record.messages.size} messages",
        ).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            alignmentX = Component.LEFT_ALIGNMENT
        })
        previewContent.add(Box.createVerticalStrut(JBUI.scale(14)))

        val messages = record.messages.filter { it.role != SnapshotRole.SYSTEM }
        val visibleMessages = messages.takeLast(MAX_PREVIEW_MESSAGES)
        if (messages.size > visibleMessages.size) {
            previewContent.add(JBLabel("${messages.size - visibleMessages.size} earlier messages omitted").apply {
                icon = AllIcons.General.Warning
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            previewContent.add(Box.createVerticalStrut(JBUI.scale(9)))
        }
        if (visibleMessages.isEmpty()) {
            previewContent.add(emptyStateContent("No messages", "This conversation has no previewable messages."))
        } else {
            visibleMessages.forEachIndexed { index, message ->
                if (index > 0) previewContent.add(Box.createVerticalStrut(JBUI.scale(9)))
                previewContent.add(RoleMessageCard(message))
            }
        }

        previewContent.revalidate()
        previewContent.repaint()
        previewCards.show(previewContainer, PREVIEW_CARD)
        SwingUtilities.invokeLater {
            previewScroll.verticalScrollBar.value = previewScroll.verticalScrollBar.minimum
        }
    }

    private fun deleteSelected() {
        val selected = list.selectedValue ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            "Delete “${selected.title.ifBlank { "Untitled conversation" }}”? This cannot be undone.",
            "Delete Conversation",
            "Delete",
            "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        deleteButton.isEnabled = false
        deleteButton.text = "Deleting…"
        actionStatus.text = ""
        deleteConversation(selected.id) callback@{ deleted ->
            if (isDisposed) return@callback
            deleteButton.text = "Delete"
            if (deleted) {
                allRecords.removeAll { it.id == selected.id }
                actionStatus.text = "Conversation deleted"
                actionStatus.foreground = OmniCodeUiPalette.secondary
                applyFilter(preferredId = null)
            } else {
                deleteButton.isEnabled = list.selectedValue != null
                actionStatus.text = "Unable to delete conversation"
                actionStatus.foreground = OmniCodeUiPalette.error
            }
        }
    }

    private class ConversationCellRenderer : JPanel(BorderLayout()), ListCellRenderer<ConversationRecord> {
        private val titleLabel = JBLabel().apply { font = JBFont.label().asBold() }
        private val metaLabel = JBLabel().apply { font = JBFont.small() }

        init {
            isOpaque = true
            border = JBUI.Borders.empty(7, 9)
            add(titleLabel, BorderLayout.NORTH)
            add(metaLabel, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(
            list: JList<out ConversationRecord>,
            value: ConversationRecord,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            background = if (isSelected) list.selectionBackground else list.background
            titleLabel.foreground = if (isSelected) list.selectionForeground else OmniCodeUiPalette.primary
            metaLabel.foreground = if (isSelected) list.selectionForeground else OmniCodeUiPalette.secondary
            titleLabel.text = value.title.ifBlank { "Untitled conversation" }
            metaLabel.text = "${DATE_FORMAT.format(value.updatedAt.atZone(ZoneId.systemDefault()))}" +
                "  ·  ${value.messages.size} messages"
            toolTipText = buildString {
                append(value.title.ifBlank { "Untitled conversation" })
                if (value.projectId.isNotBlank()) append(" · ").append(value.projectId)
            }
            return this
        }
    }

    private class RoleMessageCard(message: MessageSnapshot) : RoundedSurfacePanel(
        fillColor = if (message.role == SnapshotRole.USER) {
            OmniCodeUiPalette.userBubble
        } else {
            OmniCodeUiPalette.surface
        },
        outlineColor = OmniCodeUiPalette.border,
        radius = 10,
    ) {
        init {
            layout = BorderLayout(0, JBUI.scale(5))
            border = JBUI.Borders.empty(8, 10)
            val role = when {
                message.toolName != null -> "Tool · ${message.toolName}"
                message.role == SnapshotRole.USER -> "User"
                message.role == SnapshotRole.ASSISTANT -> "Assistant"
                message.role == SnapshotRole.TOOL -> "Tool"
                else -> "System"
            }
            val icon = when {
                message.isError -> AllIcons.General.Error
                message.toolName != null || message.role == SnapshotRole.TOOL -> AllIcons.General.GearPlain
                message.role == SnapshotRole.USER -> AllIcons.General.User
                else -> AllIcons.Actions.Lightning
            }
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel(role, icon, JBLabel.LEADING).apply {
                    foreground = if (message.isError) OmniCodeUiPalette.error else OmniCodeUiPalette.primary
                    font = JBFont.small().asBold()
                }, BorderLayout.WEST)
                add(JBLabel(TIME_FORMAT.format(message.recordedAt.atZone(ZoneId.systemDefault()))).apply {
                    foreground = OmniCodeUiPalette.secondary
                    font = JBFont.small()
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(GrowingTextArea(previewText(message.text)).apply {
                if (message.isError) foreground = OmniCodeUiPalette.error
            }, BorderLayout.CENTER)
        }
    }

    private companion object {
        const val LIST_CARD = "list"
        const val PREVIEW_CARD = "preview"
        const val EMPTY_CARD = "empty"
        const val MAX_PREVIEW_MESSAGES = 100
        const val MAX_MESSAGE_PREVIEW_CHARS = 12_000
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun previewText(value: String): String =
            if (value.length <= MAX_MESSAGE_PREVIEW_CHARS) value
            else value.take(MAX_MESSAGE_PREVIEW_CHARS) + "\\n[message preview truncated]"

        fun emptyState(title: String, detail: String): JPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(emptyStateContent(title, detail))
        }

        fun emptyStateContent(title: String, detail: String): JComponent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(title).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = OmniCodeUiPalette.primary
                font = JBFont.label().asBold()
            })
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(JBLabel(detail).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
            })
        }
    }
}
