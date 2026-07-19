package dev.omnicode.provider

import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse

/**
 * OpenCode Zen exposes one model catalog backed by multiple wire protocols.
 * Keep the selected adapter alive for the lifetime of this provider so opaque
 * reasoning/tool replay state remains available across agent turns.
 */
class OpenCodeZenProvider(
    private val connection: ProviderConnection,
) : ModelProvider {
    override val id: String = connection.preset.id

    private val delegate: ModelProvider = when (openCodeZenAdapter(connection.model)) {
        OpenCodeZenAdapter.OPENAI_RESPONSES -> OpenAiResponsesProvider(connection)
        OpenCodeZenAdapter.ANTHROPIC_MESSAGES -> AnthropicMessagesProvider(
            connection.copy(apiVersion = OPENCODE_ZEN_ANTHROPIC_VERSION),
        )
        OpenCodeZenAdapter.GEMINI -> GeminiProvider(connection)
        OpenCodeZenAdapter.OPENAI_CHAT -> OpenAiChatProvider(connection)
    }

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse = delegate.complete(request, onTextDelta)
}

private const val OPENCODE_ZEN_ANTHROPIC_VERSION = "2023-06-01"

internal enum class OpenCodeZenAdapter {
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI,
    OPENAI_CHAT,
}

internal fun openCodeZenAdapter(model: String): OpenCodeZenAdapter {
    val normalized = model.trim().lowercase()
    return when {
        normalized.startsWith("gpt-") -> OpenCodeZenAdapter.OPENAI_RESPONSES
        normalized.startsWith("claude-") || normalized.startsWith("qwen3.") ->
            OpenCodeZenAdapter.ANTHROPIC_MESSAGES
        normalized.startsWith("gemini-") -> OpenCodeZenAdapter.GEMINI
        else -> OpenCodeZenAdapter.OPENAI_CHAT
    }
}
