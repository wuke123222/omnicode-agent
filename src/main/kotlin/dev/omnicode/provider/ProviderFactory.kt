package dev.omnicode.provider

import java.nio.file.Path

object ProviderFactory {
    fun create(
        connection: ProviderConnection,
        nativeCodexContext: CodexNativeExecutionContext? = null,
        cliWorkingDirectory: Path? = null,
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
        ProviderProtocol.CLI_OPENCODE -> CliToolProvider(connection, CliTool.OPENCODE, cliWorkingDirectory)
        ProviderProtocol.CLI_KIMI -> CliToolProvider(connection, CliTool.KIMI, cliWorkingDirectory)
        ProviderProtocol.CLI_GROK -> CliToolProvider(connection, CliTool.GROK, cliWorkingDirectory)
        ProviderProtocol.CLI_PI -> CliToolProvider(connection, CliTool.PI, cliWorkingDirectory)
        ProviderProtocol.CLI_QODER -> CliToolProvider(connection, CliTool.QODER, cliWorkingDirectory)
    }
}
