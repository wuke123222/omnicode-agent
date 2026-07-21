package dev.omnicode.mcp.oauth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.provider.modelApiProxySelector
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.util.Locale

internal data class McpOAuthHttpRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

internal data class McpOAuthHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}

internal fun interface McpOAuthHttpTransport {
    fun execute(request: McpOAuthHttpRequest): McpOAuthHttpResponse
}

internal class JavaMcpOAuthHttpTransport(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(modelApiProxySelector())
        .build(),
) : McpOAuthHttpTransport {
    override fun execute(request: McpOAuthHttpRequest): McpOAuthHttpResponse {
        require(request.body == null || request.body.size <= MAX_REQUEST_BYTES) {
            "OAuth request body exceeds the supported size limit"
        }
        val builder = HttpRequest.newBuilder(request.uri)
            .timeout(REQUEST_TIMEOUT)
        request.headers.forEach(builder::header)
        when (request.method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofByteArray(request.body ?: ByteArray(0)))
            else -> throw IllegalArgumentException("Unsupported OAuth HTTP method")
        }
        val response = try {
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw McpOAuthException("OAuth network request was interrupted", interrupted)
        } catch (error: Throwable) {
            throw McpOAuthException("OAuth network request failed", error)
        }
        response.body().use { body ->
            if (response.statusCode() in 300..399) {
                throw McpOAuthException("OAuth endpoint refused HTTP redirect status ${response.statusCode()}")
            }
            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw McpOAuthException("OAuth response exceeds the supported size limit")
            }
            return McpOAuthHttpResponse(
                statusCode = response.statusCode(),
                headers = response.headers().map(),
                body = readBounded(body),
            )
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1)
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw McpOAuthException("OAuth response exceeds the supported size limit")
        }
        return bytes
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val MAX_REQUEST_BYTES = 256 * 1_024
        const val MAX_RESPONSE_BYTES = 1 * 1_024 * 1_024
    }
}

internal fun McpOAuthHttpTransport.getJson(uri: URI, label: String): McpOAuthHttpResponse = execute(
    McpOAuthHttpRequest(
        method = "GET",
        uri = uri,
        headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "OmniCode/0.9.0",
        ),
    ),
).also { response ->
    if (response.statusCode !in 200..299 && response.statusCode != 404) {
        throw endpointFailure(label, response)
    }
}

internal fun McpOAuthHttpTransport.postJson(uri: URI, json: JsonObject, label: String): McpOAuthHttpResponse = execute(
    McpOAuthHttpRequest(
        method = "POST",
        uri = uri,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "User-Agent" to "OmniCode/0.9.0",
        ),
        body = json.toString().toByteArray(StandardCharsets.UTF_8),
    ),
).also { response ->
    if (response.statusCode !in 200..299) throw endpointFailure(label, response)
}

internal fun McpOAuthHttpTransport.postForm(uri: URI, form: String, label: String): McpOAuthHttpResponse = execute(
    McpOAuthHttpRequest(
        method = "POST",
        uri = uri,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to "OmniCode/0.9.0",
        ),
        body = form.toByteArray(StandardCharsets.UTF_8),
    ),
).also { response ->
    if (response.statusCode !in 200..299) throw endpointFailure(label, response)
}

internal fun parseJsonResponse(response: McpOAuthHttpResponse, label: String): JsonObject {
    val contentType = response.header("Content-Type")
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    if (contentType != "application/json" && contentType?.endsWith("+json") != true) {
        throw McpOAuthException("$label returned an unsupported Content-Type")
    }
    return runCatching {
        JsonParser.parseString(decodeUtf8Strict(response.body)).asJsonObject
    }.getOrElse {
        // Parser exception messages can include attacker-controlled response fragments. Token
        // responses may contain credentials, so never attach or propagate the parser cause.
        throw McpOAuthException("$label returned invalid JSON")
    }
}

private fun endpointFailure(label: String, response: McpOAuthHttpResponse): McpOAuthException {
    // OAuth error descriptions are deliberately excluded: a hostile endpoint can echo a code,
    // verifier, client secret, or token in them. The standardized error code is safe only after
    // strict character and length validation.
    val errorCode = runCatching {
        JsonParser.parseString(decodeUtf8Strict(response.body))
            .asJsonObject
            .get("error")
            ?.asString
    }.getOrNull()?.takeIf { it.length <= 64 && it.all { char -> char.isLetterOrDigit() || char in "-_." } }
    return McpOAuthEndpointException(label, response.statusCode, errorCode)
}

private fun decodeUtf8Strict(value: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(value))
    .toString()
