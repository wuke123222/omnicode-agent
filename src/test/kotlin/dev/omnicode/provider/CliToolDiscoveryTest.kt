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
    fun `silent CLI keeps the configured total bound instead of a 45 second cutoff`() {
        assertEquals(CliOutputWaitPolicy(10L, 15L), cliOutputWaitPolicy(10L))
        assertEquals(CliOutputWaitPolicy(120L, 15L), cliOutputWaitPolicy(120L))
        assertEquals(CliOutputWaitPolicy(1_800L, 15L), cliOutputWaitPolicy(1_800L))
        assertEquals(CliOutputWaitPolicy(3_600L, 15L), cliOutputWaitPolicy(Long.MAX_VALUE))
    }

    @Test
    fun `all selectable CLI models are forwarded using their native model flag`() {
        assertTrue(CliTool.OPENCODE.buildArgs("prompt", "opencode/hy3-free").containsAll(listOf("--model", "opencode/hy3-free")))
        assertTrue(CliTool.KIMI.buildArgs("prompt", "kimi-k2.5").containsAll(listOf("-m", "kimi-k2.5")))
        assertTrue(CliTool.PI.buildArgs("prompt", "openai/gpt-5").containsAll(listOf("--model", "openai/gpt-5")))
        assertTrue(CliTool.QODER.buildArgs("prompt", "qoder-model").containsAll(listOf("--model", "qoder-model")))
    }

    @Test
    fun `OpenCode one-shot requests skip the title model and expose error-only diagnostics`() {
        val arguments = CliTool.OPENCODE.buildArgs("prompt", "opencode/nemotron-3-ultra-free")

        assertTrue(arguments.containsAll(listOf("--title", "OmniCode task")))
        assertTrue(arguments.containsAll(listOf("--print-logs", "--log-level", "INFO")))
        assertFalse("--pure" in arguments, "User configured OpenCode plugins and tools must remain available")
    }

    @Test
    fun `OpenCode request disables unrelated startup maintenance only in its child environment`() {
        val environment = linkedMapOf("PATH" to "/existing")

        applyCliRequestEnvironment(CliTool.OPENCODE, environment)

        assertEquals("true", environment["OPENCODE_DISABLE_MODELS_FETCH"])
        assertEquals("true", environment["OPENCODE_DISABLE_AUTOUPDATE"])
        assertEquals("true", environment["OPENCODE_DISABLE_PRUNE"])
        assertEquals("omnicode-agent.db", environment["OPENCODE_DB"])
        assertEquals("/existing", environment["PATH"])

        val explicitlyIsolated = linkedMapOf("OPENCODE_DB" to "/custom/opencode.db")
        applyCliRequestEnvironment(CliTool.OPENCODE, explicitlyIsolated)
        assertEquals("/custom/opencode.db", explicitlyIsolated["OPENCODE_DB"])

        val otherCli = linkedMapOf("PATH" to "/existing")
        applyCliRequestEnvironment(CliTool.KIMI, otherCli)
        assertEquals(mapOf("PATH" to "/existing"), otherCli)
    }

    @Test
    fun `OpenCode stderr is reduced to safe actionable progress`() {
        val overloaded = """
            timestamp=2026-08-28T03:55:28Z level=ERROR providerID=opencode small=false agent=build error="[502] Service temporarily overloaded secret=do-not-show"
        """.trimIndent()
        assertEquals("OpenCode 上游模型暂时繁忙，正在重试…", cliStderrProgress(overloaded))

        val titleOnly = """
            timestamp=2026-08-28T03:55:28Z level=ERROR small=true agent=title error="[502] Service temporarily overloaded"
        """.trimIndent()
        assertEquals(null, cliStderrProgress(titleOnly))

        val metadataTimeout = "level=ERROR message=\"Failed to fetch models.dev\" cause=TimeoutError"
        assertEquals("OpenCode 模型目录服务响应较慢；当前任务仍在继续…", cliStderrProgress(metadataTimeout))

        val created = "level=INFO message=created id=ses_123 path=/private/project"
        assertEquals("OpenCode 本地会话已创建，正在准备项目快照…", cliStderrProgress(created))
        assertTrue(openCodeSessionHasStarted(created))

        val primaryStream = "level=INFO message=stream providerID=opencode modelID=free small=false secret=hidden"
        assertEquals("OpenCode 已连接任务模型，正在生成结果…", cliStderrProgress(primaryStream))
        assertTrue(openCodeSessionHasStarted(primaryStream))
        assertFalse(openCodeSessionHasStarted("level=INFO message=init"))
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
