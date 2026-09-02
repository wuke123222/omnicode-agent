package dev.omnicode.provider

import dev.omnicode.util.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliToolDiscoveryTest {
    @Test
    fun `one-shot CLI receives EOF instead of waiting forever for piped stdin`() {
        val java = File(System.getProperty("java.home"), "bin/${if (isWindows()) "java.exe" else "java"}")
        val probeClasses = File(System.getProperty("user.dir"), "build/classes/java/test").canonicalFile
        assertTrue(probeClasses.isDirectory, "Compiled EOF probe must be available")
        val process = ProcessBuilder(
            java.absolutePath,
            "-cp",
            probeClasses.absolutePath,
            CliStdinEofProbe::class.java.name,
        ).start()
        try {
            assertEquals(null, cliProcessExitCode(process), "A live cleanup process has no exit code yet")
            closeOneShotCliInput(process)

            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Probe must observe EOF and exit")
            assertEquals("EOF", process.inputStream.bufferedReader().readText())
            assertEquals(0, cliProcessExitCode(process))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `runtime probe does not block when a child keeps stdout open after parent exit`() {
        val java = File(System.getProperty("java.home"), "bin/${if (isWindows()) "java.exe" else "java"}")
        val probeClasses = File(System.getProperty("user.dir"), "build/classes/java/test").canonicalFile
        assertTrue(probeClasses.isDirectory, "Compiled child-pipe probe must be available")
        val process = ProcessBuilder(
            java.absolutePath,
            "-cp",
            probeClasses.absolutePath,
            CliChildPipeProbe::class.java.name,
        ).start()
        try {
            val startedAt = System.nanoTime()
            val output = runBlocking {
                withTimeout(1_500L) { readBoundedProcessOutput(process, 4_096) }
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue(output.contains("READY"))
            assertTrue(elapsedMillis < 1_500L, "Post-exit stdout drain must remain bounded")
            assertFalse(process.isAlive)
        } finally {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

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
    fun `OpenCode heartbeat distinguishes startup queue and generation`() {
        assertEquals(
            "OpenCode 仍在初始化本地会话 · 15秒 · 可随时停止",
            cliHeartbeatProgress(CliTool.OPENCODE, false, false, false, 15),
        )
        assertEquals(
            "OpenCode 正在准备项目快照 · 15秒 · 可随时停止",
            cliHeartbeatProgress(CliTool.OPENCODE, true, false, false, 15),
        )
        assertEquals(
            "OpenCode 正在等待模型响应 · 30秒 · 上游模型可能排队 · 可随时停止",
            cliHeartbeatProgress(CliTool.OPENCODE, true, true, false, 30),
        )
        assertEquals(
            "OpenCode 正在生成回答 · 45秒 · 可随时停止",
            cliHeartbeatProgress(CliTool.OPENCODE, true, true, true, 45),
        )
        assertEquals(
            "本地 CLI 仍在处理 · 45秒 · 可随时停止",
            cliHeartbeatProgress(CliTool.KIMI, true, true, true, 45),
        )
    }

    @Test
    fun `OpenCode only treats final model steps as protocol completion`() {
        assertTrue(openCodeProtocolEventCompletesRun("step_finish", "step-finish", "stop"))
        assertTrue(openCodeProtocolEventCompletesRun("step_finish", "step-finish", "length"))
        assertTrue(openCodeProtocolEventCompletesRun("session.idle", null, null))
        assertFalse(openCodeProtocolEventCompletesRun("step_finish", "step-finish", "tool-calls"))
        assertFalse(openCodeProtocolEventCompletesRun("step_finish", "step-finish", "unknown"))
        assertFalse(openCodeProtocolEventCompletesRun("step_start", "step-start", null))
    }

    @Test
    fun `terminal event waits briefly for a late text flush when no output was seen`() {
        val completedAt = 1_000_000_000L

        assertFalse(cliProtocolOutputReady(true, 0, completedAt, completedAt + 499_000_000L))
        assertTrue(cliProtocolOutputReady(true, 0, completedAt, completedAt + 500_000_000L))
        assertTrue(cliProtocolOutputReady(true, 3, completedAt, completedAt + 1_000_000L))
        assertFalse(cliProtocolOutputReady(false, 3, completedAt, completedAt + 1_000_000_000L))
        assertFalse(cliProtocolOutputReady(true, 0, null, completedAt + 1_000_000_000L))
    }

    @Test
    fun `OpenCode session ids are found in bounded nested event shapes`() {
        assertEquals(
            "ses_current",
            openCodeEventSessionId(Json.parseObject("""{"type":"message.part.updated","properties":{"part":{"sessionID":"ses_current"}}}""")),
        )
        assertEquals(
            "ses_legacy",
            openCodeEventSessionId(Json.parseObject("""{"data":{"message":{"session_id":"ses_legacy"}}}""")),
        )
        assertEquals(null, openCodeEventSessionId(Json.parseObject("""{"sessionID":"../../unsafe"}""")))
    }

    @Test
    fun `all selectable CLI models are forwarded using their native model flag`() {
        assertTrue(CliTool.OPENCODE.buildArgs("prompt", "opencode/hy3-free").containsAll(listOf("--model", "opencode/hy3-free")))
        assertTrue(CliTool.KIMI.buildArgs("prompt", "kimi-k2.5").containsAll(listOf("-m", "kimi-k2.5")))
        assertTrue(CliTool.PI.buildArgs("prompt", "openai/gpt-5").containsAll(listOf("--model", "openai/gpt-5")))
        assertTrue(CliTool.QODER.buildArgs("prompt", "qoder-model").containsAll(listOf("--model", "qoder-model")))
    }

    @Test
    fun `OpenCode one-shot requests preserve agent config and enable bounded phase diagnostics`() {
        val arguments = CliTool.OPENCODE.buildArgs("prompt", "opencode/nemotron-3-ultra-free")

        assertEquals(
            listOf(
                "run", "--format", "json", "--print-logs", "--log-level", "INFO",
                "--model", "opencode/nemotron-3-ultra-free", "prompt",
            ),
            arguments,
        )
        assertFalse("--agent" in arguments, "User configured OpenCode agent must remain available")
    }

    @Test
    fun `OMP forwards reasoning and resumes the native session before the positional prompt`() {
        val base = CliTool.OMP.buildArgs("next turn", "openai/gpt-5")
        val withReasoning = ompArgsWithReasoning(base, ReasoningEffort.HIGH)
        val resumed = ompArgsWithSession(withReasoning, "omp-session_42")

        assertEquals(
            listOf(
                "--print", "--mode", "json", "--model", "openai/gpt-5",
                "--thinking", "high", "--resume", "omp-session_42", "next turn",
            ),
            resumed,
        )
        assertEquals(base, ompArgsWithReasoning(base, ReasoningEffort.AUTO))
        assertTrue(ompArgsWithReasoning(base, ReasoningEffort.NONE).containsAll(listOf("--thinking", "off")))
    }

    @Test
    fun `OpenCode request retains the same native environment as the user terminal`() {
        val isolationRoot = Files.createTempDirectory("omnicode-opencode-runtime")
        val environment = linkedMapOf(
            "PATH" to "/existing",
            "XDG_CACHE_HOME" to "/shared/cache",
            "XDG_STATE_HOME" to "/shared/state",
            "XDG_DATA_HOME" to "/shared/data",
            "XDG_CONFIG_HOME" to "/shared/config",
        )

        applyCliRequestEnvironment(CliTool.OPENCODE, environment, isolationRoot)

        assertEquals(
            mapOf(
                "PATH" to "/existing",
                "XDG_CACHE_HOME" to "/shared/cache",
                "XDG_STATE_HOME" to "/shared/state",
                "XDG_DATA_HOME" to "/shared/data",
                "XDG_CONFIG_HOME" to "/shared/config",
            ),
            environment,
        )

        val explicitlyIsolated = linkedMapOf("OPENCODE_DB" to "/custom/opencode.db")
        applyCliRequestEnvironment(CliTool.OPENCODE, explicitlyIsolated, isolationRoot)
        assertEquals("/custom/opencode.db", explicitlyIsolated["OPENCODE_DB"])

        val otherCli = linkedMapOf("PATH" to "/existing")
        applyCliRequestEnvironment(CliTool.KIMI, otherCli, isolationRoot)
        assertEquals(mapOf("PATH" to "/existing"), otherCli)

        Files.deleteIfExists(isolationRoot)
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
        assertEquals("OpenCode 请求已提交，正在等待模型响应…", cliStderrProgress(primaryStream))
        assertTrue(openCodeSessionHasStarted(primaryStream))
        assertTrue(openCodePrimaryModelHasStarted(primaryStream))
        assertFalse(openCodeSessionHasStarted("level=INFO message=init"))
        assertFalse(openCodePrimaryModelHasStarted(created))
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

    @Test
    fun `Windows npm shim resolves only its local node modules target`() {
        val root = Files.createTempDirectory("omnicode-windows-shim")
        val target = root.resolve("node_modules/example/bin/cli.js")
        val shim = root.resolve("example.cmd")
        try {
            Files.createDirectories(target.parent)
            Files.writeString(target, "console.log('ok')")
            Files.writeString(
                shim,
                """@ECHO off
                |SET dp0=%~dp0
                |"%_prog%" "%dp0%\node_modules\example\bin\cli.js" %*
                """.trimMargin(),
            )

            assertEquals(target.toFile().canonicalFile, CliToolDiscovery.windowsNpmNodeLauncherTarget(shim.toFile()))
            Files.writeString(shim, "\"%_prog%\" \"%dp0%\\..\\outside.js\" %*")
            assertEquals(null, CliToolDiscovery.windowsNpmNodeLauncherTarget(shim.toFile()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")
}
