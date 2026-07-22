package dev.omnicode.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.provider.ModelProvider
import dev.omnicode.provider.ProviderException
import dev.omnicode.tool.ApprovalGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentEngineSharedBudgetTest {
    @Test
    fun `serial turns shrink request and reservation to the remaining output budget`() = runBlocking {
        val provider = ScriptedProvider(
            listOf(
                ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("call-1", "missing_tool", JsonObject())),
                    usage = TokenUsage(inputTokens = 5, outputTokens = 4),
                    stopReason = StopReason.TOOL_USE,
                ),
                ModelResponse(
                    blocks = listOf(ContentBlock.Text("done")),
                    usage = TokenUsage(inputTokens = 5, outputTokens = 3),
                    stopReason = StopReason.COMPLETE,
                ),
            ),
        )
        val ledger = SharedAgentBudgetLedger(
            maxTotalTokens = 100_000,
            maxInputTokens = 99_990,
            maxOutputTokens = 10,
        )

        val result = engine(
            provider = provider,
            identity = AgentIdentity("lead", role = AgentRole.LEAD, displayName = "Lead"),
            ledger = ledger,
            limits = AgentLimits(
                maxInputTokens = 99_990,
                maxOutputTokens = 10,
                maxOutputTokensPerTurn = 10,
            ),
        ).run("inspect then finish")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(listOf(10, 6), provider.requests.map(ModelRequest::maxOutputTokens))
        assertEquals(TokenUsage(10, 7), result.usage)
        assertEquals(TokenUsage(10, 7), ledger.snapshot().usage)
    }

    @Test
    fun `dynamic turn limits still preserve concurrent shared reservations`() = runBlocking {
        val enteredProvider = CompletableDeferred<Unit>()
        val ledger = SharedAgentBudgetLedger(
            maxTotalTokens = 100_000,
            maxInputTokens = 99_990,
            maxOutputTokens = 10,
        )
        val limits = AgentLimits(
            maxInputTokens = 99_990,
            maxOutputTokens = 10,
            maxOutputTokensPerTurn = 10,
        )
        val blockingProvider = object : ModelProvider {
            override val id = "blocking"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                enteredProvider.complete(Unit)
                delay(Long.MAX_VALUE)
                error("unreachable")
            }
        }
        val first = launch {
            engine(
                provider = blockingProvider,
                identity = AgentIdentity("lead-a", role = AgentRole.LEAD, displayName = "Lead A"),
                ledger = ledger,
                limits = limits,
            ).run("hold the reservation")
        }
        withTimeout(2_000) { enteredProvider.await() }
        val rejectedProvider = CapturingProvider()

        val rejected = engine(
            provider = rejectedProvider,
            identity = AgentIdentity("lead-b", role = AgentRole.LEAD, displayName = "Lead B"),
            ledger = ledger,
            limits = limits,
        ).run("compete for the reservation")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, rejected.status)
        assertEquals(0, rejectedProvider.calls.get())
        assertEquals(1, ledger.snapshot().activeReservations)
        first.cancelAndJoin()
        assertEquals(0, ledger.snapshot().activeReservations)
    }

    @Test
    fun `local token usage accumulation saturates instead of wrapping`() = runBlocking {
        val provider = ScriptedProvider(
            listOf(
                ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("call-1", "missing_tool", JsonObject())),
                    usage = TokenUsage(inputTokens = 1, outputTokens = Long.MAX_VALUE - 5),
                    stopReason = StopReason.TOOL_USE,
                ),
                ModelResponse(
                    blocks = listOf(ContentBlock.Text("done")),
                    usage = TokenUsage(inputTokens = 1, outputTokens = 10),
                    stopReason = StopReason.COMPLETE,
                ),
            ),
        )

        val result = engine(
            provider = provider,
            identity = AgentIdentity("lead", role = AgentRole.LEAD, displayName = "Lead"),
            limits = AgentLimits(
                maxInputTokens = 10_000,
                maxOutputTokens = Long.MAX_VALUE,
                maxOutputTokensPerTurn = 10,
            ),
        ).run("exercise usage accounting")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(Long.MAX_VALUE, result.usage.outputTokens)
        assertEquals(listOf(10, 5), provider.requests.map(ModelRequest::maxOutputTokens))
    }

    @Test
    fun `shared hard limit rejects a request before the provider call`() = runBlocking {
        val provider = CapturingProvider()
        val engine = engine(
            provider = provider,
            identity = AgentIdentity("explorer-1", "lead", AgentRole.EXPLORER, "Explorer 1"),
            ledger = SharedAgentBudgetLedger(maxTotalTokens = 100),
        )

        val result = engine.run("inspect the project")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, provider.calls.get())
        assertEquals(TokenUsage(), result.usage)
    }

    @Test
    fun `configured cost limit rejects unpriced model before the provider call`() = runBlocking {
        val provider = CapturingProvider()
        val ledger = SharedAgentBudgetLedger(
            maxTotalTokens = 100_000,
            maxCostUsd = BigDecimal("1.00"),
            estimator = { null },
        )

        val result = engine(
            provider = provider,
            identity = AgentIdentity("lead", role = AgentRole.LEAD, displayName = "Lead"),
            ledger = ledger,
        ).run("inspect the project")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertTrue(result.error?.message.orEmpty().contains("pricing is unavailable"))
        assertEquals(0, provider.calls.get())
        assertEquals(0, ledger.snapshot().activeReservations)
    }

    @Test
    fun `usage events expose aggregate shared usage while results stay agent local`() = runBlocking {
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100_000)
        val eventsA = mutableListOf<AgentEvent>()
        val eventsB = mutableListOf<AgentEvent>()
        val usage = TokenUsage(11, 3)

        val resultA = engine(
            provider = CapturingProvider(usage),
            identity = AgentIdentity("explorer-1", "lead", AgentRole.EXPLORER, "Explorer"),
            ledger = ledger,
            events = eventsA,
        ).run("inspect")
        val resultB = engine(
            provider = CapturingProvider(usage),
            identity = AgentIdentity("reviewer-1", "lead", AgentRole.REVIEWER, "Reviewer"),
            ledger = ledger,
            events = eventsB,
        ).run("review")

        assertEquals(usage, resultA.usage)
        assertEquals(usage, resultB.usage)
        assertEquals(usage, eventsA.filterIsInstance<AgentEvent.UsageUpdated>().single().usage)
        assertEquals(TokenUsage(22, 6), eventsB.filterIsInstance<AgentEvent.UsageUpdated>().single().usage)
        assertEquals(TokenUsage(11, 3), ledger.snapshot().usageByAgent["explorer-1"])
        assertEquals(TokenUsage(11, 3), ledger.snapshot().usageByAgent["reviewer-1"])
    }

    @Test
    fun `retry attempts keep one idempotency key and conservatively account an uncertain charge`() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val checkpoints = mutableListOf<AgentExecutionCheckpoint>()
        val provider = object : ModelProvider {
            override val id = "retry-accounting"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                requests += request
                if (requests.size == 1) throw ProviderException("connection reset", networkFailure = true)
                return ModelResponse(
                    blocks = listOf(ContentBlock.Text("recovered")),
                    usage = TokenUsage(5, 2),
                    stopReason = StopReason.COMPLETE,
                )
            }
        }
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 10_000, maxOutputTokens = 30)
        val engine = AgentEngine(
            project = project(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            identity = AgentIdentity("lead", role = AgentRole.LEAD, displayName = "Lead"),
            sharedLedger = ledger,
            providerRequestScopeId = "workflow-retry",
            limits = AgentLimits(
                maxInputTokens = 10_000,
                maxOutputTokens = 30,
                maxOutputTokensPerTurn = 10,
                providerMaxAttempts = 2,
                providerRetryBaseDelay = Duration.ZERO,
                providerRetryMaxDelay = Duration.ZERO,
                providerRetryJitterRatio = 0.0,
            ),
            checkpoints = AgentCheckpointSink { checkpoints += it },
        )

        val result = engine.run("retry safely")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(2, requests.size)
        assertEquals(1, requests.mapNotNull(ModelRequest::idempotencyKey).distinct().size)
        assertTrue(requests.first().idempotencyKey?.startsWith("omnicode-") == true)
        assertEquals(12, ledger.snapshot().usage.outputTokens)
        assertTrue(checkpoints.any { it.pendingProviderAttempt?.attempt == 1 })
        assertTrue(checkpoints.any { it.pendingProviderAttempt?.attempt == 2 })
        assertTrue(checkpoints.any { it.pendingProviderAttempt == null && it.sharedBudget?.usage?.outputTokens == 10L })
    }

    @Test
    fun `known local provider rejection clears reservation without charging tokens`() = runBlocking {
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100_000, maxOutputTokens = 10)
        val provider = object : ModelProvider {
            override val id = "local-rejection"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse = throw ProviderException("credentials are missing")
        }

        val result = engine(
            provider = provider,
            identity = AgentIdentity("lead", role = AgentRole.LEAD, displayName = "Lead"),
            ledger = ledger,
            limits = AgentLimits(maxInputTokens = 100_000, maxOutputTokens = 10, maxOutputTokensPerTurn = 10),
        ).run("validate locally")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(TokenUsage(), ledger.snapshot().usage)
        assertEquals(TokenUsage(), ledger.snapshot().reservedUsage)
    }

    @Test
    fun `uncertain first attempt closes the hard limit before a retry`() = runBlocking {
        var calls = 0
        val provider = object : ModelProvider {
            override val id = "retry-budget"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                calls++
                throw ProviderException("timeout", networkFailure = true)
            }
        }
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 10_000, maxOutputTokens = 10)
        val result = engine(
            provider = provider,
            identity = AgentIdentity("lead", role = AgentRole.LEAD, displayName = "Lead"),
            ledger = ledger,
            limits = AgentLimits(
                maxInputTokens = 10_000,
                maxOutputTokens = 10,
                maxOutputTokensPerTurn = 10,
                providerMaxAttempts = 2,
                providerRetryBaseDelay = Duration.ZERO,
                providerRetryMaxDelay = Duration.ZERO,
                providerRetryJitterRatio = 0.0,
            ),
        ).run("do not overspend")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(1, calls)
        assertEquals(10, ledger.snapshot().usage.outputTokens)
    }

    @Test
    fun `retry is blocked when uncertain usage cannot be persisted`() = runBlocking {
        var calls = 0
        val provider = object : ModelProvider {
            override val id = "retry-checkpoint"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                calls++
                throw ProviderException("connection reset", networkFailure = true)
            }
        }
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100_000, maxOutputTokens = 30)
        val engine = AgentEngine(
            project = project(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            identity = AgentIdentity("lead", role = AgentRole.LEAD, displayName = "Lead"),
            sharedLedger = ledger,
            providerRequestScopeId = "workflow-checkpoint-failure",
            limits = AgentLimits(
                maxInputTokens = 100_000,
                maxOutputTokens = 30,
                maxOutputTokensPerTurn = 10,
                providerMaxAttempts = 2,
                providerRetryBaseDelay = Duration.ZERO,
                providerRetryMaxDelay = Duration.ZERO,
                providerRetryJitterRatio = 0.0,
            ),
            checkpoints = AgentCheckpointSink { checkpoint ->
                if (checkpoint.pendingProviderAttempt == null && checkpoint.sharedBudget?.usage?.outputTokens == 10L) {
                    error("disk unavailable")
                }
            },
        )

        val result = engine.run("do not retry without durable usage")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(1, calls)
        assertEquals(10, ledger.snapshot().usage.outputTokens)
        assertTrue(result.finalText.contains("uncertain usage could not be saved"))
    }

    @Test
    fun `provider cancellation conservatively commits the shared reservation`() = runBlocking {
        val enteredProvider = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<AgentRunResult>()
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100_000, maxOutputTokens = 10)
        val provider = object : ModelProvider {
            override val id = "blocking"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                enteredProvider.complete(Unit)
                delay(Long.MAX_VALUE)
                error("unreachable")
            }
        }
        val job = launch {
            delivered.complete(
                engine(
                    provider = provider,
                    identity = AgentIdentity("planner-1", "lead", AgentRole.PLANNER, "Planner"),
                    ledger = ledger,
                    limits = AgentLimits(maxInputTokens = 100_000, maxOutputTokens = 10, maxOutputTokensPerTurn = 10),
                ).run("plan"),
            )
        }

        withTimeout(2_000) { enteredProvider.await() }
        assertEquals(1, ledger.snapshot().activeReservations)
        job.cancelAndJoin()
        val result = withTimeout(2_000) { delivered.await() }

        assertEquals(AgentRunStatus.CANCELLED, result.status)
        assertEquals(10, ledger.snapshot().usage.outputTokens)
        assertTrue(ledger.snapshot().usage.inputTokens > 0)
        assertEquals(TokenUsage(), ledger.snapshot().reservedUsage)
        assertEquals(0, ledger.snapshot().activeReservations)
    }

    @Test
    fun `specialist reserves its last turn for a staged report without tools`() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ModelProvider {
            override val id = "specialist-finalization"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                requests += request
                return if (requests.size == 1) {
                    ModelResponse(
                        blocks = listOf(ContentBlock.ToolCall("inspect-1", "missing_tool", JsonObject())),
                        usage = TokenUsage(10, 60),
                        stopReason = StopReason.TOOL_USE,
                    )
                } else {
                    ModelResponse(
                        blocks = listOf(ContentBlock.Text("staged evidence")),
                        usage = TokenUsage(10, 5),
                        stopReason = StopReason.COMPLETE,
                    )
                }
            }
        }

        val result = engine(
            provider = provider,
            identity = AgentIdentity("explorer-1", "lead", AgentRole.EXPLORER, "Explorer"),
            limits = AgentLimits(
                maxIterations = 4,
                maxInputTokens = 10_000,
                maxOutputTokens = 100,
                maxOutputTokensPerTurn = 25,
            ),
        ).run("inspect then summarize")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(2, requests.size)
        assertTrue(requests[1].tools.isEmpty())
        assertTrue(
            requests[1].messages
                .filter { it.role == MessageRole.SYSTEM }
                .flatMap { it.blocks }
                .filterIsInstance<ContentBlock.Text>()
                .any { it.text.contains("staged report") },
        )
    }

    @Test
    fun `agent identity parent and bounded context are isolated in system prompts`() = runBlocking {
        val providerA = CapturingProvider()
        val providerB = CapturingProvider()
        val longContext = "A".repeat(AgentEngine.MAX_SYSTEM_CONTEXT_CHARS + 500)

        engine(
            provider = providerA,
            identity = AgentIdentity("explorer-1", "lead-a", AgentRole.EXPLORER, "Explorer A"),
            systemContext = "context-a-$longContext",
        ).run("inspect")
        engine(
            provider = providerB,
            identity = AgentIdentity("reviewer-1", "lead-b", AgentRole.REVIEWER, "Reviewer B"),
            systemContext = "context-b",
        ).run("review")

        val promptA = providerA.systemPrompt()
        val promptB = providerB.systemPrompt()
        assertTrue(promptA.contains("Agent id: explorer-1"))
        assertTrue(promptA.contains("Parent agent id: lead-a"))
        assertTrue(promptA.contains("Agent role: EXPLORER"))
        assertTrue(promptA.contains("[orchestration context truncated]"))
        assertFalse(promptA.contains("context-b"))
        assertTrue(promptB.contains("Agent id: reviewer-1"))
        assertTrue(promptB.contains("Parent agent id: lead-b"))
        assertTrue(promptB.contains("context-b"))
        assertFalse(promptB.contains("context-a"))
    }

    private fun engine(
        provider: ModelProvider,
        identity: AgentIdentity,
        ledger: SharedAgentBudgetLedger? = null,
        systemContext: String = "",
        events: MutableList<AgentEvent>? = null,
        limits: AgentLimits = AgentLimits(),
    ) = AgentEngine(
        project = project(),
        provider = provider,
        approvalGate = ApprovalGate { false },
        identity = identity,
        sharedLedger = ledger,
        systemContext = systemContext,
        events = AgentEventSink { event -> events?.add(event) },
        limits = limits,
    )

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> "/tmp/omnicode-shared-budget-test"
            "isDisposed" -> false
            "getName" -> "shared-budget-test"
            "toString" -> "SharedBudgetTestProject"
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

    private class CapturingProvider(
        private val usage: TokenUsage = TokenUsage(5, 2),
    ) : ModelProvider {
        override val id = "capturing"
        val calls = AtomicInteger()
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            calls.incrementAndGet()
            requests += request
            return ModelResponse(
                blocks = listOf(ContentBlock.Text("done")),
                usage = usage,
                stopReason = StopReason.COMPLETE,
            )
        }

        fun systemPrompt(): String = requests.single().messages
            .single { it.role == MessageRole.SYSTEM }
            .blocks
            .filterIsInstance<ContentBlock.Text>()
            .single()
            .text
    }

    private class ScriptedProvider(
        private val responses: List<ModelResponse>,
    ) : ModelProvider {
        override val id = "scripted"
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            val response = responses.getOrElse(requests.size) { error("Unexpected provider call") }
            requests += request
            return response
        }
    }
}
