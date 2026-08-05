package dev.omnicode.service

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Optional provider-neutral cloud relay for already encrypted workflow packages.
 *
 * OmniCode does not ship a hosted account service. A deployment only needs to implement two
 * endpoints: `POST /v1/workflows/{id}` (raw `application/octet-stream`, returns 2xx) and
 * `GET /v1/workflows/{id}` (returns the same encrypted package). The relay must treat the body as
 * opaque bytes; the passphrase never leaves the client. HTTPS is required except for loopback
 * development endpoints.
 */
class WorkflowCloudSyncClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) {
    fun upload(
        endpoint: String,
        workflowId: String,
        bearerToken: CharArray,
        encryptedPackage: ByteArray,
    ): CloudSyncResult {
        return try {
            validateInput(endpoint, workflowId, bearerToken, encryptedPackage)
            val request = request(endpoint, workflowId, bearerToken)
                .header("Content-Type", "application/octet-stream")
                .header("X-OmniCode-Transfer-Version", "1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedPackage))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val body = response.body().use(::readBoundedResponse)
            CloudSyncResult(response.statusCode(), body.size, body).also { ensureSuccess(response.statusCode()) }
        } finally {
            bearerToken.fill('\u0000')
        }
    }

    fun download(
        endpoint: String,
        workflowId: String,
        bearerToken: CharArray,
    ): ByteArray {
        return try {
            validateInput(endpoint, workflowId, bearerToken, null)
            val request = request(endpoint, workflowId, bearerToken).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            ensureSuccess(response.statusCode())
            val body = response.body().use(::readBoundedPackage)
            require(body.size in 1..MAX_PACKAGE_BYTES) {
                "Cloud workflow package exceeds the 2 MiB client limit."
            }
            body
        } finally {
            bearerToken.fill('\u0000')
        }
    }

    private fun request(endpoint: String, workflowId: String, bearerToken: CharArray): HttpRequest.Builder {
        val base = URI.create(endpoint.trim().removeSuffix("/"))
        val path = base.path.trimEnd('/') + "/v1/workflows/" + encodePathSegment(workflowId)
        val uri = URI(base.scheme, base.authority, path, null, null)
        return HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer ${String(bearerToken)}")
            .header("Accept", "application/octet-stream")
    }

    private fun validateInput(
        endpoint: String,
        workflowId: String,
        bearerToken: CharArray,
        encryptedPackage: ByteArray?,
    ) {
        val uri = runCatching { URI.create(endpoint.trim()) }
            .getOrElse { throw IllegalArgumentException("Cloud endpoint is invalid.", it) }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Cloud endpoint must not contain credentials, query, or fragment."
        }
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost" || uri.host == "[::1]"
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "Cloud workflow sync requires HTTPS (HTTP is allowed only on loopback for development)."
        }
        require(uri.authority != null && uri.path.length <= 512) { "Cloud endpoint is invalid." }
        require(workflowId.matches(SAFE_ID)) { "Workflow id contains unsupported characters." }
        require(bearerToken.size in 8..4096) { "Cloud access token is missing or too long." }
        encryptedPackage?.let {
            require(it.size in 1..MAX_PACKAGE_BYTES) { "Workflow package exceeds the 2 MiB limit." }
        }
    }

    private fun ensureSuccess(status: Int) {
        require(status in 200..299) { "Cloud workflow sync failed with HTTP $status." }
    }

    private fun readBoundedResponse(input: java.io.InputStream): ByteArray = readBounded(input, MAX_RESPONSE_BYTES)

    private fun readBoundedPackage(input: java.io.InputStream): ByteArray = readBounded(input, MAX_PACKAGE_BYTES)

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Cloud response exceeds its bounded size limit." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun encodePathSegment(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    data class CloudSyncResult(
        val statusCode: Int,
        val responseBytes: Int,
        val responsePreview: ByteArray,
    )

    companion object {
        const val MAX_PACKAGE_BYTES = 2 * 1_048_576
        private const val MAX_RESPONSE_BYTES = 8 * 1_024
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,256}")
    }
}
