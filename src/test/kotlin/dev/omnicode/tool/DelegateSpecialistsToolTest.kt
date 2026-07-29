package dev.omnicode.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentEventSink
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRole
import dev.omnicode.agent.AgentRunResult
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DelegateSpecialistsToolTest {
    @Test
    fun `runs two isolated specialists concurrently and preserves input order`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val tool = tool(events) { request ->
            val now = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, now) }
            delay(if (request.role == AgentRole.EXPLORER) 40 else 10)
            active.decrementAndGet()
            completed("${request.role.name} finding", TokenUsage(12, 3))
        }

        val result = tool.execute(
            tasks(
                "explorer" to "Trace the request path",
                "reviewer" to "Check concurrency risks",
            ),
            context(),
        )

        assertFalse(result.isError)
        assertTrue(maxActive.get() >= 2)
        assertTrue(result.content.indexOf("Explorer") < result.content.indexOf("Reviewer"))
        assertEquals(2, events.filterIsInstance<AgentEvent.DelegatedAgentStarted>().size)
        assertEquals(2, events.filterIsInstance<AgentEvent.DelegatedAgentCompleted>().size)
        assertEquals(2, tool.completedSummaries().size)
    }

    @Test
    fun `complex batch can run four specialists concurrently`() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val tool = tool(mutableListOf()) {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, now) }
            delay(40)
            active.decrementAndGet()
            completed("finding")
        }

        val result = tool.execute(
            tasks(
                "explorer" to "Trace API flow",
                "explorer" to "Trace persistence flow",
                "planner" to "Map implementation order",
                "reviewer" to "Review risks",
            ),
            context(),
        )

        assertFalse(result.isError)
        assertEquals(4, maxActive.get())
        assertEquals(4, tool.completedSummaries().size)
    }

    @Test
    fun `budget preflight shrinks a batch instead of rejecting every specialist`() = runBlocking {
        val attempts = mutableListOf<Int>()
        val startedObjectives = mutableListOf<String>()
        val tool = DelegateSpecialistsTool(
            workflowId = "workflow-1",
            parentAgentId = "lead",
            originalGoal = "goal",
            runner = SpecialistTaskRunner { request ->
                startedObjectives += request.objective
                completed("${request.objective} finding")
            },
            events = AgentEventSink {},
            budgetPreflight = { count ->
                attempts += count
                if (count > 2) "Only two specialist reservations fit." else null
            },
            maxParallel = 1,
        )

        val result = tool.execute(
            tasks(
                "explorer" to "First",
                "reviewer" to "Second",
                "planner" to "Third",
                "explorer" to "Fourth",
            ),
            context(),
        )

        assertFalse(result.isError)
        assertEquals(listOf(4, 3, 2), attempts)
        assertEquals(listOf("First", "Second"), startedObjectives)
        assertTrue(result.content.contains("DELEGATION_PARTIAL: Started 2 of 4"))
        assertTrue(result.content.contains("Planner: Third"))
        assertTrue(result.content.contains("Explorer: Fourth"))
    }

    @Test
    fun `remaining workflow agent capacity admits a safe prefix`() = runBlocking {
        val started = mutableListOf<String>()
        val tool = DelegateSpecialistsTool(
            workflowId = "workflow-1",
            parentAgentId = "lead",
            originalGoal = "goal",
            runner = SpecialistTaskRunner { request ->
                started += request.objective
                completed("ok")
            },
            events = AgentEventSink {},
            maxAgents = 3,
            maxParallel = 1,
        )

        val result = tool.execute(
            tasks(
                "explorer" to "First",
                "reviewer" to "Second",
                "planner" to "Third",
                "explorer" to "Deferred",
            ),
            context(),
        )

        assertFalse(result.isError)
        assertEquals(listOf("First", "Second", "Third"), started)
        assertTrue(result.content.contains("DELEGATION_PARTIAL: Started 3 of 4"))
        assertTrue(result.content.contains("Explorer: Deferred"))
    }

    @Test
    fun `partial failure remains usable but all failed is an error`() = runBlocking {
        val partial = tool(mutableListOf()) { request ->
            if (request.role == AgentRole.REVIEWER) failed("review failed") else completed("evidence")
        }.execute(tasks("explorer" to "Inspect", "reviewer" to "Review"), context())
        assertFalse(partial.isError)
        assertTrue(partial.content.contains("FAILED"))

        val failed = tool(mutableListOf()) { failed("unavailable") }
            .execute(tasks("planner" to "Plan"), context())
        assertTrue(failed.isError)
    }

    @Test
    fun `round and total agent limits fail closed`() = runBlocking {
        val tool = DelegateSpecialistsTool(
            workflowId = "workflow-1",
            parentAgentId = "lead",
            originalGoal = "goal",
            runner = SpecialistTaskRunner { completed("ok") },
            events = AgentEventSink {},
            maxRounds = 1,
            maxAgents = 2,
            maxParallel = 1,
        )

        assertFalse(tool.execute(tasks("explorer" to "Inspect"), context()).isError)
        val rejected = tool.execute(tasks("reviewer" to "Review"), context())

        assertTrue(rejected.isError)
        assertTrue(rejected.content.startsWith("DELEGATION_LIMIT"))
    }

    @Test
    fun `budget preflight returns a staged handoff without starting empty specialists`() = runBlocking {
        var runs = 0
        val tool = DelegateSpecialistsTool(
            workflowId = "workflow-1",
            parentAgentId = "lead",
            originalGoal = "goal",
            runner = SpecialistTaskRunner {
                runs++
                completed("unreachable")
            },
            events = AgentEventSink {},
            budgetPreflight = { "Only 300 output tokens remain." },
        )

        val result = tool.execute(tasks("explorer" to "Inspect", "reviewer" to "Review"), context())

        assertFalse(result.isError)
        assertEquals(0, runs)
        assertTrue(result.content.contains("DELEGATION_BUDGET_PRECHECK"))
        assertTrue(result.content.contains("staged result"))
    }

    @Test
    fun `budget exhausted specialist with partial findings remains usable`() = runBlocking {
        val tool = tool(mutableListOf()) {
            AgentRunResult(
                status = AgentRunStatus.BUDGET_EXHAUSTED,
                finalText = "Verified src/Foo.kt before the execution boundary.",
                messages = emptyList(),
                usage = TokenUsage(100, 20),
                mode = AgentMode.PLAN,
            )
        }

        val result = tool.execute(tasks("explorer" to "Inspect"), context())

        assertFalse(result.isError)
        assertTrue(result.content.contains("Verified src/Foo.kt"))

        val emptyBoundary = tool(mutableListOf()) {
            AgentRunResult(
                status = AgentRunStatus.BUDGET_EXHAUSTED,
                finalText = "Partial result\n\nAchieved\n- No task outcome was verified before the run stopped.",
                messages = emptyList(),
                usage = TokenUsage(),
                mode = AgentMode.PLAN,
            )
        }.execute(tasks("reviewer" to "Review"), context())
        assertTrue(emptyBoundary.isError)
    }

    @Test
    fun `machine boundary evidence stays available to the lead but is hidden from the UI event`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val rawBoundary = """
            Partial result

            Achieved
            - Captured 1 successful tool observation(s).

            Evidence
            - list_files: - .codemoss/ - advertising_console/VERY_LONG_INTERNAL_FILE.md …[truncated]

            Remaining
            - Run stopped at the specialist execution boundary.
        """.trimIndent()
        val tool = tool(events) {
            AgentRunResult(
                status = AgentRunStatus.BUDGET_EXHAUSTED,
                finalText = rawBoundary,
                messages = listOf(
                    ConversationMessage(
                        MessageRole.ASSISTANT,
                        listOf(
                            ContentBlock.ToolCall(
                                "list-1",
                                "list_files",
                                JsonObject().apply {
                                    addProperty("path", ".")
                                    addProperty("max_depth", 3)
                                },
                            ),
                        ),
                    ),
                    ConversationMessage(
                        MessageRole.USER,
                        listOf(ContentBlock.ToolResult("list-1", ".codemoss\nadvertising_console", false)),
                    ),
                ),
                usage = TokenUsage(100, 20),
                mode = AgentMode.PLAN,
            )
        }

        val result = tool.execute(tasks("explorer" to "Inspect the repository"), context())

        assertFalse(result.isError)
        assertTrue(result.content.contains("representative paths"))
        assertTrue(result.content.contains("advertising_console"))
        assertFalse(result.content.contains("Partial result"))
        assertTrue(tool.completedSummaries().single().summary.contains("STAGED_SPECIALIST_RESULT"))
        val completed = events.filterIsInstance<AgentEvent.DelegatedAgentCompleted>().single()
        assertTrue(completed.summary.contains("1 条工具证据"))
        assertFalse(completed.summary.contains("Partial result"))
        assertFalse(completed.summary.contains("Evidence"))
        assertFalse(completed.summary.contains("list_files"))
        assertFalse(completed.summary.contains(".codemoss"))
    }

    @Test
    fun `partial model analysis without a tool result is not reported as no conclusion`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val tool = tool(events) {
            AgentRunResult(
                status = AgentRunStatus.BUDGET_EXHAUSTED,
                finalText = "Partial result\n\nAchieved\n- Captured the latest model response as unverified partial progress.",
                messages = listOf(ConversationMessage(MessageRole.ASSISTANT, "A staged concurrency analysis.")),
                usage = TokenUsage(50, 10),
                mode = AgentMode.PLAN,
            )
        }

        val result = tool.execute(tasks("reviewer" to "Review concurrency"), context())

        assertFalse(result.isError)
        assertTrue(result.content.contains("A staged concurrency analysis."))
        val completed = events.filterIsInstance<AgentEvent.DelegatedAgentCompleted>().single()
        assertTrue(completed.summary.contains("阶段性分析"))
        assertFalse(completed.summary.contains("未形成可用结论"))
    }

    @Test
    fun `cancellation is never converted into a specialist failure`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val tool = DelegateSpecialistsTool(
            workflowId = "workflow-1",
            parentAgentId = "lead",
            originalGoal = "goal",
            runner = SpecialistTaskRunner { throw CancellationException("stop") },
            events = AgentEventSink(events::add),
            usageForAgent = { TokenUsage(17, 4) },
        )

        assertFailsWith<CancellationException> {
            tool.execute(tasks("explorer" to "Inspect"), context())
        }
        val completed = events.filterIsInstance<AgentEvent.DelegatedAgentCompleted>().single()
        assertEquals(AgentRunStatus.CANCELLED, completed.status)
        assertEquals(TokenUsage(17, 4), completed.usage)
        assertEquals(AgentRunStatus.CANCELLED, tool.completedSummaries().single().status)
    }

    @Test
    fun `exception preserves usage already recorded by the shared ledger`() = runBlocking {
        val tool = DelegateSpecialistsTool(
            workflowId = "workflow-1",
            parentAgentId = "lead",
            originalGoal = "goal",
            runner = SpecialistTaskRunner { throw IllegalStateException("provider failed") },
            events = AgentEventSink {},
            usageForAgent = { TokenUsage(21, 5) },
        )

        val result = tool.execute(tasks("explorer" to "Inspect"), context())

        assertTrue(result.isError)
        assertEquals(TokenUsage(21, 5), tool.completedSummaries().single().usage)
    }

    @Test
    fun `unknown roles and fields are rejected`() = runBlocking {
        val tool = tool(mutableListOf()) { completed("unused") }
        val unknownRole = tasks("implementer" to "Write files")
        assertFailsWith<IllegalArgumentException> { tool.execute(unknownRole, context()) }

        val unknownField = tasks("explorer" to "Inspect").also { arguments ->
            arguments.getAsJsonArray("tasks")[0].asJsonObject.addProperty("command", "rm")
        }
        assertFailsWith<IllegalArgumentException> { tool.execute(unknownField, context()) }
        assertTrue(tool.completedSummaries().isEmpty())
    }

    private fun tool(
        events: MutableList<AgentEvent>,
        runner: suspend (SpecialistTaskRequest) -> AgentRunResult,
    ): DelegateSpecialistsTool = DelegateSpecialistsTool(
        workflowId = "workflow-1",
        parentAgentId = "lead",
        originalGoal = "Fix the project safely",
        runner = SpecialistTaskRunner(runner),
        events = AgentEventSink(events::add),
    )

    private fun tasks(vararg tasks: Pair<String, String>): JsonObject = JsonObject().apply {
        add("tasks", JsonArray().apply {
            tasks.forEach { (role, objective) ->
                add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("objective", objective)
                })
            }
        })
    }

    private fun context(): ToolExecutionContext = ToolExecutionContext(
        project = project(),
        approvalGate = ApprovalGate { true },
        mode = AgentMode.AGENT,
    )

    private fun completed(text: String, usage: TokenUsage = TokenUsage()): AgentRunResult = AgentRunResult(
        status = AgentRunStatus.COMPLETED,
        finalText = text,
        messages = emptyList<ConversationMessage>(),
        usage = usage,
        mode = AgentMode.PLAN,
    )

    private fun failed(text: String): AgentRunResult = AgentRunResult(
        status = AgentRunStatus.FAILED,
        finalText = text,
        messages = emptyList(),
        usage = TokenUsage(),
        mode = AgentMode.PLAN,
    )

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "getBasePath" -> "/tmp/project"
            "toString" -> "test-project"
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
