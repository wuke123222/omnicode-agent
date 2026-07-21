package dev.omnicode.agent

import com.intellij.openapi.project.Project
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.provider.ModelProvider
import dev.omnicode.tool.ApprovalGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentEngineSharedBudgetTest {
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
    fun `provider cancellation releases the shared reservation`() = runBlocking {
        val enteredProvider = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<AgentRunResult>()
        val ledger = SharedAgentBudgetLedger(maxTotalTokens = 100_000)
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
                ).run("plan"),
            )
        }

        withTimeout(2_000) { enteredProvider.await() }
        assertEquals(1, ledger.snapshot().activeReservations)
        job.cancelAndJoin()
        val result = withTimeout(2_000) { delivered.await() }

        assertEquals(AgentRunStatus.CANCELLED, result.status)
        assertEquals(TokenUsage(), ledger.snapshot().usage)
        assertEquals(TokenUsage(), ledger.snapshot().reservedUsage)
        assertEquals(0, ledger.snapshot().activeReservations)
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
    ) = AgentEngine(
        project = project(),
        provider = provider,
        approvalGate = ApprovalGate { false },
        identity = identity,
        sharedLedger = ledger,
        systemContext = systemContext,
        events = AgentEventSink { event -> events?.add(event) },
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
}
