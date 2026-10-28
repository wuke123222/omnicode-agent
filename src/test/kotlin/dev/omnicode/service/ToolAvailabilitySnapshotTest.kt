package dev.omnicode.service

import dev.omnicode.agent.AgentMode
import dev.omnicode.provider.LocalAgentEngineRegistry
import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.tool.ToolDescriptor
import dev.omnicode.tool.ToolEffect
import dev.omnicode.tool.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolAvailabilitySnapshotTest {
    private val database = ToolDescriptor("query_database", ToolEffect.EXTERNAL, true, "digest")
    private val patch = ToolDescriptor("apply_patch", ToolEffect.MUTATING, true, "digest")
    private val mcp = ToolDescriptor("mcp__approved__query", ToolEffect.EXTERNAL, true, "digest")

    @Test
    fun `recovery snapshot matches effective registry instead of advertising blocked commands`() {
        val registry = ToolRegistry()
        val snapshot = toolAvailabilitySnapshot(
            descriptors = registry.descriptorsFor(AgentMode.AGENT) + mcp,
            mode = AgentMode.AGENT, transport = HostToolTransport.STRUCTURED,
            enabledDatabaseCount = 1, credentialedDatabaseCount = 1,
            configuredMcpCount = 1, connectedMcpToolCount = 0, mcpErrorCount = 0,
            recoveryRequiresReadOnly = true,
        )

        assertEquals(
            registry.definitionsFor(AgentMode.AGENT, safeOnly = true).map { it.name }.toSet(),
            snapshot.entries.filter { it.state in setOf("available", "approval") }.map { it.name }.toSet(),
        )
        for (name in listOf("run_command", "query_database", "mcp")) {
            val entry = snapshot.entries.single { it.name == name }
            assertEquals("unsupported", entry.state)
            assertTrue(entry.detail.contains("上次操作"))
            assertTrue(entry.detail.contains("查看待确认操作"))
        }
        assertFalse(snapshot.entries.any { it.name == mcp.name || it.name == "apply_patch" })
        assertEquals("approval", snapshot.entries.single { it.name == "inspect_remote_kubernetes" }.state)
        assertTrue(snapshot.note.contains("降级为只读"))
        val restored = parseToolAvailabilitySummary(formatToolAvailabilitySummary(snapshot))
        assertEquals(snapshot.note, restored.first)
        assertEquals(snapshot.entries, restored.second)
    }

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
    fun `API CLI and native engines report the same discovered Kubernetes catalog`() {
        val kubernetes = (1..14).map { index ->
            ToolDescriptor("mcp__kubernetes__tool_$index", ToolEffect.EXTERNAL, true, "digest")
        }
        for (transport in listOf(
            HostToolTransport.STRUCTURED,
            HostToolTransport.CLI_BRIDGE,
            HostToolTransport.MARKER_BRIDGE,
            HostToolTransport.NATIVE_DYNAMIC_BRIDGE,
        )) {
            val snapshot = toolAvailabilitySnapshot(
                descriptors = listOf(database) + kubernetes,
                mode = AgentMode.AGENT,
                transport = transport,
                enabledDatabaseCount = 1,
                credentialedDatabaseCount = 1,
                configuredMcpCount = 1,
                connectedMcpToolCount = 14,
                mcpErrorCount = 0,
            )
            assertEquals(14, snapshot.entries.count { it.name.startsWith("mcp__") }, "$transport")
            assertEquals("approval", snapshot.entries.single { it.name == "mcp" }.state)
        }
    }

    @Test
    fun `availability follows task-relevant bounded bridge selection`() {
        val unrelated = (1..60).map { index ->
            ToolDescriptor("mcp__github__tool_$index", ToolEffect.EXTERNAL, true, "digest")
        }
        val kubernetes = (1..14).map { index ->
            ToolDescriptor("mcp__kubernetes__tool_$index", ToolEffect.EXTERNAL, true, "digest")
        }
        val snapshot = toolAvailabilitySnapshot(
            descriptors = listOf(database) + unrelated + kubernetes,
            mode = AgentMode.AGENT,
            transport = HostToolTransport.NATIVE_DYNAMIC_BRIDGE,
            enabledDatabaseCount = 1,
            credentialedDatabaseCount = 1,
            configuredMcpCount = 2,
            connectedMcpToolCount = 74,
            mcpErrorCount = 0,
            taskText = "查看 Kubernetes 集群",
        )

        assertEquals(14, snapshot.entries.count { it.name.startsWith("mcp__kubernetes__") })
        assertEquals("approval", snapshot.entries.single { it.name == "mcp" }.state)
    }

    @Test
    fun `every configured local engine has an MCP-capable Agent transport`() {
        val kubernetes = (1..14).map { index ->
            ToolDescriptor("mcp__kubernetes__tool_$index", ToolEffect.EXTERNAL, true, "digest")
        }
        LocalAgentEngineRegistry.all.forEach { engine ->
            val transport = hostToolTransport(engine.protocol)
            assertTrue(transport != HostToolTransport.NATIVE_UNBRIDGED, engine.displayName)
            val snapshot = toolAvailabilitySnapshot(
                descriptors = listOf(database) + kubernetes,
                mode = AgentMode.AGENT,
                transport = transport,
                enabledDatabaseCount = 1,
                credentialedDatabaseCount = 1,
                configuredMcpCount = 1,
                connectedMcpToolCount = 14,
                mcpErrorCount = 0,
            )
            assertEquals(14, snapshot.entries.count { it.name.startsWith("mcp__") }, engine.displayName)
            assertEquals("approval", snapshot.entries.single { it.name == "mcp" }.state)
        }
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
