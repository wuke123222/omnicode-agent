package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalAgentEngineRegistryTest {
    @Test
    fun `the public local catalog contains exactly the eight CCGUI baseline engines`() {
        assertEquals(
            listOf("claude", "codex", "grok", "kimi", "opencode", "pi", "omp", "dsh"),
            LocalAgentEngineRegistry.all.map(LocalAgentEngineContract::id),
        )
        assertEquals(8, LocalAgentEngineRegistry.all.map(LocalAgentEngineContract::protocol).distinct().size)
        assertEquals(8, LocalAgentEngineRegistry.all.map(LocalAgentEngineContract::tool).distinct().size)
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
        assertEquals(true, dsh.supportsNativeResume)
        assertEquals(false, dsh.supportsNativeHistory)
    }

    @Test
    fun `OpenCode uses the direct CCGUI JSON stream`() {
        val openCode = assertNotNull(LocalAgentEngineRegistry.forProtocol(ProviderProtocol.CLI_OPENCODE))
        assertEquals(LocalAgentTransport.ONE_SHOT_JSON, openCode.transport)
        assertEquals(LocalModelDiscovery.OPENCODE_MODELS, openCode.modelDiscovery)
        assertEquals(true, openCode.supportsNativeResume)
    }
}
