package dev.omnicode.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeZenProviderTest {
    @Test
    fun `model ids select the documented Zen protocol`() {
        assertEquals(OpenCodeZenAdapter.OPENAI_RESPONSES, openCodeZenAdapter("gpt-5.4"))
        assertEquals(OpenCodeZenAdapter.OPENAI_RESPONSES, openCodeZenAdapter(" GPT-5.4-mini "))
        assertEquals(OpenCodeZenAdapter.ANTHROPIC_MESSAGES, openCodeZenAdapter("claude-sonnet-4-5"))
        assertEquals(OpenCodeZenAdapter.ANTHROPIC_MESSAGES, openCodeZenAdapter("qwen3.5-plus"))
        assertEquals(OpenCodeZenAdapter.GEMINI, openCodeZenAdapter("gemini-3-flash"))
        assertEquals(OpenCodeZenAdapter.OPENAI_CHAT, openCodeZenAdapter("big-pickle"))
        assertEquals(OpenCodeZenAdapter.OPENAI_CHAT, openCodeZenAdapter("north-mini-code-free"))
        assertEquals(OpenCodeZenAdapter.OPENAI_CHAT, openCodeZenAdapter("qwen3-coder-plus"))
    }

    @Test
    fun `factory creates the dedicated Zen hybrid provider`() {
        assertIs<OpenCodeZenProvider>(ProviderFactory.create(zenConnection("https://example.test", "big-pickle")))
    }

    @Test
    fun `hybrid provider uses each adapter endpoint and credential header`() = runBlocking {
        val requests = mutableListOf<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handleZenRequest(exchange, requests) }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val cases = listOf(
                Case("gpt-5.4", "/responses", "authorization"),
                Case("claude-sonnet-4-5", "/messages", "x-api-key"),
                Case("qwen3.5-plus", "/messages", "x-api-key"),
                Case("gemini-3-flash", "/models/gemini-3-flash:streamGenerateContent?alt=sse", "x-goog-api-key"),
                Case("big-pickle", "/chat/completions", "authorization"),
                Case("north-mini-code-free", "/chat/completions", "authorization"),
            )

            cases.forEachIndexed { index, case ->
                val provider = ProviderFactory.create(zenConnection(baseUrl, case.model))
                val response = provider.complete(request())

                assertEquals("ok", response.text, case.model)
                val captured = requests[index]
                assertEquals(case.pathAndQuery, captured.pathAndQuery, case.model)
                assertEquals(
                    if (case.credentialHeader == "authorization") "Bearer zen-secret" else "zen-secret",
                    captured.headers[case.credentialHeader],
                    case.model,
                )
                if (case.credentialHeader != "authorization") {
                    assertNull(captured.headers["authorization"], case.model)
                }
                if (case.pathAndQuery == "/messages") {
                    assertEquals("2023-06-01", captured.headers["anthropic-version"], case.model)
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stream ending without a terminal marker fails closed`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                val body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
                    .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val provider = ProviderFactory.create(
                zenConnection("http://127.0.0.1:${server.address.port}", "big-pickle"),
            )

            val failure = try {
                provider.complete(request())
                throw AssertionError("Expected an incomplete stream failure")
            } catch (error: ProviderException) {
                error
            }

            assertTrue(failure.message.orEmpty().contains("before a terminal event"))
        } finally {
            server.stop(0)
        }
    }

    private fun zenConnection(baseUrl: String, model: String): ProviderConnection = ProviderConnection(
        preset = ProviderPresets.byId("opencode"),
        baseUrl = baseUrl,
        model = model,
        apiKey = "zen-secret",
        apiVersion = "2025-04-01",
        requestTimeoutSeconds = 5,
    )

    private fun request(): ModelRequest = ModelRequest(
        messages = listOf(ConversationMessage(MessageRole.USER, "hello")),
        tools = emptyList(),
        maxOutputTokens = 64,
    )

    private fun handleZenRequest(exchange: HttpExchange, requests: MutableList<CapturedRequest>) {
        val pathAndQuery = buildString {
            append(exchange.requestURI.rawPath)
            exchange.requestURI.rawQuery?.let { append('?').append(it) }
        }
        requests += CapturedRequest(
            pathAndQuery = pathAndQuery,
            headers = exchange.requestHeaders.entries.associate { (name, values) ->
                name.lowercase() to values.first()
            },
        )
        exchange.requestBody.use { it.readAllBytes() }
        val body = when {
            exchange.requestURI.path.endsWith("/responses") -> responsesSse()
            exchange.requestURI.path.endsWith("/messages") -> anthropicSse()
            exchange.requestURI.path.contains(":streamGenerateContent") -> geminiSse()
            else -> chatSse()
        }.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun responsesSse(): String = """
        event: response.output_text.delta
        data: {"type":"response.output_text.delta","delta":"ok"}

        event: response.completed
        data: {"type":"response.completed","response":{"id":"resp_zen","usage":{"input_tokens":1,"output_tokens":1},"output":[]}}

        data: [DONE]

    """.trimIndent()

    private fun anthropicSse(): String = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_zen","usage":{"input_tokens":1,"output_tokens":0}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

    """.trimIndent()

    private fun geminiSse(): String = """
        data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}}

    """.trimIndent()

    private fun chatSse(): String = """
        data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}

        data: [DONE]

    """.trimIndent()

    private data class Case(
        val model: String,
        val pathAndQuery: String,
        val credentialHeader: String,
    )

    private data class CapturedRequest(
        val pathAndQuery: String,
        val headers: Map<String, String>,
    )
}
