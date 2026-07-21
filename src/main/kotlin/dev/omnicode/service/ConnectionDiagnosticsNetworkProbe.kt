package dev.omnicode.service

import dev.omnicode.OMNICODE_PROVIDER_USER_AGENT
import dev.omnicode.provider.modelApiProxySelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Credential-free network boundary used by connection diagnostics.
 *
 * There is deliberately no headers/body/credential parameter. Implementations may only resolve
 * the validated provider host and issue a bodyless request to the exact configured Base URL.
 */
interface ConnectionDiagnosticsNetworkProbe {
    suspend fun resolve(host: String, timeoutMillis: Long): ConnectionDiagnosticsDnsResult

    suspend fun probe(
        endpoint: URI,
        connectTimeoutMillis: Long,
        requestTimeoutMillis: Long,
    ): ConnectionDiagnosticsEndpointResult
}

internal class JdkConnectionDiagnosticsNetworkProbe : ConnectionDiagnosticsNetworkProbe {
    override suspend fun resolve(host: String, timeoutMillis: Long): ConnectionDiagnosticsDnsResult = try {
        withTimeout(timeoutMillis) { resolveOnVirtualThread(host) }
    } catch (_: TimeoutCancellationException) {
        ConnectionDiagnosticsDnsResult(failure = ConnectionDiagnosticsNetworkFailure.TIMEOUT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    }

    private suspend fun resolveOnVirtualThread(host: String): ConnectionDiagnosticsDnsResult =
        suspendCancellableCoroutine { continuation ->
            val worker = Thread.ofVirtual()
                .name("OmniCode diagnostics DNS")
                .unstarted {
                    val result = try {
                        val addresses = InetAddress.getAllByName(host)
                        if (addresses.isEmpty()) {
                            ConnectionDiagnosticsDnsResult(failure = ConnectionDiagnosticsNetworkFailure.DNS_NOT_FOUND)
                        } else {
                            ConnectionDiagnosticsDnsResult(addressCount = addresses.size)
                        }
                    } catch (_: UnknownHostException) {
                        ConnectionDiagnosticsDnsResult(failure = ConnectionDiagnosticsNetworkFailure.DNS_NOT_FOUND)
                    } catch (_: Throwable) {
                        ConnectionDiagnosticsDnsResult(failure = ConnectionDiagnosticsNetworkFailure.OTHER)
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            continuation.invokeOnCancellation { worker.interrupt() }
            worker.start()
        }

    override suspend fun probe(
        endpoint: URI,
        connectTimeoutMillis: Long,
        requestTimeoutMillis: Long,
    ): ConnectionDiagnosticsEndpointResult {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(modelApiProxySelector())
            .build()
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(requestTimeoutMillis))
            .header("User-Agent", OMNICODE_PROVIDER_USER_AGENT)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        return try {
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).awaitCancellable()
            ConnectionDiagnosticsEndpointResult(
                statusCode = response.statusCode(),
                tlsEstablished = endpoint.scheme.equals("https", ignoreCase = true) && response.sslSession().isPresent,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ConnectionDiagnosticsEndpointResult(failure = classifyNetworkFailure(error))
        }
    }
}

private fun classifyNetworkFailure(source: Throwable): ConnectionDiagnosticsNetworkFailure {
    val error = unwrapCompletionFailure(source)
    return when (error) {
        is UnknownHostException -> ConnectionDiagnosticsNetworkFailure.DNS_NOT_FOUND
        is HttpTimeoutException -> ConnectionDiagnosticsNetworkFailure.TIMEOUT
        is SSLException -> ConnectionDiagnosticsNetworkFailure.TLS
        is ConnectException -> ConnectionDiagnosticsNetworkFailure.CONNECTION
        is ProtocolException -> ConnectionDiagnosticsNetworkFailure.PROTOCOL
        is IOException -> ConnectionDiagnosticsNetworkFailure.CONNECTION
        else -> ConnectionDiagnosticsNetworkFailure.OTHER
    }
}

private fun unwrapCompletionFailure(source: Throwable): Throwable {
    var current = source
    while ((current is CompletionException || current is java.util.concurrent.ExecutionException) && current.cause != null) {
        current = checkNotNull(current.cause)
    }
    return current
}

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) continuation.resume(value) else continuation.resumeWithException(error)
    }
    continuation.invokeOnCancellation { cancel(true) }
}
