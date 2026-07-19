package dev.omnicode.mcp.oauth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class McpOAuthAuthorizationCallback(
    val code: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * One-shot OAuth callback bound exclusively to IPv4 loopback.
 *
 * A random path prevents unrelated localhost pages from targeting the callback, while the
 * caller-provided state is checked before a result is accepted. Invalid requests never consume
 * the pending authorization attempt.
 */
internal class McpOAuthLoopbackCallback private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val expectedState: String,
    callbackPath: String,
) : AutoCloseable {
    private val completion = CompletableDeferred<McpOAuthAuthorizationCallback>()

    val redirectUri: URI = URI(
        "http",
        null,
        LOOPBACK_HOST,
        server.address.port,
        callbackPath,
        null,
        null,
    )

    init {
        server.createContext(callbackPath, ::handle)
        server.executor = executor
        server.start()
    }

    suspend fun await(timeout: Duration = DEFAULT_TIMEOUT): McpOAuthAuthorizationCallback =
        withTimeout(timeout.toMillis()) { completion.await() }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" || exchange.requestURI.rawQuery.orEmpty().length > MAX_QUERY_CHARS) {
                exchange.respond(400, FAILURE_PAGE)
                return
            }
            val expectedHost = "$LOOPBACK_HOST:${server.address.port}"
            if (!exchange.requestHeaders.getFirst("Host").orEmpty().equals(expectedHost, ignoreCase = true)) {
                exchange.respond(400, FAILURE_PAGE)
                return
            }
            val parameters = parseQuery(exchange.requestURI.rawQuery.orEmpty()) ?: run {
                exchange.respond(400, FAILURE_PAGE)
                return
            }
            val state = parameters.singleValue("state")
            if (state == null || !constantTimeEquals(state, expectedState)) {
                exchange.respond(400, FAILURE_PAGE)
                return
            }
            val code = parameters.singleValue("code")?.bounded(MAX_CODE_CHARS)
            val error = parameters.singleValue("error")?.bounded(MAX_ERROR_CHARS)
            val description = parameters.singleValue("error_description")?.bounded(MAX_DESCRIPTION_CHARS)
            if ((code == null) == (error == null)) {
                exchange.respond(400, FAILURE_PAGE)
                return
            }
            if (completion.isCompleted) {
                exchange.respond(409, FAILURE_PAGE)
                return
            }
            val result = McpOAuthAuthorizationCallback(code, error, description)
            exchange.respond(200, SUCCESS_PAGE)
            completion.complete(result)
        } catch (_: Throwable) {
            runCatching { exchange.respond(500, FAILURE_PAGE) }
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        completion.cancel()
        runCatching { server.stop(0) }
        executor.shutdownNow()
    }

    companion object {
        fun start(expectedState: String): McpOAuthLoopbackCallback {
            require(expectedState.length in 32..512) { "OAuth state must be a bounded high-entropy value" }
            val address = InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0)
            val server = HttpServer.create(address, 0)
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "OmniCode OAuth callback").apply { isDaemon = true }
            }
            val randomPath = "/omnicode/oauth/callback/${randomUrlToken(24)}"
            return try {
                McpOAuthLoopbackCallback(server, executor, expectedState, randomPath)
            } catch (error: Throwable) {
                executor.shutdownNow()
                runCatching { server.stop(0) }
                throw error
            }
        }

        private fun randomUrlToken(bytes: Int): String = ByteArray(bytes)
            .also(SECURE_RANDOM::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        private val SECURE_RANDOM = SecureRandom()
        private val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(3)
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MAX_QUERY_CHARS = 16_384
        private const val MAX_CODE_CHARS = 4_096
        private const val MAX_ERROR_CHARS = 128
        private const val MAX_DESCRIPTION_CHARS = 512
        private const val SUCCESS_PAGE = "<!doctype html><meta charset=utf-8><title>OmniCode</title>Authorization complete. You can return to the IDE."
        private const val FAILURE_PAGE = "<!doctype html><meta charset=utf-8><title>OmniCode</title>Authorization was not accepted. Return to the IDE and try again."
    }
}

private fun parseQuery(rawQuery: String): Map<String, List<String>>? {
    if (rawQuery.isBlank()) return emptyMap()
    val result = linkedMapOf<String, MutableList<String>>()
    rawQuery.split('&').forEach { pair ->
        val separator = pair.indexOf('=')
        val rawName = if (separator < 0) pair else pair.substring(0, separator)
        val rawValue = if (separator < 0) "" else pair.substring(separator + 1)
        val name = decodeQueryPart(rawName) ?: return null
        val value = decodeQueryPart(rawValue) ?: return null
        if (name.length > 128 || value.length > 8_192) return null
        result.getOrPut(name) { mutableListOf() } += value
    }
    return result
}

private fun decodeQueryPart(value: String): String? = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8)
}.getOrNull()?.takeUnless { decoded -> decoded.any { it == '\u0000' || it == '\r' || it == '\n' } }

private fun Map<String, List<String>>.singleValue(name: String): String? =
    get(name)?.takeIf { it.size == 1 }?.single()?.takeIf(String::isNotBlank)

private fun String.bounded(maxChars: Int): String? = takeIf { length <= maxChars }

private fun constantTimeEquals(left: String, right: String): Boolean {
    val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
    val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
    var difference = leftBytes.size xor rightBytes.size
    val length = maxOf(leftBytes.size, rightBytes.size)
    for (index in 0 until length) {
        val leftByte = leftBytes.getOrElse(index) { 0 }
        val rightByte = rightBytes.getOrElse(index) { 0 }
        difference = difference or (leftByte.toInt() xor rightByte.toInt())
    }
    return difference == 0
}

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    responseHeaders.set("Cache-Control", "no-store")
    responseHeaders.set("Pragma", "no-cache")
    responseHeaders.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
    responseHeaders.set("Referrer-Policy", "no-referrer")
    responseHeaders.set("X-Content-Type-Options", "nosniff")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
