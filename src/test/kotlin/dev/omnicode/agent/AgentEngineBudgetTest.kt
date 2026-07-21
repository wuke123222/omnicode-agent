package dev.omnicode.agent

import com.intellij.openapi.project.Project
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.provider.ModelProvider
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.AgentTool
import dev.omnicode.tool.ToolExecutionContext
import dev.omnicode.tool.ToolExecutionResult
import dev.omnicode.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentEngineBudgetTest {
    @Test
    fun `oversized user message fails before provider call`() = runBlocking {
        val provider = RecordingProvider()
        val result = engine(provider).run("x".repeat(AgentEngine.MAX_USER_MESSAGE_CHARS + 1))

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, provider.calls.get())
        assertTrue(result.finalText.contains("maximum"))
    }

    @Test
    fun `estimated input budget is enforced before provider call`() = runBlocking {
        val provider = RecordingProvider()
        val result = engine(
            provider,
            AgentLimits(maxInputTokens = 1, maxContextChars = 10_000),
        ).run("small request")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, provider.calls.get())
        assertTrue(result.finalText.contains("before contacting the provider"))
        listOf("Achieved", "Evidence", "Remaining", "Risks").forEach { heading ->
            assertTrue(result.finalText.contains("\n$heading\n"), "Missing partial-result section: $heading")
        }
        assertTrue(result.finalText.contains("no extra model or tool call was made"))
    }

    @Test
    fun `tool schemas count toward the preflight input budget`() = runBlocking {
        val provider = RecordingProvider()
        val largeSchemaTool = object : AgentTool {
            override val name = "large_schema"
            override val description = "large test schema"
            override val dangerous = false
            override val inputSchema = com.google.gson.JsonObject().apply {
                addProperty("type", "object")
                addProperty("description", "x".repeat(30_000))
            }

            override suspend fun execute(
                arguments: com.google.gson.JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult = ToolExecutionResult("unused")
        }
        val result = engine(
            provider = provider,
            limits = AgentLimits(maxInputTokens = 5_000, maxContextChars = 10_000),
            tools = ToolRegistry(additionalTools = listOf(largeSchemaTool)),
        ).run("small request")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun `provider output-only usage falls back to estimated input usage`() = runBlocking {
        val provider = RecordingProvider(TokenUsage(inputTokens = 0, outputTokens = 7))

        val result = engine(provider).run("small request")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertTrue(result.usage.inputTokens > 0)
        assertEquals(7, result.usage.outputTokens)
    }

    @Test
    fun `tool call arguments count toward fallback output usage`() {
        val arguments = com.google.gson.JsonObject().apply {
            addProperty("role", "reviewer")
            addProperty("objective", "x".repeat(400))
        }

        val estimated = estimatedResponseOutputTokens(
            listOf(ContentBlock.ToolCall("call-1", "delegate_specialists", arguments)),
        )

        assertTrue(estimated >= 100)
    }

    @Test
    fun `priced run stops before a request that can exceed the hard cost limit`() = runBlocking {
        val provider = RecordingProvider()
        val budget = AgentCostBudget(
            maxUsd = BigDecimal("0.05"),
            estimator = { usage -> BigDecimal.valueOf(usage.outputTokens).multiply(BigDecimal("0.01")) },
        )

        val result = engine(
            provider = provider,
            limits = AgentLimits(maxOutputTokensPerTurn = 10),
            costBudget = budget,
        ).run("small request")

        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, provider.calls.get())
        assertTrue(result.finalText.contains("projected cost"))
    }

    @Test
    fun `priced run emits a warning before reaching its hard limit`() = runBlocking {
        val provider = RecordingProvider()
        val events = mutableListOf<AgentEvent>()
        val budget = AgentCostBudget(
            maxUsd = BigDecimal("1.00"),
            warningRatio = 0.05,
            estimator = { usage -> BigDecimal.valueOf(usage.outputTokens).multiply(BigDecimal("0.01")) },
        )

        val result = engine(
            provider = provider,
            limits = AgentLimits(maxOutputTokensPerTurn = 10),
            costBudget = budget,
            events = events,
        ).run("small request")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        val warning = events.filterIsInstance<AgentEvent.BudgetWarning>().single()
        assertTrue(warning.projected)
        assertEquals(BigDecimal("1.00"), warning.maxCostUsd)
    }

    private fun engine(
        provider: ModelProvider,
        limits: AgentLimits = AgentLimits(),
        tools: ToolRegistry = ToolRegistry(),
        costBudget: AgentCostBudget = AgentCostBudget(),
        events: MutableList<AgentEvent>? = null,
    ): AgentEngine = AgentEngine(
        project = project(),
        provider = provider,
        approvalGate = ApprovalGate { false },
        limits = limits,
        tools = tools,
        costBudget = costBudget,
        events = AgentEventSink { event -> events?.add(event) },
    )

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> "/tmp/omnicode-budget-test"
            "isDisposed" -> false
            "getName" -> "budget-test"
            "toString" -> "BudgetTestProject"
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

    private class RecordingProvider(
        private val usage: TokenUsage = TokenUsage(),
    ) : ModelProvider {
        override val id: String = "recording"
        val calls = AtomicInteger()

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            calls.incrementAndGet()
            return ModelResponse(
                blocks = listOf(ContentBlock.Text("ok")),
                usage = usage,
                stopReason = StopReason.COMPLETE,
            )
        }
    }
}
