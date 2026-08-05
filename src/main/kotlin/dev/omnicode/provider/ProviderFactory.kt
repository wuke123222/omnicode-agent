package dev.omnicode.provider

object ProviderFactory {
    fun create(
        connection: ProviderConnection,
        nativeCodexContext: CodexNativeExecutionContext? = null,
    ): ModelProvider = when (connection.preset.protocol) {
        ProviderProtocol.CODEX_APP_SERVER -> CodexNativeProvider(
            connection,
            nativeCodexContext ?: throw ProviderException(
                "Codex 原生后端需要项目工作区和审批上下文；请从 OmniCode 对话中启动任务。",
            ),
        )
        ProviderProtocol.OPENCODE_ZEN -> OpenCodeZenProvider(connection)
        ProviderProtocol.OPENAI_RESPONSES -> OpenAiResponsesProvider(connection)
        ProviderProtocol.OPENAI_CHAT, ProviderProtocol.AZURE_OPENAI -> OpenAiChatProvider(connection)
        ProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesProvider(connection)
        ProviderProtocol.GEMINI -> GeminiProvider(connection)
        ProviderProtocol.BEDROCK_CONVERSE -> BedrockConverseProvider(connection)
    }
}
