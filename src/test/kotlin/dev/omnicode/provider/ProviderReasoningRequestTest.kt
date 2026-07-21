package dev.omnicode.provider

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ToolDefinition
import dev.omnicode.util.Json
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderReasoningRequestTest {
    @Test
    fun `Gemini maps 2_5 budgets and 3_x levels without low temperature`() = runBlocking {
        val captured = CopyOnWriteArrayList<JsonObject>()
        val server = jsonServer(captured) { geminiResponse() }
        try {
            val baseUrl = serverUrl(server)
            val budgetResponse = GeminiProvider(geminiConnection(baseUrl, "gemini-2.5-flash", ReasoningEffort.HIGH))
                .complete(request(maxOutputTokens = 100))
            val levelResponse = GeminiProvider(geminiConnection(baseUrl, "gemini-3.5-flash", ReasoningEffort.MEDIUM))
                .complete(request(maxOutputTokens = 200))

            val budgetConfig = captured[0].getAsJsonObject("generationConfig")
            assertEquals(24_576, budgetConfig.getAsJsonObject("thinkingConfig")["thinkingBudget"].asInt)
            assertFalse(budgetConfig.has("temperature"))
            assertEquals(20, budgetResponse.usage.outputTokens)

            val levelConfig = captured[1].getAsJsonObject("generationConfig")
            assertEquals("medium", levelConfig.getAsJsonObject("thinkingConfig")["thinkingLevel"].asString)
            assertFalse(levelConfig.has("temperature"))
            assertEquals(20, levelResponse.usage.outputTokens)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Bedrock maps Claude adaptive and Nova high fields`() = runBlocking {
        val captured = CopyOnWriteArrayList<JsonObject>()
        val server = jsonServer(captured) { bedrockTextResponse("ok") }
        try {
            val baseUrl = serverUrl(server)
            BedrockConverseProvider(
                bedrockConnection(baseUrl, "anthropic.claude-opus-4-6-v1", ReasoningEffort.HIGH),
            ).complete(request(maxOutputTokens = 300))
            BedrockConverseProvider(
                bedrockConnection(baseUrl, "us.amazon.nova-2-lite-v1:0", ReasoningEffort.HIGH),
            ).complete(request(maxOutputTokens = 400))

            val claude = captured[0]
            assertFalse(claude.getAsJsonObject("inferenceConfig").has("temperature"))
            val claudeFields = claude.getAsJsonObject("additionalModelRequestFields")
            assertEquals("adaptive", claudeFields.getAsJsonObject("thinking")["type"].asString)
            assertEquals("high", claudeFields.getAsJsonObject("output_config")["effort"].asString)

            val nova = captured[1]
            assertFalse(nova.has("inferenceConfig"))
            val novaReasoning = nova.getAsJsonObject("additionalModelRequestFields")
                .getAsJsonObject("reasoningConfig")
            assertEquals("enabled", novaReasoning["type"].asString)
            assertEquals("high", novaReasoning["maxReasoningEffort"].asString)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Bedrock Auto omits unsupported sampling for Claude Opus 4_7`() = runBlocking {
        val captured = CopyOnWriteArrayList<JsonObject>()
        val server = jsonServer(captured) { bedrockTextResponse("ok") }
        try {
            BedrockConverseProvider(
                bedrockConnection(
                    serverUrl(server),
                    "us.anthropic.claude-opus-4-7",
                    ReasoningEffort.AUTO,
                ),
            ).complete(request(maxOutputTokens = 300))

            val body = captured.single()
            assertEquals(300, body.getAsJsonObject("inferenceConfig")["maxTokens"].asInt)
            assertFalse(body.getAsJsonObject("inferenceConfig").has("temperature"))
            assertFalse(body.has("additionalModelRequestFields"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Bedrock replays signed reasoning content exactly for a tool continuation`() = runBlocking {
        val captured = CopyOnWriteArrayList<JsonObject>()
        val sequence = AtomicInteger()
        val firstContent = Json.parseObject(bedrockToolResponse()).getAsJsonObject("output")
            .getAsJsonObject("message").getAsJsonArray("content")
        val server = jsonServer(captured) {
            if (sequence.getAndIncrement() == 0) bedrockToolResponse() else bedrockTextResponse("done")
        }
        try {
            val provider = BedrockConverseProvider(
                bedrockConnection(
                    serverUrl(server),
                    "anthropic.claude-sonnet-4-20250514-v1:0",
                    ReasoningEffort.MEDIUM,
                ),
            )
            val initial = request(maxOutputTokens = 512, tools = listOf(readTool()))
            val first = provider.complete(initial)
            val continuation = initial.copy(
                messages = initial.messages +
                    ConversationMessage(MessageRole.ASSISTANT, first.blocks) +
                    ConversationMessage(
                        MessageRole.USER,
                        listOf(ContentBlock.ToolResult("tool-1", "file contents")),
                    ),
            )
            assertEquals("done", provider.complete(continuation).text)

            val firstRequest = captured[0]
            val thinking = firstRequest.getAsJsonObject("additionalModelRequestFields")
                .getAsJsonObject("thinking")
            assertEquals(4_096, thinking["budget_tokens"].asInt)
            assertEquals(4_097, firstRequest.getAsJsonObject("inferenceConfig")["maxTokens"].asInt)
            assertFalse(firstRequest.getAsJsonObject("inferenceConfig").has("temperature"))

            val replayed = captured[1].getAsJsonArray("messages")[1].asJsonObject.getAsJsonArray("content")
            assertEquals(firstContent, replayed)
            assertEquals(
                "sig-v1",
                replayed[0].asJsonObject.getAsJsonObject("reasoningContent")
                    .getAsJsonObject("reasoningText")["signature"].asString,
            )
            assertTrue(replayed[2].asJsonObject.has("toolUse"))
        } finally {
            server.stop(0)
        }
    }

    private fun request(
        maxOutputTokens: Int,
        tools: List<ToolDefinition> = emptyList(),
    ) = ModelRequest(
        messages = listOf(ConversationMessage(MessageRole.USER, "hello")),
        tools = tools,
        maxOutputTokens = maxOutputTokens,
        temperature = 0.2,
    )

    private fun readTool() = ToolDefinition(
        name = "read_file",
        description = "Read one file",
        inputSchema = JsonObject().apply { addProperty("type", "object") },
    )

    private fun geminiConnection(baseUrl: String, model: String, effort: ReasoningEffort) = ProviderConnection(
        preset = ProviderPresets.byId("gemini"),
        baseUrl = baseUrl,
        model = model,
        apiKey = "gemini-key",
        reasoningEffort = effort,
        requestTimeoutSeconds = 5,
    )

    private fun bedrockConnection(baseUrl: String, model: String, effort: ReasoningEffort) = ProviderConnection(
        preset = ProviderPresets.byId("bedrock"),
        baseUrl = baseUrl,
        model = model,
        apiKey = "bedrock-key",
        reasoningEffort = effort,
        requestTimeoutSeconds = 5,
    )

    private fun jsonServer(
        captured: MutableList<JsonObject>,
        response: () -> String,
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            captured += Json.parseObject(exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
            if (exchange.requestURI.path.contains(":streamGenerateContent")) {
                respond(exchange, "data: ${response()}\n\n", "text/event-stream")
            } else {
                respond(exchange, response(), "application/json")
            }
        }
        start()
    }

    private fun respond(exchange: HttpExchange, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serverUrl(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    private fun geminiResponse() =
        """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":7,"thoughtsTokenCount":13}}"""

    private fun bedrockTextResponse(text: String) =
        """{"output":{"message":{"role":"assistant","content":[{"text":"$text"}]}},"stopReason":"end_turn","usage":{"inputTokens":2,"outputTokens":3,"totalTokens":5}}"""

    private fun bedrockToolResponse() =
        """{"output":{"message":{"role":"assistant","content":[{"reasoningContent":{"reasoningText":{"text":"summary","signature":"sig-v1"}}},{"text":"checking"},{"toolUse":{"toolUseId":"tool-1","name":"read_file","input":{"path":"a.kt"}}}]}},"stopReason":"tool_use","usage":{"inputTokens":2,"outputTokens":10,"totalTokens":12}}"""
}
