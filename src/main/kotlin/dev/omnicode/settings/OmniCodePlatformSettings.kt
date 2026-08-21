package dev.omnicode.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.UUID

const val UNLIMITED_WORKFLOW_TOKENS: Long = Long.MAX_VALUE

internal fun OmniCodePlatformSettingsState.applyFullSpeedRuntimePreset() {
    agentContinuousExecution = true
    agentMaxIterations = 128
    agentMaxToolCalls = 256
    agentMaxWallTimeSeconds = 3_600
    agentMaxToolTimeSeconds = 1_800
}

class McpServerState {
    var id: String = UUID.randomUUID().toString()
    var name: String = "MCP Server"
    var enabled: Boolean = true
    var transport: String = McpTransport.STDIO.id
    var command: String = ""
    var arguments: String = ""
    var environmentKeys: String = ""
    var workingDirectory: String = "."
    var url: String = ""
    var httpAuthMode: String = McpHttpAuthMode.BEARER.id
    var oauthClientId: String = ""
    var oauthScopes: String = ""
}

class McpLaunchTrustState {
    var serverId: String = ""
    var projectId: String = ""
    var fingerprint: String = ""
    var trustedAtEpochMillis: Long = 0L
}

class PromptTemplateState {
    var id: String = UUID.randomUUID().toString()
    var name: String = "New prompt"
    var shortcut: String = "prompt"
    var content: String = ""
}

class SkillSourceState {
    var id: String = UUID.randomUUID().toString()
    var name: String = "Skill library"
    var path: String = ""
    var enabled: Boolean = true
}

class ModelPricingState {
    var providerId: String = ""
    var modelPattern: String = "*"
    var inputUsdPerMillion: Double = 0.0
    var outputUsdPerMillion: Double = 0.0
}

class OmniCodePlatformSettingsState {
    var sandboxMode: String = SandboxMode.WORKSPACE_WRITE.name
    var historyEnabled: Boolean = true
    var historyRetention: Int = 100
    var usageRetentionDays: Int = 365
    var mcpServers: MutableList<McpServerState> = mutableListOf()
    var mcpLaunchTrusts: MutableList<McpLaunchTrustState> = mutableListOf()
    var promptTemplates: MutableList<PromptTemplateState> = mutableListOf()
    var skillSources: MutableList<SkillSourceState> = mutableListOf()
    var pricing: MutableList<ModelPricingState> = mutableListOf()
    /** Continue until completion, cancellation, or a behavioral/provider safety failure. */
    var agentContinuousExecution: Boolean = true
    var agentMaxIterations: Int = 24
    var agentMaxToolCalls: Int = 32
    var agentMaxWallTimeSeconds: Int = 600
    var agentMaxToolTimeSeconds: Int = 300
    /** Retained for settings-file compatibility; production workflows always normalize to unlimited. */
    var agentMaxInputTokens: Long = UNLIMITED_WORKFLOW_TOKENS
    /** Retained for settings-file compatibility; production workflows always normalize to unlimited. */
    var agentMaxOutputTokens: Long = UNLIMITED_WORKFLOW_TOKENS
    var agentProviderMaxAttempts: Int = 3
    /** Retained for settings-file compatibility; local per-run cost limits are disabled. */
    var agentMaxRunCostUsd: Double = 0.0
    var agentCostWarningPercent: Int = 80
    var commitAiEnabled: Boolean = true
    var commitIncludeBody: Boolean = true
    var commitLanguage: String = "Auto"
    var commitPrompt: String = DEFAULT_COMMIT_PROMPT
}

data class McpServerConfig(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val command: String,
    val arguments: List<String>,
    val environmentKeys: Set<String>,
    val workingDirectory: String,
    val transport: McpTransport = McpTransport.STDIO,
    val url: String = "",
    val httpAuthMode: McpHttpAuthMode = McpHttpAuthMode.BEARER,
    val oauthClientId: String = "",
    val oauthScopes: List<String> = emptyList(),
)

enum class McpTransport(val id: String, val displayName: String) {
    STDIO("stdio", "stdio（本地进程）"),
    HTTP("http", "Streamable HTTP"),
    ;

    override fun toString(): String = displayName

    companion object {
        fun fromId(value: String): McpTransport = entries.firstOrNull {
            it.id.equals(value.trim(), ignoreCase = true) || it.name.equals(value.trim(), ignoreCase = true)
        } ?: STDIO
    }
}

enum class McpHttpAuthMode(val id: String, val displayName: String) {
    NONE("none", "无认证"),
    BEARER("bearer", "Bearer Token"),
    OAUTH("oauth", "OAuth 2.1 / PKCE"),
    ;

    override fun toString(): String = displayName

    companion object {
        fun fromId(value: String): McpHttpAuthMode = entries.firstOrNull {
            it.id.equals(value.trim(), ignoreCase = true) || it.name.equals(value.trim(), ignoreCase = true)
        } ?: BEARER
    }
}

data class PromptTemplate(
    val id: String,
    val name: String,
    val shortcut: String,
    val content: String,
)

data class SkillSource(
    val id: String,
    val name: String,
    val path: String,
    val enabled: Boolean,
)

data class ModelPricing(
    val providerId: String,
    val modelPattern: String,
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
)

data class CommitAiSettings(
    val enabled: Boolean,
    val includeBody: Boolean,
    val language: String,
    val prompt: String,
)

data class AgentRuntimeSettings(
    val continuousExecution: Boolean,
    val maxIterations: Int,
    val maxToolCalls: Int,
    val maxWallTimeSeconds: Int,
    val maxToolTimeSeconds: Int,
    val maxInputTokens: Long,
    val maxOutputTokens: Long,
    val providerMaxAttempts: Int,
    val maxRunCostUsd: Double?,
    val costWarningRatio: Double,
)

data class OmniCodePlatformSnapshot(
    val sandboxMode: SandboxMode,
    val historyEnabled: Boolean,
    val historyRetention: Int,
    val usageRetentionDays: Int,
    val mcpServers: List<McpServerConfig>,
    val promptTemplates: List<PromptTemplate>,
    val skillSources: List<SkillSource>,
    val pricing: List<ModelPricing>,
    val agentRuntime: AgentRuntimeSettings,
    val commitAi: CommitAiSettings,
)

@Service(Service.Level.APP)
@State(
    name = "OmniCodePlatformSettings",
    storages = [Storage("omnicode-platform.xml")],
)
class OmniCodePlatformSettingsService : PersistentStateComponent<OmniCodePlatformSettingsState> {
    private var current = defaultState()

    override fun getState(): OmniCodePlatformSettingsState = current

    override fun loadState(state: OmniCodePlatformSettingsState) {
        current = OmniCodePlatformSettingsState().also { XmlSerializerUtil.copyBean(state, it) }
        normalize(current)
    }

    @Synchronized
    fun snapshot(): OmniCodePlatformSnapshot {
        val state = current
        return OmniCodePlatformSnapshot(
            sandboxMode = runCatching { SandboxMode.valueOf(state.sandboxMode) }
                .getOrDefault(SandboxMode.WORKSPACE_WRITE),
            historyEnabled = state.historyEnabled,
            historyRetention = state.historyRetention.coerceIn(1, 1_000),
            usageRetentionDays = state.usageRetentionDays.coerceIn(1, 3_650),
            mcpServers = state.mcpServers.map { server ->
                McpServerConfig(
                    id = server.id,
                    name = server.name.trim().ifBlank { "MCP Server" },
                    enabled = server.enabled,
                    command = server.command.trim(),
                    arguments = parseCommandLine(server.arguments),
                    environmentKeys = server.environmentKeys
                        .split(',', '\n')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .toSet(),
                    workingDirectory = server.workingDirectory.trim().ifBlank { "." },
                    transport = McpTransport.fromId(server.transport),
                    url = server.url.trim(),
                    httpAuthMode = McpHttpAuthMode.fromId(server.httpAuthMode),
                    oauthClientId = server.oauthClientId.trim(),
                    oauthScopes = normalizeOAuthScopes(server.oauthScopes),
                )
            },
            promptTemplates = state.promptTemplates.map { prompt ->
                PromptTemplate(
                    id = prompt.id,
                    name = prompt.name.trim().ifBlank { "Prompt" },
                    shortcut = prompt.shortcut.trim().removePrefix("!").ifBlank { "prompt" },
                    content = prompt.content,
                )
            },
            skillSources = state.skillSources.map { skill ->
                SkillSource(skill.id, skill.name.trim().ifBlank { "Skill library" }, skill.path.trim(), skill.enabled)
            },
            pricing = state.pricing.map { price ->
                ModelPricing(
                    providerId = price.providerId.trim(),
                    modelPattern = price.modelPattern.trim().ifBlank { "*" },
                    // Preserve invalid persisted values so pricing enforcement can fail closed;
                    // silently clamping one negative side to zero would understate the hard limit.
                    inputUsdPerMillion = price.inputUsdPerMillion,
                    outputUsdPerMillion = price.outputUsdPerMillion,
                )
            },
            agentRuntime = AgentRuntimeSettings(
                continuousExecution = state.agentContinuousExecution,
                maxIterations = state.agentMaxIterations,
                maxToolCalls = state.agentMaxToolCalls,
                maxWallTimeSeconds = state.agentMaxWallTimeSeconds,
                maxToolTimeSeconds = state.agentMaxToolTimeSeconds,
                maxInputTokens = UNLIMITED_WORKFLOW_TOKENS,
                maxOutputTokens = UNLIMITED_WORKFLOW_TOKENS,
                providerMaxAttempts = state.agentProviderMaxAttempts,
                maxRunCostUsd = null,
                costWarningRatio = state.agentCostWarningPercent / 100.0,
            ),
            commitAi = CommitAiSettings(
                enabled = state.commitAiEnabled,
                includeBody = state.commitIncludeBody,
                language = state.commitLanguage.trim().ifBlank { "Auto" },
                prompt = state.commitPrompt.trim().ifBlank { DEFAULT_COMMIT_PROMPT },
            ),
        )
    }

    @Synchronized
    fun update(transform: (OmniCodePlatformSettingsState) -> Unit) {
        transform(current)
        normalize(current)
    }

    @Synchronized
    fun isMcpLaunchTrusted(serverId: String, projectId: String, fingerprint: String): Boolean =
        current.mcpLaunchTrusts.any { trust ->
            trust.serverId == serverId &&
                trust.projectId == projectId &&
                trust.fingerprint == fingerprint
        }

    @Synchronized
    fun trustMcpLaunch(serverId: String, projectId: String, fingerprint: String, trustedAtEpochMillis: Long) {
        require(serverId.isNotBlank() && projectId.isNotBlank() && fingerprint.isNotBlank())
        current.mcpLaunchTrusts.removeIf { trust ->
            trust.serverId == serverId && trust.projectId == projectId
        }
        current.mcpLaunchTrusts += McpLaunchTrustState().also { trust ->
            trust.serverId = serverId
            trust.projectId = projectId
            trust.fingerprint = fingerprint
            trust.trustedAtEpochMillis = trustedAtEpochMillis.coerceAtLeast(0L)
        }
        normalizeMcpLaunchTrusts(current)
    }

    @Synchronized
    fun clearMcpLaunchTrusts(serverId: String? = null): Int {
        val before = current.mcpLaunchTrusts.size
        if (serverId == null) {
            current.mcpLaunchTrusts.clear()
        } else {
            current.mcpLaunchTrusts.removeIf { it.serverId == serverId }
        }
        return before - current.mcpLaunchTrusts.size
    }

    @Synchronized
    fun mcpLaunchTrustCount(serverId: String? = null): Int = if (serverId == null) {
        current.mcpLaunchTrusts.size
    } else {
        current.mcpLaunchTrusts.count { it.serverId == serverId }
    }

    private fun normalize(state: OmniCodePlatformSettingsState) {
        state.sandboxMode = runCatching { SandboxMode.valueOf(state.sandboxMode).name }
            .getOrDefault(SandboxMode.WORKSPACE_WRITE.name)
        state.historyRetention = state.historyRetention.coerceIn(1, 1_000)
        state.usageRetentionDays = state.usageRetentionDays.coerceIn(1, 3_650)
        state.agentMaxIterations = state.agentMaxIterations.coerceIn(1, 128)
        state.agentMaxToolCalls = state.agentMaxToolCalls.coerceIn(1, 256)
        state.agentMaxWallTimeSeconds = state.agentMaxWallTimeSeconds.coerceIn(30, 3_600)
        state.agentMaxToolTimeSeconds = state.agentMaxToolTimeSeconds.coerceIn(5, 1_800)
        // Migrate every legacy finite task quota to usage-only accounting. Provider context/output
        // limits and operational loop guards remain independent execution boundaries.
        state.agentMaxInputTokens = UNLIMITED_WORKFLOW_TOKENS
        state.agentMaxOutputTokens = UNLIMITED_WORKFLOW_TOKENS
        state.agentProviderMaxAttempts = state.agentProviderMaxAttempts.coerceIn(1, 5)
        state.agentMaxRunCostUsd = 0.0
        state.agentCostWarningPercent = state.agentCostWarningPercent.coerceIn(1, 100)
        state.mcpServers.forEach {
            if (it.id.isBlank()) it.id = UUID.randomUUID().toString()
            it.transport = McpTransport.fromId(it.transport).id
            it.httpAuthMode = McpHttpAuthMode.fromId(it.httpAuthMode).id
            it.oauthClientId = it.oauthClientId.trim().take(MAX_OAUTH_CLIENT_ID_CHARS)
                .takeIf { value -> value.none(Char::isISOControl) }
                .orEmpty()
            it.oauthScopes = normalizeOAuthScopes(it.oauthScopes).joinToString(" ")
        }
        normalizeMcpLaunchTrusts(state)
        state.promptTemplates.forEach { if (it.id.isBlank()) it.id = UUID.randomUUID().toString() }
        ensureBuiltInPromptTemplates(state)
        state.skillSources.forEach { if (it.id.isBlank()) it.id = UUID.randomUUID().toString() }
    }

    /**
     * Keep high-value workflows discoverable for existing installations without overwriting
     * user-authored templates that happen to use the same shortcut.
     */
    private fun ensureBuiltInPromptTemplates(state: OmniCodePlatformSettingsState) {
        if (state.promptTemplates.none { it.shortcut.trim().removePrefix("!").equals("semi-design", ignoreCase = true) }) {
            state.promptTemplates += PromptTemplateState().also {
                it.name = "Semi Design 图转码"
                it.shortcut = "semi-design"
                it.content = SEMI_DESIGN_IMAGE_TO_CODE_PROMPT
            }
        }
    }

    private fun normalizeMcpLaunchTrusts(state: OmniCodePlatformSettingsState) {
        val validServerIds = state.mcpServers.mapTo(hashSetOf()) { it.id }
        val normalized = state.mcpLaunchTrusts
            .asSequence()
            .filter { trust ->
                trust.serverId in validServerIds &&
                    trust.projectId.isNotBlank() &&
                    trust.fingerprint.matches(SHA256_HEX)
            }
            .distinctBy { trust -> trust.serverId to trust.projectId }
            .sortedByDescending(McpLaunchTrustState::trustedAtEpochMillis)
            .take(MAX_MCP_LAUNCH_TRUSTS)
            .toMutableList()
        state.mcpLaunchTrusts = normalized
    }

    companion object {
        fun getInstance(): OmniCodePlatformSettingsService =
            ApplicationManager.getApplication().getService(OmniCodePlatformSettingsService::class.java)

        private fun defaultState(): OmniCodePlatformSettingsState = OmniCodePlatformSettingsState().apply {
            promptTemplates += PromptTemplateState().also {
                it.name = "Explain selected code"
                it.shortcut = "explain"
                it.content = "Explain the selected code, its invariants, edge cases, and likely failure modes."
            }
            promptTemplates += PromptTemplateState().also {
                it.name = "Semi Design 图转码"
                it.shortcut = "semi-design"
                it.content = SEMI_DESIGN_IMAGE_TO_CODE_PROMPT
            }
            skillSources += SkillSourceState().also {
                it.name = "Personal skills"
                it.path = "~/.omnicode/skills"
            }
        }

        private val SHA256_HEX = Regex("[a-f0-9]{64}")
        private const val MAX_OAUTH_CLIENT_ID_CHARS = 2_048
        private const val MAX_MCP_LAUNCH_TRUSTS = 256
    }
}

private val SEMI_DESIGN_IMAGE_TO_CODE_PROMPT = """
请根据当前附加的界面截图，将它还原为可维护、可运行的 Semi Design React 代码。

工作顺序：
1. 先描述从图片中能确认的布局、层级、间距、颜色、状态和交互；不臆测看不见的业务数据。
2. 检查项目已有的前端框架、入口、路由、样式约定和 package.json，优先复用已有依赖与组件，不要擅自安装新包。
3. 使用 Semi Design 组件（如 Layout、Nav、Card、Form、Table、Button、Modal、Typography 等）表达结构；复杂视觉效果用项目现有 CSS/主题变量实现。
4. 给出图片区域到组件的映射、建议文件路径和关键代码；若用户要求直接实现，再通过现有变更审阅流程修改文件。
5. 保留响应式布局、键盘可达性、空/加载/错误状态和中文文案；不要把图片中的密钥、个人信息或隐私内容写入代码。

输出先给“实现方案”和“文件清单”，再给代码或执行变更。没有附加图片时，请提示用户先上传截图。
""".trimIndent()

internal fun parseCommandLine(value: String): List<String> {
    val result = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var escaping = false
    var tokenStarted = false
    value.forEach { char ->
        when {
            escaping -> {
                token.append(char)
                escaping = false
                tokenStarted = true
            }
            char == '\\' -> escaping = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '\'' || char == '"') -> {
                quote = char
                tokenStarted = true
            }
            quote == null && char.isWhitespace() -> {
                if (tokenStarted) {
                    result += token.toString()
                    token.setLength(0)
                    tokenStarted = false
                }
            }
            else -> {
                token.append(char)
                tokenStarted = true
            }
        }
    }
    require(!escaping && quote == null) { "Arguments contain an unfinished escape or quote" }
    if (tokenStarted) result += token.toString()
    return result
}

internal fun normalizeOAuthScopes(value: String): List<String> = value
    .split(Regex("[\\s,]+"))
    .asSequence()
    .map(String::trim)
    .filter(OAUTH_SCOPE_TOKEN::matches)
    .distinct()
    .take(128)
    .toList()

internal val OAUTH_SCOPE_TOKEN: Regex = Regex("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,256}")

const val DEFAULT_COMMIT_PROMPT: String =
    "Write a concise conventional Git commit message for the staged diff. Do not invent changes."
