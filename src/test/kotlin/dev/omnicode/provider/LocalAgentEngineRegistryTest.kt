package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalAgentEngineRegistryTest {
    @Test
    fun `the public local catalog contains the CCGUI baseline plus Gemini CLI`() {
        assertEquals(
            listOf("claude", "codex", "gemini", "grok", "kimi", "opencode", "pi", "omp", "dsh"),
            LocalAgentEngineRegistry.all.map(LocalAgentEngineContract::id),
        )
        assertEquals(9, LocalAgentEngineRegistry.all.map(LocalAgentEngineContract::protocol).distinct().size)
        assertEquals(9, LocalAgentEngineRegistry.all.map(LocalAgentEngineContract::tool).distinct().size)
    }

    @Test
    fun `each local provider protocol resolves to the same runtime contract used by detection`() {
        val protocols = ProviderPresets.all.map(ProviderPreset::protocol).filter { it.name.startsWith("CLI_") }
        protocols.forEach { protocol ->
            val contract = assertNotNull(LocalAgentEngineRegistry.forProtocol(protocol), protocol.name)
            assertTrue(contract.versionArguments.isNotEmpty())
        }
    }

    @Test
    fun `DSH is explicit persistent host transport rather than a guessed one shot command`() {
        val dsh = assertNotNull(LocalAgentEngineRegistry.forProtocol(ProviderProtocol.CLI_DSH))
        assertEquals(LocalAgentTransport.PERSISTENT_HOST_RPC, dsh.transport)
        assertEquals(LocalModelDiscovery.DSH_HOST_CATALOG, dsh.modelDiscovery)
        assertEquals(LocalSessionContinuity.PERSISTENT_HOST, dsh.sessionContinuity)
        assertEquals(true, dsh.supportsNativeResume)
        assertEquals(false, dsh.supportsNativeHistory)
    }

    @Test
    fun `OpenCode uses the direct CCGUI JSON stream`() {
        val openCode = assertNotNull(LocalAgentEngineRegistry.forProtocol(ProviderProtocol.CLI_OPENCODE))
        assertEquals(LocalAgentTransport.ONE_SHOT_JSON, openCode.transport)
        assertEquals(LocalModelDiscovery.OPENCODE_MODELS, openCode.modelDiscovery)
        assertEquals(LocalSessionContinuity.NATIVE_SESSION_ID, openCode.sessionContinuity)
        assertEquals(true, openCode.supportsNativeResume)
    }

    @Test
    fun `Codex model discovery uses the authenticated app server catalog`() {
        val codex = assertNotNull(LocalAgentEngineRegistry.forProtocol(ProviderProtocol.CLI_CODEX))
        assertEquals(LocalModelDiscovery.CODEX_APP_SERVER_MODELS, codex.modelDiscovery)
        assertTrue(ProviderModelDiscovery.supportsModelDiscovery(ProviderProtocol.CLI_CODEX))
        assertEquals(LocalHistoryDiscovery.CODEX_APP_SERVER, codex.historyDiscovery)
        assertTrue(codex.supportsNativeHistory)
    }

    @Test
    fun `native history is advertised only for stable bounded protocols`() {
        assertEquals(
            mapOf(
                "codex" to LocalHistoryDiscovery.CODEX_APP_SERVER,
                "gemini" to LocalHistoryDiscovery.GEMINI_SESSIONS,
                "opencode" to LocalHistoryDiscovery.OPENCODE_JSON,
            ),
            LocalAgentEngineRegistry.all
                .filter(LocalAgentEngineContract::supportsNativeHistory)
                .associate { it.id to it.historyDiscovery },
        )
    }
}
