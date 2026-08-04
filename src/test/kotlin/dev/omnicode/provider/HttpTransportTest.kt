package dev.omnicode.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTransportTest {
    @Test
    fun `HTTP failures expose bounded retry and request correlation metadata`() = runBlocking {
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            exchange.responseHeaders.add("Retry-After", "2")
            exchange.responseHeaders.add("X-Request-Id", "req_01J-safe")
            respond(exchange, 429, "rate limited")
        }.use { server ->
            val error = expectProviderFailure {
                HttpTransport.postJson(server.url("/limited"), emptyMap(), "{}", 5)
            }

            assertEquals(429, error.statusCode)
            assertEquals(2_000L, error.retryAfterMillis)
            assertEquals("req_01J-safe", error.requestId)
            assertTrue(error.retryable)
            assertFalse(error.networkFailure)
        }
    }

    @Test
    fun `Retry-After HTTP dates are parsed without retaining raw headers`() {
        val now = Instant.parse("2026-07-19T12:00:00Z")
        val headers = mapOf("retry-after" to listOf("Sun, 19 Jul 2026 12:00:03 GMT"))

        assertEquals(3_000L, retryAfterMillis(headers, now))
        assertNull(safeProviderRequestId(mapOf("x-request-id" to listOf("unsafe id with spaces"))))
    }

    @Test
    fun `provider transport never follows redirects carrying authorization`() = runBlocking {
        val redirectedRequests = AtomicInteger()
        withServer { exchange ->
            redirectedRequests.incrementAndGet()
            respond(exchange, 200, "{}")
        }.use { target ->
            withServer { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                exchange.responseHeaders.add("Location", target.url("/credential-leak"))
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }.use { source ->
                val error = expectProviderFailure {
                    HttpTransport.getJson(
                        source.url("/redirect"),
                        mapOf("Authorization" to "Bearer provider-secret"),
                        timeoutSeconds = 5,
                    )
                }

                assertEquals(302, error.statusCode)
                assertEquals(0, redirectedRequests.get())
            }
        }
    }

    @Test
    fun `remote HTTP endpoints are rejected while loopback HTTP is accepted`() {
        assertTrue(modelApiEndpointValidationError("http://api.example.com/v1").orEmpty().contains("HTTPS"))
        assertNull(modelApiEndpointValidationError("http://localhost:11434/v1"))
        assertNull(modelApiEndpointValidationError("http://127.42.0.1:8080/v1"))
        assertNull(modelApiEndpointValidationError("http://[::1]:8080/v1"))
    }

    @Test
    fun `base URL rejects query credentials userinfo and fragments`() {
        assertTrue(
            modelApiBaseUrlValidationError("https://api.example.com/v1?key=provider-secret")
                .orEmpty()
                .contains("Password Safe"),
        )
        assertTrue(modelApiBaseUrlValidationError("https://user:secret@api.example.com/v1").orEmpty().contains("用户名"))
        assertTrue(modelApiBaseUrlValidationError("https://api.example.com/v1#secret").orEmpty().contains("fragment"))
        assertNull(modelApiEndpointValidationError("https://api.example.com/v1?key=runtime-secret"))
    }

    @Test
    fun `credential origins are canonical and ignore API paths`() {
        assertEquals("https://api.example.com", canonicalModelApiOrigin("HTTPS://API.EXAMPLE.COM:443/v1"))
        assertEquals("https://api.example.com:8443", canonicalModelApiOrigin("https://api.example.com:8443/v2"))
        assertEquals(
            "https://bedrock-runtime.{region}.amazonaws.com",
            canonicalModelApiOrigin("https://bedrock-runtime.{region}.amazonaws.com/model"),
        )
    }

    @Test
    fun `system proxy selector is explicitly retained when IDE proxy is unset`() {
        val system = ProxySelector.of(InetSocketAddress("127.0.0.1", 7897))

        val selected = modelApiProxySelector(systemSelector = system, property = { null })
        assertEquals(Proxy.NO_PROXY, selected.select(URI("http://127.0.0.1:8080")).single())
        assertEquals(system.select(URI("https://api.openai.com")).single(), selected.select(URI("https://api.openai.com")).single())
    }

    @Test
    fun `JVM HTTPS proxy properties win over an IDE selector and preserve no proxy hosts`() {
        val selector = modelApiProxySelector(
            systemSelector = ProxySelector.of(null),
            property = mapOf(
                "https.proxyHost" to "127.0.0.1",
                "https.proxyPort" to "7897",
                "http.nonProxyHosts" to "localhost|*.internal",
            )::get,
        )

        val apiProxy = selector.select(URI("https://api.openai.com/v1/models")).single()
        assertEquals(Proxy.Type.HTTP, apiProxy.type())
        assertEquals(InetSocketAddress.createUnresolved("127.0.0.1", 7897), apiProxy.address())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://localhost:8080")).single())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://127.0.0.1:8080")).single())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("https://worker.internal/task")).single())
    }

    @Test
    fun `JSON responses are read incrementally with a hard byte limit`() = runBlocking {
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            val body = if (exchange.requestURI.path == "/small") "{\"ok\":true}" else "x".repeat(129)
            respond(exchange, 200, body)
        }.use { server ->
            val limits = limits(jsonBytes = 128)
            val result = HttpTransport.getJson(server.url("/small"), emptyMap(), 5, limits = limits)

            assertEquals("{\"ok\":true}", result.body)

            val error = expectProviderFailure {
                HttpTransport.postJson(server.url("/large"), emptyMap(), "{}", 5, limits = limits)
            }
            assertTrue(error.message.orEmpty().contains("JSON response exceeded the 128-bytes limit"))
            assertEquals(200, error.statusCode)
            assertTrue(error.billingUncertain)
            assertNull(error.responseBody)
        }
    }

    @Test
    fun `abrupt JSON response body becomes retryable provider network failure`() = runBlocking {
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 128)
            exchange.responseBody.use { output ->
                output.write("{\"partial\":".toByteArray(StandardCharsets.UTF_8))
            }
        }.use { server ->
            val error = expectProviderFailure {
                HttpTransport.getJson(server.url("/abrupt"), emptyMap(), timeoutSeconds = 5)
            }

            assertEquals(200, error.statusCode)
            assertTrue(error.networkFailure)
            assertTrue(error.retryable)
            assertTrue(error.message.orEmpty().contains("stream closed unexpectedly"))
        }
    }

    @Test
    fun `ordinary and SSE error bodies are bounded and secrets stay redacted`() = runBlocking {
        val secret = "provider-secret-value"
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            when (exchange.requestURI.path) {
                "/redacted" -> respond(exchange, 500, "server echoed $secret")
                else -> respond(exchange, 502, "e".repeat(65))
            }
        }.use { server ->
            val limits = limits(errorBytes = 64)
            val ordinary = expectProviderFailure {
                HttpTransport.getJson(
                    server.url("/redacted"),
                    emptyMap(),
                    5,
                    sensitiveValues = listOf(secret),
                    limits = limits,
                )
            }
            assertEquals(500, ordinary.statusCode)
            assertFalse(ordinary.responseBody.orEmpty().contains(secret))
            assertTrue(ordinary.responseBody.orEmpty().contains("[REDACTED]"))

            val sse = expectProviderFailure {
                HttpTransport.postSse(
                    url = server.url("/large"),
                    headers = emptyMap(),
                    body = "{}",
                    timeoutSeconds = 5,
                    limits = limits,
                ) { _, _ -> }
            }
            assertTrue(sse.message.orEmpty().contains("error response exceeded the 64-bytes limit"))
            assertEquals(502, sse.statusCode)
            assertNull(sse.responseBody)
        }
    }

    @Test
    fun `SSE remains event incremental and supports multiline data`() = runBlocking {
        val body = """
            event: first
            data: alpha
            data: beta

            data: gamma

        """.trimIndent()
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            respond(exchange, 200, body, "text/event-stream")
        }.use { server ->
            val events = mutableListOf<Pair<String?, String>>()

            HttpTransport.postSse(
                url = server.url("/events"),
                headers = emptyMap(),
                body = "{}",
                timeoutSeconds = 5,
                limits = limits(),
            ) { event, data -> events += event to data }

            assertEquals(listOf("first" to "alpha\nbeta", null to "gamma"), events)
        }
    }

    @Test
    fun `SSE line and event accumulation limits fail closed`() = runBlocking {
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            val body = when (exchange.requestURI.path) {
                "/line" -> "data: ${"x".repeat(40)}\n\n"
                else -> "data: 1234567890\ndata: abcdefghij\n\n"
            }
            respond(exchange, 200, body, "text/event-stream")
        }.use { server ->
            val lineError = expectProviderFailure {
                HttpTransport.postSse(
                    server.url("/line"),
                    emptyMap(),
                    "{}",
                    5,
                    limits = limits(lineChars = 24),
                ) { _, _ -> }
            }
            assertTrue(lineError.message.orEmpty().contains("SSE line exceeded the 24-characters limit"))

            val eventError = expectProviderFailure {
                HttpTransport.postSse(
                    server.url("/event"),
                    emptyMap(),
                    "{}",
                    5,
                    limits = limits(eventChars = 16),
                ) { _, _ -> }
            }
            assertTrue(eventError.message.orEmpty().contains("SSE event exceeded the 16-characters limit"))
        }
    }

    @Test
    fun `SSE total byte limit bounds streams with many small events`() = runBlocking {
        val body = buildString {
            repeat(20) { index -> append("data: ").append(index).append("\n\n") }
        }
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            respond(exchange, 200, body, "text/event-stream")
        }.use { server ->
            val error = expectProviderFailure {
                HttpTransport.postSse(
                    server.url("/many"),
                    emptyMap(),
                    "{}",
                    5,
                    limits = limits(streamBytes = 32),
                ) { _, _ -> }
            }

            assertTrue(error.message.orEmpty().contains("SSE stream exceeded the 32-bytes limit"))
            assertEquals(200, error.statusCode)
            assertTrue(error.billingUncertain)
        }
    }

    @Test
    fun `JSON timeout closes a body that hangs after response headers`() = runBlocking {
        warmUpHttpClient()
        HangingBodyServer("application/json", "{").use { server ->
            val request = async {
                expectProviderFailure {
                    HttpTransport.postJson(server.url, emptyMap(), "{}", timeoutSeconds = 3)
                }
            }
            assertTrue(server.awaitRequestReceived(), "Server did not receive the JSON request")
            assertTrue(server.awaitBodyStarted(), server.bodyStartFailure())

            val error = withTimeout(6_000) { request.await() }

            assertTrue(error.message.orEmpty().contains("timed out after 3 seconds"))
            assertTrue(server.probeClientClosed(), "Timed-out JSON response body was not closed")
        }
    }

    @Test
    fun `JSON cancellation closes a body that hangs after response headers`() = runBlocking {
        warmUpHttpClient()
        HangingBodyServer("application/json", "{").use { server ->
            val request = launch {
                HttpTransport.getJson(server.url, emptyMap(), timeoutSeconds = 30)
            }
            assertTrue(server.awaitRequestReceived(), "Server did not receive the JSON request")
            assertTrue(server.awaitBodyStarted(), server.bodyStartFailure())
            delay(100)

            withTimeout(2_000) { request.cancelAndJoin() }

            assertTrue(server.probeClientClosed(), "Cancelled JSON response body was not closed")
        }
    }

    @Test
    fun `SSE timeout closes a body that hangs after response headers`() = runBlocking {
        warmUpHttpClient()
        HangingBodyServer("text/event-stream", "data: ready\n\n").use { server ->
            val firstEvent = CompletableDeferred<Unit>()
            val request = async {
                expectProviderFailure {
                    HttpTransport.postSse(
                        url = server.url,
                        headers = emptyMap(),
                        body = "{}",
                        timeoutSeconds = 1,
                    ) { _, _ -> firstEvent.complete(Unit) }
                }
            }
            withTimeout(2_000) { firstEvent.await() }

            val error = withTimeout(4_000) { request.await() }

            assertTrue(error.message.orEmpty().contains("timed out after 1 seconds"))
            assertTrue(server.probeClientClosed(), "Timed-out SSE response body was not closed")
        }
    }

    @Test
    fun `SSE cancellation closes a body that hangs after response headers`() = runBlocking {
        warmUpHttpClient()
        HangingBodyServer("text/event-stream", "data: ready\n\n").use { server ->
            val firstEvent = CompletableDeferred<Unit>()
            val request = launch {
                HttpTransport.postSse(
                    url = server.url,
                    headers = emptyMap(),
                    body = "{}",
                    timeoutSeconds = 30,
                ) { _, _ -> firstEvent.complete(Unit) }
            }
            withTimeout(2_000) { firstEvent.await() }

            withTimeout(2_000) { request.cancelAndJoin() }

            assertTrue(server.probeClientClosed(), "Cancelled SSE response body was not closed")
        }
    }

    private suspend fun expectProviderFailure(block: suspend () -> Unit): ProviderException {
        try {
            block()
        } catch (error: ProviderException) {
            return error
        }
        throw AssertionError("Expected ProviderException")
    }

    private suspend fun warmUpHttpClient() {
        withServer { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            respond(exchange, 200, "{}")
        }.use { server ->
            HttpTransport.getJson(server.url("/warm-up"), emptyMap(), timeoutSeconds = 5)
        }
    }

    private fun limits(
        jsonBytes: Int = 256,
        errorBytes: Int = 128,
        lineChars: Int = 128,
        eventChars: Int = 256,
        streamBytes: Int = 2_048,
    ): HttpTransportLimits = HttpTransportLimits(
        jsonResponseBytes = jsonBytes,
        errorResponseBytes = errorBytes,
        sseLineChars = lineChars,
        sseEventChars = eventChars,
        sseStreamBytes = streamBytes,
    )

    private fun withServer(handler: (HttpExchange) -> Unit): TestServer = TestServer(handler)

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> runCatching { output.write(bytes) } }
    }

    private class TestServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

        fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

        override fun close() {
            server.stop(0)
        }
    }

    private class HangingBodyServer(
        private val contentType: String,
        private val initialBody: String,
    ) : AutoCloseable {
        private val bodyStarted = CountDownLatch(1)
        private val requestReceived = CountDownLatch(1)
        private val allowCloseProbe = CountDownLatch(1)
        private val clientClosed = CountDownLatch(1)
        private val handlerDone = CountDownLatch(1)
        private val startFailure = AtomicReference<Throwable?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> serve(exchange) }
            start()
        }

        val url: String
            get() = "http://127.0.0.1:${server.address.port}/hang"

        suspend fun awaitRequestReceived(): Boolean = withContext(Dispatchers.IO) {
            requestReceived.await(5, TimeUnit.SECONDS)
        }

        suspend fun awaitBodyStarted(): Boolean = withContext(Dispatchers.IO) {
            bodyStarted.await(5, TimeUnit.SECONDS)
        }

        fun bodyStartFailure(): String = startFailure.get()?.let { error ->
            "Server could not start the response body: ${error::class.java.simpleName}: ${error.message}"
        } ?: "Server did not start the response body"

        fun probeClientClosed(): Boolean {
            allowCloseProbe.countDown()
            return clientClosed.await(5, TimeUnit.SECONDS)
        }

        private fun serve(exchange: HttpExchange) {
            requestReceived.countDown()
            val output = try {
                exchange.requestBody.use { it.readAllBytes() }
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody
            } catch (error: Throwable) {
                startFailure.set(error)
                handlerDone.countDown()
                return
            }
            try {
                output.write(initialBody.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                bodyStarted.countDown()
                allowCloseProbe.await()

                val probe = ByteArray(16 * 1_024) { 'x'.code.toByte() }
                repeat(4_096) {
                    output.write(probe)
                    output.flush()
                }
            } catch (_: IOException) {
                clientClosed.countDown()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                bodyStarted.countDown()
                runCatching { output.close() }
                handlerDone.countDown()
            }
        }

        override fun close() {
            allowCloseProbe.countDown()
            server.stop(0)
            handlerDone.await(2, TimeUnit.SECONDS)
        }
    }
}
