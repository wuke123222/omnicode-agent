package dev.omnicode.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.provider.ModelProvider
import dev.omnicode.provider.ProviderException
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentEngineControlFlowTest {
    @Test
    fun `iteration boundary returns deterministic evidence without a synthesis call`() = runBlocking {
        var executions = 0
        val tool = object : AgentTool {
            override val name = "bounded_inspection"
            override val description = "Produces one verified observation"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions++
                return ToolExecutionResult("verified inspection evidence")
            }
        }
        var providerCalls = 0
        val provider = object : ModelProvider {
            override val id = "one-tool-call"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("inspect-1", tool.name, JsonObject())),
                    stopReason = StopReason.TOOL_USE,
                )
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 1),
        )

        val result = engine.run("inspect once")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(1, providerCalls)
        assertEquals(1, executions)
        assertPartialResultSections(result)
        assertTrue(result.finalText.contains("verified inspection evidence"))
        assertTrue(result.finalText.contains("maximum of 1 agent iterations"))
        assertTrue(result.finalText.contains("no extra model or tool call was made"))
    }

    @Test
    fun `tool call boundary preserves successful evidence and names pending action`() = runBlocking {
        var executions = 0
        val tool = object : AgentTool {
            override val name = "bounded_tool"
            override val description = "A bounded test tool"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions++
                return ToolExecutionResult("first action completed")
            }
        }
        var providerCalls = 0
        val provider = object : ModelProvider {
            override val id = "two-tool-calls"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("call-$providerCalls", tool.name, JsonObject())),
                    stopReason = StopReason.TOOL_USE,
                )
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 3, maxToolCalls = 1),
        )

        val result = engine.run("run until the tool boundary")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(2, providerCalls)
        assertEquals(1, executions)
        assertPartialResultSections(result)
        assertTrue(result.finalText.contains("first action completed"))
        assertTrue(result.finalText.contains("bounded_tool failure"))
        assertTrue(result.finalText.contains("TOOL_BUDGET_BLOCKED"))
        assertTrue(result.finalText.contains("maximum of 1 tool calls"))
    }

    @Test
    fun `consecutive failure boundary reports failed evidence without another provider call`() = runBlocking {
        var providerCalls = 0
        val failedTool = object : AgentTool {
            override val name = "failing_inspection"
            override val description = "Always returns a structured failure"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult = ToolExecutionResult("fixture unavailable", isError = true)
        }
        val provider = object : ModelProvider {
            override val id = "failed-tool-call"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("failed-1", failedTool.name, JsonObject())),
                    stopReason = StopReason.TOOL_USE,
                )
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(failedTool)),
            limits = AgentLimits(maxIterations = 3, maxConsecutiveFailures = 1),
        )

        val result = engine.run("collect failure evidence")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(1, providerCalls)
        assertPartialResultSections(result)
        assertTrue(result.finalText.contains("fixture unavailable"))
        assertTrue(result.finalText.contains("1 tool observation(s) failed"))
        assertTrue(result.finalText.contains("1 consecutive tool failures"))
    }

    @Test
    fun `run timeout closes active tool event and preserves billed usage`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val tool = slowTool()
        val usage = TokenUsage(inputTokens = 23, outputTokens = 7)
        val engine = AgentEngine(
            project = fakeProject(),
            provider = ToolCallProvider(tool.name, usage),
            approvalGate = ApprovalGate { true },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 2, maxWallTime = Duration.ofMillis(200)),
            events = AgentEventSink { event -> events += event },
        )

        val result = engine.run("run the slow tool")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(usage, result.usage)
        val requested = events.filterIsInstance<AgentEvent.ToolRequested>().single()
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals(requested.callId, completed.callId)
        assertTrue(completed.cancelled)
        assertTrue(completed.isError)
        assertEquals(ToolApprovalOutcome.APPROVED, completed.approvalOutcome)
        val resultBlock = assertIs<ContentBlock.ToolResult>(result.messages.last().blocks.single())
        assertEquals(completed.callId, resultBlock.toolCallId)
        assertTrue(resultBlock.isError)
        assertPartialResultSections(result)
        assertTrue(result.finalText.contains("wall-clock limit"))
        assertTrue(result.finalText.contains("TOOL_TIMEOUT"))
    }

    @Test
    fun `external cancellation still exposes a cancelled result and terminal tool event`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val requested = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<AgentRunResult>()
        val tool = slowTool()
        val engine = AgentEngine(
            project = fakeProject(),
            provider = ToolCallProvider(tool.name, TokenUsage(17, 4)),
            approvalGate = ApprovalGate { true },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 2),
            events = AgentEventSink { event ->
                events += event
                if (event is AgentEvent.ToolRequested) requested.complete(Unit)
            },
        )
        val job = launch {
            delivered.complete(engine.run("run the slow tool"))
        }

        withTimeout(2_000) { requested.await() }
        job.cancelAndJoin()
        val result = withTimeout(2_000) { delivered.await() }

        assertEquals(AgentRunStatus.CANCELLED, result.status)
        assertEquals(TokenUsage(17, 4), result.usage)
        assertTrue(events.filterIsInstance<AgentEvent.ToolCompleted>().single().cancelled)
    }

    @Test
    fun `tool timeout becomes an observation and does not masquerade as run timeout`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val tool = object : AgentTool {
            override val name = "hung_tool"
            override val description = "Never completes"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                delay(Long.MAX_VALUE)
                return ToolExecutionResult("unreachable")
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = TwoTurnProvider(tool.name),
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(
                maxIterations = 3,
                maxWallTime = Duration.ofSeconds(2),
                maxToolTime = Duration.ofMillis(75),
            ),
            events = AgentEventSink { event -> events += event },
        )

        val result = engine.run("test")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertTrue(completed.isError)
        assertFalse(completed.cancelled)
        assertTrue(completed.result.contains("TOOL_TIMEOUT"))
    }

    @Test
    fun `content filtered response is not reported as completed`() = runBlocking {
        val engine = AgentEngine(
            project = fakeProject(),
            provider = SingleResponseProvider(
                ModelResponse(
                    blocks = listOf(ContentBlock.Text("Partial response")),
                    usage = TokenUsage(10, 3),
                    stopReason = StopReason.CONTENT_FILTER,
                ),
            ),
            approvalGate = ApprovalGate { false },
        )

        val result = engine.run("test")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertTrue(result.finalText.contains("content filter"))
        assertEquals(TokenUsage(10, 3), result.usage)
    }

    @Test
    fun `length limited response is reported as budget exhausted`() = runBlocking {
        val engine = AgentEngine(
            project = fakeProject(),
            provider = SingleResponseProvider(
                ModelResponse(
                    blocks = listOf(ContentBlock.Text("Truncated response")),
                    stopReason = StopReason.LENGTH,
                ),
            ),
            approvalGate = ApprovalGate { false },
        )

        val result = engine.run("test")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertTrue(result.finalText.contains("output limit"))
    }

    @Test
    fun `partial tool call stopped by output limit is never executed`() = runBlocking {
        var executed = false
        val tool = object : AgentTool {
            override val name = "partial_tool"
            override val description = "Must not execute when the provider reports truncation"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executed = true
                return ToolExecutionResult("unexpected")
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = SingleResponseProvider(
                ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("partial-call", tool.name, JsonObject())),
                    stopReason = StopReason.LENGTH,
                ),
            ),
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
        )

        val result = engine.run("test")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertFalse(executed)
    }

    @Test
    fun `event sink failure after a side effect does not discard its observation`() = runBlocking {
        var executions = 0
        val tool = object : AgentTool {
            override val name = "one_shot"
            override val description = "Executes once"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions++
                return ToolExecutionResult("side effect completed")
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = TwoTurnProvider(tool.name),
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            events = AgentEventSink { event ->
                if (event is AgentEvent.ToolCompleted) error("audit unavailable")
            },
        )

        val result = engine.run("test")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(1, executions)
        val observations = result.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals("side effect completed", observations.single().content)
    }

    @Test
    fun `provider is not transparently retried after streaming visible output`() = runBlocking {
        var calls = 0
        val deltas = mutableListOf<String>()
        val provider = object : ModelProvider {
            override val id = "partial-stream"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                calls++
                onTextDelta("partial")
                throw ProviderException("stream failed", statusCode = 500)
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            events = AgentEventSink { event ->
                if (event is AgentEvent.TextDelta) deltas += event.text
            },
        )

        val result = engine.run("test")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(1, calls)
        assertEquals(listOf("partial"), deltas)
    }

    @Test
    fun `network and rate limit failures retry while configuration failures do not`() = runBlocking {
        var networkCalls = 0
        val recoveringProvider = object : ModelProvider {
            override val id = "recovering"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                networkCalls++
                if (networkCalls == 1) {
                    throw ProviderException("connection reset", networkFailure = true, requestId = "req-safe")
                }
                return ModelResponse(listOf(ContentBlock.Text("recovered")), stopReason = StopReason.COMPLETE)
            }
        }
        val retryEvents = mutableListOf<AgentEvent>()
        val retrying = AgentEngine(
            project = fakeProject(),
            provider = recoveringProvider,
            approvalGate = ApprovalGate { false },
            limits = AgentLimits(
                providerMaxAttempts = 2,
                providerRetryBaseDelay = Duration.ZERO,
                providerRetryMaxDelay = Duration.ZERO,
                providerRetryJitterRatio = 0.0,
            ),
            events = AgentEventSink { retryEvents += it },
        )

        assertEquals(AgentRunStatus.COMPLETED, retrying.run("test").status)
        assertEquals(2, networkCalls)
        assertTrue(retryEvents.filterIsInstance<AgentEvent.Status>().any { it.message.contains("req-safe") })

        var invalidCalls = 0
        val invalidProvider = object : ModelProvider {
            override val id = "invalid"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                invalidCalls++
                throw ProviderException("invalid endpoint")
            }
        }
        val invalid = AgentEngine(
            project = fakeProject(),
            provider = invalidProvider,
            approvalGate = ApprovalGate { false },
            limits = AgentLimits(
                providerRetryBaseDelay = Duration.ZERO,
                providerRetryMaxDelay = Duration.ZERO,
            ),
        )

        assertEquals(AgentRunStatus.FAILED, invalid.run("test").status)
        assertEquals(1, invalidCalls)
    }

    @Test
    fun `retry delay honors server floor and adds bounded positive jitter`() {
        val limits = AgentLimits(
            providerRetryBaseDelay = Duration.ofMillis(100),
            providerRetryMaxDelay = Duration.ofSeconds(1),
            providerRetryJitterRatio = 0.5,
        )
        val error = ProviderException("limited", statusCode = 429, retryAfterMillis = 750)

        assertEquals(750L, providerRetryDelayMillis(error, failedAttempt = 0, limits = limits, jitterUnit = 0.0))
        assertEquals(938L, providerRetryDelayMillis(error, failedAttempt = 0, limits = limits, jitterUnit = 0.5))
        assertEquals(750L, providerRetryDelayMillis(error, failedAttempt = 2, limits = limits, jitterUnit = 0.0))
    }

    private class ToolCallProvider(
        private val toolName: String,
        private val usage: TokenUsage,
    ) : ModelProvider {
        override val id = "tool-call"

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse = ModelResponse(
            blocks = listOf(ContentBlock.ToolCall("call-slow", toolName, JsonObject())),
            usage = usage,
            stopReason = StopReason.TOOL_USE,
        )
    }

    private class SingleResponseProvider(
        private val response: ModelResponse,
    ) : ModelProvider {
        override val id = "single-response"

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse = response
    }

    private class TwoTurnProvider(private val toolName: String) : ModelProvider {
        override val id = "two-turn"
        private var turn = 0

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse = if (turn++ == 0) {
            ModelResponse(
                blocks = listOf(ContentBlock.ToolCall("call-once", toolName, JsonObject())),
                stopReason = StopReason.TOOL_USE,
            )
        } else {
            ModelResponse(
                blocks = listOf(ContentBlock.Text("done")),
                stopReason = StopReason.COMPLETE,
            )
        }
    }

    private fun slowTool(): AgentTool = object : AgentTool {
        override val name = "slow_write"
        override val description = "A cancellable dangerous test tool"
        override val dangerous = true
        override val inputSchema = JsonObject().apply { addProperty("type", "object") }

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolExecutionResult {
            check(context.approvalGate.approve(ApprovalRequest(name, "Slow write", "test", "test")))
            delay(Long.MAX_VALUE)
            return ToolExecutionResult("unreachable")
        }
    }

    private fun assertPartialResultSections(result: AgentRunResult) {
        listOf("Achieved", "Evidence", "Remaining", "Risks").forEach { heading ->
            assertTrue(result.finalText.contains("\n$heading\n"), "Missing partial-result section: $heading")
        }
    }

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> "/tmp/omnicode-control-flow-test"
            "isDisposed" -> false
            "getName" -> "control-flow-test"
            "toString" -> "ControlFlowTestProject"
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
