package dev.omnicode.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpResult(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

internal data class HttpTransportLimits(
    val jsonResponseBytes: Int = 16 * 1_048_576,
    val errorResponseBytes: Int = 256 * 1_024,
    val sseLineChars: Int = 1_048_576,
    val sseEventChars: Int = 4 * 1_048_576,
    val sseStreamBytes: Int = 64 * 1_048_576,
) {
    init {
        require(jsonResponseBytes > 0)
        require(errorResponseBytes > 0)
        require(sseLineChars > 0)
        require(sseEventChars > 0)
        require(sseStreamBytes > 0)
    }
}

internal object HttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        // Provider requests frequently carry API keys. Never let the JDK replay those headers to
        // a redirect target; callers must explicitly configure the final provider endpoint.
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(modelApiProxySelector())
        .build()

    suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String> = emptyList(),
        limits: HttpTransportLimits = HttpTransportLimits(),
    ): HttpResult = withRequestTimeout(timeoutSeconds) {
        withContext(Dispatchers.IO) {
            val request = requestBuilder(url, headers + ("Accept" to "application/json"), timeoutSeconds)
                .GET()
                .build()
            val response = send(
                request,
                HttpResponse.BodyHandlers.ofInputStream(),
                headers.values + sensitiveValues,
            )
            val responseBody = readResponseBody(response, limits)
            requireSuccess(
                response.statusCode(),
                responseBody,
                response.headers().map(),
                headers.values + sensitiveValues,
            )
            HttpResult(response.statusCode(), responseBody, response.headers().map())
        }
    }

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String> = emptyList(),
        limits: HttpTransportLimits = HttpTransportLimits(),
    ): HttpResult = withRequestTimeout(timeoutSeconds) {
        withContext(Dispatchers.IO) {
            val request = requestBuilder(url, headers, timeoutSeconds)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            val response = send(
                request,
                HttpResponse.BodyHandlers.ofInputStream(),
                headers.values + sensitiveValues,
            )
            val responseBody = readResponseBody(response, limits)
            requireSuccess(
                response.statusCode(),
                responseBody,
                response.headers().map(),
                headers.values + sensitiveValues,
            )
            HttpResult(response.statusCode(), responseBody, response.headers().map())
        }
    }

    suspend fun postSse(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String> = emptyList(),
        limits: HttpTransportLimits = HttpTransportLimits(),
        onEvent: suspend (event: String?, data: String) -> Unit,
    ): Map<String, List<String>> = withRequestTimeout(timeoutSeconds) {
        withContext(Dispatchers.IO) {
            val request = requestBuilder(url, headers + ("Accept" to "text/event-stream"), timeoutSeconds)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            val response = send(
                request,
                HttpResponse.BodyHandlers.ofInputStream(),
                headers.values + sensitiveValues,
            )
            if (response.statusCode() !in 200..299) {
                val error = readUtf8Body(
                    response = response,
                    maxBytes = limits.errorResponseBytes,
                    component = "error response",
                )
                throw providerHttpException(
                    statusCode = response.statusCode(),
                    body = error,
                    responseHeaders = response.headers().map(),
                    sensitiveValues = headers.values + sensitiveValues,
                )
            }

            consumeSseBody(response, limits, onEvent)
            response.headers().map()
        }
    }

    private suspend fun <T> send(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<T>,
        sensitiveValues: Collection<String>,
    ): HttpResponse<T> {
        val future = client.sendAsync(request, bodyHandler)
        return try {
            future.awaitCancellable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val cause = (error as? CompletionException)?.cause ?: error
            val detail = sanitizeProviderText(
                cause.message?.lineSequence()?.firstOrNull()?.take(300),
                sensitiveValues,
            ) ?: cause::class.java.simpleName
            throw ProviderException(
                "Model API network request failed: $detail",
                cause = cause,
                networkFailure = true,
            )
        }
    }

    private suspend fun readResponseBody(
        response: HttpResponse<InputStream>,
        limits: HttpTransportLimits,
    ): String = readUtf8Body(
        response = response,
        maxBytes = if (response.statusCode() in 200..299) {
            limits.jsonResponseBytes
        } else {
            limits.errorResponseBytes
        },
        component = if (response.statusCode() in 200..299) "JSON response" else "error response",
    )

    private suspend fun readUtf8Body(
        response: HttpResponse<InputStream>,
        maxBytes: Int,
        component: String,
    ): String = try {
        consumeBody(response.body()) { source ->
            LimitedInputStream(source, maxBytes, component).use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, READ_BUFFER_BYTES))
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toString(StandardCharsets.UTF_8)
            }
        }
    } catch (error: TransportLimitExceededException) {
        throw limitException(response.statusCode(), response.headers().map(), error)
    }

    private suspend fun consumeSseBody(
        response: HttpResponse<InputStream>,
        limits: HttpTransportLimits,
        onEvent: suspend (event: String?, data: String) -> Unit,
    ) {
        val body = response.body()
        val lines = Channel<String>(SSE_LINE_QUEUE_CAPACITY)
        val worker = Thread.ofVirtual().name(BODY_READER_THREAD_NAME).unstarted {
            try {
                LimitedInputStream(
                    source = body,
                    maxBytes = limits.sseStreamBytes,
                    component = "SSE stream",
                ).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        val line = readBoundedLine(reader, limits.sseLineChars) ?: break
                        runBlocking { lines.send(line) }
                    }
                }
                lines.close()
            } catch (error: Throwable) {
                lines.close(error)
            }
        }
        worker.start()

        try {
            var event: String? = null
            val data = StringBuilder()

            suspend fun flush() {
                if (data.isNotEmpty()) {
                    onEvent(event, data.toString())
                    data.setLength(0)
                }
                event = null
            }

            for (line in lines) {
                coroutineContext.ensureActive()
                when {
                    line.isEmpty() -> flush()
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("data:") -> appendEventData(
                        target = data,
                        value = line.substringAfter(':').trimStart(),
                        maxChars = limits.sseEventChars,
                    )
                }
            }
            flush()
        } catch (error: TransportLimitExceededException) {
            throw limitException(response.statusCode(), response.headers().map(), error)
        } finally {
            lines.cancel()
            runCatching { body.close() }
            worker.interrupt()
        }
    }

    private suspend fun <T : Any> consumeBody(
        body: InputStream,
        read: (InputStream) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val worker = Thread.ofVirtual().name(BODY_READER_THREAD_NAME).unstarted {
            try {
                val value = body.use(read)
                continuation.resume(value)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            runCatching { body.close() }
            worker.interrupt()
        }
        worker.start()
    }

    private suspend fun <T : Any> withRequestTimeout(
        timeoutSeconds: Long,
        block: suspend () -> T,
    ): T {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        val timeoutMillis = if (timeoutSeconds > Long.MAX_VALUE / 1_000L) {
            Long.MAX_VALUE
        } else {
            timeoutSeconds * 1_000L
        }
        return withTimeoutOrNull(timeoutMillis) { block() }
            ?: throw ProviderException(
                "Model API request timed out after $timeoutSeconds seconds",
                networkFailure = true,
            )
    }

    private fun requireSuccess(
        statusCode: Int,
        body: String,
        responseHeaders: Map<String, List<String>>,
        sensitiveValues: Collection<String>,
    ) {
        if (statusCode !in 200..299) {
            throw providerHttpException(
                statusCode = statusCode,
                body = body,
                responseHeaders = responseHeaders,
                sensitiveValues = sensitiveValues,
            )
        }
    }

    private fun readBoundedLine(reader: Reader, maxChars: Int): String? {
        val line = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            val char = next.toChar()
            if (char == '\n') {
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            if (line.length >= maxChars) {
                throw TransportLimitExceededException("SSE line", maxChars, "characters")
            }
            line.append(char)
        }
    }

    private fun appendEventData(target: StringBuilder, value: String, maxChars: Int) {
        val separatorChars = if (target.isEmpty()) 0 else 1
        if (target.length.toLong() + separatorChars + value.length > maxChars) {
            throw TransportLimitExceededException("SSE event", maxChars, "characters")
        }
        if (separatorChars == 1) target.append('\n')
        target.append(value)
    }

    private fun limitException(
        statusCode: Int,
        responseHeaders: Map<String, List<String>>,
        error: TransportLimitExceededException,
    ): ProviderException =
        ProviderException(
            "Model API ${error.component} exceeded the ${error.limit}-${error.unit} limit",
            statusCode = statusCode,
            cause = error,
            requestId = safeProviderRequestId(responseHeaders),
        )

    private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }

    private fun requestBuilder(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): HttpRequest.Builder {
        modelApiEndpointValidationError(url)?.let { message ->
            throw ProviderException(message)
        }
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
        val mergedHeaders = linkedMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "OmniCode-Agent/0.10.0",
        )
        headers.forEach { (key, value) ->
            val existing = mergedHeaders.keys.firstOrNull { it.equals(key, ignoreCase = true) }
            if (existing != null) mergedHeaders.remove(existing)
            mergedHeaders[key] = value
        }
        mergedHeaders.forEach { (key, value) -> builder.header(key, value) }
        return builder
    }

    private const val READ_BUFFER_BYTES = 8 * 1_024
    private const val SSE_LINE_QUEUE_CAPACITY = 16
    private const val BODY_READER_THREAD_NAME = "OmniCode HTTP body reader"
}

private fun providerHttpException(
    statusCode: Int,
    body: String,
    responseHeaders: Map<String, List<String>>,
    sensitiveValues: Collection<String>,
): ProviderException = ProviderException(
    message = "Model API returned HTTP $statusCode",
    statusCode = statusCode,
    responseBody = sanitizeProviderText(body, sensitiveValues)?.take(20_000),
    retryAfterMillis = retryAfterMillis(responseHeaders),
    requestId = safeProviderRequestId(responseHeaders, sensitiveValues),
)

internal fun retryAfterMillis(
    headers: Map<String, List<String>>,
    now: Instant = Instant.now(),
): Long? {
    val raw = firstHeader(headers, "retry-after")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val seconds = raw.toLongOrNull()
    val millis = if (seconds != null) {
        if (seconds <= 0) 0L else seconds.coerceAtMost(MAX_RETRY_AFTER_MILLIS / 1_000L) * 1_000L
    } else {
        val retryAt = runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull() ?: return null
        runCatching { java.time.Duration.between(now, retryAt).toMillis() }
            .getOrDefault(MAX_RETRY_AFTER_MILLIS)
            .coerceAtLeast(0L)
    }
    return millis.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
}

internal fun safeProviderRequestId(
    headers: Map<String, List<String>>,
    sensitiveValues: Collection<String> = emptyList(),
): String? {
    val raw = REQUEST_ID_HEADERS.firstNotNullOfOrNull { name -> firstHeader(headers, name) }
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val sanitized = sanitizeProviderText(raw, sensitiveValues)?.take(MAX_PROVIDER_REQUEST_ID_CHARS) ?: return null
    if (sanitized.contains("[REDACTED]")) return null
    return sanitized.takeIf { value -> value.all(::isSafeRequestIdCharacter) }
}

private fun firstHeader(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

private fun isSafeRequestIdCharacter(value: Char): Boolean =
    value.isLetterOrDigit() || value == '-' || value == '_' || value == '.' || value == ':' || value == '/'

private val REQUEST_ID_HEADERS = listOf(
    "x-request-id",
    "request-id",
    "x-amzn-requestid",
    "x-goog-request-id",
)
private const val MAX_PROVIDER_REQUEST_ID_CHARS = 256
private const val MAX_RETRY_AFTER_MILLIS = 10 * 60 * 1_000L

/**
 * Returns a stable scheme/host/port identity for a provider URL. Paths are intentionally omitted:
 * changing `/v1` to `/v2` stays on the same credential origin, while a scheme, host, or port
 * change requires an explicit credential rebind.
 */
internal fun canonicalModelApiOrigin(value: String): String {
    val raw = value.trim()
    require(raw.isNotEmpty()) { "Base URL 不能为空。" }

    // Bedrock presets retain this placeholder until request construction. Replacing only this
    // known token lets URI perform strict authority parsing without accepting arbitrary templates.
    val parseable = raw.replace(REGION_PLACEHOLDER, REGION_PLACEHOLDER_HOST)
    require(!parseable.contains('{') && !parseable.contains('}')) {
        "Base URL 包含不支持的地址占位符。"
    }
    val uri = runCatching { URI(parseable) }.getOrElse {
        throw IllegalArgumentException("Base URL 格式无效。", it)
    }
    val scheme = uri.scheme?.lowercase().orEmpty()
    require(scheme == "https" || scheme == "http") {
        "Base URL 必须以 https:// 开头；本机回环地址可使用 http://。"
    }
    require(uri.rawUserInfo == null) { "Base URL 不能包含用户名或密码。" }
    require(uri.rawFragment == null) { "Base URL 不能包含 #fragment。" }
    val parsedHost = uri.host?.lowercase().orEmpty()
    require(parsedHost.isNotEmpty()) { "Base URL 必须包含有效主机名。" }
    val host = parsedHost.replace(REGION_PLACEHOLDER_HOST, REGION_PLACEHOLDER)
    require(scheme == "https" || isLoopbackModelApiHost(host)) {
        "远程 Base URL 必须使用 HTTPS；只有 localhost 或回环 IP 可以使用 HTTP。"
    }
    val port = uri.port
    require(port == -1 || port in 1..65_535) { "Base URL 端口无效。" }
    val renderedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    val renderedPort = when {
        port == -1 -> ""
        scheme == "https" && port == 443 -> ""
        scheme == "http" && port == 80 -> ""
        else -> ":$port"
    }
    return "$scheme://$renderedHost$renderedPort"
}

internal fun modelApiEndpointValidationError(value: String): String? = try {
    canonicalModelApiOrigin(value)
    null
} catch (error: IllegalArgumentException) {
    error.message ?: "Base URL 格式无效。"
}

internal fun isLoopbackModelApiOrigin(origin: String): Boolean = runCatching {
    val uri = URI(origin)
    isLoopbackModelApiHost(uri.host.orEmpty())
}.getOrDefault(false)

private fun isLoopbackModelApiHost(value: String): Boolean {
    val host = value.trim().removePrefix("[").removeSuffix("]").removeSuffix(".").lowercase()
    if (host == "localhost" || host.endsWith(".localhost")) return true
    if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
    val octets = host.split('.')
    return octets.size == 4 && octets.all { part ->
        val number = part.toIntOrNull()
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && number != null && number in 0..255
    } && octets.first() == "127"
}

private const val REGION_PLACEHOLDER = "{region}"
private const val REGION_PLACEHOLDER_HOST = "omnicode-region-placeholder"

/**
 * HttpClient needs an explicit proxy selector. Prefer the JVM's HTTP(S) proxy properties because
 * IntelliJ can replace the default selector with one that does not expose the system proxy.
 * Fall back to that selector when no JVM proxy is configured (for example, IDE-only settings).
 */
internal fun modelApiProxySelector(
    systemSelector: ProxySelector? = ProxySelector.getDefault(),
    property: (String) -> String? = System::getProperty,
): ProxySelector = proxySelectorFromProperties(property)
    ?: systemSelector?.let(::LoopbackBypassProxySelector)
    ?: ProxySelector.of(null)

private fun proxySelectorFromProperties(property: (String) -> String?): ProxySelector? {
    val httpsHost = property("https.proxyHost")?.trim().orEmpty()
    val httpHost = property("http.proxyHost")?.trim().orEmpty()
    val host = httpsHost.ifBlank { httpHost }
    if (host.isBlank()) return null

    val portProperty = if (httpsHost.isNotBlank()) "https.proxyPort" else "http.proxyPort"
    val defaultPort = if (httpsHost.isNotBlank()) 443 else 80
    val port = property(portProperty)?.trim()?.toIntOrNull() ?: defaultPort
    if (port !in 1..65_535) return null

    val excludedHosts = sequenceOf(property("http.nonProxyHosts"), property("https.nonProxyHosts"))
        .filterNotNull()
        .flatMap { it.split('|').asSequence() }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    return SystemPropertyProxySelector(host, port, excludedHosts)
}

private class SystemPropertyProxySelector(
    host: String,
    port: Int,
    private val excludedHosts: Set<String>,
) : ProxySelector() {
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    override fun select(uri: URI): List<Proxy> {
        val targetHost = uri.host?.lowercase()
        return if (targetHost != null &&
            (isLiteralLoopbackHost(targetHost) || excludedHosts.any { pattern -> pattern.matchesProxyHost(targetHost) })
        ) {
            listOf(Proxy.NO_PROXY)
        } else {
            listOf(proxy)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

private class LoopbackBypassProxySelector(
    private val delegate: ProxySelector,
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = if (uri.host?.let(::isLiteralLoopbackHost) == true) {
        listOf(Proxy.NO_PROXY)
    } else {
        delegate.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        delegate.connectFailed(uri, sa, ioe)
    }
}

private fun isLiteralLoopbackHost(value: String): Boolean {
    val host = value.removePrefix("[").removeSuffix("]").lowercase()
    if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
    val octets = host.split('.')
    return octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 } && octets.first() == "127"
}

private fun String.matchesProxyHost(host: String): Boolean = when {
    this == "*" -> true
    startsWith("*.") -> host == removePrefix("*.") || host.endsWith(substring(1))
    startsWith('*') -> host.endsWith(removePrefix("*"))
    endsWith('*') -> host.startsWith(removeSuffix("*"))
    else -> host == this
}

private class LimitedInputStream(
    source: InputStream,
    private val maxBytes: Int,
    private val component: String,
) : FilterInputStream(source) {
    private var consumed = 0

    override fun read(): Int {
        val value = super.read()
        if (value < 0) return value
        if (consumed >= maxBytes) throw TransportLimitExceededException(component, maxBytes, "bytes")
        consumed++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = maxBytes - consumed
        val requested = minOf(length, remaining + 1)
        val read = super.read(buffer, offset, requested)
        if (read < 0) return read
        if (read > remaining) throw TransportLimitExceededException(component, maxBytes, "bytes")
        consumed += read
        return read
    }
}

private class TransportLimitExceededException(
    val component: String,
    val limit: Int,
    val unit: String,
) : IOException("$component exceeded the $limit-$unit limit")
