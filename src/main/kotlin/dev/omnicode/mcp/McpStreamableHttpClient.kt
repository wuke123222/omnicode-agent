package dev.omnicode.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.provider.modelApiProxySelector
import dev.omnicode.util.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** MCP 2025-11-25 Streamable HTTP transport. */
class McpStreamableHttpClient private constructor(
    override val config: McpServerConfig,
    private val endpoint: URI,
    private val bearerToken: String,
    private val httpClient: HttpClient,
    private val timeouts: McpTimeouts,
) : McpClient {
    private val requestMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var negotiatedProtocolVersion: String = PROTOCOL_VERSION

    override suspend fun listTools(): List<McpToolDescriptor> {
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val params = JsonObject().apply { cursor?.let { addProperty("cursor", it) } }
            val result = request("tools/list", params, timeouts.requestMs)
            parseRemotePayload("tools/list") {
                result.getAsJsonArray("tools")?.forEach { item ->
                    val tool = item.asJsonObject
                    val name = tool.get("name")?.asString?.trim().orEmpty()
                    if (name.isEmpty()) return@forEach
                    tools += McpToolDescriptor(
                        name = name,
                        description = safeRemoteText(
                            tool.get("description")?.asString.orEmpty(),
                            MAX_DESCRIPTION_CHARS,
                        ),
                        inputSchema = tool.getAsJsonObject("inputSchema") ?: emptyObjectSchema(),
                    )
                }
                cursor = result.get("nextCursor")?.takeUnless(JsonElement::isJsonNull)?.asString
            }
            if (cursor.isNullOrBlank()) return tools.distinctBy(McpToolDescriptor::name)
        }
        return tools.distinctBy(McpToolDescriptor::name)
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult {
        val result = request(
            "tools/call",
            JsonObject().apply {
                addProperty("name", name)
                add("arguments", arguments.deepCopy())
            },
            timeouts.toolCallMs,
        )
        return parseRemotePayload("tools/call") {
            val textParts = mutableListOf<String>()
            result.getAsJsonArray("content")?.forEach { item ->
                val block = item.asJsonObject
                when (block.get("type")?.asString) {
                    "text" -> block.get("text")?.asString?.let { textParts += safeRemoteText(it, MAX_RESULT_CHARS) }
                    else -> textParts += safeRemoteText(Json.stringify(block), MAX_RESULT_CHARS)
                }
            }
            result.get("structuredContent")?.takeUnless(JsonElement::isJsonNull)?.let {
                textParts += safeRemoteText(Json.stringify(it), MAX_RESULT_CHARS)
            }
            McpToolCallResult(
                text = textParts.joinToString("\n").take(MAX_RESULT_CHARS).ifBlank { "MCP tool completed." },
                isError = result.get("isError")?.asBoolean == true,
            )
        }
    }

    private suspend fun initialize() = requestMutex.withLock {
        runWithTimeout("initialize", timeouts.requestMs) { initializeLocked() }
    }

    private fun initializeLocked() {
        sessionId = null
        negotiatedProtocolVersion = PROTOCOL_VERSION
        val initialize = rpcRequest(
            nextId.getAndIncrement(),
            "initialize",
            JsonObject().apply {
                addProperty("protocolVersion", PROTOCOL_VERSION)
                add("capabilities", JsonObject())
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "OmniCode")
                    addProperty("version", "0.12.0")
                })
            },
        )
        val response = postRequest(initialize, "initialize", initial = true, allowSessionRecovery = false)
        val result = responseResult(response, initialize.get("id").asLong, "initialize")
        result.get("protocolVersion")?.takeUnless(JsonElement::isJsonNull)?.let { version ->
            runCatching { version.asString }.getOrNull()
                ?.takeIf { PROTOCOL_VERSION_PATTERN.matches(it) }
                ?.let { negotiatedProtocolVersion = it }
        }
        postNotification(
            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("method", "notifications/initialized")
                add("params", JsonObject())
            },
            "notifications/initialized",
        )
    }

    private suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonObject =
        requestMutex.withLock {
            runWithTimeout(method, timeoutMs) {
                val id = nextId.getAndIncrement()
                val response = postRequest(
                    rpcRequest(id, method, params),
                    method,
                    initial = false,
                    allowSessionRecovery = true,
                )
                responseResult(response, id, method)
            }
        }

    private suspend fun <T> runWithTimeout(method: String, timeoutMs: Long, block: () -> T): T {
        try {
            return withTimeout(timeoutMs) { withContext(Dispatchers.IO) { block() } }
        } catch (timeout: TimeoutCancellationException) {
            throw McpProtocolException("MCP $method timed out after $timeoutMs ms", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: McpProtocolException) {
            throw error
        } catch (error: Throwable) {
            throw McpProtocolException("MCP $method HTTP transport failed", error)
        }
    }

    private fun postRequest(
        message: JsonObject,
        method: String,
        initial: Boolean,
        allowSessionRecovery: Boolean,
    ): JsonObject {
        ensureOpen()
        val hadSession = sessionId != null
        val response = send(
            requestBuilder(initial = initial)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(message), StandardCharsets.UTF_8))
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Accept", POST_ACCEPT)
                .build(),
        )
        if (response.statusCode() == 404 && hadSession && allowSessionRecovery) {
            discard(response.body())
            initializeLocked()
            return postRequest(message, method, initial = false, allowSessionRecovery = false)
        }
        rejectRedirectOrError(response, method)
        if (initial) captureSessionId(response)
        return try {
            when (responseContentType(response)) {
                JSON_CONTENT_TYPE -> parseJsonBody(response.body(), method)
                SSE_CONTENT_TYPE -> readSseResponse(response.body(), message.get("id").asLong, method)
                else -> {
                    discard(response.body())
                    throw McpProtocolException("MCP $method returned an unsupported Content-Type")
                }
            }
        } catch (_: McpHttpSessionExpiredException) {
            if (!allowSessionRecovery) throw McpProtocolException("MCP $method session expired during SSE resume")
            initializeLocked()
            postRequest(message, method, initial = false, allowSessionRecovery = false)
        }
    }

    private fun postNotification(message: JsonObject, method: String) {
        ensureOpen()
        val response = send(
            requestBuilder(initial = false)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(message), StandardCharsets.UTF_8))
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Accept", POST_ACCEPT)
                .build(),
        )
        response.body().use { body ->
            if (response.statusCode() != 202) {
                val detail = safeRemoteText(
                    readBounded(body).toString(StandardCharsets.UTF_8).trim(),
                    MAX_ERROR_DETAIL_CHARS,
                )
                throw McpProtocolException(
                    "MCP $method notification expected HTTP 202 but received ${response.statusCode()}" +
                        detail.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty(),
                )
            }
        }
    }

    private fun readSseResponse(
        initialBody: InputStream,
        expectedId: Long,
        method: String,
    ): JsonObject {
        var body = initialBody
        var lastEventId: String? = null
        var retryMs = DEFAULT_SSE_RETRY_MS
        repeat(MAX_SSE_RESUME_ATTEMPTS + 1) { attempt ->
            val stream = body.use { readSseStream(it, expectedId, method, lastEventId, retryMs) }
            stream.response?.let { return it }
            lastEventId = stream.lastEventId
            retryMs = stream.retryMs
            if (attempt >= MAX_SSE_RESUME_ATTEMPTS || lastEventId == null) {
                throw McpProtocolException("MCP $method SSE stream ended before its JSON-RPC response")
            }
            if (retryMs > 0) Thread.sleep(retryMs)
            val resumed = send(
                requestBuilder(initial = false)
                    .GET()
                    .header("Accept", SSE_CONTENT_TYPE)
                    .header("Last-Event-ID", lastEventId)
                    .build(),
            )
            if (resumed.statusCode() == 404 && sessionId != null) {
                discard(resumed.body())
                throw McpHttpSessionExpiredException()
            }
            rejectRedirectOrError(resumed, "$method SSE resume")
            if (responseContentType(resumed) != SSE_CONTENT_TYPE) {
                discard(resumed.body())
                throw McpProtocolException("MCP $method SSE resume returned an unsupported Content-Type")
            }
            body = resumed.body()
        }
        throw McpProtocolException("MCP $method SSE stream could not be resumed")
    }

    private fun readSseStream(
        input: InputStream,
        expectedId: Long,
        method: String,
        previousEventId: String?,
        previousRetryMs: Long,
    ): SseStreamResult {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        var totalChars = 0
        var eventId = previousEventId
        var retryMs = previousRetryMs
        val data = mutableListOf<String>()

        fun dispatch(): JsonObject? {
            if (data.isEmpty()) return null
            val payload = data.joinToString("\n")
            data.clear()
            if (payload.isBlank()) return null
            val message = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrElse {
                throw McpProtocolException("MCP $method returned invalid JSON in an SSE event")
            }
            return message.takeIf { responseId(it) == expectedId }
        }

        while (true) {
            val line = readBoundedSseLine(reader, method) ?: break
            totalChars += line.length + 1
            if (totalChars > MAX_HTTP_BODY_BYTES) {
                throw McpProtocolException("MCP $method SSE response exceeded the transport size limit")
            }
            if (line.isEmpty()) {
                dispatch()?.let { return SseStreamResult(it, eventId, retryMs) }
                continue
            }
            if (line.startsWith(':')) continue
            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            val rawValue = if (separator < 0) "" else line.substring(separator + 1)
            val value = rawValue.removePrefix(" ")
            when (field) {
                "data" -> data += value
                "id" -> if (!value.contains('\u0000')) eventId = value
                "retry" -> value.toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?.let { retryMs = it.coerceAtMost(MAX_SSE_RETRY_MS) }
            }
        }
        dispatch()?.let { return SseStreamResult(it, eventId, retryMs) }
        return SseStreamResult(null, eventId, retryMs)
    }

    private fun readBoundedSseLine(reader: BufferedReader, method: String): String? {
        val line = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            val char = next.toChar()
            if (char == '\n') {
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            if (line.length >= MAX_SSE_LINE_CHARS) {
                throw McpProtocolException("MCP $method SSE line exceeded the $MAX_SSE_LINE_CHARS-character limit")
            }
            line.append(char)
        }
    }

    private fun parseJsonBody(body: InputStream, method: String): JsonObject = body.use {
        val bytes = readBounded(it)
        runCatching { JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).asJsonObject }.getOrElse {
            throw McpProtocolException("MCP $method returned invalid JSON")
        }
    }

    private fun responseResult(response: JsonObject, expectedId: Long, method: String): JsonObject =
        parseRemotePayload(method) {
            if (responseId(response) != expectedId) {
                throw McpProtocolException("MCP $method returned a mismatched JSON-RPC response id")
            }
            response.getAsJsonObject("error")?.let { error ->
                val code = error.get("code")?.let { runCatching { it.asInt }.getOrNull() }
                val description = error.get("message")?.let { runCatching { it.asString }.getOrNull() }
                    .orEmpty()
                    .let { safeRemoteText(it, MAX_ERROR_DETAIL_CHARS) }
                throw McpProtocolException("MCP $method failed${code?.let { " ($it)" }.orEmpty()}: $description")
            }
            response.getAsJsonObject("result") ?: JsonObject()
        }

    private fun responseId(response: JsonObject): Long? = response.get("id")
        ?.takeUnless(JsonElement::isJsonNull)
        ?.let { runCatching { it.asLong }.getOrNull() }

    private fun requestBuilder(initial: Boolean): HttpRequest.Builder = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofMillis(maxOf(timeouts.requestMs, timeouts.toolCallMs)))
        .also { builder ->
            if (bearerToken.isNotBlank()) builder.header("Authorization", "Bearer $bearerToken")
            if (!initial) {
                builder.header("MCP-Protocol-Version", negotiatedProtocolVersion)
                sessionId?.let { builder.header("MCP-Session-Id", it) }
            }
        }

    private fun send(request: HttpRequest): HttpResponse<InputStream> = try {
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    }

    private fun captureSessionId(response: HttpResponse<*>) {
        response.headers().firstValue(SESSION_HEADER).orElse(null)?.let { value ->
            if (value.isEmpty() || value.any { it.code !in 0x21..0x7e }) {
                throw McpProtocolException("MCP initialize returned an invalid MCP-Session-Id header")
            }
            sessionId = value
        }
    }

    private fun rejectRedirectOrError(response: HttpResponse<InputStream>, method: String) {
        val status = response.statusCode()
        if (status in 300..399) {
            discard(response.body())
            throw McpProtocolException("MCP $method refused HTTP redirect status $status")
        }
        if (status == 401 || status == 403) {
            val challenges = response.headers()
                .allValues("WWW-Authenticate")
                .take(MAX_AUTH_CHALLENGE_HEADERS)
                .map { safeRemoteText(it, MAX_AUTH_CHALLENGE_CHARS) }
            discard(response.body())
            throw McpHttpAuthorizationChallengeException(status, challenges)
        }
        if (status !in 200..299) {
            val detail = response.body().use {
                safeRemoteText(
                    readBounded(it).toString(StandardCharsets.UTF_8).trim(),
                    MAX_ERROR_DETAIL_CHARS,
                )
            }
            throw McpProtocolException(
                "MCP $method returned HTTP $status" + detail.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty(),
            )
        }
    }

    private fun responseContentType(response: HttpResponse<*>): String = response.headers()
        .firstValue("Content-Type")
        .orElse("")
        .substringBefore(';')
        .trim()
        .lowercase(Locale.ROOT)

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readNBytes(MAX_HTTP_BODY_BYTES + 1)
        if (bytes.size > MAX_HTTP_BODY_BYTES) {
            throw McpProtocolException("MCP HTTP response exceeded the $MAX_HTTP_BODY_BYTES-byte limit")
        }
        return bytes
    }

    private fun discard(input: InputStream) {
        runCatching { input.close() }
    }

    private fun ensureOpen() {
        if (closed.get()) throw McpProtocolException("MCP HTTP client '${config.name}' is closed")
    }

    private fun safeRemoteText(value: String, maxChars: Int): String {
        val redacted = if (bearerToken.isBlank()) value else value.replace(bearerToken, "[REDACTED]")
        return redacted.take(maxChars)
    }

    private inline fun <T> parseRemotePayload(method: String, block: () -> T): T = try {
        block()
    } catch (error: McpProtocolException) {
        throw error
    } catch (_: Throwable) {
        // Gson type errors can include attacker-controlled values in their messages. Do not
        // preserve those exceptions as causes because they can later reach IDE logs.
        throw McpProtocolException("MCP $method returned an invalid response payload")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (sessionId == null) return
        runCatching {
            val response = send(
                requestBuilder(initial = false)
                    .timeout(Duration.ofMillis(CLOSE_TIMEOUT_MS))
                    .DELETE()
                    .header("Accept", POST_ACCEPT)
                    .build(),
            )
            response.body().close()
            if (response.statusCode() in 300..399) {
                throw McpProtocolException("MCP session DELETE refused HTTP redirect status ${response.statusCode()}")
            }
            if (response.statusCode() !in 200..299 && response.statusCode() != 405) {
                throw McpProtocolException("MCP session DELETE returned HTTP ${response.statusCode()}")
            }
        }
        sessionId = null
    }

    companion object {
        suspend fun connect(config: McpServerConfig, bearerToken: String = ""): McpStreamableHttpClient =
            connect(config, bearerToken, defaultHttpClient(), McpTimeouts())

        internal suspend fun connect(
            config: McpServerConfig,
            bearerToken: String,
            httpClient: HttpClient,
            timeouts: McpTimeouts,
        ): McpStreamableHttpClient {
            val endpoint = validateMcpHttpEndpoint(config.url)
            return McpStreamableHttpClient(config, endpoint, bearerToken, httpClient, timeouts).also { client ->
                try {
                    client.initialize()
                } catch (error: Throwable) {
                    client.close()
                    throw error
                }
            }
        }

        private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(modelApiProxySelector())
            .build()

        private fun rpcRequest(id: Long, method: String, params: JsonObject): JsonObject = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }

        private fun emptyObjectSchema(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
        }

        private const val PROTOCOL_VERSION = "2025-11-25"
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val SSE_CONTENT_TYPE = "text/event-stream"
        private const val POST_ACCEPT = "$JSON_CONTENT_TYPE, $SSE_CONTENT_TYPE"
        private const val SESSION_HEADER = "MCP-Session-Id"
        private const val MAX_PAGES = 10
        private const val MAX_DESCRIPTION_CHARS = 1_000
        private const val MAX_RESULT_CHARS = 24_000
        private const val MAX_ERROR_DETAIL_CHARS = 300
        private const val MAX_AUTH_CHALLENGE_HEADERS = 16
        private const val MAX_AUTH_CHALLENGE_CHARS = 8_192
        private const val MAX_HTTP_BODY_BYTES = 2 * 1_024 * 1_024
        private const val MAX_SSE_LINE_CHARS = 1_048_576
        private const val MAX_SSE_RESUME_ATTEMPTS = 3
        private const val DEFAULT_SSE_RETRY_MS = 1_000L
        private const val MAX_SSE_RETRY_MS = 30_000L
        private const val CLOSE_TIMEOUT_MS = 3_000L
        private val PROTOCOL_VERSION_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    }
}

private data class SseStreamResult(
    val response: JsonObject?,
    val lastEventId: String?,
    val retryMs: Long,
)

private class McpHttpSessionExpiredException : RuntimeException()

internal fun validateMcpHttpEndpoint(value: String): URI {
    val endpoint = runCatching { URI(value.trim()) }.getOrElse {
        throw IllegalArgumentException("MCP HTTP endpoint is not a valid URL", it)
    }
    require(endpoint.isAbsolute && endpoint.host != null) { "MCP HTTP endpoint must be an absolute URL" }
    require(endpoint.rawUserInfo == null) { "MCP HTTP endpoint must not contain embedded credentials" }
    require(endpoint.rawFragment == null) { "MCP HTTP endpoint must not contain a fragment" }
    val scheme = endpoint.scheme.lowercase(Locale.ROOT)
    require(scheme == "https" || scheme == "http") { "MCP HTTP endpoint must use HTTPS" }
    if (scheme == "http") {
        require(isLiteralLoopbackHost(endpoint.host)) {
            "Remote MCP HTTP endpoints must use HTTPS; plain HTTP is allowed only for loopback"
        }
    }
    return endpoint
}

internal fun isLiteralLoopbackHost(value: String): Boolean {
    val host = value.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
    if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
    val octets = host.split('.')
    return octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 } && octets.first() == "127"
}
