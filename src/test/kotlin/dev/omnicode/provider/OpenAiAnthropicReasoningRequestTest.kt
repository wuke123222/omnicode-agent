package dev.omnicode.provider

import com.google.gson.JsonArray
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

class OpenAiAnthropicReasoningRequestTest {
    @Test
    fun `Responses emits max plus pro for GPT 5_6 and omits Auto`() = runBlocking {
        SseServer { responsesSse() }.use { server ->
            OpenAiResponsesProvider(
                connection(server.baseUrl, ProviderProtocol.OPENAI_RESPONSES, "openai", "gpt-5.6-sol", ReasoningEffort.MAX),
            ).complete(simpleRequest())
            OpenAiResponsesProvider(
                connection(server.baseUrl, ProviderProtocol.OPENAI_RESPONSES, "openai", "gpt-5.6-sol", ReasoningEffort.AUTO),
            ).complete(simpleRequest())

            val maxBody = Json.parseObject(server.requestBodies[0])
            assertEquals(65_536, maxBody["max_output_tokens"].asInt)
            assertEquals("max", maxBody.getAsJsonObject("reasoning")["effort"].asString)
            assertEquals("pro", maxBody.getAsJsonObject("reasoning")["mode"].asString)
            assertFalse(Json.parseObject(server.requestBodies[1]).has("reasoning"))
        }
    }

    @Test
    fun `Chat uses native and OpenRouter reasoning shapes without sampling parameters`() = runBlocking {
        SseServer { chatSse() }.use { server ->
            OpenAiChatProvider(
                connection(server.baseUrl, ProviderProtocol.OPENAI_CHAT, "openai", "gpt-5.5", ReasoningEffort.HIGH),
            ).complete(simpleRequest())
            OpenAiChatProvider(
                connection(
                    server.baseUrl,
                    ProviderProtocol.OPENAI_CHAT,
                    "openrouter",
                    "openai/gpt-5.6-sol",
                    ReasoningEffort.MAX,
                ),
            ).complete(simpleRequest())
            OpenAiChatProvider(
                connection(server.baseUrl, ProviderProtocol.OPENAI_CHAT, "openai", "gpt-4.1", ReasoningEffort.AUTO),
            ).complete(simpleRequest())

            val native = Json.parseObject(server.requestBodies[0])
            assertEquals("high", native["reasoning_effort"].asString)
            assertEquals(65_536, native["max_completion_tokens"].asInt)
            assertFalse(native.has("max_tokens"))
            assertFalse(native.has("temperature"))

            val openRouter = Json.parseObject(server.requestBodies[1])
            assertEquals("max", openRouter.getAsJsonObject("reasoning")["effort"].asString)
            assertFalse(openRouter.has("reasoning_effort"))
            assertTrue(openRouter.has("max_completion_tokens"))
            assertFalse(openRouter.has("temperature"))

            val auto = Json.parseObject(server.requestBodies[2])
            assertFalse(auto.has("reasoning"))
            assertFalse(auto.has("reasoning_effort"))
            assertEquals(65_536, auto["max_tokens"].asInt)
            assertEquals(0.2, auto["temperature"].asDouble)
        }
    }

    @Test
    fun `Chat ignores null stream fields and consumes a later valid chunk`() = runBlocking {
        SseServer { chatNullFieldsSse() }.use { server ->
            val response = OpenAiChatProvider(
                connection(server.baseUrl, ProviderProtocol.OPENAI_CHAT, "openai", "gpt-4.1", ReasoningEffort.AUTO),
            ).complete(simpleRequest())

            assertEquals("ok", response.text)
            assertEquals(2, response.usage.inputTokens)
            assertEquals(3, response.usage.outputTokens)
            assertEquals(dev.omnicode.model.StopReason.COMPLETE, response.stopReason)
        }
    }

    @Test
    fun `Responses ignores null stream fields and consumes a later valid chunk`() = runBlocking {
        SseServer { responsesNullFieldsSse() }.use { server ->
            val response = OpenAiResponsesProvider(
                connection(
                    server.baseUrl,
                    ProviderProtocol.OPENAI_RESPONSES,
                    "openai",
                    "gpt-5.6-sol",
                    ReasoningEffort.AUTO,
                ),
            ).complete(simpleRequest())

            assertEquals("ok", response.text)
            assertEquals(5, response.usage.inputTokens)
            assertEquals(7, response.usage.outputTokens)
            assertEquals(dev.omnicode.model.StopReason.COMPLETE, response.stopReason)
        }
    }

    @Test
    fun `Anthropic emits effort and replays signed thinking with its tool call`() = runBlocking {
        val responseIndex = AtomicInteger()
        SseServer {
            if (responseIndex.getAndIncrement() == 0) anthropicToolSse() else anthropicTextSse()
        }.use { server ->
            val provider = AnthropicMessagesProvider(
                connection(
                    server.baseUrl,
                    ProviderProtocol.ANTHROPIC_MESSAGES,
                    "anthropic",
                    "claude-opus-4-8",
                    ReasoningEffort.XHIGH,
                ),
            )
            val user = ConversationMessage(MessageRole.USER, "Inspect the project")
            val first = provider.complete(
                ModelRequest(listOf(user), listOf(readFileTool()), maxOutputTokens = 65_536),
            )

            assertEquals(1, first.toolCalls.size)
            assertTrue(first.blocks.none { it is ContentBlock.Text })
            val toolCall = first.toolCalls.single()

            provider.complete(
                ModelRequest(
                    messages = listOf(
                        user,
                        ConversationMessage(MessageRole.ASSISTANT, first.blocks),
                        ConversationMessage(
                            MessageRole.USER,
                            listOf(ContentBlock.ToolResult(toolCall.id, "file contents")),
                        ),
                    ),
                    tools = listOf(readFileTool()),
                    maxOutputTokens = 65_536,
                ),
            )

            val firstBody = Json.parseObject(server.requestBodies[0])
            assertEquals("xhigh", firstBody.getAsJsonObject("output_config")["effort"].asString)
            assertEquals("adaptive", firstBody.getAsJsonObject("thinking")["type"].asString)
            assertFalse(firstBody.has("temperature"))

            val continuation = Json.parseObject(server.requestBodies[1])
            val assistant = continuation.getAsJsonArray("messages")
                .map { it.asJsonObject }
                .single { it["role"].asString == "assistant" }
            val content = assistant.getAsJsonArray("content").map { it.asJsonObject }
            assertEquals(listOf("thinking", "tool_use"), content.map { it["type"].asString })
            assertEquals("inspect project", content[0]["thinking"].asString)
            assertEquals("sig-nature", content[0]["signature"].asString)
            assertEquals(toolCall.id, content[1]["id"].asString)
            assertEquals("src/App.kt", content[1].getAsJsonObject("input")["path"].asString)
        }
    }

    @Test
    fun `Anthropic Auto omits effort and explicit thinking`() = runBlocking {
        SseServer { anthropicTextSse() }.use { server ->
            AnthropicMessagesProvider(
                connection(
                    server.baseUrl,
                    ProviderProtocol.ANTHROPIC_MESSAGES,
                    "anthropic",
                    "claude-opus-4-8",
                    ReasoningEffort.AUTO,
                ),
            ).complete(simpleRequest())

            val body = Json.parseObject(server.requestBodies.single())
            assertFalse(body.has("output_config"))
            assertFalse(body.has("thinking"))
            assertFalse(body.has("temperature"))
        }
    }

    private fun connection(
        baseUrl: String,
        protocol: ProviderProtocol,
        providerId: String,
        model: String,
        effort: ReasoningEffort,
    ) = ProviderConnection(
        preset = ProviderPreset(providerId, providerId, protocol, baseUrl, model),
        baseUrl = baseUrl,
        model = model,
        apiKey = "test-secret",
        apiVersion = "2023-06-01",
        reasoningEffort = effort,
        requestTimeoutSeconds = 5,
    )

    private fun simpleRequest() = ModelRequest(
        messages = listOf(ConversationMessage(MessageRole.USER, "hello")),
        tools = emptyList(),
        maxOutputTokens = 65_536,
    )

    private fun readFileTool() = ToolDefinition(
        name = "read_file",
        description = "Read a project file",
        inputSchema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("path", JsonObject().apply { addProperty("type", "string") })
            })
            add("required", JsonArray().apply { add("path") })
        },
    )

    private fun responsesSse() = """
        event: response.completed
        data: {"type":"response.completed","response":{"id":"resp_test","usage":{"input_tokens":1,"output_tokens":1},"output":[]}}

        data: [DONE]

    """.trimIndent()

    private fun chatSse() = """
        data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

        data: [DONE]

    """.trimIndent()

    private fun chatNullFieldsSse() = """
        data: {"choices":null,"usage":null}

        data: {"choices":[null],"usage":{"prompt_tokens":null,"completion_tokens":null}}

        data: {"choices":[{"delta":null,"finish_reason":null}]}

        data: {"choices":[{"delta":{"content":null,"tool_calls":null},"finish_reason":null}]}

        data: {"choices":[{"delta":{"content":"ok","tool_calls":[null]},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":3}}

        data: [DONE]

    """.trimIndent()

    private fun responsesNullFieldsSse() = """
        event: response.output_text.delta
        data: {"type":"response.output_text.delta","delta":null,"response":null}

        event: response.output_item.added
        data: {"type":"response.output_item.added","output_index":null,"item":null}

        event: response.completed
        data: {"type":"response.completed","response":{"id":null,"usage":null,"output":null}}

        event: response.output_text.delta
        data: {"type":"response.output_text.delta","delta":"ok"}

        event: response.completed
        data: {"type":"response.completed","response":{"id":"resp_null_safe","usage":{"input_tokens":5,"output_tokens":7},"output":[]}}

        data: [DONE]

    """.trimIndent()

    private fun anthropicToolSse() = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_tool","usage":{"input_tokens":4,"output_tokens":0}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"inspect "}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"project"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"nature"}}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_read","name":"read_file","input":{}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"src/App.kt\"}"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()

    private fun anthropicTextSse() = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_text","usage":{"input_tokens":3,"output_tokens":0}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

    """.trimIndent()

    private class SseServer(private val response: () -> String) : AutoCloseable {
        val requestBodies = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requestBodies += exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                respond(exchange, response())
            }
            start()
        }

        val baseUrl = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }

    companion object {
        private fun respond(exchange: HttpExchange, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
