package dev.omnicode.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTabbedPane
import dev.omnicode.mcp.ApprovedMcpHttpClientConnector
import dev.omnicode.mcp.McpClient
import dev.omnicode.mcp.McpCatalogEntry
import dev.omnicode.mcp.McpCatalogInstallOption
import dev.omnicode.mcp.McpMarketplaceCatalog
import dev.omnicode.mcp.McpRegistryCatalogClient
import dev.omnicode.mcp.McpStdioClient
import dev.omnicode.mcp.oauth.McpOAuthLoginApproval
import dev.omnicode.mcp.oauth.McpOAuthSessionManager
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ReasoningEffort
import dev.omnicode.provider.reasoningEffortOptions
import dev.omnicode.provider.recommendedOutputTokenFloor
import dev.omnicode.tool.SandboxedMcpProcessLauncher
import dev.omnicode.ui.ModalApprovalGate
import dev.omnicode.ui.McpMarketplaceDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import dev.omnicode.tool.ProcessSandbox
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.swing.ButtonGroup
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JFileChooser
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileFilter

class OmniCodePlatformConfigurable : SearchableConfigurable, Configurable.NoScroll {
    private var editor: PlatformSettingsEditor? = null

    override fun getId(): String = "dev.omnicode.platform.settings"

    override fun getDisplayName(): String = "OmniCode 平台"

    override fun createComponent(): JComponent {
        val current = editor ?: PlatformSettingsEditor().also { editor = it }
        current.reset(OmniCodePlatformSettingsService.getInstance().snapshot())
        return current.component
    }

    override fun getPreferredFocusedComponent(): JComponent? = editor?.preferredFocus

    override fun isModified(): Boolean = editor?.isModified() == true

    @Throws(ConfigurationException::class)
    override fun apply() {
        val current = editor ?: return
        val form = current.form()
        validatePlatformForm(form)
        val service = OmniCodePlatformSettingsService.getInstance()
        val previous = service.snapshot()
        service.update { state -> form.writeTo(state) }
        val saved = service.snapshot()
        clearRemovedMcpCredentials(previous.mcpServers, saved.mcpServers)
        current.reset(saved)
    }

    override fun reset() {
        editor?.reset(OmniCodePlatformSettingsService.getInstance().snapshot())
    }

    override fun disposeUIResources() {
        editor?.dispose()
        editor = null
    }

    fun selectSection(index: Int) {
        editor?.selectSection(index)
    }
}

internal class PlatformEmbeddedSettings(project: Project) : OmniCodeEmbeddedSettings {
    private val editor = PlatformSettingsEditor(showTabs = false, project = project)

    override val component: JComponent get() = editor.component
    override val isModified: Boolean get() = editor.isModified()

    init {
        reset()
    }

    override fun save() {
        val form = editor.form()
        try {
            validatePlatformForm(form)
        } catch (error: PlatformFormValidationException) {
            throw OmniCodeSettingsSaveException(
                error.localizedMessage ?: "平台配置无效。",
                error,
                error.sectionIndex,
            )
        } catch (error: ConfigurationException) {
            throw OmniCodeSettingsSaveException(error.localizedMessage ?: "平台配置无效。", error)
        }
        val service = OmniCodePlatformSettingsService.getInstance()
        val previous = service.snapshot()
        service.update { state -> form.writeTo(state) }
        val saved = service.snapshot()
        clearRemovedMcpCredentials(previous.mcpServers, saved.mcpServers)
        editor.reset(saved)
    }

    override fun reset() {
        editor.reset(OmniCodePlatformSettingsService.getInstance().snapshot())
    }

    override fun selectSection(index: Int) {
        editor.selectSection(index)
    }

    override fun dispose() = editor.dispose()
}

private class PlatformSettingsEditor(
    private val showTabs: Boolean = true,
    private val project: Project? = null,
) {
    private val historyEnabled = JCheckBox("保存会话历史")
    private val historyRetention = JSpinner(SpinnerNumberModel(100, 1, 1_000, 10))
    private val usageRetentionDays = JSpinner(SpinnerNumberModel(365, 1, 3_650, 30))
    private val agentMaxIterations = JSpinner(SpinnerNumberModel(24, 1, 128, 1))
    private val agentMaxToolCalls = JSpinner(SpinnerNumberModel(32, 1, 256, 1))
    private val agentMaxWallTimeSeconds = JSpinner(SpinnerNumberModel(600, 30, 3_600, 30))
    private val agentMaxToolTimeSeconds = JSpinner(SpinnerNumberModel(300, 5, 1_800, 5))
    private val agentMaxInputTokens = JSpinner(
        SpinnerNumberModel(250_000L, 1_000L, MAX_WORKFLOW_TOKEN_BUDGET, 10_000L),
    )
    private val agentMaxOutputTokens = JSpinner(
        SpinnerNumberModel(32_000L, 1_000L, MAX_WORKFLOW_TOKEN_BUDGET, 10_000L),
    )
    private val fullSpeedPresetButton = JButton("应用全速项目预设")
    private val agentProviderMaxAttempts = JSpinner(SpinnerNumberModel(3, 1, 5, 1))
    private val agentMaxRunCostUsd = JSpinner(SpinnerNumberModel(0.0, 0.0, 10_000.0, 0.1))
    private val agentCostWarningPercent = JSpinner(SpinnerNumberModel(80, 1, 100, 5))
    private val workspaceWrite = JRadioButton("workspace-write（推荐）")
    private val dangerFullAccess = JRadioButton("danger-full-access")
    private val sandboxProbeButton = JButton("检测本机隔离能力")
    private val sandboxProbeStatus = description("尚未检测")
    private val mcpEditor = McpServersEditor(project)
    private val commitEnabled = JCheckBox("启用 AI 生成 Git Commit 信息")
    private val commitIncludeBody = JCheckBox("必要时生成 Commit 正文")
    private val commitLanguage = JComboBox<String>().apply {
        model = DefaultComboBoxModel(arrayOf("Auto", "English", "Chinese", "Japanese"))
        isEditable = true
    }
    private val commitPrompt = JTextArea(10, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val promptEditor = PromptLibraryEditor()
    private val skillEditor = SkillLibraryEditor(project)
    private var baseline = PlatformEditorForm.default()

    val preferredFocus: JComponent get() = workspaceWrite

    private val sections = listOf(
        "常规" to generalPanel(),
        "沙箱" to sandboxPanel(),
        "MCP 服务器" to mcpEditor.component,
        "Commit AI" to commitPanel(),
        "提示词库" to promptEditor.component,
        "Skill 库" to skillEditor.component,
        "运行控制" to runtimePanel(),
    )
    private val cardsLayout = CardLayout()
    private val cards = JPanel(cardsLayout).apply {
        if (!showTabs) sections.forEachIndexed { index, section -> add(section.second, index.toString()) }
    }
    private val tabs = JBTabbedPane().apply {
        tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        if (showTabs) sections.forEach { section -> addTab(section.first, section.second) }
    }
    val component: JComponent = if (showTabs) tabs else cards

    init {
        ButtonGroup().apply {
            add(workspaceWrite)
            add(dangerFullAccess)
        }
        sandboxProbeButton.addActionListener { detectSandboxCapability() }
        commitEnabled.addActionListener { updateCommitControls() }
        fullSpeedPresetButton.addActionListener { applyFullSpeedPreset() }
    }

    fun reset(snapshot: OmniCodePlatformSnapshot) {
        val form = PlatformEditorForm.from(snapshot)
        historyEnabled.isSelected = form.historyEnabled
        historyRetention.value = form.historyRetention
        usageRetentionDays.value = form.usageRetentionDays
        agentMaxIterations.value = form.agentRuntime.maxIterations
        agentMaxToolCalls.value = form.agentRuntime.maxToolCalls
        agentMaxWallTimeSeconds.value = form.agentRuntime.maxWallTimeSeconds
        agentMaxToolTimeSeconds.value = form.agentRuntime.maxToolTimeSeconds
        agentMaxInputTokens.value = form.agentRuntime.maxInputTokens
        agentMaxOutputTokens.value = form.agentRuntime.maxOutputTokens
        agentProviderMaxAttempts.value = form.agentRuntime.providerMaxAttempts
        agentMaxRunCostUsd.value = form.agentRuntime.maxRunCostUsd
        agentCostWarningPercent.value = form.agentRuntime.costWarningPercent
        when (form.sandboxMode) {
            SandboxMode.WORKSPACE_WRITE -> workspaceWrite.isSelected = true
            SandboxMode.DANGER_FULL_ACCESS -> dangerFullAccess.isSelected = true
        }
        mcpEditor.reset(form.mcpServers)
        commitEnabled.isSelected = form.commitAi.enabled
        commitIncludeBody.isSelected = form.commitAi.includeBody
        commitLanguage.selectedItem = form.commitAi.language
        commitPrompt.text = form.commitAi.prompt
        commitPrompt.caretPosition = 0
        updateCommitControls()
        promptEditor.reset(form.prompts)
        skillEditor.reset(form.skills)
        baseline = form
    }

    fun form(): PlatformEditorForm {
        val language = commitLanguage.editor.item?.toString()?.trim().orEmpty().ifBlank { "Auto" }
        return PlatformEditorForm(
            historyEnabled = historyEnabled.isSelected,
            historyRetention = (historyRetention.value as Number).toInt(),
            usageRetentionDays = (usageRetentionDays.value as Number).toInt(),
            agentRuntime = AgentRuntimeEditorForm(
                maxIterations = (agentMaxIterations.value as Number).toInt(),
                maxToolCalls = (agentMaxToolCalls.value as Number).toInt(),
                maxWallTimeSeconds = (agentMaxWallTimeSeconds.value as Number).toInt(),
                maxToolTimeSeconds = (agentMaxToolTimeSeconds.value as Number).toInt(),
                maxInputTokens = (agentMaxInputTokens.value as Number).toLong(),
                maxOutputTokens = (agentMaxOutputTokens.value as Number).toLong(),
                providerMaxAttempts = (agentProviderMaxAttempts.value as Number).toInt(),
                maxRunCostUsd = (agentMaxRunCostUsd.value as Number).toDouble(),
                costWarningPercent = (agentCostWarningPercent.value as Number).toInt(),
            ),
            sandboxMode = if (dangerFullAccess.isSelected) {
                SandboxMode.DANGER_FULL_ACCESS
            } else {
                SandboxMode.WORKSPACE_WRITE
            },
            mcpServers = mcpEditor.rows(),
            commitAi = CommitEditorForm(
                enabled = commitEnabled.isSelected,
                includeBody = commitIncludeBody.isSelected,
                language = language,
                prompt = commitPrompt.text,
            ),
            prompts = promptEditor.rows(),
            skills = skillEditor.rows(),
        )
    }

    fun isModified(): Boolean = form() != baseline

    fun selectSection(index: Int) {
        if (index !in sections.indices) return
        if (showTabs) tabs.selectedIndex = index else cardsLayout.show(cards, index.toString())
    }

    fun dispose() {
        mcpEditor.dispose()
    }

    private fun generalPanel(): JComponent = paddedPanel().apply {
        layout = GridBagLayout()
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }
        constraints.gridy = 0
        add(sectionTitle("本地记录"), constraints)
        constraints.gridy++
        constraints.insets = Insets(12, 0, 0, 0)
        add(historyEnabled, constraints)
        constraints.gridy++
        constraints.insets = Insets(8, 24, 0, 0)
        add(labeledField("最多保留会话", JPanel(BorderLayout(8, 0)).apply {
            add(historyRetention, BorderLayout.WEST)
            add(JLabel("条"), BorderLayout.CENTER)
        }), constraints)
        constraints.gridy++
        constraints.insets = Insets(8, 24, 0, 0)
        add(labeledField("用量数据保留", JPanel(BorderLayout(8, 0)).apply {
            add(usageRetentionDays, BorderLayout.WEST)
            add(JLabel("天"), BorderLayout.CENTER)
        }), constraints)
        constraints.gridy++
        constraints.insets = Insets(10, 0, 0, 0)
        add(description("历史、工具审计和用量记录仅保存在 JetBrains 的本地系统目录，不写入项目仓库。"), constraints)
        constraints.gridy++
        constraints.weighty = 1.0
        add(JPanel().apply { isOpaque = false }, constraints)
    }

    private fun runtimePanel(): JComponent = paddedPanel().apply {
        layout = GridBagLayout()
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }
        constraints.gridy = 0
        add(sectionTitle("Agent 运行边界"), constraints)
        constraints.gridy++
        constraints.insets = Insets(8, 0, 10, 0)
        add(description("限制单次任务的循环、工具、时间、累计 Token 和费用；达到任一硬上限后 Agent 会保留现场并停止。"), constraints)

        constraints.gridy++
        constraints.insets = Insets(0, 0, 10, 0)
        add(JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(fullSpeedPresetButton, BorderLayout.WEST)
            add(description("把当前模型设为全速，同时将累计输入/输出预算提升到百亿，并放宽轮次、工具和一小时运行时间。"), BorderLayout.CENTER)
        }, constraints)

        val rows = listOf(
            "最多思考轮次" to unitField(agentMaxIterations, "轮"),
            "最多工具调用" to unitField(agentMaxToolCalls, "次"),
            "单次任务超时" to unitField(agentMaxWallTimeSeconds, "秒"),
            "单个工具超时" to unitField(agentMaxToolTimeSeconds, "秒"),
            "输入 Token 上限" to unitField(agentMaxInputTokens, "tokens"),
            "输出 Token 上限" to unitField(agentMaxOutputTokens, "tokens"),
            "Provider 最多尝试" to unitField(agentProviderMaxAttempts, "次"),
            "单次费用上限" to unitField(agentMaxRunCostUsd, "USD（0 = 不限制）"),
            "费用预警阈值" to unitField(agentCostWarningPercent, "%"),
        )
        rows.forEach { (label, field) ->
            constraints.gridy++
            constraints.insets = Insets(6, 0, 0, 0)
            add(labeledField(label, field), constraints)
        }
        constraints.gridy++
        constraints.insets = Insets(12, 0, 0, 0)
        add(description("累计预算最高允许 10,000,000,000 Token，但不会突破模型真实上下文或单次输出上限。429、5xx 和网络故障会按 Retry-After/指数退避重试；费用限制依赖“价格配置”中匹配的模型单价。"), constraints)
        constraints.gridy++
        constraints.weighty = 1.0
        add(JPanel().apply { isOpaque = false }, constraints)
    }

    private fun applyFullSpeedPreset() {
        val confirmed = Messages.showYesNoDialog(
            project,
            "全速预设会把单次任务累计输入和输出预算各提升到 10,000,000,000 Token，并放宽到 128 轮、256 次工具调用和 1 小时。\n\n模型仍受单次请求上限约束；若费用上限为 0，理论费用可能非常高。是否继续？",
            "应用全速项目预设",
            "应用预设",
            "取消",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return
        agentMaxIterations.value = 128
        agentMaxToolCalls.value = 256
        agentMaxWallTimeSeconds.value = 3_600
        agentMaxToolTimeSeconds.value = 1_800
        agentMaxInputTokens.value = MAX_WORKFLOW_TOKEN_BUDGET
        agentMaxOutputTokens.value = MAX_WORKFLOW_TOKEN_BUDGET
        OmniCodePlatformSettingsService.getInstance().update { state -> state.applyFullSpeedRuntimePreset() }
        val providerSettings = OmniCodeSettingsService.getInstance()
        val provider = providerSettings.snapshot()
        val preset = ProviderPresets.byId(provider.providerId)
        if (ReasoningEffort.MAX in reasoningEffortOptions(preset.id, preset.protocol, provider.model)) {
            providerSettings.update(
                provider.copy(
                    reasoningEffort = ReasoningEffort.MAX,
                    maxOutputTokens = maxOf(
                        provider.maxOutputTokens,
                        ReasoningEffort.MAX.recommendedOutputTokenFloor(),
                    ),
                ),
            )
        }
    }

    private fun sandboxPanel(): JComponent = paddedPanel().apply {
        layout = GridBagLayout()
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }
        constraints.gridy = 0
        add(sectionTitle("命令执行沙箱"), constraints)
        constraints.gridy++
        constraints.insets = Insets(12, 0, 0, 0)
        add(workspaceWrite, constraints)
        constraints.gridy++
        constraints.insets = Insets(2, 24, 12, 0)
        add(description(
            "默认模式。命令只能在当前工作区边界内运行；无法提供系统级隔离时会拒绝执行，不会静默降级。",
        ), constraints)
        constraints.gridy++
        constraints.insets = Insets(0, 24, 12, 0)
        add(description(ProcessSandbox.setupGuidance()), constraints)
        constraints.gridy++
        constraints.insets = Insets(0, 24, 12, 0)
        add(JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(sandboxProbeButton, BorderLayout.WEST)
            add(sandboxProbeStatus, BorderLayout.CENTER)
        }, constraints)
        constraints.gridy++
        constraints.insets = Insets(0, 0, 0, 0)
        add(dangerFullAccess, constraints)
        constraints.gridy++
        constraints.insets = Insets(2, 24, 12, 0)
        add(warningDescription(
            "关闭系统文件和网络隔离，可用于 adb、docker 等需要额外权限的命令；每次危险操作仍需审批。",
        ), constraints)
        constraints.gridy++
        constraints.weighty = 1.0
        add(JPanel().apply { isOpaque = false }, constraints)
    }

    private fun commitPanel(): JComponent = paddedPanel().apply {
        layout = BorderLayout(0, 12)
        add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            val constraints = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }
            constraints.gridy = 0
            add(commitEnabled, constraints)
            constraints.gridy++
            constraints.insets = Insets(6, 24, 0, 0)
            add(commitIncludeBody, constraints)
            constraints.gridy++
            constraints.insets = Insets(8, 24, 0, 0)
            add(labeledField("语言", commitLanguage), constraints)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(JLabel("Commit 提示词"), BorderLayout.NORTH)
            add(JScrollPane(commitPrompt), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
    }

    private fun updateCommitControls() {
        val enabled = commitEnabled.isSelected
        commitIncludeBody.isEnabled = enabled
        commitLanguage.isEnabled = enabled
        commitPrompt.isEnabled = enabled
    }

    private fun detectSandboxCapability() {
        sandboxProbeButton.isEnabled = false
        sandboxProbeStatus.text = "检测中…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val capability = runCatching { ProcessSandbox().capability(SandboxMode.WORKSPACE_WRITE) }
                .getOrElse { error ->
                    SwingUtilities.invokeLater {
                        sandboxProbeStatus.text = "检测失败：${error.message ?: error::class.java.simpleName}"
                        sandboxProbeButton.isEnabled = true
                    }
                    return@executeOnPooledThread
                }
            SwingUtilities.invokeLater {
                sandboxProbeStatus.text = if (capability.enforced) {
                    "可用：${capability.summary}"
                } else {
                    "不可用：${capability.summary}"
                }
                sandboxProbeButton.isEnabled = true
            }
        }
    }
}

private class McpServersEditor(
    private val project: Project?,
) {
    private val marketplaceRegistryClient = McpRegistryCatalogClient()
    private val model = DefaultListModel<McpEditorRow>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val row = value as? McpEditorRow
                val label = row?.let {
                    val state = when {
                        it.enabled && it.transport == McpTransport.STDIO && it.command.isNotBlank() -> "已启用"
                        it.enabled && it.transport == McpTransport.HTTP && it.url.isNotBlank() -> "已启用"
                        it.enabled && it.transport == McpTransport.STDIO -> "需填写命令"
                        it.enabled -> "需填写 URL"
                        else -> "未启用"
                    }
                    "${it.name.ifBlank { "MCP Server" }}  ·  ${it.transport.id}  ·  $state"
                } ?: value
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
            }
        }
    }
    private val enabled = JCheckBox("启用此 MCP 服务器")
    private val name = editorTextField()
    private val transport = JComboBox(McpTransport.entries.toTypedArray())
    private val command = editorTextField()
    private val arguments = editorTextField()
    private val environmentKeys = editorTextField()
    private val workingDirectory = editorTextField()
    private val url = editorTextField()
    private val httpAuthMode = JComboBox(McpHttpAuthMode.entries.toTypedArray())
    private val oauthClientId = editorTextField()
    private val oauthScopes = editorTextField()
    private val removeButton = JButton("删除").apply { isEnabled = false }
    private val marketplaceButton = JButton("MCP 市场…")
    private val manualAddButton = JButton("手动添加")
    private val clearTrustButton = JButton("清除连接信任").apply { isEnabled = false }
    private val saveSecretButton = JButton("保存密钥…").apply { isEnabled = false }
    private val clearSecretButton = JButton("清除密钥…").apply { isEnabled = false }
    private val saveTokenButton = JButton("保存 Bearer Token…").apply { isEnabled = false }
    private val clearTokenButton = JButton("清除 Token").apply { isEnabled = false }
    private val oauthLoginButton = JButton("OAuth 登录…").apply { isEnabled = false }
    private val oauthLogoutButton = JButton("退出 OAuth").apply { isEnabled = false }
    private val testConnectionButton = JButton("测试连接 / 发现工具").apply { isEnabled = false }
    private val trustStatus = JLabel()
    private val secretStatus = JLabel()
    private val tokenStatus = JLabel()
    private val oauthStatus = JLabel()
    private val connectionStatus = JLabel()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val credentialStore: McpEnvironmentCredentialStore
        get() = McpEnvironmentCredentialStore.getInstance()
    private val httpCredentialStore: McpHttpCredentialStore
        get() = McpHttpCredentialStore.getInstance()
    private val oauthSessions: McpOAuthSessionManager by lazy(::McpOAuthSessionManager)
    private var editingIndex = -1
    private var loading = false

    val component: JComponent = paddedPanel().apply {
        layout = BorderLayout(0, 8)
        add(JPanel(BorderLayout(0, 8)).apply {
            isOpaque = false
            add(description(
                "从内置 MCP 市场添加停用的配置草稿，或手动配置 stdio / Streamable HTTP。密钥与 Token 只保存到 IDE PasswordSafe。",
            ), BorderLayout.NORTH)
            add(mcpToolbarPanel(), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            resizeWeight = 0.28
            leftComponent = JScrollPane(list).apply { minimumSize = Dimension(120, 120) }
            rightComponent = mcpDetailsPanel()
            leftComponent.minimumSize = Dimension(0, 0)
            rightComponent.minimumSize = Dimension(0, 0)
            installAdaptiveSplit(this, threshold = 620, horizontalWeight = 0.28, verticalWeight = 0.34)
        }, BorderLayout.CENTER)
    }

    init {
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !loading) {
                commitEditor()
                loadSelection()
            }
        }
        transport.addActionListener {
            if (!loading) {
                commitEditor()
                updateEditorEnabled(editingIndex in 0 until model.size())
            }
        }
        httpAuthMode.addActionListener {
            if (!loading) {
                commitEditor()
                updateEditorEnabled(editingIndex in 0 until model.size())
            }
        }
        updateEditorEnabled(false)
    }

    fun reset(rows: List<McpEditorRow>) {
        loading = true
        editingIndex = -1
        model.clear()
        rows.forEach(model::addElement)
        list.selectedIndex = if (model.size() > 0) 0 else -1
        loading = false
        loadSelection()
    }

    fun rows(): List<McpEditorRow> {
        commitEditor()
        return (0 until model.size()).map(model::getElementAt)
    }

    fun dispose() {
        testScope.cancel()
    }

    private fun mcpDetailsPanel(): JComponent = JPanel(GridBagLayout()).apply {
        border = EmptyBorder(0, 12, 0, 0)
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(0, 0, 8, 0)
        }
        constraints.gridy = 0
        add(enabled, constraints)
        constraints.gridy++
        add(labeledField("名称", this@McpServersEditor.name), constraints)
        constraints.gridy++
        add(labeledField("传输方式", transport), constraints)
        constraints.gridy++
        add(labeledField("HTTP Endpoint", url), constraints)
        constraints.gridy++
        add(labeledField("HTTP 认证", httpAuthMode), constraints)
        constraints.gridy++
        add(labeledField("OAuth Client ID", oauthClientId), constraints)
        constraints.gridy++
        add(labeledField("OAuth Scopes", oauthScopes), constraints)
        constraints.gridy++
        add(labeledField("启动命令", command), constraints)
        constraints.gridy++
        add(labeledField("参数", arguments), constraints)
        constraints.gridy++
        add(labeledField("环境变量名", environmentKeys), constraints)
        constraints.gridy++
        add(labeledField("工作目录", workingDirectory), constraints)
        constraints.gridy++
        add(description("stdio 示例：npx · -y @modelcontextprotocol/server-filesystem .；HTTP 示例：https://example.com/mcp（仅 localhost/127.0.0.1/::1 可使用明文 HTTP）。"), constraints)
        constraints.gridy++
        add(mcpCredentialPanel(), constraints)
        constraints.gridy++
        constraints.weighty = 1.0
        add(JPanel().apply { isOpaque = false }, constraints)
    }

    private fun mcpCredentialPanel(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(saveSecretButton.also { button -> button.addActionListener { saveEnvironmentSecret() } })
            add(clearSecretButton.also { button -> button.addActionListener { clearEnvironmentSecret() } })
            add(saveTokenButton.also { button -> button.addActionListener { saveBearerToken() } })
            add(clearTokenButton.also { button -> button.addActionListener { clearBearerToken() } })
            add(oauthLoginButton.also { button -> button.addActionListener { loginOAuth() } })
            add(oauthLogoutButton.also { button -> button.addActionListener { logoutOAuth() } })
        })
        add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            val row = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }
            listOf(trustStatus, secretStatus, tokenStatus, oauthStatus, connectionStatus).forEachIndexed { index, label ->
                row.gridy = index
                add(label, row)
            }
        })
    }

    private fun mcpToolbarPanel(): JComponent {
        marketplaceButton.addActionListener { openMarketplace() }
        manualAddButton.addActionListener { addServer() }
        removeButton.addActionListener { removeServer() }
        clearTrustButton.addActionListener { clearLaunchTrust() }
        testConnectionButton.addActionListener { testConnection() }
        val buttons = listOf(
            marketplaceButton,
            manualAddButton,
            removeButton,
            clearTrustButton,
            testConnectionButton,
        )
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            var compactLayout: Boolean? = null

            fun rebuild(compact: Boolean) {
                if (compactLayout == compact) return
                compactLayout = compact
                removeAll()
                buttons.forEachIndexed { index, button ->
                    val row = if (compact && index >= MCP_COMPACT_TOOLBAR_COLUMNS) 1 else 0
                    val column = if (compact) index % MCP_COMPACT_TOOLBAR_COLUMNS else index
                    add(button, GridBagConstraints().apply {
                        gridx = column
                        gridy = row
                        anchor = GridBagConstraints.WEST
                        insets = Insets(
                            0,
                            0,
                            if (compact && row == 0) 6 else 0,
                            if (index == buttons.lastIndex) 0 else 6,
                        )
                    })
                }
                add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
                    gridx = if (compact) MCP_COMPACT_TOOLBAR_COLUMNS else buttons.size
                    gridy = 0
                    gridheight = if (compact) 2 else 1
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                })
                revalidate()
                repaint()
            }

            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    rebuild(width in 1 until MCP_TOOLBAR_COMPACT_THRESHOLD)
                }
            })
            rebuild(compact = true)
        }
    }

    private fun addServer() {
        commitEditor()
        val index = model.size()
        model.addElement(
            McpEditorRow(
                id = UUID.randomUUID().toString(),
                name = nextMcpName(),
                enabled = false,
                transport = McpTransport.STDIO,
                command = "",
                arguments = "",
                environmentKeys = "",
                workingDirectory = ".",
                url = "",
                httpAuthMode = McpHttpAuthMode.BEARER,
                oauthClientId = "",
                oauthScopes = "",
            ),
        )
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        loadSelection()
        command.requestFocusInWindow()
    }

    private fun openMarketplace() {
        commitEditor()
        McpMarketplaceDialog(
            project = project,
            isInstalled = ::isCatalogEntryConfigured,
            onAdd = ::addCatalogDraft,
            onViewInstalled = ::selectCatalogEntry,
            registryLoader = { forceRefresh -> marketplaceRegistryClient.load(forceRefresh).entries },
        ).show()
    }

    private fun addCatalogDraft(entry: McpCatalogEntry, optionId: String) {
        commitEditor()
        val existingIndex = configuredIndex(entry)
        if (existingIndex >= 0) {
            selectConfiguredIndex(existingIndex, "该市场项已在配置列表中。")
            return
        }
        val draft = McpMarketplaceCatalog.createDraft(entry, optionId, uniqueServerName(entry.name))
        val index = model.size()
        model.addElement(draft.config.toEditorRow())
        selectConfiguredIndex(
            index,
            "已添加停用草稿 · 请核对命令与权限，再在侧边栏底部保存。",
        )
    }

    private fun selectCatalogEntry(entry: McpCatalogEntry) {
        commitEditor()
        val index = configuredIndex(entry)
        check(index >= 0) { "该 MCP 配置已不在当前草稿中" }
        selectConfiguredIndex(index, "已定位到该 MCP 配置。")
    }

    private fun selectConfiguredIndex(index: Int, status: String) {
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        loadSelection()
        connectionStatus.text = status
    }

    private fun isCatalogEntryConfigured(entry: McpCatalogEntry): Boolean = configuredIndex(entry) >= 0

    private fun configuredIndex(entry: McpCatalogEntry): Int = (0 until model.size()).firstOrNull { index ->
        val row = model.getElementAt(index)
        entry.installOptions.any { option -> row.matchesCatalogOption(option) }
    } ?: -1

    private fun McpEditorRow.matchesCatalogOption(option: McpCatalogInstallOption): Boolean = when (option.transport) {
        McpTransport.STDIO -> transport == McpTransport.STDIO &&
            command.trim() == option.command &&
            runCatching { parseCommandLine(arguments) }.getOrNull() == option.arguments
        McpTransport.HTTP -> transport == McpTransport.HTTP && runCatching {
            dev.omnicode.mcp.validateMcpHttpEndpoint(url).toASCIIString()
        }.getOrNull() == runCatching {
            dev.omnicode.mcp.validateMcpHttpEndpoint(option.url).toASCIIString()
        }.getOrNull()
    }

    private fun McpServerConfig.toEditorRow(): McpEditorRow = McpEditorRow(
        id = id,
        name = name,
        enabled = false,
        transport = transport,
        command = command,
        arguments = renderCommandLine(arguments),
        environmentKeys = environmentKeys.sorted().joinToString(", "),
        workingDirectory = workingDirectory,
        url = url,
        httpAuthMode = httpAuthMode,
        oauthClientId = oauthClientId,
        oauthScopes = oauthScopes.joinToString(" "),
    )

    private fun uniqueServerName(preferred: String): String {
        val names = (0 until model.size()).map { model.getElementAt(it).name.trim().lowercase() }.toSet()
        if (preferred.trim().lowercase() !in names) return preferred
        var suffix = 2
        while ("$preferred $suffix".lowercase() in names) suffix++
        return "$preferred $suffix"
    }

    private fun removeServer() {
        commitEditor()
        val index = list.selectedIndex
        if (index < 0) return
        loading = true
        editingIndex = -1
        model.remove(index)
        list.selectedIndex = when {
            model.isEmpty -> -1
            index < model.size() -> index
            else -> model.size() - 1
        }
        loading = false
        loadSelection()
    }

    private fun commitEditor() {
        if (loading || editingIndex !in 0 until model.size()) return
        model.set(
            editingIndex,
            model.getElementAt(editingIndex).copy(
                name = name.text,
                enabled = enabled.isSelected,
                transport = transport.selectedItem as? McpTransport ?: McpTransport.STDIO,
                command = command.text,
                arguments = arguments.text,
                environmentKeys = environmentKeys.text,
                workingDirectory = workingDirectory.text,
                url = url.text,
                httpAuthMode = httpAuthMode.selectedItem as? McpHttpAuthMode ?: McpHttpAuthMode.BEARER,
                oauthClientId = oauthClientId.text,
                oauthScopes = oauthScopes.text,
            ),
        )
    }

    private fun loadSelection() {
        loading = true
        editingIndex = list.selectedIndex
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt)
        enabled.isSelected = row?.enabled == true
        name.text = row?.name.orEmpty()
        transport.selectedItem = row?.transport ?: McpTransport.STDIO
        command.text = row?.command.orEmpty()
        arguments.text = row?.arguments.orEmpty()
        environmentKeys.text = row?.environmentKeys.orEmpty()
        workingDirectory.text = row?.workingDirectory.orEmpty()
        url.text = row?.url.orEmpty()
        httpAuthMode.selectedItem = row?.httpAuthMode ?: McpHttpAuthMode.BEARER
        oauthClientId.text = row?.oauthClientId.orEmpty()
        oauthScopes.text = row?.oauthScopes.orEmpty()
        updateEditorEnabled(row != null)
        removeButton.isEnabled = row != null
        updateTrustStatus(row)
        updateSecretStatus(row)
        updateTokenStatus(row)
        updateOAuthStatus(row)
        connectionStatus.text = ""
        loading = false
    }

    private fun clearLaunchTrust() {
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        val removed = OmniCodePlatformSettingsService.getInstance().clearMcpLaunchTrusts(row.id)
        updateTrustStatus(row, if (removed > 0) "已清除 $removed 条启动信任" else null)
    }

    private fun saveEnvironmentSecret() {
        commitEditor()
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        val key = com.intellij.openapi.ui.Messages.showInputDialog(
            project,
            "输入 MCP Server 需要的环境变量名，例如 GITHUB_TOKEN。变量名会写入普通配置，值只写入 PasswordSafe。",
            "保存 MCP 密钥",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        )?.trim().orEmpty()
        if (!isValidMcpEnvironmentKey(key)) {
            if (key.isNotEmpty()) trustStatus.text = "环境变量名无效"
            return
        }
        val value = com.intellij.openapi.ui.Messages.showPasswordDialog(
            project,
            "输入 $key 的值。该值不会显示在配置或审计日志中。",
            "保存 MCP 密钥",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        ) ?: return
        if (value.isBlank()) {
            trustStatus.text = "密钥值不能为空"
            return
        }
        runCatching { credentialStore.save(row.id, key, value) }
            .onFailure {
                trustStatus.text = "无法写入 PasswordSafe"
                return
            }
        val keys = normalizeEnvironmentKeys(environmentKeys.text).toMutableSet().apply { add(key) }
        environmentKeys.text = keys.sorted().joinToString(", ")
        commitEditor()
        updateSecretStatus(model.getElementAt(editingIndex), "已安全保存 $key")
    }

    private fun clearEnvironmentSecret() {
        commitEditor()
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        val configured = normalizeEnvironmentKeys(row.environmentKeys)
        if (configured.isEmpty()) {
            updateSecretStatus(row, "请先添加环境变量名")
            return
        }
        val key = com.intellij.openapi.ui.Messages.showInputDialog(
            project,
            "输入要从 PasswordSafe 清除的变量名。已配置：${configured.joinToString()}。",
            "清除 MCP 密钥",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
            configured.first(),
            null,
        )?.trim().orEmpty()
        if (key !in configured) {
            if (key.isNotEmpty()) trustStatus.text = "该变量未在此服务器配置"
            return
        }
        runCatching { credentialStore.clear(row.id, key) }
            .onFailure {
                trustStatus.text = "无法更新 PasswordSafe"
                return
            }
        updateSecretStatus(row, "已清除 $key")
    }

    private fun saveBearerToken() {
        commitEditor()
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        val value = com.intellij.openapi.ui.Messages.showPasswordDialog(
            project,
            "输入 ${row.name.ifBlank { "MCP Server" }} 的 Bearer Token。Token 只保存到 IDE PasswordSafe，不会写入 XML 或日志。",
            "保存 MCP Bearer Token",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        ) ?: return
        if (value.isBlank()) {
            updateTokenStatus(row, "Token 不能为空")
            return
        }
        runCatching { httpCredentialStore.save(row.id, value) }
            .onFailure {
                updateTokenStatus(row, "无法写入 PasswordSafe")
                return
            }
        updateTokenStatus(row, "Bearer Token 已安全保存")
    }

    private fun clearBearerToken() {
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        runCatching { httpCredentialStore.clear(row.id) }
            .onFailure {
                updateTokenStatus(row, "无法更新 PasswordSafe")
                return
            }
        updateTokenStatus(row, "Bearer Token 已清除")
    }

    private fun loginOAuth() {
        commitEditor()
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        if (row.transport != McpTransport.HTTP || row.httpAuthMode != McpHttpAuthMode.OAUTH) return
        val config = runCatching { row.toTestConfig() }.getOrElse { error ->
            oauthStatus.text = error.message ?: "OAuth 配置无效"
            return
        }
        if (!isPersistedOAuthConfig(row)) {
            oauthStatus.text = "请先保存当前 MCP 配置，再进行 OAuth 登录"
            return
        }
        oauthLoginButton.isEnabled = false
        oauthLogoutButton.isEnabled = false
        testConnectionButton.isEnabled = false
        oauthStatus.text = "正在发现 OAuth 授权服务…"
        testScope.launch {
            val result = runCatching {
                oauthSessions.login(
                    config = config,
                    confirm = { approval -> confirmOAuthLogin(approval) },
                    openBrowser = { uri -> onEdt { BrowserUtil.browse(uri.toASCIIString()) } },
                )
            }
            SwingUtilities.invokeLater {
                val selected = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt)
                if (selected?.id != row.id) return@invokeLater
                val message = result.fold(
                    onSuccess = { session ->
                        val scopeSummary = session.scopes.take(3).joinToString(", ").ifBlank { "服务器默认权限" }
                        "OAuth 已登录 · ${URI(session.issuer).host} · $scopeSummary"
                    },
                    onFailure = { error ->
                        "OAuth 登录失败：${error.message?.lineSequence()?.firstOrNull()?.take(180) ?: error::class.java.simpleName}"
                    },
                )
                updateEditorEnabled(true)
                oauthStatus.text = message
            }
        }
    }

    private fun logoutOAuth() {
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        runCatching { oauthSessions.logout(row.id) }
            .onSuccess { updateOAuthStatus(row, "OAuth 凭据已从 PasswordSafe 清除") }
            .onFailure { updateOAuthStatus(row, "无法更新 PasswordSafe") }
    }

    private suspend fun confirmOAuthLogin(approval: McpOAuthLoginApproval): Boolean = onEdt {
        val scopes = approval.scopes.joinToString(" ").ifBlank { "由授权服务器决定" }
        val registration = if (approval.dynamicRegistration) "动态注册公开客户端" else "使用已配置 Client ID"
        com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            """
            MCP 服务：${approval.serverName}
            资源：${approval.resource}
            授权服务器：${approval.issuer}
            浏览器授权地址：${approval.authorizationEndpoint}
            权限：$scopes
            客户端：$registration
            回调：${approval.redirectUri}

            继续后将打开系统浏览器。访问令牌和刷新令牌只保存到 IDE PasswordSafe。
            """.trimIndent(),
            "授权 OmniCode 连接 MCP",
            "继续",
            "取消",
            com.intellij.openapi.ui.Messages.getWarningIcon(),
        ) == com.intellij.openapi.ui.Messages.YES
    }

    private fun testConnection() {
        val activeProject = project ?: run {
            connectionStatus.text = "请从项目侧边栏打开配置后测试"
            return
        }
        commitEditor()
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        val config = runCatching { row.toTestConfig() }.getOrElse { error ->
            connectionStatus.text = error.message ?: "配置无效"
            return
        }
        if (config.httpAuthMode == McpHttpAuthMode.OAUTH && !isPersistedOAuthConfig(row)) {
            connectionStatus.text = "请先保存当前 MCP 配置，再测试 OAuth 连接"
            return
        }
        testConnectionButton.isEnabled = false
        connectionStatus.text = "等待连接审批…"
        testScope.launch {
            val result = runCatching {
                val gate = ModalApprovalGate(activeProject)
                val client: McpClient = when (config.transport) {
                    McpTransport.STDIO -> McpStdioClient.connect(
                        config,
                        SandboxedMcpProcessLauncher(
                            activeProject,
                            OmniCodePlatformSettingsService.getInstance().snapshot().sandboxMode,
                            gate,
                        ),
                    )
                    McpTransport.HTTP -> ApprovedMcpHttpClientConnector(activeProject, gate).connect(config)
                }
                client.use { connected -> connected.listTools() }
            }
            SwingUtilities.invokeLater {
                val selected = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt)
                if (selected?.id != row.id) return@invokeLater
                connectionStatus.text = result.fold(
                    onSuccess = { tools -> "连接成功 · 发现 ${tools.size} 个工具" },
                    onFailure = { error ->
                        "连接失败：${error.message?.lineSequence()?.firstOrNull()?.take(180) ?: error::class.java.simpleName}"
                    },
                )
                testConnectionButton.isEnabled = true
            }
        }
    }

    private fun McpEditorRow.toTestConfig(): McpServerConfig {
        val serverName = name.trim().ifBlank { "MCP Server" }
        return when (transport) {
            McpTransport.STDIO -> {
                require(command.isNotBlank()) { "$serverName 需要填写启动命令" }
                McpServerConfig(
                    id = id,
                    name = serverName,
                    enabled = true,
                    command = command.trim(),
                    arguments = parseCommandLine(arguments),
                    environmentKeys = normalizeEnvironmentKeys(environmentKeys).onEach { key ->
                        require(ENVIRONMENT_KEY.matches(key)) { "环境变量名无效：$key" }
                    }.toSet(),
                    workingDirectory = workingDirectory.trim().ifBlank { "." },
                    transport = McpTransport.STDIO,
                )
            }
            McpTransport.HTTP -> {
                val endpoint = dev.omnicode.mcp.validateMcpHttpEndpoint(url).toASCIIString()
                McpServerConfig(
                    id = id,
                    name = serverName,
                    enabled = true,
                    command = "",
                    arguments = emptyList(),
                    environmentKeys = emptySet(),
                    workingDirectory = ".",
                    transport = McpTransport.HTTP,
                    url = endpoint,
                    httpAuthMode = httpAuthMode,
                    oauthClientId = oauthClientId.trim(),
                    oauthScopes = normalizeOAuthScopes(oauthScopes),
                )
            }
        }
    }

    private fun updateTrustStatus(row: McpEditorRow?, overrideText: String? = null) {
        val count = row?.let { OmniCodePlatformSettingsService.getInstance().mcpLaunchTrustCount(it.id) } ?: 0
        clearTrustButton.isEnabled = row != null && count > 0
        trustStatus.text = overrideText ?: when {
            row == null -> ""
            count > 0 -> "已信任 $count 个项目指纹；配置变化会自动失效"
            else -> "首次连接需审批"
        }
    }

    private fun updateSecretStatus(row: McpEditorRow?, overrideText: String? = null) {
        val configured = row?.let { normalizeEnvironmentKeys(it.environmentKeys).size } ?: 0
        val stdio = row?.transport == McpTransport.STDIO
        saveSecretButton.isEnabled = row != null && stdio
        clearSecretButton.isEnabled = row != null && stdio && configured > 0
        secretStatus.text = overrideText ?: when {
            row == null || !stdio -> ""
            configured > 0 -> "密钥值仅保存在 PasswordSafe"
            else -> ""
        }
    }

    private fun updateTokenStatus(row: McpEditorRow?, overrideText: String? = null) {
        val bearer = row?.transport == McpTransport.HTTP && row.httpAuthMode == McpHttpAuthMode.BEARER
        val configured = row?.takeIf { bearer }?.let { runCatching { httpCredentialStore.hasToken(it.id) }.getOrDefault(false) }
            ?: false
        saveTokenButton.isEnabled = row != null && bearer
        clearTokenButton.isEnabled = row != null && bearer && configured
        tokenStatus.text = overrideText ?: when {
            row == null || !bearer -> ""
            configured -> "Bearer Token 已保存"
            else -> "未配置 Token"
        }
    }

    private fun updateOAuthStatus(row: McpEditorRow?, overrideText: String? = null) {
        val oauth = row?.transport == McpTransport.HTTP && row.httpAuthMode == McpHttpAuthMode.OAUTH
        val persisted = row?.takeIf { oauth }?.let(::isPersistedOAuthConfig) ?: false
        val configured = row?.takeIf { oauth }
            ?.let { runCatching { oauthSessions.hasSession(it.id) }.getOrDefault(false) }
            ?: false
        val usable = row?.takeIf { oauth && persisted }
            ?.let { selected ->
                runCatching { oauthSessions.hasUsableSession(selected.toTestConfig()) }.getOrDefault(false)
            }
            ?: false
        oauthLoginButton.isEnabled = row != null && oauth && persisted
        oauthLogoutButton.isEnabled = row != null && oauth && configured
        oauthStatus.text = overrideText ?: when {
            row == null || !oauth -> ""
            !persisted && configured -> "OAuth 配置有未保存变更；旧凭据仍保留，可退出 OAuth 清除"
            !persisted -> "请先保存当前 MCP 配置，再进行 OAuth 登录"
            usable -> "OAuth 凭据已保存到 PasswordSafe"
            configured -> "OAuth 配置已变化，需要重新登录；旧凭据可退出 OAuth 清除"
            else -> "尚未 OAuth 登录；Client ID 留空时尝试动态注册"
        }
    }

    private fun isPersistedOAuthConfig(row: McpEditorRow): Boolean {
        val stored = OmniCodePlatformSettingsService.getInstance()
            .snapshot()
            .mcpServers
            .firstOrNull { it.id == row.id }
            ?: return false
        if (stored.transport != McpTransport.HTTP || stored.httpAuthMode != McpHttpAuthMode.OAUTH) return false
        val editedEndpoint = runCatching {
            dev.omnicode.mcp.validateMcpHttpEndpoint(row.url).toASCIIString()
        }.getOrNull() ?: return false
        val storedEndpoint = runCatching {
            dev.omnicode.mcp.validateMcpHttpEndpoint(stored.url).toASCIIString()
        }.getOrNull() ?: return false
        return editedEndpoint == storedEndpoint &&
            row.oauthClientId.trim() == stored.oauthClientId &&
            normalizeOAuthScopes(row.oauthScopes) == stored.oauthScopes
    }

    private fun updateEditorEnabled(value: Boolean) {
        val selectedTransport = transport.selectedItem as? McpTransport ?: McpTransport.STDIO
        val stdio = value && selectedTransport == McpTransport.STDIO
        val http = value && selectedTransport == McpTransport.HTTP
        val oauth = http && (httpAuthMode.selectedItem as? McpHttpAuthMode) == McpHttpAuthMode.OAUTH
        val bearer = http && (httpAuthMode.selectedItem as? McpHttpAuthMode) == McpHttpAuthMode.BEARER
        enabled.isEnabled = value
        name.isEnabled = value
        transport.isEnabled = value
        command.isEnabled = stdio
        arguments.isEnabled = stdio
        environmentKeys.isEnabled = stdio
        workingDirectory.isEnabled = stdio
        url.isEnabled = http
        httpAuthMode.isEnabled = http
        oauthClientId.isEnabled = oauth
        oauthScopes.isEnabled = oauth
        saveSecretButton.isEnabled = stdio
        saveSecretButton.isVisible = stdio
        clearSecretButton.isVisible = stdio
        saveTokenButton.isVisible = bearer
        clearTokenButton.isVisible = bearer
        oauthLoginButton.isVisible = oauth
        oauthLogoutButton.isVisible = oauth
        testConnectionButton.isEnabled = value
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt)
        updateSecretStatus(row)
        updateTokenStatus(row)
        updateOAuthStatus(row)
    }

    private fun nextMcpName(): String {
        val names = (0 until model.size()).map { model.getElementAt(it).name.lowercase() }.toSet()
        var index = 1
        while (true) {
            val candidate = if (index == 1) "MCP Server" else "MCP Server $index"
            if (candidate.lowercase() !in names) return candidate
            index++
        }
    }
}

private class PromptLibraryEditor {
    private val model = DefaultListModel<PromptEditorRow>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val row = value as? PromptEditorRow
                val title = row?.let {
                    "${it.name.ifBlank { "Prompt" }}  ·  !${it.shortcut.ifBlank { "prompt" }}"
                } ?: value
                return super.getListCellRendererComponent(list, title, index, isSelected, cellHasFocus)
            }
        }
    }
    private val name = JTextField()
    private val shortcut = JTextField()
    private val content = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val removeButton = JButton("删除").apply { isEnabled = false }
    private var editingIndex = -1
    private var loading = false

    val component: JComponent = paddedPanel().apply {
        layout = BorderLayout(0, 8)
        add(description("在聊天框输入 !快捷词 即可插入模板；内容仅保存在本地 IDE 设置中。"), BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            resizeWeight = 0.28
            leftComponent = JPanel(BorderLayout(0, 6)).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(JButton("添加提示词").also { button -> button.addActionListener { addPrompt() } })
                    add(removeButton.also { button -> button.addActionListener { removePrompt() } })
                }, BorderLayout.NORTH)
                add(JScrollPane(list), BorderLayout.CENTER)
            }
            rightComponent = JPanel(GridBagLayout()).apply {
                border = EmptyBorder(0, 10, 0, 0)
                val constraints = GridBagConstraints().apply {
                    gridx = 0
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTHWEST
                    insets = Insets(0, 0, 8, 0)
                }
                constraints.gridy = 0
                add(labeledField("名称", this@PromptLibraryEditor.name), constraints)
                constraints.gridy++
                add(labeledField("快捷词", JPanel(BorderLayout()).apply {
                    add(JLabel("! "), BorderLayout.WEST)
                    add(shortcut, BorderLayout.CENTER)
                }), constraints)
                constraints.gridy++
                constraints.weighty = 1.0
                constraints.fill = GridBagConstraints.BOTH
                add(JPanel(BorderLayout(0, 4)).apply {
                    add(JLabel("内容"), BorderLayout.NORTH)
                    add(JScrollPane(content), BorderLayout.CENTER)
                }, constraints)
            }
            leftComponent.minimumSize = Dimension(0, 0)
            rightComponent.minimumSize = Dimension(0, 0)
            installAdaptiveSplit(this, threshold = 560, horizontalWeight = 0.28, verticalWeight = 0.38)
        }, BorderLayout.CENTER)
    }

    init {
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !loading) {
                commitEditor()
                loadSelection()
            }
        }
        updateEditorEnabled(false)
    }

    fun reset(rows: List<PromptEditorRow>) {
        loading = true
        editingIndex = -1
        model.clear()
        rows.forEach(model::addElement)
        list.selectedIndex = if (model.size() > 0) 0 else -1
        loading = false
        loadSelection()
    }

    fun rows(): List<PromptEditorRow> {
        commitEditor()
        return (0 until model.size()).map(model::getElementAt)
    }

    private fun addPrompt() {
        commitEditor()
        loading = true
        val index = model.size()
        val shortcut = nextPromptShortcut()
        model.addElement(PromptEditorRow(UUID.randomUUID().toString(), "New prompt", shortcut, ""))
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        loading = false
        loadSelection()
        name.requestFocusInWindow()
        name.selectAll()
    }

    private fun removePrompt() {
        commitEditor()
        val index = list.selectedIndex
        if (index < 0) return
        loading = true
        editingIndex = -1
        model.remove(index)
        list.selectedIndex = when {
            model.isEmpty -> -1
            index < model.size() -> index
            else -> model.size() - 1
        }
        loading = false
        loadSelection()
    }

    private fun commitEditor() {
        if (loading || editingIndex !in 0 until model.size()) return
        model.set(
            editingIndex,
            model.getElementAt(editingIndex).copy(
                name = name.text,
                shortcut = shortcut.text.trim().removePrefix("!"),
                content = content.text,
            ),
        )
    }

    private fun loadSelection() {
        loading = true
        editingIndex = list.selectedIndex
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt)
        name.text = row?.name.orEmpty()
        shortcut.text = row?.shortcut.orEmpty()
        content.text = row?.content.orEmpty()
        content.caretPosition = 0
        updateEditorEnabled(row != null)
        removeButton.isEnabled = row != null
        loading = false
    }

    private fun updateEditorEnabled(enabled: Boolean) {
        name.isEnabled = enabled
        shortcut.isEnabled = enabled
        content.isEnabled = enabled
    }

    private fun nextPromptShortcut(): String {
        val existing = (0 until model.size()).map { model.getElementAt(it).shortcut.lowercase() }.toSet()
        var index = 1
        while (true) {
            val candidate = if (index == 1) "prompt" else "prompt-$index"
            if (candidate.lowercase() !in existing) return candidate
            index++
        }
    }
}

private class SkillLibraryEditor(
    private val project: Project?,
) {
    private val model = DefaultListModel<SkillEditorRow>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val row = value as? SkillEditorRow
                val label = row?.let {
                    val state = if (it.enabled) "已启用" else "未启用"
                    "${it.name.ifBlank { "Skill library" }}  ·  $state"
                } ?: value
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
            }
        }
    }
    private val enabled = JCheckBox("启用此 Skill 来源")
    private val name = editorTextField()
    private val path = editorTextField()
    private val browseButton = JButton("选择…").apply { isEnabled = false }
    private val removeButton = JButton("删除").apply { isEnabled = false }
    private val scanButton = JButton("重新扫描").apply { isEnabled = false }
    private val scanStatus = JLabel("请选择 SKILL.md 文件或 Skill 目录。")
    private var editingIndex = -1
    private var loading = false

    val component: JComponent = paddedPanel().apply {
        layout = BorderLayout(0, 8)
        add(JPanel(BorderLayout(0, 8)).apply {
            isOpaque = false
            add(description("添加 SKILL.md 文件或包含多个 Skill 子目录的目录；保存后 Agent 可通过 Skill 工具发现和加载。"), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JButton("添加本地 Skill…").also { button -> button.addActionListener { chooseSkillPath(addNew = true) } })
                add(JButton("手动添加").also { button -> button.addActionListener { addManualSource() } })
                add(removeButton.also { button -> button.addActionListener { removeSource() } })
                add(scanButton.also { button -> button.addActionListener { scanSelected() } })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            resizeWeight = 0.30
            leftComponent = JScrollPane(list).apply { minimumSize = Dimension(120, 120) }
            rightComponent = skillDetailsPanel()
            leftComponent.minimumSize = Dimension(0, 0)
            rightComponent.minimumSize = Dimension(0, 0)
            installAdaptiveSplit(this, threshold = 620, horizontalWeight = 0.30, verticalWeight = 0.34)
        }, BorderLayout.CENTER)
    }

    init {
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !loading) {
                commitEditor()
                loadSelection()
            }
        }
        browseButton.addActionListener { chooseSkillPath(addNew = false) }
        updateEditorEnabled(false)
    }

    fun reset(rows: List<SkillEditorRow>) {
        loading = true
        editingIndex = -1
        model.clear()
        rows.forEach(model::addElement)
        list.selectedIndex = if (model.size() > 0) 0 else -1
        loading = false
        loadSelection()
    }

    fun rows(): List<SkillEditorRow> {
        commitEditor()
        return (0 until model.size()).map(model::getElementAt)
    }

    private fun skillDetailsPanel(): JComponent = JPanel(GridBagLayout()).apply {
        border = EmptyBorder(0, 12, 0, 0)
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(0, 0, 8, 0)
        }
        constraints.gridy = 0
        add(enabled, constraints)
        constraints.gridy++
        add(labeledField("名称", this@SkillLibraryEditor.name), constraints)
        constraints.gridy++
        add(labeledField("路径", JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(path, BorderLayout.CENTER)
            add(browseButton, BorderLayout.EAST)
        }), constraints)
        constraints.gridy++
        add(scanStatus, constraints)
        constraints.gridy++
        add(description("支持项目相对路径、绝对路径和 ~/；扫描目录本身及其一级子目录中的 SKILL.md。"), constraints)
        constraints.gridy++
        constraints.weighty = 1.0
        add(JPanel().apply { isOpaque = false }, constraints)
    }

    private fun addManualSource() {
        commitEditor()
        val index = model.size()
        model.addElement(
            SkillEditorRow(UUID.randomUUID().toString(), nextSkillName(), "", false),
        )
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        loadSelection()
        path.requestFocusInWindow()
    }

    private fun chooseSkillPath(addNew: Boolean) {
        val chooser = JFileChooser(initialSkillDirectory()).apply {
            dialogTitle = "选择 SKILL.md 或 Skill 目录"
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isAcceptAllFileFilterUsed = false
            fileFilter = object : FileFilter() {
                override fun accept(file: File): Boolean =
                    file.isDirectory || file.name.equals("SKILL.md", ignoreCase = true)

                override fun getDescription(): String = "Skill 目录或 SKILL.md"
            }
        }
        if (chooser.showOpenDialog(component) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile.toPath().toAbsolutePath().normalize()
        if (addNew || editingIndex !in 0 until model.size()) {
            commitEditor()
            val index = model.size()
            model.addElement(
                SkillEditorRow(
                    id = UUID.randomUUID().toString(),
                    name = skillNameFor(selected),
                    path = selected.toString(),
                    enabled = true,
                ),
            )
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
            loadSelection()
        } else {
            path.text = selected.toString()
            if (name.text.isBlank() || name.text.startsWith("Skill library")) {
                name.text = skillNameFor(selected)
            }
            enabled.isSelected = true
            commitEditor()
            list.repaint()
        }
        scanSelected()
    }

    private fun removeSource() {
        commitEditor()
        val index = list.selectedIndex
        if (index < 0) return
        loading = true
        editingIndex = -1
        model.remove(index)
        list.selectedIndex = when {
            model.isEmpty -> -1
            index < model.size() -> index
            else -> model.size() - 1
        }
        loading = false
        loadSelection()
    }

    private fun commitEditor() {
        if (loading || editingIndex !in 0 until model.size()) return
        model.set(
            editingIndex,
            model.getElementAt(editingIndex).copy(
                name = name.text,
                path = path.text,
                enabled = enabled.isSelected,
            ),
        )
    }

    private fun loadSelection() {
        loading = true
        editingIndex = list.selectedIndex
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt)
        enabled.isSelected = row?.enabled == true
        name.text = row?.name.orEmpty()
        path.text = row?.path.orEmpty()
        updateEditorEnabled(row != null)
        removeButton.isEnabled = row != null
        scanButton.isEnabled = row != null
        scanStatus.text = if (row == null) "请选择 SKILL.md 文件或 Skill 目录。" else "点击“重新扫描”验证此来源。"
        loading = false
    }

    private fun updateEditorEnabled(value: Boolean) {
        enabled.isEnabled = value
        name.isEnabled = value
        path.isEnabled = value
        browseButton.isEnabled = value
    }

    private fun scanSelected() {
        commitEditor()
        val row = editingIndex.takeIf { it in 0 until model.size() }?.let(model::getElementAt) ?: return
        val inspection = inspectSkillSource(row.path, project?.basePath)
        scanStatus.text = inspection.message
        scanStatus.foreground = if (inspection.isValid) {
            UIManager.getColor("Label.foreground")
        } else {
            UIManager.getColor("Component.errorFocusColor") ?: Color(0xC7, 0x54, 0x50)
        }
    }

    private fun initialSkillDirectory(): File {
        val selected = path.text.trim().takeIf(String::isNotEmpty)
            ?.let { runCatching { resolveSkillSourcePath(it, project?.basePath) }.getOrNull() }
        return when {
            selected != null && Files.isDirectory(selected) -> selected.toFile()
            selected?.parent != null && Files.isDirectory(selected.parent) -> selected.parent.toFile()
            project?.basePath != null -> File(project.basePath!!)
            else -> File(System.getProperty("user.home"))
        }
    }

    private fun nextSkillName(): String {
        val names = (0 until model.size()).map { model.getElementAt(it).name.lowercase() }.toSet()
        var index = 1
        while (true) {
            val candidate = if (index == 1) "Skill library" else "Skill library $index"
            if (candidate.lowercase() !in names) return candidate
            index++
        }
    }
}

private data class PlatformEditorForm(
    val historyEnabled: Boolean,
    val historyRetention: Int,
    val usageRetentionDays: Int,
    val agentRuntime: AgentRuntimeEditorForm,
    val sandboxMode: SandboxMode,
    val mcpServers: List<McpEditorRow>,
    val commitAi: CommitEditorForm,
    val prompts: List<PromptEditorRow>,
    val skills: List<SkillEditorRow>,
) {
    fun writeTo(state: OmniCodePlatformSettingsState) {
        state.historyEnabled = historyEnabled
        state.historyRetention = historyRetention.coerceIn(1, 1_000)
        state.usageRetentionDays = usageRetentionDays.coerceIn(1, 3_650)
        state.agentMaxIterations = agentRuntime.maxIterations
        state.agentMaxToolCalls = agentRuntime.maxToolCalls
        state.agentMaxWallTimeSeconds = agentRuntime.maxWallTimeSeconds
        state.agentMaxToolTimeSeconds = agentRuntime.maxToolTimeSeconds
        state.agentMaxInputTokens = agentRuntime.maxInputTokens
        state.agentMaxOutputTokens = agentRuntime.maxOutputTokens
        state.agentProviderMaxAttempts = agentRuntime.providerMaxAttempts
        state.agentMaxRunCostUsd = agentRuntime.maxRunCostUsd
        state.agentCostWarningPercent = agentRuntime.costWarningPercent
        state.sandboxMode = sandboxMode.name
        state.mcpServers = mcpServers.map { row ->
            McpServerState().apply {
                id = row.id
                name = row.name.trim().ifBlank { "MCP Server" }
                enabled = row.enabled
                transport = row.transport.id
                command = row.command.trim()
                arguments = row.arguments.trim()
                environmentKeys = normalizeEnvironmentKeys(row.environmentKeys).joinToString(", ")
                workingDirectory = row.workingDirectory.trim().ifBlank { "." }
                url = row.url.trim()
                httpAuthMode = row.httpAuthMode.id
                oauthClientId = row.oauthClientId.trim()
                oauthScopes = normalizeOAuthScopes(row.oauthScopes).joinToString(" ")
            }
        }.toMutableList()
        val retainedMcpIds = state.mcpServers.mapTo(hashSetOf()) { it.id }
        state.mcpLaunchTrusts.removeIf { it.serverId !in retainedMcpIds }
        state.promptTemplates = prompts.map { row ->
            PromptTemplateState().apply {
                id = row.id
                name = row.name.trim().ifBlank { "Prompt" }
                shortcut = row.shortcut.trim().removePrefix("!").ifBlank { "prompt" }
                content = row.content
            }
        }.toMutableList()
        state.skillSources = skills.map { row ->
            SkillSourceState().apply {
                id = row.id
                name = row.name.trim().ifBlank { "Skill library" }
                path = row.path.trim()
                enabled = row.enabled
            }
        }.toMutableList()
        state.commitAiEnabled = commitAi.enabled
        state.commitIncludeBody = commitAi.includeBody
        state.commitLanguage = commitAi.language.trim().ifBlank { "Auto" }
        state.commitPrompt = commitAi.prompt.trim().ifBlank { DEFAULT_COMMIT_PROMPT }
    }

    companion object {
        fun default(): PlatformEditorForm = PlatformEditorForm(
            historyEnabled = true,
            historyRetention = 100,
            usageRetentionDays = 365,
            agentRuntime = AgentRuntimeEditorForm.default(),
            sandboxMode = SandboxMode.DEFAULT,
            mcpServers = emptyList(),
            commitAi = CommitEditorForm(true, true, "Auto", DEFAULT_COMMIT_PROMPT),
            prompts = emptyList(),
            skills = emptyList(),
        )

        fun from(snapshot: OmniCodePlatformSnapshot): PlatformEditorForm = PlatformEditorForm(
            historyEnabled = snapshot.historyEnabled,
            historyRetention = snapshot.historyRetention,
            usageRetentionDays = snapshot.usageRetentionDays,
            agentRuntime = AgentRuntimeEditorForm(
                maxIterations = snapshot.agentRuntime.maxIterations,
                maxToolCalls = snapshot.agentRuntime.maxToolCalls,
                maxWallTimeSeconds = snapshot.agentRuntime.maxWallTimeSeconds,
                maxToolTimeSeconds = snapshot.agentRuntime.maxToolTimeSeconds,
                maxInputTokens = snapshot.agentRuntime.maxInputTokens,
                maxOutputTokens = snapshot.agentRuntime.maxOutputTokens,
                providerMaxAttempts = snapshot.agentRuntime.providerMaxAttempts,
                maxRunCostUsd = snapshot.agentRuntime.maxRunCostUsd ?: 0.0,
                costWarningPercent = (snapshot.agentRuntime.costWarningRatio * 100).toInt().coerceIn(1, 100),
            ),
            sandboxMode = snapshot.sandboxMode,
            mcpServers = snapshot.mcpServers.map { server ->
                McpEditorRow(
                    id = server.id,
                    name = server.name,
                    enabled = server.enabled,
                    transport = server.transport,
                    command = server.command,
                    arguments = renderCommandLine(server.arguments),
                    environmentKeys = server.environmentKeys.joinToString(", "),
                    workingDirectory = server.workingDirectory,
                    url = server.url,
                    httpAuthMode = server.httpAuthMode,
                    oauthClientId = server.oauthClientId,
                    oauthScopes = server.oauthScopes.joinToString(" "),
                )
            },
            commitAi = CommitEditorForm(
                snapshot.commitAi.enabled,
                snapshot.commitAi.includeBody,
                snapshot.commitAi.language,
                snapshot.commitAi.prompt,
            ),
            prompts = snapshot.promptTemplates.map { prompt ->
                PromptEditorRow(prompt.id, prompt.name, prompt.shortcut, prompt.content)
            },
            skills = snapshot.skillSources.map { skill ->
                SkillEditorRow(skill.id, skill.name, skill.path, skill.enabled)
            },
        )
    }
}

private data class AgentRuntimeEditorForm(
    val maxIterations: Int,
    val maxToolCalls: Int,
    val maxWallTimeSeconds: Int,
    val maxToolTimeSeconds: Int,
    val maxInputTokens: Long,
    val maxOutputTokens: Long,
    val providerMaxAttempts: Int,
    val maxRunCostUsd: Double,
    val costWarningPercent: Int,
) {
    companion object {
        fun default(): AgentRuntimeEditorForm = AgentRuntimeEditorForm(
            maxIterations = 24,
            maxToolCalls = 32,
            maxWallTimeSeconds = 600,
            maxToolTimeSeconds = 300,
            maxInputTokens = 250_000,
            maxOutputTokens = 32_000,
            providerMaxAttempts = 3,
            maxRunCostUsd = 0.0,
            costWarningPercent = 80,
        )
    }
}

private data class McpEditorRow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: McpTransport,
    val command: String,
    val arguments: String,
    val environmentKeys: String,
    val workingDirectory: String,
    val url: String,
    val httpAuthMode: McpHttpAuthMode,
    val oauthClientId: String,
    val oauthScopes: String,
)

private data class CommitEditorForm(
    val enabled: Boolean,
    val includeBody: Boolean,
    val language: String,
    val prompt: String,
)

private data class PromptEditorRow(
    val id: String,
    val name: String,
    val shortcut: String,
    val content: String,
)

private data class SkillEditorRow(
    val id: String,
    val name: String,
    val path: String,
    val enabled: Boolean,
)

internal data class SkillSourceInspection(
    val isValid: Boolean,
    val discoveredSkills: Int,
    val message: String,
)

internal fun inspectSkillSource(value: String, projectBasePath: String?): SkillSourceInspection {
    if (value.isBlank()) return SkillSourceInspection(false, 0, "尚未填写 Skill 路径。")
    val source = runCatching { resolveSkillSourcePath(value, projectBasePath) }
        .getOrElse { return SkillSourceInspection(false, 0, "Skill 路径格式无效。") }
    if (!Files.exists(source)) return SkillSourceInspection(false, 0, "路径不存在：$source")
    if (!Files.isReadable(source)) return SkillSourceInspection(false, 0, "路径不可读取：$source")
    if (Files.isRegularFile(source)) {
        return if (source.fileName.toString().equals("SKILL.md", ignoreCase = true)) {
            SkillSourceInspection(true, 1, "扫描成功 · 发现 1 个 Skill")
        } else {
            SkillSourceInspection(false, 0, "请选择名为 SKILL.md 的文件。")
        }
    }
    if (!Files.isDirectory(source)) return SkillSourceInspection(false, 0, "该路径不是文件或目录。")
    val discovered = runCatching {
        var count = if (Files.isRegularFile(source.resolve("SKILL.md"))) 1 else 0
        Files.newDirectoryStream(source).use { children ->
            children.forEach { child ->
                if (Files.isDirectory(child) && Files.isRegularFile(child.resolve("SKILL.md"))) count++
            }
        }
        count
    }.getOrElse { error ->
        return SkillSourceInspection(false, 0, "扫描失败：${error.message ?: error::class.java.simpleName}")
    }
    return if (discovered > 0) {
        SkillSourceInspection(true, discovered, "扫描成功 · 发现 $discovered 个 Skill")
    } else {
        SkillSourceInspection(false, 0, "未发现 SKILL.md；支持目录本身或一级子目录。")
    }
}

internal fun resolveSkillSourcePath(value: String, projectBasePath: String?): Path {
    val trimmed = value.trim()
    val expanded = if (trimmed == "~" || trimmed.startsWith("~/")) {
        Path.of(System.getProperty("user.home")).resolve(trimmed.removePrefix("~/").removePrefix("~"))
    } else {
        Path.of(trimmed)
    }
    if (expanded.isAbsolute) return expanded.normalize()
    val base = projectBasePath?.let(Path::of) ?: Path.of(System.getProperty("user.dir"))
    return base.resolve(expanded).normalize()
}

private fun skillNameFor(path: Path): String = when {
    path.fileName?.toString()?.equals("SKILL.md", ignoreCase = true) == true ->
        path.parent?.fileName?.toString().orEmpty()
    else -> path.fileName?.toString().orEmpty()
}.ifBlank { "Skill library" }

private fun clearRemovedMcpCredentials(
    previous: List<McpServerConfig>,
    saved: List<McpServerConfig>,
) {
    val retainedIds = saved.mapTo(hashSetOf()) { it.id }
    val environmentCredentials = McpEnvironmentCredentialStore.getInstance()
    val bearerCredentials = McpHttpCredentialStore.getInstance()
    val oauthSessions = McpOAuthSessionManager()
    previous.asSequence().filter { it.id !in retainedIds }.forEach { removed ->
        removed.environmentKeys.forEach { key ->
            runCatching { environmentCredentials.clear(removed.id, key) }
        }
        runCatching { bearerCredentials.clear(removed.id) }
        runCatching { oauthSessions.logout(removed.id) }
    }
}

@Throws(ConfigurationException::class)
private fun validatePlatformForm(form: PlatformEditorForm) {
    val mcpNames = mutableSetOf<String>()
    form.mcpServers.forEachIndexed { index, server ->
        val label = server.name.trim().ifBlank { "MCP server ${index + 1}" }
        if (!mcpNames.add(label.lowercase())) {
            throw PlatformFormValidationException(2, "MCP 服务器名称“$label”重复，请使用唯一名称。")
        }
        when (server.transport) {
            McpTransport.STDIO -> {
                if (server.enabled && server.command.isBlank()) {
                    throw PlatformFormValidationException(2, "$label 已启用，请填写启动命令。")
                }
                try {
                    parseCommandLine(server.arguments)
                } catch (_: IllegalArgumentException) {
                    throw PlatformFormValidationException(2, "$label 的参数存在未闭合的引号或转义。")
                }
                val invalidEnvironmentKey = normalizeEnvironmentKeys(server.environmentKeys)
                    .firstOrNull { !ENVIRONMENT_KEY.matches(it) }
                if (invalidEnvironmentKey != null) {
                    throw PlatformFormValidationException(2, "$label 的环境变量名无效：$invalidEnvironmentKey")
                }
            }
            McpTransport.HTTP -> if (server.enabled || server.url.isNotBlank()) {
                if (server.url.isBlank()) {
                    throw PlatformFormValidationException(2, "$label 已启用，请填写 HTTP Endpoint。")
                }
                runCatching { dev.omnicode.mcp.validateMcpHttpEndpoint(server.url) }.getOrElse { error ->
                    throw PlatformFormValidationException(
                        2,
                        "$label 的 HTTP Endpoint 无效：${error.message ?: "URL 格式错误"}",
                    )
                }
                if (server.httpAuthMode == McpHttpAuthMode.OAUTH) {
                    if (server.oauthClientId.length > 2_048 || server.oauthClientId.any(Char::isISOControl)) {
                        throw PlatformFormValidationException(2, "$label 的 OAuth Client ID 无效。")
                    }
                    val rawScopes = server.oauthScopes.split(Regex("[\\s,]+")).filter(String::isNotBlank)
                    val invalidScope = rawScopes.firstOrNull { !OAUTH_SCOPE_TOKEN.matches(it) }
                    if (invalidScope != null || rawScopes.size > 128) {
                        throw PlatformFormValidationException(2, "$label 的 OAuth Scope 无效或过多。")
                    }
                }
            }
        }
    }

    val shortcuts = mutableSetOf<String>()
    form.prompts.forEachIndexed { index, prompt ->
        if (prompt.name.isBlank()) throw PlatformFormValidationException(4, "提示词 ${index + 1} 必须填写名称。")
        val shortcut = prompt.shortcut.trim().removePrefix("!")
        if (!PROMPT_SHORTCUT.matches(shortcut)) {
            throw PlatformFormValidationException(4, "提示词“${prompt.name}”的快捷词只能包含字母、数字、_ 或 -。")
        }
        if (!shortcuts.add(shortcut.lowercase())) {
            throw PlatformFormValidationException(4, "提示词快捷词 !$shortcut 重复。")
        }
        if (prompt.content.isBlank()) throw PlatformFormValidationException(4, "提示词“${prompt.name}”必须填写内容。")
    }

    form.skills.forEachIndexed { index, skill ->
        if (skill.name.isBlank()) throw PlatformFormValidationException(5, "Skill 来源 ${index + 1} 必须填写名称。")
        if (skill.enabled && skill.path.isBlank()) {
            throw PlatformFormValidationException(5, "Skill 来源“${skill.name}”已启用，请填写路径。")
        }
    }

    if (form.commitAi.enabled && form.commitAi.prompt.isBlank()) {
        throw PlatformFormValidationException(3, "Commit AI 已启用，请填写提示词。")
    }
}

private class PlatformFormValidationException(
    val sectionIndex: Int,
    message: String,
) : ConfigurationException(message)

internal fun renderCommandLine(arguments: List<String>): String = arguments.joinToString(" ") { argument ->
    when {
        argument.isNotEmpty() && SAFE_COMMAND_ARGUMENT.matches(argument) -> argument
        else -> "\"${argument.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}

private fun normalizeEnvironmentKeys(value: String): List<String> = value
    .split(',', '\n')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

private fun editorTextField(): JTextField = JTextField().apply {
    minimumSize = Dimension(0, preferredSize.height)
}

private fun installAdaptiveSplit(
    split: JSplitPane,
    threshold: Int,
    horizontalWeight: Double,
    verticalWeight: Double,
) {
    fun update() {
        val next = if (split.width in 1 until threshold) {
            JSplitPane.VERTICAL_SPLIT
        } else {
            JSplitPane.HORIZONTAL_SPLIT
        }
        val weight = if (next == JSplitPane.VERTICAL_SPLIT) verticalWeight else horizontalWeight
        if (split.orientation != next) split.orientation = next
        split.resizeWeight = weight
        SwingUtilities.invokeLater {
            if (split.isDisplayable) split.setDividerLocation(weight)
        }
    }
    split.addComponentListener(object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = update()
    })
    SwingUtilities.invokeLater(::update)
}

private fun paddedPanel(): JPanel = JPanel().apply {
    border = EmptyBorder(14, 14, 14, 14)
}

private fun sectionTitle(text: String): JLabel = JLabel(text).apply {
    font = font.deriveFont(Font.BOLD)
}

private fun description(text: String): JTextArea = JTextArea(text).apply {
    isEditable = false
    isFocusable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    border = null
    foreground = UIManager.getColor("Label.foreground")
}

private fun warningDescription(text: String): JTextArea = description(text).apply {
    foreground = UIManager.getColor("Component.warningFocusColor") ?: Color(0xB2, 0x6A, 0x00)
}

private fun labeledField(label: String, field: JComponent): JComponent = JPanel(BorderLayout(8, 0)).apply {
    isOpaque = false
    add(JLabel(label), BorderLayout.WEST)
    add(field, BorderLayout.CENTER)
}

private fun unitField(field: JComponent, unit: String): JComponent = JPanel(BorderLayout(8, 0)).apply {
    isOpaque = false
    add(field, BorderLayout.WEST)
    add(JLabel(unit), BorderLayout.CENTER)
}

private suspend fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    return suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            if (!continuation.isActive) return@invokeLater
            runCatching(block).fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(it) },
            )
        }
    }
}

private val SAFE_COMMAND_ARGUMENT = Regex("[A-Za-z0-9_@%+=:,./-]+")
private val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val PROMPT_SHORTCUT = Regex("[A-Za-z0-9_-]+")
private const val MCP_TOOLBAR_COMPACT_THRESHOLD = 680
private const val MCP_COMPACT_TOOLBAR_COLUMNS = 3
