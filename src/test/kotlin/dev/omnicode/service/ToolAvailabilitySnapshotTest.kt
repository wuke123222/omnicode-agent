package dev.omnicode.service

import dev.omnicode.agent.AgentMode
import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.tool.ToolDescriptor
import dev.omnicode.tool.ToolEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolAvailabilitySnapshotTest {
    private val database = ToolDescriptor("query_database", ToolEffect.EXTERNAL, true, "digest")
    private val patch = ToolDescriptor("apply_patch", ToolEffect.MUTATING, true, "digest")
    private val mcp = ToolDescriptor("mcp__approved__query", ToolEffect.EXTERNAL, true, "digest")

    @Test
    fun `configured database is not reported as ready without a stored password`() {
        val snapshot = toolAvailabilitySnapshot(
            descriptors = listOf(database, patch), mode = AgentMode.AGENT, transport = HostToolTransport.STRUCTURED,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 0,
            configuredMcpCount = 0, connectedMcpToolCount = 0, mcpErrorCount = 0,
        )

        assertEquals("configuration", snapshot.entries.single { it.name == "query_database" }.state)
        assertEquals("approval", snapshot.entries.single { it.name == "apply_patch" }.state)
        assertFalse(snapshot.note.contains("password", ignoreCase = true))
    }

    @Test
    fun `local cli reports only MCP tools exposed to the current turn`() {
        val snapshot = toolAvailabilitySnapshot(
            descriptors = listOf(database, mcp), mode = AgentMode.AGENT, transport = HostToolTransport.CLI_BRIDGE,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 1,
            configuredMcpCount = 1, connectedMcpToolCount = 1, mcpErrorCount = 0,
        )

        assertEquals("approval", snapshot.entries.single { it.name == "query_database" }.state)
        assertEquals("approval", snapshot.entries.single { it.name == "mcp" }.state)
        assertTrue(snapshot.entries.any { it.name == mcp.name })
    }

    @Test
    fun `restricted mode reports database as unavailable and omits unsafe MCP`() {
        val snapshot = toolAvailabilitySnapshot(
            descriptors = emptyList(), mode = AgentMode.PLAN, transport = HostToolTransport.STRUCTURED,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 1,
            configuredMcpCount = 1, connectedMcpToolCount = 0, mcpErrorCount = 0,
        )

        assertEquals("unsupported", snapshot.entries.single { it.name == "query_database" }.state)
        assertEquals("unsupported", snapshot.entries.single { it.name == "mcp" }.state)
        assertTrue(snapshot.entries.all { it.detail.length <= 240 })
    }

    @Test
    fun `native Codex and DSH expose bounded database and MCP tools without native write claims`() {
        assertEquals(HostToolTransport.NATIVE_DYNAMIC_BRIDGE, hostToolTransport(ProviderProtocol.CLI_CODEX))
        assertEquals(HostToolTransport.NATIVE_DYNAMIC_BRIDGE, hostToolTransport(ProviderProtocol.CODEX_APP_SERVER))
        assertEquals(HostToolTransport.MARKER_BRIDGE, hostToolTransport(ProviderProtocol.CLI_DSH))
        assertEquals(HostToolTransport.CLI_BRIDGE, hostToolTransport(ProviderProtocol.CLI_OPENCODE))
        assertEquals(HostToolTransport.CLI_BRIDGE, hostToolTransport(ProviderProtocol.CLI_QODER))
        val snapshot = toolAvailabilitySnapshot(
            descriptors = listOf(database, patch, mcp), mode = AgentMode.AGENT,
            transport = HostToolTransport.MARKER_BRIDGE,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 1,
            configuredMcpCount = 1, connectedMcpToolCount = 1, mcpErrorCount = 0,
        )

        assertEquals("approval", snapshot.entries.single { it.name == "query_database" }.state)
        assertEquals("approval", snapshot.entries.single { it.name == "mcp" }.state)
        assertTrue(snapshot.entries.any { it.name == mcp.name })
        assertFalse(snapshot.entries.any { it.name == "apply_patch" })
        assertTrue(snapshot.note.contains("文本协议"))
        val codexSnapshot = toolAvailabilitySnapshot(
            descriptors = listOf(database, patch, mcp), mode = AgentMode.AGENT,
            transport = HostToolTransport.NATIVE_DYNAMIC_BRIDGE,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 1,
            configuredMcpCount = 1, connectedMcpToolCount = 1, mcpErrorCount = 0,
        )
        assertTrue(codexSnapshot.entries.any { it.name == mcp.name })
        assertFalse(codexSnapshot.entries.any { it.name == "apply_patch" })
        assertTrue(codexSnapshot.note.contains("动态工具"))
    }

    @Test
    fun `bounded workflow summary restores safe entries after switching sessions`() {
        val snapshot = toolAvailabilitySnapshot(
            descriptors = listOf(database, patch), mode = AgentMode.AGENT,
            transport = HostToolTransport.STRUCTURED,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 0,
            configuredMcpCount = 0, connectedMcpToolCount = 0, mcpErrorCount = 0,
        )
        val restored = parseToolAvailabilitySummary(formatToolAvailabilitySummary(snapshot))

        assertEquals(snapshot.note, restored.first)
        assertEquals(snapshot.entries, restored.second)
        assertTrue(parseToolAvailabilitySummary("note\nsecret/key · available · invalid\n").second.isEmpty())
    }

    @Test
    fun `recent probe success is distinguished from current run connection`() {
        val snapshot = toolAvailabilitySnapshot(
            descriptors = listOf(database), mode = AgentMode.AGENT,
            transport = HostToolTransport.STRUCTURED,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 1,
            configuredMcpCount = 1, connectedMcpToolCount = 0, mcpErrorCount = 1,
            recentlyTestedDatabaseCount = 1, recentlyTestedMcpCount = 1,
        )
        assertTrue(snapshot.entries.single { it.name == "query_database" }.detail.contains("最近测试通过"))
        val mcpEntry = snapshot.entries.single { it.name == "mcp" }
        assertEquals("configuration", mcpEntry.state)
        assertTrue(mcpEntry.detail.contains("本轮没有连接成功"))
    }
}
