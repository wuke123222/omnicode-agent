package dev.omnicode.service

import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.settings.McpProviderScope
import kotlin.test.Test
import kotlin.test.assertEquals

class McpProviderScopeRoutingTest {
    @Test
    fun `provider-specific MCP servers cannot leak into other engines`() {
        assertEquals(
            setOf(McpProviderScope.ALL, McpProviderScope.CLAUDE),
            mcpProviderScopesFor(ProviderProtocol.CLI_CLAUDE),
        )
        assertEquals(
            setOf(McpProviderScope.ALL, McpProviderScope.CODEX),
            mcpProviderScopesFor(ProviderProtocol.CODEX_APP_SERVER),
        )
        assertEquals(
            setOf(McpProviderScope.ALL),
            mcpProviderScopesFor(ProviderProtocol.CLI_OPENCODE),
        )
    }
}
