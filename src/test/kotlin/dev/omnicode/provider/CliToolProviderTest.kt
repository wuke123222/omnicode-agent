package dev.omnicode.provider

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliToolProviderTest {
    @Test
    fun `every CLI tool maps to a registered provider preset`() {
        CliTool.entries.forEach { tool ->
            val providerId = tool.cliProviderId()
            val preset = ProviderPresets.byId(providerId)
            assertEquals(providerId, preset.id)
            assertEquals("cli://local", preset.defaultBaseUrl)
            assertTrue(preset.protocol.isCliProtocol, "$providerId should use a CLI protocol")
        }
    }

    @Test
    fun `launch PATH starts with the executable directory and keeps the process PATH`() {
        val directory = createTempDirectory("omnicode-cli").toFile()
        try {
            val executable = File(directory, "opencode").apply { writeText("#!/usr/bin/env node\n") }

            val path = CliToolDiscovery.launchPath(executable)
            val entries = path.split(File.pathSeparator)

            assertEquals(directory.absolutePath, entries.first())
            System.getenv("PATH")?.split(File.pathSeparator)?.firstOrNull { it.isNotBlank() }?.let { inherited ->
                assertTrue(inherited in entries, "inherited PATH entry '$inherited' should be preserved")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `OpenCode run args use the verified format flag`() {
        assertEquals(
            listOf("run", "hello", "--model", "opencode/big-pickle", "--format", "json"),
            CliTool.OPENCODE.buildArgs("hello", "opencode/big-pickle"),
        )
        assertEquals(
            listOf("run", "hi", "--format", "json"),
            CliTool.OPENCODE.buildArgs("hi", "default"),
        )
        assertTrue("--output-format" !in CliTool.OPENCODE.buildArgs("hi", null))
    }

    @Test
    fun `CLI model lines keep plain ids and drop prose and headers`() {
        assertEquals("anthropic/claude-sonnet-4-5", normalizeCliModelLine("  anthropic/claude-sonnet-4-5  "))
        assertEquals("gpt-5.1", normalizeCliModelLine("gpt-5.1"))
        assertEquals(null, normalizeCliModelLine(""))
        assertEquals(null, normalizeCliModelLine("Available models:"))
        assertEquals(null, normalizeCliModelLine("- item with spaces"))
        assertEquals(null, normalizeCliModelLine("x".repeat(200)))
    }

    @Test
    fun `only OpenCode participates in CLI model discovery`() {
        assertEquals(listOf("models"), CliTool.OPENCODE.modelListArgs)
        CliTool.entries.filter { it != CliTool.OPENCODE }.forEach { tool ->
            assertEquals(null, tool.modelListArgs, "${tool.name} should not run a model list command")
        }
        assertTrue(ProviderModelDiscovery.supportsRemoteDiscovery(ProviderProtocol.CLI_OPENCODE))
        assertTrue(!ProviderModelDiscovery.supportsRemoteDiscovery(ProviderProtocol.CLI_KIMI))
    }

    @Test
    fun `model argument support matches each CLI capability`() {
        assertTrue(CliTool.OPENCODE.supportsModelArgument)
        assertTrue(CliTool.GROK.supportsModelArgument)
        assertTrue(CliTool.QODER.supportsModelArgument)
        assertTrue(!CliTool.KIMI.supportsModelArgument)
        assertTrue(!CliTool.PI.supportsModelArgument)
        CliTool.entries.forEach { tool ->
            assertTrue(tool.suggestedModels.isNotEmpty(), "${tool.name} should suggest at least one model")
        }
    }
}
