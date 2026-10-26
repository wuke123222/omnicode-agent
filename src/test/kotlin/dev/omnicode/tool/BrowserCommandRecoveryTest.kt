package dev.omnicode.tool

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentEngine
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentEventSink
import dev.omnicode.agent.AgentCheckpointSink
import dev.omnicode.agent.AgentExecutionCheckpoint
import dev.omnicode.agent.AgentLimits
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.provider.ModelProvider
import dev.omnicode.settings.SandboxMode
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserCommandRecoveryTest {
    @Test
    fun `missing browser executable after approval does not poison later command availability`() = runBlocking {
        for (action in listOf("check", "open", "screenshot")) {
            val workspace = createTempDirectory("omnicode-browser-recovery").toRealPath()
            try {
                var launches = 0
                var approvals = 0
                val saved = mutableListOf<AgentExecutionCheckpoint>()
                val events = mutableListOf<AgentEvent>()
                val provider = BrowserProvider(action)
                val command = RunCommandTool(
                    sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
                    processStarter = { launches++; error("No process should start") },
                )
                val engine = AgentEngine(
                    project = projectAt(workspace),
                    provider = provider,
                    approvalGate = ApprovalGate { approvals++; true },
                    tools = ToolRegistry(runCommandTool = remapExecutable(command, workspace.resolve("missing-npx"))),
                    limits = AgentLimits(maxIterations = 3),
                    checkpoints = AgentCheckpointSink { saved += it },
                    events = AgentEventSink { events += it },
                )

                val result = engine.run("Check the browser operation")

                assertEquals(AgentRunStatus.COMPLETED, result.status, action)
                assertEquals(1, approvals, action)
                assertEquals(0, launches, action)
                assertEquals(2, provider.requests.size, action)
                assertTrue(provider.requests.last().tools.any { it.name == "run_command" }, action)
                assertNull(saved.last().pendingTool, action)
                val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
                assertTrue(completed.isError, action)
                assertTrue(completed.result.contains("COMMAND_NOT_STARTED"), action)
                assertFalse(completed.result.contains("SIDE_EFFECT_STATE_UNKNOWN"), action)
            } finally {
                workspace.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `failure at process launch still retains unknown side effect recovery`() = runBlocking {
        val workspace = createTempDirectory("omnicode-browser-launch").toRealPath()
        try {
            var launches = 0
            val saved = mutableListOf<AgentExecutionCheckpoint>()
            val events = mutableListOf<AgentEvent>()
            val command = RunCommandTool(
                sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
                processStarter = { launches++; error("Failure after launch was attempted") },
            )
            val javaName = if (System.getProperty("os.name").contains("Windows", true)) "java.exe" else "java"
            val executable = Path.of(System.getProperty("java.home"), "bin", javaName)
            val result = AgentEngine(
                project = projectAt(workspace),
                provider = BrowserProvider("open"),
                approvalGate = ApprovalGate { true },
                tools = ToolRegistry(runCommandTool = remapExecutable(command, executable)),
                limits = AgentLimits(maxIterations = 3),
                checkpoints = AgentCheckpointSink { saved += it },
                events = AgentEventSink { events += it },
            ).run("Open the browser")

            assertEquals(AgentRunStatus.FAILED, result.status)
            assertEquals(1, launches)
            val pending = assertNotNull(saved.last().pendingTool)
            assertEquals("browser_automation", pending.name)
            assertTrue(pending.executionStarted)
            assertTrue(events.filterIsInstance<AgentEvent.ToolCompleted>().single().result.contains("SIDE_EFFECT_STATE_UNKNOWN"))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    // Exercise the actual resolver without relying on whether npx is installed on the test host.
    private fun remapExecutable(command: RunCommandTool, executable: Path): AgentTool = object : AgentTool by command {
        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            assertEquals("npx", arguments.getAsJsonArray("argv")[0].asString)
            val mapped = arguments.deepCopy()
            mapped.getAsJsonArray("argv").set(0, JsonPrimitive(executable.toString()))
            return command.execute(mapped, context)
        }
    }

    private class BrowserProvider(private val action: String) : ModelProvider {
        override val id = "browser-recovery-test"
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(request: ModelRequest, onTextDelta: suspend (String) -> Unit): ModelResponse {
            requests += request
            return if (requests.size == 1) {
                ModelResponse(
                    listOf(ContentBlock.ToolCall("browser-call", "browser_automation", JsonObject().apply {
                        addProperty("action", action)
                        addProperty("url", "https://example.test/")
                        addProperty("path", ".omnicode-browser/test.png")
                    })),
                    stopReason = StopReason.TOOL_USE,
                )
            } else {
                ModelResponse(listOf(ContentBlock.Text("Browser failure reported")), stopReason = StopReason.COMPLETE)
            }
        }
    }

    private fun projectAt(root: Path): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> root.toString()
            "getName" -> "browser-recovery-test"
            "isDisposed" -> false
            "toString" -> "BrowserRecoveryProject"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as Project
}
