package dev.omnicode.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStreamableHttpClientTest {
    @Test
    fun `validates HTTPS for remote endpoints and permits literal loopback HTTP`() {
        assertEquals("https", validateMcpHttpEndpoint("https://mcp.example.com/api").scheme)
        assertEquals("http", validateMcpHttpEndpoint("http://localhost:8080/mcp").scheme)
        assertEquals("http", validateMcpHttpEndpoint("http://127.0.0.1/mcp").scheme)
        assertEquals("http", validateMcpHttpEndpoint("http://[::1]/mcp").scheme)

        assertFailsWith<IllegalArgumentException> { validateMcpHttpEndpoint("http://mcp.example.com/api") }
        assertFailsWith<IllegalArgumentException> { validateMcpHttpEndpoint("file:///tmp/mcp") }
        assertFailsWith<IllegalArgumentException> { validateMcpHttpEndpoint("https://token@example.com/mcp") }
        assertFailsWith<IllegalArgumentException> { validateMcpHttpEndpoint("https://example.com/mcp#secret") }
    }

    @Test
    fun `JSON transport sends required headers carries session and deletes it on close`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        withServer { exchange ->
            val request = capture(exchange)
            requests += request
            when (request.rpcMethod) {
                "initialize" -> exchange.respondJson(
                    rpcResult(request.rpcId, JsonObject().apply {
                        addProperty("protocolVersion", "2025-11-25")
                        add("capabilities", JsonObject())
                    }),
                    headers = mapOf("MCP-Session-Id" to "session-123"),
                )
                "notifications/initialized" -> exchange.respondEmpty(202)
                "tools/list" -> exchange.respondJson(
                    rpcResult(request.rpcId, JsonObject().apply {
                        add("tools", JsonArray().apply { add(tool("echo")) })
                    }),
                )
                null -> exchange.respondEmpty(204)
                else -> exchange.respondEmpty(400)
            }
        }.use { server ->
            val client = McpStreamableHttpClient.connect(
                config(server.endpoint),
                bearerToken = "secret-token",
            )
            try {
                assertEquals(listOf("echo"), client.listTools().map { it.name })
            } finally {
                client.close()
            }
        }

        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", null),
            requests.map(CapturedRequest::rpcMethod),
        )
        assertEquals(listOf("POST", "POST", "POST", "DELETE"), requests.map(CapturedRequest::httpMethod))
        assertTrue(requests.take(3).all { it.accept.contains("application/json") && it.accept.contains("text/event-stream") })
        assertTrue(requests.all { it.authorization == "Bearer secret-token" })
        assertNull(requests.first().sessionId)
        assertNull(requests.first().protocolVersion)
        assertTrue(requests.drop(1).all { it.sessionId == "session-123" })
        assertTrue(requests.drop(1).all { it.protocolVersion == "2025-11-25" })
    }

    @Test
    fun `SSE response is parsed including multi-line data`() = runBlocking {
        withServer { exchange ->
            val request = capture(exchange)
            when (request.rpcMethod) {
                "initialize" -> exchange.respondJson(
                    rpcResult(request.rpcId, JsonObject().apply {
                        addProperty("protocolVersion", "2025-11-25")
                        add("capabilities", JsonObject())
                    }),
                )
                "notifications/initialized" -> exchange.respondEmpty(202)
                "tools/list" -> exchange.respondSse(
                    """
                    id: prime-1
                    data:

                    id: response-1
                    data: {"jsonrpc":"2.0",
                    data: "id":${request.rpcId},"result":{"tools":[{"name":"sse-tool","inputSchema":{"type":"object"}}]}}

                    """.trimIndent(),
                )
                else -> exchange.respondEmpty(if (request.httpMethod == "DELETE") 204 else 400)
            }
        }.use { server ->
            val client = McpStreamableHttpClient.connect(config(server.endpoint))
            try {
                assertEquals(listOf("sse-tool"), client.listTools().map { it.name })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `session 404 starts a fresh initialization and retries the request`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        var initializeCount = 0
        withServer { exchange ->
            val request = capture(exchange)
            requests += request
            when (request.rpcMethod) {
                "initialize" -> {
                    initializeCount++
                    exchange.respondJson(
                        rpcResult(request.rpcId, JsonObject().apply {
                            addProperty("protocolVersion", "2025-11-25")
                            add("capabilities", JsonObject())
                        }),
                        headers = mapOf("MCP-Session-Id" to "session-$initializeCount"),
                    )
                }
                "notifications/initialized" -> exchange.respondEmpty(202)
                "tools/list" -> if (request.sessionId == "session-1") {
                    exchange.respondEmpty(404)
                } else {
                    exchange.respondJson(
                        rpcResult(request.rpcId, JsonObject().apply {
                            add("tools", JsonArray().apply { add(tool("after-reconnect")) })
                        }),
                    )
                }
                else -> exchange.respondEmpty(if (request.httpMethod == "DELETE") 204 else 400)
            }
        }.use { server ->
            val client = McpStreamableHttpClient.connect(config(server.endpoint))
            try {
                assertEquals(listOf("after-reconnect"), client.listTools().map { it.name })
            } finally {
                client.close()
            }
        }

        assertEquals(2, initializeCount)
        val initializeRequests = requests.filter { it.rpcMethod == "initialize" }
        assertTrue(initializeRequests.all { it.sessionId == null })
        assertEquals(listOf("session-1", "session-2"), requests.filter { it.rpcMethod == "tools/list" }.map { it.sessionId })
    }

    @Test
    fun `redirect responses are rejected and never followed`() = runBlocking {
        var requests = 0
        withServer { exchange ->
            requests++
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${exchange.localAddress.port}/other")
            exchange.respondEmpty(307)
        }.use { server ->
            val error = assertFailsWith<McpProtocolException> {
                McpStreamableHttpClient.connect(config(server.endpoint))
            }
            assertTrue(error.message.orEmpty().contains("refused HTTP redirect"))
        }
        assertEquals(1, requests)
    }

    @Test
    fun `HTTP errors never echo the configured bearer token`() = runBlocking {
        withServer { exchange ->
            exchange.respondText(500, "upstream rejected secret-token")
        }.use { server ->
            val error = assertFailsWith<McpProtocolException> {
                McpStreamableHttpClient.connect(config(server.endpoint), bearerToken = "secret-token")
            }
            assertFalse(error.message.orEmpty().contains("secret-token"))
            assertTrue(error.message.orEmpty().contains("[REDACTED]"))
        }
    }

    @Test
    fun `authorization challenge is exposed in a bounded typed exception`() = runBlocking {
        withServer { exchange ->
            exchange.responseHeaders.add(
                "WWW-Authenticate",
                "Bearer resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource\", " +
                    "scope=\"mcp:use\", error_description=\"secret-token\"",
            )
            exchange.respondEmpty(401)
        }.use { server ->
            val error = assertFailsWith<McpProtocolException> {
                McpStreamableHttpClient.connect(config(server.endpoint), bearerToken = "secret-token")
            }
            val challenge = assertIs<McpHttpAuthorizationChallengeException>(error)
            assertEquals(401, challenge.statusCode)
            assertTrue(challenge.wwwAuthenticate.single().contains("resource_metadata"))
            assertFalse(challenge.wwwAuthenticate.single().contains("secret-token"))
            assertTrue(challenge.wwwAuthenticate.single().contains("[REDACTED]"))
        }
    }

    @Test
    fun `JSON-RPC error messages redact the configured bearer token`() = runBlocking {
        withServer { exchange ->
            val request = capture(exchange)
            exchange.respondJson(
                JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    request.rpcId?.let { addProperty("id", it) }
                    add("error", JsonObject().apply {
                        addProperty("code", -32_000)
                        addProperty("message", "server echoed secret-token")
                    })
                },
            )
        }.use { server ->
            val error = assertFailsWith<McpProtocolException> {
                McpStreamableHttpClient.connect(config(server.endpoint), bearerToken = "secret-token")
            }
            assertFalse(error.message.orEmpty().contains("secret-token"))
            assertTrue(error.message.orEmpty().contains("[REDACTED]"))
            assertTrue(error.causes().none { it.message.orEmpty().contains("secret-token") })
        }
    }

    @Test
    fun `invalid remote JSON never survives as an attacker controlled exception cause`() = runBlocking {
        withServer { exchange -> exchange.respondJsonText("\"secret-token\"") }.use { server ->
            val error = assertFailsWith<McpProtocolException> {
                McpStreamableHttpClient.connect(config(server.endpoint), bearerToken = "secret-token")
            }
            assertFalse(error.message.orEmpty().contains("secret-token"))
            assertTrue(error.causes().none { it.message.orEmpty().contains("secret-token") })
        }
    }

    private fun config(endpoint: String): McpServerConfig = McpServerConfig(
        id = "http-test",
        name = "HTTP test",
        enabled = true,
        command = "",
        arguments = emptyList(),
        environmentKeys = emptySet(),
        workingDirectory = ".",
        transport = McpTransport.HTTP,
        url = endpoint,
    )

    private fun capture(exchange: HttpExchange): CapturedRequest {
        val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        val json = body.takeIf(String::isNotBlank)?.let { JsonParser.parseString(it).asJsonObject }
        return CapturedRequest(
            httpMethod = exchange.requestMethod,
            rpcMethod = json?.get("method")?.asString,
            rpcId = json?.get("id")?.asLong,
            accept = exchange.requestHeaders.getFirst("Accept").orEmpty(),
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            sessionId = exchange.requestHeaders.getFirst("MCP-Session-Id"),
            protocolVersion = exchange.requestHeaders.getFirst("MCP-Protocol-Version"),
        )
    }

    private fun rpcResult(id: Long?, result: JsonObject): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        id?.let { addProperty("id", it) }
        add("result", result)
    }

    private fun tool(name: String): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", "test")
        add("inputSchema", JsonObject().apply { addProperty("type", "object") })
    }

    private fun HttpExchange.respondJson(body: JsonObject, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondSse(body: String) {
        responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondJsonText(body: String) {
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondEmpty(status: Int) {
        sendResponseHeaders(status, -1)
        close()
    }

    private fun HttpExchange.respondText(status: Int, body: String) {
        responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun withServer(handler: (HttpExchange) -> Unit): TestServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange -> handler(exchange) }
        server.start()
        return TestServer(server)
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(cause) { it.cause }

private data class CapturedRequest(
    val httpMethod: String,
    val rpcMethod: String?,
    val rpcId: Long?,
    val accept: String,
    val authorization: String?,
    val sessionId: String?,
    val protocolVersion: String?,
)

private class TestServer(private val server: HttpServer) : AutoCloseable {
    val endpoint: String = "http://127.0.0.1:${server.address.port}/mcp"

    override fun close() {
        server.stop(0)
    }
}
