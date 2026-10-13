package dev.omnicode.settings

import com.google.gson.JsonParser
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ProviderPreset
import dev.omnicode.provider.ReasoningEffort
import dev.omnicode.provider.canonicalModelApiOrigin
import dev.omnicode.provider.isLoopbackModelApiOrigin
import dev.omnicode.provider.modelApiBaseUrlValidationError
import dev.omnicode.provider.reasoningEffortOptions

internal data class NormalizedApiKeyInput(val value: String, val sourceVariable: String? = null)

internal data class ResolvedProviderSecrets(
    val secrets: ProviderSecrets,
    val environmentVariable: String? = null,
    val blockedEnvironmentVariable: String? = null,
)

internal class CredentialInputFormatException(message: String) : IllegalArgumentException(message)

internal fun normalizeApiKeyInput(rawValue: String, providerId: String): NormalizedApiKeyInput {
    val value = rawValue.trim()
    if (value.isEmpty()) return NormalizedApiKeyInput("")
    if (value.startsWith("{")) {
        val objectValue = runCatching { JsonParser.parseString(value) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw CredentialInputFormatException("API Key JSON 格式无效，请粘贴纯 Key 或 {\"OPENAI_API_KEY\": \"…\"}。")
        val candidates = objectValue.entrySet().mapNotNull { (name, element) ->
            name.takeIf(::looksLikeApiKeyVariable)?.let {
                val secret = element.takeIf { it.isJsonPrimitive }?.runCatching { asString }?.getOrNull()
                Triple(name.uppercase(), secret.orEmpty().trim(), name)
            }
        }
        if (candidates.isEmpty()) throw CredentialInputFormatException("JSON 中没有可识别的 *_API_KEY 字段。")
        val allowed = providerApiKeyEnvironmentVariables(providerId).toSet()
        val selected = candidates.firstOrNull { it.first in allowed } ?: candidates.singleOrNull()
            ?: throw CredentialInputFormatException("JSON 中包含多个 API Key，请只保留当前供应商对应的一项。")
        validateVariableForProvider(selected.first, providerId)
        if (selected.second.isBlank()) throw CredentialInputFormatException("${selected.third} 的值为空。")
        return NormalizedApiKeyInput(selected.second, selected.first)
    }
    ENV_ASSIGNMENT.matchEntire(value)?.let { match ->
        val variable = match.groupValues[1].uppercase()
        if (looksLikeApiKeyVariable(variable)) {
            validateVariableForProvider(variable, providerId)
            val secret = unwrapQuoted(match.groupValues[2].trim())
            if (secret.isBlank()) throw CredentialInputFormatException("$variable 的值为空。")
            return NormalizedApiKeyInput(secret, variable)
        }
    }
    return NormalizedApiKeyInput(unwrapQuoted(value))
}

/** Password Safe wins; process environment variables are non-persistent fallbacks only. */
internal fun resolveProviderSecrets(
    providerId: String,
    stored: ProviderSecrets,
    baseUrl: String = ProviderPresets.byId(providerId).defaultBaseUrl,
    environment: (String) -> String? = System::getenv,
): ResolvedProviderSecrets {
    if (stored.apiKey.isNotBlank()) return ResolvedProviderSecrets(stored)
    val variables = providerApiKeyEnvironmentVariables(providerId)
    if (!environmentFallbackAllowed(providerId, baseUrl)) {
        val blocked = variables.firstOrNull { environment(it)?.trim().orEmpty().isNotBlank() }
        return ResolvedProviderSecrets(stored, blockedEnvironmentVariable = blocked)
    }
    variables.forEach { variable ->
        environment(variable)?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            return ResolvedProviderSecrets(stored.copy(apiKey = value), variable)
        }
    }
    return ResolvedProviderSecrets(stored)
}

internal fun environmentFallbackAllowed(providerId: String, baseUrl: String): Boolean {
    val target = runCatching { canonicalModelApiOrigin(baseUrl) }.getOrNull() ?: return false
    val default = runCatching {
        canonicalModelApiOrigin(ProviderPresets.byId(providerId).defaultBaseUrl)
    }.getOrNull() ?: return false
    return target == default || isLoopbackModelApiOrigin(target)
}

internal fun blockedEnvironmentCredentialMessage(variable: String, baseUrl: String): String {
    val origin = runCatching { canonicalModelApiOrigin(baseUrl) }.getOrElse { baseUrl.trim().take(120) }
    return "安全保护：未将环境变量 $variable 自动发送到非默认远程地址 $origin。" +
        "请在供应商配置中显式输入并保存该地址使用的 API Key。"
}

internal fun providerApiKeyEnvironmentVariables(providerId: String): List<String> = when (providerId) {
    "openai" -> listOf("OPENAI_API_KEY")
    "opencode" -> listOf("OPENCODE_API_KEY")
    "anthropic" -> listOf("ANTHROPIC_API_KEY")
    "gemini" -> listOf("GEMINI_API_KEY", "GOOGLE_API_KEY")
    "azure" -> listOf("AZURE_OPENAI_API_KEY")
    "deepseek" -> listOf("DEEPSEEK_API_KEY")
    "groq" -> listOf("GROQ_API_KEY")
    "xai" -> listOf("XAI_API_KEY")
    "mistral" -> listOf("MISTRAL_API_KEY")
    "openrouter" -> listOf("OPENROUTER_API_KEY")
    "together" -> listOf("TOGETHER_API_KEY")
    "cerebras" -> listOf("CEREBRAS_API_KEY")
    "qwen" -> listOf("DASHSCOPE_API_KEY", "QWEN_API_KEY")
    "moonshot" -> listOf("MOONSHOT_API_KEY")
    "siliconflow" -> listOf("SILICONFLOW_API_KEY")
    "zhipu" -> listOf("ZHIPU_API_KEY")
    "qianfan" -> listOf("QIANFAN_API_KEY")
    "hunyuan" -> listOf("HUNYUAN_API_KEY")
    "volcengine" -> listOf("ARK_API_KEY", "VOLCENGINE_API_KEY")
    "nvidia" -> listOf("NVIDIA_API_KEY")
    "fireworks" -> listOf("FIREWORKS_API_KEY")
    "custom" -> listOf("OPENAI_API_KEY")
    else -> emptyList()
}

private fun validateVariableForProvider(variable: String, providerId: String) {
    val allowed = providerApiKeyEnvironmentVariables(providerId)
    if (variable in allowed || allowed.isEmpty()) return
    throw CredentialInputFormatException(
        "检测到 $variable，但当前供应商不是它对应的服务。请切换供应商，或改用 ${allowed.joinToString(" / ")}。",
    )
}

private fun looksLikeApiKeyVariable(value: String): Boolean =
    value.uppercase().let { it.endsWith("_API_KEY") || it == "GOOGLE_API_KEY" }

private fun unwrapQuoted(value: String): String = when {
    value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.substring(1, value.lastIndex)
    value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.substring(1, value.lastIndex)
    else -> value
}.trim()

private val ENV_ASSIGNMENT = Regex("^(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

internal fun credentialOriginChanged(previousBaseUrl: String?, currentBaseUrl: String): Boolean {
    if (previousBaseUrl.isNullOrBlank()) return false
    val previous = runCatching { canonicalModelApiOrigin(previousBaseUrl) }.getOrNull() ?: return true
    val current = runCatching { canonicalModelApiOrigin(currentBaseUrl) }.getOrNull() ?: return true
    return previous != current
}

internal fun credentialBaselineAfterSaveAttempt(
    saved: ProviderSecrets,
    draft: ProviderSecrets,
    saveRequested: Boolean,
    saveSucceeded: Boolean,
): ProviderSecrets = if (saveRequested && saveSucceeded) draft else saved

internal fun modelAuthenticationError(preset: ProviderPreset, baseUrl: String): String =
    if (preset.id == "openai") {
        "OpenAI 返回 HTTP 401。请确认 Base URL 为 https://api.openai.com/v1，并使用 " +
            "platform.openai.com/api-keys 创建的 API Key；ChatGPT 登录或订阅凭据不能用于 API。"
    } else {
        "${preset.displayName} 返回 HTTP 401。请确认该 Key 属于当前供应商，并检查 Base URL：" +
            baseUrl.take(120).ifBlank { preset.defaultBaseUrl }
    }

internal data class ReasoningEffortEditorState(
    val options: List<ReasoningEffort>,
    val selected: ReasoningEffort,
    val unsupportedSelection: Boolean,
)

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

private fun reasoningEffortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.AUTO -> "Auto（模型默认）"
    ReasoningEffort.NONE -> "None（关闭推理）"
    ReasoningEffort.MINIMAL -> "Minimal（极简）"
    ReasoningEffort.LOW -> "Low（快速）"
    ReasoningEffort.MEDIUM -> "Medium（均衡）"
    ReasoningEffort.HIGH -> "High（深入）"
    ReasoningEffort.XHIGH -> "XHigh（极高）"
    ReasoningEffort.MAX -> "Max（模型最高档）"
}
