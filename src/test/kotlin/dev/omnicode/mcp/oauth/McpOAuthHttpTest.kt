package dev.omnicode.mcp.oauth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthHttpTest {
    @Test
    fun `OAuth JSON parser rejects null and non-object roots without leaking Gson type errors`() {
        listOf("null", "[]", "\"not-an-object\"").forEach { body ->
            val error = assertFailsWith<McpOAuthException> {
                parseJsonResponse(
                    McpOAuthHttpResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to listOf("application/json")),
                        body = body.toByteArray(),
                    ),
                    label = "OAuth metadata",
                )
            }

            assertTrue(error.message.orEmpty().contains("non-object JSON response"))
            assertNull(error.cause)
        }
    }

    @Test
    fun `default HTTP transport never follows redirects`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var followed = false
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            followed = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val error = assertFailsWith<McpOAuthException> {
                JavaMcpOAuthHttpTransport().execute(
                    McpOAuthHttpRequest(
                        method = "GET",
                        uri = URI("http://127.0.0.1:${server.address.port}/start"),
                        headers = emptyMap(),
                    ),
                )
            }
            assertTrue(error.message.orEmpty().contains("redirect"))
            assertTrue(!followed)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `default HTTP transport bounds response bodies`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { exchange ->
            exchange.responseHeaders.add("Content-Length", "2097152")
            exchange.sendResponseHeaders(200, 2_097_152)
            exchange.responseBody.close()
            exchange.close()
        }
        server.start()
        try {
            val error = assertFailsWith<McpOAuthException> {
                JavaMcpOAuthHttpTransport().execute(
                    McpOAuthHttpRequest(
                        method = "GET",
                        uri = URI("http://127.0.0.1:${server.address.port}/large"),
                        headers = emptyMap(),
                    ),
                )
            }
            assertTrue(error.message.orEmpty().contains("size limit"))
        } finally {
            server.stop(0)
        }
    }
}
