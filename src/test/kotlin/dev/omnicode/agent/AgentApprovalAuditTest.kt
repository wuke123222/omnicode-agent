package dev.omnicode.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.provider.ModelProvider
import dev.omnicode.tool.AgentTool
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.tool.ToolExecutionContext
import dev.omnicode.tool.ToolExecutionResult
import dev.omnicode.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentApprovalAuditTest {
    @Test
    fun `dangerous validation failure is not recorded as approved`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val tool = testTool { _, _ -> error("validation failed before approval") }
        val engine = engine(tool, ApprovalGate { error("approval must not be requested") }, events)

        val result = engine.run("test")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        val requested = events.filterIsInstance<AgentEvent.ToolRequested>().single()
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals("call-1", requested.callId)
        assertEquals(requested.callId, completed.callId)
        assertTrue(completed.isError)
        assertEquals(ToolApprovalOutcome.NOT_REQUESTED, completed.approvalOutcome)
    }

    @Test
    fun `explicit rejection is recorded as rejected`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val tool = testTool { _, context ->
            val approved = context.approvalGate.approve(
                ApprovalRequest("dangerous_test", "Test", "details", "risk"),
            )
            if (approved) ToolExecutionResult("unexpected")
            else ToolExecutionResult("REJECTED_BY_USER: test", true)
        }
        val engine = engine(tool, ApprovalGate { false }, events)

        engine.run("test")

        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        val approval = events.filterIsInstance<AgentEvent.ToolApprovalResolved>().single()
        assertEquals(ToolApprovalOutcome.REJECTED, approval.outcome)
        assertEquals(ToolApprovalOutcome.REJECTED, completed.approvalOutcome)
    }

    @Test
    fun `approval evidence is emitted before a dangerous side effect starts`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        var sideEffectObservedApproval = false
        val tool = testTool { _, context ->
            check(context.approvalGate.approve(ApprovalRequest("dangerous_test", "Write", "details", "risk")))
            sideEffectObservedApproval = events.lastOrNull() is AgentEvent.ToolApprovalResolved
            ToolExecutionResult("changed")
        }
        val engine = engine(tool, ApprovalGate { true }, events)

        engine.run("test")

        assertTrue(sideEffectObservedApproval)
        val approvalIndex = events.indexOfFirst { it is AgentEvent.ToolApprovalResolved }
        val completionIndex = events.indexOfFirst { it is AgentEvent.ToolCompleted }
        assertTrue(approvalIndex >= 0)
        assertTrue(approvalIndex < completionIndex)
        assertEquals(ToolApprovalOutcome.APPROVED, (events[approvalIndex] as AgentEvent.ToolApprovalResolved).outcome)
    }

    @Test
    fun `approval audit delivery failure keeps the dangerous gate closed`() = runBlocking {
        var sideEffects = 0
        val tool = testTool { _, context ->
            check(context.approvalGate.approve(ApprovalRequest("dangerous_test", "Write", "details", "risk")))
            sideEffects++
            ToolExecutionResult("changed")
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = TwoTurnProvider(tool.name),
            approvalGate = ApprovalGate { true },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 3),
            events = AgentEventSink { event ->
                if (event is AgentEvent.ToolApprovalResolved) error("audit store unavailable")
            },
        )

        val result = engine.run("test")

        assertEquals(0, sideEffects)
        val observation = result.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>().single()
        assertTrue(observation.isError)
        assertTrue(observation.content.contains("audit store unavailable"))
    }

    private fun engine(
        tool: AgentTool,
        approvalGate: ApprovalGate,
        events: MutableList<AgentEvent>,
    ): AgentEngine = AgentEngine(
        project = fakeProject(),
        provider = TwoTurnProvider(tool.name),
        approvalGate = approvalGate,
        tools = ToolRegistry(additionalTools = listOf(tool)),
        limits = AgentLimits(maxIterations = 3),
        events = AgentEventSink { event -> events += event },
    )

    private fun testTool(
        execute: suspend (JsonObject, ToolExecutionContext) -> ToolExecutionResult,
    ): AgentTool = object : AgentTool {
        override val name = "dangerous_test"
        override val description = "Test dangerous tool"
        override val inputSchema = JsonObject().apply { addProperty("type", "object") }
        override val dangerous = true

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolExecutionResult = execute(arguments, context)
    }

    private class TwoTurnProvider(private val toolName: String) : ModelProvider {
        override val id = "test"
        private var turn = 0

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse = if (turn++ == 0) {
            ModelResponse(
                blocks = listOf(ContentBlock.ToolCall("call-1", toolName, JsonObject())),
                stopReason = StopReason.TOOL_USE,
            )
        } else {
            ModelResponse(
                blocks = listOf(ContentBlock.Text("done")),
                stopReason = StopReason.COMPLETE,
            )
        }
    }

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> "/tmp/omnicode-test"
            "isDisposed" -> false
            "toString" -> "ApprovalAuditProject"
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
