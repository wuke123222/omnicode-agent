package dev.omnicode.provider

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliToolDiscoveryTest {
    @Test
    fun `runtime path keeps the inherited order and adds the selected CLI directory once`() {
        val separator = File.pathSeparator
        val executable = File(File(System.getProperty("java.io.tmpdir"), "omnicode-cli"), "opencode")

        val entries = CliToolDiscovery.runtimePath(
            "/first${separator}/second${separator}/first",
            executable,
        ).split(separator)

        assertEquals(listOf("/first", "/second", executable.parent), entries.take(3))
    }

    @Test
    fun `runtime path preserves a case-insensitive Windows path key`() {
        val environment = linkedMapOf("Path" to "/existing")
        val executable = File(File(System.getProperty("java.io.tmpdir"), "omnicode-cli"), "pi")

        CliToolDiscovery.applyRuntimePath(environment, executable)

        assertTrue("PATH" !in environment)
        assertTrue(environment.getValue("Path").split(File.pathSeparator).contains(executable.parent))
    }

    @Test
    fun `local CLI always runs in the canonical project directory`() {
        val project = Files.createTempDirectory("omnicode-cli-project")
        try {
            assertEquals(project.toRealPath().toFile(), CliToolLaunch.resolveWorkingDirectory(project))
        } finally {
            Files.deleteIfExists(project)
        }
    }

    @Test
    fun `local CLI never falls back to the IDE home directory`() {
        val error = assertFailsWith<ProviderException> {
            CliToolLaunch.resolveWorkingDirectory(null)
        }

        assertTrue(error.message.orEmpty().contains("项目目录"))
    }

    @Test
    fun `first CLI output timeout is bounded independently of total runtime`() {
        assertEquals(10L, cliFirstOutputTimeoutSeconds(10L))
        assertEquals(45L, cliFirstOutputTimeoutSeconds(120L))
        assertEquals(45L, cliFirstOutputTimeoutSeconds(1_800L))
    }

    @Test
    fun `all selectable CLI models are forwarded using their native model flag`() {
        assertTrue(CliTool.OPENCODE.buildArgs("prompt", "opencode/hy3-free").containsAll(listOf("--model", "opencode/hy3-free")))
        assertTrue(CliTool.KIMI.buildArgs("prompt", "kimi-k2.5").containsAll(listOf("-m", "kimi-k2.5")))
        assertTrue(CliTool.PI.buildArgs("prompt", "openai/gpt-5").containsAll(listOf("--model", "openai/gpt-5")))
        assertTrue(CliTool.QODER.buildArgs("prompt", "qoder-model").containsAll(listOf("--model", "qoder-model")))
    }

    @Test
    fun `Qoder never receives stale format or automatic approval flags`() {
        val arguments = CliTool.QODER.buildArgs("prompt", "default")

        assertTrue("--print" in arguments)
        assertFalse("--format" in arguments)
        assertFalse("--yolo" in arguments)
    }

    @Test
    fun `saved CLI keys are injected for the selected model provider`() {
        assertEquals(setOf("OPENAI_API_KEY"), cliCredentialEnvironmentVariables(CliTool.PI, "openai/gpt-5"))
        assertEquals(setOf("GEMINI_API_KEY"), cliCredentialEnvironmentVariables(CliTool.PI, "default"))
        assertEquals(setOf("OPENROUTER_API_KEY"), cliCredentialEnvironmentVariables(CliTool.OPENCODE, "openrouter/auto"))
        assertEquals(setOf("XAI_API_KEY"), cliCredentialEnvironmentVariables(CliTool.GROK, "grok-build-0.1"))
        assertEquals(
            linkedSetOf("KIMI_API_KEY", "MOONSHOT_API_KEY"),
            cliCredentialEnvironmentVariables(CliTool.KIMI, "kimi-k2.5"),
        )
    }

    @Test
    fun `native CLI executable is never forced through Node`() {
        val executable = Files.createTempFile("omnicode-native-cli", ".bin")
        try {
            Files.write(executable, byteArrayOf(0, 1, 2, 3, 4))

            assertEquals(listOf(executable.toString()), CliToolDiscovery.launchCommand(executable.toFile()))
        } finally {
            Files.deleteIfExists(executable)
        }
    }
}
