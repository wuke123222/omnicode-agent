package dev.omnicode.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentEngineCheckpointTest {
    @Test
    fun `resumed engine continues cumulative iteration tool and token counters`() = runBlocking {
        val saved = mutableListOf<AgentExecutionCheckpoint>()
        var providerCalls = 0
        val provider = object : ModelProvider {
            override val id = "resume-budget-provider"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return ModelResponse(
                    blocks = listOf(ContentBlock.Text("resumed")),
                    usage = dev.omnicode.model.TokenUsage(7, 3),
                    stopReason = StopReason.COMPLETE,
                )
            }
        }
        val historical = AgentPendingTool("old-call", "apply_patch", "{}", dangerous = true, executionStarted = true)
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { true },
            limits = AgentLimits(maxIterations = 4, maxToolCalls = 8, maxInputTokens = 10_000, maxOutputTokens = 100),
            checkpoints = AgentCheckpointSink { saved += it },
            initialUsage = dev.omnicode.model.TokenUsage(20, 10),
            initialIteration = 2,
            initialToolCalls = 3,
            initialPendingTool = historical,
        )

        val result = engine.run("continue")

        assertEquals(1, providerCalls)
        assertEquals(dev.omnicode.model.TokenUsage(27, 13), result.usage)
        assertEquals(listOf(2, 3, 3), saved.map { it.iteration })
        assertTrue(saved.take(2).all { it.toolCalls == 3 && it.pendingTool == historical })
    }

    @Test
    fun `checkpoints surround provider and tool callbacks and clear completed pending tool`() = runBlocking {
        val trace = mutableListOf<String>()
        val saved = mutableListOf<AgentExecutionCheckpoint>()
        val tool = object : AgentTool {
            override val name = "checkpoint_write"
            override val description = "A checkpoint lifecycle fixture"
            override val dangerous = true
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                check(context.approvalGate.approve(ApprovalRequest(name, "Write", "fixture", "fixture")))
                trace += "tool"
                return ToolExecutionResult("write completed")
            }
        }
        var providerTurn = 0
        val provider = object : ModelProvider {
            override val id = "checkpoint-provider"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerTurn++
                trace += "provider-$providerTurn"
                return if (providerTurn == 1) {
                    ModelResponse(
                        blocks = listOf(
                            ContentBlock.ToolCall(
                                id = "call-1",
                                name = tool.name,
                                arguments = JsonObject().apply { addProperty("path", "fixture.txt") },
                            ),
                        ),
                        stopReason = StopReason.TOOL_USE,
                    )
                } else {
                    ModelResponse(listOf(ContentBlock.Text("done")), stopReason = StopReason.COMPLETE)
                }
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { true },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 3),
            checkpoints = AgentCheckpointSink { checkpoint ->
                saved += checkpoint
                trace += checkpointLabel(checkpoint)
            },
        )

        val result = engine.run("change the fixture")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(
            listOf(
                "checkpoint-initial",
                "checkpoint-before-provider",
                "provider-1",
                "checkpoint-pending",
                "checkpoint-pending",
                "checkpoint-executing",
                "tool",
                "checkpoint-tool-result",
                "checkpoint-before-provider",
                "provider-2",
                "checkpoint-assistant",
            ),
            trace,
        )
        val requested = assertNotNull(saved.first { it.pendingTool?.executionStarted == false }.pendingTool)
        assertEquals("call-1", requested.callId)
        assertEquals(tool.name, requested.name)
        assertTrue(requested.dangerous)
        assertTrue(requested.argumentsJson.contains("fixture.txt"))
        assertTrue(saved.first { it.pendingTool?.executionStarted == true }.pendingTool?.executionStarted == true)
        val toolResult = saved.first { checkpointLabel(it) == "checkpoint-tool-result" }
        assertEquals(null, toolResult.pendingTool)
        assertTrue(toolResult.messages.last().blocks.single() is ContentBlock.ToolResult)
        assertEquals(listOf(0, 1, 1, 1, 1, 1, 2, 2), saved.map(AgentExecutionCheckpoint::iteration))
    }

    @Test
    fun `checkpoint failure after side effect emits status and preserves completed observation`() = runBlocking {
        var executions = 0
        var failedOnce = false
        val events = mutableListOf<AgentEvent>()
        val saved = mutableListOf<AgentExecutionCheckpoint>()
        val tool = object : AgentTool {
            override val name = "checkpoint_side_effect"
            override val description = "Completes before checkpoint storage fails"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions++
                return ToolExecutionResult("side effect evidence")
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = TwoTurnProvider(tool.name),
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 3),
            events = AgentEventSink { events += it },
            checkpoints = AgentCheckpointSink { checkpoint ->
                val hasToolResult = checkpoint.messages
                    .flatMap { it.blocks }
                    .any { it is ContentBlock.ToolResult }
                if (hasToolResult && !failedOnce) {
                    failedOnce = true
                    error("checkpoint disk unavailable")
                }
                saved += checkpoint
            },
        )

        val result = engine.run("execute once")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(1, executions)
        assertTrue(failedOnce)
        assertTrue(
            events.filterIsInstance<AgentEvent.Status>()
                .any { it.message.contains("Checkpoint save failed") && it.message.contains("disk unavailable") },
        )
        val observation = result.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals("side effect evidence", observation.content)
        assertFalse(observation.isError)
        assertTrue(saved.last().messages.last().role == MessageRole.ASSISTANT)
    }

    @Test
    fun `cancellation retains execution-started pending tool for side effect review`() = runBlocking {
        val enteredTool = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<AgentRunResult>()
        val saved = mutableListOf<AgentExecutionCheckpoint>()
        val tool = object : AgentTool {
            override val name = "checkpoint_slow_write"
            override val description = "A cancellable side effect fixture"
            override val dangerous = true
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                check(context.approvalGate.approve(ApprovalRequest(name, "Write", "fixture", "fixture")))
                enteredTool.complete(Unit)
                delay(Long.MAX_VALUE)
                return ToolExecutionResult("unreachable")
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = ToolCallProvider(tool.name),
            approvalGate = ApprovalGate { true },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 2),
            checkpoints = AgentCheckpointSink { saved += it },
        )
        val job = launch { delivered.complete(engine.run("start write")) }

        withTimeout(2_000) { enteredTool.await() }
        job.cancelAndJoin()
        val result = withTimeout(2_000) { delivered.await() }

        assertEquals(AgentRunStatus.CANCELLED, result.status)
        val recovery = saved.last()
        val pending = assertNotNull(recovery.pendingTool)
        assertEquals("cancel-call", pending.callId)
        assertTrue(pending.executionStarted)
        assertTrue(pending.dangerous)
        val cancellationObservation = recovery.messages
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.ToolResult>()
            .single()
        assertTrue(cancellationObservation.isError)
        assertTrue(cancellationObservation.content.contains("TOOL_CANCELLED"))
        assertTrue(cancellationObservation.content.contains("SIDE_EFFECT_STATE_UNKNOWN"))
    }

    @Test
    fun `cancellation while waiting for approval remains safe to retry`() = runBlocking {
        val approvalOpened = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<AgentRunResult>()
        val saved = mutableListOf<AgentExecutionCheckpoint>()
        var executions = 0
        val tool = object : AgentTool {
            override val name = "checkpoint_waiting_write"
            override val description = "Waits for approval before a fixture side effect"
            override val dangerous = true
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                if (!context.approvalGate.approve(ApprovalRequest(name, "Write", "fixture", "fixture"))) {
                    return ToolExecutionResult("rejected", true)
                }
                executions++
                return ToolExecutionResult("executed")
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = ToolCallProvider(tool.name),
            approvalGate = ApprovalGate {
                approvalOpened.complete(Unit)
                delay(Long.MAX_VALUE)
                true
            },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 2),
            checkpoints = AgentCheckpointSink { saved += it },
        )
        val job = launch { delivered.complete(engine.run("start write")) }

        withTimeout(2_000) { approvalOpened.await() }
        job.cancelAndJoin()
        val result = withTimeout(2_000) { delivered.await() }

        assertEquals(AgentRunStatus.CANCELLED, result.status)
        assertEquals(0, executions)
        val pending = assertNotNull(saved.last().pendingTool)
        assertFalse(pending.executionStarted)
        val observation = saved.last().messages
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.ToolResult>()
            .single()
        assertFalse(observation.content.contains("SIDE_EFFECT_STATE_UNKNOWN"))
    }

    @Test
    fun `dangerous tool is blocked when execution-start checkpoint cannot be saved`() = runBlocking {
        var executions = 0
        val tool = object : AgentTool {
            override val name = "checkpoint_guarded_write"
            override val description = "Requires a durable checkpoint before a fixture side effect"
            override val dangerous = true
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                check(context.approvalGate.approve(ApprovalRequest(name, "Write", "fixture", "fixture")))
                executions++
                return ToolExecutionResult("executed")
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = TwoTurnProvider(tool.name),
            approvalGate = ApprovalGate { true },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 3),
            checkpoints = AgentCheckpointSink { checkpoint ->
                if (checkpoint.pendingTool?.executionStarted == true) error("checkpoint disk unavailable")
            },
        )

        val result = engine.run("start guarded write")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(0, executions)
        val observation = result.messages
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.ToolResult>()
            .single()
        assertTrue(observation.isError)
        assertTrue(observation.content.contains("CHECKPOINT_REQUIRED"))
    }

    @Test
    fun `provider is blocked when its paid reservation checkpoint cannot be saved`() = runBlocking {
        var providerCalls = 0
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100_000)
        val provider = object : ModelProvider {
            override val id = "checkpoint-required-provider"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return ModelResponse(listOf(ContentBlock.Text("unreachable")), stopReason = StopReason.COMPLETE)
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            sharedLedger = ledger,
            checkpoints = AgentCheckpointSink { checkpoint ->
                if (checkpoint.pendingProviderAttempt != null) error("checkpoint disk unavailable")
            },
        )

        val result = engine.run("do not dispatch without durability")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(0, providerCalls)
        assertTrue(result.finalText.contains("CHECKPOINT_REQUIRED"))
        assertEquals(0, ledger.snapshot().activeReservations)
        assertEquals(dev.omnicode.model.TokenUsage(), ledger.snapshot().usage)
    }

    private fun checkpointLabel(checkpoint: AgentExecutionCheckpoint): String = when {
        checkpoint.iteration == 0 -> "checkpoint-initial"
        checkpoint.pendingTool?.executionStarted == false -> "checkpoint-pending"
        checkpoint.pendingTool?.executionStarted == true -> "checkpoint-executing"
        checkpoint.iteration == 1 && checkpoint.messages.last().blocks.any { it is ContentBlock.ToolResult } ->
            "checkpoint-tool-result"
        checkpoint.messages.last().role == MessageRole.ASSISTANT -> "checkpoint-assistant"
        else -> "checkpoint-before-provider"
    }

    private class TwoTurnProvider(private val toolName: String) : ModelProvider {
        override val id = "checkpoint-two-turn"
        private var turn = 0

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse = if (turn++ == 0) {
            ModelResponse(
                blocks = listOf(ContentBlock.ToolCall("side-effect-call", toolName, JsonObject())),
                stopReason = StopReason.TOOL_USE,
            )
        } else {
            ModelResponse(listOf(ContentBlock.Text("done")), stopReason = StopReason.COMPLETE)
        }
    }

    private class ToolCallProvider(private val toolName: String) : ModelProvider {
        override val id = "checkpoint-tool-call"

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse = ModelResponse(
            blocks = listOf(ContentBlock.ToolCall("cancel-call", toolName, JsonObject())),
            stopReason = StopReason.TOOL_USE,
        )
    }

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> "/tmp/omnicode-checkpoint-test"
            "isDisposed" -> false
            "getName" -> "checkpoint-test"
            "toString" -> "CheckpointTestProject"
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
