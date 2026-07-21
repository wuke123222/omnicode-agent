package dev.omnicode.settings

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OmniCodePlatformSettingsTest {
    @Test
    fun `full speed preset raises every workflow runtime boundary including ten billion tokens`() {
        val state = OmniCodePlatformSettingsState()

        state.applyFullSpeedRuntimePreset()

        assertEquals(128, state.agentMaxIterations)
        assertEquals(256, state.agentMaxToolCalls)
        assertEquals(3_600, state.agentMaxWallTimeSeconds)
        assertEquals(1_800, state.agentMaxToolTimeSeconds)
        assertEquals(MAX_WORKFLOW_TOKEN_BUDGET, state.agentMaxInputTokens)
        assertEquals(MAX_WORKFLOW_TOKEN_BUDGET, state.agentMaxOutputTokens)
    }

    @Test
    fun `MCP command arguments survive editor rendering`() {
        val arguments = listOf(
            "--flag",
            "two words",
            "quote\"inside",
            "path\\segment",
            "",
        )

        assertEquals(arguments, parseCommandLine(renderCommandLine(arguments)))
    }

    @Test
    fun `MCP command parser rejects unfinished input`() {
        assertFailsWith<IllegalArgumentException> { parseCommandLine("--name \"unfinished") }
        assertFailsWith<IllegalArgumentException> { parseCommandLine("trailing\\") }
    }

    @Test
    fun `platform settings normalize public snapshot values`() {
        val service = OmniCodePlatformSettingsService()
        service.loadState(OmniCodePlatformSettingsState().apply {
            sandboxMode = "not-a-mode"
            agentMaxIterations = 10_000
            agentMaxToolCalls = -1
            agentMaxInputTokens = Long.MAX_VALUE
            agentMaxOutputTokens = Long.MAX_VALUE
            agentMaxRunCostUsd = 2.5
            agentCostWarningPercent = 150
            mcpServers += McpServerState().also {
                it.name = "  Example  "
                it.transport = "HTTP"
                it.url = "  https://mcp.example.com/api  "
                it.httpAuthMode = "OAUTH"
                it.oauthClientId = "  public-client  "
                it.oauthScopes = "tools:read, tools:call tools:read \"invalid"
                it.arguments = "--label \"two words\""
                it.environmentKeys = " HOME,\nPATH, HOME "
                it.workingDirectory = ""
            }
            promptTemplates += PromptTemplateState().also {
                it.shortcut = "!review"
            }
        })

        val snapshot = service.snapshot()

        assertEquals(SandboxMode.DEFAULT, snapshot.sandboxMode)
        assertEquals(McpTransport.HTTP, snapshot.mcpServers.single().transport)
        assertEquals("https://mcp.example.com/api", snapshot.mcpServers.single().url)
        assertEquals(McpHttpAuthMode.OAUTH, snapshot.mcpServers.single().httpAuthMode)
        assertEquals("public-client", snapshot.mcpServers.single().oauthClientId)
        assertEquals(listOf("tools:read", "tools:call"), snapshot.mcpServers.single().oauthScopes)
        assertEquals(listOf("--label", "two words"), snapshot.mcpServers.single().arguments)
        assertEquals(setOf("HOME", "PATH"), snapshot.mcpServers.single().environmentKeys)
        assertEquals(".", snapshot.mcpServers.single().workingDirectory)
        assertEquals("review", snapshot.promptTemplates.single().shortcut)
        assertEquals(128, snapshot.agentRuntime.maxIterations)
        assertEquals(1, snapshot.agentRuntime.maxToolCalls)
        assertEquals(MAX_WORKFLOW_TOKEN_BUDGET, snapshot.agentRuntime.maxInputTokens)
        assertEquals(MAX_WORKFLOW_TOKEN_BUDGET, snapshot.agentRuntime.maxOutputTokens)
        assertEquals(2.5, snapshot.agentRuntime.maxRunCostUsd)
        assertEquals(1.0, snapshot.agentRuntime.costWarningRatio)
    }

    @Test
    fun `MCP launch trust is scoped by server project and exact fingerprint`() {
        val service = OmniCodePlatformSettingsService()
        service.loadState(OmniCodePlatformSettingsState().apply {
            mcpServers += McpServerState().also { it.id = "server-1" }
        })
        val first = "a".repeat(64)
        val changed = "b".repeat(64)

        service.trustMcpLaunch("server-1", "project-1", first, 1L)

        assertTrue(service.isMcpLaunchTrusted("server-1", "project-1", first))
        assertFalse(service.isMcpLaunchTrusted("server-1", "project-2", first))
        assertFalse(service.isMcpLaunchTrusted("server-1", "project-1", changed))
        service.trustMcpLaunch("server-1", "project-1", changed, 2L)
        assertEquals(1, service.mcpLaunchTrustCount("server-1"))
        assertTrue(service.isMcpLaunchTrusted("server-1", "project-1", changed))
        assertEquals(1, service.clearMcpLaunchTrusts("server-1"))
        assertEquals(0, service.mcpLaunchTrustCount("server-1"))
    }

    @Test
    fun `skill source inspection discovers root and child skills`() {
        val root = createTempDirectory("omnicode-skills")
        try {
            root.resolve("SKILL.md").writeText("# Root")
            root.resolve("review").createDirectories().resolve("SKILL.md").writeText("# Review")
            root.resolve("ignored").createDirectories().resolve("README.md").writeText("ignored")

            val inspection = inspectSkillSource(root.toString(), null)

            assertTrue(inspection.isValid)
            assertEquals(2, inspection.discoveredSkills)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `skill source inspection resolves project relative paths and reports missing paths`() {
        val project = createTempDirectory("omnicode-project")
        try {
            project.resolve(".agents/skills/review").createDirectories()
                .resolve("SKILL.md").writeText("# Review")

            val valid = inspectSkillSource(".agents/skills", project.toString())
            val missing = inspectSkillSource("missing-skills", project.toString())

            assertTrue(valid.isValid)
            assertEquals(1, valid.discoveredSkills)
            assertFalse(missing.isValid)
            assertTrue(missing.message.contains("不存在"))
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
