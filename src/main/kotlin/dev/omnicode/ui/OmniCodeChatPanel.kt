package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentEngine
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunResult
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.agent.AgentRole
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.UserAttachment
import dev.omnicode.model.UserSubmission
import dev.omnicode.service.AgentRunCallbacks
import dev.omnicode.service.OmniCodeProjectService
import dev.omnicode.service.ProviderModelCatalog
import dev.omnicode.service.ProviderModelCatalogService
import dev.omnicode.service.ProviderStatus
import dev.omnicode.service.ReproducibleResearchPackageExporter
import dev.omnicode.service.ResearchPackageExportRequest
import dev.omnicode.provider.ProviderPreset
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.classifyModelCatalogKind
import dev.omnicode.provider.modelCatalogView
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.OmniCodeSettingsService
import dev.omnicode.settings.SandboxMode
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import dev.omnicode.ui.workshop.DesktopPetPanel
import dev.omnicode.ui.workshop.DesktopPetState
import dev.omnicode.ui.workshop.WorkshopUiColors
import dev.omnicode.ui.workshop.toDesktopPetAppearance
import dev.omnicode.ui.workshop.toWorkspaceColors
import dev.omnicode.workshop.ResolvedWorkshopSelection
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.KeyEvent
import java.awt.datatransfer.DataFlavor
import java.awt.Image
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.awt.geom.Path2D
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JLayeredPane
import javax.swing.JFileChooser
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.Icon
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal class OmniCodeChatPanel(
    private val project: Project,
    private val service: OmniCodeProjectService,
    private val settingsNavigator: (OmniCodeSettingsPage) -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val conversation = ConversationColumn()
    private val conversationScroll = JBScrollPane(conversation).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        viewport.background = OmniCodeUiPalette.canvas
        verticalScrollBar.unitIncrement = JBUI.scale(18)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val bodyLayout = CardLayout()
    private val bodyCards = JPanel(bodyLayout).apply {
        isOpaque = false
    }
    private val desktopPet = DesktopPetPanel(initiallyEnabled = false).apply { isVisible = false }
    private val bodyWithPet = ChatPetLayer(bodyCards, desktopPet)
    private val petSettleTimer = Timer(PET_TERMINAL_STATE_MS) {
        desktopPet.state = DesktopPetState.IDLE
    }.apply { isRepeats = false }
    private val input = PromptTextArea("输入任务，使用 @ 引用文件，或输入 ! 选择提示词…").apply {
        toolTipText = "可粘贴截图，或拖入 PDF 论文、Notebook、图片、科研资料和代码"
    }
    private val attachments = mutableListOf<UserAttachment>()
    private val attachmentSourceKeys = linkedMapOf<String, UserAttachment>()
    private val pendingAttachmentSourceKeys = mutableSetOf<String>()
    private val attachmentTray = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 4, 0)
    }
    private val attachmentTrayScroll = JBScrollPane(attachmentTray).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBar.unitIncrement = JBUI.scale(20)
        preferredSize = Dimension(0, JBUI.scale(50))
        accessibleContext.accessibleName = "待发送附件"
        isVisible = false
    }
    private val attachmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clipboardAttachmentSemaphore = Semaphore(1)
    private var attachmentDraftGeneration = 0
    private var pendingAttachmentBatches = 0
    private var reservedAttachmentSlots = 0
    private var researchExportInProgress = false
    private lateinit var composerCard: RoundedSurfacePanel
    private var activeDropAttachedObject: Any? = null
    private var activeDropPaths: List<Path> = emptyList()
    private val targetButton = flatButton("未连接模型 · 配置…", "选择模型或配置 API Key").apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
    }
    private val addButton = composerControlButton("", "上传附件（也可从桌面或项目树拖入）").apply {
        icon = AllIcons.General.Add
        accessibleContext.accessibleName = "上传附件"
        accessibleContext.accessibleDescription = "选择、粘贴或拖入 PDF 论文、图片、Markdown、Notebook、科研资料、代码和安全文本文件"
    }
    private val modeButton = composerControlButton(
        "Agent",
        "选择 Agent / Plan / Research 模式（Cmd/Ctrl+Shift+M 循环）",
        ComposerControlState.SELECTED,
    ).apply {
        isFocusPainted = true
        accessibleContext.accessibleName = "运行模式：Agent"
    }
    private val teamButton = composerControlButton(
        "Team",
        "启用 Team 协作，由主代理委派独立调查、评审或验证任务",
    ).apply {
        isFocusPainted = true
        accessibleContext.accessibleName = "Team 协作：关闭"
    }
    private val sandboxButton = composerControlButton("workspace-write", "打开沙箱设置")
    private val sandboxControl = object : JPanel() {
        override fun getMaximumSize(): Dimension = preferredSize
    }.apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(sandboxButton)
    }
    private val setupProviderLabel = JBLabel("").apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        horizontalAlignment = javax.swing.SwingConstants.CENTER
    }
    private val runStatusLabel = object : JBLabel("") {
        override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
    }.apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        horizontalAlignment = javax.swing.SwingConstants.RIGHT
        isVisible = false
    }
    private val sendButton = composerActionButton(
        icon = PaperPlaneIcon,
        background = OmniCodeUiPalette.accent,
        foreground = com.intellij.ui.JBColor.WHITE,
        tooltip = "发送 · Cmd/Ctrl+Enter",
    )
    private val stopButton = composerActionButton(
        icon = AllIcons.Actions.Cancel,
        background = OmniCodeUiPalette.userBubble,
        foreground = OmniCodeUiPalette.error,
        tooltip = "停止运行",
    ).apply { isVisible = false }
    private val approvalGate = ModalApprovalGate(project)
    private val modelCatalog = ProviderModelCatalogService.getInstance()
    private val commitAi = CommitAiUiController(
        project = project,
        onBusyChanged = ::setCommitAiRunning,
        onStatusChanged = { status -> if (!disposed) setRunStatus(status) },
    )

    private val transcriptBlocks = ArrayDeque<TranscriptBlock>()
    private val pendingText = StringBuilder()
    private val streamFlushTimer = Timer(STREAM_FLUSH_MS) { flushPendingText() }.apply {
        isRepeats = false
    }

    @Volatile
    private var disposed = false
    private var activeRunSawText = false
    private var activeTurn: AssistantTurnPanel? = null
    private var activeTurnBlock: TranscriptBlock? = null
    private var transcriptCharacters = 0
    private var providerRefreshGeneration = 0
    private var modelSelectorGeneration = 0
    private var composerModeState = ComposerModeState()
    private var activeRunMode: AgentMode? = null
    private var activeRunStrategy: AgentExecutionStrategy? = null
    private var activeWorkflowId: String? = null
    private var lastSubmission: RecoverableSubmission? = null
    private var recoveryTurn: AssistantTurnPanel? = null
    private var pendingPlanExecution: PendingPlanExecution? = null
    private var lastProviderStatus: ProviderStatus? = null
    private var promptPopup: JPopupMenu? = null
    private var fileMentionPopup: JPopupMenu? = null
    private var fileMentionGeneration = 0
    private var fileMentionJob: Job? = null
    private var suppressPromptPopup = false
    private lateinit var composerHost: JComponent
    private val executionNavigation = ExecutionNavigationBar().apply { isVisible = false }
    private var executionToolCount = 0
    private var executionSubagentCount = 0
    private var executionEditCount = 0
    private var bodyState = ChatBodyState.EMPTY
    private var activePopup: JBPopup? = null
    private var workshopColors: WorkshopUiColors? = null

    init {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        border = JBUI.Borders.empty()
        bodyCards.add(buildSetupState(), ChatBodyState.SETUP.name)
        bodyCards.add(buildEmptyState(), ChatBodyState.EMPTY.name)
        bodyCards.add(conversationScroll, ChatBodyState.TRANSCRIPT.name)
        add(bodyWithPet, BorderLayout.CENTER)
        composerHost = buildComposer()
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 10, 2, 10)
                add(executionNavigation, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(composerHost, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                updateResponsiveLayout()
            }
        })

        sendButton.addActionListener { submitPrompt() }
        stopButton.addActionListener { stopRun() }
        addButton.addActionListener { chooseAttachment() }
        targetButton.addActionListener {
            if (lastProviderStatus?.configured == false) openProviderSettings() else showModelSelector()
        }
        modeButton.addActionListener { showModeMenu(modeButton) }
        teamButton.addActionListener { toggleExecutionStrategy() }
        sandboxButton.addActionListener { settingsNavigator(OmniCodeSettingsPage.SANDBOX) }
        input.document.addDocumentListener(SimpleDocumentListener {
            updateSendButtonState()
            SwingUtilities.invokeLater(::updateProjectFileMentionPopup)
        })
        installSendShortcuts()
        installComposerPopupNavigation()
        installClipboardAttachmentPaste()
        installPromptLibrary()
        synchronizeComposerModeFromConversation()
        restoreHistory()
        updateSendButtonState()
        refreshProviderStatus()
    }

    override fun addNotify() {
        super.addNotify()
        requestComposerFocusLater()
    }

    internal fun insertPromptContext(value: String) {
        val context = value.trim()
        if (context.isEmpty()) return
        val existing = input.text
        input.text = if (existing.isBlank()) context else buildString {
            append(existing.trimEnd())
            append("\n\n")
            append(context)
        }
        input.caretPosition = input.document.length
        updateSendButtonState()
        requestComposerFocusLater()
    }

    override fun dispose() {
        disposed = true
        streamFlushTimer.stop()
        petSettleTimer.stop()
        desktopPet.dispose()
        recoveryTurn?.clearRecoveryAction()
        recoveryTurn = null
        lastSubmission = null
        attachmentDraftGeneration++
        clearAttachmentDropState()
        pendingText.setLength(0)
        attachmentScope.cancel()
        promptPopup?.isVisible = false
        fileMentionPopup?.isVisible = false
        fileMentionGeneration++
        fileMentionJob?.cancel()
        activePopup?.cancel()
        modelSelectorGeneration++
        commitAi.dispose()
        service.cancelCurrentRun()
    }

    internal fun applyWorkshopSelection(resolved: ResolvedWorkshopSelection) {
        val colors = resolved.toWorkspaceColors()
        workshopColors = colors
        background = colors.background
        conversationScroll.viewport.background = colors.background
        composerCard.updateSurfaceColors(colors.surface, colors.border)
        targetButton.foreground = colors.secondaryText
        setupProviderLabel.foreground = colors.secondaryText
        runStatusLabel.foreground = colors.secondaryText
        stopButton.background = colors.surface
        stopButton.foreground = colors.error
        desktopPet.appearance = resolved.toDesktopPetAppearance()
        desktopPet.isPetEnabled = resolved.selection.petEnabled
        desktopPet.isVisible = resolved.selection.petEnabled
        updateSendButtonState()
        bodyWithPet.revalidate()
        bodyWithPet.repaint()
        revalidate()
        repaint()
    }

    private fun buildComposer(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10, 8, 10)

        val card = RoundedSurfacePanel(
            fillColor = OmniCodeUiPalette.surface,
            outlineColor = OmniCodeUiPalette.border,
            radius = 12,
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 10, 8, 10)

            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(attachmentTrayScroll, BorderLayout.NORTH)
                add(JBScrollPane(input).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                    preferredSize = Dimension(0, JBUI.scale(72))
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)

            add(
                createComposerToolbar(
                    addButton = addButton,
                    modeButton = modeButton,
                    teamButton = teamButton,
                    sandboxControl = sandboxControl,
                    stopButton = stopButton,
                    sendButton = sendButton,
                ),
                BorderLayout.SOUTH,
            )
        }

        composerCard = card
        installAttachmentDropSupport(composerCard)
        installAttachmentDropSupport(input)

        add(card, BorderLayout.CENTER)
        add(StretchPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 2, 0, 2)
            add(targetButton, BorderLayout.WEST)
            add(runStatusLabel, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    private fun installAttachmentDropSupport(target: JComponent) {
        DnDSupport.createBuilder(target)
            .disableAsSource()
            .enableAsNativeTarget()
            .setTargetChecker { event -> checkAttachmentDrop(event) }
            .setDropHandler { event -> handleAttachmentDrop(event) }
            .setCleanUpOnLeaveCallback(::clearAttachmentDropState)
            .setDisposableParent(this)
            .install()
    }

    private fun checkAttachmentDrop(event: DnDEvent): Boolean {
        val paths = attachmentPathsFromDrop(event.attachedObject)
        if (paths.isEmpty()) {
            clearAttachmentDropState()
            return false
        }

        event.updateAction(DnDAction.COPY)
        val knownKeys = attachmentSourceKeys.keys + pendingAttachmentSourceKeys
        val seenKeys = mutableSetOf<String>()
        val supportedSourceCount = paths.count(AttachmentIntake::supports)
        val supportedCount = paths.count { path ->
            val key = AttachmentBatchIntake.sourceKey(path)
            AttachmentIntake.supports(path) && key !in knownKeys && seenKeys.add(key)
        }
        val availableSlots = AttachmentIntake.MAX_ATTACHMENTS - attachments.size - reservedAttachmentSlots
        when {
            availableSlots <= 0 -> {
                composerCard.emphasizedOutlineColor = OmniCodeUiPalette.error
                event.setDropPossible(false, "一次最多添加 ${AttachmentIntake.MAX_ATTACHMENTS} 个附件")
            }
            supportedCount == 0 -> {
                composerCard.emphasizedOutlineColor = OmniCodeUiPalette.error
                event.setDropPossible(
                    false,
                    if (supportedSourceCount > 0) {
                        "所选附件已添加"
                    } else {
                        "仅支持 PDF、图片、Markdown、Notebook、科研资料、代码和安全文本文件"
                    },
                )
            }
            else -> {
                val acceptedCount = minOf(supportedCount, availableSlots)
                val ignoredCount = paths.size - acceptedCount
                composerCard.emphasizedOutlineColor = if (ignoredCount > 0) {
                    OmniCodeUiPalette.warning
                } else {
                    OmniCodeUiPalette.accent
                }
                val hint = buildString {
                    append("松开以添加 ").append(acceptedCount).append(" 个附件")
                    if (ignoredCount > 0) append("，忽略 ").append(ignoredCount).append(" 个")
                }
                event.setDropPossible(true, hint)
            }
        }
        return true
    }

    private fun handleAttachmentDrop(event: DnDEvent) {
        val paths = attachmentPathsFromDrop(event.attachedObject).toList()
        clearAttachmentDropState()
        if (paths.isNotEmpty()) enqueueAttachmentPaths(paths)
    }

    private fun attachmentPathsFromDrop(attachedObject: Any?): List<Path> {
        if (attachedObject == null) return emptyList()
        if (activeDropAttachedObject === attachedObject) return activeDropPaths
        activeDropAttachedObject = attachedObject
        activeDropPaths = attachmentPathsFromDropPayload(attachedObject)
        return activeDropPaths
    }

    private fun clearAttachmentDropState() {
        activeDropAttachedObject = null
        activeDropPaths = emptyList()
        if (::composerCard.isInitialized) composerCard.emphasizedOutlineColor = null
    }

    private fun chooseAttachment() {
        if (attachments.size + reservedAttachmentSlots >= AttachmentIntake.MAX_ATTACHMENTS) {
            setRunStatus("一次最多添加 ${AttachmentIntake.MAX_ATTACHMENTS} 个附件。", isError = true)
            return
        }
        val descriptor = FileChooserDescriptor(true, false, false, false, false, true).apply {
            title = "上传附件"
            description = "支持 PDF 论文、图片、Markdown、Jupyter Notebook、LaTeX/BibTeX、代码和安全文本；不支持真实 .env"
            withFileFilter { file -> AttachmentIntake.supportsFileName(file.name) }
        }
        val selected = FileChooser.chooseFiles(descriptor, project, null)
        if (selected.isNotEmpty()) enqueueAttachmentPaths(selected.map { Path.of(it.path) })
    }

    private fun enqueueAttachmentPaths(paths: List<Path>) {
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(
                { if (!disposed) enqueueAttachmentPaths(paths) },
                ModalityState.any(),
            )
            return
        }
        if (disposed || paths.isEmpty()) return

        val knownKeys = attachmentSourceKeys.keys + pendingAttachmentSourceKeys
        val batchKeys = mutableSetOf<String>()
        val candidates = mutableListOf<Path>()
        var duplicates = 0
        paths.forEach { path ->
            val normalized = path.toAbsolutePath().normalize()
            val key = AttachmentBatchIntake.sourceKey(normalized)
            if (key in knownKeys || !batchKeys.add(key)) duplicates++ else candidates.add(normalized)
        }

        val availableSlots = AttachmentIntake.MAX_ATTACHMENTS - attachments.size - reservedAttachmentSlots
        if (availableSlots <= 0) {
            setRunStatus("一次最多添加 ${AttachmentIntake.MAX_ATTACHMENTS} 个附件。", isError = true)
            return
        }
        if (candidates.isEmpty()) {
            setRunStatus(if (duplicates > 0) "所选附件已添加。" else "没有可添加的附件。", isError = true)
            return
        }

        val reservation = minOf(availableSlots, candidates.size)
        val generation = attachmentDraftGeneration
        val candidateKeys = candidates.map(AttachmentBatchIntake::sourceKey)
        reservedAttachmentSlots += reservation
        pendingAttachmentBatches++
        pendingAttachmentSourceKeys += candidateKeys
        setRunStatus("正在读取 ${minOf(candidates.size, AttachmentBatchIntake.MAX_DROP_CANDIDATES)} 个附件…")
        updateSendButtonState()

        attachmentScope.launch {
            val outcome = captureAttachmentWork { AttachmentBatchIntake.read(candidates, reservation) }
            ApplicationManager.getApplication().invokeLater({
                reservedAttachmentSlots = (reservedAttachmentSlots - reservation).coerceAtLeast(0)
                pendingAttachmentBatches = (pendingAttachmentBatches - 1).coerceAtLeast(0)
                pendingAttachmentSourceKeys.removeAll(candidateKeys.toSet())
                if (disposed || generation != attachmentDraftGeneration) return@invokeLater
                val result = outcome.getOrElse {
                    setRunStatus("附件处理失败，请重试。", isError = true)
                    updateSendButtonState()
                    requestComposerFocusLater()
                    return@invokeLater
                }

                val acceptedNames = mutableListOf<String>()
                result.accepted.forEach { accepted ->
                    if (attachments.size >= AttachmentIntake.MAX_ATTACHMENTS) return@forEach
                    if (accepted.sourceKey in attachmentSourceKeys) return@forEach
                    attachments += accepted.attachment
                    attachmentSourceKeys[accepted.sourceKey] = accepted.attachment
                    acceptedNames += accepted.attachment.fileName
                }
                if (acceptedNames.isNotEmpty()) renderAttachmentTray()

                val rejectedCount = duplicates + result.rejected.size + result.omittedByLimit
                val detail = result.rejected.joinToString("\n") { "${it.fileName}：${it.message}" }
                    .takeIf(String::isNotBlank)
                setRunStatus(
                    attachmentBatchStatus(acceptedNames, rejectedCount),
                    isError = acceptedNames.isEmpty() && rejectedCount > 0,
                    detail = detail,
                )
                updateSendButtonState()
                requestComposerFocusLater()
            }, ModalityState.any())
        }
    }

    private fun removeAttachment(attachment: UserAttachment) {
        attachments.remove(attachment)
        attachmentSourceKeys.entries.firstOrNull { it.value === attachment }?.key?.let(attachmentSourceKeys::remove)
        renderAttachmentTray()
        updateSendButtonState()
        requestComposerFocusLater()
    }

    private fun renderAttachmentTray() {
        attachmentTray.removeAll()
        attachments.forEach { attachment ->
            attachmentTray.add(AttachmentChip(attachment) { removeAttachment(attachment) })
        }
        attachmentTrayScroll.preferredSize = Dimension(
            0,
            JBUI.scale(if (attachments.any { it.kind == dev.omnicode.model.AttachmentKind.IMAGE }) 78 else 50),
        )
        attachmentTrayScroll.toolTipText =
            "附件 ${attachments.size}/${AttachmentIntake.MAX_ATTACHMENTS} · 可横向滚动"
        attachmentTrayScroll.accessibleContext.accessibleDescription = attachmentTrayScroll.toolTipText
        attachmentTrayScroll.isVisible = attachments.isNotEmpty()
        attachmentTray.revalidate()
        attachmentTray.repaint()
        attachmentTrayScroll.revalidate()
        composerHost.revalidate()
    }

    private fun selectComposerMode(mode: AgentMode) {
        if (service.isRunning() || commitAi.isRunning) {
            val lockedMode = activeRunMode ?: composerModeState.selectedMode
            setRunStatus("本次运行已锁定为 ${composerModePresentation(lockedMode).label} 模式。")
            requestComposerFocusLater()
            return
        }
        if (mode == composerModeState.selectedMode) {
            requestComposerFocusLater()
            return
        }
        composerModeState = composerModeState.select(mode)
        updateComposerModeUi()
        setRunStatus("")
        requestComposerFocusLater()
    }

    private fun updateComposerModeUi() {
        val presentation = composerModePresentation(composerModeState.selectedMode)
        val locked = activeRunMode?.takeIf { service.isRunning() }
        modeButton.text = if (locked != null) {
            composerModePresentation(locked).label
        } else {
            composerModeButtonText(composerModeState.selectedMode, composerLayoutMode(width))
        }
        modeButton.toolTipText = if (locked != null) {
            "本次运行已锁定为 ${composerModePresentation(locked).label} 模式"
        } else {
            "${presentation.description}（点击选择；Cmd/Ctrl+Shift+M 循环）"
        }
        modeButton.accessibleContext.accessibleName = "运行模式：${presentation.label}"
        modeButton.accessibleContext.accessibleDescription = modeButton.toolTipText
        updateTeamButtonUi()
        updateResponsiveLayout()
    }

    private fun toggleExecutionStrategy() {
        if (service.isRunning() || commitAi.isRunning) {
            val locked = activeRunStrategy ?: composerModeState.executionStrategy
            setRunStatus("本次运行已锁定为 ${executionStrategyLabel(locked)}。")
            return
        }
        composerModeState = composerModeState.selectExecutionStrategy(
            if (composerModeState.executionStrategy == AgentExecutionStrategy.TEAM) {
                AgentExecutionStrategy.SINGLE
            } else {
                AgentExecutionStrategy.TEAM
            },
        )
        updateTeamButtonUi()
        requestComposerFocusLater()
    }

    private fun updateTeamButtonUi() {
        val locked = activeRunStrategy?.takeIf { service.isRunning() }
        val strategy = locked ?: composerModeState.executionStrategy
        teamButton.text = teamButtonText(strategy, composerLayoutMode(width))
        teamButton.controlState = if (strategy == AgentExecutionStrategy.TEAM) {
            ComposerControlState.SELECTED
        } else {
            ComposerControlState.QUIET
        }
        teamButton.toolTipText = if (locked != null) {
            "本次运行已锁定为 ${executionStrategyLabel(strategy)}"
        } else if (strategy == AgentExecutionStrategy.TEAM) {
            "Team 协作已开启；主代理可委派独立调查、评审或验证任务"
        } else {
            "Team 协作已关闭；点击允许主代理委派独立任务"
        }
        teamButton.accessibleContext.accessibleName = "Team 协作：${if (strategy == AgentExecutionStrategy.TEAM) "开启" else "关闭"}"
        teamButton.accessibleContext.accessibleDescription = teamButton.toolTipText
    }

    private fun showModeMenu(anchor: JComponent) {
        if (service.isRunning() || commitAi.isRunning) {
            val lockedMode = activeRunMode ?: composerModeState.selectedMode
            setRunStatus("本次运行已锁定为 ${composerModePresentation(lockedMode).label} 模式。")
            return
        }
        val menu = JPopupMenu()
        val group = ButtonGroup()
        AgentMode.entries.forEach { mode ->
            val presentation = composerModePresentation(mode)
            val item = JRadioButtonMenuItem("${presentation.label} · ${presentation.menuSummary}").apply {
                isSelected = composerModeState.selectedMode == mode
                toolTipText = presentation.description
                addActionListener { selectComposerMode(mode) }
            }
            group.add(item)
            menu.add(item)
        }
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = requestComposerFocusLater()
            override fun popupMenuCanceled(event: PopupMenuEvent) = requestComposerFocusLater()
        })
        menu.show(anchor, 0, -menu.preferredSize.height)
    }

    private fun synchronizeComposerModeFromConversation() {
        val conversationMode = service.conversationModeSnapshot()
        val conversationStrategy = service.conversationStrategySnapshot()
        composerModeState = synchronizeComposerModeState(composerModeState, conversationMode)
            .selectExecutionStrategy(conversationStrategy)
        updateComposerModeUi()
    }

    private fun showManagementMenu(anchor: JComponent) {
        val menu = JPopupMenu().apply {
            add(JMenuItem("供应商与 API Key…").apply {
                addActionListener { openProviderSettings() }
            })
            add(JMenuItem("平台、MCP、提示词与 Skills…").apply {
                addActionListener { openPlatformSettings() }
            })
            add(JMenuItem("用量与对话历史…").apply {
                addActionListener { openUsageAndHistory() }
            })
            addSeparator()
            add(JMenuItem("生成 Commit Message").apply {
                isEnabled = canGenerateCommitMessage()
                addActionListener { generateCommitMessage() }
            })
            add(JMenuItem("导出可复现实验研究包…").apply {
                isEnabled = canExportResearchPackage()
                toolTipText = "导出脱敏的 Markdown 会话、命令证据与复现清单（Cmd/Ctrl+Shift+E）"
                addActionListener { exportResearchPackage() }
            })
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = requestComposerFocusLater()
                override fun popupMenuCanceled(event: PopupMenuEvent) = requestComposerFocusLater()
            })
        }
        menu.show(anchor, 0, -menu.preferredSize.height)
    }

    private fun insertPromptText(value: String) {
        val caret = input.caretPosition.coerceIn(0, input.document.length)
        input.insert(value, caret)
        input.caretPosition = caret + value.length
        requestComposerFocusLater()
    }

    private fun setRunStatus(message: String, isError: Boolean = false, detail: String? = null) {
        runStatusLabel.text = message
        runStatusLabel.foreground = if (isError) {
            workshopColors?.error ?: OmniCodeUiPalette.error
        } else {
            workshopColors?.secondaryText ?: OmniCodeUiPalette.secondary
        }
        runStatusLabel.toolTipText = detail?.take(500) ?: message.takeIf { it.isNotBlank() }
        runStatusLabel.accessibleContext.accessibleName = message
        runStatusLabel.isVisible = message.isNotBlank()
        runStatusLabel.parent?.revalidate()
        runStatusLabel.parent?.repaint()
    }

    private fun requestComposerFocusLater() {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && isShowing && activePopup?.isVisible != true) {
                input.requestFocusInWindow()
            }
        }, ModalityState.nonModal())
    }

    private fun buildSetupState(): JComponent = centeredStatePanel(
        title = "连接模型后开始使用",
        description = "先保存供应商 API Key，OmniCode 会自动读取该账号可用的模型列表。",
    ) {
        add(setupProviderLabel)
        add(Box.createVerticalStrut(JBUI.scale(16)))
        add(primaryButton("配置 API Key", "打开供应商设置").apply {
            alignmentX = JComponent.CENTER_ALIGNMENT
            addActionListener { openProviderSettings() }
        })
    }

    private fun buildEmptyState(): JComponent = centeredStatePanel(
        title = "今天想构建什么？",
        description = "描述目标，OmniCode 会先理解项目，再按当前模式给出计划或执行操作。",
    ) {
        add(Box.createVerticalStrut(JBUI.scale(18)))
        add(responsiveSuggestionGrid(
            defaultComposerSuggestions(),
        ) { suggestion ->
            suggestion.targetMode?.let(::selectComposerMode)
            input.text = suggestion.prompt
            input.caretPosition = input.document.length
            input.requestFocusInWindow()
        })
    }

    private fun showEmptyState() = refreshBodyState()

    private fun removeEmptyState() = showBodyState(ChatBodyState.TRANSCRIPT)

    private fun refreshBodyState() {
        val state = chatBodyState(transcriptBlocks.isNotEmpty(), lastProviderStatus?.configured)
        showBodyState(state)
    }

    private fun showBodyState(state: ChatBodyState) {
        bodyState = state
        bodyLayout.show(bodyCards, state.name)
        if (::composerHost.isInitialized) composerHost.isVisible = state != ChatBodyState.SETUP
        executionNavigation.isVisible = state == ChatBodyState.TRANSCRIPT
        bodyCards.revalidate()
        bodyCards.repaint()
    }

    private fun updateExecutionNavigation(running: Boolean) {
        executionNavigation.updateCounts(
            toolCount = executionToolCount,
            subagentCount = executionSubagentCount,
            editCount = executionEditCount,
            running = running,
        )
        executionNavigation.isVisible = bodyState == ChatBodyState.TRANSCRIPT || transcriptBlocks.isNotEmpty()
        executionNavigation.parent?.revalidate()
        executionNavigation.parent?.repaint()
    }

    private fun installSendShortcuts() {
        val enterAction = "omnicode.sendOrInsertLine"
        val explicitSendAction = "omnicode.sendPrompt"
        val switchModeAction = "omnicode.switchAgentMode"
        val exportResearchAction = "omnicode.exportResearchPackage"
        input.getInputMap(JComponent.WHEN_FOCUSED).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), enterAction)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), explicitSendAction)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), explicitSendAction)
            put(
                KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                switchModeAction,
            )
            put(
                KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                switchModeAction,
            )
            put(
                KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                exportResearchAction,
            )
            put(
                KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                exportResearchAction,
            )
        }
        input.actionMap.put(enterAction, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                performComposerEnter(explicitSend = false)
            }
        })
        input.actionMap.put(explicitSendAction, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                performComposerEnter(explicitSend = true)
            }
        })
        input.actionMap.put(switchModeAction, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                selectComposerMode(nextComposerMode(composerModeState.selectedMode))
            }
        })
        input.actionMap.put(exportResearchAction, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = exportResearchPackage()
        })
        sendButton.toolTipText = "发送（Enter 或 Cmd/Ctrl+Enter）· Shift+Enter 换行"
    }

    private fun installClipboardAttachmentPaste() {
        val shortcutMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        input.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask),
            "omnicode.paste",
        )
        input.actionMap.put("omnicode.paste", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                if (!pasteClipboardAttachment()) input.paste()
            }
        })
    }

    private fun installComposerPopupNavigation() {
        fun bind(
            keyStroke: KeyStroke,
            actionName: String,
            popupAction: () -> Boolean,
        ) {
            val inputMap = input.getInputMap(JComponent.WHEN_FOCUSED)
            val fallbackKey = inputMap.get(keyStroke)
            val fallbackAction = fallbackKey?.let(input.actionMap::get)
            inputMap.put(keyStroke, actionName)
            input.actionMap.put(actionName, object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                    if (!popupAction()) fallbackAction?.actionPerformed(event)
                }
            })
        }

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "omnicode.nextComposerSuggestion") {
            moveComposerPopupSelection(1)
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "omnicode.previousComposerSuggestion") {
            moveComposerPopupSelection(-1)
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "omnicode.acceptComposerSuggestion") {
            activateSelectedComposerPopup(activeComposerPopup())
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "omnicode.dismissComposerSuggestion") {
            val popup = activeComposerPopup() ?: return@bind false
            popup.isVisible = false
            true
        }
    }

    private fun activeComposerPopup(): JPopupMenu? =
        fileMentionPopup?.takeIf { it.isVisible } ?: promptPopup?.takeIf { it.isVisible }

    private fun moveComposerPopupSelection(delta: Int): Boolean {
        val popup = activeComposerPopup() ?: return false
        val next = nextPopupSelectionIndex(
            current = popup.selectionModel.selectedIndex,
            itemCount = popup.componentCount,
            delta = delta,
        )
        if (next < 0) return false
        popup.selectionModel.selectedIndex = next
        return true
    }

    private fun pasteClipboardAttachment(): Boolean {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return false
        val contents = runCatching { clipboard.getContents(null) }.getOrNull() ?: return false
        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File>)
                    ?.map { it.toPath().toAbsolutePath().normalize() }
                    .orEmpty()
            }.getOrDefault(emptyList())
            if (files.isNotEmpty()) {
                enqueueAttachmentPaths(files)
                return true
            }
        }
        if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return false
        if (attachments.size + reservedAttachmentSlots >= AttachmentIntake.MAX_ATTACHMENTS) {
            setRunStatus("一次最多添加 ${AttachmentIntake.MAX_ATTACHMENTS} 个附件。", isError = true)
            return true
        }
        val image = runCatching { contents.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull()
            ?: return false
        val fileName = "clipboard-${CLIPBOARD_IMAGE_TIME.format(LocalDateTime.now())}.png"
        val generation = attachmentDraftGeneration
        reservedAttachmentSlots++
        pendingAttachmentBatches++
        setRunStatus("正在安全处理剪贴板图片…")
        updateSendButtonState()
        attachmentScope.launch {
            val outcome = captureAttachmentWork {
                clipboardAttachmentSemaphore.withPermit { clipboardImageAttachment(image, fileName) }
            }
            ApplicationManager.getApplication().invokeLater({
                reservedAttachmentSlots = (reservedAttachmentSlots - 1).coerceAtLeast(0)
                pendingAttachmentBatches = (pendingAttachmentBatches - 1).coerceAtLeast(0)
                if (disposed || generation != attachmentDraftGeneration) return@invokeLater
                val result = outcome.getOrElse {
                    setRunStatus("剪贴板图片处理失败，请重试。", isError = true)
                    updateSendButtonState()
                    requestComposerFocusLater()
                    return@invokeLater
                }
                when (result) {
                    is AttachmentIntakeResult.Accepted -> {
                        if (attachments.size < AttachmentIntake.MAX_ATTACHMENTS) {
                            attachments += result.attachment
                            attachmentSourceKeys["clipboard:${System.nanoTime()}"] = result.attachment
                            renderAttachmentTray()
                            setRunStatus("已添加剪贴板截图 ${result.attachment.fileName}")
                        } else {
                            setRunStatus("一次最多添加 ${AttachmentIntake.MAX_ATTACHMENTS} 个附件。", isError = true)
                        }
                    }
                    is AttachmentIntakeResult.Rejected -> setRunStatus(result.message, isError = true)
                }
                updateSendButtonState()
                requestComposerFocusLater()
            }, ModalityState.any())
        }
        return true
    }

    private fun performComposerEnter(explicitSend: Boolean) {
        if (activateSelectedComposerPopup(fileMentionPopup) || activateSelectedComposerPopup(promptPopup)) return
        when (composerEnterAction(
            isBusy = service.isRunning() || commitAi.isRunning,
            explicitSend = explicitSend,
            shiftDown = false,
            promptPopupVisible = promptPopup?.isVisible == true,
        )) {
            ComposerEnterAction.SEND -> submitPrompt()
            ComposerEnterAction.INSERT_NEWLINE -> input.replaceSelection("\n")
            ComposerEnterAction.SHOW_BUSY -> {
                setRunStatus("当前任务仍在运行；可继续编辑，停止后再发送。")
                requestComposerFocusLater()
            }
            ComposerEnterAction.IGNORE -> Unit
        }
    }

    private fun installPromptLibrary() {
        input.document.addDocumentListener(SimpleDocumentListener {
            if (!suppressPromptPopup) SwingUtilities.invokeLater(::updatePromptPopup)
        })
    }

    private fun updatePromptPopup() {
        if (disposed || !input.isFocusOwner) return
        val text = input.text
        if (!text.startsWith("!") || text.contains('\n') || text.drop(1).any(Char::isWhitespace)) {
            promptPopup?.isVisible = false
            return
        }
        val query = text.removePrefix("!")
        val templates = OmniCodePlatformSettingsService.getInstance().snapshot().promptTemplates
            .filter { template ->
                query.isBlank() || template.shortcut.contains(query, ignoreCase = true) ||
                    template.name.contains(query, ignoreCase = true)
            }
            .take(10)
        promptPopup?.isVisible = false
        if (templates.isEmpty()) return
        val popup = JPopupMenu()
        templates.forEach { template ->
            popup.add(JMenuItem("!${template.shortcut}  ·  ${template.name}").apply {
                toolTipText = template.content.take(300)
                addActionListener {
                    suppressPromptPopup = true
                    try {
                        input.text = template.content
                        input.caretPosition = input.document.length
                    } finally {
                        suppressPromptPopup = false
                    }
                    input.requestFocusInWindow()
                }
            })
        }
        promptPopup = popup
        popup.show(input, 0, -popup.preferredSize.height)
        if (popup.componentCount > 0) popup.selectionModel.selectedIndex = 0
    }

    private fun updateProjectFileMentionPopup() {
        if (disposed || !input.isFocusOwner || input.text.startsWith("!")) {
            fileMentionPopup?.isVisible = false
            fileMentionJob?.cancel()
            return
        }
        val mention = activeFileMention(input.text, input.caretPosition)
        if (mention == null) {
            fileMentionPopup?.isVisible = false
            fileMentionJob?.cancel()
            fileMentionGeneration++
            return
        }
        val root = project.basePath?.let(Path::of) ?: return
        val generation = ++fileMentionGeneration
        fileMentionJob?.cancel()
        fileMentionJob = attachmentScope.launch {
            delay(FILE_MENTION_DEBOUNCE_MS)
            val context = currentCoroutineContext()
            val candidates = runCatching {
                findProjectFileMentions(root, mention.query, continueScanning = { context.isActive })
            }.getOrDefault(emptyList())
            if (!context.isActive) return@launch
            ApplicationManager.getApplication().invokeLater({
                if (disposed || generation != fileMentionGeneration) return@invokeLater
                val current = activeFileMention(input.text, input.caretPosition)
                if (current != mention) return@invokeLater
                fileMentionPopup?.isVisible = false
                if (candidates.isEmpty()) return@invokeLater
                fileMentionPopup = JPopupMenu().apply {
                    candidates.forEach { candidate ->
                        add(JMenuItem("@${candidate.relativePath}").apply {
                            toolTipText = "作为附件加入当前任务"
                            addActionListener {
                                val active = activeFileMention(input.text, input.caretPosition)
                                if (active != null) input.replaceRange("", active.start, active.end)
                                enqueueAttachmentPaths(listOf(candidate.path))
                            }
                        })
                    }
                    addPopupMenuListener(object : PopupMenuListener {
                        override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
                        override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = requestComposerFocusLater()
                        override fun popupMenuCanceled(event: PopupMenuEvent) = requestComposerFocusLater()
                    })
                }.also { popup ->
                    popup.show(input, 0, -popup.preferredSize.height)
                    popup.selectionModel.selectedIndex = 0
                }
            }, ModalityState.any())
        }
    }

    private fun submitPrompt() {
        if (disposed) return
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("当前任务仍在运行；可继续编辑，停止后再发送。")
            requestComposerFocusLater()
            return
        }
        if (pendingAttachmentBatches > 0) {
            setRunStatus("附件仍在读取，请稍候再发送。")
            requestComposerFocusLater()
            return
        }
        if (lastProviderStatus?.configured == false) {
            setRunStatus("请先配置供应商 API Key。", isError = true)
            openProviderSettings()
            return
        }
        val prompt = input.text.trim()
        if (prompt.isEmpty() && attachments.isEmpty()) {
            setRunStatus("请输入任务或添加附件后再发送。", isError = true)
            requestComposerFocusLater()
            return
        }
        val userSubmission = UserSubmission(prompt, attachments.toList())
        if (userSubmission.estimatedCharacterCount > AgentEngine.MAX_USER_MESSAGE_CHARS) {
            setRunStatus("消息过长，最多 ${AgentEngine.MAX_USER_MESSAGE_CHARS} 个字符。", isError = true)
            requestComposerFocusLater()
            return
        }

        activeRunSawText = false
        val submission = composerModeState.snapshot(prompt)
        val callbacks = AgentRunCallbacks(
            onRunningChanged = ::setRunning,
            onEvent = ::handleAgentEvent,
            onResult = ::handleResult,
        )
        if (!service.startRun(
            userSubmission.copy(prompt = submission.prompt),
            submission.mode,
            submission.strategy,
            approvalGate,
            callbacks,
        )) {
            setRunStatus("已有任务正在运行。", isError = true)
            requestComposerFocusLater()
            return
        }

        recoveryTurn?.clearRecoveryAction()
        recoveryTurn = null
        pendingPlanExecution = null
        lastSubmission = RecoverableSubmission(
            submission = userSubmission.copy(prompt = submission.prompt),
            mode = submission.mode,
            strategy = submission.strategy,
        )
        activeRunMode = submission.mode
        activeRunStrategy = submission.strategy
        activeWorkflowId = null
        executionToolCount = 0
        executionSubagentCount = 0
        executionEditCount = 0
        updateExecutionNavigation(running = true)
        updateComposerModeUi()
        flushPendingText()
        addUserMessage(prompt, attachments.toList())
        beginAssistantTurn()
        input.text = ""
        attachmentDraftGeneration++
        attachments.clear()
        attachmentSourceKeys.clear()
        renderAttachmentTray()
        requestComposerFocusLater()
        scrollToBottom(force = true)
    }

    private fun stopRun() {
        if (service.cancelCurrentRun()) {
            stopButton.isEnabled = false
            setRunStatus("正在停止…")
            activeTurn?.updateStatus("正在停止…")
        } else {
            setRunStatus("当前没有可停止的任务。", isError = true)
        }
        requestComposerFocusLater()
    }

    private fun clearConversation() {
        if (!service.clearHistory()) {
            setRunStatus("请先停止当前任务，再开始新对话。", isError = true)
            return
        }
        resetConversationView()
        showEmptyState()
        setRunStatus("")
        requestComposerFocusLater()
    }

    private fun resetConversationView() {
        streamFlushTimer.stop()
        pendingText.setLength(0)
        conversation.clearBlocks()
        transcriptBlocks.clear()
        transcriptCharacters = 0
        activeTurn = null
        activeTurnBlock = null
        activeRunSawText = false
        activeRunMode = null
        activeRunStrategy = null
        activeWorkflowId = null
        lastSubmission = null
        recoveryTurn = null
        pendingPlanExecution = null
        executionToolCount = 0
        executionSubagentCount = 0
        executionEditCount = 0
        updateExecutionNavigation(running = false)
    }

    private fun setRunning(running: Boolean) {
        if (disposed) return
        input.isEnabled = true
        sendButton.isVisible = !running
        stopButton.isEnabled = running
        stopButton.isVisible = running
        targetButton.isEnabled = !running
        modeButton.isEnabled = !running
        teamButton.isEnabled = !running
        updateComposerModeUi()
        updateSendButtonState()
        if (running) {
            updatePetState(DesktopPetState.THINKING)
            setRunStatus("运行中…")
        } else if (runStatusLabel.text == "正在停止…" || runStatusLabel.text.startsWith("运行中")) {
            setRunStatus("")
            if (desktopPet.state == DesktopPetState.THINKING || desktopPet.state == DesktopPetState.TOOL) {
                updatePetState(DesktopPetState.IDLE)
            }
        }
        revalidate()
        repaint()
    }

    private fun updateSendButtonState() {
        val active = composerSendEnabled(
            isRunning = service.isRunning(),
            isCommitAiRunning = commitAi.isRunning,
            providerConfigured = lastProviderStatus?.configured == true,
            prompt = input.text,
            attachmentCount = attachments.size,
            pendingAttachmentLoads = pendingAttachmentBatches,
        )
        sendButton.isEnabled = active
        sendButton.background = if (active) {
            workshopColors?.accent ?: OmniCodeUiPalette.accent
        } else {
            workshopColors?.surface ?: OmniCodeUiPalette.controlHover
        }
        sendButton.foreground = if (active) {
            workshopColors?.accentText ?: com.intellij.ui.JBColor.WHITE
        } else {
            workshopColors?.secondaryText ?: OmniCodeUiPalette.secondary
        }
        sendButton.putClientProperty(ACTION_ICON_COLOR_KEY, sendButton.foreground)
        sendButton.repaint()
    }

    private fun setCommitAiRunning(running: Boolean) {
        if (disposed) return
        val interactive = !running && !service.isRunning()
        input.isEnabled = interactive
        targetButton.isEnabled = interactive
        modeButton.isEnabled = interactive
        teamButton.isEnabled = interactive
        updateComposerModeUi()
        updateSendButtonState()
        if (running) {
            updatePetState(DesktopPetState.THINKING)
            setRunStatus("正在生成提交信息…")
        } else if (!service.isRunning() && desktopPet.state == DesktopPetState.THINKING) {
            updatePetState(DesktopPetState.IDLE)
        }
        if (!running && interactive) requestComposerFocusLater()
        revalidate()
        repaint()
    }

    private fun handleAgentEvent(event: AgentEvent) {
        if (disposed) return
        val followOutput = isNearBottom()
        desktopPetStateForAgentEvent(event)?.let(::updatePetState)
        when (event) {
            is AgentEvent.ModeSelected -> {
                activeRunMode = event.mode
                setRunStatus("${composerModePresentation(event.mode).label} 模式 · 已锁定本次任务")
                updateComposerModeUi()
            }
            is AgentEvent.ExecutionStrategySelected -> {
                activeRunStrategy = event.strategy
                activeWorkflowId = event.workflowId
                setRunStatus("${executionStrategyLabel(event.strategy)} · 已锁定本次任务")
                updateTeamButtonUi()
            }
            is AgentEvent.DelegatedAgentStarted -> {
                if (activeWorkflowId != null && activeWorkflowId != event.workflowId) return
                activeWorkflowId = event.workflowId
                flushPendingText()
                val added = ensureActiveTurn().startDelegate(
                    agentId = event.agentId,
                    displayName = event.displayName,
                    objective = event.objective,
                    role = delegateRoleLabel(event.role),
                )
                if (added) executionSubagentCount++
                updateExecutionNavigation(running = true)
                addActiveTurnCharacters(event.displayName.length + minOf(event.objective.length, 500))
                setRunStatus("${event.displayName} 正在处理委派任务…")
            }
            is AgentEvent.DelegatedAgentCompleted -> {
                if (activeWorkflowId != null && activeWorkflowId != event.workflowId) return
                activeWorkflowId = event.workflowId
                flushPendingText()
                val added = ensureActiveTurn().completeDelegate(
                    agentId = event.agentId,
                    displayName = event.displayName,
                    status = delegateProgressStatus(event.status),
                    summary = event.summary,
                    tokens = event.usage.totalTokens,
                    role = delegateRoleLabel(event.role),
                )
                if (added) executionSubagentCount++
                updateExecutionNavigation(running = true)
                addActiveTurnCharacters(minOf(event.summary.length, 1_200))
                setRunStatus("${event.displayName}${delegateCompletionStatusText(event.status)}")
            }
            is AgentEvent.Status -> {
                ensureActiveTurn().updateStatus(event.message)
                setRunStatus(event.message)
            }
            is AgentEvent.TextDelta -> {
                // Delegated specialists are represented by their progress card; the service only
                // forwards the lead agent's deltas to keep the main answer coherent.
                activeRunSawText = true
                pendingText.append(event.text)
                if (!streamFlushTimer.isRunning) streamFlushTimer.start()
            }
            is AgentEvent.ToolRequested -> {
                flushPendingText()
                ensureActiveTurn().startTool(event.name, event.summary, event.callId)
                executionToolCount++
                if (event.name == "apply_change" || event.name == "apply_patch") executionEditCount++
                updateExecutionNavigation(running = true)
                addActiveTurnCharacters(event.name.length + event.summary.length)
                setRunStatus("正在运行 ${humanizeToolName(event.name)}…")
            }
            is AgentEvent.ToolApprovalResolved -> {
                val status = when (event.outcome) {
                    dev.omnicode.agent.ToolApprovalOutcome.APPROVED -> "已批准 ${humanizeToolName(event.name)}"
                    dev.omnicode.agent.ToolApprovalOutcome.REJECTED -> "已拒绝 ${humanizeToolName(event.name)}"
                    dev.omnicode.agent.ToolApprovalOutcome.NOT_REQUIRED -> "${humanizeToolName(event.name)} 无需审批"
                    dev.omnicode.agent.ToolApprovalOutcome.NOT_REQUESTED -> "${humanizeToolName(event.name)} 未发起审批"
                }
                setRunStatus(status)
            }
            is AgentEvent.ToolCompleted -> {
                flushPendingText()
                val result = boundedToolResult(event.result)
                ensureActiveTurn().completeTool(
                    event.name,
                    result,
                    event.isError,
                    event.callId,
                    cancelled = event.cancelled,
                )
                addActiveTurnCharacters(result.length)
                setRunStatus(if (event.isError) "${humanizeToolName(event.name)}失败" else "${humanizeToolName(event.name)}完成")
            }
            is AgentEvent.UsageUpdated -> {
                ensureActiveTurn().updateUsage(event.usage.totalTokens)
                setRunStatus("运行中 · ${event.usage.totalTokens} tokens")
            }
            is AgentEvent.BudgetWarning -> {
                val projected = if (event.projected) "预计" else "当前"
                setRunStatus(
                    "$projected 费用 \$${event.estimatedCostUsd.stripTrailingZeros().toPlainString()} / " +
                        "\$${event.maxCostUsd.stripTrailingZeros().toPlainString()}",
                    isError = false,
                )
            }
        }
        if (event !is AgentEvent.TextDelta) scrollToBottom(force = followOutput)
    }

    private fun flushPendingText() {
        if (disposed || pendingText.isEmpty()) return
        val followOutput = isNearBottom()
        val value = pendingText.toString()
        pendingText.setLength(0)
        ensureActiveTurn().appendText(value)
        addActiveTurnCharacters(value.length)
        scrollToBottom(force = followOutput)
    }

    private fun handleResult(result: AgentRunResult) {
        if (disposed) return
        val followOutput = isNearBottom()
        flushPendingText()
        val turn = ensureActiveTurn()
        when (result.status) {
            AgentRunStatus.COMPLETED -> {
                updatePetState(DesktopPetState.SUCCESS, settle = true)
                if (!activeRunSawText && result.finalText.isNotBlank()) {
                    turn.appendText(result.finalText)
                    addActiveTurnCharacters(result.finalText.length)
                }
                turn.finish(
                    when (result.mode) {
                        AgentMode.AGENT -> "✓  完成"
                        AgentMode.PLAN -> "✓  计划完成"
                        AgentMode.RESEARCH -> "✓  研究记录完成"
                    },
                )
                lastSubmission = null
                if (result.mode == AgentMode.PLAN && result.finalText.isNotBlank()) {
                    offerPlanExecution(turn, result.finalText)
                } else if (result.mode == AgentMode.RESEARCH) {
                    offerResearchExport(turn)
                } else {
                    recoveryTurn = null
                    pendingPlanExecution = null
                }
                setRunStatus("")
            }
            AgentRunStatus.CANCELLED -> {
                updatePetState(DesktopPetState.IDLE)
                appendTerminalText(turn, result.finalText)
                turn.finish("›  已取消")
                offerSubmissionRecovery(turn, result.status)
                setRunStatus("已取消")
            }
            AgentRunStatus.FAILED -> {
                updatePetState(DesktopPetState.ERROR, settle = true)
                appendTerminalText(turn, result.finalText)
                turn.finish("!  失败", isError = true)
                offerSubmissionRecovery(turn, result.status)
                setRunStatus("运行失败", isError = true, detail = result.finalText)
            }
            AgentRunStatus.BUDGET_EXHAUSTED -> {
                updatePetState(DesktopPetState.ERROR, settle = true)
                if (!activeRunSawText) appendTerminalText(turn, result.finalText)
                turn.finish("!  已达到 Token 预算", isError = true)
                offerSubmissionRecovery(turn, result.status)
                setRunStatus("Token 预算已用尽", isError = true, detail = result.finalText)
            }
        }
        activeTurn = null
        activeTurnBlock = null
        activeRunMode = null
        activeRunStrategy = null
        activeWorkflowId = null
        updateExecutionNavigation(running = false)
        updateComposerModeUi()
        refreshProviderStatus()
        requestComposerFocusLater()
        scrollToBottom(force = followOutput)
    }

    private fun updatePetState(state: DesktopPetState, settle: Boolean = false) {
        petSettleTimer.stop()
        desktopPet.state = state
        if (settle && desktopPet.isPetEnabled) petSettleTimer.restart()
    }

    private fun offerSubmissionRecovery(turn: AssistantTurnPanel, status: AgentRunStatus) {
        if (!shouldOfferSubmissionRecovery(status) || lastSubmission == null) return
        recoveryTurn?.takeIf { it !== turn }?.clearRecoveryAction()
        recoveryTurn = turn
        turn.showRecoveryAction(
            label = "编辑后重发",
            tooltip = "恢复上次提示词和附件到输入框",
            action = ::restoreLastSubmissionForEditing,
        )
    }

    private fun offerPlanExecution(turn: AssistantTurnPanel, planText: String) {
        recoveryTurn?.takeIf { it !== turn }?.clearRecoveryAction()
        pendingPlanExecution = PendingPlanExecution(planFingerprint(planText))
        recoveryTurn = turn
        turn.showRecoveryAction(
            label = "按此计划执行",
            tooltip = "确认该计划并切换到 Agent 模式开始实施",
            icon = AllIcons.Actions.Execute,
            action = ::executePendingPlan,
        )
    }

    private fun offerResearchExport(turn: AssistantTurnPanel) {
        recoveryTurn?.takeIf { it !== turn }?.clearRecoveryAction()
        pendingPlanExecution = null
        recoveryTurn = turn
        turn.showRecoveryAction(
            label = "导出研究包",
            tooltip = "导出脱敏的 Markdown 会话、命令证据、复现清单与引用核对清单",
            icon = AllIcons.Actions.Download,
            action = ::exportResearchPackage,
        )
    }

    private fun executePendingPlan() {
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("当前任务仍在运行，请稍后再执行计划。")
            return
        }
        val plan = pendingPlanExecution ?: return
        composerModeState = composerModeState.select(AgentMode.AGENT)
        updateComposerModeUi()
        input.text = planExecutionPrompt(plan.fingerprint)
        input.caretPosition = input.document.length
        recoveryTurn?.clearRecoveryAction()
        recoveryTurn = null
        pendingPlanExecution = null
        submitPrompt()
    }

    private fun restoreLastSubmissionForEditing() {
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("当前任务仍在运行，请稍后再恢复。")
            return
        }
        val recoverable = lastSubmission ?: return
        input.text = recoverable.submission.prompt
        input.caretPosition = input.document.length
        composerModeState = composerModeState.select(recoverable.mode)
            .selectExecutionStrategy(recoverable.strategy)
        updateComposerModeUi()

        attachmentDraftGeneration++
        attachments.clear()
        attachmentSourceKeys.clear()
        recoverable.submission.attachments.forEachIndexed { index, attachment ->
            attachments += attachment
            attachmentSourceKeys["recovered:$index:${attachment.fileName}"] = attachment
        }
        renderAttachmentTray()
        recoveryTurn?.clearRecoveryAction()
        recoveryTurn = null
        pendingPlanExecution = null
        lastSubmission = null
        updateSendButtonState()
        setRunStatus("已恢复上次任务和附件，可修改后重发。")
        requestComposerFocusLater()
    }

    private fun appendTerminalText(turn: AssistantTurnPanel, value: String) {
        if (value.isBlank()) return
        turn.appendText(value)
        addActiveTurnCharacters(value.length)
    }

    private fun addUserMessage(text: String, attachments: List<UserAttachment> = emptyList()) {
        removeEmptyState()
        val card = UserMessageCard(text, attachments)
        registerBlock(card, text.length + attachments.sumOf { it.fileName.length })
    }

    private fun beginAssistantTurn(): AssistantTurnPanel {
        val turn = AssistantTurnPanel(activeRunMode ?: composerModeState.selectedMode, ::openToolFileReference)
        activeTurn = turn
        activeTurnBlock = registerBlock(turn, 0)
        return turn
    }

    private fun openToolFileReference(reference: ToolFileReference) {
        val base = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize() ?: return
        val requested = runCatching { Path.of(reference.path) }.getOrNull() ?: return
        val resolved = (if (requested.isAbsolute) requested else base.resolve(requested)).toAbsolutePath().normalize()
        if (!resolved.startsWith(base)) {
            setRunStatus("无法打开工作区外的文件。", isError = true)
            return
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolved.toString())
        if (file == null) {
            setRunStatus("文件不存在：${reference.path}", isError = true)
            return
        }
        OpenFileDescriptor(project, file, (reference.startLine ?: 1).minus(1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun ensureActiveTurn(): AssistantTurnPanel = activeTurn ?: beginAssistantTurn()

    private fun registerBlock(component: JComponent, characters: Int): TranscriptBlock {
        val block = TranscriptBlock(component, characters)
        transcriptBlocks.addLast(block)
        transcriptCharacters += characters
        conversation.addBlock(component)
        trimTranscript()
        return block
    }

    private fun addActiveTurnCharacters(characters: Int) {
        if (characters <= 0) return
        activeTurnBlock?.characters = (activeTurnBlock?.characters ?: 0) + characters
        transcriptCharacters += characters
        trimTranscript()
    }

    private fun trimTranscript() {
        while (
            transcriptCharacters > MAX_TRANSCRIPT_CHARS &&
            transcriptBlocks.size > 1 &&
            transcriptBlocks.firstOrNull() !== activeTurnBlock
        ) {
            val removed = transcriptBlocks.removeFirst()
            transcriptCharacters -= removed.characters
            conversation.removeBlock(removed.component)
        }
    }

    private fun restoreHistory() {
        var restored = false
        service.historySnapshot().forEach { message ->
            if (message.role == MessageRole.SYSTEM) return@forEach
            val text = message.blocks
                .filterIsInstance<ContentBlock.Text>()
                .joinToString("") { it.text }
                .trim()
            if (text.isBlank()) return@forEach
            restored = true
            if (message.role == MessageRole.USER) {
                addUserMessage(text)
            } else {
                val turn = AssistantTurnPanel(mode = null).apply {
                    appendText(text)
                    finish("✓  完成")
                }
                registerBlock(turn, text.length)
            }
        }
        if (restored) showBodyState(ChatBodyState.TRANSCRIPT) else showEmptyState()
        scrollToBottom(force = true)
    }

    private fun refreshProviderStatus() {
        val generation = ++providerRefreshGeneration
        service.refreshProviderStatus { status ->
            if (!disposed && generation == providerRefreshGeneration) updateProviderStatus(status)
        }
    }

    private fun updateProviderStatus(status: ProviderStatus) {
        lastProviderStatus = status
        setupProviderLabel.text = status.providerName.ifBlank { "当前供应商" }
        targetButton.foreground = OmniCodeUiPalette.secondary
        targetButton.toolTipText = status.text
        refreshBodyState()
        updateSendButtonState()
        updateResponsiveLayout()
    }

    private fun updateResponsiveLayout() {
        val layoutMode = composerLayoutMode(width)
        val sandboxMode = OmniCodePlatformSettingsService.getInstance().snapshot().sandboxMode
        val visibility = composerToolbarVisibility(composerModeState.selectedMode, layoutMode, sandboxMode)
        sandboxControl.isVisible = visibility.showSandbox
        val locked = activeRunMode?.takeIf { service.isRunning() }
        modeButton.text = if (locked != null) {
            composerModePresentation(locked).label
        } else {
            composerModeButtonText(composerModeState.selectedMode, layoutMode)
        }
        updateTeamButtonUi()
        lastProviderStatus?.let(::updateFooterLabels)
        updateSandboxButton()
        revalidate()
        repaint()
    }

    private fun updateSandboxButton() {
        val sandboxMode = OmniCodePlatformSettingsService.getInstance().snapshot().sandboxMode
        val presentation = sandboxButtonPresentation(composerModeState.selectedMode, sandboxMode, width)
        sandboxButton.text = presentation.text
        sandboxButton.toolTipText = presentation.tooltip
        sandboxButton.accessibleContext.accessibleName = presentation.tooltip
        sandboxButton.controlState = if (presentation.dangerous) {
            ComposerControlState.WARNING
        } else {
            ComposerControlState.QUIET
        }
        sandboxButton.foreground = if (presentation.dangerous) {
            OmniCodeUiPalette.warning
        } else {
            OmniCodeUiPalette.secondary
        }
    }

    private fun updateFooterLabels(status: ProviderStatus) {
        val layoutMode = composerLayoutMode(width)
        val limits = footerTextLimits(width)
        val provider = status.providerName.ifBlank { "Provider" }
        val model = status.model.ifBlank { "Model" }
        targetButton.text = if (!status.configured) {
            "未连接模型 · 配置…"
        } else if (layoutMode == ComposerLayoutMode.NARROW) {
            "${compactFooterText(model, limits.model + 3)}  ▾"
        } else {
            "${compactFooterText(provider, limits.provider)} / ${compactFooterText(model, limits.model)}  ▾"
        }
        targetButton.accessibleContext.accessibleName = if (status.configured) {
            "当前模型：$provider / $model"
        } else {
            "未连接模型，配置 API Key"
        }
    }

    private fun showModelSelector(forceRefresh: Boolean = false) {
        if (service.isRunning() || commitAi.isRunning) return
        val generation = ++modelSelectorGeneration
        var resultHandled = false
        setRunStatus("正在加载模型…")
        targetButton.isEnabled = false
        modelCatalog.loadCurrent(
            forceRefresh = forceRefresh,
            onFinished = {
                if (disposed || generation != modelSelectorGeneration) return@loadCurrent
                val busy = service.isRunning() || commitAi.isRunning
                targetButton.isEnabled = !busy
                if (!resultHandled && !busy && runStatusLabel.text == "正在加载模型…") {
                    setRunStatus("模型配置已更新，请重新打开模型列表。")
                }
            },
        ) { catalog ->
            if (disposed || generation != modelSelectorGeneration) return@loadCurrent
            val busy = service.isRunning() || commitAi.isRunning
            if (busy) return@loadCurrent
            val currentProviderId = OmniCodeSettingsService.getInstance().snapshot().providerId
            if (catalog.providerId != currentProviderId) return@loadCurrent
            resultHandled = true
            setRunStatus(
                modelCatalogStatusText(catalog),
                isError = catalog.error != null,
                detail = catalog.error,
            )
            showModelPopup(catalog)
        }
    }

    internal fun showProviderSelector() {
        if (service.isRunning() || commitAi.isRunning) return
        val currentId = OmniCodeSettingsService.getInstance().snapshot().providerId
        val allProviders = ProviderPresets.all
        val listModel = DefaultListModel<ProviderPreset>()
        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(34)
            emptyText.text = "没有匹配的供应商"
            cellRenderer = object : ColoredListCellRenderer<ProviderPreset>() {
                override fun customizeCellRenderer(
                    list: JList<out ProviderPreset>,
                    value: ProviderPreset,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(if (value.id == currentId) "✓  " else "   ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(value.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("   ${providerKind(value)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
        val search = SearchTextField(false).apply {
            textEditor.emptyText.text = "搜索供应商"
        }
        fun refill(query: String) {
            listModel.clear()
            allProviders.filter { preset ->
                query.isBlank() || preset.displayName.contains(query, ignoreCase = true) ||
                    preset.id.contains(query, ignoreCase = true)
            }.forEach(listModel::addElement)
            val selectedIndex = (0 until listModel.size).firstOrNull { listModel.get(it).id == currentId } ?: 0
            if (listModel.size > 0) list.selectedIndex = selectedIndex
        }
        refill("")

        val content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(JBUI.scale(390), JBUI.scale(470))
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JBLabel("选择供应商").apply {
                        foreground = OmniCodeUiPalette.primary
                        font = JBFont.label().asBold()
                    })
                    add(JBLabel("每个供应商独立保存 API Key").apply {
                        foreground = OmniCodeUiPalette.secondary
                        font = JBFont.small()
                    })
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                isOpaque = false
                add(search, BorderLayout.NORTH)
                add(JBScrollPane(list).apply { border = JBUI.Borders.emptyTop(2) }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(2)
                add(JBLabel("选择后可从右侧模型菜单加载可用模型").apply {
                    foreground = OmniCodeUiPalette.secondary
                    font = JBFont.small()
                }, BorderLayout.WEST)
                add(flatButton("管理…", "打开供应商设置").apply {
                    addActionListener {
                        activePopup?.cancel()
                        openProviderSettings()
                    }
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }

        fun chooseProvider() {
            val selected = list.selectedValue ?: return
            val settings = OmniCodeSettingsService.getInstance()
            val snapshot = settings.snapshot()
            if (snapshot.providerId != selected.id) {
                settings.activateProvider(selected.id)
                modelSelectorGeneration++
                targetButton.isEnabled = true
                modelCatalog.invalidate()
                setRunStatus("已切换到 ${selected.displayName}")
                refreshProviderStatus()
            }
            activePopup?.cancel()
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1) chooseProvider()
            }
        })
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chooseProvider")
        list.actionMap.put("chooseProvider", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = chooseProvider()
        })
        search.textEditor.document.addDocumentListener(SimpleDocumentListener { refill(search.text.trim()) })
        installSearchListNavigation(search, list, ::chooseProvider)
        showPopupAbove(targetButton, content, search.textEditor)
        SwingUtilities.invokeLater { list.ensureIndexIsVisible(list.selectedIndex) }
    }

    private fun showModelPopup(catalog: ProviderModelCatalog) {
        val allModels = catalog.models.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val activeModel = OmniCodeSettingsService.getInstance().snapshot().model
        val defaultView = modelCatalogView(allModels, activeModel = activeModel)
        val hiddenNonChatCount = defaultView.hiddenNonChatCount
        val listModel = DefaultListModel<String>()
        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(34)
            emptyText.text = if (allModels.isEmpty()) {
                "供应商未返回模型，可使用下方“输入模型 ID…”"
            } else {
                "没有匹配的模型"
            }
            cellRenderer = object : ColoredListCellRenderer<String>() {
                override fun customizeCellRenderer(
                    list: JList<out String>,
                    value: String,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(if (value == activeModel) "✓  " else "   ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (value == activeModel) append("   当前", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    val kind = classifyModelCatalogKind(value)
                    if (!kind.codingChatCandidate) {
                        append("   ${kind.displayName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }
        val search = SearchTextField(false).apply {
            textEditor.emptyText.text = "搜索模型"
        }
        val showAllModels = JBCheckBox(
            if (hiddenNonChatCount > 0) "显示全部（含 $hiddenNonChatCount 个专用模型）" else "显示全部模型",
        ).apply {
            isOpaque = false
            isVisible = hiddenNonChatCount > 0
            toolTipText = "默认隐藏明确用于图片、Embedding、音频、实时、审核等非编程对话用途的模型"
        }
        val filterHint = JBLabel().apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
        }
        fun refill(query: String) {
            val selected = list.selectedValue
            listModel.clear()
            val view = modelCatalogView(
                models = allModels,
                activeModel = activeModel,
                query = query,
                showAll = showAllModels.isSelected,
            )
            view.models.forEach(listModel::addElement)
            filterHint.text = when {
                allModels.isEmpty() && activeModel.isNotBlank() ->
                    "供应商未返回模型列表；已保留当前模型，也可手动输入"
                allModels.isEmpty() -> "供应商未返回模型列表，请手动输入模型 ID"
                showAllModels.isSelected -> "显示全部 ${view.totalCount} 个模型；专用模型带用途标签"
                hiddenNonChatCount > 0 -> "优先显示编程对话模型，已隐藏 $hiddenNonChatCount 个专用模型"
                else -> "显示当前账号可用于选择的模型"
            }
            val current = selected ?: activeModel
            val index = (0 until listModel.size).firstOrNull { listModel.get(it) == current } ?: 0
            if (listModel.size > 0) list.selectedIndex = index
        }
        refill("")

        fun selectModel(selected: String) {
            val normalized = selected.trim()
            if (normalized.isEmpty()) return
            val settings = OmniCodeSettingsService.getInstance()
            val snapshot = settings.snapshot()
            if (snapshot.providerId != catalog.providerId) return
            settings.update(snapshot.copy(model = normalized))
            activePopup?.cancel()
            refreshProviderStatus()
            setRunStatus("已切换模型 · $normalized")
        }
        fun chooseSelected() = list.selectedValue?.let(::selectModel) ?: Unit
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1) chooseSelected()
            }
        })
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "choose")
        list.actionMap.put("choose", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = chooseSelected()
        })
        search.textEditor.document.addDocumentListener(SimpleDocumentListener { refill(search.text.trim()) })
        showAllModels.addActionListener { refill(search.text.trim()) }
        installSearchListNavigation(search, list, ::chooseSelected)

        val content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(520))
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JBLabel("选择模型").apply {
                        foreground = OmniCodeUiPalette.primary
                        font = JBFont.label().asBold()
                    })
                    add(JBLabel(catalog.providerName.ifBlank { catalog.providerId }).apply {
                        foreground = OmniCodeUiPalette.secondary
                        font = JBFont.small()
                    })
                }, BorderLayout.WEST)
                add(flatButton(if (catalog.error != null) "重试" else "刷新", "根据已保存的 API Key 重新加载").apply {
                    addActionListener {
                        activePopup?.cancel()
                        showModelSelector(forceRefresh = true)
                    }
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(search, BorderLayout.NORTH)
                    add(showAllModels, BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
                add(JBScrollPane(list), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JBLabel(
                        if (allModels.isEmpty() && activeModel.isNotBlank()) {
                            "当前配置 · 未加载到供应商模型列表"
                        } else {
                            modelCatalogSourceText(catalog)
                        },
                    ).apply {
                        foreground = if (catalog.error == null) OmniCodeUiPalette.secondary else OmniCodeUiPalette.error
                        font = JBFont.small()
                    })
                    add(filterHint)
                    val detail = catalog.error?.let { "加载错误：$it" }
                        ?: catalog.status.takeUnless { catalog.discoveredRemotely }
                    if (!detail.isNullOrBlank()) {
                        add(GrowingTextArea(detail).apply {
                            foreground = if (catalog.error == null) OmniCodeUiPalette.secondary else OmniCodeUiPalette.error
                            font = JBFont.small()
                            toolTipText = detail.take(500)
                        })
                    }
                }, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(flatButton("输入模型 ID…", "手动使用未出现在供应商列表中的模型").apply {
                        addActionListener {
                            val entered = Messages.showInputDialog(
                                project,
                                "输入供应商支持的模型 ID。该值不会绕过供应商权限校验。",
                                "手动选择模型",
                                null,
                                activeModel,
                                null,
                            ) ?: return@addActionListener
                            selectModel(entered)
                        }
                    })
                    add(flatButton("切换供应商…", "选择其他 API 服务供应商").apply {
                        addActionListener {
                            activePopup?.cancel()
                            showProviderSelector()
                        }
                    })
                    add(flatButton("设置…", "打开供应商设置").apply {
                        addActionListener {
                            activePopup?.cancel()
                            openProviderSettings()
                        }
                    })
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        showPopupAbove(targetButton, content, search.textEditor)
        SwingUtilities.invokeLater { list.ensureIndexIsVisible(list.selectedIndex) }
    }

    private fun <T> installSearchListNavigation(
        search: SearchTextField,
        list: JBList<T>,
        choose: () -> Unit,
    ) {
        val editor = search.textEditor
        editor.getInputMap(JComponent.WHEN_FOCUSED).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "omnicode.chooseSearchResult")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "omnicode.nextSearchResult")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "omnicode.previousSearchResult")
        }
        editor.actionMap.put("omnicode.chooseSearchResult", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = choose()
        })

        fun moveSelection(delta: Int) {
            if (list.model.size == 0) return
            val current = list.selectedIndex.takeIf { it >= 0 } ?: 0
            list.selectedIndex = (current + delta).coerceIn(0, list.model.size - 1)
            list.ensureIndexIsVisible(list.selectedIndex)
        }
        editor.actionMap.put("omnicode.nextSearchResult", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = moveSelection(1)
        })
        editor.actionMap.put("omnicode.previousSearchResult", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = moveSelection(-1)
        })
    }

    private fun showPopupAbove(anchor: JComponent, content: JComponent, focus: JComponent) {
        activePopup?.cancel()
        val popupPlacement = runCatching {
            val configuration = anchor.graphicsConfiguration ?: return@runCatching null
            val screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            val screenBounds = configuration.bounds
            val screenSize = Dimension(
                screenBounds.width - screenInsets.left - screenInsets.right - JBUI.scale(24),
                screenBounds.height - screenInsets.top - screenInsets.bottom - JBUI.scale(24),
            )
            val availableSize = popupAvailableSize(
                screen = screenSize,
                panel = Dimension(this@OmniCodeChatPanel.width, this@OmniCodeChatPanel.height),
            )
            content.preferredSize = fitPopupSize(content.preferredSize, availableSize)
            content.maximumSize = content.preferredSize
            val screenTop = screenBounds.y + screenInsets.top
            val spaceAbove = anchor.locationOnScreen.y - screenTop
            popupVerticalOffset(content.preferredSize.height, spaceAbove, anchor.height, JBUI.scale(6))
        }.getOrNull()
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, focus)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        activePopup = popup
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (activePopup === popup) activePopup = null
                requestComposerFocusLater()
            }
        })
        val offset = popupPlacement ?: -content.preferredSize.height - JBUI.scale(6)
        popup.show(RelativePoint(anchor, Point(0, offset)))
    }

    private fun providerKind(preset: ProviderPreset): String = when {
        preset.id == "ollama" || preset.id == "lmstudio" -> "本地"
        preset.id == "custom" -> "自定义"
        else -> "API"
    }

    private fun compactFooterText(value: String, maxLength: Int): String =
        if (value.length <= maxLength) value else value.take(maxLength - 1) + "…"

    internal fun openSettings() {
        openProviderSettings()
    }

    internal fun openProviderSettings() {
        settingsNavigator(OmniCodeSettingsPage.PROVIDERS)
    }

    internal fun openPlatformSettings() {
        settingsNavigator(OmniCodeSettingsPage.GENERAL)
    }

    internal fun openUsageAndHistory() {
        settingsNavigator(OmniCodeSettingsPage.USAGE)
    }

    internal fun refreshAfterSettings() {
        refreshProviderStatus()
        updateSandboxButton()
        updateResponsiveLayout()
    }

    internal fun focusComposer() {
        requestComposerFocusLater()
    }

    internal fun generateCommitMessage() {
        if (canGenerateCommitMessage()) commitAi.start()
    }

    internal fun startNewChat() {
        if (canStartNewChat()) clearConversation()
    }

    internal fun canStartNewChat(): Boolean = !disposed && !service.isRunning() && !commitAi.isRunning

    internal fun canGenerateCommitMessage(): Boolean = canStartNewChat()

    internal fun canExportResearchPackage(): Boolean =
        !researchExportInProgress && canStartNewChat() && service.historySnapshot().isNotEmpty()

    internal fun exportResearchPackage() {
        if (!canExportResearchPackage()) {
            val message = when {
                researchExportInProgress -> "研究包正在导出，请完成或取消当前导出。"
                service.isRunning() || commitAi.isRunning -> "当前任务运行结束后才能导出研究包。"
                else -> "当前没有可导出的研究记录。"
            }
            setRunStatus(message, isError = true)
            return
        }
        researchExportInProgress = true
        val messages = service.historySnapshot()
        val mode = service.conversationModeSnapshot()
        setRunStatus("正在生成脱敏的可复现实验研究包…")
        attachmentScope.launch {
            val result = runCatching {
                val settings = OmniCodeSettingsService.getInstance()
                val platform = OmniCodePlatformSettingsService.getInstance()
                val connection = settings.providerConnectionAsync()
                val redactor = DefaultSensitiveDataRedactor(
                    collectResearchExportSecrets(settings, platform, connection),
                )
                ReproducibleResearchPackageExporter(redactor).export(
                    ResearchPackageExportRequest(
                        messages = messages,
                        mode = mode,
                        provider = connection.preset.displayName,
                        model = connection.model,
                        projectName = project.name,
                    ),
                )
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val researchPackage = result.getOrElse { error ->
                    researchExportInProgress = false
                    setRunStatus("研究包生成失败。", isError = true, detail = error.message)
                    return@invokeLater
                }
                val chooser = JFileChooser(project.basePath).apply {
                    dialogTitle = "导出可复现实验研究包"
                    approveButtonText = "导出"
                    selectedFile = java.io.File(project.basePath.orEmpty(), researchPackage.suggestedFileName)
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Markdown (*.md)", "md")
                    accessory = JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        border = JBUI.Borders.emptyLeft(12)
                        add(JBLabel("导出摘要").apply { font = JBFont.label().asBold() })
                        add(Box.createVerticalStrut(JBUI.scale(6)))
                        add(JBLabel("${researchPackage.exportedMessageCount} 条消息"))
                        add(JBLabel("${researchPackage.evidenceCount} 条工具/命令证据"))
                        add(JBLabel("SYSTEM 已排除 ${researchPackage.excludedSystemMessageCount} 条"))
                        add(JBLabel(if (researchPackage.truncated) "部分内容已按安全上限截断" else "内容未触发截断"))
                        add(Box.createVerticalStrut(JBUI.scale(8)))
                        add(JBLabel("分享前请人工检查敏感信息").apply {
                            foreground = OmniCodeUiPalette.warning
                            font = JBFont.small()
                        })
                    }
                }
                if (chooser.showSaveDialog(this@OmniCodeChatPanel) != JFileChooser.APPROVE_OPTION) {
                    researchExportInProgress = false
                    setRunStatus("")
                    requestComposerFocusLater()
                    return@invokeLater
                }
                var destination = chooser.selectedFile.toPath().toAbsolutePath().normalize()
                if (!destination.fileName.toString().endsWith(".md", ignoreCase = true)) {
                    destination = destination.resolveSibling(destination.fileName.toString() + ".md")
                }
                prepareResearchPackageWrite(researchPackage, destination)
            }, ModalityState.any())
        }
    }

    private fun prepareResearchPackageWrite(
        researchPackage: dev.omnicode.service.ReproducibleResearchPackage,
        destination: Path,
    ) {
        setRunStatus("正在安全检查导出目标…")
        attachmentScope.launch {
            val result = runCatching {
                val writer = ResearchPackageMarkdownWriter()
                val expectedTarget = if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    writer.captureTargetIdentity(destination)
                } else {
                    null
                }
                PendingResearchPackageWrite(
                    writer = writer,
                    policy = if (expectedTarget == null) {
                        ResearchPackageWritePolicy.CREATE_NEW
                    } else {
                        ResearchPackageWritePolicy.REPLACE_MATCHING
                    },
                    expectedTarget = expectedTarget,
                )
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val pending = result.getOrElse { error ->
                    researchExportInProgress = false
                    setRunStatus("无法使用所选导出目标。", isError = true, detail = error.message)
                    return@invokeLater
                }
                val expected = pending.expectedTarget
                if (expected != null && Messages.showYesNoDialog(
                        project,
                        "文件已存在（${attachmentDisplaySize(expected.size)}），是否覆盖？\n$destination\n\n" +
                            "若文件在确认后发生变化，导出会自动拒绝覆盖。",
                        "导出研究包",
                        "覆盖",
                        "取消",
                        Messages.getWarningIcon(),
                    ) != Messages.YES
                ) {
                    researchExportInProgress = false
                    setRunStatus("已取消导出。")
                    return@invokeLater
                }
                writeResearchPackage(researchPackage, destination, pending)
            }, ModalityState.any())
        }
    }

    private fun writeResearchPackage(
        researchPackage: dev.omnicode.service.ReproducibleResearchPackage,
        destination: Path,
        pending: PendingResearchPackageWrite,
    ) {
        setRunStatus("正在写入研究包…")
        attachmentScope.launch {
            val result = runCatching {
                pending.writer.writeAtomically(
                    researchPackage,
                    destination,
                    pending.policy,
                    pending.expectedTarget,
                )
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                result.onSuccess { written ->
                    setRunStatus("研究包已导出：${written.fileName}")
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(written)?.let { file ->
                        OpenFileDescriptor(project, file).navigate(true)
                    }
                }.onFailure { error ->
                    setRunStatus("研究包写入失败。", isError = true, detail = error.message)
                }
                researchExportInProgress = false
                requestComposerFocusLater()
            }, ModalityState.any())
        }
    }

    internal fun showHistory() {
        if (!canStartNewChat()) return
        setRunStatus("正在加载历史记录…")
        service.listConversationHistory { records ->
            if (disposed) return@listConversationHistory
            if (records.isEmpty()) {
                setRunStatus("暂无已保存的对话。")
                requestComposerFocusLater()
                return@listConversationHistory
            }
            val dialog = ConversationHistoryDialog(project, records, service::deleteConversation)
            if (!dialog.showAndGet()) {
                setRunStatus("")
                requestComposerFocusLater()
                return@listConversationHistory
            }
            val selected = dialog.selectedConversationId ?: run {
                requestComposerFocusLater()
                return@listConversationHistory
            }
            service.restoreConversation(selected) { restored ->
                if (restored && !disposed) {
                    resetConversationView()
                    synchronizeComposerModeFromConversation()
                    restoreHistory()
                    setRunStatus("对话已恢复。")
                } else if (!disposed) {
                    setRunStatus("无法恢复该对话。", isError = true)
                }
                requestComposerFocusLater()
            }
        }
    }

    private fun isNearBottom(): Boolean {
        val bar = conversationScroll.verticalScrollBar
        return bar.maximum - (bar.value + bar.visibleAmount) <= JBUI.scale(72)
    }

    private fun scrollToBottom(force: Boolean) {
        if (!force) return
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            val bar = conversationScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun boundedToolResult(value: String): String {
        if (value.length <= MAX_TOOL_RESULT_CHARS) return value
        return value.take(MAX_TOOL_RESULT_CHARS) + "\n[tool result truncated in UI]"
    }

    private data class TranscriptBlock(
        val component: JComponent,
        var characters: Int,
    )

    private companion object {
        const val STREAM_FLUSH_MS = 40
        const val PET_TERMINAL_STATE_MS = 2_800
        const val MAX_TRANSCRIPT_CHARS = 500_000
        const val MAX_TOOL_RESULT_CHARS = 4_000
        const val SMALL_TOOL_WINDOW_WIDTH = 360
        const val FILE_MENTION_DEBOUNCE_MS = 120L
        val CLIPBOARD_IMAGE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

private class ChatPetLayer(
    private val content: JComponent,
    private val pet: JComponent,
) : JLayeredPane() {
    init {
        isOpaque = false
        add(content, DEFAULT_LAYER)
        add(pet, PALETTE_LAYER)
    }

    override fun doLayout() {
        content.setBounds(0, 0, width, height)
        if (!pet.isVisible) {
            pet.setBounds(0, 0, 0, 0)
            return
        }
        val preferred = pet.preferredSize
        val petWidth = preferred.width.coerceAtMost((width - JBUI.scale(16)).coerceAtLeast(0))
        val petHeight = preferred.height.coerceAtMost((height - JBUI.scale(16)).coerceAtLeast(0))
        pet.setBounds(
            (width - petWidth - JBUI.scale(12)).coerceAtLeast(0),
            JBUI.scale(10),
            petWidth,
            petHeight,
        )
    }

    override fun getPreferredSize(): Dimension = content.preferredSize

    override fun getMinimumSize(): Dimension = content.minimumSize
}

internal fun selectedComposerPopupItem(popup: JPopupMenu): JMenuItem? {
    val index = popup.selectionModel.selectedIndex
    if (index !in 0 until popup.componentCount) return null
    return popup.getComponent(index) as? JMenuItem
}

internal fun nextPopupSelectionIndex(current: Int, itemCount: Int, delta: Int): Int {
    if (itemCount <= 0 || delta == 0) return -1
    val start = if (current in 0 until itemCount) current else if (delta > 0) -1 else 0
    return Math.floorMod(start + delta, itemCount)
}

private data class PendingResearchPackageWrite(
    val writer: ResearchPackageMarkdownWriter,
    val policy: ResearchPackageWritePolicy,
    val expectedTarget: ResearchPackageTargetIdentity?,
)

private fun activateSelectedComposerPopup(popup: JPopupMenu?): Boolean {
    if (popup?.isVisible != true) return false
    val item = selectedComposerPopupItem(popup) ?: return false
    item.doClick()
    return true
}

internal enum class ComposerEnterAction {
    SEND,
    INSERT_NEWLINE,
    SHOW_BUSY,
    IGNORE,
}

internal data class ComposerModePresentation(
    val label: String,
    val menuSummary: String,
    val description: String,
    val runningStatus: String,
)

internal fun composerModePresentation(mode: AgentMode): ComposerModePresentation = when (mode) {
    AgentMode.AGENT -> ComposerModePresentation(
        label = "Agent",
        menuSummary = "执行任务",
        description = "读取、修改项目并运行所需工具",
        runningStatus = "Agent 正在处理…",
    )
    AgentMode.PLAN -> ComposerModePresentation(
        label = "Plan",
        menuSummary = "只读规划",
        description = "只分析项目并输出实施计划，不修改文件或执行命令",
        runningStatus = "Plan 正在制定计划…",
    )
    AgentMode.RESEARCH -> ComposerModePresentation(
        label = "Research",
        menuSummary = "科研与实验",
        description = "阅读资料并在审批后的沙箱中运行实验；不自动修改项目文件",
        runningStatus = "Research 正在整理证据与实验…",
    )
}

internal data class ComposerSubmission(
    val prompt: String,
    val mode: AgentMode,
    val strategy: AgentExecutionStrategy,
)

internal data class RecoverableSubmission(
    val submission: UserSubmission,
    val mode: AgentMode,
    val strategy: AgentExecutionStrategy,
)

internal data class PendingPlanExecution(
    val fingerprint: String,
)

internal fun planFingerprint(planText: String): String = MessageDigest.getInstance("SHA-256")
    .digest(planText.toByteArray(StandardCharsets.UTF_8))
    .take(8)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun planExecutionPrompt(fingerprint: String): String =
    "执行上一条已确认的实施计划（计划指纹 $fingerprint）。先核对当前文件状态，然后逐步修改、运行必要验证并汇报结果。"

internal fun shouldOfferSubmissionRecovery(status: AgentRunStatus): Boolean = when (status) {
    AgentRunStatus.COMPLETED -> false
    AgentRunStatus.CANCELLED,
    AgentRunStatus.FAILED,
    AgentRunStatus.BUDGET_EXHAUSTED -> true
}

internal data class ComposerModeState(
    val selectedMode: AgentMode = AgentMode.AGENT,
    val executionStrategy: AgentExecutionStrategy = AgentExecutionStrategy.SINGLE,
) {
    fun select(mode: AgentMode): ComposerModeState = copy(selectedMode = mode)

    fun selectExecutionStrategy(strategy: AgentExecutionStrategy): ComposerModeState = copy(executionStrategy = strategy)

    fun snapshot(prompt: String): ComposerSubmission = ComposerSubmission(prompt, selectedMode, executionStrategy)
}

internal fun synchronizeComposerModeState(
    current: ComposerModeState,
    conversationMode: AgentMode,
): ComposerModeState = current.select(conversationMode)

internal fun nextComposerMode(mode: AgentMode): AgentMode = when (mode) {
    AgentMode.AGENT -> AgentMode.PLAN
    AgentMode.PLAN -> AgentMode.RESEARCH
    AgentMode.RESEARCH -> AgentMode.AGENT
}

internal fun composerEnterAction(
    isBusy: Boolean,
    explicitSend: Boolean,
    shiftDown: Boolean,
    promptPopupVisible: Boolean,
): ComposerEnterAction = when {
    promptPopupVisible -> ComposerEnterAction.IGNORE
    shiftDown -> ComposerEnterAction.INSERT_NEWLINE
    isBusy && explicitSend -> ComposerEnterAction.SHOW_BUSY
    isBusy -> ComposerEnterAction.INSERT_NEWLINE
    else -> ComposerEnterAction.SEND
}

internal data class FooterTextLimits(
    val provider: Int,
    val model: Int,
)

internal enum class ComposerLayoutMode {
    NARROW,
    COMPACT,
    REGULAR,
}

internal fun composerLayoutMode(width: Int): ComposerLayoutMode = when (width) {
    in 1 until 340 -> ComposerLayoutMode.NARROW
    in 340 until 480 -> ComposerLayoutMode.COMPACT
    else -> ComposerLayoutMode.REGULAR
}

internal fun composerModeButtonText(mode: AgentMode, layoutMode: ComposerLayoutMode): String = when (mode) {
    AgentMode.AGENT -> "Agent"
    AgentMode.PLAN -> if (layoutMode == ComposerLayoutMode.NARROW) "Plan" else "Plan · 只读"
    AgentMode.RESEARCH -> if (layoutMode == ComposerLayoutMode.NARROW) "Research" else "Research · 实验"
}

internal fun teamButtonText(strategy: AgentExecutionStrategy, layoutMode: ComposerLayoutMode): String = when {
    layoutMode == ComposerLayoutMode.NARROW && strategy == AgentExecutionStrategy.TEAM -> "T · 开"
    layoutMode == ComposerLayoutMode.NARROW -> "T"
    strategy == AgentExecutionStrategy.TEAM -> "Team · 开"
    else -> "Team"
}

internal fun executionStrategyLabel(strategy: AgentExecutionStrategy): String = when (strategy) {
    AgentExecutionStrategy.SINGLE -> "单代理"
    AgentExecutionStrategy.TEAM -> "Team 协作"
}

internal data class ComposerToolbarVisibility(
    val showSandbox: Boolean,
    val showProvider: Boolean,
)

internal fun composerToolbarVisibility(
    agentMode: AgentMode,
    layoutMode: ComposerLayoutMode,
    sandboxMode: SandboxMode = SandboxMode.WORKSPACE_WRITE,
): ComposerToolbarVisibility = ComposerToolbarVisibility(
    showSandbox = agentMode != AgentMode.PLAN && sandboxMode == SandboxMode.DANGER_FULL_ACCESS,
    showProvider = layoutMode != ComposerLayoutMode.NARROW,
)

internal fun createComposerToolbar(
    addButton: JComponent,
    modeButton: JComponent,
    teamButton: JComponent,
    sandboxControl: JComponent,
    stopButton: JComponent,
    sendButton: JComponent,
): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    isOpaque = false
    border = JBUI.Borders.emptyTop(6)
    add(addButton)
    add(Box.createHorizontalStrut(JBUI.scale(6)))
    add(modeButton)
    add(Box.createHorizontalStrut(JBUI.scale(4)))
    add(teamButton)
    add(sandboxControl)
    add(Box.createHorizontalGlue())
    add(stopButton)
    add(sendButton)
}

internal fun footerTextLimits(width: Int): FooterTextLimits = when (width) {
    in 1 until 340 -> FooterTextLimits(provider = 8, model = 11)
    in 340 until 460 -> FooterTextLimits(provider = 12, model = 16)
    else -> FooterTextLimits(provider = 20, model = 24)
}

internal data class SandboxButtonPresentation(
    val text: String,
    val tooltip: String,
    val dangerous: Boolean,
)

internal fun sandboxButtonPresentation(
    agentMode: AgentMode,
    sandboxMode: SandboxMode,
    width: Int,
): SandboxButtonPresentation {
    if (agentMode == AgentMode.PLAN) {
        return SandboxButtonPresentation(
            text = "只读",
            tooltip = "Plan 只读模式不会执行命令；沙箱设置仅在 Agent / Research 模式执行命令时生效",
            dangerous = false,
        )
    }
    val fullText = sandboxMode.name.lowercase().replace('_', '-')
    val compact = composerLayoutMode(width) != ComposerLayoutMode.REGULAR
    return SandboxButtonPresentation(
        text = if (compact) {
            if (sandboxMode == SandboxMode.DANGER_FULL_ACCESS) "完全访问" else "工作区"
        } else {
            if (sandboxMode == SandboxMode.DANGER_FULL_ACCESS) "完全访问" else "workspace-write"
        },
        tooltip = if (agentMode == AgentMode.RESEARCH) {
            "Research 实验命令沙箱：$fullText；不会开放文件修改工具"
        } else {
            "沙箱模式：$fullText"
        },
        dangerous = sandboxMode == SandboxMode.DANGER_FULL_ACCESS,
    )
}

internal fun fitPopupSize(preferred: Dimension, available: Dimension): Dimension {
    val maxWidth = available.width.coerceAtLeast(1)
    val maxHeight = available.height.coerceAtLeast(1)
    return Dimension(
        preferred.width.coerceIn(minOf(280, maxWidth), maxWidth),
        preferred.height.coerceIn(minOf(180, maxHeight), maxHeight),
    )
}

internal fun popupAvailableSize(screen: Dimension, panel: Dimension): Dimension {
    val panelWidth = panel.width.takeIf { it > 0 }
        ?.minus(24)
        ?.coerceAtLeast(1)
        ?: screen.width
    val panelHeight = panel.height.takeIf { it > 0 }
        ?.let { (it * 0.7).toInt().coerceAtLeast(180) }
        ?: screen.height
    return Dimension(
        minOf(screen.width, panelWidth).coerceAtLeast(1),
        minOf(screen.height, panelHeight).coerceAtLeast(1),
    )
}

internal fun modelCatalogSourceText(catalog: ProviderModelCatalog): String = when {
    catalog.error != null -> "${catalog.models.size} 个模型 · 仅显示当前配置（API 加载失败）"
    catalog.discoveredRemotely -> "${catalog.models.size} 个可用模型 · 来自供应商 API"
    else -> "${catalog.models.size} 个模型 · 来自当前配置或供应商预设"
}

internal fun modelCatalogStatusText(catalog: ProviderModelCatalog): String = when {
    catalog.error != null -> "模型列表加载失败"
    catalog.discoveredRemotely -> "已加载 ${catalog.models.size} 个可用模型"
    else -> "已加载 ${catalog.models.size} 个模型"
}

internal fun popupVerticalOffset(contentHeight: Int, spaceAbove: Int, anchorHeight: Int, gap: Int = 6): Int =
    if (spaceAbove >= contentHeight + gap) -contentHeight - gap else anchorHeight + gap

private fun interface SimpleDocumentListener : javax.swing.event.DocumentListener {
    fun changed()

    override fun insertUpdate(event: javax.swing.event.DocumentEvent) = changed()
    override fun removeUpdate(event: javax.swing.event.DocumentEvent) = changed()
    override fun changedUpdate(event: javax.swing.event.DocumentEvent) = changed()
}

internal enum class ChatBodyState {
    SETUP,
    EMPTY,
    TRANSCRIPT,
}

internal fun chatBodyState(hasTranscript: Boolean, providerConfigured: Boolean?): ChatBodyState = when {
    hasTranscript -> ChatBodyState.TRANSCRIPT
    providerConfigured == false -> ChatBodyState.SETUP
    else -> ChatBodyState.EMPTY
}

/**
 * Maps non-terminal Agent events to their transient desktop-pet state. Run results are handled
 * separately so a terminal failure remains ERROR, while a recoverable tool error is cleared by
 * the next loop's Status event.
 */
internal fun desktopPetStateForAgentEvent(event: AgentEvent): DesktopPetState? = when (event) {
    is AgentEvent.Status,
    is AgentEvent.TextDelta,
    is AgentEvent.DelegatedAgentStarted,
    is AgentEvent.DelegatedAgentCompleted,
    -> DesktopPetState.THINKING
    is AgentEvent.ToolRequested -> DesktopPetState.TOOL
    is AgentEvent.ToolCompleted -> if (event.isError) DesktopPetState.ERROR else DesktopPetState.THINKING
    is AgentEvent.ModeSelected,
    is AgentEvent.ExecutionStrategySelected,
    is AgentEvent.ToolApprovalResolved,
    is AgentEvent.UsageUpdated,
    is AgentEvent.BudgetWarning,
    -> null
}

internal fun delegateProgressStatus(status: AgentRunStatus): DelegateProgressStatus = when (status) {
    AgentRunStatus.COMPLETED -> DelegateProgressStatus.COMPLETED
    AgentRunStatus.CANCELLED -> DelegateProgressStatus.CANCELLED
    AgentRunStatus.FAILED,
    AgentRunStatus.BUDGET_EXHAUSTED,
    -> DelegateProgressStatus.FAILED
}

internal fun delegateCompletionStatusText(status: AgentRunStatus): String = when (status) {
    AgentRunStatus.COMPLETED -> "已完成"
    AgentRunStatus.CANCELLED -> "已取消"
    AgentRunStatus.FAILED -> "失败"
    AgentRunStatus.BUDGET_EXHAUSTED -> "已达到预算"
}

internal fun delegateRoleLabel(role: AgentRole): String = when (role) {
    AgentRole.EXPLORER -> "探索"
    AgentRole.PLANNER -> "规划"
    AgentRole.REVIEWER -> "评审"
    AgentRole.LEAD -> "主代理"
}

internal fun composerSendEnabled(
    isRunning: Boolean,
    isCommitAiRunning: Boolean,
    providerConfigured: Boolean,
    prompt: String,
    attachmentCount: Int = 0,
    pendingAttachmentLoads: Int = 0,
): Boolean = !isRunning && !isCommitAiRunning && providerConfigured && pendingAttachmentLoads == 0 &&
    (prompt.isNotBlank() || attachmentCount > 0)

private fun centeredStatePanel(
    title: String,
    description: String,
    customize: JPanel.() -> Unit,
): JComponent = JPanel(GridBagLayout()).apply {
    isOpaque = false
    val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(JBLabel("✦").apply {
            alignmentX = JComponent.CENTER_ALIGNMENT
            foreground = OmniCodeUiPalette.accent
            font = font.deriveFont(font.size2D + 14f)
        })
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(JBLabel(title).apply {
            alignmentX = JComponent.CENTER_ALIGNMENT
            foreground = OmniCodeUiPalette.primary
            font = JBFont.label().asBold().deriveFont(JBFont.label().size2D + 4f)
        })
        add(Box.createVerticalStrut(JBUI.scale(7)))
        add(JBLabel("<html><div style='text-align:center;width:320px'>$description</div></html>").apply {
            alignmentX = JComponent.CENTER_ALIGNMENT
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
        })
        customize()
    }
    add(content, GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        weightx = 1.0
        weighty = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.CENTER
        insets = Insets(JBUI.scale(16), JBUI.scale(22), JBUI.scale(16), JBUI.scale(22))
    })
}

internal data class ComposerSuggestion(
    val label: String,
    val prompt: String,
    val targetMode: AgentMode? = null,
)

internal fun defaultComposerSuggestions(): List<ComposerSuggestion> = listOf(
    ComposerSuggestion(
        "了解这个项目",
        "请先快速了解这个项目的结构、技术栈和关键入口，然后给我一份简明概览。",
    ),
    ComposerSuggestion(
        "审查当前改动",
        "请审查当前未提交的改动，指出高风险问题和可以立即改进的地方。",
    ),
    ComposerSuggestion(
        "定位并修复问题",
        "请分析当前项目中最值得优先处理的问题，先说明原因，再给出修复方案。",
    ),
    ComposerSuggestion(
        "实现一个新功能",
        "我想实现一个新功能。请先检查相关代码并列出最小可行实现方案。",
    ),
    ComposerSuggestion(
        "设计可复现实验",
        "帮我把研究问题拆成假设、变量、对照组、实验步骤、评价指标和复现清单。",
        AgentMode.RESEARCH,
    ),
    ComposerSuggestion(
        "分析论文与资料",
        "基于我上传的论文或资料提取研究问题、方法、证据、局限和可验证的后续实验；不要编造引用。",
        AgentMode.RESEARCH,
    ),
)

private fun responsiveSuggestionGrid(
    suggestions: List<ComposerSuggestion>,
    onSelected: (ComposerSuggestion) -> Unit,
): JComponent = object : JPanel() {
    private var columns = 2

    init {
        layout = GridLayout(0, columns, JBUI.scale(8), JBUI.scale(8))
        isOpaque = false
    }

    override fun doLayout() {
        val next = if (width >= JBUI.scale(400)) 2 else 1
        if (next != columns) {
            columns = next
            layout = GridLayout(0, columns, JBUI.scale(8), JBUI.scale(if (columns == 1) 6 else 8))
            revalidate()
        }
        super.doLayout()
    }
}.apply {
    suggestions.forEach { suggestion ->
        add(suggestionCard(suggestion.label) { onSelected(suggestion) })
    }
}

private fun primaryButton(text: String, tooltip: String? = null): JButton = object : JButton(text) {
    override fun getPreferredSize(): Dimension {
        val width = getFontMetrics(font).stringWidth(text) + JBUI.scale(28)
        return Dimension(width.coerceAtLeast(JBUI.scale(96)), JBUI.scale(34))
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = when {
                model.isPressed -> mixColors(background, Color.BLACK, 0.14)
                model.isRollover -> mixColors(background, Color.WHITE, 0.10)
                else -> background
            }
            g.fillRoundRect(0, 0, width, height, JBUI.scale(9), JBUI.scale(9))
            g.font = font
            g.color = Color.WHITE
            val metrics = g.fontMetrics
            g.drawString(text, (width - metrics.stringWidth(text)) / 2, (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            g.dispose()
        }
    }

    override fun paintBorder(graphics: Graphics) {
        if (hasFocus()) paintRoundedFocusRing(graphics, this, 8, Color.WHITE)
    }
}.apply {
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = true
    isFocusPainted = true
    isRolloverEnabled = true
    background = OmniCodeUiPalette.accent
    foreground = Color.WHITE
    font = JBFont.label().asBold()
    border = JBUI.Borders.empty()
    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    toolTipText = tooltip
    accessibleContext.accessibleName = tooltip ?: text
}

private fun composerActionButton(
    icon: Icon,
    background: java.awt.Color,
    foreground: java.awt.Color,
    tooltip: String,
): JButton = object : JButton(icon) {
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = when {
                isEnabled && model.isPressed -> mixColors(this.background, Color.BLACK, 0.14)
                isEnabled && model.isRollover -> mixColors(this.background, Color.WHITE, 0.10)
                else -> this.background
            }
            val arc = JBUI.scale(10)
            g.fillRoundRect(0, 0, width, height, arc, arc)
            icon?.paintIcon(this, g, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
        } finally {
            g.dispose()
        }
    }

    override fun paintBorder(graphics: Graphics) {
        if (!hasFocus()) return
        val focusColor = if (this.background == OmniCodeUiPalette.accent) Color.WHITE else OmniCodeUiPalette.accent
        paintRoundedFocusRing(graphics, this, 9, focusColor)
    }
}.apply {
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = true
    isFocusPainted = true
    isRolloverEnabled = true
    this.background = background
    this.foreground = foreground
    putClientProperty(ACTION_ICON_COLOR_KEY, foreground)
    border = JBUI.Borders.empty()
    val size = Dimension(JBUI.scale(32), JBUI.scale(32))
    preferredSize = size
    minimumSize = size
    maximumSize = size
    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    toolTipText = tooltip
    accessibleContext.accessibleName = tooltip
}

private fun suggestionCard(label: String, action: () -> Unit): JComponent = object : JButton(label) {
    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(44))

    override fun getMinimumSize(): Dimension = Dimension(0, JBUI.scale(44))

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(44))

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = when {
                model.isPressed -> OmniCodeUiPalette.controlPressed
                model.isRollover -> OmniCodeUiPalette.controlHover
                else -> OmniCodeUiPalette.surface
            }
            val arc = JBUI.scale(9)
            g.fillRoundRect(0, 0, width, height, arc, arc)

            val metrics = g.getFontMetrics(font)
            val baseline = (height - metrics.height) / 2 + metrics.ascent
            g.color = foreground
            g.drawString(text, JBUI.scale(12), baseline)
            val chevron = AllIcons.General.ChevronRight
            chevron.paintIcon(
                this,
                g,
                width - chevron.iconWidth - JBUI.scale(10),
                (height - chevron.iconHeight) / 2,
            )
        } finally {
            g.dispose()
        }
    }

    override fun paintBorder(graphics: Graphics) {
        if (hasFocus()) paintRoundedFocusRing(graphics, this, 8, OmniCodeUiPalette.accent)
    }
}.apply {
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = true
    isFocusPainted = true
    isRolloverEnabled = true
    foreground = OmniCodeUiPalette.primary
    font = JBFont.label()
    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    horizontalAlignment = javax.swing.SwingConstants.LEFT
    border = JBUI.Borders.empty()
    accessibleContext.accessibleName = label
    addActionListener { action() }
}

private object PaperPlaneIcon : Icon {
    override fun getIconWidth(): Int = JBUI.scale(16)

    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(component: java.awt.Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = (component as? JComponent)?.getClientProperty(ACTION_ICON_COLOR_KEY) as? Color
                ?: component?.foreground
                ?: Color.WHITE
            val scale = iconWidth / 16.0
            g.stroke = BasicStroke((1.5 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val path = Path2D.Double().apply {
                moveTo(x + 2 * scale, y + 2 * scale)
                lineTo(x + 14 * scale, y + 8 * scale)
                lineTo(x + 2 * scale, y + 14 * scale)
                lineTo(x + 5 * scale, y + 8 * scale)
                closePath()
            }
            g.draw(path)
            g.drawLine(
                (x + 5 * scale).toInt(),
                (y + 8 * scale).toInt(),
                (x + 14 * scale).toInt(),
                (y + 8 * scale).toInt(),
            )
        } finally {
            g.dispose()
        }
    }
}

private const val ACTION_ICON_COLOR_KEY = "omnicode.actionIconColor"

private fun mixColors(base: Color, overlay: Color, amount: Double): Color {
    val ratio = amount.coerceIn(0.0, 1.0)
    fun channel(left: Int, right: Int): Int = (left * (1 - ratio) + right * ratio).toInt().coerceIn(0, 255)
    return Color(
        channel(base.red, overlay.red),
        channel(base.green, overlay.green),
        channel(base.blue, overlay.blue),
        base.alpha,
    )
}

private fun paintRoundedFocusRing(
    graphics: Graphics,
    component: JComponent,
    radius: Int,
    color: Color,
) {
    val g = graphics.create() as Graphics2D
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        g.stroke = BasicStroke(JBUI.scale(2).toFloat())
        val inset = JBUI.scale(2)
        val arc = JBUI.scale(radius)
        g.drawRoundRect(
            inset,
            inset,
            (component.width - inset * 2 - 1).coerceAtLeast(0),
            (component.height - inset * 2 - 1).coerceAtLeast(0),
            arc,
            arc,
        )
    } finally {
        g.dispose()
    }
}
