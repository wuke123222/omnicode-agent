package dev.omnicode.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
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
import dev.omnicode.tool.ToolEffect
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
        assertTrue(result.finalText.contains("1 agent iterations for this execution attempt"))
        assertTrue(result.finalText.contains("no extra model or tool call was made"))
    }

    @Test
    fun `continuous workflow ignores cumulative iteration tool and wall time limits`() = runBlocking {
        var providerCalls = 0
        var executions = 0
        val statuses = mutableListOf<String>()
        val tool = object : AgentTool {
            override val name = "continuous_inspection"
            override val description = "Produces distinct observations across a continuous workflow"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                delay(75)
                executions++
                return ToolExecutionResult("observation ${arguments.get("step").asInt}")
            }
        }
        val provider = object : ModelProvider {
            override val id = "continuous-workflow"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return if (providerCalls <= 2) {
                    ModelResponse(
                        blocks = listOf(
                            ContentBlock.ToolCall(
                                "continuous-$providerCalls",
                                tool.name,
                                JsonObject().apply { addProperty("step", providerCalls) },
                            ),
                        ),
                        stopReason = StopReason.TOOL_USE,
                    )
                } else {
                    ModelResponse(
                        blocks = listOf(ContentBlock.Text("continuous workflow completed")),
                        stopReason = StopReason.COMPLETE,
                    )
                }
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(
                maxIterations = 1,
                maxToolCalls = 1,
                maxWallTime = Duration.ofMillis(25),
                maxToolTime = Duration.ofSeconds(1),
                enforceWorkflowLimits = false,
            ),
            events = AgentEventSink { event ->
                if (event is AgentEvent.Status) statuses += event.message
            },
        )

        val result = withTimeout(2_000) { engine.run("continue until the model finishes") }

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals("continuous workflow completed", result.finalText)
        assertEquals(3, providerCalls)
        assertEquals(2, executions)
        val thinkingStatuses = statuses.filter { it.startsWith("Thinking") }
        assertEquals(3, thinkingStatuses.size)
        assertTrue(thinkingStatuses.none { "/" in it })
        assertTrue(statuses.none { it.contains("maximum", ignoreCase = true) })
    }

    @Test
    fun `continuous workflow stops alternating identical read observations without progress`() = runBlocking {
        var providerCalls = 0
        val tool = object : AgentTool {
            override val name = "alternating_probe"
            override val description = "Returns a stable observation for each slot"
            override val dangerous = false
            override val effect = ToolEffect.READ_ONLY
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult = ToolExecutionResult("stable-${arguments.get("slot").asString}")
        }
        val provider = object : ModelProvider {
            override val id = "alternating-loop"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                val slot = if (providerCalls % 2 == 0) "b" else "a"
                return ModelResponse(
                    blocks = listOf(
                        ContentBlock.ToolCall(
                            "alternating-$providerCalls",
                            tool.name,
                            JsonObject().apply { addProperty("slot", slot) },
                        ),
                    ),
                    stopReason = StopReason.TOOL_USE,
                )
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(
                maxRepeatedAction = 2,
                enforceWorkflowLimits = false,
            ),
        )

        val result = withTimeout(2_000) { engine.run("detect an alternating read loop") }

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(6, providerCalls)
        assertTrue(result.finalText.contains("same read-only observation repeatedly"))
    }

    @Test
    fun `finite resumed workflow receives a fresh attempt allowance`() = runBlocking {
        var providerCalls = 0
        var executions = 0
        val tool = object : AgentTool {
            override val name = "resume_probe"
            override val description = "Verifies a resumed finite attempt"
            override val dangerous = false
            override val effect = ToolEffect.READ_ONLY
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                executions++
                return ToolExecutionResult("verified")
            }
        }
        val provider = object : ModelProvider {
            override val id = "finite-resume"

            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return if (providerCalls == 1) {
                    ModelResponse(
                        blocks = listOf(ContentBlock.ToolCall("resume-call", tool.name, JsonObject())),
                        stopReason = StopReason.TOOL_USE,
                    )
                } else {
                    ModelResponse(
                        blocks = listOf(ContentBlock.Text("resumed attempt completed")),
                        stopReason = StopReason.COMPLETE,
                    )
                }
            }
        }
        val engine = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 2, maxToolCalls = 1),
            initialIteration = 12,
            initialToolCalls = 34,
        )

        val result = engine.run("continue the finite attempt")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(2, providerCalls)
        assertEquals(1, executions)
    }

    @Test
    fun `boundary evidence summarizes repository inventories and nested delegation`() {
        val listArguments = JsonObject().apply {
            addProperty("path", ".")
            addProperty("max_depth", 3)
            addProperty("limit", 160)
        }
        val inventory = (0 until 160).joinToString("\n") { "advertising_console/file-$it.md" } +
            "\n[truncated at 160 entries; narrow path or use search_text]"

        val listSummary = boundaryEvidenceDetail(
            ContentBlock.ToolCall("list-1", "list_files", listArguments),
            inventory,
            1_200,
        )
        assertTrue(listSummary.contains("160 model-visible entries"))
        assertTrue(listSummary.contains("listing was truncated"))
        assertFalse(listSummary.contains("advertising_console/file-"))
        val failedListSummary = boundaryEvidenceDetail(
            ContentBlock.ToolCall("list-failed", "list_files", listArguments),
            "Path does not exist: missing",
            600,
            failed = true,
        )
        assertEquals("Path does not exist: missing", failedListSummary)

        val delegation = """
            DELEGATION_RESULT batch-1

            [1] Explorer · BUDGET_EXHAUSTED
            Evidence
            - list_files: $inventory

            [2] Reviewer · COMPLETED
            Verified src/Foo.kt:12
            No checkpoint race was found under the project lock.
        """.trimIndent()
        val delegationSummary = boundaryEvidenceDetail(
            ContentBlock.ToolCall("delegate-1", "delegate_specialists", JsonObject()),
            delegation,
            1_200,
        )
        assertTrue(delegationSummary.contains("2 specialist outcome(s)"))
        assertFalse(delegationSummary.contains("advertising_console/file-"))
        assertTrue(delegationSummary.contains("src/Foo.kt:12"))
        assertTrue(delegationSummary.contains("No checkpoint race was found"))

        val singleLineRootInventory = (0 until 20).joinToString(" ") { "file-$it.md" }
        assertTrue(boundaryModelProgressDetail(singleLineRootInventory, 1_200).contains("path dump was omitted"))
    }

    @Test
    fun `repository inventory detection supports common paths without deleting prose`() {
        val inventories = listOf(
            (0 until 20).joinToString("\n") { "src\\main\\generated\\File$it.kt" },
            (0 until 20).joinToString("\n") { "`论文 第一阶段 原始数据/数据 $it.csv`" },
            (0 until 20).joinToString(", ") { "file-$it.md" },
        )
        inventories.forEach { inventory ->
            val detail = boundaryModelProgressDetail(inventory, 1_200)
            assertTrue(detail.contains("path dump was omitted"), inventory.take(160))
        }

        val proseWithReferences = buildString {
            append("The checkpoint analysis remains valid after reviewing the relevant files. ")
            append((0 until 12).joinToString(" ") { "src/review/File$it.kt:${it + 1}" })
            append(" Lock ordering is stable, rollback remains safe, and no race was found.")
        }
        val ordinaryTechnicalText = listOf(
            (0 until 8).joinToString("\n") { "https://example.com/research/$it" },
            listOf("HTTP/2", "gRPC/1.0", "v2.0.0", "1.2.3", "127.0.0.1").joinToString("\n"),
            proseWithReferences,
            "中文分析中引用 论文 实验/结果.csv 仅用于比较，统计显著性仍需复核。",
        )
        ordinaryTechnicalText.forEach { prose ->
            val detail = boundaryModelProgressDetail(prose, 1_200)
            assertFalse(detail.contains("path dump was omitted"), prose.take(160))
        }
        assertTrue(boundaryModelProgressDetail(proseWithReferences, 1_200).contains("no race was found"))
    }

    @Test
    fun `delegation boundary retains one conclusion from each specialist`() {
        val delegation = """
            DELEGATION_RESULT batch-4

            [1] Explorer · COMPLETED
            First specialist found bounded context selection ${"detail ".repeat(80)}

            [2] Reviewer · COMPLETED
            truncated JSON handling is correct and no evidence was lost.

            [3] Tester · BUDGET_EXHAUSTED
            Third specialist preserved a reproducible regression case.

            [4] Researcher · COMPLETED
            Fourth specialist confirmed citations remain visible.
        """.trimIndent()

        val detail = boundaryEvidenceDetail(
            ContentBlock.ToolCall("delegate-4", "delegate_specialists", JsonObject()),
            delegation,
            1_200,
        )

        assertTrue(detail.contains("1 Explorer:"))
        assertTrue(detail.contains("2 Reviewer:"))
        assertTrue(detail.contains("3 Tester:"))
        assertTrue(detail.contains("4 Researcher:"))
        assertTrue(detail.contains("First specialist found"))
        assertTrue(detail.contains("truncated JSON handling is correct"))
        assertTrue(detail.contains("Third specialist preserved"))
        assertTrue(detail.contains("Fourth specialist confirmed"))
        assertTrue(detail.length <= 1_200)
    }

    @Test
    fun `terminal detail bounds normalization work for very large prose`() {
        val prose = "Normal technical explanation remains visible. " + "word ".repeat(500_000)

        val detail = boundaryModelProgressDetail(prose, 1_200)

        assertTrue(detail.startsWith("Normal technical explanation remains visible."))
        assertTrue(detail.endsWith("…[truncated]"))
        assertTrue(detail.length <= 1_200)
    }

    @Test
    fun `terminal model progress omits an unsynthesized repository inventory`() = runBlocking {
        val inventory = (0 until 20).joinToString("\n") { "- src/generated/file-$it.kt" }
        val tool = object : AgentTool {
            override val name = "bounded_inspection"
            override val description = "Produces one observation"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }

            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext) =
                ToolExecutionResult("inspection complete")
        }
        val provider = object : ModelProvider {
            override val id = "inventory-boundary"
            override suspend fun complete(request: ModelRequest, onTextDelta: suspend (String) -> Unit) = ModelResponse(
                blocks = listOf(ContentBlock.ToolCall("inspect-1", tool.name, JsonObject())),
                stopReason = StopReason.TOOL_USE,
            )
        }
        val result = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 1),
        ).run(
            userMessage = "inspect once",
            priorMessages = listOf(ConversationMessage(MessageRole.ASSISTANT, inventory)),
        )

        assertTrue(result.finalText.contains("unsynthesized repository inventory"))
        assertFalse(result.finalText.contains("src/generated/file-19.kt"))
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
            limits = AgentLimits(maxIterations = 2, enforceWorkflowLimits = false),
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
    fun `trusted orchestration timeout can exceed generic tool timeout but not the run limit`() = runBlocking {
        val tool = object : AgentTool {
            override val name = "coordinated_tool"
            override val description = "Coordinates bounded child work"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }
            override val executionTimeout = Duration.ofSeconds(1)

            override suspend fun execute(
                arguments: JsonObject,
                context: ToolExecutionContext,
            ): ToolExecutionResult {
                delay(100)
                return ToolExecutionResult("coordinated")
            }
        }
        val events = mutableListOf<AgentEvent>()
        val engine = AgentEngine(
            project = fakeProject(),
            provider = TwoTurnProvider(tool.name),
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(
                maxIterations = 3,
                maxWallTime = Duration.ofSeconds(2),
                maxToolTime = Duration.ofMillis(25),
            ),
            events = AgentEventSink { event -> events += event },
        )

        val result = engine.run("test")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertFalse(completed.isError)
        assertEquals("coordinated", completed.result)
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
        assertIs<ProviderOutputLimitReachedException>(result.error)
        assertTrue(result.finalText.contains("output limit"))
    }

    @Test
    fun `continuous workflow automatically continues after a provider output segment limit`() = runBlocking {
        var providerCalls = 0
        val provider = object : ModelProvider {
            override val id = "segmented-output"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return if (providerCalls == 1) {
                    ModelResponse(listOf(ContentBlock.Text("first segment")), stopReason = StopReason.LENGTH)
                } else {
                    ModelResponse(listOf(ContentBlock.Text("finished")), stopReason = StopReason.COMPLETE)
                }
            }
        }
        val result = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            limits = AgentLimits(enforceWorkflowLimits = false),
        ).run("finish across segments")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(2, providerCalls)
        assertEquals("finished", result.finalText)
    }

    @Test
    fun `continuous workflow resumes after a retryable stream interruption with partial text`() = runBlocking {
        var providerCalls = 0
        val provider = object : ModelProvider {
            override val id = "interrupted-stream"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                if (providerCalls == 1) {
                    onTextDelta("saved partial segment")
                    throw ProviderException("connection reset", networkFailure = true)
                }
                assertTrue(request.messages.any { message ->
                    message.blocks.filterIsInstance<ContentBlock.Text>().any {
                        it.text.contains("saved partial segment")
                    }
                })
                return ModelResponse(listOf(ContentBlock.Text("continued safely")), stopReason = StopReason.COMPLETE)
            }
        }
        val result = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            limits = AgentLimits(enforceWorkflowLimits = false),
        ).run("survive a broken stream")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(2, providerCalls)
        assertEquals("continued safely", result.finalText)
    }

    @Test
    fun `continuous output continuation never executes a truncated tool call`() = runBlocking {
        var providerCalls = 0
        var executions = 0
        val tool = object : AgentTool {
            override val name = "partial_continuous_tool"
            override val description = "Must be reissued after truncation"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }
            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
                executions++
                return ToolExecutionResult("unexpected")
            }
        }
        val provider = object : ModelProvider {
            override val id = "truncated-tool-continuation"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return if (providerCalls == 1) {
                    ModelResponse(
                        listOf(ContentBlock.ToolCall("partial-continuous", tool.name, JsonObject())),
                        stopReason = StopReason.LENGTH,
                    )
                } else {
                    assertTrue(
                        request.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>()
                            .any { it.content.contains("NOT_EXECUTED_INCOMPLETE_RESPONSE") },
                    )
                    ModelResponse(listOf(ContentBlock.Text("recovered")), stopReason = StopReason.COMPLETE)
                }
            }
        }
        val result = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(enforceWorkflowLimits = false),
        ).run("recover safely")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(2, providerCalls)
        assertEquals(0, executions)
    }

    @Test
    fun `changing read only observations are not blocked as a repeated action`() = runBlocking {
        var providerCalls = 0
        var executions = 0
        val tool = object : AgentTool {
            override val name = "changing_read"
            override val description = "Returns a changing read-only observation"
            override val dangerous = false
            override val effect = ToolEffect.READ_ONLY
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }
            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult("version-${++executions}")
        }
        val provider = object : ModelProvider {
            override val id = "changing-read-provider"
            override suspend fun complete(
                request: ModelRequest,
                onTextDelta: suspend (String) -> Unit,
            ): ModelResponse {
                providerCalls++
                return if (providerCalls <= 3) {
                    ModelResponse(
                        listOf(ContentBlock.ToolCall("read-$providerCalls", tool.name, JsonObject())),
                        stopReason = StopReason.TOOL_USE,
                    )
                } else {
                    ModelResponse(listOf(ContentBlock.Text("observed changes")), stopReason = StopReason.COMPLETE)
                }
            }
        }
        val result = AgentEngine(
            project = fakeProject(),
            provider = provider,
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxIterations = 4, maxToolCalls = 3),
        ).run("watch changing state")

        assertEquals(AgentRunStatus.COMPLETED, result.status)
        assertEquals(3, executions)
    }

    @Test
    fun `oversized per response tool batch fails closed without executing any call`() = runBlocking {
        var executions = 0
        val tool = object : AgentTool {
            override val name = "batch_safety_tool"
            override val description = "Records execution"
            override val dangerous = false
            override val inputSchema = JsonObject().apply { addProperty("type", "object") }
            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
                executions++
                return ToolExecutionResult("unexpected")
            }
        }
        val calls = (1..5).map { index -> ContentBlock.ToolCall("batch-$index", tool.name, JsonObject()) }
        val result = AgentEngine(
            project = fakeProject(),
            provider = SingleResponseProvider(ModelResponse(calls, stopReason = StopReason.TOOL_USE)),
            approvalGate = ApprovalGate { false },
            tools = ToolRegistry(additionalTools = listOf(tool)),
            limits = AgentLimits(maxToolCallsPerTurn = 4),
        ).run("reject an abnormal batch")

        assertEquals(AgentRunStatus.FAILED, result.status)
        assertEquals(0, executions)
        assertTrue(result.finalText.contains("per-response safety maximum"))
        assertTrue(result.messages.flatMap { it.blocks }.none { it is ContentBlock.ToolCall })
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
