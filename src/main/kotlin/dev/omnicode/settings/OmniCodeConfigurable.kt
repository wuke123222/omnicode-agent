package dev.omnicode.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.omnicode.provider.ModelDiscoveryResult
import dev.omnicode.provider.ProviderConnection
import dev.omnicode.provider.ProviderException
import dev.omnicode.provider.ProviderModelDiscovery
import dev.omnicode.provider.ProviderPreset
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.provider.ProviderProxyMode
import dev.omnicode.provider.ReasoningEffort
import dev.omnicode.provider.classifyModelCatalogKind
import dev.omnicode.provider.modelCatalogView
import dev.omnicode.provider.canonicalModelApiOrigin
import dev.omnicode.provider.modelApiBaseUrlValidationError
import dev.omnicode.provider.reasoningEffortOptions
import dev.omnicode.provider.recommendedOutputTokenFloor
import dev.omnicode.service.ProviderModelCatalogService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.SwingConstants
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class OmniCodeConfigurable : SearchableConfigurable, Configurable.NoScroll {
    private var settingsPanel: SettingsPanel? = null

    override fun getId(): String = "dev.omnicode.settings"

    override fun getDisplayName(): String = "OmniCode Agent"

    override fun createComponent(): JComponent {
        val panel = settingsPanel ?: SettingsPanel(OmniCodeCredentialStore.getInstance()).also {
            settingsPanel = it
        }
        val settings = OmniCodeSettingsService.getInstance()
        panel.resetFrom(settings.snapshot(), settings.profileSnapshots(), settings.visionModels())
        return panel.component
    }

    override fun getPreferredFocusedComponent(): JComponent? = settingsPanel?.providerCombo

    override fun isModified(): Boolean {
        val panel = settingsPanel ?: return false
        return panel.settingsModified() || panel.credentialsModified()
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val panel = settingsPanel ?: return
        try {
            panel.prepareApply()
        } catch (error: CredentialInputFormatException) {
            throw ConfigurationException(error.message ?: "API Key 输入格式无效。")
        }
        val snapshots = panel.profileSnapshots()
        snapshots.firstNotNullOfOrNull(::providerValidationError)?.let { message ->
            throw ConfigurationException(message)
        }

        try {
            panel.saveModifiedCredentials()
        } catch (error: CredentialOriginMismatchException) {
            throw ConfigurationException(error.message ?: "Base URL 已改变，请重新确认 API Key。")
        } catch (error: UnsafeEnvironmentCredentialTargetException) {
            throw ConfigurationException(error.message ?: "环境变量中的 API Key 不能用于该远程地址。")
        } catch (_: RuntimeException) {
            throw ConfigurationException("无法将供应商凭据保存到 IDE Password Safe。")
        }

        OmniCodeSettingsService.getInstance().updateProfiles(panel.selectedProviderId(), snapshots)
        OmniCodeSettingsService.getInstance().updateVisionModels(panel.visionModels())
        ProviderModelCatalogService.getInstance().invalidate()
        panel.markApplied()
    }

    override fun reset() {
        val settings = OmniCodeSettingsService.getInstance()
        settingsPanel?.resetFrom(settings.snapshot(), settings.profileSnapshots(), settings.visionModels())
    }

    override fun disposeUIResources() {
        settingsPanel?.dispose()
        settingsPanel = null
    }

    internal class SettingsPanel(
        private val credentialStore: OmniCodeCredentialStore,
    ) {
        val providerCombo = ComboBox(ProviderPresets.all.toTypedArray())
        private val baseUrlField = compactTextField(24)
        private val modelCombo = ComboBox<String>().apply {
            isEditable = true
            minimumSize = Dimension(0, preferredSize.height)
            toolTipText = "可从列表选择，也可直接输入供应商支持的模型 ID"
        }
        private val showAllModelsCheckBox = JBCheckBox("显示全部模型（含非对话用途）").apply {
            isOpaque = false
            isEnabled = false
            toolTipText = "默认隐藏明确用于图片、Embedding、音频、实时、审核等用途的专用模型"
        }
        private val reasoningEffortCombo = ComboBox<ReasoningEffort>().apply {
            minimumSize = Dimension(0, preferredSize.height)
            toolTipText = "按当前供应商与模型显示可用推理档位"
        }
        private val reasoningEffortHintLabel = hintLabel(
            "Auto 使用模型默认值；更高档位会增加推理深度、延迟和 Token 消耗，但不保证用满上限。",
        )
        private val visionModelField = compactTextField(24)
        private val regionField = compactTextField(18)
        private val apiVersionField = compactTextField(18)
        private val proxyModeCombo = ComboBox(ProviderProxyMode.entries.toTypedArray())
        private val requestTimeoutSpinner = JSpinner(SpinnerNumberModel(0, 0, 1_800, 5))
        private val maxOutputTokensSpinner = JSpinner(
            SpinnerNumberModel(
                OmniCodeSettingsDefaults.MAX_OUTPUT_TOKENS,
                OmniCodeSettingsDefaults.MIN_OUTPUT_TOKENS,
                OmniCodeSettingsDefaults.MAX_ALLOWED_OUTPUT_TOKENS,
                256,
            ),
        )
        private val apiKeyField = compactPasswordField()
        private val secondarySecretField = compactPasswordField()
        private val sessionTokenField = compactPasswordField()
        private val restoreEndpointButton = JButton("恢复默认")
        private val refreshModelsButton = JButton("保存并加载模型")
        private val credentialStatusLabel = hintLabel()
        private val passwordSafeLabel = hintLabel(
            "支持纯 Key、KEY=value 或 JSON；Key 会按 Base URL 的 Origin 绑定并写入 IDE Password Safe。",
        )
        private val modelStatusLabel = hintLabel("保存 API Key 后，将从供应商接口加载当前账号可用的模型。")

        private lateinit var apiKeyRow: FormRow
        private lateinit var secondarySecretRow: FormRow
        private lateinit var sessionTokenRow: FormRow
        private lateinit var regionRow: FormRow
        private lateinit var apiVersionRow: FormRow

        private val credentialBaselines = mutableMapOf<String, ProviderSecrets>()
        private val credentialDrafts = mutableMapOf<String, ProviderSecrets>()
        private val credentialEnvironmentSources = mutableMapOf<String, String>()
        private val blockedCredentialEnvironmentSources = mutableMapOf<String, String>()
        private val profileBaselines = mutableMapOf<String, OmniCodeSettingsSnapshot>()
        private val profileDrafts = mutableMapOf<String, OmniCodeSettingsSnapshot>()
        private val visionModelBaselines = mutableMapOf<String, String>()
        private val visionModelDrafts = mutableMapOf<String, String>()
        private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var discoveryJob: Job? = null
        private var discoveryGeneration = 0L
        private var credentialLoadGeneration = 0L
        private var credentialsLoadingProviderId: String? = null
        private var activeProviderId: String = OmniCodeSettingsDefaults.providerId
        private var baselineProviderId: String = OmniCodeSettingsDefaults.providerId
        private var updatingUi = false
        private var updatingCredentialFields = false
        private var disposed = false
        private var rawModelChoices: List<String> = emptyList()

        val component: JPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 12, 12)
        }

        init {
            providerCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(
                    list,
                    (value as? ProviderPreset)?.displayName ?: value,
                    index,
                    isSelected,
                    cellHasFocus,
                )
            }
            modelCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val component = super.getListCellRendererComponent(
                        list,
                        value,
                        index,
                        isSelected,
                        cellHasFocus,
                    )
                    val modelId = value?.toString().orEmpty()
                    val kind = classifyModelCatalogKind(modelId)
                    if (modelId.isNotBlank() && !kind.codingChatCandidate) {
                        text = "$modelId   · ${kind.displayName}"
                    }
                    return component
                }
            }
            reasoningEffortCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(
                    list,
                    reasoningEffortLabel(value as? ReasoningEffort ?: ReasoningEffort.AUTO),
                    index,
                    isSelected,
                    cellHasFocus,
                )
            }
            proxyModeCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(
                    list,
                    (value as? ProviderProxyMode)?.displayName ?: value,
                    index,
                    isSelected,
                    cellHasFocus,
                )
            }

            val endpointPanel = JPanel(BorderLayout(8, 0)).apply {
                add(baseUrlField, BorderLayout.CENTER)
                add(restoreEndpointButton, BorderLayout.EAST)
                minimumSize = Dimension(0, preferredSize.height)
            }
            val modelPanel = JPanel(BorderLayout(8, 0)).apply {
                add(modelCombo, BorderLayout.CENTER)
                add(refreshModelsButton, BorderLayout.EAST)
                add(showAllModelsCheckBox, BorderLayout.SOUTH)
                minimumSize = Dimension(0, preferredSize.height)
            }

            var row = 0
            addGroupHeader(row++, "供应商", first = true)
            addRow(row++, "供应商", providerCombo)
            addRow(row++, "Base URL", endpointPanel)

            addGroupHeader(row++, "凭据")
            apiKeyRow = addRow(row++, "API Key", apiKeyField)
            secondarySecretRow = addRow(row++, "Secondary secret", secondarySecretField)
            sessionTokenRow = addRow(row++, "Session token", sessionTokenField)
            addHint(row++, credentialStatusLabel)
            addHint(row++, passwordSafeLabel, bottomInset = 4)

            addGroupHeader(row++, "模型")
            addRow(row++, "模型", modelPanel)
            addHint(row++, modelStatusLabel, bottomInset = 4)
            addRow(row++, "推理强度", reasoningEffortCombo)
            addHint(row++, reasoningEffortHintLabel, bottomInset = 4)

            addGroupHeader(row++, "高级")
            addRow(row++, "视觉辅助模型", visionModelField)
            addHint(row++, hintLabel("可选：主模型不支持图片时，使用同一供应商的该模型生成图片说明；发送前会征求确认。"))
            regionRow = addRow(row++, "Region", regionField)
            apiVersionRow = addRow(row++, "API Version", apiVersionField)
            addRow(row++, "最大输出 Token", maxOutputTokensSpinner)
            addRow(row++, "网络连接", proxyModeCombo)
            addHint(row++, hintLabel("按供应商保存：直连不会使用系统/IDE代理；系统代理会随运行中的代理变化自动刷新。"))
            addRow(row++, "请求超时（秒）", requestTimeoutSpinner)
            addHint(row++, hintLabel("0 表示按推理强度自动设置；显式值同时约束模型列表和模型请求。"))

            component.add(
                JPanel(),
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    gridwidth = 2
                    weightx = 1.0
                    weighty = 1.0
                    fill = GridBagConstraints.BOTH
                },
            )

            providerCombo.addActionListener {
                if (!updatingUi) switchProvider()
            }
            restoreEndpointButton.addActionListener {
                baseUrlField.text = selectedProvider().defaultBaseUrl
            }
            refreshModelsButton.addActionListener { refreshModels() }
            modelCombo.addActionListener {
                if (!updatingUi) refreshReasoningEffortOptions()
            }
            showAllModelsCheckBox.addActionListener {
                if (!updatingUi) rebuildModelChoices(selectedModel())
            }
            reasoningEffortCombo.addActionListener {
                if (!updatingUi) applyReasoningEffortSelection()
            }
            maxOutputTokensSpinner.addChangeListener {
                if (!updatingUi) enforceReasoningOutputFloor()
            }
            restoreEndpointButton.toolTipText = "恢复该供应商的默认地址"
            listOf(apiKeyField, secondarySecretField, sessionTokenField).forEach(::installCredentialListener)
        }

        fun resetFrom(
            snapshot: OmniCodeSettingsSnapshot,
            savedProfiles: Map<String, OmniCodeSettingsSnapshot>,
            savedVisionModels: Map<String, String>,
        ) {
            cancelModelRefresh()
            updatingUi = true
            try {
                clearPasswordFields()
                credentialBaselines.clear()
                credentialDrafts.clear()
                credentialEnvironmentSources.clear()
                blockedCredentialEnvironmentSources.clear()
                profileBaselines.clear()
                profileBaselines.putAll(savedProfiles)
                profileBaselines[snapshot.providerId] = snapshot
                profileDrafts.clear()
                profileDrafts.putAll(profileBaselines)
                visionModelBaselines.clear()
                visionModelBaselines.putAll(savedVisionModels)
                visionModelDrafts.clear()
                visionModelDrafts.putAll(savedVisionModels)

                val preset = ProviderPresets.byId(snapshot.providerId)
                activeProviderId = preset.id
                baselineProviderId = preset.id
                providerCombo.selectedItem = preset
                showProfile(snapshot)
                updateProviderSpecificFields(preset)
                beginCredentialLoad(preset)
            } finally {
                updatingUi = false
            }
        }

        fun selectProvider(providerId: String) {
            val preset = ProviderPresets.byId(providerId)
            providerCombo.selectedItem = preset
            if (activeProviderId != preset.id) switchProvider()
        }

        fun settingsSnapshot(): OmniCodeSettingsSnapshot {
            captureActiveProfile()
            return requireNotNull(profileDrafts[activeProviderId])
        }

        fun profileSnapshots(): List<OmniCodeSettingsSnapshot> {
            captureActiveProfile()
            return profileDrafts.values.toList()
        }

        fun visionModels(): Map<String, String> {
            captureActiveVisionModel()
            return visionModelDrafts.toMap()
        }

        fun selectedProviderId(): String = selectedProvider().id

        fun prepareApply() {
            cancelModelRefresh()
            // The model editor is free-form, so typing/pasting a value does not always fire the
            // combo-box action event. Re-evaluate the capability list at the Apply boundary while
            // preserving an invalid selection for the validation error below.
            refreshReasoningEffortOptions()
            normalizeCredentialInputs()
        }

        fun settingsModified(): Boolean {
            captureActiveProfile()
            return selectedProvider().id != baselineProviderId || profileDrafts.any { (providerId, draft) ->
                draft != profileBaselines[providerId]
            } || visionModels() != visionModelBaselines
        }

        private fun currentProfileFromFields(providerId: String = activeProviderId): OmniCodeSettingsSnapshot {
            val preset = ProviderPresets.byId(providerId)
            val reasoningEffort = selectedReasoningEffort()
            return OmniCodeSettingsSnapshot(
                providerId = preset.id,
                baseUrl = baseUrlField.text.trim(),
                model = selectedModel(),
                region = regionField.text.trim(),
                apiVersion = apiVersionField.text.trim(),
                maxOutputTokens = maxOutputTokensForReasoning(
                    (maxOutputTokensSpinner.value as Number).toInt(),
                    reasoningEffort,
                ),
                reasoningEffort = reasoningEffort,
                proxyMode = proxyModeCombo.selectedItem as? ProviderProxyMode ?: ProviderProxyMode.SYSTEM,
                requestTimeoutSeconds = (requestTimeoutSpinner.value as Number).toLong(),
            )
        }

        fun credentialsModified(): Boolean {
            captureActiveCredentials()
            return credentialDrafts.any { (providerId, draft) ->
                draft != credentialBaselines[providerId]
            }
        }

        fun saveModifiedCredentials() {
            captureActiveCredentials()
            captureActiveProfile()
            val pending = credentialDrafts.filter { (providerId, draft) ->
                draft != credentialBaselines[providerId]
            }
            runBlocking {
                withContext(Dispatchers.IO) {
                    credentialDrafts.forEach { (providerId, draft) ->
                        val baseUrl = profileDrafts[providerId]?.baseUrl
                            ?: ProviderPresets.byId(providerId).defaultBaseUrl
                        credentialEnvironmentSources[providerId]?.let { variable ->
                            if (!environmentFallbackAllowed(providerId, baseUrl)) {
                                throw UnsafeEnvironmentCredentialTargetException(
                                    blockedEnvironmentCredentialMessage(variable, baseUrl),
                                )
                            }
                            return@forEach
                        }
                        if (providerId in pending) {
                            credentialStore.save(providerId, baseUrl, draft)
                        } else if (!draft.isEmpty()) {
                            // Reading through the origin-aware store is an intentional fail-closed
                            // assertion: changing host/scheme/port requires the explicit model button.
                            credentialStore.load(providerId, baseUrl)
                        }
                    }
                }
            }
        }

        fun markApplied() {
            captureActiveCredentials()
            credentialBaselines.clear()
            credentialBaselines.putAll(credentialDrafts)
            blockedCredentialEnvironmentSources.clear()
            captureActiveProfile()
            profileBaselines.clear()
            profileBaselines.putAll(profileDrafts)
            captureActiveVisionModel()
            visionModelBaselines.clear()
            visionModelBaselines.putAll(visionModelDrafts)
            baselineProviderId = selectedProvider().id
            updateModelDiscoveryHint(selectedProvider(), credentialDrafts[activeProviderId] ?: ProviderSecrets())
        }

        fun dispose() {
            disposed = true
            cancelModelRefresh()
            credentialLoadGeneration++
            discoveryScope.cancel()
            clearPasswordFields()
            credentialDrafts.clear()
            credentialBaselines.clear()
            credentialEnvironmentSources.clear()
            blockedCredentialEnvironmentSources.clear()
            profileDrafts.clear()
            profileBaselines.clear()
            visionModelDrafts.clear()
            visionModelBaselines.clear()
        }

        private fun addGroupHeader(row: Int, title: String, first: Boolean = false) {
            component.add(
                TitledSeparator(title),
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    gridwidth = 2
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(if (first) 0 else 8, 0, 7, 0)
                },
            )
        }

        private fun addHint(row: Int, label: JComponent, bottomInset: Int = 8) {
            component.add(
                label,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(-4, 0, bottomInset, 0)
                },
            )
        }

        private fun addRow(row: Int, labelText: String, field: JComponent): FormRow {
            val label = JBLabel("$labelText:").apply { labelFor = field }
            component.add(
                label,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.WEST
                    insets = Insets(0, 0, 8, 12)
                },
            )
            component.add(
                field,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(0, 0, 8, 0)
                },
            )
            return FormRow(label, field)
        }

        private fun switchProvider() {
            cancelModelRefresh()
            captureActiveCredentials()
            captureActiveProfile()
            captureActiveVisionModel()
            val preset = selectedProvider()
            activeProviderId = preset.id
            val profile = profileDrafts[preset.id]
                ?: OmniCodeSettingsService.getInstance().snapshotFor(preset.id).also { defaults ->
                    profileBaselines.putIfAbsent(preset.id, defaults)
                    profileDrafts[preset.id] = defaults
                    visionModelBaselines.putIfAbsent(preset.id, "")
                    visionModelDrafts.putIfAbsent(preset.id, "")
                }
            showProfile(profile)
            updateProviderSpecificFields(preset)
            beginCredentialLoad(preset)
        }

        private fun captureActiveProfile() {
            if (updatingUi) return
            profileDrafts[activeProviderId] = currentProfileFromFields(activeProviderId)
        }

        private fun captureActiveVisionModel() {
            if (updatingUi) return
            visionModelDrafts[activeProviderId] = visionModelField.text.trim()
        }

        private fun showProfile(profile: OmniCodeSettingsSnapshot) {
            val preset = ProviderPresets.byId(profile.providerId)
            val wasUpdating = updatingUi
            updatingUi = true
            try {
                baseUrlField.text = profile.baseUrl
                showAllModelsCheckBox.isSelected = false
                setModelChoices(listOf(profile.model, preset.defaultModel), profile.model)
                setReasoningEffortOptions(preset, profile.model, profile.reasoningEffort)
                regionField.text = profile.region
                apiVersionField.text = providerApiVersion(preset, profile.apiVersion)
                maxOutputTokensSpinner.value = profile.maxOutputTokens
                proxyModeCombo.selectedItem = profile.proxyMode
                requestTimeoutSpinner.value = profile.requestTimeoutSeconds
                visionModelField.text = visionModelDrafts[profile.providerId].orEmpty()
            } finally {
                updatingUi = wasUpdating
            }
        }

        private fun refreshModels() {
            if (disposed || discoveryJob != null) return
            captureActiveCredentials()

            val preset = selectedProvider()
            if (credentialsLoadingProviderId == preset.id) {
                setModelStatus("正在读取已保存的凭据，请稍候。")
                return
            }
            val draft = try {
                normalizeCredentialInputs()
            } catch (error: CredentialInputFormatException) {
                setCredentialStatus(error.message ?: "API Key 输入格式无效。", isError = true)
                setModelStatus("请修正 API Key 后重试。", isError = true)
                return
            }
            val saved = credentialBaselines[preset.id] ?: ProviderSecrets()
            if (requiresSavedApiKey(preset) && draft.apiKey.isBlank()) {
                setModelStatus("请先输入 API Key。", isError = true)
                updateRefreshAvailability(preset, saved)
                return
            }

            val baseUrl = baseUrlField.text.trim()
            modelApiBaseUrlValidationError(baseUrl)?.let { message ->
                setModelStatus(message, isError = true)
                return
            }

            val currentModel = selectedModel().ifBlank { preset.defaultModel }
            val region = regionField.text.trim().ifBlank { OmniCodeSettingsDefaults.REGION }
            val apiVersion = apiVersionField.text.trim().ifBlank { OmniCodeSettingsDefaults.API_VERSION }
            val generation = ++discoveryGeneration
            refreshModelsButton.isEnabled = false
            setDiscoveryFieldsEnabled(false)
            val rebindsCredential = !draft.isEmpty() && credentialEnvironmentSources[preset.id] == null &&
                credentialOriginChanged(profileBaselines[preset.id]?.baseUrl, baseUrl)
            val savesCredential = draft != saved || rebindsCredential
            setModelStatus(if (savesCredential) "正在安全保存 Key 并加载模型…" else "正在刷新可用模型…")

            discoveryJob = discoveryScope.launch {
                val credentialSave = runCatching {
                    if (savesCredential) credentialStore.save(preset.id, baseUrl, draft)
                }
                if (savesCredential && credentialSave.isSuccess) {
                    runCatching { ProviderModelCatalogService.getInstance().invalidate() }
                }
                val outcome = credentialSave.mapCatching {
                    val resolved = resolveProviderSecrets(
                        providerId = preset.id,
                        stored = credentialStore.load(preset.id, baseUrl),
                        baseUrl = baseUrl,
                    )
                    resolved.blockedEnvironmentVariable?.let { variable ->
                        throw ProviderException(blockedEnvironmentCredentialMessage(variable, baseUrl))
                    }
                    val storedSecrets = resolved.secrets
                    if (ProviderModelDiscovery.supportsRemoteDiscovery(preset.protocol) &&
                        !preset.apiKeyOptional && storedSecrets.apiKey.isBlank()
                    ) {
                        throw ProviderException("请先保存 API Key。")
                    }
                    ProviderModelDiscovery.discover(
                        ProviderConnection(
                            preset = preset,
                            baseUrl = baseUrl,
                            model = currentModel,
                            apiKey = storedSecrets.apiKey,
                            secondarySecret = storedSecrets.secondarySecret,
                            sessionToken = storedSecrets.sessionToken,
                            region = region,
                            apiVersion = apiVersion,
                            requestTimeoutSeconds = profileDrafts[preset.id]?.requestTimeoutSeconds
                                ?.takeIf { it > 0L }
                                ?: MODEL_DISCOVERY_TIMEOUT_SECONDS,
                            proxyMode = profileDrafts[preset.id]?.proxyMode ?: ProviderProxyMode.SYSTEM,
                        ),
                    )
                }
                ApplicationManager.getApplication().invokeLater(
                    {
                        val persistedCredential = credentialBaselineAfterSaveAttempt(
                            saved = saved,
                            draft = draft,
                            saveRequested = savesCredential,
                            saveSucceeded = credentialSave.isSuccess,
                        )
                        if (!disposed && savesCredential && credentialSave.isSuccess) {
                            credentialBaselines[preset.id] = persistedCredential
                            credentialDrafts[preset.id] = persistedCredential
                            credentialEnvironmentSources.remove(preset.id)
                            blockedCredentialEnvironmentSources.remove(preset.id)
                            if (activeProviderId == preset.id) showCredentials(persistedCredential)
                        }
                        if (disposed || generation != discoveryGeneration) return@invokeLater
                        discoveryJob = null
                        setDiscoveryFieldsEnabled(true)
                        if (savesCredential && credentialSave.isSuccess) {
                            setCredentialStatus("API Key 已安全保存。")
                        }
                        outcome.fold(
                            onSuccess = {
                                if (savesCredential) setCredentialStatus("API Key 已安全保存，并已验证连接。")
                                applyModelDiscovery(it, currentModel, preset.defaultModel)
                                updateRefreshAvailability(preset, persistedCredential)
                            },
                            onFailure = {
                                if (savesCredential && credentialSave.isFailure) {
                                    setCredentialStatus("无法将 API Key 保存到 Password Safe。", isError = true)
                                }
                                setModelStatus(modelDiscoveryError(it), isError = true)
                                updateRefreshAvailability(preset, persistedCredential)
                            },
                        )
                    },
                    ModalityState.any(),
                )
            }
        }

        private fun applyModelDiscovery(
            result: ModelDiscoveryResult,
            currentModel: String,
            defaultModel: String,
        ) {
            setModelChoices(listOf(currentModel) + result.models + defaultModel, currentModel)
            captureActiveProfile()
            val defaultView = modelCatalogView(rawModelChoices, activeModel = currentModel)
            setModelStatus(
                if (result.discoveredRemotely && defaultView.hiddenNonChatCount > 0) {
                    "已加载 ${result.models.size} 个模型；默认显示编程对话候选，隐藏 " +
                        "${defaultView.hiddenNonChatCount} 个专用模型。仍可手动输入模型 ID。"
                } else if (result.discoveredRemotely) {
                    "已加载 ${result.models.size} 个可用模型；可从列表选择或手动输入模型 ID。"
                }
                else result.status,
            )
        }

        private fun setModelChoices(models: List<String>, selected: String) {
            rawModelChoices = models.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .toList()
            rebuildModelChoices(selected)
        }

        private fun rebuildModelChoices(selected: String) {
            val choices = modelCatalogView(
                models = rawModelChoices,
                activeModel = selected,
                showAll = showAllModelsCheckBox.isSelected,
            ).models
            modelCombo.model = DefaultComboBoxModel(choices.toTypedArray())
            modelCombo.selectedItem = selected.ifBlank { choices.firstOrNull().orEmpty() }
            val hiddenCount = modelCatalogView(rawModelChoices, activeModel = selected).hiddenNonChatCount
            showAllModelsCheckBox.text = if (hiddenCount > 0) {
                "显示全部模型（另有 $hiddenCount 个非对话用途）"
            } else {
                "显示全部模型（含非对话用途）"
            }
            showAllModelsCheckBox.isEnabled = !disposed && hiddenCount > 0
        }

        private fun selectedModel(): String = modelCombo.editor.item?.toString()?.trim().orEmpty()

        private fun selectedReasoningEffort(): ReasoningEffort =
            reasoningEffortCombo.selectedItem as? ReasoningEffort ?: ReasoningEffort.AUTO

        private fun refreshReasoningEffortOptions() {
            val requested = selectedReasoningEffort()
            val preset = selectedProvider()
            val model = selectedModel().ifBlank { preset.defaultModel }
            val wasUpdating = updatingUi
            updatingUi = true
            try {
                setReasoningEffortOptions(preset, model, requested)
            } finally {
                updatingUi = wasUpdating
            }
            enforceReasoningOutputFloor()
        }

        private fun setReasoningEffortOptions(
            preset: ProviderPreset,
            model: String,
            requested: ReasoningEffort,
        ) {
            val state = reasoningEffortEditorState(preset, model, requested)
            reasoningEffortCombo.model = DefaultComboBoxModel(state.options.toTypedArray())
            reasoningEffortCombo.selectedItem = state.selected
            updateReasoningEffortHint(state.selected, state.unsupportedSelection)
        }

        private fun applyReasoningEffortSelection() {
            val selected = selectedReasoningEffort()
            enforceReasoningOutputFloor()
            updateReasoningEffortHint(selected)
        }

        private fun enforceReasoningOutputFloor() {
            val current = (maxOutputTokensSpinner.value as Number).toInt()
            val normalized = maxOutputTokensForReasoning(current, selectedReasoningEffort())
            if (current != normalized) maxOutputTokensSpinner.value = normalized
        }

        private fun updateReasoningEffortHint(
            effort: ReasoningEffort,
            unsupportedSelection: Boolean = false,
        ) {
            reasoningEffortHintLabel.foreground = if (unsupportedSelection) {
                UIUtil.getErrorForeground()
            } else {
                UIUtil.getContextHelpForeground()
            }
            reasoningEffortHintLabel.text = when {
                unsupportedSelection ->
                    "当前模型不支持已选推理强度。请主动选择 Auto 或列表中的可用档位；保存前不会自动改写原配置。"
                effort == ReasoningEffort.AUTO ->
                    "Auto 使用模型默认值；推理强度是质量/延迟偏好，不保证模型用满 Token 上限。"
                else -> {
                    val floor = effort.recommendedOutputTokenFloor()
                    "${reasoningEffortLabel(effort)} 会提高推理深度、延迟和消耗；最大输出 Token 将至少为 $floor。"
                }
            }
        }

        private fun updateModelDiscoveryHint(preset: ProviderPreset, secrets: ProviderSecrets) {
            updateProviderSpecificFields(preset)
            val draft = credentialDrafts[preset.id] ?: secrets
            val dirty = draft != secrets
            val supportsDiscovery = ProviderModelDiscovery.supportsRemoteDiscovery(preset.protocol)

            when {
                credentialsLoadingProviderId == preset.id -> {
                    setCredentialStatus("正在从 Password Safe 读取凭据…")
                    setModelStatus("读取完成后即可加载模型。")
                }
                dirty -> {
                    setCredentialStatus("Key 尚未保存；点击“保存并加载模型”会写入 Password Safe 并验证。")
                    setModelStatus("保存 Key 后将自动从供应商 API 获取可用模型。")
                }
                !draft.isEmpty() && credentialEnvironmentSources[preset.id] == null &&
                    credentialOriginChanged(profileBaselines[preset.id]?.baseUrl, baseUrlField.text.trim()) -> {
                    setCredentialStatus(
                        "Base URL 的 Origin 已改变；点击“保存并加载模型”确认将 Key 绑定到新地址。",
                        isError = true,
                    )
                    setModelStatus("确认前不会把已保存的 API Key 发送到新地址。", isError = true)
                }
                blockedCredentialEnvironmentSources[preset.id] != null -> {
                    val variable = blockedCredentialEnvironmentSources.getValue(preset.id)
                    setCredentialStatus(
                        blockedEnvironmentCredentialMessage(variable, baseUrlField.text.trim()),
                        isError = true,
                    )
                    setModelStatus("请为该地址显式输入并保存 API Key。", isError = true)
                }
                requiresSavedApiKey(preset) && secrets.apiKey.isBlank() -> {
                    setCredentialStatus("请输入 API Key，然后点击“保存并加载模型”。")
                    setModelStatus("模型列表只会在 Key 保存后从供应商 API 获取。")
                }
                preset.protocol == ProviderProtocol.BEDROCK_CONVERSE && secrets.apiKey.isBlank() -> {
                    setCredentialStatus("可填写下方凭据，或使用标准 AWS 凭据链。")
                    setModelStatus("Bedrock 不提供统一模型列表，请手动填写 Model ID。")
                }
                preset.apiKeyOptional && secrets.apiKey.isBlank() -> {
                    setCredentialStatus("该供应商不强制要求 API Key。")
                    setModelStatus(
                        if (supportsDiscovery) "点击“刷新模型”查询可用模型。"
                        else "该供应商不支持模型发现，请手动填写模型 ID。",
                    )
                }
                credentialEnvironmentSources[preset.id] != null -> {
                    val variable = credentialEnvironmentSources.getValue(preset.id)
                    setCredentialStatus("已从环境变量 $variable 读取 API Key，不会写入 Password Safe。")
                    setModelStatus(
                        if (supportsDiscovery) "点击“刷新模型”验证环境变量并加载可用模型。"
                        else "该供应商不支持模型发现，请手动填写模型 ID。",
                    )
                }
                else -> {
                    setCredentialStatus("API Key 已安全保存在 IDE Password Safe。")
                    setModelStatus(
                        if (supportsDiscovery) "点击“刷新模型”重新查询当前 Key 可用的模型。"
                        else "该供应商不支持模型发现，请手动填写模型 ID。",
                    )
                }
            }
            updateRefreshAvailability(preset, secrets)
        }

        private fun cancelModelRefresh() {
            discoveryGeneration++
            val wasRunning = discoveryJob != null
            discoveryJob?.cancel()
            discoveryJob = null
            if (!disposed) {
                if (wasRunning) setDiscoveryFieldsEnabled(true)
                val preset = selectedProvider()
                updateRefreshAvailability(preset, credentialBaselines[preset.id] ?: ProviderSecrets())
            } else {
                refreshModelsButton.isEnabled = false
            }
        }

        private fun modelDiscoveryError(error: Throwable): String {
            val providerError = error as? ProviderException
            return when (providerError?.statusCode) {
                401 -> modelAuthenticationError(selectedProvider(), baseUrlField.text.trim())
                403 -> "当前凭据没有读取模型列表的权限（HTTP 403）。"
                404 -> "该地址不提供模型列表（HTTP 404），可手动填写模型 ID。"
                429 -> "模型接口触发限流（HTTP 429），请稍后重试。"
                else -> error.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(240)
                    ?.let { "模型加载失败：$it" }
                    ?: "模型加载失败。"
            }
        }

        private fun selectedProvider(): ProviderPreset =
            providerCombo.selectedItem as? ProviderPreset ?: ProviderPresets.byId(OmniCodeSettingsDefaults.providerId)

        private fun providerApiVersion(preset: ProviderPreset, configured: String): String = when {
            preset.protocol == ProviderProtocol.ANTHROPIC_MESSAGES &&
                (configured.isBlank() || configured == OmniCodeSettingsDefaults.API_VERSION) -> "2023-06-01"
            configured.isNotBlank() -> configured
            else -> OmniCodeSettingsDefaults.API_VERSION
        }

        private fun beginCredentialLoad(preset: ProviderPreset) {
            updateProviderSpecificFields(preset)
            credentialDrafts[preset.id]?.let { loaded ->
                credentialsLoadingProviderId = null
                setCredentialFieldsEnabled(true)
                showCredentials(loaded)
                updateModelDiscoveryHint(preset, credentialBaselines[preset.id] ?: ProviderSecrets())
                return
            }

            val generation = ++credentialLoadGeneration
            credentialsLoadingProviderId = preset.id
            clearPasswordFields()
            setCredentialFieldsEnabled(false)
            modelCombo.isEnabled = false
            reasoningEffortCombo.isEnabled = false
            showAllModelsCheckBox.isEnabled = false
            refreshModelsButton.isEnabled = false
            setCredentialStatus("正在从 Password Safe 读取凭据…")
            setModelStatus("读取完成后即可加载模型。")
            val baseUrl = profileDrafts[preset.id]?.baseUrl ?: preset.defaultBaseUrl
            discoveryScope.launch {
                val outcome = runCatching {
                    resolveProviderSecrets(
                        providerId = preset.id,
                        stored = credentialStore.load(preset.id, baseUrl),
                        baseUrl = baseUrl,
                    )
                }
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (disposed || generation != credentialLoadGeneration || activeProviderId != preset.id) {
                            return@invokeLater
                        }
                        credentialsLoadingProviderId = null
                        setCredentialFieldsEnabled(true)
                        outcome.fold(
                            onSuccess = { resolved ->
                                val secrets = resolved.secrets
                                resolved.environmentVariable?.let { variable ->
                                    credentialEnvironmentSources[preset.id] = variable
                                } ?: credentialEnvironmentSources.remove(preset.id)
                                resolved.blockedEnvironmentVariable?.let { variable ->
                                    blockedCredentialEnvironmentSources[preset.id] = variable
                                } ?: blockedCredentialEnvironmentSources.remove(preset.id)
                                credentialBaselines[preset.id] = secrets
                                credentialDrafts[preset.id] = secrets
                                showCredentials(secrets)
                                updateModelDiscoveryHint(preset, secrets)
                            },
                            onFailure = { error ->
                                val empty = ProviderSecrets()
                                credentialEnvironmentSources.remove(preset.id)
                                blockedCredentialEnvironmentSources.remove(preset.id)
                                credentialBaselines[preset.id] = empty
                                credentialDrafts[preset.id] = empty
                                showCredentials(empty)
                                updateModelDiscoveryHint(preset, empty)
                                setCredentialStatus(
                                    if (error is CredentialOriginMismatchException) {
                                        error.message ?: "Base URL 已改变，请重新输入并保存 API Key。"
                                    } else {
                                        "无法读取已保存的凭据，请重新输入并保存。"
                                    },
                                    isError = true,
                                )
                            },
                        )
                    },
                    ModalityState.any(),
                )
            }
        }

        private fun captureActiveCredentials() {
            if (credentialsLoadingProviderId == activeProviderId) return
            credentialDrafts[activeProviderId] = ProviderSecrets(
                apiKey = passwordValue(apiKeyField),
                secondarySecret = passwordValue(secondarySecretField),
                sessionToken = passwordValue(sessionTokenField),
            )
        }

        private fun normalizeCredentialInputs(): ProviderSecrets {
            captureActiveCredentials()
            credentialDrafts.keys.toList().forEach { providerId ->
                val draft = credentialDrafts.getValue(providerId)
                val normalized = normalizeApiKeyInput(draft.apiKey, providerId)
                credentialDrafts[providerId] = draft.copy(apiKey = normalized.value)
                // A pasted JSON/KEY=value snippet is intentionally imported into Password Safe;
                // only an untouched process environment fallback remains non-persistent.
                if (normalized.sourceVariable != null) {
                    credentialEnvironmentSources.remove(providerId)
                    blockedCredentialEnvironmentSources.remove(providerId)
                }
            }
            val active = credentialDrafts[activeProviderId] ?: ProviderSecrets()
            showCredentials(active)
            return active
        }

        private fun showCredentials(secrets: ProviderSecrets) {
            updatingCredentialFields = true
            try {
                apiKeyField.text = secrets.apiKey
                secondarySecretField.text = secrets.secondarySecret
                sessionTokenField.text = secrets.sessionToken
                apiKeyField.setPasswordIsStored(secrets.apiKey.isNotBlank())
                secondarySecretField.setPasswordIsStored(secrets.secondarySecret.isNotBlank())
                sessionTokenField.setPasswordIsStored(secrets.sessionToken.isNotBlank())
            } finally {
                updatingCredentialFields = false
            }
        }

        private fun clearPasswordFields() {
            updatingCredentialFields = true
            try {
                apiKeyField.text = ""
                secondarySecretField.text = ""
                sessionTokenField.text = ""
                apiKeyField.setPasswordIsStored(false)
                secondarySecretField.setPasswordIsStored(false)
                sessionTokenField.setPasswordIsStored(false)
            } finally {
                updatingCredentialFields = false
            }
        }

        private fun setCredentialFieldsEnabled(enabled: Boolean) {
            apiKeyRow.setEnabled(enabled)
            secondarySecretRow.setEnabled(enabled)
            sessionTokenRow.setEnabled(enabled)
        }

        private fun setDiscoveryFieldsEnabled(enabled: Boolean) {
            baseUrlField.isEnabled = enabled
            restoreEndpointButton.isEnabled = enabled
            modelCombo.isEnabled = enabled
            reasoningEffortCombo.isEnabled = enabled
            showAllModelsCheckBox.isEnabled = enabled &&
                modelCatalogView(rawModelChoices, activeModel = selectedModel()).hiddenNonChatCount > 0
            visionModelField.isEnabled = enabled
            regionRow.setEnabled(enabled)
            apiVersionRow.setEnabled(enabled)
            maxOutputTokensSpinner.isEnabled = enabled
            setCredentialFieldsEnabled(enabled)
        }

        private fun passwordValue(field: JBPasswordField): String {
            val password = field.password
            return try {
                password.concatToString()
            } finally {
                password.fill('\u0000')
            }
        }

        private fun installCredentialListener(field: JBPasswordField) {
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = credentialEdited()
                override fun removeUpdate(event: DocumentEvent) = credentialEdited()
                override fun changedUpdate(event: DocumentEvent) = credentialEdited()
            })
        }

        private fun credentialEdited() {
            if (updatingUi || updatingCredentialFields || credentialsLoadingProviderId == activeProviderId) return
            credentialEnvironmentSources.remove(activeProviderId)
            blockedCredentialEnvironmentSources.remove(activeProviderId)
            captureActiveCredentials()
            val preset = selectedProvider()
            updateModelDiscoveryHint(preset, credentialBaselines[preset.id] ?: ProviderSecrets())
        }

        private fun updateProviderSpecificFields(preset: ProviderPreset) {
            val bedrock = preset.protocol == ProviderProtocol.BEDROCK_CONVERSE
            secondarySecretRow.setVisible(bedrock)
            sessionTokenRow.setVisible(bedrock)
            regionRow.setVisible(bedrock)
            apiVersionRow.setVisible(
                preset.protocol == ProviderProtocol.AZURE_OPENAI ||
                    preset.protocol == ProviderProtocol.ANTHROPIC_MESSAGES,
            )
            apiKeyRow.label.text = when {
                bedrock -> "Access key ID（可选）:"
                preset.apiKeyOptional -> "API Key（可选）:"
                else -> "API Key:"
            }
            secondarySecretRow.label.text = "Secret access key:"
            component.revalidate()
            component.repaint()
        }

        private fun updateRefreshAvailability(preset: ProviderPreset, saved: ProviderSecrets) {
            val loading = credentialsLoadingProviderId == preset.id
            val draft = credentialDrafts[preset.id] ?: saved
            val rebindRequired = !draft.isEmpty() && credentialEnvironmentSources[preset.id] == null &&
                credentialOriginChanged(profileBaselines[preset.id]?.baseUrl, baseUrlField.text.trim())
            val dirty = draft != saved || rebindRequired
            val supportsDiscovery = ProviderModelDiscovery.supportsRemoteDiscovery(preset.protocol)
            val hasRequiredKey = !requiresSavedApiKey(preset) || draft.apiKey.isNotBlank()
            refreshModelsButton.isEnabled =
                !disposed && !loading && discoveryJob == null && supportsDiscovery && hasRequiredKey
            refreshModelsButton.text = if (dirty) "保存并加载模型" else "刷新模型"
            refreshModelsButton.toolTipText = when {
                loading -> "等待凭据读取完成"
                !supportsDiscovery -> "该供应商不支持模型发现"
                !hasRequiredKey -> "请先输入 API Key"
                dirty -> "安全保存 API Key、验证连接并加载可用模型"
                else -> "从供应商 API 刷新当前 Key 可用的模型"
            }
            modelCombo.isEnabled = !disposed && !loading
            reasoningEffortCombo.isEnabled = !disposed && !loading
            showAllModelsCheckBox.isEnabled = !disposed && !loading &&
                modelCatalogView(rawModelChoices, activeModel = selectedModel()).hiddenNonChatCount > 0
        }

        private fun requiresSavedApiKey(preset: ProviderPreset): Boolean =
            ProviderModelDiscovery.supportsRemoteDiscovery(preset.protocol) && !preset.apiKeyOptional

        private fun setCredentialStatus(message: String, isError: Boolean = false) {
            credentialStatusLabel.text = message
            credentialStatusLabel.foreground =
                if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        }

        private fun setModelStatus(message: String, isError: Boolean = false) {
            modelStatusLabel.text = message
            modelStatusLabel.foreground =
                if (isError) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
        }

        private fun hintLabel(text: String = ""): JBTextArea = JBTextArea(text).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            columns = 1
            border = JBUI.Borders.empty()
            minimumSize = Dimension(0, preferredSize.height)
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
        }

        private data class FormRow(
            val label: JBLabel,
            val field: JComponent,
        ) {
            fun setVisible(visible: Boolean) {
                label.isVisible = visible
                field.isVisible = visible
            }

            fun setEnabled(enabled: Boolean) {
                label.isEnabled = enabled
                field.isEnabled = enabled
            }
        }

        private companion object {
            const val MODEL_DISCOVERY_TIMEOUT_SECONDS = 30L
        }
    }
}

internal data class ReasoningEffortEditorState(
    val options: List<ReasoningEffort>,
    val selected: ReasoningEffort,
    val unsupportedSelection: Boolean,
)

/**
 * Keeps a persisted/user-entered effort visible when a model change makes it invalid. The Apply
 * boundary can then reject the combination explicitly instead of silently rewriting it to Auto.
 */
internal fun reasoningEffortEditorState(
    preset: ProviderPreset,
    model: String,
    requested: ReasoningEffort,
): ReasoningEffortEditorState {
    val supported = reasoningEffortOptions(preset.id, preset.protocol, model)
    val unsupported = requested !in supported
    return ReasoningEffortEditorState(
        options = if (unsupported) supported + requested else supported,
        selected = requested,
        unsupportedSelection = unsupported,
    )
}

internal fun reasoningEffortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.AUTO -> "Auto（模型默认）"
    ReasoningEffort.NONE -> "None（关闭推理）"
    ReasoningEffort.MINIMAL -> "Minimal（极简）"
    ReasoningEffort.LOW -> "Low（快速）"
    ReasoningEffort.MEDIUM -> "Medium（均衡）"
    ReasoningEffort.HIGH -> "High（深入）"
    ReasoningEffort.XHIGH -> "XHigh（超高）"
    ReasoningEffort.MAX -> "Max（模型最高档）"
}

internal class ProviderEmbeddedSettings : OmniCodeEmbeddedSettings {
    private val editor = OmniCodeConfigurable.SettingsPanel(OmniCodeCredentialStore.getInstance())
    private val cliPanel = CliToolsManagementPanel(::useCli)
    private val tabs = JTabbedPane(SwingConstants.TOP).apply {
        addTab("Claude Code", ApiProvidersPanel(editor.component) { providerId ->
            editor.selectProvider(providerId)
        })
        addTab("Codex", CodexProviderPanel())
        addTab("CLI", cliPanel.component)
        toolTipText = "切换不同的 AI 供应商接入方式"
    }

    private fun useCli(tool: dev.omnicode.provider.CliTool) {
        val providerId = when (tool) {
            dev.omnicode.provider.CliTool.OPENCODE -> "cli-opencode"
            dev.omnicode.provider.CliTool.KIMI -> "cli-kimi"
            dev.omnicode.provider.CliTool.GROK -> "cli-grok"
            dev.omnicode.provider.CliTool.PI -> "cli-pi"
            dev.omnicode.provider.CliTool.QODER -> "cli-qoder"
        }
        editor.selectProvider(providerId)
        tabs.selectedIndex = 2
    }

    override val component: JComponent = JPanel(BorderLayout()).apply {
        add(tabs, BorderLayout.CENTER)
    }
    override val isModified: Boolean get() = editor.settingsModified() || editor.credentialsModified()

    init {
        reset()
    }

    override fun save() {
        try {
            editor.prepareApply()
        } catch (error: CredentialInputFormatException) {
            throw OmniCodeSettingsSaveException(error.message ?: "API Key 输入格式无效。", error)
        }
        val snapshots = editor.profileSnapshots()
        snapshots.firstNotNullOfOrNull(::providerValidationError)?.let { message ->
            throw OmniCodeSettingsSaveException(message)
        }
        try {
            editor.saveModifiedCredentials()
        } catch (error: CredentialOriginMismatchException) {
            throw OmniCodeSettingsSaveException(
                error.message ?: "Base URL 已改变，请重新确认 API Key。",
                error,
            )
        } catch (error: UnsafeEnvironmentCredentialTargetException) {
            throw OmniCodeSettingsSaveException(
                error.message ?: "环境变量中的 API Key 不能用于该远程地址。",
                error,
            )
        } catch (error: RuntimeException) {
            throw OmniCodeSettingsSaveException("无法将供应商凭据保存到 IDE Password Safe。", error)
        }
        OmniCodeSettingsService.getInstance().updateProfiles(editor.selectedProviderId(), snapshots)
        OmniCodeSettingsService.getInstance().updateVisionModels(editor.visionModels())
        ProviderModelCatalogService.getInstance().invalidate()
        editor.markApplied()
    }

    override fun reset() {
        val settings = OmniCodeSettingsService.getInstance()
        editor.resetFrom(settings.snapshot(), settings.profileSnapshots(), settings.visionModels())
    }

    override fun dispose() {
        cliPanel.dispose()
        editor.dispose()
    }
}

/** Keeps the regular API providers visible while retaining the full existing editor below. */
private fun ApiProvidersPanel(editor: JComponent, onSelect: (String) -> Unit): JComponent = JPanel(BorderLayout(0, 8)).apply {
    val header = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(12, 16, 4, 16)
        add(JBLabel("普通 API 供应商").apply { font = JBFont.h2().asBold() }, BorderLayout.WEST)
        add(JBLabel("${dev.omnicode.provider.ProviderPresets.all.count { !it.id.startsWith("cli-") }} 个可配置供应商")
            .apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
    }
    add(header, BorderLayout.NORTH)
    val providers = dev.omnicode.provider.ProviderPresets.all.filterNot { it.id.startsWith("cli-") }
    val cards = JPanel(GridLayout(0, 3, 8, 8)).apply {
        border = JBUI.Borders.empty(0, 16, 12, 16)
        providers.forEach { provider ->
            add(JButton(provider.displayName).apply {
                horizontalAlignment = SwingConstants.LEFT
                toolTipText = provider.defaultBaseUrl
                addActionListener { onSelect(provider.id) }
            })
        }
    }
    val detail = JPanel(BorderLayout(0, 4)).apply {
        add(JBLabel("详细配置").apply {
            border = JBUI.Borders.empty(0, 16, 0, 16)
            font = JBFont.label().asBold()
        }, BorderLayout.NORTH)
        add(editor, BorderLayout.CENTER)
    }
    add(JScrollPane(JPanel(BorderLayout()).apply {
        add(cards, BorderLayout.NORTH)
        add(detail, BorderLayout.CENTER)
    }).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }, BorderLayout.CENTER)
}

/** Lightweight Codex tab: the native App Server is managed by the local Codex installation. */
private fun CodexProviderPanel(): JComponent = JPanel(BorderLayout(0, 12)).apply {
    border = JBUI.Borders.empty(16)
    add(JBLabel("Codex 原生子智能体").apply { font = JBFont.h2().asBold() }, BorderLayout.NORTH)
    add(JBLabel(
        "OmniCode 会通过本机 Codex App Server 创建原生子智能体。无需在这里复制 API Key；请先安装并登录 Codex。",
    ).apply {
        foreground = UIUtil.getContextHelpForeground()
        verticalAlignment = SwingConstants.TOP
    }, BorderLayout.CENTER)
    add(JBLabel("连接状态将在首次运行任务时自动检测。", SwingConstants.LEADING).apply {
        foreground = UIUtil.getContextHelpForeground()
    }, BorderLayout.SOUTH)
}

/**
 * Presents local CLI installations without changing credentials or installing packages. The
 * probe is explicit and bounded; users can refresh it after installing a CLI in their terminal.
 */
private class CliToolsManagementPanel(
    private val onUse: (dev.omnicode.provider.CliTool) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16)
    }
    private val countLabel = JBLabel()
    private val refreshButton = JButton("重新检测")
    val component: JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8)
        val header = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(JBLabel("本地 CLI 工具").apply { font = JBFont.h2().asBold() }, BorderLayout.WEST)
            add(countLabel, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(JScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
    }

    init {
        refreshButton.addActionListener { refresh() }
        refresh()
    }

    fun dispose() {
        scope.cancel()
    }

    private fun refresh() {
        refreshButton.isEnabled = false
        countLabel.text = "检测中…"
        content.removeAll()
        content.add(JBLabel("正在检查本机 PATH 中的 CLI 工具…").apply {
            foreground = UIUtil.getContextHelpForeground()
        })
        content.revalidate()
        content.repaint()
        scope.launch {
            val rows = dev.omnicode.provider.CliTool.entries.map { tool -> detect(tool) }
            ApplicationManager.getApplication().invokeLater {
                if (scope.coroutineContext[Job]?.isActive == false) return@invokeLater
                render(rows)
            }
        }
    }

    private fun detect(tool: dev.omnicode.provider.CliTool): CliStatus {
        val executable = dev.omnicode.provider.CliToolDiscovery.resolveExecutable(tool, null)
            ?: return CliStatus(tool, null, null)
        val version = runCatching {
            ProcessBuilder(listOf(executable.absolutePath, "--version"))
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    process.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull()
                        .orEmpty()
                        .take(80)
                        .also { process.destroyForcibly() }
                }
        }.getOrNull().orEmpty()
        return CliStatus(tool, executable.absolutePath, version.ifBlank { "已安装" })
    }

    private fun render(rows: List<CliStatus>) {
        content.removeAll()
        val installed = rows.count { it.path != null }
        countLabel.text = "$installed / ${rows.size} 已安装"
        content.add(JBLabel("以下 CLI 需要在系统终端自行安装和配置。插件只负责检测，不会替你安装。").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyBottom(10)
        })
        rows.forEach { content.add(cliCard(it)) }
        content.add(JBLabel("后续将支持更多 CLI 工具").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyTop(8)
        })
        content.revalidate()
        content.repaint()
        refreshButton.isEnabled = true
    }

    private fun cliCard(status: CliStatus): JComponent = JPanel(BorderLayout(12, 0)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 64)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(
                if (status.path == null) UIUtil.getBoundsColor() else UIUtil.getFocusedBorderColor(),
            ),
            JBUI.Borders.empty(10, 12),
        )
        val name = when (status.tool) {
            dev.omnicode.provider.CliTool.GROK -> "Grok CLI"
            dev.omnicode.provider.CliTool.KIMI -> "Kimi CLI"
            dev.omnicode.provider.CliTool.OPENCODE -> "OpenCode CLI"
            dev.omnicode.provider.CliTool.PI -> "Pi CLI"
            dev.omnicode.provider.CliTool.QODER -> "Qoder CLI"
        }
        add(JBLabel(name).apply { font = JBFont.label().asBold() }, BorderLayout.WEST)
        add(JBLabel(status.path?.let { "${status.version}   $it" } ?: "未安装").apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.CENTER)
        add(if (status.path == null) JButton("查看安装方式").apply {
            toolTipText = "查看安装命令；插件不会自动安装 CLI"
            addActionListener {
                Messages.showInfoMessage(
                    installInstructions(status.tool),
                    "${name} 安装方式",
                )
            }
        } else JButton("使用此 CLI").apply {
            addActionListener { onUse(status.tool) }
            toolTipText = "切换到此 CLI 供应商并使用已检测到的可执行文件"
        }, BorderLayout.EAST)
    }

    private fun installInstructions(tool: dev.omnicode.provider.CliTool): String = when (tool) {
        dev.omnicode.provider.CliTool.GROK ->
            "请先按照 xAI 官方文档安装 Grok CLI，然后在系统终端确认：\n\n" +
                "grok --version\n\n安装完成后返回此页点击“重新检测”。"
        dev.omnicode.provider.CliTool.KIMI ->
            "请按照 Moonshot/Kimi CLI 官方文档安装：\n\n" +
                "npm install -g @moonshot-ai/kimi-cli\n\nkimi --version\n\n" +
                "安装完成后返回此页点击“重新检测”。"
        dev.omnicode.provider.CliTool.OPENCODE ->
            "请按照 OpenCode 官方文档安装：\n\n" +
                "npm install -g opencode-ai\n\nopencode --version\n\n" +
                "安装完成后返回此页点击“重新检测”。"
        dev.omnicode.provider.CliTool.PI ->
            "请按照 Pi CLI 官方文档安装：\n\n" +
                "npm install -g @earendil-works/pi-coding-agent\n\npi --version\n\n" +
                "安装完成后返回此页点击“重新检测”。"
        dev.omnicode.provider.CliTool.QODER ->
            "请按照 Qoder CLI 官方文档安装 qoder 或 qodercli，然后在终端确认：\n\n" +
                "qoder --version\n\n安装完成后返回此页点击“重新检测”。"
    }

    private data class CliStatus(
        val tool: dev.omnicode.provider.CliTool,
        val path: String?,
        val version: String?,
    )
}

internal fun providerValidationError(snapshot: OmniCodeSettingsSnapshot): String? {
    modelApiBaseUrlValidationError(snapshot.baseUrl)?.let { return it }
    when {
        snapshot.model.isBlank() -> return "模型不能为空。"
        snapshot.region.isBlank() -> return "Region 不能为空。"
        snapshot.apiVersion.isBlank() -> return "API Version 不能为空。"
        snapshot.maxOutputTokens !in
            OmniCodeSettingsDefaults.MIN_OUTPUT_TOKENS..OmniCodeSettingsDefaults.MAX_ALLOWED_OUTPUT_TOKENS ->
            return "最大输出 Token 超出支持范围。"
    }
    val preset = ProviderPresets.byId(snapshot.providerId)
    if (snapshot.reasoningEffort !in reasoningEffortOptions(preset.id, preset.protocol, snapshot.model)) {
        return "${preset.displayName} 模型 '${snapshot.model}' 不支持推理强度 " +
            "${reasoningEffortLabel(snapshot.reasoningEffort)}。请选择 Auto 或当前列表中的可用档位。"
    }
    return null
}

internal fun credentialOriginChanged(previousBaseUrl: String?, currentBaseUrl: String): Boolean {
    if (previousBaseUrl.isNullOrBlank()) return false
    val previous = runCatching { canonicalModelApiOrigin(previousBaseUrl) }.getOrNull() ?: return true
    val current = runCatching { canonicalModelApiOrigin(currentBaseUrl) }.getOrNull() ?: return true
    return previous != current
}

private fun compactTextField(columns: Int): JBTextField = JBTextField(columns).apply {
    minimumSize = Dimension(0, preferredSize.height)
}

private fun compactPasswordField(): JBPasswordField = JBPasswordField().apply {
    columns = 24
    minimumSize = Dimension(0, preferredSize.height)
}

internal fun credentialBaselineAfterSaveAttempt(
    saved: ProviderSecrets,
    draft: ProviderSecrets,
    saveRequested: Boolean,
    saveSucceeded: Boolean,
): ProviderSecrets = if (saveRequested && saveSucceeded) draft else saved

internal fun modelAuthenticationError(preset: ProviderPreset, baseUrl: String): String =
    if (preset.id == "openai") {
        "OpenAI 返回 HTTP 401。请确认 Base URL 为 https://api.openai.com/v1，" +
            "并使用 platform.openai.com/api-keys 创建的 API Key；ChatGPT 登录或订阅凭据不能用于 API。"
    } else {
        "${preset.displayName} 返回 HTTP 401。请确认该 Key 属于当前供应商，并检查 Base URL：" +
            baseUrl.take(120).ifBlank { preset.defaultBaseUrl }
    }
