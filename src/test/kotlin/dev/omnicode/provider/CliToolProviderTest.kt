package dev.omnicode.provider

import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliToolProviderTest {
    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")

    private fun fakeCli(directory: File, script: String): File =
        File(directory, "opencode").apply {
            writeText("#!/bin/sh\n$script\n")
            setExecutable(true)
        }

    private fun connectionFor(executable: File, timeoutSeconds: Long): ProviderConnection =
        ProviderConnection(
            preset = ProviderPresets.byId("cli-opencode"),
            baseUrl = executable.absolutePath,
            model = "default",
            apiKey = "",
            requestTimeoutSeconds = timeoutSeconds,
        )

    private fun request(): ModelRequest = ModelRequest(
        messages = listOf(ConversationMessage(MessageRole.USER, "hi")),
        tools = emptyList(),
        maxOutputTokens = 1024,
    )

    @Test
    fun `stdin is closed so a CLI waiting for piped input EOF cannot deadlock the request`() {
        if (isWindows()) return
        val directory = createTempDirectory("omnicode-cli-stdin").toFile()
        try {
            // `cat` consumes stdin until EOF: with the child's stdin pipe left open this fake
            // CLI produces zero output forever, reproducing the endless "正在请求模型" hang.
            val executable = fakeCli(
                directory,
                "cat > /dev/null\nprintf '%s\\n' '{\"type\":\"text\",\"part\":{\"text\":\"ok\"}}'",
            )
            val provider = CliToolProvider(connectionFor(executable, timeoutSeconds = 30), CliTool.OPENCODE)

            val startedAt = System.nanoTime()
            val response = runBlocking { provider.complete(request()) }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals("ok", (response.blocks.single() as ContentBlock.Text).text)
            assertTrue(elapsedMs < 20_000, "request should finish immediately, took ${elapsedMs}ms")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `watchdog kills a CLI that never finishes instead of hanging the session`() {
        if (isWindows()) return
        val directory = createTempDirectory("omnicode-cli-hang").toFile()
        try {
            val executable = fakeCli(directory, "sleep 600")
            val provider = CliToolProvider(connectionFor(executable, timeoutSeconds = 10), CliTool.OPENCODE)

            val startedAt = System.nanoTime()
            val error = assertFailsWith<ProviderException> {
                runBlocking { provider.complete(request()) }
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(elapsedMs < 30_000, "timeout should fire near 10s, took ${elapsedMs}ms")
            assertTrue(error.message.orEmpty().contains("超过"), "unexpected message: ${error.message}")
        } finally {
            directory.deleteRecursively()
        }
    }
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
    fun `named executable lookup accepts absolute candidates and explicit paths`() {
        if (isWindows()) return
        val directory = createTempDirectory("omnicode-codex").toFile()
        try {
            val absolute = File(directory, "codex").apply {
                writeText("#!/bin/sh\necho codex-cli 1.0.0\n")
                setExecutable(true)
            }

            assertEquals(
                absolute,
                CliToolDiscovery.resolveByNames(listOf("definitely-not-on-path", absolute.absolutePath)),
            )
            assertEquals(
                absolute,
                CliToolDiscovery.resolveByNames(listOf("definitely-not-on-path"), explicitPath = absolute.absolutePath),
            )
            assertEquals(null, CliToolDiscovery.resolveByNames(listOf("definitely-not-on-path")))
        } finally {
            directory.deleteRecursively()
        }
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
