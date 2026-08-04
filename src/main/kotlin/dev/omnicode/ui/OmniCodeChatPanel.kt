package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
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
import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.MessageRole
import dev.omnicode.model.UserAttachment
import dev.omnicode.model.UserSubmission
import dev.omnicode.service.AgentRunCallbacks
import dev.omnicode.service.AgentRecoveryAction
import dev.omnicode.service.DiscardedRecoverableWorkflow
import dev.omnicode.service.DiscardedWorkflowRestoreResult
import dev.omnicode.service.OmniCodeProjectService
import dev.omnicode.service.ProviderModelCatalog
import dev.omnicode.service.ProviderModelCatalogService
import dev.omnicode.service.ProviderStatus
import dev.omnicode.service.RecoverableWorkflow
import dev.omnicode.service.UnifiedTaskEntry
import dev.omnicode.service.classifyAgentFailure
import dev.omnicode.service.ReproducibleResearchPackageExporter
import dev.omnicode.service.ResearchPackageExportRequest
import dev.omnicode.service.ResearchExperimentLock
import dev.omnicode.provider.ProviderPreset
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ReasoningEffort
import dev.omnicode.provider.classifyModelCatalogKind
import dev.omnicode.provider.modelCatalogView
import dev.omnicode.provider.reasoningEffortOptions
import dev.omnicode.provider.recommendedOutputTokenFloor
import dev.omnicode.provider.resolveReasoningEffort
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.OmniCodeSettingsService
import dev.omnicode.settings.applyFullSpeedRuntimePreset
import dev.omnicode.settings.SandboxMode
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import dev.omnicode.plan.PlanBoard
import dev.omnicode.plan.PlanBoardService
import dev.omnicode.plan.PlanExecutionPolicy
import dev.omnicode.plan.PlanExecutionRequest
import dev.omnicode.plan.PlanStepState
import dev.omnicode.review.TaskChangeReviewService
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
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.awt.geom.Path2D
import java.util.ArrayDeque
import java.util.IdentityHashMap
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
    private val planNavigator: () -> Unit = {},
    private val reviewNavigator: () -> Unit = {},
    private val contextNavigator: () -> Unit = {},
    private val taskNavigator: () -> Unit = {},
    private val diagnosticsNavigator: () -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    private val planBoardService = PlanBoardService.getInstance(project)
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
    private val input = PromptTextArea("输入任务；/plan 规划，/review 审阅，@ 引用文件，! 选提示词…").apply {
        toolTipText = "支持 /plan、/review、/status、/model、/permissions、/mcp、/tasks；也可粘贴截图或拖入 PDF、Notebook、图片和代码"
    }
    private val attachments = mutableListOf<UserAttachment>()
    private val attachmentSourceKeys = linkedMapOf<String, UserAttachment>()
    private val pendingAttachmentSourceKeys = mutableSetOf<String>()
    private val workflowRecoveryImages = WorkflowRecoveryImageSelection()
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
    private val reasoningButton = flatButton("思考·自动  ▾", "选择模型推理强度").apply {
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        accessibleContext.accessibleName = "模型推理强度：自动"
    }
    private val addButton = composerControlButton("", "上传附件（也可从桌面或项目树拖入）").apply {
        icon = AllIcons.General.Add
        accessibleContext.accessibleName = "上传附件"
        accessibleContext.accessibleDescription = "选择、粘贴或拖入 PDF 论文、图片、Markdown、Notebook、科研资料、代码和安全文本文件"
    }
    private val modeButton = composerControlButton(
        "Agent",
        "Shift+Tab 切换 Agent / Claude Plan；Cmd/Ctrl+Shift+M 循环全部模式",
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
    private val contextButton = composerControlButton("上下文", "查看项目规则、固定/排除文件和上下文占用")
    private val sandboxControl = object : JPanel() {
        override fun getMaximumSize(): Dimension = preferredSize
    }.apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(contextButton)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
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
    private val checkpointUndoTimers = mutableSetOf<Timer>()

    @Volatile
    private var disposed = false
    private var scrollRequestPending = false
    private var activeRunSawText = false
    private var activeTurn: AssistantTurnPanel? = null
    private var activeTurnBlock: TranscriptBlock? = null
    private var transcriptCharacters = 0
    private var providerRefreshGeneration = 0
    private var modelSelectorGeneration = 0
    private var composerModeState = ComposerModeState()
    private var activeRunMode: AgentMode? = null
    private var activeRunStrategy: AgentExecutionStrategy? = null
    private var activeRunReasoningEffort: ReasoningEffort? = null
    private var activeWorkflowId: String? = null
    private var lastReviewWorkflowId: String? = null
    private var lastSubmission: RecoverableSubmission? = null
    private var recoveryTurn: AssistantTurnPanel? = null
    private var recoverableWorkflowTurn: AssistantTurnPanel? = null
    private var activeRecoveryWorkflow: RecoverableWorkflow? = null
    private var activePlanStepId: String? = null
    private var autoContinueApprovedPlan = false
    private var planRevisionBoardId: String? = null
    private var inlinePlanReviewCard: InlinePlanReviewCard? = null
    private var lastProviderStatus: ProviderStatus? = null
    private var promptPopup: JPopupMenu? = null
    private var fileMentionPopup: JPopupMenu? = null
    private var fileMentionGeneration = 0
    private var fileMentionJob: Job? = null
    private var suppressPromptPopup = false
    private lateinit var composerHost: JComponent
    private lateinit var composerToolbar: JPanel
    private lateinit var providerFooterControls: JPanel
    private val executionNavigation = ExecutionNavigationBar(::navigateExecutionSection).apply { isVisible = false }
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
        reasoningButton.addActionListener { showReasoningEffortMenu() }
        modeButton.addActionListener { showModeMenu(modeButton) }
        teamButton.addActionListener { toggleExecutionStrategy() }
        sandboxButton.addActionListener { settingsNavigator(OmniCodeSettingsPage.SANDBOX) }
        contextButton.addActionListener { contextNavigator() }
        input.document.addDocumentListener(SimpleDocumentListener {
            updateSendButtonState()
            updateComposerModeUi()
            SwingUtilities.invokeLater(::updateProjectFileMentionPopup)
        })
        installSendShortcuts()
        installComposerPopupNavigation()
        installClipboardAttachmentPaste()
        installPromptLibrary()
        synchronizeComposerModeFromConversation()
        restoreHistory()
        checkRecoverableWorkflows()
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
        petSettleTimer.stop()
        checkpointUndoTimers.forEach(Timer::stop)
        checkpointUndoTimers.clear()
        desktopPet.dispose()
        recoveryTurn?.clearRecoveryAction()
        recoveryTurn = null
        recoverableWorkflowTurn?.clearRecoveryAction()
        recoverableWorkflowTurn = null
        activeRecoveryWorkflow = null
        workflowRecoveryImages.reset()
        inlinePlanReviewCard?.let(Disposer::dispose)
        inlinePlanReviewCard = null
        lastSubmission = null
        attachmentDraftGeneration++
        clearAttachmentDropState()
        attachmentScope.cancel()
        promptPopup?.isVisible = false
        fileMentionPopup?.isVisible = false
        fileMentionGeneration++
        fileMentionJob?.cancel()
        activePopup?.cancel()
        modelSelectorGeneration++
        commitAi.dispose()
        service.interruptCurrentRun()
    }

    internal fun applyWorkshopSelection(resolved: ResolvedWorkshopSelection) {
        val colors = resolved.toWorkspaceColors()
        workshopColors = colors
        background = colors.background
        conversationScroll.viewport.background = colors.background
        composerCard.updateSurfaceColors(colors.surface, colors.border)
        targetButton.foreground = colors.secondaryText
        reasoningButton.foreground = colors.secondaryText
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

            composerToolbar = createComposerToolbar(
                addButton = addButton,
                modeButton = modeButton,
                teamButton = teamButton,
                sandboxControl = sandboxControl,
                stopButton = stopButton,
                sendButton = sendButton,
            )
            add(composerToolbar, BorderLayout.SOUTH)
        }

        composerCard = card
        installAttachmentDropSupport(composerCard)
        installAttachmentDropSupport(input)

        add(card, BorderLayout.CENTER)
        add(StretchPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 2, 0, 2)
            providerFooterControls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(targetButton)
                add(reasoningButton)
            }
            add(providerFooterControls, BorderLayout.WEST)
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

    private fun enqueueAttachmentPaths(
        paths: List<Path>,
        recoveryWorkflowId: String? = workflowRecoveryImages.captureTarget(),
    ) {
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(
                { if (!disposed) enqueueAttachmentPaths(paths, recoveryWorkflowId) },
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
                    workflowRecoveryImages.record(accepted.attachment, recoveryWorkflowId)
                    acceptedNames += accepted.attachment.fileName
                }
                if (acceptedNames.isNotEmpty()) renderAttachmentTray()

                val rejectedCount = duplicates + result.rejected.size + result.omittedByLimit
                val recoveryNames = result.accepted
                    .asSequence()
                    .map { it.attachment }
                    .filter { it.kind == AttachmentKind.IMAGE }
                    .filter { workflowRecoveryImages.isSelectedFor(it, recoveryWorkflowId) }
                    .map { it.fileName }
                    .toList()
                val detail = result.rejected.joinToString("\n") { "${it.fileName}：${it.message}" }
                    .takeIf(String::isNotBlank)
                setRunStatus(
                    buildString {
                        append(attachmentBatchStatus(acceptedNames, rejectedCount))
                        if (recoveryNames.isNotEmpty() && workflowRecoveryImages.isActive(recoveryWorkflowId)) {
                            append("；已选为恢复图片：")
                            append(recoveryNames.joinToString("、") { attachmentDisplayName(it, 36) })
                        }
                    },
                    isError = acceptedNames.isEmpty() && rejectedCount > 0,
                    detail = detail,
                )
                updateSendButtonState()
                requestComposerFocusLater()
            }, ModalityState.any())
        }
    }

    private fun removeAttachment(attachment: UserAttachment) {
        removeAttachmentsByIdentity(attachments, listOf(attachment))
        attachmentSourceKeys.entries.firstOrNull { it.value === attachment }?.key?.let(attachmentSourceKeys::remove)
        workflowRecoveryImages.forget(attachment)
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
        updateResponsiveLayout()
    }

    private fun updateModeButtonUi(layoutMode: ComposerLayoutMode) {
        val locked = activeRunMode?.takeIf { service.isRunning() }
        val draftOverride = composerPromptResolution(input.text).modeOverride
            ?.takeIf { locked == null && it != composerModeState.selectedMode }
        val displayedMode = locked ?: draftOverride ?: composerModeState.selectedMode
        val presentation = composerModePresentation(displayedMode)
        modeButton.text = if (locked != null) {
            composerModePresentation(locked).label
        } else if (draftOverride != null) {
            "${composerModeButtonText(draftOverride, layoutMode)} · 本轮"
        } else {
            composerModeButtonText(composerModeState.selectedMode, layoutMode)
        }
        modeButton.toolTipText = if (locked != null) {
            "本次运行已锁定为 ${composerModePresentation(locked).label} 模式"
        } else if (draftOverride != null) {
            "/plan 将本轮覆盖为 ${presentation.label}；发送后恢复 ${composerModePresentation(composerModeState.selectedMode).label}"
        } else {
            "${presentation.description}（Shift+Tab 切换 Agent / Claude Plan；Cmd/Ctrl+Shift+M 循环全部模式）"
        }
        modeButton.accessibleContext.accessibleName = if (draftOverride != null) {
            "本轮运行模式：${presentation.label}"
        } else {
            "运行模式：${presentation.label}"
        }
        modeButton.accessibleContext.accessibleDescription = modeButton.toolTipText
    }

    private fun toggleExecutionStrategy() {
        if (service.isRunning() || commitAi.isRunning) {
            val locked = activeRunStrategy ?: composerModeState.executionStrategy
            setRunStatus("本次运行已锁定为 ${executionStrategyLabel(locked)}。")
            return
        }
        composerModeState = composerModeState.selectExecutionStrategy(
            when (composerModeState.executionStrategy) {
                AgentExecutionStrategy.SINGLE -> AgentExecutionStrategy.AUTO
                AgentExecutionStrategy.AUTO -> AgentExecutionStrategy.TEAM
                AgentExecutionStrategy.TEAM -> AgentExecutionStrategy.SINGLE
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
        } else if (strategy == AgentExecutionStrategy.AUTO) {
            ComposerControlState.SELECTED
        } else {
            ComposerControlState.QUIET
        }
        teamButton.toolTipText = if (locked != null) {
            "本次运行已锁定为 ${executionStrategyLabel(strategy)}"
        } else if (strategy == AgentExecutionStrategy.TEAM) {
            "Team 协作已开启；主代理可委派独立调查、评审或验证任务"
        } else if (strategy == AgentExecutionStrategy.AUTO) {
            "自动路由已开启；小任务使用单代理，跨模块/科研/复杂排障自动启用 Team"
        } else {
            "单代理模式已开启；点击切换自动路由或 Team"
        }
        teamButton.accessibleContext.accessibleName = "执行策略：${executionStrategyLabel(strategy)}"
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
        updateFooterResponsiveVisibility()
        runStatusLabel.parent?.revalidate()
        runStatusLabel.parent?.repaint()
    }

    private fun updateFooterResponsiveVisibility() {
        if (!::providerFooterControls.isInitialized) return
        val narrow = composerLayoutMode(width) == ComposerLayoutMode.NARROW
        // Keep model controls discoverable while idle, but reserve the full row for progress and
        // safety warnings while a narrow Tool Window is showing status text.
        val activeProgress = service.isRunning() || commitAi.isRunning
        providerFooterControls.isVisible = !narrow || !runStatusLabel.isVisible || !activeProgress
        runStatusLabel.horizontalAlignment = if (narrow) {
            javax.swing.SwingConstants.LEFT
        } else {
            javax.swing.SwingConstants.RIGHT
        }
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

    private fun navigateExecutionSection(target: ExecutionNavigationTarget) {
        when (target) {
            ExecutionNavigationTarget.TASKS -> taskNavigator()
            ExecutionNavigationTarget.SUBAGENTS -> {
                if (!focusLatestExecutionSection(target)) {
                    setRunStatus("本次任务还没有子代理记录。")
                }
            }
            ExecutionNavigationTarget.EDITS -> {
                if (!service.isRunning() && lastReviewWorkflowId != null) {
                    reviewNavigator()
                } else if (!focusLatestExecutionSection(target)) {
                    setRunStatus("本次任务还没有可定位的文件修改。")
                }
            }
        }
    }

    private fun focusLatestExecutionSection(target: ExecutionNavigationTarget): Boolean =
        transcriptBlocks.toList().asReversed()
            .asSequence()
            .mapNotNull { it.component as? AssistantTurnPanel }
            .any { it.focusExecutionSection(target) }

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
        installClaudePlanShortcut(input) {
            if (service.isRunning() || commitAi.isRunning) {
                val lockedMode = activeRunMode ?: composerModeState.selectedMode
                setRunStatus("本次运行已锁定为 ${composerModePresentation(lockedMode).label} 模式。")
            } else {
                val nextMode = nextClaudePlanShortcutMode(composerModeState.selectedMode)
                selectComposerMode(nextMode)
                setRunStatus(
                    if (nextMode == AgentMode.CLAUDE_PLAN) {
                        "Claude Plan · 只读探索；Shift+Tab 返回 Agent"
                    } else {
                        "Agent · 可编辑执行；Shift+Tab 进入 Claude Plan"
                    },
                )
            }
        }
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
        val recoveryWorkflowId = workflowRecoveryImages.captureTarget()
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
                            workflowRecoveryImages.record(result.attachment, recoveryWorkflowId)
                            renderAttachmentTray()
                            setRunStatus(
                                if (workflowRecoveryImages.isSelectedFor(result.attachment, recoveryWorkflowId) &&
                                    workflowRecoveryImages.isActive(recoveryWorkflowId)
                                ) {
                                    "已添加并选为恢复图片：${result.attachment.fileName}"
                                } else {
                                    "已添加剪贴板截图 ${result.attachment.fileName}"
                                },
                            )
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

    private fun submitPrompt(transcriptText: String? = null): Boolean {
        if (disposed) return false
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("当前任务仍在运行；可继续编辑，停止后再发送。")
            requestComposerFocusLater()
            return false
        }
        if (pendingAttachmentBatches > 0) {
            setRunStatus("附件仍在读取，请稍候再发送。")
            requestComposerFocusLater()
            return false
        }
        val promptResolution = composerPromptResolution(input.text)
        val command = promptResolution.command
        if (command != null && !command.requiresModel) {
            input.text = ""
            handleComposerCommand(command)
            updateSendButtonState()
            requestComposerFocusLater()
            return true
        }
        if (lastProviderStatus?.configured == false) {
            setRunStatus("请先配置供应商 API Key。", isError = true)
            openProviderSettings()
            return false
        }
        val prompt = promptResolution.prompt
        if (prompt.isEmpty() && attachments.isEmpty()) {
            setRunStatus(
                if (promptResolution.modeOverride == AgentMode.CLAUDE_PLAN) {
                    "请在 /plan 后输入要探索和规划的任务。"
                } else {
                    "请输入任务或添加附件后再发送。"
                },
                isError = true,
            )
            requestComposerFocusLater()
            return false
        }
        val userSubmission = UserSubmission(prompt, attachments.toList())
        if (userSubmission.estimatedCharacterCount > AgentEngine.MAX_USER_MESSAGE_CHARS) {
            setRunStatus("消息过长，最多 ${AgentEngine.MAX_USER_MESSAGE_CHARS} 个字符。", isError = true)
            requestComposerFocusLater()
            return false
        }

        activeRunSawText = false
        val submission = composerModeState.snapshot(promptResolution)
        if (submission.mode != AgentMode.PLAN && submission.mode != AgentMode.CLAUDE_PLAN) {
            planRevisionBoardId = null
        }
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
            return false
        }

        recoveryTurn?.clearRecoveryAction()
        recoveryTurn = null
        lastSubmission = RecoverableSubmission(
            submission = userSubmission.copy(prompt = submission.prompt),
            mode = submission.mode,
            strategy = submission.strategy,
        )
        activeRunMode = submission.mode
        activeRecoveryWorkflow = null
        activeRunStrategy = submission.strategy
        activeWorkflowId = null
        executionToolCount = 0
        executionSubagentCount = 0
        executionEditCount = 0
        updateExecutionNavigation(running = true)
        updateComposerModeUi()
        addUserMessage(transcriptText?.takeIf(String::isNotBlank) ?: prompt, attachments.toList())
        val initialStatus = composerModePresentation(submission.mode).runningStatus
        beginAssistantTurn().updateStatus(initialStatus)
        setRunStatus(initialStatus)
        input.text = ""
        attachmentDraftGeneration++
        attachments.clear()
        attachmentSourceKeys.clear()
        workflowRecoveryImages.forgetAllAttachments()
        renderAttachmentTray()
        requestComposerFocusLater()
        scrollToBottom(force = true)
        return true
    }

    private fun handleComposerCommand(command: ComposerCommand) {
        when (command) {
            ComposerCommand.MODEL -> showModelSelector()
            ComposerCommand.STATUS -> diagnosticsNavigator()
            ComposerCommand.PERMISSIONS -> settingsNavigator(OmniCodeSettingsPage.SANDBOX)
            ComposerCommand.MCP -> settingsNavigator(OmniCodeSettingsPage.MCP)
            ComposerCommand.TASKS -> taskNavigator()
            ComposerCommand.NEW -> clearConversation()
            ComposerCommand.HELP -> setRunStatus(
                "命令：/plan 规划 · /review 审阅当前差异 · /status 诊断 · /model 选择模型 · " +
                    "/permissions 沙箱 · /mcp MCP · /tasks 任务中心 · /new 新对话",
            )
            ComposerCommand.REVIEW -> Unit
        }
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
        conversation.clearBlocks()
        transcriptBlocks.clear()
        transcriptCharacters = 0
        activeTurn = null
        activeTurnBlock = null
        activeRunSawText = false
        activeRunMode = null
        activeRunStrategy = null
        activeRunReasoningEffort = null
        activeWorkflowId = null
        lastSubmission = null
        recoveryTurn = null
        recoverableWorkflowTurn = null
        activeRecoveryWorkflow = null
        workflowRecoveryImages.reset()
        activePlanStepId = null
        autoContinueApprovedPlan = false
        planRevisionBoardId = null
        inlinePlanReviewCard?.let(Disposer::dispose)
        inlinePlanReviewCard = null
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
        reasoningButton.isEnabled = !running
        modeButton.isEnabled = !running
        teamButton.isEnabled = !running
        updateComposerModeUi()
        updateSendButtonState()
        if (running) {
            activeRunReasoningEffort = OmniCodeSettingsService.getInstance().snapshot().reasoningEffort
            updatePetState(DesktopPetState.THINKING)
            setRunStatus("正在准备任务…")
        } else if (runStatusLabel.text == "正在停止…" || runStatusLabel.text.startsWith("正在准备任务")) {
            activeRunReasoningEffort = null
            setRunStatus("")
            if (desktopPet.state == DesktopPetState.THINKING || desktopPet.state == DesktopPetState.TOOL) {
                updatePetState(DesktopPetState.IDLE)
            }
        }
        updateFooterResponsiveVisibility()
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
        reasoningButton.isEnabled = interactive
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
        updateFooterResponsiveVisibility()
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
                updateComposerModeUi()
            }
            is AgentEvent.ExecutionStrategySelected -> {
                activeRunStrategy = event.strategy
                activeWorkflowId = event.workflowId
                updateTeamButtonUi()
            }
            is AgentEvent.DelegatedAgentStarted -> {
                if (activeWorkflowId != null && activeWorkflowId != event.workflowId) return
                activeWorkflowId = event.workflowId
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
                val added = ensureActiveTurn().completeDelegate(
                    agentId = event.agentId,
                    displayName = event.displayName,
                    status = delegateProgressStatus(event.status, event.usable),
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
                userFacingRunStatus(event.message)?.let { status ->
                    setRunStatus(status, isError = isCriticalRunWarning(event.message))
                }
            }
            is AgentEvent.TextDelta -> {
                // Delegated specialists are represented by their progress card; the service only
                // forwards the lead agent's deltas to keep the main answer coherent.
                if (event.text.isNotEmpty()) {
                    if (event.text.isNotBlank()) activeRunSawText = true
                    ensureActiveTurn().appendText(event.text)
                    addActiveTurnCharacters(event.text.length)
                    scrollToBottom(force = followOutput)
                }
            }
            is AgentEvent.ToolRequested -> {
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
                val result = boundedToolResult(event.result)
                ensureActiveTurn().completeTool(
                    event.name,
                    result,
                    event.isError,
                    event.callId,
                    cancelled = event.cancelled,
                )
                addActiveTurnCharacters(result.length)
                if (event.isError) setRunStatus("${humanizeToolName(event.name)}失败")
            }
            is AgentEvent.UsageUpdated -> {
                ensureActiveTurn().updateUsage(event.usage.totalTokens)
            }
            is AgentEvent.ProjectContextPrepared -> {
                ensureActiveTurn().showProjectContext(
                    rulePaths = event.rulePaths,
                    pinnedPaths = event.pinnedPaths,
                    excludedPathCount = event.excludedPathCount,
                    estimatedContextTokens = event.estimatedContextTokens,
                    maxContextTokens = event.maxContextTokens,
                    truncated = event.truncated,
                )
                addActiveTurnCharacters(event.rulePaths.sumOf(String::length) + event.pinnedPaths.sumOf(String::length))
                val percent = ((event.estimatedContextTokens.toDouble() / event.maxContextTokens.toDouble()) * 100)
                    .toInt().coerceIn(0, 100)
                contextButton.text = "上下文 $percent%"
                contextButton.toolTipText = "本轮 ${event.rulePaths.size} 条规则、${event.pinnedPaths.size} 个固定文件；" +
                    "自动上下文约 ${event.estimatedContextTokens}/${event.maxContextTokens} context tokens"
            }
            is AgentEvent.BudgetWarning -> {
                val projected = if (event.projected) "预计" else "当前"
                setRunStatus(
                    "$projected 费用 \$${event.estimatedCostUsd.stripTrailingZeros().toPlainString()} / " +
                        "\$${event.maxCostUsd.stripTrailingZeros().toPlainString()}",
                    isError = false,
                )
            }
            is AgentEvent.StageStarted -> {
                ensureActiveTurn().updateStatus("阶段：${event.stage}…")
            }
            is AgentEvent.StageCompleted -> {
                ensureActiveTurn().updateStatus("阶段 ${event.stage}${if (event.success) "完成" else "失败"} · ${event.durationMillis} ms")
            }
            is AgentEvent.ProviderRequestStarted -> {
                ensureActiveTurn().updateStatus("模型请求 #${event.attempt}…")
            }
            is AgentEvent.ProviderRetryScheduled -> {
                setRunStatus("模型请求失败，${event.delayMillis} ms 后重试：${event.reason.take(120)}", isError = true)
            }
        }
        if (event !is AgentEvent.TextDelta) scrollToBottom(force = followOutput)
    }

    private fun handleResult(result: AgentRunResult) {
        if (disposed) return
        val resumedWorkflow = activeRecoveryWorkflow
        if (result.workflowId.isNotBlank()) lastReviewWorkflowId = result.workflowId
        val followOutput = isNearBottom()
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
                        AgentMode.PLAN -> "✓  看板计划完成"
                        AgentMode.CLAUDE_PLAN -> "✓  Claude Plan 完成"
                        AgentMode.RESEARCH -> "✓  研究记录完成"
                    },
                )
                lastSubmission = null
                activeRecoveryWorkflow = null
                if ((result.mode == AgentMode.PLAN || result.mode == AgentMode.CLAUDE_PLAN) && result.finalText.isNotBlank()) {
                    offerPlanExecution(turn, result.finalText, result.mode)
                } else if (result.mode == AgentMode.RESEARCH) {
                    offerResearchExport(turn)
                } else if (result.mode == AgentMode.AGENT && result.workflowId.isNotBlank() &&
                    TaskChangeReviewService.getInstance(project).listFiles(result.workflowId).isNotEmpty()
                ) {
                    recoveryTurn = turn
                    turn.showRecoveryAction(
                        label = "审阅本次变更",
                        tooltip = "逐文件或逐块保留、回退，也可回退全部已记录的 Agent 直接修改",
                        icon = AllIcons.Actions.Diff,
                        action = reviewNavigator,
                    )
                } else {
                    recoveryTurn = null
                }
                setRunStatus("")
            }
            AgentRunStatus.CANCELLED -> {
                val failure = classifyAgentFailure(result.status, result.error)
                updatePetState(DesktopPetState.IDLE)
                appendTerminalText(turn, result.finalText)
                turn.finish("›  已取消")
                offerSubmissionRecovery(turn, result)
                if (resumedWorkflow != null) refreshWorkflowRecovery(turn, result, resumedWorkflow)
                else attachResultWorkflowRecovery(turn, result)
                setRunStatus(failure.title, detail = failure.detail)
            }
            AgentRunStatus.FAILED -> {
                val failure = classifyAgentFailure(result.status, result.error)
                updatePetState(DesktopPetState.ERROR, settle = true)
                appendTerminalText(turn, result.finalText)
                turn.finish("!  ${failure.title}", isError = true)
                offerSubmissionRecovery(turn, result)
                if (resumedWorkflow != null) refreshWorkflowRecovery(turn, result, resumedWorkflow)
                else attachResultWorkflowRecovery(turn, result)
                setRunStatus(failure.title, isError = true, detail = failure.detail)
            }
            AgentRunStatus.BUDGET_EXHAUSTED -> {
                val failure = classifyAgentFailure(result.status, result.error)
                updatePetState(DesktopPetState.IDLE)
                if (!activeRunSawText) appendTerminalText(turn, result.finalText)
                turn.finish("›  ${failure.title}")
                offerSubmissionRecovery(turn, result)
                if (resumedWorkflow != null) refreshWorkflowRecovery(turn, result, resumedWorkflow)
                else attachResultWorkflowRecovery(turn, result)
                setRunStatus(failure.title, detail = failure.detail)
            }
        }
        activeTurn = null
        activeTurnBlock = null
        activeRunMode = null
        activeRunStrategy = null
        activeWorkflowId = null
        if ((result.mode == AgentMode.PLAN || result.mode == AgentMode.CLAUDE_PLAN) &&
            result.status != AgentRunStatus.COMPLETED
        ) {
            planRevisionBoardId = null
        }
        finishActivePlanStep(result)
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

    private fun offerSubmissionRecovery(turn: AssistantTurnPanel, result: AgentRunResult) {
        if (!shouldOfferSubmissionRecovery(result.status) || lastSubmission == null) return
        if (result.status == AgentRunStatus.BUDGET_EXHAUSTED && result.workflowId.isNotBlank()) return
        val failure = classifyAgentFailure(result.status, result.error)
        recoveryTurn?.takeIf { it !== turn }?.clearRecoveryAction()
        recoveryTurn = turn
        turn.showRecoveryAction(
            label = failure.recoveryLabel,
            tooltip = failure.recoveryTooltip,
            action = {
                restoreLastSubmissionForEditing()
                when (failure.recoveryAction) {
                    AgentRecoveryAction.CONFIGURE_PROVIDER -> openProviderSettings()
                    AgentRecoveryAction.CONFIGURE_PRICING -> settingsNavigator(OmniCodeSettingsPage.PRICING)
                    AgentRecoveryAction.SWITCH_MODEL -> showModelSelector()
                    AgentRecoveryAction.ADJUST_BUDGET -> settingsNavigator(OmniCodeSettingsPage.RUNTIME)
                    AgentRecoveryAction.OPEN_SANDBOX -> settingsNavigator(OmniCodeSettingsPage.SANDBOX)
                    AgentRecoveryAction.RUN_DIAGNOSTICS -> diagnosticsNavigator()
                    AgentRecoveryAction.RESTORE_AND_RETRY,
                    AgentRecoveryAction.EDIT_AND_RETRY,
                    -> Unit
                }
            },
        )
    }

    private fun offerWorkflowRecovery(
        turn: AssistantTurnPanel,
        result: AgentRunResult,
        workflow: RecoverableWorkflow,
    ) {
        workflowRecoveryImages.begin(
            workflow.workflowId,
            acceptsImages = workflow.requiredImageAttachments > 0,
        )
        val failure = classifyAgentFailure(result.status, result.error)
        recoveryTurn?.takeIf { it !== turn }?.clearRecoveryAction()
        recoveryTurn = turn
        recoverableWorkflowTurn = turn
        val targetedAction: () -> Unit = when (failure.recoveryAction) {
            AgentRecoveryAction.CONFIGURE_PROVIDER -> ::openProviderSettings
            AgentRecoveryAction.CONFIGURE_PRICING -> ({ settingsNavigator(OmniCodeSettingsPage.PRICING) })
            AgentRecoveryAction.SWITCH_MODEL -> ::showModelSelector
            AgentRecoveryAction.ADJUST_BUDGET -> ({ enableContinuousExecutionAndResume(workflow, turn) })
            AgentRecoveryAction.OPEN_SANDBOX -> ({ settingsNavigator(OmniCodeSettingsPage.SANDBOX) })
            AgentRecoveryAction.RUN_DIAGNOSTICS -> diagnosticsNavigator
            AgentRecoveryAction.RESTORE_AND_RETRY,
            AgentRecoveryAction.EDIT_AND_RETRY,
            -> ({ resumeInterruptedWorkflow(workflow, turn) })
        }
        turn.showRecoveryAction(
            label = if (failure.recoveryAction in setOf(
                    AgentRecoveryAction.RESTORE_AND_RETRY,
                    AgentRecoveryAction.EDIT_AND_RETRY,
                )
            ) {
                "再次继续"
            } else {
                failure.recoveryLabel
            },
            tooltip = failure.recoveryTooltip,
            action = targetedAction,
        )
        if (failure.recoveryAction !in setOf(
                AgentRecoveryAction.RESTORE_AND_RETRY,
                AgentRecoveryAction.EDIT_AND_RETRY,
                AgentRecoveryAction.ADJUST_BUDGET,
            )
        ) {
            turn.addRecoveryAction(
                label = "配置后再次继续",
                tooltip = "保留原安全检查点，调整配置后重新尝试恢复",
                icon = AllIcons.Actions.Execute,
                action = { resumeInterruptedWorkflow(workflow, turn) },
            )
        }
        turn.addRecoveryAction(
            label = "放弃检查点",
            tooltip = "确认后删除本地恢复记录；8 秒内可撤销，不会回退文件改动",
            icon = AllIcons.Actions.Cancel,
            action = {
                confirmAndDiscardWorkflowCheckpoint(
                    turn = turn,
                    workflow = workflow,
                    onDiscarded = {
                        activeRecoveryWorkflow = null
                        recoverableWorkflowTurn = null
                        workflowRecoveryImages.clear(workflow.workflowId)
                        if (recoveryTurn === turn) recoveryTurn = null
                        setRunStatus("已放弃该恢复检查点；8 秒内可撤销。")
                    },
                    onUndo = {
                        activeRecoveryWorkflow = workflow
                        recoverableWorkflowTurn = turn
                        recoveryTurn = turn
                        workflowRecoveryImages.begin(
                            workflow.workflowId,
                            acceptsImages = workflow.requiredImageAttachments > 0,
                        )
                        offerWorkflowRecovery(turn, result, workflow)
                    },
                    onUndoExpired = { turn.clearRecoveryAction() },
                    onDiscardFailed = { refreshWorkflowRecovery(turn, result, workflow) },
                )
            },
        )
    }

    private fun refreshWorkflowRecovery(
        turn: AssistantTurnPanel,
        result: AgentRunResult,
        workflow: RecoverableWorkflow,
    ) {
        service.listRecoverableWorkflows { workflows ->
            if (disposed || activeRecoveryWorkflow?.workflowId != workflow.workflowId) {
                return@listRecoverableWorkflows
            }
            val latest = workflows.firstOrNull { it.workflowId == workflow.workflowId }
            if (latest == null) {
                activeRecoveryWorkflow = null
                recoverableWorkflowTurn = null
                workflowRecoveryImages.clear(workflow.workflowId)
                if (recoveryTurn === turn) recoveryTurn = null
                turn.clearRecoveryAction()
                setRunStatus("恢复检查点已不存在。", isError = true)
                return@listRecoverableWorkflows
            }
            activeRecoveryWorkflow = latest
            offerWorkflowRecovery(turn, result, latest)
        }
    }

    private fun attachResultWorkflowRecovery(turn: AssistantTurnPanel, result: AgentRunResult) {
        if (result.workflowId.isBlank()) return
        service.listRecoverableWorkflows { workflows ->
            if (disposed) return@listRecoverableWorkflows
            val workflow = workflows.firstOrNull { it.workflowId == result.workflowId } ?: return@listRecoverableWorkflows
            activeRecoveryWorkflow = workflow
            offerWorkflowRecovery(turn, result, workflow)
        }
    }

    private fun enableContinuousExecutionAndResume(workflow: RecoverableWorkflow, turn: AssistantTurnPanel) {
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.agentContinuousExecution = true
        }
        updateReasoningButton()
        setRunStatus("已开启持续执行，正在从安全检查点恢复…")
        resumeInterruptedWorkflow(workflow, turn)
    }

    private fun offerPlanExecution(turn: AssistantTurnPanel, planText: String, mode: AgentMode) {
        recoveryTurn?.takeIf { it !== turn }?.clearRecoveryAction()
        val board = planBoardService.replaceFromPlan(
            planText = planText,
            mode = mode,
            preserveFromBoardId = planRevisionBoardId,
        )
        planRevisionBoardId = null
        inlinePlanReviewCard?.let { previous ->
            previous.markSuperseded()
            Disposer.dispose(previous)
        }
        val reviewCard = InlinePlanReviewCard(
            service = planBoardService,
            boardId = board.id,
            actions = object : InlinePlanReviewActions {
                override fun execute(request: PlanExecutionRequest) = executeApprovedPlanSteps(request)

                override fun continuePlanning(board: PlanBoard) = this@OmniCodeChatPanel.continuePlanning(board)

                override fun openFullBoard() = planNavigator()

                override fun showMessage(message: String, isError: Boolean) = setRunStatus(message, isError)
            },
        )
        inlinePlanReviewCard = reviewCard
        registerBlock(reviewCard, board.sourceText.length.coerceAtMost(12_000))
        recoveryTurn = turn
        turn.showRecoveryAction(
            label = "打开完整计划看板",
            tooltip = "聊天中的审批卡可直接执行；完整看板提供跳过、暂停和逐步重试",
            icon = AllIcons.Actions.ListFiles,
            action = planNavigator,
        )
        setRunStatus("计划已生成；请在聊天内审阅后选择继续规划、逐步执行或自动执行。")
        showBodyState(ChatBodyState.TRANSCRIPT)
        scrollToBottom(force = true)
    }

    internal fun executeApprovedPlanSteps() {
        val policy = planBoardService.snapshot()?.executionPolicy ?: PlanExecutionPolicy.NONE
        val request = planBoardService.requestExecution(policy) ?: run {
            setRunStatus("当前计划未批准、已变更或没有可执行步骤。", isError = true)
            return
        }
        executeApprovedPlanSteps(request)
    }

    internal fun executeApprovedPlanSteps(request: PlanExecutionRequest) {
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("当前步骤仍在运行；可先暂停，再调整计划。")
            return
        }
        if (!planBoardService.isExecutionAuthorized(request)) {
            setRunStatus("计划已变更或批准已失效；请审阅当前版本后重试。", isError = true)
            return
        }
        val step = planBoardService.startExecution(request) ?: run {
            setRunStatus("无法启动此计划步骤：它可能已被编辑、跳过或由其他任务占用。", isError = true)
            return
        }
        autoContinueApprovedPlan = request.policy == PlanExecutionPolicy.AUTO_AGENT
        activePlanStepId = step.id
        val board = planBoardService.snapshot() ?: run {
            planBoardService.markFailed(step.id, "计划状态丢失，任务未启动")
            activePlanStepId = null
            autoContinueApprovedPlan = false
            return
        }
        composerModeState = composerModeState.select(AgentMode.AGENT)
        updateComposerModeUi()
        input.text = planStepExecutionPrompt(board, step.id)
        input.caretPosition = input.document.length
        if (!submitPrompt(planStepTranscriptText(board, step.id))) {
            planBoardService.markFailed(step.id, "任务未启动；请检查供应商配置或当前运行状态")
            activePlanStepId = null
            autoContinueApprovedPlan = false
        }
    }

    internal fun pausePlanExecution() {
        autoContinueApprovedPlan = false
        if (activePlanStepId != null && service.cancelCurrentRun()) {
            planBoardService.pauseRunning()
            setRunStatus("正在暂停计划步骤…")
        } else {
            setRunStatus("当前没有执行中的计划步骤。")
        }
    }

    internal fun continuePlanning(board: PlanBoard) {
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("请先暂停当前步骤，再继续规划。")
            return
        }
        composerModeState = composerModeState.select(board.sourceMode)
        planRevisionBoardId = board.id
        updateComposerModeUi()
        input.text = buildString {
            append("继续完善计划 ").append(board.sourceFingerprint).append("。请根据以下看板状态重新规划；")
            append("保留已完成步骤，处理用户随后补充的反馈，不要修改文件。\n\n")
            board.steps.forEachIndexed { index, step ->
                append(index + 1).append(". [").append(if (step.state == PlanStepState.COMPLETED) 'x' else ' ')
                    .append("] ").append(step.text).append(" · ").append(step.state.name).append('\n')
            }
        }.take(AgentEngine.MAX_USER_MESSAGE_CHARS)
        input.caretPosition = input.document.length
        requestComposerFocusLater()
    }

    private fun executeNextApprovedPlanStep() {
        val board = planBoardService.snapshot() ?: run {
            autoContinueApprovedPlan = false
            setRunStatus("当前没有可执行的计划。", isError = true)
            return
        }
        if (board.executionPolicy != PlanExecutionPolicy.AUTO_AGENT) {
            autoContinueApprovedPlan = false
            setRunStatus("计划已变更或自动批准已失效；继续执行前需要重新审阅。")
            return
        }
        val request = planBoardService.requestExecution(PlanExecutionPolicy.AUTO_AGENT) ?: run {
            autoContinueApprovedPlan = false
            setRunStatus("所有已批准步骤均已处理。")
            return
        }
        executeApprovedPlanSteps(request)
    }

    private fun finishActivePlanStep(result: AgentRunResult) {
        val stepId = activePlanStepId ?: return
        activePlanStepId = null
        when (result.status) {
            AgentRunStatus.COMPLETED -> planBoardService.markCompleted(stepId)
            AgentRunStatus.CANCELLED -> {
                if (planBoardService.snapshot()?.steps?.any {
                        it.id == stepId && it.state == PlanStepState.PAUSED
                    } != true
                ) {
                    planBoardService.markFailed(stepId, "执行已取消")
                }
                autoContinueApprovedPlan = false
            }
            AgentRunStatus.FAILED -> {
                planBoardService.markFailed(stepId, result.error?.message ?: result.status.name)
                autoContinueApprovedPlan = false
            }
            AgentRunStatus.BUDGET_EXHAUSTED -> {
                planBoardService.pauseRunning()
                autoContinueApprovedPlan = false
            }
        }
        if (result.status == AgentRunStatus.COMPLETED && autoContinueApprovedPlan) {
            SwingUtilities.invokeLater { if (!disposed) executeNextApprovedPlanStep() }
        }
    }

    private fun offerResearchExport(turn: AssistantTurnPanel) {
        recoveryTurn?.takeIf { it !== turn }?.clearRecoveryAction()
        recoveryTurn = turn
        turn.showRecoveryAction(
            label = "导出研究包",
            tooltip = "导出脱敏的 Markdown 会话、命令证据、复现清单与引用核对清单",
            icon = AllIcons.Actions.Download,
            action = ::exportResearchPackage,
        )
    }

    private fun restoreLastSubmissionForEditing() {
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("当前任务仍在运行，请稍后再恢复。")
            return
        }
        val recoverable = lastSubmission ?: return
        if (pendingAttachmentBatches > 0) {
            setRunStatus("附件仍在读取；读取完成后再恢复，以免覆盖当前草稿。", isError = true)
            return
        }
        val missingAttachments = recoverable.submission.attachments.filterNot(attachments::contains)
        if (!canMergeRecoveredAttachments(attachments.size, missingAttachments.size)) {
            val requiredSlots = (attachments.size + missingAttachments.size - AttachmentIntake.MAX_ATTACHMENTS)
                .coerceAtLeast(1)
            setRunStatus(
                "当前草稿附件已占用 ${attachments.size}/${AttachmentIntake.MAX_ATTACHMENTS}；" +
                    "请先移除 $requiredSlots 个附件后再恢复，已保留上次任务。",
                isError = true,
            )
            return
        }
        val existingPrompt = input.text.trim()
        val hadNewDraft = existingPrompt.isNotBlank() || attachments.isNotEmpty()
        input.text = mergeRecoveredPrompt(existingPrompt, recoverable.submission.prompt)
        input.caretPosition = input.document.length
        if (!hadNewDraft) {
            composerModeState = composerModeState.select(recoverable.mode)
                .selectExecutionStrategy(recoverable.strategy)
        }
        updateComposerModeUi()

        missingAttachments.forEachIndexed { index, attachment ->
            attachments += attachment
            attachmentSourceKeys["recovered:$index:${attachment.fileName}"] = attachment
        }
        renderAttachmentTray()
        recoveryTurn?.clearRecoveryAction()
        recoveryTurn = null
        lastSubmission = null
        updateSendButtonState()
        setRunStatus(
            if (hadNewDraft) {
                "已把上次任务和附件合并到当前草稿，没有覆盖正在编辑的内容。"
            } else {
                "已恢复上次任务和附件，可修改后重发。"
            },
        )
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
        val realBase = runCatching { base.toRealPath() }.getOrNull() ?: return
        val requested = runCatching { Path.of(reference.path) }.getOrNull() ?: return
        val resolved = (if (requested.isAbsolute) requested else base.resolve(requested)).toAbsolutePath().normalize()
        if (!resolved.startsWith(base)) {
            setRunStatus("无法打开工作区外的文件。", isError = true)
            return
        }
        val realFile = resolveReferencedProjectFile(reference, requested, resolved, base, realBase) ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(realFile.toString())
        if (file == null) {
            setRunStatus("文件不存在：${reference.path}", isError = true)
            return
        }
        val startLine = (reference.startLine ?: 1).coerceAtLeast(1)
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, file, startLine - 1, 0),
                true,
            ) ?: return@invokeLater
            val endLine = reference.endLine?.coerceAtLeast(startLine) ?: return@invokeLater
            val document = editor.document
            if (document.lineCount <= 0) return@invokeLater
            val startIndex = (startLine - 1).coerceAtMost(document.lineCount - 1)
            val endIndex = (endLine - 1).coerceAtMost(document.lineCount - 1)
            val startOffset = document.getLineStartOffset(startIndex)
            val endOffset = document.getLineEndOffset(endIndex)
            editor.selectionModel.setSelection(startOffset, endOffset)
            editor.caretModel.moveToOffset(startOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    private fun resolveReferencedProjectFile(
        reference: ToolFileReference,
        requested: Path,
        resolved: Path,
        base: Path,
        realBase: Path,
    ): Path? {
        if (Files.isSymbolicLink(resolved)) {
            setRunStatus("为安全起见，不能通过符号链接打开文件。", isError = true)
            return null
        }
        if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return verifiedProjectFile(resolved, realBase, reference.path)
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            setRunStatus("只能打开工作区内的普通文件。", isError = true)
            return null
        }
        if (requested.isAbsolute || requested.nameCount != 1) {
            setRunStatus("文件不存在：${reference.path}", isError = true)
            return null
        }

        val matches = ReadAction.compute<List<Path>, RuntimeException> {
            FilenameIndex.getVirtualFilesByName(
                reference.path,
                GlobalSearchScope.projectScope(project),
            ).asSequence()
                .mapNotNull { virtualFile ->
                    runCatching { Path.of(virtualFile.path).toAbsolutePath().normalize() }.getOrNull()
                }
                .filter { candidate -> candidate.startsWith(base) }
                .filterNot(Files::isSymbolicLink)
                .mapNotNull { candidate ->
                    if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        runCatching { candidate.toRealPath() }.getOrNull()?.takeIf { it.startsWith(realBase) }
                    } else {
                        null
                    }
                }
                .distinct()
                .take(3)
                .toList()
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> {
                setRunStatus("文件不存在：${reference.path}", isError = true)
                null
            }
            else -> {
                setRunStatus("找到多个同名文件，请让模型输出完整项目路径：${reference.path}", isError = true)
                null
            }
        }
    }

    private fun verifiedProjectFile(candidate: Path, realBase: Path, displayPath: String): Path? {
        val realFile = runCatching { candidate.toRealPath() }.getOrNull()
        if (realFile == null || !realFile.startsWith(realBase)) {
            setRunStatus("无法打开指向工作区外部的文件：$displayPath", isError = true)
            return null
        }
        return realFile
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
                val turn = AssistantTurnPanel(mode = null, ::openToolFileReference).apply {
                    appendText(text)
                    finish("✓  完成")
                }
                registerBlock(turn, text.length)
            }
        }
        if (restored) showBodyState(ChatBodyState.TRANSCRIPT) else showEmptyState()
        scrollToBottom(force = true)
    }

    private fun checkRecoverableWorkflows() {
        service.listRecoverableWorkflows { workflows ->
            if (disposed || service.isRunning()) return@listRecoverableWorkflows
            val latest = workflows.firstOrNull() ?: return@listRecoverableWorkflows
            showRecoverableWorkflow(latest)
        }
    }

    private fun showRecoverableWorkflow(workflow: RecoverableWorkflow) {
        recoverableWorkflowTurn?.let(::removeTranscriptComponent)
        workflowRecoveryImages.begin(
            workflow.workflowId,
            acceptsImages = workflow.requiredImageAttachments > 0,
        )
        val pending = workflow.pendingToolName?.let { tool ->
            if (workflow.pendingToolDangerous) {
                "\n\n中断时 `$tool` 的副作用状态不确定；恢复后不会自动重放，并会重新审批。"
            } else {
                "\n\n中断时 `$tool` 尚未确认完成；恢复后会先核对现状。"
            }
        }.orEmpty()
        val missingImages = if (workflow.requiredImageAttachments > 0) {
            "\n\n原任务包含 ${workflow.requiredImageAttachments} 张未写入磁盘的图片。为避免缺失图表或截图上下文，" +
                "继续前请重新拖入这些图片。只有此提示出现后新添加、并在确认框列出的图片会用于恢复；" +
                "已有草稿附件不会发送或删除。"
        } else {
            ""
        }
        val pausedByFiniteLimit = workflow.state == dev.omnicode.persistence.WorkflowCheckpointState.BUDGET_EXHAUSTED
        val recoveryKind = if (pausedByFiniteLimit) "有限模式暂停" else "IDE 中断"
        val turn = AssistantTurnPanel(workflow.mode, ::openToolFileReference).apply {
            appendText(
                "发现一个因${recoveryKind}而可恢复的任务：${workflow.title}\n\n" +
                    "已保存到第 ${workflow.iteration} 轮。恢复会沿用原 workflow，并从最后安全检查点继续。$pending$missingImages",
            )
            finish(if (pausedByFiniteLimit) "有限模式已暂停" else "可恢复的中断任务")
        }
        recoverableWorkflowTurn = turn
        configureRecoverableWorkflowActions(turn, workflow)
        registerBlock(turn, workflow.title.length + 120)
        showBodyState(ChatBodyState.TRANSCRIPT)
        scrollToBottom(force = true)
    }

    private fun configureRecoverableWorkflowActions(
        turn: AssistantTurnPanel,
        workflow: RecoverableWorkflow,
    ) {
        turn.showRecoveryAction(
            label = "继续任务",
            tooltip = "从最后安全检查点恢复；未确认的副作用不会自动重放",
            icon = AllIcons.Actions.Execute,
            action = { resumeInterruptedWorkflow(workflow, turn) },
        )
        turn.addRecoveryAction(
            label = "放弃检查点",
            tooltip = "确认后删除这条本地恢复记录；8 秒内可撤销，不会回退文件改动",
            icon = AllIcons.Actions.Cancel,
            action = {
                confirmAndDiscardWorkflowCheckpoint(
                    turn = turn,
                    workflow = workflow,
                    onDiscarded = {
                        workflowRecoveryImages.clear(workflow.workflowId)
                        activeRecoveryWorkflow = null
                        recoverableWorkflowTurn = null
                        if (recoveryTurn === turn) recoveryTurn = null
                        setRunStatus("已放弃中断任务的本地检查点；8 秒内可撤销。")
                    },
                    onUndo = {
                        recoverableWorkflowTurn = turn
                        recoveryTurn = turn
                        workflowRecoveryImages.begin(
                            workflow.workflowId,
                            acceptsImages = workflow.requiredImageAttachments > 0,
                        )
                        configureRecoverableWorkflowActions(turn, workflow)
                    },
                    onUndoExpired = { removeTranscriptComponent(turn) },
                    onDiscardFailed = {
                        removeTranscriptComponent(turn)
                        checkRecoverableWorkflows()
                    },
                )
            },
        )
    }

    private fun confirmAndDiscardWorkflowCheckpoint(
        turn: AssistantTurnPanel,
        workflow: RecoverableWorkflow,
        onDiscarded: () -> Unit,
        onUndo: () -> Unit,
        onUndoExpired: () -> Unit,
        onDiscardFailed: () -> Unit,
    ) {
        val confirmed = Messages.showYesNoDialog(
            project,
            checkpointDiscardConfirmationText(workflow.title, workflow.pendingToolDangerous),
            "放弃恢复检查点",
            "放弃检查点",
            "取消",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        turn.setRecoveryActionsEnabled(false)
        setRunStatus("正在安全放弃检查点…")
        service.discardRecoverableWorkflowWithUndo(
            workflowId = workflow.workflowId,
            expectedRunId = workflow.runId,
            expectedUpdatedAt = workflow.updatedAt,
        ) { discarded ->
            if (disposed) return@discardRecoverableWorkflowWithUndo
            if (discarded == null) {
                turn.setRecoveryActionsEnabled(true)
                onDiscardFailed()
                setRunStatus("检查点已变化或无法删除；已重新读取最新恢复状态。", isError = true)
                return@discardRecoverableWorkflowWithUndo
            }
            onDiscarded()
            showCheckpointDiscardUndo(turn, discarded, onUndo, onUndoExpired)
        }
    }

    private fun showCheckpointDiscardUndo(
        turn: AssistantTurnPanel,
        discarded: DiscardedRecoverableWorkflow,
        onUndo: () -> Unit,
        onUndoExpired: () -> Unit,
    ) {
        lateinit var expiryTimer: Timer
        expiryTimer = Timer(CHECKPOINT_DISCARD_UNDO_MILLIS) {
            checkpointUndoTimers.remove(expiryTimer)
            if (!disposed) {
                onUndoExpired()
                setRunStatus("已放弃恢复检查点。")
            }
        }.apply {
            isRepeats = false
        }
        turn.showRecoveryAction(
            label = "撤销放弃",
            tooltip = "8 秒内恢复刚删除的本地检查点",
            icon = AllIcons.Actions.Rollback,
            action = {
                expiryTimer.stop()
                checkpointUndoTimers.remove(expiryTimer)
                turn.setRecoveryActionsEnabled(false)
                service.restoreDiscardedRecoverableWorkflow(discarded) { result ->
                    if (disposed) return@restoreDiscardedRecoverableWorkflow
                    when (result) {
                        DiscardedWorkflowRestoreResult.RESTORED -> {
                            onUndo()
                            setRunStatus("已恢复检查点。")
                        }
                        DiscardedWorkflowRestoreResult.EXPIRED -> {
                            onUndoExpired()
                            setRunStatus("撤销窗口已结束，检查点未恢复。", isError = true)
                        }
                        DiscardedWorkflowRestoreResult.ALREADY_CONSUMED -> {
                            onUndoExpired()
                            setRunStatus("该撤销请求已处理，不会重复恢复检查点。", isError = true)
                        }
                        DiscardedWorkflowRestoreResult.CONFLICT -> {
                            onUndoExpired()
                            setRunStatus("检测到同一任务的较新检查点，已保留新记录并拒绝覆盖。", isError = true)
                            checkRecoverableWorkflows()
                        }
                        DiscardedWorkflowRestoreResult.FAILED -> {
                            onUndoExpired()
                            setRunStatus("检查点恢复失败；撤销令牌已安全关闭，不会重复写入。", isError = true)
                        }
                    }
                }
            },
        )
        checkpointUndoTimers += expiryTimer
        expiryTimer.start()
    }

    private fun resumeInterruptedWorkflow(
        workflow: RecoverableWorkflow,
        notice: AssistantTurnPanel,
    ) {
        if (service.isRunning() || commitAi.isRunning) {
            setRunStatus("当前任务仍在运行，请稍后恢复。")
            return
        }
        if (pendingAttachmentBatches > 0) {
            setRunStatus("恢复图片仍在读取，请等待附件栏更新后再确认。")
            requestComposerFocusLater()
            return
        }
        val reattachedImages = workflowRecoveryImages.selectedImages(attachments, workflow.workflowId)
        if (reattachedImages.size < workflow.requiredImageAttachments) {
            val missingCount = workflow.requiredImageAttachments - reattachedImages.size
            setRunStatus(
                "还需重新添加 $missingCount 张原任务图片；已有草稿图片不会自动用于恢复。",
                isError = true,
            )
            requestComposerFocusLater()
            return
        }
        if (reattachedImages.size > workflow.requiredImageAttachments) {
            setRunStatus(
                "原任务需要 ${workflow.requiredImageAttachments} 张图片，但已为恢复选择 ${reattachedImages.size} 张；" +
                    "请从附件栏移除多余图片后再继续。",
                isError = true,
                detail = reattachedImages.joinToString("\n") { it.fileName },
            )
            requestComposerFocusLater()
            return
        }
        if (reattachedImages.isNotEmpty()) {
            val confirmed = Messages.showYesNoDialog(
                project,
                recoveryImageConfirmationText(reattachedImages),
                "确认恢复图片",
                "使用这些图片并继续",
                "返回检查",
                Messages.getQuestionIcon(),
            )
            if (confirmed != Messages.YES) {
                setRunStatus("已保留全部草稿附件，可继续预览或调整恢复图片。")
                requestComposerFocusLater()
                return
            }
        }
        notice.clearRecoveryAction()
        workflowRecoveryImages.pause(workflow.workflowId)
        composerModeState = composerModeState
            .select(workflow.mode)
            .selectExecutionStrategy(workflow.strategy)
        activeRunMode = workflow.mode
        activeRecoveryWorkflow = workflow
        activeRunStrategy = workflow.strategy
        activeWorkflowId = workflow.workflowId
        executionToolCount = 0
        executionSubagentCount = 0
        executionEditCount = 0
        lastSubmission = null
        updateComposerModeUi()
        addUserMessage("继续中断任务：${workflow.title}")
        val turn = beginAssistantTurn()
        setRunStatus("正在读取安全检查点…")
        service.resumeWorkflow(
            workflowId = workflow.workflowId,
            approvalGate = approvalGate,
            callbacks = AgentRunCallbacks(
                onRunningChanged = ::setRunning,
                onEvent = ::handleAgentEvent,
                onResult = ::handleResult,
            ),
            reattachedImages = reattachedImages,
        ) { started ->
            if (disposed) return@resumeWorkflow
            if (started) {
                removeTranscriptComponent(notice)
                consumeReattachedImages(reattachedImages)
                workflowRecoveryImages.clear(workflow.workflowId)
                if (service.isRunning()) setRunStatus("已从第 ${workflow.iteration} 轮恢复。")
            } else {
                workflowRecoveryImages.begin(
                    workflow.workflowId,
                    acceptsImages = workflow.requiredImageAttachments > 0,
                )
                activeRecoveryWorkflow = null
                turn.appendText("无法恢复：检查点已不存在，或已有任务正在运行。")
                turn.finish("!  恢复失败", isError = true)
                activeTurn = null
                activeTurnBlock = null
                removeTranscriptComponent(notice)
                checkRecoverableWorkflows()
                setRunStatus("无法恢复该任务。", isError = true)
            }
        }
    }

    private fun removeTranscriptComponent(component: JComponent) {
        val block = transcriptBlocks.firstOrNull { it.component === component } ?: return
        transcriptBlocks.remove(block)
        transcriptCharacters = (transcriptCharacters - block.characters).coerceAtLeast(0)
        conversation.removeBlock(component)
        if (recoverableWorkflowTurn === component) recoverableWorkflowTurn = null
        refreshBodyState()
    }

    private fun consumeReattachedImages(consumed: List<UserAttachment>) {
        if (consumed.isEmpty()) return
        removeAttachmentsByIdentity(attachments, consumed)
        attachmentSourceKeys.entries.removeIf { entry -> consumed.any { it === entry.value } }
        consumed.forEach(workflowRecoveryImages::forget)
        renderAttachmentTray()
        updateSendButtonState()
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
        val displayedMode = activeRunMode?.takeIf { service.isRunning() }
            ?: composerPromptResolution(input.text).modeOverride
            ?: composerModeState.selectedMode
        val visibility = composerToolbarVisibility(displayedMode, layoutMode, sandboxMode)
        // Project context is useful in every mode and must not disappear with the optional danger
        // sandbox warning. Only the sandbox chip itself follows the responsive visibility policy.
        sandboxControl.isVisible = true
        contextButton.isVisible = true
        sandboxButton.isVisible = visibility.showSandbox
        updateFooterResponsiveVisibility()
        updateModeButtonUi(layoutMode)
        updateTeamButtonUi()
        lastProviderStatus?.let(::updateFooterLabels)
        updateReasoningButton()
        updateSandboxButton(displayedMode)
        if (::composerToolbar.isInitialized) composerToolbar.revalidate()
        revalidate()
        repaint()
    }

    private fun updateSandboxButton(displayedMode: AgentMode) {
        val sandboxMode = OmniCodePlatformSettingsService.getInstance().snapshot().sandboxMode
        val presentation = sandboxButtonPresentation(displayedMode, sandboxMode, width)
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

    private fun updateReasoningButton() {
        val snapshot = OmniCodeSettingsService.getInstance().snapshot()
        val continuousExecution = OmniCodePlatformSettingsService.getInstance()
            .snapshot().agentRuntime.continuousExecution
        val selected = activeRunReasoningEffort?.takeIf { service.isRunning() } ?: snapshot.reasoningEffort
        val preset = ProviderPresets.byId(snapshot.providerId)
        val resolution = resolveReasoningEffort(
            providerId = preset.id,
            protocol = preset.protocol,
            model = snapshot.model,
            requested = selected,
        )
        val label = reasoningEffortLabel(selected)
        reasoningButton.text = if (composerLayoutMode(width) == ComposerLayoutMode.NARROW) {
            "$label  ▾"
        } else {
            "思考·$label  ▾"
        }
        reasoningButton.toolTipText = buildString {
            append("推理强度：$label；")
            append(resolution.explanation)
            append("。配置输出上限 ${snapshot.maxOutputTokens} Token，超长响应会自动分段续写；")
            append(if (continuousExecution) {
                "持续执行已开启，任务累计时长、轮次、工具调用、Token 与费用不设本地硬上限。"
            } else {
                "当前使用有限模式，累计轮次、工具调用和任务时长上限会生效。"
            })
        }
        reasoningButton.accessibleContext.accessibleName = "模型推理强度：$label"
        reasoningButton.foreground = when {
            !resolution.supported -> OmniCodeUiPalette.error
            selected == ReasoningEffort.MAX -> OmniCodeUiPalette.warning
            else -> workshopColors?.secondaryText ?: OmniCodeUiPalette.secondary
        }
    }

    private fun showReasoningEffortMenu() {
        if (service.isRunning() || commitAi.isRunning) return
        val settings = OmniCodeSettingsService.getInstance()
        val snapshot = settings.snapshot()
        val preset = ProviderPresets.byId(snapshot.providerId)
        val options = reasoningEffortOptions(preset.id, preset.protocol, snapshot.model)
        val popup = JPopupMenu()
        options.forEach { effort ->
            val resolution = resolveReasoningEffort(preset.id, preset.protocol, snapshot.model, effort)
            popup.add(JRadioButtonMenuItem(reasoningEffortLabel(effort), effort == snapshot.reasoningEffort).apply {
                toolTipText = resolution.explanation
                addActionListener {
                    if (effort == ReasoningEffort.MAX) {
                        val confirmed = Messages.showYesNoDialog(
                            project,
                            "全速会使用当前模型可验证的最高推理档位；GPT-5.6 Responses 还会启用 Pro 模式。\n\n同时开启持续执行，不再因累计时长、轮次、工具调用、Token 或本地费用估算终止任务。单次操作和安全保护仍会生效，延迟与实际费用可能显著增加。",
                            "启用全速推理",
                            "启用全速",
                            "取消",
                            Messages.getWarningIcon(),
                        )
                        if (confirmed != Messages.YES) return@addActionListener
                        OmniCodePlatformSettingsService.getInstance().update { state ->
                            state.applyFullSpeedRuntimePreset()
                        }
                    }
                    val floor = effort.recommendedOutputTokenFloor()
                    settings.update(
                        snapshot.copy(
                            reasoningEffort = effort,
                            maxOutputTokens = maxOf(snapshot.maxOutputTokens, floor),
                        ),
                    )
                    updateReasoningButton()
                    setRunStatus(
                        if (effort == ReasoningEffort.MAX) {
                            "全速已启用 · 持续执行"
                        } else {
                            "推理强度 · ${reasoningEffortLabel(effort)}"
                        },
                    )
                }
            })
        }
        popup.addSeparator()
        popup.add(JMenuItem("配置单轮输出与运行保护…").apply {
            addActionListener { settingsNavigator(OmniCodeSettingsPage.RUNTIME) }
        })
        popup.show(reasoningButton, 0, -popup.preferredSize.height)
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

    internal fun continueUnifiedTask(task: UnifiedTaskEntry) {
        if (!canStartNewChat() || task.workflowId == null) return
        service.listRecoverableWorkflows { workflows ->
            if (disposed) return@listRecoverableWorkflows
            val workflow = workflows.firstOrNull { it.workflowId == task.workflowId }
            if (workflow == null) {
                setRunStatus("该任务已没有可恢复检查点。", isError = true)
                return@listRecoverableWorkflows
            }
            showRecoverableWorkflow(workflow)
            if (workflow.requiredImageAttachments == 0) {
                recoverableWorkflowTurn?.let { turn -> resumeInterruptedWorkflow(workflow, turn) }
            }
        }
    }

    internal fun retryUnifiedTask(task: UnifiedTaskEntry) {
        if (!canStartNewChat()) return
        service.taskPrompt(task) { prompt ->
            if (disposed || prompt.isNullOrBlank()) {
                if (!disposed) setRunStatus("该任务没有可重试的文本目标。", isError = true)
                return@taskPrompt
            }
            clearConversation()
            composerModeState = composerModeState.select(task.mode)
                .selectExecutionStrategy(task.strategy)
            updateComposerModeUi()
            input.text = prompt
            input.caretPosition = input.document.length
            if (task.requiredImageAttachments > 0) {
                setRunStatus(
                    "已恢复任务文本；请重新添加并确认 ${task.requiredImageAttachments} 张原图后再发送。",
                )
                requestComposerFocusLater()
            } else {
                submitPrompt()
            }
        }
    }

    internal fun copyUnifiedTask(task: UnifiedTaskEntry) {
        if (!canStartNewChat()) return
        service.taskPrompt(task) { prompt ->
            if (disposed || prompt.isNullOrBlank()) {
                if (!disposed) setRunStatus("该任务没有可复制的文本目标。", isError = true)
                return@taskPrompt
            }
            clearConversation()
            composerModeState = composerModeState.select(task.mode)
                .selectExecutionStrategy(task.strategy)
            updateComposerModeUi()
            input.text = prompt
            input.caretPosition = input.document.length
            setRunStatus("任务已复制到新草稿；附件需重新选择。")
            requestComposerFocusLater()
        }
    }

    internal fun restoreUnifiedTaskCheckpoint(task: UnifiedTaskEntry) {
        val restore: (((Boolean) -> Unit) -> Unit) = when {
            task.workflowId != null -> { callback -> service.restoreWorkflowCheckpoint(task.workflowId, callback) }
            task.conversationId != null -> { callback -> service.restoreConversation(task.conversationId, callback) }
            else -> run {
                setRunStatus("该任务没有可恢复的会话检查点。", isError = true)
                return
            }
        }
        if (!canStartNewChat()) return
        restore callback@ { restored ->
            if (disposed) return@callback
            if (restored) {
                resetConversationView()
                synchronizeComposerModeFromConversation()
                restoreHistory()
                setRunStatus("已回到所选任务检查点；不会自动执行或回放副作用。")
            } else {
                setRunStatus("无法恢复该任务检查点。", isError = true)
            }
        }
    }

    internal fun canStartNewChat(): Boolean = !disposed && !service.isRunning() && !commitAi.isRunning

    internal fun canGenerateCommitMessage(): Boolean = canStartNewChat()

    internal fun latestReviewWorkflowId(): String? = activeWorkflowId ?: lastReviewWorkflowId

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
                        experimentLock = ResearchExperimentLock(
                            workspaceRelative = ".",
                            sandbox = platform.snapshot().sandboxMode.name,
                            dependencySummary = "未自动采集；请按证据表命令复核",
                        ),
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
        if (scrollRequestPending) return
        val requestedValue = conversationScroll.verticalScrollBar.value
        scrollRequestPending = true
        SwingUtilities.invokeLater {
            scrollRequestPending = false
            if (disposed) return@invokeLater
            val bar = conversationScroll.verticalScrollBar
            // A wheel/drag action after this request means the user chose to keep reading above.
            // Do not let a queued layout callback pull the viewport away from that position.
            if (bar.value != requestedValue) return@invokeLater
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
        const val PET_TERMINAL_STATE_MS = 2_800
        const val MAX_TRANSCRIPT_CHARS = 500_000
        const val MAX_TOOL_RESULT_CHARS = 4_000
        const val SMALL_TOOL_WINDOW_WIDTH = 360
        const val FILE_MENTION_DEBOUNCE_MS = 120L
        const val CHECKPOINT_DISCARD_UNDO_MILLIS = 8_000
        val CLIPBOARD_IMAGE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

internal fun checkpointDiscardConfirmationText(title: String, pendingToolDangerous: Boolean): String {
    val safeTitle = title.replace(Regex("[\\p{Cntrl}]"), " ").trim().take(120).ifBlank { "未完成任务" }
    return buildString {
        append("确定放弃“").append(safeTitle).append("”的本地恢复检查点？\n\n")
        append("这不会撤销已经发生的文件改动或外部副作用。删除成功后 8 秒内可撤销。")
        if (pendingToolDangerous) {
            append("\n\n该任务还有副作用状态未知的工具；放弃检查点后将无法从此记录继续核对。")
        }
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
        label = "Plan 看板",
        menuSummary = "结构化只读规划",
        description = "只读分析并生成可编辑、可分步批准的执行看板",
        runningStatus = "Plan 看板正在制定计划…",
    )
    AgentMode.CLAUDE_PLAN -> ComposerModePresentation(
        label = "Claude Plan",
        menuSummary = "先探索，再批准执行",
        description = "仿 Claude Code：可读文件、检索代码并运行只读探索命令，批准计划后才修改",
        runningStatus = "Claude Plan 正在探索与规划…",
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

internal fun planStepExecutionPrompt(board: PlanBoard, stepId: String): String {
    val targetIndex = board.steps.indexOfFirst { it.id == stepId }
    require(targetIndex >= 0) { "Plan step is no longer present" }
    val target = board.steps[targetIndex]
    return buildString {
        appendLine("执行已批准计划 ${board.sourceFingerprint} 的第 ${targetIndex + 1}/${board.steps.size} 步。")
        appendLine("只完成本步骤，不要提前执行其他待批准、草稿或已跳过步骤。")
        appendLine("开始前重新读取相关文件；完成后运行本步骤最窄的有效验证并汇报证据。")
        appendLine()
        appendLine("当前步骤：")
        appendLine(target.text)
        appendLine()
        appendLine("看板边界：")
        board.steps.forEachIndexed { index, step ->
            append(index + 1).append(". ").append(step.state.name).append(" · ")
                .append(step.text.replace('\n', ' ').take(360)).appendLine()
        }
    }.take(AgentEngine.MAX_USER_MESSAGE_CHARS)
}

internal fun planStepTranscriptText(board: PlanBoard, stepId: String): String {
    val targetIndex = board.steps.indexOfFirst { it.id == stepId }
    require(targetIndex >= 0) { "Plan step is no longer present" }
    val compact = board.steps[targetIndex].text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .take(320)
    return "执行计划步骤 ${targetIndex + 1}/${board.steps.size}：$compact"
}

internal fun shouldOfferSubmissionRecovery(status: AgentRunStatus): Boolean = when (status) {
    AgentRunStatus.COMPLETED -> false
    AgentRunStatus.CANCELLED,
    AgentRunStatus.FAILED,
    AgentRunStatus.BUDGET_EXHAUSTED -> true
}

internal fun mergeRecoveredPrompt(existing: String, recovered: String): String = when {
    existing.isBlank() -> recovered
    recovered.isBlank() || existing.trim() == recovered.trim() -> existing
    else -> existing.trimEnd() + "\n\n--- 上次未完成任务（已合并）---\n" + recovered
}

internal fun canMergeRecoveredAttachments(
    currentAttachmentCount: Int,
    recoveredAttachmentCount: Int,
): Boolean = currentAttachmentCount >= 0 && recoveredAttachmentCount >= 0 &&
    currentAttachmentCount + recoveredAttachmentCount <= AttachmentIntake.MAX_ATTACHMENTS

/**
 * Tracks images explicitly added while a particular interrupted-workflow notice is active.
 *
 * Identity semantics are intentional: two separately added images may have equal bounded payloads,
 * but selecting or consuming one must never select or remove the other draft attachment.
 */
internal class WorkflowRecoveryImageSelection {
    private val workflowByImage = IdentityHashMap<UserAttachment, String>()
    private var activeWorkflowId: String? = null

    fun begin(workflowId: String, acceptsImages: Boolean) {
        activeWorkflowId = workflowId.takeIf { acceptsImages }
        if (!acceptsImages) clear(workflowId)
    }

    /** Capture this when user intake starts, before asynchronous decoding begins. */
    fun captureTarget(): String? = activeWorkflowId

    fun record(attachment: UserAttachment, capturedWorkflowId: String?): Boolean {
        if (capturedWorkflowId == null || attachment.kind != AttachmentKind.IMAGE) return false
        workflowByImage[attachment] = capturedWorkflowId
        return true
    }

    fun selectedImages(
        attachments: List<UserAttachment>,
        workflowId: String,
    ): List<UserAttachment> = attachments.filter { attachment ->
        attachment.kind == AttachmentKind.IMAGE && workflowByImage[attachment] == workflowId
    }

    fun isSelectedFor(attachment: UserAttachment, workflowId: String?): Boolean =
        workflowId != null && workflowByImage[attachment] == workflowId

    fun isActive(workflowId: String?): Boolean = workflowId != null && activeWorkflowId == workflowId

    fun forget(attachment: UserAttachment) {
        workflowByImage.remove(attachment)
    }

    fun forgetAllAttachments() {
        workflowByImage.clear()
    }

    fun pause(workflowId: String) {
        if (activeWorkflowId == workflowId) activeWorkflowId = null
    }

    fun clear(workflowId: String) {
        pause(workflowId)
        val iterator = workflowByImage.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == workflowId) iterator.remove()
        }
    }

    fun reset() {
        activeWorkflowId = null
        workflowByImage.clear()
    }
}

internal fun removeAttachmentsByIdentity(
    attachments: MutableList<UserAttachment>,
    consumed: List<UserAttachment>,
) {
    if (consumed.isEmpty()) return
    val iterator = attachments.iterator()
    while (iterator.hasNext()) {
        val attachment = iterator.next()
        if (consumed.any { it === attachment }) iterator.remove()
    }
}

internal fun recoveryImageConfirmationText(images: List<UserAttachment>): String = buildString {
    append("将仅使用以下 ")
    append(images.size)
    append(" 张图片恢复中断任务：\n\n")
    images.forEach { image ->
        val safeName = image.fileName.lineSequence().firstOrNull().orEmpty().take(100).ifBlank { "未命名图片" }
        append("• ")
        append(safeName)
        append(" · ")
        append(attachmentDisplaySize(image.byteSize))
        append('\n')
    }
    append("\n附件栏中的其他草稿文件不会发送，也不会被删除。")
}

internal data class ComposerModeState(
    val selectedMode: AgentMode = AgentMode.AGENT,
    val executionStrategy: AgentExecutionStrategy = AgentExecutionStrategy.SINGLE,
) {
    fun select(mode: AgentMode): ComposerModeState = copy(selectedMode = mode)

    fun selectExecutionStrategy(strategy: AgentExecutionStrategy): ComposerModeState = copy(executionStrategy = strategy)

    fun snapshot(prompt: String): ComposerSubmission = ComposerSubmission(prompt, selectedMode, executionStrategy)

    fun snapshot(resolution: ComposerPromptResolution): ComposerSubmission = ComposerSubmission(
        prompt = resolution.prompt,
        mode = resolution.modeOverride ?: selectedMode,
        strategy = executionStrategy,
    )
}

internal data class ComposerPromptResolution(
    val prompt: String,
    val modeOverride: AgentMode? = null,
    val command: ComposerCommand? = null,
)

internal enum class ComposerCommand(val requiresModel: Boolean) {
    MODEL(false),
    REVIEW(true),
    STATUS(false),
    PERMISSIONS(false),
    MCP(false),
    TASKS(false),
    NEW(false),
    HELP(false),
}

/**
 * Resolves composer-only slash commands before creating the persisted user submission.
 * `/plan` is deliberately a one-turn override: it never mutates [ComposerModeState].
 */
internal fun composerPromptResolution(rawPrompt: String): ComposerPromptResolution {
    val trimmed = rawPrompt.trim()
    val isPlanCommand = trimmed == "/plan" ||
        (trimmed.startsWith("/plan") && trimmed.getOrNull("/plan".length)?.isWhitespace() == true)
    if (isPlanCommand) {
        return ComposerPromptResolution(
            prompt = trimmed.drop("/plan".length).trim(),
            modeOverride = AgentMode.CLAUDE_PLAN,
        )
    }

    val command = listOf(
        "/review" to ComposerCommand.REVIEW,
        "/status" to ComposerCommand.STATUS,
        "/model" to ComposerCommand.MODEL,
        "/permissions" to ComposerCommand.PERMISSIONS,
        "/permission" to ComposerCommand.PERMISSIONS,
        "/mcp" to ComposerCommand.MCP,
        "/tasks" to ComposerCommand.TASKS,
        "/new" to ComposerCommand.NEW,
        "/help" to ComposerCommand.HELP,
    ).firstOrNull { (name, _) ->
        trimmed == name || (trimmed.startsWith(name) && trimmed.getOrNull(name.length)?.isWhitespace() == true)
    }
    if (command == null) return ComposerPromptResolution(prompt = trimmed)

    val (name, value) = command
    val remainder = trimmed.drop(name.length).trim()
    if (value == ComposerCommand.REVIEW) {
        return ComposerPromptResolution(
            prompt = remainder.ifBlank {
                "审阅当前 Git 工作区差异，找出正确性、回归、安全性和缺失验证问题；只报告证据，不修改文件。"
            },
            modeOverride = AgentMode.RESEARCH,
            command = value,
        )
    }

    return ComposerPromptResolution(
        prompt = remainder,
        command = value,
    )
}

internal fun synchronizeComposerModeState(
    current: ComposerModeState,
    conversationMode: AgentMode,
): ComposerModeState = current.select(conversationMode)

internal fun nextComposerMode(mode: AgentMode): AgentMode = when (mode) {
    AgentMode.AGENT -> AgentMode.PLAN
    AgentMode.PLAN -> AgentMode.CLAUDE_PLAN
    AgentMode.CLAUDE_PLAN -> AgentMode.RESEARCH
    AgentMode.RESEARCH -> AgentMode.AGENT
}

/** Shift+Tab mirrors Claude Code's normal/plan toggle without replacing the full mode cycle. */
internal fun nextClaudePlanShortcutMode(mode: AgentMode): AgentMode = when (mode) {
    AgentMode.CLAUDE_PLAN -> AgentMode.AGENT
    else -> AgentMode.CLAUDE_PLAN
}

internal fun installClaudePlanShortcut(
    component: JComponent,
    onToggle: () -> Unit,
) {
    val actionName = "omnicode.toggleClaudePlanMode"
    component.getInputMap(JComponent.WHEN_FOCUSED).put(
        KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK),
        actionName,
    )
    component.actionMap.put(actionName, object : AbstractAction() {
        override fun actionPerformed(event: java.awt.event.ActionEvent?) = onToggle()
    })
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
    AgentMode.PLAN -> if (layoutMode == ComposerLayoutMode.NARROW) "Plan" else "Plan · 看板"
    AgentMode.CLAUDE_PLAN -> if (layoutMode == ComposerLayoutMode.NARROW) "Claude" else "Claude Plan"
    AgentMode.RESEARCH -> if (layoutMode == ComposerLayoutMode.NARROW) "Research" else "Research · 实验"
}

internal fun teamButtonText(strategy: AgentExecutionStrategy, layoutMode: ComposerLayoutMode): String = when {
    layoutMode == ComposerLayoutMode.NARROW && strategy == AgentExecutionStrategy.TEAM -> "T · 开"
    layoutMode == ComposerLayoutMode.NARROW && strategy == AgentExecutionStrategy.AUTO -> "自动"
    layoutMode == ComposerLayoutMode.NARROW -> "T"
    strategy == AgentExecutionStrategy.TEAM -> "Team · 开"
    strategy == AgentExecutionStrategy.AUTO -> "自动路由"
    else -> "Team"
}

internal fun executionStrategyLabel(strategy: AgentExecutionStrategy): String = when (strategy) {
    AgentExecutionStrategy.AUTO -> "自动路由"
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
    showSandbox = agentMode in setOf(AgentMode.AGENT, AgentMode.RESEARCH) &&
        sandboxMode == SandboxMode.DANGER_FULL_ACCESS,
    showProvider = layoutMode != ComposerLayoutMode.NARROW,
)

internal fun createComposerToolbar(
    addButton: JComponent,
    modeButton: JComponent,
    teamButton: JComponent,
    sandboxControl: JComponent,
    stopButton: JComponent,
    sendButton: JComponent,
): JPanel = ResponsiveComposerToolbar(
    addButton = addButton,
    modeButton = modeButton,
    teamButton = teamButton,
    sandboxControl = sandboxControl,
    stopButton = stopButton,
    sendButton = sendButton,
)

internal fun composerToolbarRowCount(width: Int): Int = if (width in 1 until 340) 2 else 1

private class ResponsiveComposerToolbar(
    private val addButton: JComponent,
    private val modeButton: JComponent,
    private val teamButton: JComponent,
    private val sandboxControl: JComponent,
    private val stopButton: JComponent,
    private val sendButton: JComponent,
) : JPanel() {
    init {
        layout = null
        isOpaque = false
        listOf(addButton, modeButton, teamButton, sandboxControl, stopButton, sendButton).forEach(::add)
    }

    override fun getPreferredSize(): Dimension {
        val availableWidth = width.takeIf { it > 0 } ?: parent?.width ?: 0
        val rows = if (availableWidth <= 0) 2 else composerToolbarRowCount(availableWidth)
        val rowHeight = preferredRowHeight()
        return Dimension(0, TOP_GAP + rowHeight * rows + if (rows == 2) ROW_GAP else 0)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun doLayout() {
        val availableWidth = width.coerceAtLeast(0)
        val rowHeight = preferredRowHeight().coerceAtMost((height - TOP_GAP).coerceAtLeast(0))
        if (composerToolbarRowCount(availableWidth) == 2) {
            layoutTwoRows(availableWidth, rowHeight)
        } else {
            layoutOneRow(availableWidth, rowHeight)
        }
    }

    private fun layoutTwoRows(availableWidth: Int, rowHeight: Int) {
        val addWidth = visibleWidth(addButton)
        val teamWidth = visibleWidth(teamButton)
        val modeX = addWidth + CONTROL_GAP
        val teamX = (availableWidth - teamWidth).coerceAtLeast(modeX)
        val modeWidth = (teamX - CONTROL_GAP - modeX).coerceAtLeast(0)
        place(addButton, 0, TOP_GAP, addWidth.coerceAtMost(availableWidth), rowHeight)
        place(modeButton, modeX.coerceAtMost(availableWidth), TOP_GAP, modeWidth, rowHeight)
        place(teamButton, teamX.coerceAtMost(availableWidth), TOP_GAP, teamWidth.coerceAtMost(availableWidth), rowHeight)

        val secondY = TOP_GAP + rowHeight + ROW_GAP
        val actionWidth = visibleWidth(stopButton) + visibleWidth(sendButton)
        val actionX = (availableWidth - actionWidth).coerceAtLeast(0)
        place(sandboxControl, 0, secondY, (actionX - CONTROL_GAP).coerceAtLeast(0), rowHeight)
        var x = actionX
        if (stopButton.isVisible) {
            val stopWidth = visibleWidth(stopButton)
            place(stopButton, x, secondY, stopWidth.coerceAtMost(availableWidth - x), rowHeight)
            x += stopWidth
        } else {
            stopButton.setBounds(0, 0, 0, 0)
        }
        val sendWidth = visibleWidth(sendButton)
        place(sendButton, x, secondY, sendWidth.coerceAtMost(availableWidth - x), rowHeight)
    }

    private fun layoutOneRow(availableWidth: Int, rowHeight: Int) {
        val actionWidth = visibleWidth(stopButton) + visibleWidth(sendButton)
        val actionX = (availableWidth - actionWidth).coerceAtLeast(0)
        var x = 0
        listOf(addButton to CONTROL_GAP, modeButton to SMALL_GAP, teamButton to 0).forEach { (component, gap) ->
            val componentWidth = visibleWidth(component).coerceAtMost((actionX - x).coerceAtLeast(0))
            place(component, x, TOP_GAP, componentWidth, rowHeight)
            x += componentWidth + gap
        }
        val sandboxWidth = visibleWidth(sandboxControl).coerceAtMost((actionX - x).coerceAtLeast(0))
        place(sandboxControl, x, TOP_GAP, sandboxWidth, rowHeight)

        x = actionX
        if (stopButton.isVisible) {
            val stopWidth = visibleWidth(stopButton)
            place(stopButton, x, TOP_GAP, stopWidth.coerceAtMost(availableWidth - x), rowHeight)
            x += stopWidth
        } else {
            stopButton.setBounds(0, 0, 0, 0)
        }
        val sendWidth = visibleWidth(sendButton)
        place(sendButton, x, TOP_GAP, sendWidth.coerceAtMost(availableWidth - x), rowHeight)
    }

    private fun preferredRowHeight(): Int = listOf(
        addButton,
        modeButton,
        teamButton,
        sandboxControl,
        stopButton,
        sendButton,
    ).filter { it.isVisible }.maxOfOrNull { it.preferredSize.height } ?: JBUI.scale(32)

    private fun visibleWidth(component: JComponent): Int =
        if (component.isVisible) component.preferredSize.width.coerceAtLeast(0) else 0

    private fun place(component: JComponent, x: Int, y: Int, width: Int, height: Int) {
        if (!component.isVisible) {
            component.setBounds(0, 0, 0, 0)
            return
        }
        component.setBounds(x.coerceAtLeast(0), y.coerceAtLeast(0), width.coerceAtLeast(0), height.coerceAtLeast(0))
        component.doLayout()
    }

    private companion object {
        val TOP_GAP: Int get() = JBUI.scale(6)
        val ROW_GAP: Int get() = JBUI.scale(4)
        val CONTROL_GAP: Int get() = JBUI.scale(6)
        val SMALL_GAP: Int get() = JBUI.scale(4)
    }
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
    if (agentMode == AgentMode.CLAUDE_PLAN) {
        return SandboxButtonPresentation(
            text = "只读",
            tooltip = "Claude Plan 可读取文件、使用 PSI / 索引检索并运行只读探索命令；文件修改与有副作用工具禁用",
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

internal fun reasoningEffortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.AUTO -> "自动"
    ReasoningEffort.NONE -> "关闭"
    ReasoningEffort.MINIMAL -> "最低"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.MEDIUM -> "中"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.XHIGH -> "超高"
    ReasoningEffort.MAX -> "全速"
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

/** Keeps the footer concise while detailed, allow-listed stages stay in the assistant timeline. */
internal fun userFacingRunStatus(message: String): String? {
    val normalized = message.lineSequence().firstOrNull().orEmpty().trim().take(240)
    if (normalized.isBlank()) return null
    return when {
        normalized.startsWith("推理强度") || normalized.startsWith("Project Harness") ||
            normalized.startsWith("Harness ·") ||
            normalized.contains("模式 · 已锁定") -> null
        normalized.startsWith("Thinking", ignoreCase = true) -> "模型思考中…"
        normalized.startsWith("Agent 正在处理") || normalized.startsWith("Plan 看板正在") ||
            normalized.startsWith("Claude Plan 正在") || normalized.startsWith("Research 正在") ||
            normalized.startsWith("正在建立安全恢复点") || normalized.startsWith("正在检查恢复状态") ||
            normalized.startsWith("正在加载模型配置") || normalized.startsWith("正在准备项目上下文") ||
            normalized.startsWith("正在并行连接 MCP") -> "正在准备任务…"
        normalized.startsWith("正在通过") && normalized.endsWith("识别图片…") -> "正在识别图片…"
        normalized.startsWith("Provider temporarily unavailable", ignoreCase = true) ||
            normalized.startsWith("Provider attempt may have consumed quota", ignoreCase = true) ->
            "模型连接不稳定，正在安全重试…"
        normalized.startsWith("Provider output segment reached", ignoreCase = true) ||
            normalized.startsWith("Provider stream was interrupted", ignoreCase = true) ->
            "正在自动衔接下一段输出…"
        normalized.startsWith("MCP ") -> "部分 MCP 服务不可用，任务继续"
        normalized.startsWith("检测到尚未解除的未知副作用恢复点") ->
            "检测到待确认的上次操作，本轮仅使用安全工具"
        normalized.startsWith("Specialist execution boundary", ignoreCase = true) -> "专家代理正在整理阶段结果…"
        normalized.startsWith("Usage could not be persisted", ignoreCase = true) ->
            "用量记录保存失败，任务结果不受影响"
        normalized.startsWith("Tool audit could not be persisted", ignoreCase = true) ->
            "工具审计保存失败，请检查本轮操作记录"
        normalized.startsWith("Checkpoint save failed", ignoreCase = true) ->
            "恢复点保存异常，请先检查任务状态"
        else -> null
    }
}

internal fun isCriticalRunWarning(message: String): Boolean {
    val normalized = message.lineSequence().firstOrNull().orEmpty().trim()
    return normalized.startsWith("Checkpoint save failed", ignoreCase = true) ||
        normalized.startsWith("Tool audit could not be persisted", ignoreCase = true) ||
        normalized.startsWith("检测到尚未解除的未知副作用恢复点")
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
    is AgentEvent.ProjectContextPrepared,
    is AgentEvent.BudgetWarning,
    is AgentEvent.StageStarted,
    is AgentEvent.StageCompleted,
    is AgentEvent.ProviderRequestStarted,
    is AgentEvent.ProviderRetryScheduled,
    -> null
}

internal fun delegateProgressStatus(status: AgentRunStatus, usable: Boolean): DelegateProgressStatus = when (status) {
    AgentRunStatus.COMPLETED -> DelegateProgressStatus.COMPLETED
    AgentRunStatus.BUDGET_EXHAUSTED -> if (usable) DelegateProgressStatus.PARTIAL else DelegateProgressStatus.FAILED
    AgentRunStatus.CANCELLED -> DelegateProgressStatus.CANCELLED
    AgentRunStatus.FAILED -> DelegateProgressStatus.FAILED
}

internal fun delegateCompletionStatusText(status: AgentRunStatus): String = when (status) {
    AgentRunStatus.COMPLETED -> "已完成"
    AgentRunStatus.CANCELLED -> "已取消"
    AgentRunStatus.FAILED -> "失败"
    AgentRunStatus.BUDGET_EXHAUSTED -> "已返回阶段结果"
}

internal fun delegateRoleLabel(role: AgentRole): String = when (role) {
    AgentRole.EXPLORER -> "探索"
    AgentRole.PLANNER -> "规划"
    AgentRole.REVIEWER -> "评审"
    AgentRole.CUSTOM -> "专家"
    AgentRole.LEAD -> "主代理"
}

internal fun composerSendEnabled(
    isRunning: Boolean,
    isCommitAiRunning: Boolean,
    providerConfigured: Boolean,
    prompt: String,
    attachmentCount: Int = 0,
    pendingAttachmentLoads: Int = 0,
): Boolean {
    if (isRunning || isCommitAiRunning || pendingAttachmentLoads != 0) return false
    val resolution = composerPromptResolution(prompt)
    val hasLocalCommand = resolution.command?.requiresModel == false
    if (!providerConfigured && !hasLocalCommand) return false
    return resolution.prompt.isNotBlank() || attachmentCount > 0 || hasLocalCommand
}

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
