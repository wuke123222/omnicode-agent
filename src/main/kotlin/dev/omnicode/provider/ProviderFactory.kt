package dev.omnicode.provider

import dev.omnicode.agent.AgentMode
import dev.omnicode.tool.ApprovalGate
import java.nio.file.Path

object ProviderFactory {
    fun create(
        connection: ProviderConnection,
        nativeCodexContext: CodexNativeExecutionContext? = null,
        cliWorkingDirectory: Path? = null,
        localCliSession: LocalCliSessionContext? = null,
        approvalGate: ApprovalGate? = null,
        agentMode: AgentMode = AgentMode.AGENT,
    ): ModelProvider {
        LocalAgentEngineRegistry.forProtocol(connection.preset.protocol)?.let { engine ->
            return when (engine.protocol) {
                // Match CCGUI: one direct `opencode run --format json` stream per turn. A
                // second local `opencode serve` host adds a health/SSE handshake before the
                // prompt reaches the model and is the source of the long initialization spinner.
                ProviderProtocol.CLI_OPENCODE -> CliToolProvider(
                    connection = connection,
                    cliTool = engine.tool,
                    workingDirectory = cliWorkingDirectory,
                    localSession = localCliSession,
                    agentMode = agentMode,
                )
                ProviderProtocol.CLI_CODEX -> nativeCodexContext?.let {
                    // CCGUI keeps Codex on its native app-server JSON-RPC session. Use the same
                    // protocol for an actual project run; callers without a project context
                    // (settings/diagnostics) retain the bounded `codex exec` fallback below.
                    CodexNativeProvider(connection, it)
                } ?: CliToolProvider(connection, engine.tool, cliWorkingDirectory, localCliSession, agentMode)
                ProviderProtocol.CLI_DSH -> DshHostProvider(
                    connection = connection,
                    workingDirectory = cliWorkingDirectory,
                    localSession = localCliSession,
                    approvalGate = approvalGate,
                    agentMode = agentMode,
                )
                else -> CliToolProvider(connection, engine.tool, cliWorkingDirectory, localCliSession, agentMode)
            }
        }
        return when (connection.preset.protocol) {
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
        ProviderProtocol.CLI_OPENCODE,
        ProviderProtocol.CLI_CLAUDE,
        ProviderProtocol.CLI_CODEX,
        ProviderProtocol.CLI_KIMI,
        ProviderProtocol.CLI_GROK,
        ProviderProtocol.CLI_PI,
        ProviderProtocol.CLI_OMP,
        ProviderProtocol.CLI_DSH,
        -> error("Local provider registry is incomplete for ${connection.preset.protocol}")
        ProviderProtocol.CLI_QODER -> CliToolProvider(connection, CliTool.QODER, cliWorkingDirectory)
        }
    }
}
