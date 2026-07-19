package dev.omnicode.provider

object ProviderFactory {
    fun create(connection: ProviderConnection): ModelProvider = when (connection.preset.protocol) {
        ProviderProtocol.OPENCODE_ZEN -> OpenCodeZenProvider(connection)
        ProviderProtocol.OPENAI_RESPONSES -> OpenAiResponsesProvider(connection)
        ProviderProtocol.OPENAI_CHAT, ProviderProtocol.AZURE_OPENAI -> OpenAiChatProvider(connection)
        ProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesProvider(connection)
        ProviderProtocol.GEMINI -> GeminiProvider(connection)
        ProviderProtocol.BEDROCK_CONVERSE -> BedrockConverseProvider(connection)
    }
}
