package dev.omnicode.settings

import com.google.gson.JsonParser
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.canonicalModelApiOrigin
import dev.omnicode.provider.isLoopbackModelApiOrigin

internal data class NormalizedApiKeyInput(
    val value: String,
    val sourceVariable: String? = null,
)

internal data class ResolvedProviderSecrets(
    val secrets: ProviderSecrets,
    val environmentVariable: String? = null,
    val blockedEnvironmentVariable: String? = null,
)

internal class CredentialInputFormatException(message: String) : IllegalArgumentException(message)

internal class UnsafeEnvironmentCredentialTargetException(message: String) : IllegalStateException(message)

/**
 * Accepts the common forms users copy from provider setup guides while ensuring that a named
 * environment variable is not silently saved under the wrong provider profile.
 */
internal fun normalizeApiKeyInput(rawValue: String, providerId: String): NormalizedApiKeyInput {
    val value = rawValue.trim()
    if (value.isEmpty()) return NormalizedApiKeyInput("")

    if (value.startsWith("{")) {
        val objectValue = runCatching { JsonParser.parseString(value) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw CredentialInputFormatException("API Key JSON 格式无效，请粘贴纯 Key 或 {\"OPENAI_API_KEY\": \"…\"}。")
        val candidates = objectValue.entrySet().mapNotNull { (name, element) ->
            name.takeIf(::looksLikeApiKeyVariable)?.let {
                val secret = element.takeIf { it.isJsonPrimitive }?.runCatching { asString }?.getOrNull()
                Triple(name.uppercase(), secret.orEmpty().trim(), name)
            }
        }
        if (candidates.isEmpty()) {
            throw CredentialInputFormatException("JSON 中没有可识别的 *_API_KEY 字段。")
        }
        val allowed = providerApiKeyEnvironmentVariables(providerId).toSet()
        val selected = candidates.firstOrNull { it.first in allowed }
            ?: candidates.singleOrNull()
            ?: throw CredentialInputFormatException("JSON 中包含多个 API Key，请只保留当前供应商对应的一项。")
        validateVariableForProvider(selected.first, providerId)
        if (selected.second.isBlank()) {
            throw CredentialInputFormatException("${selected.third} 的值为空。")
        }
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

/** Password Safe wins; a process environment variable is only a non-persistent fallback. */
internal fun resolveProviderSecrets(
    providerId: String,
    stored: ProviderSecrets,
    baseUrl: String = ProviderPresets.byId(providerId).defaultBaseUrl,
    environment: (String) -> String? = System::getenv,
): ResolvedProviderSecrets {
    if (stored.apiKey.isNotBlank()) return ResolvedProviderSecrets(stored)
    val variables = providerApiKeyEnvironmentVariables(providerId)
    if (!environmentFallbackAllowed(providerId, baseUrl)) {
        val blockedVariable = variables.firstOrNull { variable -> environment(variable)?.trim().orEmpty().isNotBlank() }
        return ResolvedProviderSecrets(stored, blockedEnvironmentVariable = blockedVariable)
    }
    variables.forEach { variable ->
        val value = environment(variable)?.trim().orEmpty()
        if (value.isNotBlank()) {
            return ResolvedProviderSecrets(stored.copy(apiKey = value), variable)
        }
    }
    return ResolvedProviderSecrets(stored)
}

/** Environment credentials may target only the provider default or an explicitly local endpoint. */
internal fun environmentFallbackAllowed(providerId: String, baseUrl: String): Boolean {
    val targetOrigin = runCatching { canonicalModelApiOrigin(baseUrl) }.getOrNull() ?: return false
    val defaultOrigin = runCatching {
        canonicalModelApiOrigin(ProviderPresets.byId(providerId).defaultBaseUrl)
    }.getOrNull() ?: return false
    return targetOrigin == defaultOrigin || isLoopbackModelApiOrigin(targetOrigin)
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
    val expected = allowed.joinToString(" / ")
    throw CredentialInputFormatException(
        "检测到 $variable，但当前供应商不是它对应的服务。请切换供应商，或改用 $expected。",
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
