package dev.omnicode.mcp.oauth

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthLoopbackCallbackTest {
    @Test
    fun `accepts one matching authorization callback on an unpredictable loopback path`() = runBlocking {
        val state = "s".repeat(43)
        McpOAuthLoopbackCallback.start(state).use { callback ->
            assertEquals("127.0.0.1", callback.redirectUri.host)
            assertTrue(callback.redirectUri.path.startsWith("/omnicode/oauth/callback/"))
            assertTrue(callback.redirectUri.port > 0)

            val pending = async { callback.await(Duration.ofSeconds(3)) }
            assertEquals(200, get(callback.redirectUri.withQuery("code=auth-code&state=$state")))
            val result = pending.await()
            assertEquals("auth-code", result.code)
            assertNull(result.error)
        }
    }

    @Test
    fun `wrong state does not consume the callback`() = runBlocking {
        val state = "expected-" + "x".repeat(34)
        McpOAuthLoopbackCallback.start(state).use { callback ->
            val pending = async { callback.await(Duration.ofSeconds(3)) }
            assertEquals(400, get(callback.redirectUri.withQuery("code=stolen&state=${"z".repeat(43)}")))
            assertEquals(200, get(callback.redirectUri.withQuery("code=real&state=${encode(state)}")))
            assertEquals("real", pending.await().code)
        }
    }

    @Test
    fun `returns bounded provider error without reflecting it into browser HTML`() = runBlocking {
        val state = "q".repeat(43)
        McpOAuthLoopbackCallback.start(state).use { callback ->
            val pending = async { callback.await(Duration.ofSeconds(3)) }
            val query = "error=access_denied&error_description=${encode("User denied <script>alert(1)</script>")}&state=$state"
            val connection = open(callback.redirectUri.withQuery(query))
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().readText()
            assertNotEquals(true, body.contains("<script>"))
            val result = pending.await()
            assertEquals("access_denied", result.error)
            assertTrue(result.errorDescription.orEmpty().startsWith("User denied"))
        }
    }

    private fun get(uri: URI): Int = open(uri).let { connection ->
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun open(uri: URI): HttpURLConnection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 1_000
        readTimeout = 1_000
        instanceFollowRedirects = false
    }

    private fun URI.withQuery(query: String): URI = URI(scheme, userInfo, host, port, path, query, null)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
