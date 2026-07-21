package dev.omnicode.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.OmniCodeProjectService
import dev.omnicode.plan.PlanBoardService
import dev.omnicode.review.TaskChangeReviewService
import dev.omnicode.settings.InsightsEmbeddedSettings
import dev.omnicode.settings.OmniCodeEmbeddedSettings
import dev.omnicode.settings.OmniCodeSettingsSaveException
import dev.omnicode.settings.PlatformEmbeddedSettings
import dev.omnicode.settings.ProviderEmbeddedSettings
import dev.omnicode.ui.workshop.CreativeWorkshopPanel
import dev.omnicode.ui.workshop.WorkshopUiColors
import dev.omnicode.ui.workshop.toWorkspaceColors
import dev.omnicode.workshop.ResolvedWorkshopSelection
import dev.omnicode.workshop.WorkshopSettingsService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

internal enum class OmniCodeSettingsPage(
    val label: String,
    val description: String,
    val glyph: String,
    internal val module: EmbeddedSettingsModule,
    internal val tabIndex: Int,
) {
    PROVIDERS("API 与模型", "供应商、API Key、接口地址、模型与视觉辅助", "◇", EmbeddedSettingsModule.PROVIDER, -1),
    GENERAL("常规", "本地记录与数据保留策略", "☰", EmbeddedSettingsModule.PLATFORM, 0),
    RUNTIME("运行控制", "Agent 的时间、Token、重试和费用边界", "⌛", EmbeddedSettingsModule.PLATFORM, 6),
    SANDBOX("沙箱", "命令执行的系统权限边界", "▣", EmbeddedSettingsModule.PLATFORM, 1),
    MCP("MCP 服务", "配置和管理 Model Context Protocol 服务器", "⇄", EmbeddedSettingsModule.PLATFORM, 2),
    COMMIT_AI("Commit AI", "配置 AI 生成 Git Commit 信息的行为", "✓", EmbeddedSettingsModule.PLATFORM, 3),
    PROMPTS("提示词库", "管理输入 ! 可快速插入的提示词模板", "!", EmbeddedSettingsModule.PLATFORM, 4),
    SKILLS("Skill 库", "管理 Agent 可发现和加载的 Skill 来源", "✦", EmbeddedSettingsModule.PLATFORM, 5),
    USAGE("使用统计", "Token、费用与使用趋势", "⌁", EmbeddedSettingsModule.INSIGHTS, 0),
    HISTORY("历史记录", "查看和管理本地保存的会话", "◷", EmbeddedSettingsModule.INSIGHTS, 1),
    AUDIT("工具审计", "查看工具调用、审批与执行结果", "◎", EmbeddedSettingsModule.INSIGHTS, 2),
    PRICING("价格配置", "维护模型 Token 价格规则", "$", EmbeddedSettingsModule.INSIGHTS, 3),
}

internal enum class EmbeddedSettingsModule { PROVIDER, PLATFORM, INSIGHTS }

internal enum class SettingsSidebarMode { FULL, RAIL }

internal enum class OmniCodeToolDestination { CHAT, TASKS, PLAN, REVIEW, CONTEXT, DIAGNOSTICS, WORKSHOP, SETTINGS }

internal fun settingsSidebarMode(width: Int): SettingsSidebarMode =
    if (width >= 580) SettingsSidebarMode.FULL else SettingsSidebarMode.RAIL

internal class OmniCodeToolWindowPanel(
    private val project: com.intellij.openapi.project.Project,
    service: OmniCodeProjectService,
) : JPanel(BorderLayout()), Disposable {
    private val rootLayout = CardLayout()
    private val rootCards = JPanel(rootLayout)
    private val settingsPageLayout = CardLayout()
    private val settingsPageHost = JPanel(settingsPageLayout)
    private val settingsScreen = JPanel(BorderLayout())
    private val settingsSidebar = JPanel()
    private val settingsSidebarScroll = JBScrollPane(settingsSidebar)
    private val sidebarDivider = JPanel()
    private val chatNavButton = SettingsNavButton("聊天", "返回 Agent 工作台").apply {
        addActionListener { returnToChat() }
    }
    private val planNavButton = SettingsNavButton("计划看板", "编辑、批准、跳过、暂停或重试计划步骤").apply {
        addActionListener { openPlanBoard() }
    }
    private val tasksNavButton = SettingsNavButton("任务中心", "统一查看运行、待恢复、失败和已完成任务").apply {
        addActionListener { openTaskCenter() }
    }
    private val diagnosticsNavButton = SettingsNavButton("连接诊断", "检查 API、网络、模型、MCP OAuth 与沙箱").apply {
        addActionListener { openDiagnostics() }
    }
    private val reviewNavButton = SettingsNavButton("变更审阅", "逐文件和逐块保留、回退已记录的 Agent 直接修改").apply {
        addActionListener { openChangeReview() }
    }
    private val contextNavButton = SettingsNavButton("项目上下文", "项目规则、PSI/符号索引、固定与排除文件").apply {
        addActionListener { openProjectContext() }
    }
    private val workshopNavButton = SettingsNavButton("创意工坊", "皮肤、桌宠与工作台个性化").apply {
        addActionListener { openWorkshop() }
    }
    private val settingsTitle = JBLabel(OmniCodeSettingsPage.PROVIDERS.label).apply {
        font = JBFont.label().asBold()
        foreground = OmniCodeUiPalette.primary
    }
    private val settingsDescription = JBLabel(OmniCodeSettingsPage.PROVIDERS.description).apply {
        font = JBFont.small()
        foreground = OmniCodeUiPalette.secondary
    }
    private val settingsStatus = JBLabel("").apply {
        font = JBFont.small()
        foreground = OmniCodeUiPalette.secondary
        minimumSize = Dimension(0, preferredSize.height)
    }
    private val saveButton = JButton("保存")
    private val resetButton = JButton("重置")
    private val pageEntries = linkedMapOf<EmbeddedSettingsModule, EmbeddedSettingsPage>()
    private val navButtons = OmniCodeSettingsPage.entries.associateWith(::createNavButton)
    private var currentPage = OmniCodeSettingsPage.PROVIDERS
    private var destination = OmniCodeToolDestination.CHAT
    private val workshopSettings = WorkshopSettingsService.getInstance()
    @Volatile
    private var disposed = false

    internal val chatPanel = OmniCodeChatPanel(
        project,
        service,
        ::openSettings,
        ::openPlanBoard,
        ::openChangeReview,
        ::openProjectContext,
    )
    private val planBoardPanel = PlanBoardPanel(
        PlanBoardService.getInstance(project),
        object : PlanBoardActions {
            override fun executeApprovedSteps() = chatPanel.executeApprovedPlanSteps()
            override fun pauseExecution() = chatPanel.pausePlanExecution()
            override fun continuePlanning(board: dev.omnicode.plan.PlanBoard) {
                returnToChat()
                chatPanel.continuePlanning(board)
            }
            override fun returnToChat() {
                this@OmniCodeToolWindowPanel.returnToChat()
            }
        },
    )
    private val taskCenterPanel = TaskCenterPanel(
        service,
        object : TaskCenterActions {
            override fun continueTask(task: dev.omnicode.service.UnifiedTaskEntry) {
                returnToChat()
                chatPanel.continueUnifiedTask(task)
            }
            override fun retryTask(task: dev.omnicode.service.UnifiedTaskEntry) {
                returnToChat()
                chatPanel.retryUnifiedTask(task)
            }
            override fun copyTask(task: dev.omnicode.service.UnifiedTaskEntry) {
                returnToChat()
                chatPanel.copyUnifiedTask(task)
            }
            override fun restoreCheckpoint(task: dev.omnicode.service.UnifiedTaskEntry) {
                returnToChat()
                chatPanel.restoreUnifiedTaskCheckpoint(task)
            }
            override fun returnToChat() {
                this@OmniCodeToolWindowPanel.returnToChat()
            }
        },
    )
    private val diagnosticsPanel = ConnectionDiagnosticsPanel()
    private val changeReviewPanel = TaskChangeReviewPanel(
        reviewService = TaskChangeReviewService.getInstance(project),
        preferredWorkflowId = chatPanel::latestReviewWorkflowId,
        canModify = chatPanel::canStartNewChat,
        beginMutation = service::beginTaskReviewMutation,
        endMutation = service::endTaskReviewMutation,
        returnToChat = { returnToChat() },
    )
    private val projectContextPanel = ProjectContextPanel(project)
    private val workshopPanel = CreativeWorkshopPanel(::applyWorkshopSelection)

    init {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        Disposer.register(this, chatPanel)
        Disposer.register(this, planBoardPanel)
        Disposer.register(this, taskCenterPanel)
        Disposer.register(this, diagnosticsPanel)
        Disposer.register(this, changeReviewPanel)
        Disposer.register(this, projectContextPanel)
        Disposer.register(this, workshopPanel)

        rootCards.isOpaque = false
        rootCards.add(chatPanel, CHAT_CARD)
        rootCards.add(planBoardPanel, PLAN_CARD)
        rootCards.add(taskCenterPanel, TASKS_CARD)
        rootCards.add(diagnosticsPanel, DIAGNOSTICS_CARD)
        rootCards.add(changeReviewPanel, REVIEW_CARD)
        rootCards.add(projectContextPanel, CONTEXT_CARD)
        rootCards.add(workshopPanel, WORKSHOP_CARD)
        rootCards.add(buildSettingsScreen(), SETTINGS_CARD)
        add(buildNavigationSidebar(), BorderLayout.WEST)
        add(rootCards, BorderLayout.CENTER)
        rootLayout.show(rootCards, CHAT_CARD)
        chatNavButton.isSelected = true
        applyWorkshopSelection(workshopSettings.resolvedSelection())
        workshopSettings.addListener(this) { refreshWorkshopSelection() }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener { refreshWorkshopSelection() },
        )

        saveButton.addActionListener { applySettings() }
        resetButton.addActionListener { resetSettings() }
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateSidebarLayout()
        })
        SwingUtilities.invokeLater {
            if (!disposed) updateSidebarLayout()
        }
    }

    internal fun openSettings(page: OmniCodeSettingsPage = OmniCodeSettingsPage.PROVIDERS) {
        if (destination != OmniCodeToolDestination.SETTINGS) {
            destination = OmniCodeToolDestination.SETTINGS
            rootLayout.show(rootCards, SETTINGS_CARD)
        }
        showSettingsPage(page)
        updateSettingsActions()
    }

    internal fun openPlanBoard() {
        if (!leaveSettings()) {
            navButtons[currentPage]?.isSelected = true
            return
        }
        destination = OmniCodeToolDestination.PLAN
        planBoardPanel.refresh()
        rootLayout.show(rootCards, PLAN_CARD)
        planNavButton.isSelected = true
        rootCards.revalidate()
        rootCards.repaint()
    }

    internal fun openTaskCenter() {
        if (!leaveSettings()) {
            navButtons[currentPage]?.isSelected = true
            return
        }
        destination = OmniCodeToolDestination.TASKS
        taskCenterPanel.refresh()
        rootLayout.show(rootCards, TASKS_CARD)
        tasksNavButton.isSelected = true
        rootCards.revalidate()
        rootCards.repaint()
    }

    internal fun openDiagnostics() {
        if (!leaveSettings()) {
            navButtons[currentPage]?.isSelected = true
            return
        }
        destination = OmniCodeToolDestination.DIAGNOSTICS
        diagnosticsPanel.refresh()
        rootLayout.show(rootCards, DIAGNOSTICS_CARD)
        diagnosticsNavButton.isSelected = true
        rootCards.revalidate()
        rootCards.repaint()
    }

    internal fun openChangeReview() {
        if (!leaveSettings()) {
            navButtons[currentPage]?.isSelected = true
            return
        }
        destination = OmniCodeToolDestination.REVIEW
        changeReviewPanel.refresh(chatPanel.latestReviewWorkflowId())
        rootLayout.show(rootCards, REVIEW_CARD)
        reviewNavButton.isSelected = true
        rootCards.revalidate()
        rootCards.repaint()
    }

    internal fun openProjectContext() {
        if (!leaveSettings()) {
            navButtons[currentPage]?.isSelected = true
            return
        }
        destination = OmniCodeToolDestination.CONTEXT
        projectContextPanel.refresh()
        rootLayout.show(rootCards, CONTEXT_CARD)
        contextNavButton.isSelected = true
        rootCards.revalidate()
        rootCards.repaint()
    }

    internal fun startNewChat() {
        if (returnToChat()) chatPanel.startNewChat()
    }

    internal fun showHistory() {
        if (returnToChat()) chatPanel.showHistory()
    }

    internal fun generateCommitMessage() {
        if (returnToChat()) chatPanel.generateCommitMessage()
    }

    internal fun exportResearchPackage() {
        if (returnToChat()) chatPanel.exportResearchPackage()
    }

    internal fun showProviderSelector() {
        if (returnToChat()) chatPanel.showProviderSelector()
    }

    internal fun prefillChat(value: String) {
        if (returnToChat()) chatPanel.insertPromptContext(value)
    }

    internal fun canStartNewChat(): Boolean = chatPanel.canStartNewChat()

    internal fun canGenerateCommitMessage(): Boolean = chatPanel.canGenerateCommitMessage()

    internal fun canExportResearchPackage(): Boolean = chatPanel.canExportResearchPackage()

    internal fun openWorkshop() {
        if (!leaveSettings()) {
            navButtons[currentPage]?.isSelected = true
            return
        }
        destination = OmniCodeToolDestination.WORKSHOP
        workshopPanel.refreshFromSettings()
        rootLayout.show(rootCards, WORKSHOP_CARD)
        workshopNavButton.isSelected = true
        rootCards.revalidate()
        rootCards.repaint()
    }

    override fun dispose() {
        disposed = true
        disposeSettingsPages()
    }

    private fun buildSettingsScreen(): JComponent = settingsScreen.apply {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        add(buildSettingsHeader(), BorderLayout.NORTH)
        add(settingsPageHost.apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 8, 4, 8)
        }, BorderLayout.CENTER)
        add(buildSettingsFooter(), BorderLayout.SOUTH)
    }

    private fun buildSettingsHeader(): JComponent = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(9, 10, 8, 10)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            minimumSize = Dimension(0, 0)
            add(settingsTitle.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(settingsDescription.apply { alignmentX = LEFT_ALIGNMENT })
        }, BorderLayout.CENTER)
    }

    private fun buildNavigationSidebar(): JComponent {
        settingsSidebar.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = OmniCodeUiPalette.surface
        border = JBUI.Borders.customLine(OmniCodeUiPalette.border, 0, 0, 0, 1)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        val group = ButtonGroup()
        group.add(chatNavButton)
        add(chatNavButton)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        group.add(tasksNavButton)
        add(tasksNavButton)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        group.add(planNavButton)
        add(planNavButton)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        group.add(reviewNavButton)
        add(reviewNavButton)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        group.add(contextNavButton)
        add(contextNavButton)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        group.add(diagnosticsNavButton)
        add(diagnosticsNavButton)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        group.add(workshopNavButton)
        add(workshopNavButton)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(sidebarDivider.apply {
            isOpaque = true
            background = OmniCodeUiPalette.border
            preferredSize = Dimension(0, 1)
            minimumSize = Dimension(0, 1)
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })
        add(Box.createVerticalStrut(JBUI.scale(8)))
        OmniCodeSettingsPage.entries.forEach { page ->
            val button = requireNotNull(navButtons[page])
            group.add(button)
            add(button)
            add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        add(Box.createVerticalGlue())
        }
        return settingsSidebarScroll.apply {
            border = JBUI.Borders.empty()
            isOpaque = true
            background = OmniCodeUiPalette.surface
            viewport.isOpaque = true
            viewport.background = OmniCodeUiPalette.surface
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(18)
        }
    }

    private fun buildSettingsFooter(): JComponent = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = true
        background = OmniCodeUiPalette.surface
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(OmniCodeUiPalette.border, 1, 0, 0, 0),
            JBUI.Borders.empty(7, 10),
        )
        add(settingsStatus, BorderLayout.CENTER)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(resetButton)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(saveButton)
        }, BorderLayout.EAST)
    }

    private fun createNavButton(page: OmniCodeSettingsPage): SettingsNavButton =
        SettingsNavButton(page.label, page.description).apply {
            addActionListener { openSettings(page) }
        }

    private fun showSettingsPage(page: OmniCodeSettingsPage) {
        ensureSettingsPage(page)
        currentPage = page
        settingsPageLayout.show(settingsPageHost, page.module.name)
        selectSettingsSection(page)
        settingsTitle.text = page.label
        settingsDescription.text = page.description
        navButtons[page]?.isSelected = true
        settingsStatus.text = ""
        updateSettingsActions()
    }

    private fun ensureSettingsPage(page: OmniCodeSettingsPage) {
        val module = page.module
        if (module in pageEntries) return
        val settings: OmniCodeEmbeddedSettings = when (module) {
            EmbeddedSettingsModule.PROVIDER -> ProviderEmbeddedSettings()
            EmbeddedSettingsModule.PLATFORM -> PlatformEmbeddedSettings(project)
            EmbeddedSettingsModule.INSIGHTS -> InsightsEmbeddedSettings()
        }
        val component = settings.component
        val surface = SettingsViewportPanel(BorderLayout()).apply {
            isOpaque = false
            add(component, BorderLayout.CENTER)
        }
        val scroll = JBScrollPane(surface).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(18)
        }
        pageEntries[module] = EmbeddedSettingsPage(settings, scroll)
        settingsPageHost.add(scroll, module.name)
    }

    private fun selectSettingsSection(page: OmniCodeSettingsPage) {
        val entry = pageEntries[page.module] ?: return
        entry.settings.selectSection(page.tabIndex)
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            entry.scroll.viewport.viewPosition = Point(0, 0)
        }
    }

    private fun applySettings(): Boolean {
        for ((module, entry) in pageEntries) {
            if (!entry.settings.isModified) continue
            try {
                entry.settings.save()
            } catch (error: OmniCodeSettingsSaveException) {
                showSettingsPage(errorPageFor(module, error.sectionIndex))
                showSettingsStatus(error.localizedMessage ?: "配置校验失败。", isError = true)
                return false
            } catch (error: RuntimeException) {
                showSettingsPage(errorPageFor(module))
                showSettingsStatus(error.message ?: "保存配置失败。", isError = true)
                return false
            }
        }
        chatPanel.refreshAfterSettings()
        showSettingsStatus("配置已保存。")
        updateSettingsActions()
        return true
    }

    private fun errorPageFor(
        module: EmbeddedSettingsModule,
        sectionIndex: Int? = null,
    ): OmniCodeSettingsPage = sectionIndex
        ?.let { index -> OmniCodeSettingsPage.entries.firstOrNull { it.module == module && it.tabIndex == index } }
        ?: currentPage.takeIf { it.module == module }
        ?: OmniCodeSettingsPage.entries.first { it.module == module }

    private fun resetSettings() {
        pageEntries.values.forEach { it.settings.reset() }
        showSettingsStatus("已恢复到上次保存的配置。")
        updateSettingsActions()
    }

    private fun returnToChat(): Boolean {
        if (destination == OmniCodeToolDestination.CHAT) return true
        if (!leaveSettings()) {
            navButtons[currentPage]?.isSelected = true
            return false
        }
        destination = OmniCodeToolDestination.CHAT
        rootLayout.show(rootCards, CHAT_CARD)
        chatNavButton.isSelected = true
        chatPanel.refreshAfterSettings()
        chatPanel.focusComposer()
        return true
    }

    private fun leaveSettings(): Boolean {
        if (destination != OmniCodeToolDestination.SETTINGS) return true
        if (hasModifiedSettings()) {
            when (Messages.showYesNoCancelDialog(
                project,
                "配置尚未保存。是否保存后离开设置？",
                "未保存的 OmniCode 配置",
                "保存",
                "不保存",
                "取消",
                null,
            )) {
                Messages.YES -> if (!applySettings()) return false
                Messages.NO -> Unit
                else -> return false
            }
        }
        disposeSettingsPages()
        return true
    }

    private fun hasModifiedSettings(): Boolean = pageEntries.values.any { entry ->
        runCatching { entry.settings.isModified }.getOrDefault(false)
    }

    private fun updateSettingsActions() {
        if (destination != OmniCodeToolDestination.SETTINGS) return
        val canSave = chatPanel.canStartNewChat()
        saveButton.isEnabled = true
        resetButton.isEnabled = true
        if (!canSave) {
            settingsStatus.text = "任务运行中 · 保存后将在下次任务生效"
            settingsStatus.foreground = OmniCodeUiPalette.secondary
        } else if (settingsStatus.text.startsWith("任务运行中")) {
            settingsStatus.text = ""
            settingsStatus.foreground = OmniCodeUiPalette.secondary
        }
    }

    private fun showSettingsStatus(message: String, isError: Boolean = false) {
        settingsStatus.text = message
        settingsStatus.foreground = if (isError) OmniCodeUiPalette.error else OmniCodeUiPalette.secondary
        settingsStatus.toolTipText = message
    }

    private fun updateSidebarLayout() {
        val full = settingsSidebarMode(width) == SettingsSidebarMode.FULL
        val sidebarWidth = JBUI.scale(if (full) 176 else 52)
        settingsSidebar.preferredSize = Dimension(sidebarWidth, 0)
        settingsSidebar.minimumSize = Dimension(sidebarWidth, 0)
        settingsSidebarScroll.preferredSize = Dimension(sidebarWidth, 0)
        settingsSidebarScroll.minimumSize = Dimension(sidebarWidth, 0)
        chatNavButton.text = if (full) "⌂  聊天" else "⌂"
        chatNavButton.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
        chatNavButton.toolTipText = if (full) "返回 Agent 工作台" else "聊天 · 返回 Agent 工作台"
        chatNavButton.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        tasksNavButton.text = if (full) "◷  任务中心" else "◷"
        tasksNavButton.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
        tasksNavButton.toolTipText = if (full) "统一查看运行、待恢复、失败和已完成任务" else "任务中心"
        tasksNavButton.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        planNavButton.text = if (full) "☑  计划看板" else "☑"
        planNavButton.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
        planNavButton.toolTipText = if (full) "编辑、批准、跳过、暂停或重试计划步骤" else "计划看板"
        planNavButton.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        reviewNavButton.text = if (full) "◫  变更审阅" else "◫"
        reviewNavButton.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
        reviewNavButton.toolTipText = if (full) "逐文件和逐块保留、回退已记录的 Agent 直接修改" else "变更审阅"
        reviewNavButton.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        contextNavButton.text = if (full) "⌘  项目上下文" else "⌘"
        contextNavButton.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
        contextNavButton.toolTipText = if (full) "项目规则、PSI/符号索引、固定与排除文件" else "项目上下文"
        contextNavButton.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        diagnosticsNavButton.text = if (full) "◉  连接诊断" else "◉"
        diagnosticsNavButton.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
        diagnosticsNavButton.toolTipText = if (full) "检查 API、网络、模型、MCP OAuth 与沙箱" else "连接诊断"
        diagnosticsNavButton.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        workshopNavButton.text = if (full) "✦  创意工坊" else "✦"
        workshopNavButton.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
        workshopNavButton.toolTipText = if (full) "皮肤、桌宠与工作台个性化" else "创意工坊 · 皮肤与桌宠"
        workshopNavButton.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        navButtons.forEach { (page, button) ->
            button.text = if (full) "${page.glyph}  ${page.label}" else page.glyph
            button.horizontalAlignment = if (full) JToggleButton.LEFT else JToggleButton.CENTER
            button.toolTipText = if (full) page.description else "${page.label} · ${page.description}"
            button.maximumSize = Dimension(sidebarWidth, JBUI.scale(36))
        }
        settingsScreen.revalidate()
        settingsScreen.repaint()
    }

    private fun applyWorkshopSelection(resolved: ResolvedWorkshopSelection) {
        if (disposed) return
        val colors = resolved.toWorkspaceColors()
        background = colors.background
        settingsSidebar.background = colors.surface
        settingsSidebarScroll.background = colors.surface
        settingsSidebarScroll.viewport.background = colors.surface
        sidebarDivider.background = colors.border
        chatNavButton.applyWorkshopColors(colors)
        tasksNavButton.applyWorkshopColors(colors)
        planNavButton.applyWorkshopColors(colors)
        reviewNavButton.applyWorkshopColors(colors)
        contextNavButton.applyWorkshopColors(colors)
        diagnosticsNavButton.applyWorkshopColors(colors)
        workshopNavButton.applyWorkshopColors(colors)
        navButtons.values.forEach { it.applyWorkshopColors(colors) }
        chatPanel.applyWorkshopSelection(resolved)
        settingsSidebar.repaint()
        rootCards.revalidate()
        rootCards.repaint()
    }

    /**
     * Re-resolves semantic JetBrains colours after a LAF change instead of reusing the concrete
     * colours captured by the desktop-pet appearance. The disposable-bound listeners prevent new
     * callbacks after teardown; the guard also makes an already queued EDT refresh harmless.
     */
    private fun refreshWorkshopSelection() {
        val refresh = Runnable {
            if (disposed) return@Runnable
            applyWorkshopSelection(workshopSettings.resolvedSelection())
            if (destination == OmniCodeToolDestination.WORKSHOP) workshopPanel.refreshFromSettings()
        }
        if (SwingUtilities.isEventDispatchThread()) refresh.run() else SwingUtilities.invokeLater(refresh)
    }

    private fun disposeSettingsPages() {
        pageEntries.values.forEach { it.settings.dispose() }
        pageEntries.clear()
        settingsPageHost.removeAll()
    }

    private data class EmbeddedSettingsPage(
        val settings: OmniCodeEmbeddedSettings,
        val scroll: JBScrollPane,
    )

    private companion object {
        const val CHAT_CARD = "chat"
        const val PLAN_CARD = "plan"
        const val TASKS_CARD = "tasks"
        const val DIAGNOSTICS_CARD = "diagnostics"
        const val REVIEW_CARD = "review"
        const val CONTEXT_CARD = "context"
        const val WORKSHOP_CARD = "workshop"
        const val SETTINGS_CARD = "settings"
    }
}

private class SettingsViewportPanel(layout: LayoutManager) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(18)

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = (if (orientation == javax.swing.SwingConstants.VERTICAL) visibleRect.height else visibleRect.width)
        .coerceAtLeast(JBUI.scale(18))

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

private class SettingsNavButton(label: String, description: String) : JToggleButton(label) {
    private var selectedFill = OmniCodeUiPalette.controlSelected
    private var hoverFill = OmniCodeUiPalette.controlHover
    private var accent = OmniCodeUiPalette.accent

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = true
        isRolloverEnabled = true
        horizontalAlignment = LEFT
        border = JBUI.Borders.empty(7, 10)
        foreground = OmniCodeUiPalette.primary
        toolTipText = description
        accessibleContext?.accessibleName = label
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
    }

    fun applyWorkshopColors(colors: WorkshopUiColors) {
        selectedFill = colors.elevatedSurface
        hoverFill = colors.background
        accent = colors.accent
        foreground = colors.primaryText
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        if (isSelected || model.isRollover) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = if (isSelected) selectedFill else hoverFill
                val arc = JBUI.scale(8)
                g.fillRoundRect(JBUI.scale(4), 1, width - JBUI.scale(8), height - 2, arc, arc)
                if (isSelected) {
                    g.color = accent
                    g.fillRoundRect(JBUI.scale(4), JBUI.scale(7), JBUI.scale(3), height - JBUI.scale(14), 3, 3)
                }
            } finally {
                g.dispose()
            }
        }
        super.paintComponent(graphics)
    }
}
