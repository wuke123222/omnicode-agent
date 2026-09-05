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
    val sessionContinuity: LocalSessionContinuity,
    val supportsNativeHistory: Boolean = false,
) {
    val supportsNativeResume: Boolean
        get() = sessionContinuity != LocalSessionContinuity.BOUNDED_REPLAY
}

internal enum class LocalAgentTransport {
    ONE_SHOT_TEXT,
    ONE_SHOT_JSON,
    PERSISTENT_HOST_RPC,
}

/**
 * Truthful continuity contract exposed to the runtime and dependency page.
 *
 * A bounded replay adapter starts a new process and sends recent visible dialogue. Native
 * session-id adapters persist only the opaque CLI identity and ask that CLI to resume it. A
 * persistent host owns the conversation lifecycle itself. These modes must never be collapsed
 * into a single boolean because doing so makes a replay fallback look like a native session.
 */
internal enum class LocalSessionContinuity {
    BOUNDED_REPLAY,
    NATIVE_SESSION_ID,
    PERSISTENT_HOST,
}

internal enum class LocalModelDiscovery {
    NONE,
    OPENCODE_MODELS,
    KIMI_PROVIDER_CATALOG,
    PI_MODELS,
    OMP_MODELS_JSON,
    DSH_HOST_CATALOG,
}

internal object LocalAgentEngineRegistry {
    val all: List<LocalAgentEngineContract> = listOf(
        contract("claude", "Claude Code", ProviderProtocol.CLI_CLAUDE, CliTool.CLAUDE, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.NONE, LocalSessionContinuity.NATIVE_SESSION_ID),
        // Project runs use the native Codex app-server adapter when an approval/workspace context
        // is available; `codex exec --json --ephemeral` remains the bounded fallback used by
        // settings and diagnostics that cannot create an IDE project context.
        contract("codex", "Codex", ProviderProtocol.CLI_CODEX, CliTool.CODEX, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.NONE, LocalSessionContinuity.NATIVE_SESSION_ID),
        contract("grok", "Grok CLI", ProviderProtocol.CLI_GROK, CliTool.GROK, LocalAgentTransport.ONE_SHOT_TEXT, LocalModelDiscovery.NONE, LocalSessionContinuity.BOUNDED_REPLAY),
        contract("kimi", "Kimi CLI", ProviderProtocol.CLI_KIMI, CliTool.KIMI, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.KIMI_PROVIDER_CATALOG, LocalSessionContinuity.NATIVE_SESSION_ID),
        // CCGUI invokes `opencode run --format json` directly. Do not start a second
        // `opencode serve` process from the IDE; its health/SSE handshake can block a turn before
        // the user's prompt is sent.
        contract("opencode", "OpenCode", ProviderProtocol.CLI_OPENCODE, CliTool.OPENCODE, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.OPENCODE_MODELS, LocalSessionContinuity.NATIVE_SESSION_ID),
        contract("pi", "Pi CLI", ProviderProtocol.CLI_PI, CliTool.PI, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.PI_MODELS, LocalSessionContinuity.NATIVE_SESSION_ID),
        contract("omp", "OMP CLI", ProviderProtocol.CLI_OMP, CliTool.OMP, LocalAgentTransport.ONE_SHOT_JSON, LocalModelDiscovery.OMP_MODELS_JSON, LocalSessionContinuity.NATIVE_SESSION_ID),
        contract("dsh", "DSH", ProviderProtocol.CLI_DSH, CliTool.DSH, LocalAgentTransport.PERSISTENT_HOST_RPC, LocalModelDiscovery.DSH_HOST_CATALOG, LocalSessionContinuity.PERSISTENT_HOST),
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
        sessionContinuity: LocalSessionContinuity,
        supportsNativeHistory: Boolean = false,
    ) = LocalAgentEngineContract(
        id = id,
        displayName = displayName,
        protocol = protocol,
        tool = tool,
        transport = transport,
        modelDiscovery = modelDiscovery,
        sessionContinuity = sessionContinuity,
        supportsNativeHistory = supportsNativeHistory,
    )
}
