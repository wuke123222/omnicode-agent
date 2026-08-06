package dev.omnicode.provider

import dev.omnicode.OMNICODE_PROVIDER_USER_AGENT
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
import javax.net.ssl.SSLException
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
    private val clientLock = Any()
    @Volatile
    private var client: HttpClient? = null
    @Volatile
    private var clientSnapshot: ProxyClientSnapshot? = null

    private data class ProxyClientSnapshot(
        val httpsHost: String?,
        val httpsPort: String?,
        val httpHost: String?,
        val httpPort: String?,
        val nonProxyHosts: String?,
        val defaultSelectorIdentity: Int,
        val proxyMode: ProviderProxyMode,
    )

    private fun currentClient(proxyMode: ProviderProxyMode = ProviderProxyMode.SYSTEM): HttpClient {
        val defaultSelector = ProxySelector.getDefault()
        val snapshot = ProxyClientSnapshot(
            httpsHost = safeSystemProperty("https.proxyHost"),
            httpsPort = safeSystemProperty("https.proxyPort"),
            httpHost = safeSystemProperty("http.proxyHost"),
            httpPort = safeSystemProperty("http.proxyPort"),
            nonProxyHosts = listOf(
                safeSystemProperty("http.nonProxyHosts"),
                safeSystemProperty("https.nonProxyHosts"),
            ).joinToString("|") { it.orEmpty() },
            defaultSelectorIdentity = defaultSelector?.let { System.identityHashCode(it) } ?: 0,
            proxyMode = proxyMode,
        )
        client?.takeIf { clientSnapshot == snapshot }?.let { return it }

        return synchronized(clientLock) {
            client?.takeIf { clientSnapshot == snapshot } ?: HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                // Provider requests frequently carry API keys. Never let the JDK replay those
                // headers to a redirect target; callers must explicitly configure the final
                // provider endpoint.
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(
                    if (proxyMode == ProviderProxyMode.DIRECT) ProxySelector.of(null)
                    else modelApiProxySelector(defaultSelector),
                )
                .build()
                .also {
                    clientSnapshot = snapshot
                    client = it
                }
        }
    }

    private fun safeSystemProperty(name: String): String? =
        runCatching { System.getProperty(name) }.getOrNull()

    suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String> = emptyList(),
        proxyMode: ProviderProxyMode = ProviderProxyMode.SYSTEM,
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
                proxyMode,
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
        proxyMode: ProviderProxyMode = ProviderProxyMode.SYSTEM,
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
                proxyMode,
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
        firstTokenTimeoutSeconds: Long = 30,
        proxyMode: ProviderProxyMode = ProviderProxyMode.SYSTEM,
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
                proxyMode,
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

            consumeSseBody(response, limits, firstTokenTimeoutSeconds, onEvent)
            response.headers().map()
        }
    }

    private suspend fun <T> send(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<T>,
        sensitiveValues: Collection<String>,
        proxyMode: ProviderProxyMode,
    ): HttpResponse<T> {
        val future = currentClient(proxyMode).sendAsync(request, bodyHandler)
        return try {
            future.awaitCancellable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val cause = (error as? CompletionException)?.cause ?: error
            val detail = networkFailureDetail(cause, sensitiveValues)
            throw ProviderException(
                "Model API network request failed: $detail",
                cause = cause,
                networkFailure = true,
                retryableOverride = if (cause is SSLException || hasSslCause(cause)) false else null,
            )
        }
    }

    /**
     * Keep transport failures actionable without exposing provider response data or credentials.
     * A TLS handshake is different from an HTTP error: the request did not reach the provider,
     * so retrying blindly is usually unhelpful and the user should inspect endpoint/proxy trust.
     */
    internal fun networkFailureDetail(
        cause: Throwable,
        sensitiveValues: Collection<String> = emptyList(),
    ): String {
        val detail = sanitizeProviderText(
            cause.message?.lineSequence()?.firstOrNull()?.take(300),
            sensitiveValues,
        ) ?: cause::class.java.simpleName
        return if (cause is SSLException || hasSslCause(cause)) {
            "TLS handshake failed: $detail. Check the HTTPS endpoint, proxy/VPN TLS interception, and certificate trust; then run connection diagnostics."
        } else {
            detail
        }
    }

    private fun hasSslCause(cause: Throwable): Boolean {
        var current: Throwable? = cause.cause
        while (current != null) {
            if (current is SSLException) return true
            current = current.cause
        }
        return false
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
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        if (!isStreamClosure(error)) throw error
        throw ProviderException(
            "Model API $component stream closed unexpectedly",
            statusCode = response.statusCode(),
            cause = error,
            networkFailure = true,
        )
    }

    private suspend fun consumeSseBody(
        response: HttpResponse<InputStream>,
        limits: HttpTransportLimits,
        firstTokenTimeoutSeconds: Long,
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
            var firstTokenPending = true
            val firstTokenDeadline = System.nanoTime() + firstTokenTimeoutSeconds.coerceAtLeast(1L) * 1_000_000_000L

            suspend fun flush() {
                if (data.isNotEmpty()) {
                    onEvent(event, data.toString())
                    data.setLength(0)
                }
                event = null
            }

            while (true) {
                coroutineContext.ensureActive()
                val received = if (firstTokenPending) {
                    val remainingNanos = firstTokenDeadline - System.nanoTime()
                    if (remainingNanos <= 0L) {
                        throw ProviderException(
                            "Model API first token timed out after ${firstTokenTimeoutSeconds.coerceAtLeast(1L)} seconds",
                            statusCode = response.statusCode(),
                            networkFailure = true,
                        )
                    }
                    withTimeoutOrNull((remainingNanos / 1_000_000L).coerceAtLeast(1L)) {
                        lines.receiveCatching()
                    } ?: throw ProviderException(
                        "Model API first token timed out after ${firstTokenTimeoutSeconds.coerceAtLeast(1L)} seconds",
                        statusCode = response.statusCode(),
                        networkFailure = true,
                    )
                } else {
                    lines.receiveCatching()
                }
                if (received.isClosed) {
                    received.exceptionOrNull()?.let { throw it }
                    break
                }
                val line = received.getOrNull() ?: break
                when {
                    line.isEmpty() -> flush()
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("data:") -> appendEventData(
                        target = data,
                        value = line.substringAfter(':').trimStart(),
                        maxChars = limits.sseEventChars,
                    )
                }
                if (data.isEmpty() && event == null) {
                    // A flushed SSE event is the first usable provider progress signal.
                    // Comment/heartbeat lines do not reset the absolute deadline.
                    firstTokenPending = false
                }
            }
            flush()
        } catch (error: TransportLimitExceededException) {
            throw limitException(response.statusCode(), response.headers().map(), error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!isStreamClosure(error)) throw error
            throw ProviderException(
                "Model API SSE stream closed unexpectedly",
                statusCode = response.statusCode(),
                cause = error,
                networkFailure = true,
            )
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

    private fun isStreamClosure(error: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is IOException) return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains("closed") || message.contains("connection reset") ||
                message.contains("broken pipe") || message.contains("premature end") ||
                message.contains("unexpected end")
            ) return true
            current = current.cause
        }
        return false
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
            billingUncertain = statusCode in 200..299 || statusCode >= 500,
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
            "User-Agent" to OMNICODE_PROVIDER_USER_AGENT,
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
    return validatedModelApiOrigin(value, rejectQuery = true)
}

private fun validatedModelApiOrigin(value: String, rejectQuery: Boolean): String {
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
    require(scheme == "https" || scheme == "http" || scheme == "codex") {
        "Base URL 必须以 https:// 开头；本机回环地址可使用 http://，Codex 原生使用 codex://。"
    }
    require(uri.rawUserInfo == null) { "Base URL 不能包含用户名或密码。" }
    require(!rejectQuery || uri.rawQuery == null) {
        "Base URL 不能包含查询参数；请将 API Key 等密钥保存到 IDE Password Safe。"
    }
    require(uri.rawFragment == null) { "Base URL 不能包含 #fragment。" }
    val parsedHost = uri.host?.lowercase().orEmpty()
    require(parsedHost.isNotEmpty()) { "Base URL 必须包含有效主机名。" }
    val host = parsedHost.replace(REGION_PLACEHOLDER_HOST, REGION_PLACEHOLDER)
    val port = uri.port
    if (scheme == "codex") {
        require(host == "local") { "Codex 原生 Base URL 必须使用 codex://local。" }
        require(port == -1) { "Codex 原生 Base URL 不接受端口。" }
        return "codex://local"
    }
    require(scheme == "https" || isLoopbackModelApiHost(host)) {
        "远程 Base URL 必须使用 HTTPS；只有 localhost 或回环 IP 可以使用 HTTP。"
    }
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
    // Provider adapters such as Gemini legitimately append a credential query parameter after
    // loading it from Password Safe. User-configured Base URLs are validated separately below.
    validatedModelApiOrigin(value, rejectQuery = false)
    null
} catch (error: IllegalArgumentException) {
    error.message ?: "Base URL 格式无效。"
}

internal fun modelApiBaseUrlValidationError(value: String): String? = try {
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
