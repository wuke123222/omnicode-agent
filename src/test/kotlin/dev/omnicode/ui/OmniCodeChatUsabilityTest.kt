package dev.omnicode.ui

import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.UserAttachment
import dev.omnicode.service.ProviderModelCatalog
import dev.omnicode.settings.SandboxMode
import dev.omnicode.ui.workshop.DesktopPetState
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmniCodeChatUsabilityTest {
    @Test
    fun `failed cancelled and budget limited runs offer draft recovery`() {
        assertFalse(shouldOfferSubmissionRecovery(AgentRunStatus.COMPLETED))
        assertTrue(shouldOfferSubmissionRecovery(AgentRunStatus.FAILED))
        assertTrue(shouldOfferSubmissionRecovery(AgentRunStatus.CANCELLED))
        assertTrue(shouldOfferSubmissionRecovery(AgentRunStatus.BUDGET_EXHAUSTED))
    }

    @Test
    fun `recovering a failed submission merges instead of overwriting a new draft`() {
        assertEquals("old task", mergeRecoveredPrompt("", "old task"))
        assertEquals("new draft", mergeRecoveredPrompt("new draft", ""))
        assertEquals("same", mergeRecoveredPrompt("same", "same"))
        val merged = mergeRecoveredPrompt("new draft", "old task")
        assertTrue(merged.startsWith("new draft"))
        assertTrue(merged.contains("old task"))
        assertTrue(merged.contains("已合并"))
    }

    @Test
    fun `recovered attachments never exceed the composer limit`() {
        assertTrue(canMergeRecoveredAttachments(currentAttachmentCount = 3, recoveredAttachmentCount = 1))
        assertFalse(canMergeRecoveredAttachments(currentAttachmentCount = 4, recoveredAttachmentCount = 1))
        assertFalse(canMergeRecoveredAttachments(currentAttachmentCount = 3, recoveredAttachmentCount = 2))
    }

    @Test
    fun `workflow recovery only selects images explicitly added after its notice`() {
        val selection = WorkflowRecoveryImageSelection()
        val decodedAfterNoticeButChosenBeforeIt = testAttachment("too-early.png", AttachmentKind.IMAGE)
        val intakeBeforeNotice = selection.captureTarget()
        selection.begin("workflow-1", acceptsImages = true)

        val draftImage = testAttachment("draft.png", AttachmentKind.IMAGE)
        val recoveredFirst = testAttachment("original-1.png", AttachmentKind.IMAGE)
        val text = testAttachment("notes.md", AttachmentKind.MARKDOWN)
        val recoveredSecond = testAttachment("original-2.png", AttachmentKind.IMAGE)
        val intakeAfterNotice = selection.captureTarget()
        selection.record(decodedAfterNoticeButChosenBeforeIt, intakeBeforeNotice)
        selection.record(recoveredFirst, intakeAfterNotice)
        selection.record(text, intakeAfterNotice)
        selection.record(recoveredSecond, intakeAfterNotice)
        val attachments = listOf(
            draftImage,
            decodedAfterNoticeButChosenBeforeIt,
            recoveredFirst,
            text,
            recoveredSecond,
        )

        assertEquals(
            listOf(recoveredFirst, recoveredSecond),
            selection.selectedImages(attachments, "workflow-1"),
        )
    }

    @Test
    fun `workflow recovery identity selection never consumes an equal draft image`() {
        val selection = WorkflowRecoveryImageSelection()
        selection.begin("workflow-1", acceptsImages = true)
        val draftImage = testAttachment("same.png", AttachmentKind.IMAGE)
        val selectedImage = draftImage.copy()
        assertEquals(draftImage, selectedImage)
        assertFalse(draftImage === selectedImage)

        selection.record(selectedImage, selection.captureTarget())
        val attachments = mutableListOf(draftImage, selectedImage)
        val consumed = selection.selectedImages(attachments, "workflow-1")
        removeAttachmentsByIdentity(attachments, consumed)

        assertEquals(1, attachments.size)
        assertTrue(attachments.single() === draftImage)
    }

    @Test
    fun `workflow recovery confirmation names selected images and preserves other drafts`() {
        val confirmation = recoveryImageConfirmationText(
            listOf(testAttachment("figure.png", AttachmentKind.IMAGE)),
        )

        assertTrue(confirmation.contains("figure.png"))
        assertTrue(confirmation.contains("其他草稿文件不会发送，也不会被删除"))
    }

    @Test
    fun `recoverable tool error returns pet to thinking on the next agent turn`() {
        val toolFailure = AgentEvent.ToolCompleted(
            name = "run_command",
            result = "exit 1",
            isError = true,
        )
        val nextTurn = AgentEvent.Status("Thinking · turn 2/24")

        assertEquals(DesktopPetState.ERROR, desktopPetStateForAgentEvent(toolFailure))
        assertEquals(DesktopPetState.THINKING, desktopPetStateForAgentEvent(nextTurn))
        assertNull(desktopPetStateForAgentEvent(AgentEvent.ModeSelected(AgentMode.AGENT)))
    }

    @Test
    fun `setup empty and transcript are mutually exclusive body states`() {
        assertEquals(ChatBodyState.SETUP, chatBodyState(hasTranscript = false, providerConfigured = false))
        assertEquals(ChatBodyState.EMPTY, chatBodyState(hasTranscript = false, providerConfigured = true))
        assertEquals(ChatBodyState.TRANSCRIPT, chatBodyState(hasTranscript = true, providerConfigured = false))
    }

    @Test
    fun `settings navigation keeps a visible rail in narrow tool windows`() {
        assertEquals(
            listOf(
                OmniCodeToolDestination.CHAT,
                OmniCodeToolDestination.TASKS,
                OmniCodeToolDestination.PLAN,
                OmniCodeToolDestination.REVIEW,
                OmniCodeToolDestination.HARNESS,
                OmniCodeToolDestination.DIAGNOSTICS,
                OmniCodeToolDestination.WORKSHOP,
                OmniCodeToolDestination.SETTINGS,
            ),
            OmniCodeToolDestination.entries,
        )
        assertEquals(SettingsSidebarMode.RAIL, settingsSidebarMode(360))
        assertEquals(SettingsSidebarMode.RAIL, settingsSidebarMode(579))
        assertEquals(SettingsSidebarMode.FULL, settingsSidebarMode(580))
        assertEquals(
            listOf(
                "API 与模型",
                "常规",
                "运行控制",
                "沙箱",
                "MCP 服务",
                "Commit AI",
                "提示词库",
                "Skill 库",
                "使用统计",
                "历史记录",
                "工具审计",
                "价格配置",
            ),
            OmniCodeSettingsPage.entries.map { it.label },
        )
        val expectedRoutes = mapOf(
            OmniCodeSettingsPage.PROVIDERS to (EmbeddedSettingsModule.PROVIDER to -1),
            OmniCodeSettingsPage.GENERAL to (EmbeddedSettingsModule.PLATFORM to 0),
            OmniCodeSettingsPage.RUNTIME to (EmbeddedSettingsModule.PLATFORM to 6),
            OmniCodeSettingsPage.SANDBOX to (EmbeddedSettingsModule.PLATFORM to 1),
            OmniCodeSettingsPage.MCP to (EmbeddedSettingsModule.PLATFORM to 2),
            OmniCodeSettingsPage.COMMIT_AI to (EmbeddedSettingsModule.PLATFORM to 3),
            OmniCodeSettingsPage.PROMPTS to (EmbeddedSettingsModule.PLATFORM to 4),
            OmniCodeSettingsPage.SKILLS to (EmbeddedSettingsModule.PLATFORM to 5),
            OmniCodeSettingsPage.USAGE to (EmbeddedSettingsModule.INSIGHTS to 0),
            OmniCodeSettingsPage.HISTORY to (EmbeddedSettingsModule.INSIGHTS to 1),
            OmniCodeSettingsPage.AUDIT to (EmbeddedSettingsModule.INSIGHTS to 2),
            OmniCodeSettingsPage.PRICING to (EmbeddedSettingsModule.INSIGHTS to 3),
        )
        expectedRoutes.forEach { (page, route) ->
            assertEquals(route.first, page.module, page.label)
            assertEquals(route.second, page.tabIndex, page.label)
        }
        assertEquals((0..6).toList(), OmniCodeSettingsPage.entries
            .filter { it.module == EmbeddedSettingsModule.PLATFORM }
            .map { it.tabIndex }
            .sorted())
        assertEquals((0..3).toList(), OmniCodeSettingsPage.entries
            .filter { it.module == EmbeddedSettingsModule.INSIGHTS }
            .map { it.tabIndex })
    }

    @Test
    fun `send only activates for a configured idle provider with a real prompt`() {
        assertFalse(composerSendEnabled(false, false, true, "  "))
        assertFalse(composerSendEnabled(false, false, false, "修复问题"))
        assertFalse(composerSendEnabled(true, false, true, "修复问题"))
        assertFalse(composerSendEnabled(false, false, true, "修复问题", pendingAttachmentLoads = 1))
        assertFalse(composerSendEnabled(false, false, true, "/plan   "))
        assertTrue(composerSendEnabled(false, false, false, "/status"))
        assertTrue(composerSendEnabled(false, false, false, "/model"))
        assertTrue(composerSendEnabled(false, false, false, "/mcp"))
        assertFalse(composerSendEnabled(false, false, false, "/review"))
        assertTrue(composerSendEnabled(false, false, true, "修复问题"))
        assertTrue(composerSendEnabled(false, false, true, "/plan 修复问题"))
        assertTrue(composerSendEnabled(false, false, true, "/plan", attachmentCount = 1))
        assertTrue(composerSendEnabled(false, false, true, "", attachmentCount = 1))
    }

    @Test
    fun `agent planning and research modes have concise accessible presentations`() {
        val agent = composerModePresentation(AgentMode.AGENT)
        val plan = composerModePresentation(AgentMode.PLAN)
        val claudePlan = composerModePresentation(AgentMode.CLAUDE_PLAN)
        val research = composerModePresentation(AgentMode.RESEARCH)

        assertEquals("Agent", agent.label)
        assertEquals("Plan 看板", plan.label)
        assertEquals("Claude Plan", claudePlan.label)
        assertEquals("Research", research.label)
        assertTrue(plan.menuSummary.contains("规划"))
        assertTrue(plan.description.contains("只读"))
        assertTrue(plan.runningStatus.contains("制定计划"))
        assertTrue(claudePlan.description.contains("Claude Code"))
        assertTrue(claudePlan.description.contains("只读探索命令"))
        assertTrue(research.menuSummary.contains("科研"))
        assertTrue(research.description.contains("实验"))
        assertTrue(research.description.contains("不自动修改"))
        assertTrue(research.runningStatus.contains("证据"))
    }

    @Test
    fun `provider and thinking statuses render as compact timeline stages`() {
        assertEquals("thinking", stagePresentation("Thinking · turn 1")?.key)
        assertEquals("provider-request", stagePresentation("模型请求 #1…")?.key)
        assertEquals("stage:context", stagePresentation("阶段：context…")?.key)
    }

    @Test
    fun `research starter cards switch the actual execution mode instead of only changing prompt text`() {
        val suggestions = defaultComposerSuggestions()
        val research = suggestions.filter { it.label in setOf("设计可复现实验", "分析论文与资料") }

        assertEquals(2, research.size)
        assertTrue(research.all { it.targetMode == AgentMode.RESEARCH })
        assertTrue(research.none { it.prompt.contains("切换到 Research") })
        assertTrue(suggestions.filterNot { it in research }.all { it.targetMode == null })
    }

    @Test
    fun `mode switching preserves the draft and submission locks its mode`() {
        val draft = "先分析认证模块，再列出最小改造步骤"
        var state = ComposerModeState().select(AgentMode.PLAN)
        val submission = state.snapshot(draft)

        state = state.select(AgentMode.AGENT)

        assertEquals(draft, submission.prompt)
        assertEquals(AgentMode.PLAN, submission.mode)
        assertEquals(AgentMode.AGENT, state.selectedMode)
        assertEquals(AgentMode.PLAN, nextComposerMode(AgentMode.AGENT))
        assertEquals(AgentMode.CLAUDE_PLAN, nextComposerMode(AgentMode.PLAN))
        assertEquals(AgentMode.RESEARCH, nextComposerMode(AgentMode.CLAUDE_PLAN))
        assertEquals(AgentMode.AGENT, nextComposerMode(AgentMode.RESEARCH))
    }

    @Test
    fun `plan slash command is a one turn override and strips only the exact command`() {
        val state = ComposerModeState(AgentMode.RESEARCH)
        val resolved = composerPromptResolution("  /plan\n  先检索认证入口，再给实施计划  ")
        val submission = state.snapshot(resolved)

        assertEquals("先检索认证入口，再给实施计划", submission.prompt)
        assertEquals(AgentMode.CLAUDE_PLAN, submission.mode)
        assertEquals(AgentMode.RESEARCH, state.selectedMode, "slash command must not mutate the persistent selection")
        assertEquals(null, composerPromptResolution("/planner 不是命令").modeOverride)
        assertEquals("/planner 不是命令", composerPromptResolution("/planner 不是命令").prompt)
        assertEquals(AgentMode.CLAUDE_PLAN, composerPromptResolution("/plan").modeOverride)
        assertEquals("", composerPromptResolution("/plan").prompt)
    }

    @Test
    fun `codex style slash commands stay local except review`() {
        assertEquals(ComposerCommand.STATUS, composerPromptResolution("/status").command)
        assertEquals(ComposerCommand.MODEL, composerPromptResolution("/model").command)
        assertEquals(ComposerCommand.PERMISSIONS, composerPromptResolution("/permissions").command)
        assertEquals(ComposerCommand.MCP, composerPromptResolution("/mcp").command)
        assertEquals(ComposerCommand.TASKS, composerPromptResolution("/tasks").command)
        assertEquals(ComposerCommand.NEW, composerPromptResolution("/new").command)
        assertEquals(ComposerCommand.HELP, composerPromptResolution("/help").command)

        val review = composerPromptResolution("/review")
        assertEquals(ComposerCommand.REVIEW, review.command)
        assertTrue(review.command?.requiresModel == true)
        assertEquals(AgentMode.RESEARCH, review.modeOverride)
        assertTrue(review.prompt.contains("审阅当前 Git 工作区差异"))
        assertEquals("/reviewer 不是命令", composerPromptResolution("/reviewer 不是命令").prompt)
        assertEquals(null, composerPromptResolution("/reviewer 不是命令").command)
    }

    @Test
    fun `shift tab toggles agent and claude plan while ordinary tab remains untouched`() {
        assertEquals(AgentMode.CLAUDE_PLAN, nextClaudePlanShortcutMode(AgentMode.AGENT))
        assertEquals(AgentMode.AGENT, nextClaudePlanShortcutMode(AgentMode.CLAUDE_PLAN))
        assertEquals(AgentMode.CLAUDE_PLAN, nextClaudePlanShortcutMode(AgentMode.PLAN))
        assertEquals(AgentMode.CLAUDE_PLAN, nextClaudePlanShortcutMode(AgentMode.RESEARCH))

        SwingUtilities.invokeAndWait {
            val input = PromptTextArea("输入")
            val ordinaryTab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)
            val shiftTab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)
            val ordinaryTabMapping = input.getInputMap(JComponent.WHEN_FOCUSED).get(ordinaryTab)
            var toggleCount = 0

            installClaudePlanShortcut(input) { toggleCount++ }

            assertEquals(ordinaryTabMapping, input.getInputMap(JComponent.WHEN_FOCUSED).get(ordinaryTab))
            val actionKey = input.getInputMap(JComponent.WHEN_FOCUSED).get(shiftTab)
            assertEquals("omnicode.toggleClaudePlanMode", actionKey)
            input.actionMap.get(actionKey).actionPerformed(null)
            assertEquals(1, toggleCount)
        }
    }

    @Test
    fun `team strategy is independent from execution mode and locks into a submission`() {
        val state = ComposerModeState()
            .select(AgentMode.PLAN)
            .selectExecutionStrategy(AgentExecutionStrategy.TEAM)
        val submission = state.snapshot("并行调查并给出计划")

        assertEquals(AgentMode.PLAN, submission.mode)
        assertEquals(AgentExecutionStrategy.TEAM, submission.strategy)
        assertEquals("Team · 开", teamButtonText(submission.strategy, ComposerLayoutMode.REGULAR))
        assertEquals("T · 开", teamButtonText(submission.strategy, ComposerLayoutMode.NARROW))
        assertEquals("T", teamButtonText(AgentExecutionStrategy.SINGLE, ComposerLayoutMode.NARROW))
    }

    @Test
    fun `restored conversation mode updates the selector without preventing later switches`() {
        val restored = synchronizeComposerModeState(ComposerModeState(AgentMode.AGENT), AgentMode.PLAN)

        assertEquals(AgentMode.PLAN, restored.selectedMode)
        assertEquals(AgentMode.AGENT, restored.select(AgentMode.AGENT).selectedMode)
    }

    @Test
    fun `restored turns use a neutral label because a conversation may contain mixed modes`() {
        assertEquals("历史", assistantTurnModeLabel(null))
        assertEquals("Agent", assistantTurnModeLabel(AgentMode.AGENT))
        assertEquals("Plan 看板", assistantTurnModeLabel(AgentMode.PLAN))
        assertEquals("Claude Plan", assistantTurnModeLabel(AgentMode.CLAUDE_PLAN))
        assertEquals("Research", assistantTurnModeLabel(AgentMode.RESEARCH))
    }

    @Test
    fun `plan mode presents the sandbox as read only even when full access is configured`() {
        val plan = sandboxButtonPresentation(AgentMode.PLAN, SandboxMode.DANGER_FULL_ACCESS, width = 300)
        val claudePlan = sandboxButtonPresentation(AgentMode.CLAUDE_PLAN, SandboxMode.DANGER_FULL_ACCESS, width = 300)
        val agent = sandboxButtonPresentation(AgentMode.AGENT, SandboxMode.DANGER_FULL_ACCESS, width = 300)

        assertEquals("只读", plan.text)
        assertFalse(plan.dangerous)
        assertTrue(plan.tooltip.contains("不会执行命令"))
        assertTrue(plan.tooltip.contains("Agent / Research"))
        assertEquals("只读", claudePlan.text)
        assertFalse(claudePlan.dangerous)
        assertTrue(claudePlan.tooltip.contains("只读探索命令"))
        assertTrue(claudePlan.tooltip.contains("文件修改"))
        assertEquals("完全访问", agent.text)
        assertTrue(agent.dangerous)
    }

    @Test
    fun `research mode exposes command sandbox without enabling file edits`() {
        val workspace = sandboxButtonPresentation(AgentMode.RESEARCH, SandboxMode.WORKSPACE_WRITE, width = 520)
        val danger = sandboxButtonPresentation(AgentMode.RESEARCH, SandboxMode.DANGER_FULL_ACCESS, width = 300)

        assertEquals("workspace-write", workspace.text)
        assertFalse(workspace.dangerous)
        assertTrue(workspace.tooltip.contains("实验命令沙箱"))
        assertTrue(workspace.tooltip.contains("不会开放文件修改工具"))
        assertEquals("完全访问", danger.text)
        assertTrue(danger.dangerous)
    }

    @Test
    fun `enter sends while idle and keeps drafting safe while busy`() {
        assertEquals(
            ComposerEnterAction.SEND,
            composerEnterAction(isBusy = false, explicitSend = false, shiftDown = false, promptPopupVisible = false),
        )
        assertEquals(
            ComposerEnterAction.INSERT_NEWLINE,
            composerEnterAction(isBusy = true, explicitSend = false, shiftDown = false, promptPopupVisible = false),
        )
        assertEquals(
            ComposerEnterAction.SHOW_BUSY,
            composerEnterAction(isBusy = true, explicitSend = true, shiftDown = false, promptPopupVisible = false),
        )
    }

    @Test
    fun `shift enter inserts a line and prompt popup owns enter`() {
        assertEquals(
            ComposerEnterAction.INSERT_NEWLINE,
            composerEnterAction(isBusy = false, explicitSend = false, shiftDown = true, promptPopupVisible = false),
        )
        assertEquals(
            ComposerEnterAction.IGNORE,
            composerEnterAction(isBusy = false, explicitSend = false, shiftDown = false, promptPopupVisible = true),
        )
    }

    @Test
    fun `keyboard popup selection resolves the highlighted attachment or prompt`() {
        val popup = JPopupMenu().apply {
            add(JMenuItem("first"))
            add(JMenuItem("second"))
            selectionModel.selectedIndex = 1
        }

        assertEquals("second", selectedComposerPopupItem(popup)?.text)
        popup.selectionModel.selectedIndex = -1
        assertNull(selectedComposerPopupItem(popup))

        assertEquals(0, nextPopupSelectionIndex(-1, 3, 1))
        assertEquals(2, nextPopupSelectionIndex(-1, 3, -1))
        assertEquals(0, nextPopupSelectionIndex(2, 3, 1))
        assertEquals(2, nextPopupSelectionIndex(0, 3, -1))
        assertEquals(-1, nextPopupSelectionIndex(0, 0, 1))
    }

    @Test
    fun `footer labels become compact in a narrow tool window`() {
        assertEquals(FooterTextLimits(provider = 8, model = 11), footerTextLimits(300))
        assertEquals(FooterTextLimits(provider = 12, model = 16), footerTextLimits(400))
        assertEquals(FooterTextLimits(provider = 20, model = 24), footerTextLimits(520))
    }

    @Test
    fun `composer uses structural responsive modes instead of only truncating text`() {
        assertEquals(ComposerLayoutMode.NARROW, composerLayoutMode(280))
        assertEquals(ComposerLayoutMode.COMPACT, composerLayoutMode(400))
        assertEquals(ComposerLayoutMode.REGULAR, composerLayoutMode(520))
        assertEquals("Plan", composerModeButtonText(AgentMode.PLAN, ComposerLayoutMode.NARROW))
        assertEquals("Research", composerModeButtonText(AgentMode.RESEARCH, ComposerLayoutMode.NARROW))
        assertEquals("Research · 实验", composerModeButtonText(AgentMode.RESEARCH, ComposerLayoutMode.COMPACT))

        val narrowAgent = composerToolbarVisibility(AgentMode.AGENT, ComposerLayoutMode.NARROW)
        val regularAgent = composerToolbarVisibility(AgentMode.AGENT, ComposerLayoutMode.REGULAR)
        val plan = composerToolbarVisibility(AgentMode.PLAN, ComposerLayoutMode.REGULAR)
        val claudePlanDanger = composerToolbarVisibility(
            AgentMode.CLAUDE_PLAN,
            ComposerLayoutMode.REGULAR,
            SandboxMode.DANGER_FULL_ACCESS,
        )
        val researchDanger = composerToolbarVisibility(
            AgentMode.RESEARCH,
            ComposerLayoutMode.REGULAR,
            SandboxMode.DANGER_FULL_ACCESS,
        )

        assertFalse(narrowAgent.showSandbox)
        assertFalse(narrowAgent.showProvider)
        assertFalse(regularAgent.showSandbox)
        assertTrue(regularAgent.showProvider)
        assertFalse(plan.showSandbox)
        assertFalse(claudePlanDanger.showSandbox)
        assertTrue(researchDanger.showSandbox)

        val narrowDanger = composerToolbarVisibility(
            AgentMode.AGENT,
            ComposerLayoutMode.NARROW,
            SandboxMode.DANGER_FULL_ACCESS,
        )
        assertTrue(narrowDanger.showSandbox)
    }

    @Test
    fun `composer controls remain inside nonoverlapping rows at narrow production widths`() {
        listOf(200 to true, 240 to false, 280 to true, 520 to true).forEach { (width, showSandbox) ->
            val add = composerControlButton("")
            val mode = composerControlButton("Plan · 只读  ▾", state = ComposerControlState.SELECTED)
            val team = composerControlButton("Team")
            val context = composerControlButton("上下文")
            val sandbox = composerControlButton("完全访问").apply { isVisible = showSandbox }
            val sandboxControl = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(Box.createHorizontalStrut(6))
                add(context)
                add(Box.createHorizontalStrut(4))
                add(sandbox)
            }
            val stop = JButton().apply {
                val square = Dimension(32, 32)
                preferredSize = square
                minimumSize = square
                maximumSize = square
                isVisible = false
            }
            val send = JButton().apply {
                val square = Dimension(32, 32)
                preferredSize = square
                minimumSize = square
                maximumSize = square
            }
            val toolbar = createComposerToolbar(add, mode, team, sandboxControl, stop, send)
            toolbar.setSize(width, 200)
            val preferredHeight = toolbar.preferredSize.height
            toolbar.setSize(width, preferredHeight)
            toolbar.doLayout()

            assertEquals(if (width < 340) 2 else 1, composerToolbarRowCount(width))
            assertTrue(preferredHeight >= if (width < 340) 68 else 32)
            assertControlsInsideWithoutOverlap(toolbar, listOf(add, mode, team, sandboxControl, send))
            assertControlsInsideWithoutOverlap(sandboxControl, listOf(context, sandbox))
        }
    }

    @Test
    fun `empty state centers in the viewport without changing normal transcript scrolling`() {
        val conversation = ConversationColumn()
        val emptyState = ViewportCenteredPanel(GridBagLayout()).apply {
            preferredSize = Dimension(200, 240)
        }
        val viewport = JViewport().apply {
            view = conversation
            size = Dimension(300, 500)
        }

        conversation.addBlock(emptyState)
        assertTrue(conversation.scrollableTracksViewportHeight)

        viewport.size = Dimension(300, 120)
        assertFalse(conversation.scrollableTracksViewportHeight)

        conversation.removeBlock(emptyState)
        assertFalse(conversation.scrollableTracksViewportHeight)
    }

    @Test
    fun `footer separator follows the visibility of its leading item`() {
        val provider = JButton("Provider")
        val model = JButton("Model")
        val row = footerRow(provider, model)
        val separator = row.components[1]

        assertTrue(separator.isVisible)
        provider.isVisible = false
        assertFalse(separator.isVisible)
    }

    @Test
    fun `popup size and direction stay inside the usable screen`() {
        assertEquals(Dimension(320, 410), fitPopupSize(Dimension(420, 520), Dimension(320, 410)))
        assertEquals(Dimension(280, 180), fitPopupSize(Dimension(100, 100), Dimension(500, 500)))
        assertEquals(Dimension(276, 560), popupAvailableSize(Dimension(1_700, 1_000), Dimension(300, 800)))
        assertEquals(Dimension(496, 700), popupAvailableSize(Dimension(1_700, 1_000), Dimension(520, 1_000)))
        assertEquals(-526, popupVerticalOffset(contentHeight = 520, spaceAbove = 700, anchorHeight = 24))
        assertEquals(30, popupVerticalOffset(contentHeight = 520, spaceAbove = 300, anchorHeight = 24))
    }

    @Test
    fun `model catalog labels never claim fallback data came from the api`() {
        val failedFallback = ProviderModelCatalog(
            providerId = "openai",
            providerName = "OpenAI",
            models = listOf("configured-model"),
            discoveredRemotely = false,
            status = "Unable to load models",
            error = "Timed out",
        )
        val remote = failedFallback.copy(
            models = listOf("a", "b"),
            discoveredRemotely = true,
            error = null,
        )

        val fallbackLabel = modelCatalogSourceText(failedFallback)
        assertTrue(fallbackLabel.contains("API 加载失败"))
        assertFalse(fallbackLabel.contains("来自供应商 API"))
        assertTrue(modelCatalogSourceText(remote).contains("来自供应商 API"))
        assertEquals("模型列表加载失败", modelCatalogStatusText(failedFallback))
        assertEquals("已加载 2 个可用模型", modelCatalogStatusText(remote))
    }

    @Test
    fun `execution stages collapse noisy status into concise timeline summaries`() {
        val thinking = stagePresentation("Thinking · turn 3/24")

        assertEquals("thinking", thinking?.key)
        assertEquals("思考中", thinking?.runningText)
        assertEquals("思考了", thinking?.completedText)
        assertNull(stagePresentation("运行中"))
        assertNull(stagePresentation("Agent 模式 · 已锁定"))
        assertEquals("provider-retry", stagePresentation("Provider temporarily unavailable; retrying (1/2)")?.key)
        assertEquals(
            "provider-retry",
            stagePresentation("Provider attempt may have consumed quota; retrying with the same idempotency key")?.key,
        )
        assertEquals("preparing", stagePresentation("正在建立安全恢复点…")?.key)
        assertEquals("project-context", stagePresentation("正在准备项目上下文…")?.key)
        assertEquals("mcp-connect", stagePresentation("正在并行连接 MCP 服务…")?.key)
        assertEquals(
            true,
            stagePresentation("Checkpoint save failed; execution state may require review")?.warning,
        )
        assertEquals(true, stagePresentation("Tool audit could not be persisted: disk full")?.warning)
        val mcpWarning = stagePresentation("MCP offline: timed out")
        assertTrue(mcpWarning?.warning == true)
        assertTrue(mcpWarning?.key?.startsWith("mcp-warning:") == true)
        assertTrue(mcpWarning?.completedText?.contains("offline: timed out") == true)
        assertNull(stagePresentation("Project Harness · READY · 100/100"))
    }

    @Test
    fun `run footer translates retries and suppresses internal diagnostics`() {
        assertEquals("模型思考中…", userFacingRunStatus("Thinking · turn 1/24"))
        assertEquals(
            "模型连接不稳定，正在安全重试…",
            userFacingRunStatus("Provider attempt may have consumed quota; retrying with the same idempotency key"),
        )
        assertEquals("部分 MCP 服务不可用，任务继续", userFacingRunStatus("MCP offline: timed out"))
        assertEquals("正在准备任务…", userFacingRunStatus("正在准备项目上下文…"))
        assertEquals("工具审计保存失败，请检查本轮操作记录", userFacingRunStatus("Tool audit could not be persisted: disk full"))
        assertTrue(isCriticalRunWarning("Tool audit could not be persisted: disk full"))
        assertFalse(isCriticalRunWarning("Usage could not be persisted: disk full"))
        assertNull(userFacingRunStatus("推理强度 · high → high"))
        assertNull(userFacingRunStatus("Project Harness · READY · 100/100"))
        assertNull(userFacingRunStatus("Harness · READY · tools 12"))
        assertNull(userFacingRunStatus("internal status that is not allow-listed"))
    }

    @Test
    fun `large assistant output keeps file links without rebuilding rich markdown`() {
        val referenceText = "src/main/App.kt:12-40"
        val value = "x".repeat(81_000) + "\n" + referenceText
        val pane = LightweightMarkdownPane()

        pane.setRawText(value)
        pane.finalizeMarkdown()

        assertEquals(value.length, pane.document.length)
        assertEquals(
            ToolFileReference("src/main/App.kt", 12, 40),
            pane.fileReferenceAt(value.indexOf(referenceText)),
        )
    }

    @Test
    fun `tool cards derive compact titles paths and line ranges from typed arguments`() {
        val read = toolCardPresentation(
            "read_file",
            """{"path":"src/main/App.kt","start_line":12,"end_line":40}""",
        )
        val search = toolCardPresentation(
            "search_text",
            """{"query":"createClient","path":"src/main"}""",
        )
        val command = toolCardPresentation(
            "run_command",
            """{"argv":["git","diff","--stat"]}""",
        )

        assertEquals("读取文件", read.title)
        assertEquals("src/main/App.kt:12-40", read.detail)
        assertEquals(ToolFileReference("src/main/App.kt", 12, 40), read.fileReference)
        assertEquals("文件匹配", search.title)
        assertTrue(search.detail.contains("createClient"))
        assertEquals("运行命令", command.title)
        assertEquals("git diff --stat", command.detail)
    }

    @Test
    fun `execution navigation only appends meaningful nonzero counts`() {
        assertEquals("任务", navigationText("任务", 0))
        assertEquals("编辑  3", navigationText("编辑", 3))
    }

    @Test
    fun `execution navigation activates with pointer enter space and arrow keys`() {
        SwingUtilities.invokeAndWait {
            val navigated = mutableListOf<ExecutionNavigationTarget>()
            val navigation = ExecutionNavigationBar(navigated::add)
            navigation.updateCounts(toolCount = 12, subagentCount = 2, editCount = 3, running = true)
            val buttons = descendants(navigation).filterIsInstance<JToggleButton>().toList()

            assertEquals(3, buttons.size)
            assertEquals("☷  任务", buttons[0].text)
            buttons[1].doClick()
            assertEquals(ExecutionNavigationTarget.SUBAGENTS, navigation.selectedTarget())

            val rightAction = buttons[1].actionMap.get("omnicode.executionNavigation.${KeyEvent.VK_RIGHT}")
            rightAction.actionPerformed(ActionEvent(buttons[1], ActionEvent.ACTION_PERFORMED, "right"))
            assertEquals(ExecutionNavigationTarget.EDITS, navigation.selectedTarget())

            val enterAction = buttons[0].actionMap.get(
                "omnicode.executionNavigation.activate.${KeyEvent.VK_ENTER}",
            )
            enterAction.actionPerformed(ActionEvent(buttons[0], ActionEvent.ACTION_PERFORMED, "enter"))
            assertEquals(ExecutionNavigationTarget.TASKS, navigation.selectedTarget())

            val spaceAction = buttons[1].actionMap.get(
                "omnicode.executionNavigation.activate.${KeyEvent.VK_SPACE}",
            )
            spaceAction.actionPerformed(ActionEvent(buttons[1], ActionEvent.ACTION_PERFORMED, "space"))
            assertEquals(ExecutionNavigationTarget.SUBAGENTS, navigation.selectedTarget())
            assertEquals(
                listOf(
                    ExecutionNavigationTarget.SUBAGENTS,
                    ExecutionNavigationTarget.EDITS,
                    ExecutionNavigationTarget.TASKS,
                    ExecutionNavigationTarget.SUBAGENTS,
                ),
                navigated,
            )
        }
    }

    @Test
    fun `custom timeline components initialize and complete without accessibility context failures`() {
        SwingUtilities.invokeAndWait {
            val navigation = ExecutionNavigationBar()
            navigation.updateCounts(toolCount = 2, subagentCount = 0, editCount = 1, running = true)

            val card = ToolCallCard("read_file", """{"path":"README.md"}""", "call-1")
            card.complete("1\t# Project", isError = false)

            assertEquals(3, navigation.componentCount)
            assertTrue(card.preferredSize.height > 0)
        }
    }

    @Test
    fun `failed turn exposes an explicit edit and resend action`() {
        SwingUtilities.invokeAndWait {
            var recovered = false
            val turn = AssistantTurnPanel(AgentMode.AGENT)
            turn.finish("!  失败", isError = true)
            turn.showRecoveryAction("编辑后重发", "恢复上次任务") { recovered = true }

            val button = descendants(turn)
                .filterIsInstance<JButton>()
                .first { it.text == "编辑后重发" }
            button.doClick()

            assertTrue(recovered)
        }
    }

    @Test
    fun `completed assistant turn exposes a copy action for the response`() {
        SwingUtilities.invokeAndWait {
            val turn = AssistantTurnPanel(AgentMode.AGENT)
            turn.appendText("完成了修复。")
            turn.finish("✓  完成")

            val copy = descendants(turn)
                .filterIsInstance<JButton>()
                .firstOrNull { it.text == "复制" }
            assertTrue(copy?.isVisible == true)
        }
    }

    @Test
    fun `completed assistant turn exposes codex retry edit and task actions when wired`() {
        SwingUtilities.invokeAndWait {
            var retries = 0
            var edits = 0
            var details = 0
            val turn = AssistantTurnPanel(
                mode = AgentMode.AGENT,
                onRetry = { retries++ },
                onEditRetry = { edits++ },
                onOpenTask = { details++ },
            )
            turn.appendText("完成了修复。")
            turn.finish("✓  完成")

            descendants(turn)
                .filterIsInstance<JButton>()
                .first { it.text == "重试" }
                .doClick()
            descendants(turn)
                .filterIsInstance<JButton>()
                .first { it.text == "编辑重试" }
                .doClick()
            descendants(turn)
                .filterIsInstance<JButton>()
                .first { it.text == "任务详情" }
                .doClick()

            assertEquals(1, retries)
            assertEquals(1, edits)
            assertEquals(1, details)
        }
    }

    @Test
    fun `completed action row wraps inside narrow sidebar bounds`() {
        SwingUtilities.invokeAndWait {
            val turn = AssistantTurnPanel(
                mode = AgentMode.AGENT,
                onRetry = {},
                onEditRetry = {},
                onOpenTask = {},
            )
            turn.appendText("完成了修复。")
            turn.finish("✓  完成")
            val actions = descendants(turn)
                .filterIsInstance<WrappingActionPanel>()
                .last()
            actions.setSize(230, actions.preferredSize.height)
            actions.doLayout()
            val visible = actions.components.filterIsInstance<JComponent>().filter { it.isVisible }
            assertTrue(actions.preferredSize.height > 32)
            assertControlsInsideWithoutOverlap(actions, visible)
        }
    }

    @Test
    fun `long continuous runs keep stage summary components bounded`() {
        SwingUtilities.invokeAndWait {
            val turn = AssistantTurnPanel(AgentMode.AGENT)
            repeat(200) { index ->
                turn.updateStatus("Thinking · turn ${index + 1}")
                turn.updateStatus("Provider output segment reached its limit · continuing automatically")
            }

            assertTrue(turn.visibleStageRowCount <= 12)
            turn.finish("✓  完成")
            assertTrue(turn.visibleStageRowCount <= 12)
        }
    }

    @Test
    fun `checkpoint discard confirmation warns about effects and recovery action survives request failure`() {
        val confirmation = checkpointDiscardConfirmationText("repair\nproject", pendingToolDangerous = true)
        assertTrue(confirmation.contains("不会撤销"))
        assertTrue(confirmation.contains("8 秒内可撤销"))
        assertTrue(confirmation.contains("副作用状态未知"))
        assertFalse(confirmation.contains("\nproject"))

        SwingUtilities.invokeAndWait {
            val turn = AssistantTurnPanel(AgentMode.AGENT)
            turn.finish("可恢复的中断任务")
            turn.showRecoveryAction("继续任务", "从检查点恢复") {}
            turn.addRecoveryAction("放弃检查点", "删除本地记录") {}
            val recoveryLabels = setOf("继续任务", "放弃检查点")
            val actions = descendants(turn)
                .filterIsInstance<JButton>()
                .filter { it.text in recoveryLabels }
                .toList()

            turn.setRecoveryActionsEnabled(false)
            assertTrue(actions.all { !it.isEnabled })
            turn.setRecoveryActionsEnabled(true)
            assertTrue(actions.all { it.isEnabled })
        }
    }

    private fun assertControlsInsideAndOrdered(containerWidth: Int, controls: List<JComponent>) {
        val visible = controls.filter { it.isVisible }.sortedBy { it.x }
        visible.forEach { control ->
            assertTrue(control.x >= 0)
            assertTrue(control.x + control.width <= containerWidth)
        }
        visible.zipWithNext().forEach { (left, right) ->
            assertTrue(left.x + left.width <= right.x)
        }
    }

    private fun assertControlsInsideWithoutOverlap(container: JComponent, controls: List<JComponent>) {
        val visible = controls.filter { it.isVisible && it.width > 0 && it.height > 0 }
        visible.forEach { control ->
            assertTrue(control.x >= 0)
            assertTrue(control.y >= 0)
            assertTrue(control.x + control.width <= container.width)
            assertTrue(control.y + control.height <= container.height)
        }
        visible.forEachIndexed { index, left ->
            visible.drop(index + 1).forEach { right ->
                assertFalse(Rectangle(left.bounds).intersects(Rectangle(right.bounds)))
            }
        }
    }

    private fun descendants(component: java.awt.Container): Sequence<java.awt.Component> = sequence {
        component.components.forEach { child ->
            yield(child)
            if (child is java.awt.Container) yieldAll(descendants(child))
        }
    }
}

private fun testAttachment(fileName: String, kind: AttachmentKind): UserAttachment = UserAttachment(
    fileName = fileName,
    kind = kind,
    mediaType = if (kind == AttachmentKind.IMAGE) "image/png" else "text/markdown",
    byteSize = 4,
    content = "data",
)
