package dev.omnicode.provider

import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse

enum class ProviderProtocol {
    OPENCODE_ZEN,
    OPENAI_RESPONSES,
    OPENAI_CHAT,
    ANTHROPIC_MESSAGES,
    GEMINI,
    AZURE_OPENAI,
    BEDROCK_CONVERSE,
}

/**
 * Provider-neutral user intent. [MAX] means the highest verified setting for the selected model;
 * adapters expose the effective provider value instead of pretending every API accepts `max`.
 */
enum class ReasoningEffort(val persistedValue: String) {
    AUTO("auto"),
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
    ;

    companion object {
        fun fromPersisted(value: String?): ReasoningEffort = entries.firstOrNull {
            it.persistedValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        } ?: AUTO
    }
}

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val apiKeyOptional: Boolean = false,
)

data class ProviderConnection(
    val preset: ProviderPreset,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val secondarySecret: String = "",
    val sessionToken: String = "",
    val region: String = "us-east-1",
    val apiVersion: String = "2025-04-01-preview",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
    val extraHeaders: Map<String, String> = emptyMap(),
    val requestTimeoutSeconds: Long = 120,
)

interface ModelProvider {
    val id: String

    suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit = {},
    ): ModelResponse
}

class ProviderException(
    message: String,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    cause: Throwable? = null,
    /** Parsed and bounded Retry-After delay. Raw response headers are never retained. */
    val retryAfterMillis: Long? = null,
    /** Bounded provider correlation id suitable for diagnostics; credentials and response headers are excluded. */
    val requestId: String? = null,
    /** True only for transport failures such as connect resets and request timeouts. */
    val networkFailure: Boolean = false,
    /** True when a dispatched request may have consumed model quota despite lacking usable usage. */
    val billingUncertain: Boolean = networkFailure || statusCode?.let { it in 500..599 } == true,
) : RuntimeException(message, cause) {
    val retryable: Boolean
        get() = networkFailure || statusCode == 429 || statusCode?.let { it in 500..599 } == true
}

internal fun ContentBlock.Image.dataUrl(): String = "data:$mediaType;base64,$base64Data"

/** Conservative client-side capability check. Unknown OpenAI-compatible models are treated as text-only. */
internal fun ProviderConnection.likelySupportsVision(): Boolean = when (preset.protocol) {
    ProviderProtocol.ANTHROPIC_MESSAGES,
    ProviderProtocol.GEMINI,
    ProviderProtocol.BEDROCK_CONVERSE,
    -> true
    ProviderProtocol.OPENAI_RESPONSES,
    ProviderProtocol.OPENAI_CHAT,
    ProviderProtocol.AZURE_OPENAI,
    ProviderProtocol.OPENCODE_ZEN,
    -> {
        val normalized = model.lowercase()
        normalized.startsWith("gpt-") || normalized.contains("claude") || normalized.contains("gemini") ||
            normalized.contains("vision") || normalized.contains("vl") || normalized.contains("multimodal")
    }
}
