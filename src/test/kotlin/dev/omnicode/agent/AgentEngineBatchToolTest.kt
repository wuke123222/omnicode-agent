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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for provider responses that contain more than one tool call.
 *
 * AgentEngine must execute a valid provider batch sequentially while preserving per-call budget,
 * approval, checkpoint, and audit boundaries.
 */
class AgentEngineBatchToolTest {
    @Test
    fun `two tool calls execute sequentially and emit an independent audit lifecycle`() = runBlocking {
        val executionOrder = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        val tool = recordingTool("batch_read") { label, _ ->
            executionOrder += label
            ToolExecutionResult("observed-$label")
        }
        val calls = listOf(call("call-first", tool.name, "first"), call("call-second", tool.name, "second"))
        var followupResults = emptyList<ContentBlock.ToolResult>()
        val provider = BatchThenCompleteProvider(calls) { request ->
            followupResults = request.toolResults()
        }
        val engine = engine(
            provider = provider,
            tools = listOf(tool),
            events = events,
        )

        val result = engine.run("inspect both inputs")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(listOf("first", "second"), executionOrder)
        assertEquals(listOf("call-first", "call-second"), followupResults.map(ContentBlock.ToolResult::toolCallId))
        assertTrue(followupResults.all { !it.isError })
        assertEquals(
            listOf("requested:call-first", "completed:call-first", "requested:call-second", "completed:call-second"),
            events.mapNotNull { event ->
                when (event) {
                    is AgentEvent.ToolRequested -> "requested:${event.callId}"
                    is AgentEvent.ToolCompleted -> "completed:${event.callId}"
                    else -> null
                }
            },
        )
    }

    @Test
    fun `batch larger than remaining tool budget executes nothing`() = runBlocking {
        var executions = 0
        val checkpoints = mutableListOf<AgentExecutionCheckpoint>()
        val tool = recordingTool("budgeted_batch") { label, _ ->
            executions++
            ToolExecutionResult("unexpected-$label")
        }
        val calls = listOf(call("budget-a", tool.name, "a"), call("budget-b", tool.name, "b"))
        val provider = object : ModelProvider {
            override val id = "over-budget-batch"
            private var callsMade = 0

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                check(callsMade++ == 0) { "An over-budget batch must stop before another provider request" }
                return ModelResponse(calls, stopReason = StopReason.TOOL_USE)
            }
        }
        val engine = engine(
            provider = provider,
            tools = listOf(tool),
            limits = AgentLimits(maxIterations = 3, maxToolCalls = 1),
            checkpoints = AgentCheckpointSink { checkpoints += it },
        )

        val result = engine.run("do not partially execute the batch")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, executions)
        assertEquals(0, checkpoints.maxOf(AgentExecutionCheckpoint::toolCalls))
        val observations = result.messages.toolResults()
        assertEquals(setOf("budget-a", "budget-b"), observations.mapTo(linkedSetOf(), ContentBlock.ToolResult::toolCallId))
        assertTrue(observations.all(ContentBlock.ToolResult::isError))
        assertTrue(observations.all { it.content.contains("BUDGET", ignoreCase = true) })
    }

    @Test
    fun `approval rejection aborts every later call in the same batch`() = runBlocking {
        val enteredTools = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        val tool = recordingTool("dangerous_batch", dangerous = true) { label, context ->
            enteredTools += label
            val approved = context.approvalGate.approve(
                ApprovalRequest(toolName = "dangerous_batch", title = "Change $label", details = label, risk = "test"),
            )
            if (approved) ToolExecutionResult("changed-$label")
            else ToolExecutionResult("REJECTED_BY_USER: $label", isError = true)
        }
        val provider = BatchThenCompleteProvider(
            listOf(call("reject-first", tool.name, "first"), call("must-not-run", tool.name, "second")),
        )
        val engine = engine(
            provider = provider,
            tools = listOf(tool),
            approvalGate = ApprovalGate { false },
            events = events,
        )

        val result = engine.run("reject the first side effect")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(listOf("first"), enteredTools)
        val observations = result.messages.toolResults().associateBy(ContentBlock.ToolResult::toolCallId)
        assertTrue(observations.getValue("reject-first").isError)
        assertTrue(observations.getValue("must-not-run").isError)
        assertTrue(observations.getValue("must-not-run").content.contains("BATCH_ABORTED_AFTER_REJECTION"))
        val completions = events.filterIsInstance<AgentEvent.ToolCompleted>().associateBy(AgentEvent.ToolCompleted::callId)
        assertEquals(ToolApprovalOutcome.REJECTED, completions.getValue("reject-first").approvalOutcome)
        assertEquals(ToolApprovalOutcome.NOT_REQUESTED, completions.getValue("must-not-run").approvalOutcome)
    }

    @Test
    fun `all observations in one provider batch share one total character limit`() = runBlocking {
        val maxBatchObservationChars = 1_000
        val executions = mutableListOf<String>()
        val tool = recordingTool("large_batch_read") { label, _ ->
            executions += label
            val marker = if (label == "alpha") 'A' else 'B'
            ToolExecutionResult("$label:" + marker.toString().repeat(900))
        }
        var followupResults = emptyList<ContentBlock.ToolResult>()
        val provider = BatchThenCompleteProvider(
            listOf(call("large-a", tool.name, "alpha"), call("large-b", tool.name, "beta")),
        ) { request ->
            followupResults = request.toolResults()
        }
        val engine = engine(
            provider = provider,
            tools = listOf(tool),
            limits = AgentLimits(
                maxIterations = 3,
                maxObservationChars = maxBatchObservationChars,
            ),
        )

        val result = engine.run("inspect both large observations")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(listOf("alpha", "beta"), executions)
        assertEquals(2, followupResults.size)
        assertTrue(followupResults.all { !it.isError })
        assertTrue(followupResults.all { it.content.contains("[observation truncated]") })
        assertTrue(
            followupResults.sumOf { it.content.length } <= maxBatchObservationChars,
            "The complete batch, including truncation markers, must fit the observation budget",
        )
    }

    @Test
    fun `blank or duplicate call IDs fail the whole batch before execution`() = runBlocking {
        var executions = 0
        val tool = recordingTool("validated_batch") { label, _ ->
            executions++
            ToolExecutionResult("unexpected-$label")
        }
        val malformedBatches = listOf(
            listOf(call("duplicate", tool.name, "a"), call("duplicate", tool.name, "b")),
            listOf(call("", tool.name, "blank")),
        )

        malformedBatches.forEach { calls ->
            val result = engine(
                provider = BatchThenCompleteProvider(calls),
                tools = listOf(tool),
            ).run("validate the batch")

            assertEquals(AgentRunStatus.FAILED, result.status)
            assertTrue(result.finalText.contains("invalid tool batch", ignoreCase = true))
            val replayBlocks = result.messages.flatMap { it.blocks }
            assertTrue(replayBlocks.none { it is ContentBlock.ToolCall || it is ContentBlock.ToolResult })
            assertTrue(replayBlocks.filterIsInstance<ContentBlock.Text>().any { it.text.contains("INVALID_TOOL_BATCH") })
        }
        assertEquals(0, executions)
    }

    @Test
    fun `length stopped batch closes every call with a non executed result`() = runBlocking {
        var executions = 0
        val events = mutableListOf<AgentEvent>()
        val tool = recordingTool("partial_batch") { label, _ ->
            executions++
            ToolExecutionResult("unexpected-$label")
        }
        val calls = listOf(call("partial-a", tool.name, "a"), call("partial-b", tool.name, "b"))
        val provider = object : ModelProvider {
            override val id = "length-stopped-batch"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse = ModelResponse(calls, stopReason = StopReason.LENGTH)
        }

        val result = engine(provider, listOf(tool), events = events).run("do not execute partial calls")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, executions)
        val observations = result.messages.toolResults()
        assertEquals(setOf("partial-a", "partial-b"), observations.mapTo(linkedSetOf(), ContentBlock.ToolResult::toolCallId))
        assertTrue(observations.all { it.content.contains("NOT_EXECUTED_INCOMPLETE_RESPONSE") })
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolRequested>().size)
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolCompleted>().size)
    }

    @Test
    fun `repeated action preflight closes every call without execution`() = runBlocking {
        var executions = 0
        val events = mutableListOf<AgentEvent>()
        val tool = recordingTool("repeated_batch") { label, _ ->
            executions++
            ToolExecutionResult("unexpected-$label")
        }
        val calls = listOf(call("repeat-a", tool.name, "same"), call("repeat-b", tool.name, "same"))

        val result = engine(
            provider = BatchThenCompleteProvider(calls),
            tools = listOf(tool),
            limits = AgentLimits(maxIterations = 3, maxRepeatedAction = 1),
            events = events,
        ).run("block repeated calls")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(0, executions)
        assertTrue(result.messages.toolResults().all { it.content.contains("REPEATED_ACTION_BLOCKED") })
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolRequested>().size)
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolCompleted>().size)
    }

    @Test
    fun `cancellation during the second dangerous call preserves the first result and current recovery point`() = runBlocking {
        val enteredSecond = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<AgentRunResult>()
        val checkpoints = mutableListOf<AgentExecutionCheckpoint>()
        val tool = recordingTool("recoverable_batch", dangerous = true) { label, context ->
            check(
                context.approvalGate.approve(
                    ApprovalRequest(toolName = "recoverable_batch", title = "Run $label", details = label, risk = "test"),
                ),
            )
            if (label == "first") return@recordingTool ToolExecutionResult("first-complete")
            enteredSecond.complete(Unit)
            delay(Long.MAX_VALUE)
            ToolExecutionResult("unreachable")
        }
        val engine = engine(
            provider = BatchThenCompleteProvider(
                listOf(call("recover-first", tool.name, "first"), call("recover-second", tool.name, "second")),
            ),
            tools = listOf(tool),
            approvalGate = ApprovalGate { true },
            checkpoints = AgentCheckpointSink { checkpoints += it },
        )
        val job = launch { delivered.complete(engine.run("run the recoverable batch")) }

        withTimeout(2_000) { enteredSecond.await() }
        job.cancelAndJoin()
        val result = withTimeout(2_000) { delivered.await() }

        assertEquals(AgentRunStatus.CANCELLED, result.status)
        val recovery = checkpoints.last()
        val pending = assertNotNull(recovery.pendingTool)
        assertEquals("recover-second", pending.callId)
        assertTrue(pending.dangerous)
        assertTrue(pending.executionStarted)
        val observations = recovery.messages.toolResults().associateBy(ContentBlock.ToolResult::toolCallId)
        assertEquals("first-complete", observations.getValue("recover-first").content)
        assertTrue(observations.getValue("recover-second").content.contains("SIDE_EFFECT_STATE_UNKNOWN"))
    }

    @Test
    fun `dangerous timeout aborts every later call in the batch`() = runBlocking {
        val entered = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        val tool = recordingTool("timed_batch", dangerous = true) { label, context ->
            entered += label
            check(
                context.approvalGate.approve(
                    ApprovalRequest(toolName = "timed_batch", title = "Run $label", details = label, risk = "test"),
                ),
            )
            if (label == "first") delay(Long.MAX_VALUE)
            ToolExecutionResult("completed-$label")
        }
        val engine = engine(
            provider = BatchThenCompleteProvider(
                listOf(call("timeout-first", tool.name, "first"), call("timeout-second", tool.name, "second")),
            ),
            tools = listOf(tool),
            limits = AgentLimits(
                maxIterations = 3,
                maxWallTime = Duration.ofSeconds(2),
                maxToolTime = Duration.ofMillis(50),
            ),
            approvalGate = ApprovalGate { true },
            events = events,
        )

        val result = engine.run("time out the first action")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(listOf("first"), entered)
        val observations = result.messages.toolResults().associateBy(ContentBlock.ToolResult::toolCallId)
        assertTrue(observations.getValue("timeout-first").content.contains("SIDE_EFFECT_STATE_UNKNOWN"))
        assertTrue(observations.getValue("timeout-second").content.contains("BATCH_ABORTED_AFTER_UNKNOWN_SIDE_EFFECT"))
        val completions = events.filterIsInstance<AgentEvent.ToolCompleted>().associateBy(AgentEvent.ToolCompleted::callId)
        assertTrue(completions.getValue("timeout-first").cancelled)
        assertEquals(ToolApprovalOutcome.NOT_REQUESTED, completions.getValue("timeout-second").approvalOutcome)
    }

    private fun engine(
        provider: ModelProvider,
        tools: List<AgentTool>,
        limits: AgentLimits = AgentLimits(maxIterations = 3),
        approvalGate: ApprovalGate = ApprovalGate { error("Approval was not expected") },
        events: MutableList<AgentEvent> = mutableListOf(),
        checkpoints: AgentCheckpointSink = AgentCheckpointSink {},
    ): AgentEngine = AgentEngine(
        project = fakeProject(),
        provider = provider,
        approvalGate = approvalGate,
        tools = ToolRegistry(additionalTools = tools),
        limits = limits,
        events = AgentEventSink { event -> events += event },
        checkpoints = checkpoints,
    )

    private fun recordingTool(
        name: String,
        dangerous: Boolean = false,
        execute: suspend (String, ToolExecutionContext) -> ToolExecutionResult,
    ): AgentTool = object : AgentTool {
        override val name = name
        override val description = "Batch tool fixture"
        override val dangerous = dangerous
        override val inputSchema = JsonObject().apply { addProperty("type", "object") }

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolExecutionResult = execute(arguments.get("label").asString, context)
    }

    private fun call(id: String, toolName: String, label: String): ContentBlock.ToolCall = ContentBlock.ToolCall(
        id = id,
        name = toolName,
        arguments = JsonObject().apply { addProperty("label", label) },
    )

    private class BatchThenCompleteProvider(
        private val calls: List<ContentBlock.ToolCall>,
        private val onFollowup: (ModelRequest) -> Unit = {},
    ) : ModelProvider {
        override val id = "batch-then-complete"
        private var turn = 0

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse = if (turn++ == 0) {
            ModelResponse(calls, stopReason = StopReason.TOOL_USE)
        } else {
            onFollowup(request)
            ModelResponse(listOf(ContentBlock.Text("done")), stopReason = StopReason.COMPLETE)
        }
    }

    private fun ModelRequest.toolResults(): List<ContentBlock.ToolResult> = messages.toolResults()

    private fun List<dev.omnicode.model.ConversationMessage>.toolResults(): List<ContentBlock.ToolResult> =
        flatMap { message -> message.blocks.filterIsInstance<ContentBlock.ToolResult>() }

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> "/tmp/omnicode-batch-tool-test"
            "isDisposed" -> false
            "getName" -> "batch-tool-test"
            "toString" -> "BatchToolTestProject"
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
