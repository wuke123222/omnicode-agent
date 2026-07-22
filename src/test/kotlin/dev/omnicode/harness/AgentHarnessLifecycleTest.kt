package dev.omnicode.harness

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentEngine
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentEventSink
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentIdentity
import dev.omnicode.agent.AgentLimits
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.provider.ModelProvider
import dev.omnicode.tool.AgentTool
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ToolEffect
import dev.omnicode.tool.ToolExecutionContext
import dev.omnicode.tool.ToolExecutionResult
import dev.omnicode.tool.ToolRegistry
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentHarnessLifecycleTest {
    @Test
    fun `harness announces preflight before engine mode selection`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val provider = RecordingTextProvider()
        val registry = ToolRegistry()
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = registry,
            events = AgentEventSink(events::add),
        )

        val result = AgentHarness(spec(), registry, engine, AgentEventSink(events::add)).run(
            ConversationMessage(MessageRole.USER, "inspect"),
            emptyList(),
        )

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertTrue((events.first() as AgentEvent.Status).message.startsWith("Harness · READY"))
        assertTrue(events[1] is AgentEvent.ModeSelected)
        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `degraded harness hides and blocks dangerous tools even if provider hallucinates one`() = runBlocking {
        val executions = AtomicInteger()
        val approvals = AtomicInteger()
        val dangerous = object : AgentTool {
            override val name = "dangerous_fixture"
            override val description = "Must remain blocked during recovery"
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }
            override val dangerous = true
            override val effect = ToolEffect.MUTATING

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions.incrementAndGet()
                return ToolExecutionResult("changed")
            }
        }
        val provider = HallucinatedDangerousToolProvider(dangerous.name)
        val registry = ToolRegistry(additionalTools = listOf(dangerous))
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { approvals.incrementAndGet(); true },
            tools = registry,
        )

        val result = AgentHarness(
            spec(recoveryRequiresReadOnly = true),
            registry,
            engine,
            AgentEventSink {},
        ).run(ConversationMessage(MessageRole.USER, "recover safely"), emptyList())

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertFalse(provider.firstRequestTools.contains(dangerous.name))
        assertEquals(0, approvals.get())
        assertEquals(0, executions.get())
        assertTrue(result.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>()
            .single().content.startsWith("HARNESS_RECOVERY_READ_ONLY"))
    }

    @Test
    fun `degraded claude plan also blocks its normally approval exempt command`() = runBlocking {
        val executions = AtomicInteger()
        val approvals = AtomicInteger()
        val command = object : AgentTool {
            override val name = "run_command"
            override val description = "Guarded read-only command fixture"
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }
            override val dangerous = true
            override val effect = ToolEffect.COMMAND

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions.incrementAndGet()
                return ToolExecutionResult("ran")
            }
        }
        val provider = HallucinatedDangerousToolProvider(command.name)
        val registry = ToolRegistry(runCommandTool = command)
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { approvals.incrementAndGet(); true },
            tools = registry,
        )

        val result = AgentHarness(
            spec(recoveryRequiresReadOnly = true, mode = AgentMode.CLAUDE_PLAN),
            registry,
            engine,
            AgentEventSink {},
        ).run(ConversationMessage(MessageRole.USER, "recover with reads only"), emptyList())

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertFalse(provider.firstRequestTools.contains("run_command"))
        assertEquals(0, approvals.get())
        assertEquals(0, executions.get())
        assertTrue(result.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>()
            .single().content.startsWith("HARNESS_RECOVERY_READ_ONLY"))
    }

    @Test
    fun `harness rejects a registry different from the engine before provider io`() = runBlocking {
        val provider = RecordingTextProvider()
        val engineRegistry = ToolRegistry()
        val harnessRegistry = ToolRegistry()
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = engineRegistry,
        )

        val failure = runCatching {
            AgentHarness(spec(), harnessRegistry, engine, AgentEventSink {}).run(
                ConversationMessage(MessageRole.USER, "must not run"),
                emptyList(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("ToolRegistry does not match"))
        assertEquals(0, provider.calls.get())
    }

    private fun spec(
        recoveryRequiresReadOnly: Boolean = false,
        mode: AgentMode = AgentMode.AGENT,
    ) = HarnessRunSpec(
        workflowId = "workflow",
        attemptId = "attempt",
        identity = AgentIdentity(),
        mode = mode,
        strategy = AgentExecutionStrategy.SINGLE,
        limits = AgentLimits(),
        recoveryRequiresReadOnly = recoveryRequiresReadOnly,
    )

    private class RecordingTextProvider : ModelProvider {
        override val id = "recording"
        val calls = AtomicInteger()

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            calls.incrementAndGet()
            return ModelResponse(listOf(ContentBlock.Text("done")), stopReason = StopReason.COMPLETE)
        }
    }

    private class HallucinatedDangerousToolProvider(private val toolName: String) : ModelProvider {
        override val id = "hallucinated-tool"
        var firstRequestTools: Set<String> = emptySet()
        private var turn = 0

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            turn++
            return if (turn == 1) {
                firstRequestTools = request.tools.mapTo(mutableSetOf()) { it.name }
                ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("call-1", toolName, JsonObject())),
                    stopReason = StopReason.TOOL_USE,
                )
            } else {
                ModelResponse(listOf(ContentBlock.Text("recovery remains read-only")), stopReason = StopReason.COMPLETE)
            }
        }
    }

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "isDisposed" -> false
            "getBasePath" -> System.getProperty("java.io.tmpdir")
            "getName" -> "test"
            "toString" -> "FakeProject"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
        }
    } as Project

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
