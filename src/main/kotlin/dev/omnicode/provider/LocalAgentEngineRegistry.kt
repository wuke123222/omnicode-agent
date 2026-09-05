package dev.omnicode.provider

/**
 * Single source of truth for the eight local engines exposed by OmniCode 3.
 *
 * Detection, provider construction, model discovery and the WebView dependency page must use
 * this registry instead of maintaining independent lists. Capability flags describe only
 * operations implemented by the adapter; unsupported operations stay explicit rather than
 * silently falling back to a guessed command line.
 */
internal data class LocalAgentEngineContract(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val tool: CliTool,
    val transport: LocalAgentTransport,
    val versionArguments: List<String> = listOf("--version"),
    val modelDiscovery: LocalModelDiscovery,
    val supportsNativeResume: Boolean,
    val supportsNativeHistory: Boolean,
)

internal enum class LocalAgentTransport {
    ONE_SHOT_TEXT,
    ONE_SHOT_JSON,
    PERSISTENT_HOST_RPC,
}

internal enum class LocalModelDiscovery {
    NONE,
    OPENCODE_MODELS,
    OMP_MODELS_JSON,
    DSH_HOST_CATALOG,
}

internal object LocalAgentEngineRegistry {
    val all: List<LocalAgentEngineContract> = listOf(
        contract("claude", "Claude Code", ProviderProtocol.CLI_CLAUDE, CliTool.CLAUDE, LocalAgentTransport.ONE_SHOT_TEXT, LocalModelDiscovery.NONE, false),
        // `codex exec --json --ephemeral` is a bounded JSONL turn.  Treating it as plain text
        // made startup lifecycle events look like the answer and left the UI waiting for a
        // process exit that Codex intentionally keeps open while the model turn is pending.
        contract("codex", "Codex", ProviderProtocol.CLI_CODEX, CliTool.CODEX, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.NONE, false),
        contract("grok", "Grok CLI", ProviderProtocol.CLI_GROK, CliTool.GROK, LocalAgentTransport.ONE_SHOT_TEXT, LocalModelDiscovery.NONE, false),
        contract("kimi", "Kimi CLI", ProviderProtocol.CLI_KIMI, CliTool.KIMI, LocalAgentTransport.ONE_SHOT_TEXT, LocalModelDiscovery.NONE, false),
        contract("opencode", "OpenCode", ProviderProtocol.CLI_OPENCODE, CliTool.OPENCODE, LocalAgentTransport.PERSISTENT_HOST_RPC, LocalModelDiscovery.OPENCODE_MODELS, true),
        contract("pi", "Pi CLI", ProviderProtocol.CLI_PI, CliTool.PI, LocalAgentTransport.ONE_SHOT_TEXT, LocalModelDiscovery.NONE, false),
        contract("omp", "OMP CLI", ProviderProtocol.CLI_OMP, CliTool.OMP, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.OMP_MODELS_JSON, true),
        contract("dsh", "DSH", ProviderProtocol.CLI_DSH, CliTool.DSH, LocalAgentTransport.PERSISTENT_HOST_RPC, LocalModelDiscovery.DSH_HOST_CATALOG, true, false),
    )

    private val byProtocol = all.associateBy(LocalAgentEngineContract::protocol)
    private val byTool = all.associateBy(LocalAgentEngineContract::tool)

    fun forProtocol(protocol: ProviderProtocol): LocalAgentEngineContract? = byProtocol[protocol]

    fun forTool(tool: CliTool): LocalAgentEngineContract? = byTool[tool]

    private fun contract(
        id: String,
        displayName: String,
        protocol: ProviderProtocol,
        tool: CliTool,
        transport: LocalAgentTransport,
        modelDiscovery: LocalModelDiscovery,
        supportsNativeResume: Boolean,
        supportsNativeHistory: Boolean = false,
    ) = LocalAgentEngineContract(
        id = id,
        displayName = displayName,
        protocol = protocol,
        tool = tool,
        transport = transport,
        modelDiscovery = modelDiscovery,
        supportsNativeResume = supportsNativeResume,
        supportsNativeHistory = supportsNativeHistory,
    )
}
