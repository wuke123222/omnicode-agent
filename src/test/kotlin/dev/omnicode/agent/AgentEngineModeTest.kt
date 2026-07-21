package dev.omnicode.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentEngineModeTest {
    @Test
    fun `transient project context does not consume the user-authored message size limit`() = runBlocking {
        val provider = CapturingProvider()
        val result = engine(provider = provider).run(
            userMessage = ConversationMessage(
                MessageRole.USER,
                listOf(
                    ContentBlock.TransientProjectContext("r".repeat(70_000)),
                    ContentBlock.Text("inspect safely"),
                ),
            ),
            mode = AgentMode.PLAN,
        )

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `plan exposes only explicitly read only tools and records its mode`() = runBlocking {
        val provider = CapturingProvider()
        val events = mutableListOf<AgentEvent>()
        val readOnly = tool("inspect_dependency", ToolEffect.READ_ONLY)
        val mutating = tool("publish_change", ToolEffect.MUTATING)
        val unclassified = unclassifiedTool("third_party_action")
        val engine = engine(
            provider = provider,
            tools = ToolRegistry(additionalTools = listOf(readOnly, mutating, unclassified)),
            events = AgentEventSink(events::add),
        )

        val result = engine.run("prepare a plan", mode = AgentMode.PLAN)

        assertEquals(AgentMode.PLAN, result.mode)
        assertEquals(AgentMode.PLAN, events.filterIsInstance<AgentEvent.ModeSelected>().single().mode)
        val request = provider.requests.single()
        val names = request.tools.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("list_files", "read_file", "search_text", "inspect_dependency")))
        assertFalse("apply_patch" in names)
        assertFalse("apply_change" in names)
        assertFalse("run_command" in names)
        assertFalse("publish_change" in names)
        assertFalse("third_party_action" in names)
        val system = request.messages.filter { it.role == MessageRole.SYSTEM }.single()
        val prompt = assertIs<ContentBlock.Text>(system.blocks.single()).text
        assertTrue(prompt.contains("Active mode: PLAN"))
        assertTrue(prompt.contains("Never write or modify files"))
    }

    @Test
    fun `claude plan exposes IDE reads and its guarded command but not arbitrary command mutation or external tools`() = runBlocking {
        val provider = CapturingProvider()
        val command = tool("inspect_with_command", ToolEffect.COMMAND)
        val mutating = tool("rewrite_project", ToolEffect.MUTATING)
        val external = tool("call_remote_service", ToolEffect.EXTERNAL)
        val result = engine(
            provider = provider,
            tools = ToolRegistry(additionalTools = listOf(command, mutating, external)),
        ).run("explore first and propose a plan", mode = AgentMode.CLAUDE_PLAN)

        assertEquals(AgentMode.CLAUDE_PLAN, result.mode)
        val request = provider.requests.single()
        val names = request.tools.mapTo(mutableSetOf()) { it.name }
        assertTrue("run_command" in names)
        assertFalse(command.name in names)
        assertFalse("apply_patch" in names)
        assertFalse("apply_change" in names)
        assertFalse(mutating.name in names)
        assertFalse(external.name in names)
        val prompt = request.messages.filter { it.role == MessageRole.SYSTEM }.single()
            .blocks.filterIsInstance<ContentBlock.Text>().single().text
        assertTrue(prompt.contains("Active mode: CLAUDE_PLAN"))
        assertTrue(prompt.contains("Explore first"))
        assertTrue(prompt.contains("without editing source files"))
        assertTrue(prompt.contains("read-only exploration"))
        assertTrue(prompt.contains("Do not start the"))
    }

    @Test
    fun `claude plan guarded read only command runs without approval and is audited as not required`() = runBlocking {
        val approvals = AtomicInteger()
        val executions = AtomicInteger()
        val events = mutableListOf<AgentEvent>()
        val guardedCommand = tool("run_command", ToolEffect.COMMAND, dangerous = true) {
            executions.incrementAndGet()
            ToolExecutionResult("read-only inspection")
        }
        val engine = AgentEngine(
            project = project(),
            provider = ToolThenTextProvider(guardedCommand.name),
            approvalGate = ApprovalGate {
                approvals.incrementAndGet()
                true
            },
            tools = ToolRegistry(runCommandTool = guardedCommand),
            events = AgentEventSink(events::add),
        )

        val result = engine.run("inspect with a safe command", mode = AgentMode.CLAUDE_PLAN)

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(1, executions.get())
        assertEquals(0, approvals.get())
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals(ToolApprovalOutcome.NOT_REQUIRED, completed.approvalOutcome)
    }

    @Test
    fun `research exposes read and command tools with evidence disciplined instructions`() = runBlocking {
        val provider = CapturingProvider()
        val events = mutableListOf<AgentEvent>()
        val readOnly = tool("inspect_experiment", ToolEffect.READ_ONLY)
        val command = tool("run_experiment", ToolEffect.COMMAND)
        val mutating = tool("rewrite_experiment", ToolEffect.MUTATING)
        val external = tool("search_external_catalog", ToolEffect.EXTERNAL)
        val unclassified = unclassifiedTool("unknown_research_action")
        val engine = engine(
            provider = provider,
            tools = ToolRegistry(additionalTools = listOf(readOnly, command, mutating, external, unclassified)),
            events = AgentEventSink(events::add),
        )

        val result = engine.run("investigate the failure", mode = AgentMode.RESEARCH)

        assertEquals(AgentMode.RESEARCH, result.mode)
        assertEquals(AgentMode.RESEARCH, events.filterIsInstance<AgentEvent.ModeSelected>().single().mode)
        val request = provider.requests.single()
        val names = request.tools.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("list_files", "read_file", "search_text", "run_command", readOnly.name, command.name)))
        assertFalse("apply_patch" in names)
        assertFalse("apply_change" in names)
        assertFalse(mutating.name in names)
        assertFalse(external.name in names)
        assertFalse(unclassified.name in names)

        val system = request.messages.filter { it.role == MessageRole.SYSTEM }.single()
        val prompt = assertIs<ContentBlock.Text>(system.blocks.single()).text
        assertTrue(prompt.contains("Active mode: RESEARCH"))
        listOf(
            "Research question",
            "Hypotheses",
            "Method",
            "Evidence",
            "Results",
            "Limitations",
            "Reproduction checklist",
            "Citations",
        ).forEach { heading -> assertTrue(prompt.contains(heading), "Missing Research instruction: $heading") }
        assertTrue(prompt.contains("distinguish direct observations from inferences"))
        assertTrue(prompt.contains("Never fabricate literature"))
        assertTrue(prompt.contains("DOI identifiers"))
        assertTrue(prompt.contains("experimental runs, or results"))
    }

    @Test
    fun `research rejects hallucinated mutating and external tools without execution or approval`() = runBlocking {
        val executions = AtomicInteger()
        val approvals = AtomicInteger()
        val mutating = tool("mutate_research_data", ToolEffect.MUTATING, dangerous = true) {
            executions.incrementAndGet()
            ToolExecutionResult("changed")
        }
        val external = tool("publish_research_data", ToolEffect.EXTERNAL, dangerous = true) {
            executions.incrementAndGet()
            ToolExecutionResult("published")
        }
        val provider = ToolSequenceThenTextProvider(listOf(mutating.name, external.name))
        val events = mutableListOf<AgentEvent>()
        val engine = AgentEngine(
            project = project(),
            provider = provider,
            approvalGate = ApprovalGate {
                approvals.incrementAndGet()
                true
            },
            tools = ToolRegistry(additionalTools = listOf(mutating, external)),
            events = AgentEventSink(events::add),
        )

        val result = engine.run("research without side effects", mode = AgentMode.RESEARCH)

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(AgentMode.RESEARCH, result.mode)
        assertEquals(0, executions.get())
        assertEquals(0, approvals.get())
        assertFalse(provider.requests.first().tools.any { it.name == mutating.name || it.name == external.name })
        val observations = result.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(2, observations.size)
        assertTrue(observations.all { it.isError && it.content.startsWith("RESEARCH_MODE_BLOCKED") })
        assertTrue(events.filterIsInstance<AgentEvent.ToolCompleted>()
            .all { it.approvalOutcome == ToolApprovalOutcome.NOT_REQUESTED })
    }

    @Test
    fun `plan rejects a hallucinated mutating tool without execution or approval`() = runBlocking {
        val executions = AtomicInteger()
        val approvals = AtomicInteger()
        val events = mutableListOf<AgentEvent>()
        val mutating = tool("mutate_workspace", ToolEffect.MUTATING, dangerous = true) {
            executions.incrementAndGet()
            ToolExecutionResult("changed")
        }
        val provider = ToolThenTextProvider(mutating.name)
        val engine = AgentEngine(
            project = project(),
            provider = provider,
            approvalGate = ApprovalGate {
                approvals.incrementAndGet()
                true
            },
            tools = ToolRegistry(additionalTools = listOf(mutating)),
            events = AgentEventSink(events::add),
        )

        val result = engine.run("plan only", mode = AgentMode.PLAN)

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(AgentMode.PLAN, result.mode)
        assertEquals(0, executions.get())
        assertEquals(0, approvals.get())
        assertFalse(mutating.name in provider.requests.first().tools.map { it.name })
        val observation = result.messages.flatMap { it.blocks }
            .filterIsInstance<ContentBlock.ToolResult>()
            .single()
        assertTrue(observation.isError)
        assertTrue(observation.content.startsWith("PLAN_MODE_BLOCKED"))
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals(ToolApprovalOutcome.NOT_REQUESTED, completed.approvalOutcome)
    }

    @Test
    fun `agent mode can execute a mutating tool`() = runBlocking {
        val executions = AtomicInteger()
        val mutating = tool("mutate_workspace", ToolEffect.MUTATING) {
            executions.incrementAndGet()
            ToolExecutionResult("changed")
        }
        val result = engine(
            provider = ToolThenTextProvider(mutating.name),
            tools = ToolRegistry(additionalTools = listOf(mutating)),
        ).run("make the change", mode = AgentMode.AGENT)

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(AgentMode.AGENT, result.mode)
        assertEquals(1, executions.get())
    }

    @Test
    fun `plan can execute an explicitly read only tool`() = runBlocking {
        val executions = AtomicInteger()
        val readOnly = tool("inspect_workspace", ToolEffect.READ_ONLY) {
            executions.incrementAndGet()
            ToolExecutionResult("inspection")
        }

        val result = engine(
            provider = ToolThenTextProvider(readOnly.name),
            tools = ToolRegistry(additionalTools = listOf(readOnly)),
        ).run("inspect before planning", mode = AgentMode.PLAN)

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(1, executions.get())
        assertEquals("inspection", result.messages.flatMap { it.blocks }
            .filterIsInstance<ContentBlock.ToolResult>().single().content)
    }

    @Test
    fun `plan token preflight excludes unavailable mutating schemas`() = runBlocking {
        val hugeMutatingTool = object : AgentTool {
            override val name: String = "huge_mutation"
            override val description: String = "Unavailable in Plan mode"
            override val inputSchema: JsonObject = JsonObject().apply {
                addProperty("type", "object")
                addProperty("description", "x".repeat(40_000))
            }
            override val dangerous: Boolean = true
            override val effect: ToolEffect = ToolEffect.MUTATING

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult = error("must not execute")
        }
        val provider = CapturingProvider()
        val engine = AgentEngine(
            project = project(),
            provider = provider,
            approvalGate = ApprovalGate { true },
            tools = ToolRegistry(additionalTools = listOf(hugeMutatingTool)),
            limits = AgentLimits(maxInputTokens = 5_000, maxContextChars = 10_000),
        )

        val result = engine.run("small plan", mode = AgentMode.PLAN)

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(1, provider.requests.size)
        assertFalse(provider.requests.single().tools.any { it.name == hugeMutatingTool.name })
    }

    @Test
    fun `unclassified third party tool is blocked by default in plan`() = runBlocking {
        val executions = AtomicInteger()
        val unclassified = object : AgentTool {
            override val name: String = "unclassified_action"
            override val description: String = "Unknown side effects"
            override val inputSchema: JsonObject = JsonObject().apply { addProperty("type", "object") }
            override val dangerous: Boolean = false

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions.incrementAndGet()
                return ToolExecutionResult("unexpected")
            }
        }
        val result = engine(
            provider = ToolThenTextProvider(unclassified.name),
            tools = ToolRegistry(additionalTools = listOf(unclassified)),
        ).run("plan", mode = AgentMode.PLAN)

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(0, executions.get())
        assertTrue(result.messages.flatMap { it.blocks }
            .filterIsInstance<ContentBlock.ToolResult>().single().content.startsWith("PLAN_MODE_BLOCKED"))
    }

    @Test
    fun `switching to plan replaces a restored agent system prompt but keeps chat history`() = runBlocking {
        val provider = CapturingProvider()
        val prior = listOf(
            ConversationMessage(MessageRole.SYSTEM, "Active mode: AGENT\nYou may change everything."),
            ConversationMessage(MessageRole.USER, "First inspect the service"),
            ConversationMessage(MessageRole.ASSISTANT, "I inspected it."),
        )

        val result = engine(provider).run("now produce a plan", prior, AgentMode.PLAN)

        val request = provider.requests.single()
        val systems = request.messages.filter { it.role == MessageRole.SYSTEM }
        assertEquals(1, systems.size)
        val activePrompt = assertIs<ContentBlock.Text>(systems.single().blocks.single()).text
        assertTrue(activePrompt.contains("Active mode: PLAN"))
        assertFalse(activePrompt.contains("You may change everything"))
        assertTrue(request.messages.any { message ->
            message.blocks.filterIsInstance<ContentBlock.Text>().any { it.text == "First inspect the service" }
        })
        assertEquals(AgentMode.PLAN, result.mode)
    }

    @Test
    fun `cancelled plan run cannot be reported as agent mode`() = runBlocking {
        val enteredProvider = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override val id: String = "blocking"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                enteredProvider.complete(Unit)
                delay(Long.MAX_VALUE)
                error("unreachable")
            }
        }
        val delivered = CompletableDeferred<AgentRunResult>()
        val job = launch {
            delivered.complete(engine(provider).run("plan", mode = AgentMode.PLAN))
        }

        withTimeout(2_000) { enteredProvider.await() }
        job.cancelAndJoin()
        val result = withTimeout(2_000) { delivered.await() }

        assertEquals(AgentRunStatus.CANCELLED, result.status)
        assertEquals(AgentMode.PLAN, result.mode)
    }

    private fun engine(
        provider: ModelProvider,
        tools: ToolRegistry = ToolRegistry(),
        events: AgentEventSink = AgentEventSink {},
    ): AgentEngine = AgentEngine(
        project = project(),
        provider = provider,
        approvalGate = ApprovalGate { true },
        tools = tools,
        events = events,
    )

    private fun tool(
        toolName: String,
        toolEffect: ToolEffect,
        dangerous: Boolean = false,
        action: suspend () -> ToolExecutionResult = { ToolExecutionResult("read") },
    ): AgentTool = object : AgentTool {
        override val name: String = toolName
        override val description: String = "Test tool $toolName"
        override val inputSchema: JsonObject = JsonObject().apply { addProperty("type", "object") }
        override val dangerous: Boolean = dangerous
        override val effect: ToolEffect = toolEffect

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolExecutionResult = action()
    }

    private fun unclassifiedTool(toolName: String): AgentTool = object : AgentTool {
        override val name: String = toolName
        override val description: String = "Unclassified third-party tool"
        override val inputSchema: JsonObject = JsonObject().apply { addProperty("type", "object") }
        override val dangerous: Boolean = false

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolExecutionResult = ToolExecutionResult("must not run")
    }

    private class CapturingProvider : ModelProvider {
        override val id: String = "capturing"
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            requests += request
            return ModelResponse(
                blocks = listOf(ContentBlock.Text("done")),
                stopReason = StopReason.COMPLETE,
            )
        }
    }

    private class ToolThenTextProvider(private val toolName: String) : ModelProvider {
        override val id: String = "tool-then-text"
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            requests += request
            return if (requests.size == 1) {
                ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("mode-call", toolName, JsonObject())),
                    stopReason = StopReason.TOOL_USE,
                )
            } else {
                ModelResponse(
                    blocks = listOf(ContentBlock.Text("done")),
                    stopReason = StopReason.COMPLETE,
                )
            }
        }
    }

    private class ToolSequenceThenTextProvider(private val toolNames: List<String>) : ModelProvider {
        override val id: String = "tool-sequence-then-text"
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            requests += request
            val nextTool = toolNames.getOrNull(requests.lastIndex)
            return if (nextTool != null) {
                ModelResponse(
                    blocks = listOf(ContentBlock.ToolCall("mode-call-${requests.size}", nextTool, JsonObject())),
                    stopReason = StopReason.TOOL_USE,
                )
            } else {
                ModelResponse(
                    blocks = listOf(ContentBlock.Text("done")),
                    stopReason = StopReason.COMPLETE,
                )
            }
        }
    }

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> "/tmp/omnicode-mode-test"
            "isDisposed" -> false
            "getName" -> "mode-test"
            "toString" -> "ModeTestProject"
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
