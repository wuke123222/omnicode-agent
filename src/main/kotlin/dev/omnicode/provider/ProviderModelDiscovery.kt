package dev.omnicode.provider

import com.google.gson.JsonObject
import dev.omnicode.util.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class ModelDiscoveryResult(
    val models: List<String>,
    val discoveredRemotely: Boolean,
    val status: String,
)

internal interface ModelDiscoveryHttpClient {
    /** Returns a successful response or throws [ProviderException] for HTTP/network failures. */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String>,
        proxyMode: ProviderProxyMode,
    ): HttpResult
}

private object HttpTransportModelDiscoveryClient : ModelDiscoveryHttpClient {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String>,
        proxyMode: ProviderProxyMode,
    ): HttpResult = HttpTransport.getJson(url, headers, timeoutSeconds, sensitiveValues, proxyMode)
}

internal object ProviderModelDiscovery {
    fun supportsRemoteDiscovery(protocol: ProviderProtocol): Boolean = when (protocol) {
        ProviderProtocol.CODEX_APP_SERVER,
        ProviderProtocol.OPENCODE_ZEN,
        ProviderProtocol.OPENAI_RESPONSES,
        ProviderProtocol.OPENAI_CHAT,
        ProviderProtocol.ANTHROPIC_MESSAGES,
        ProviderProtocol.GEMINI,
        -> true
        ProviderProtocol.AZURE_OPENAI,
        ProviderProtocol.BEDROCK_CONVERSE,
        -> false
    }

    suspend fun discover(connection: ProviderConnection): ModelDiscoveryResult =
        discover(connection, HttpTransportModelDiscoveryClient)

    internal suspend fun discover(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult = when (connection.preset.protocol) {
        ProviderProtocol.CODEX_APP_SERVER -> CodexNativeModelDiscovery.discover(connection)
        ProviderProtocol.OPENCODE_ZEN,
        ProviderProtocol.OPENAI_RESPONSES,
        ProviderProtocol.OPENAI_CHAT,
        -> discoverOpenAiCompatible(connection, client)

        ProviderProtocol.GEMINI -> discoverGemini(connection, client)

        ProviderProtocol.ANTHROPIC_MESSAGES -> discoverAnthropic(connection, client)

        ProviderProtocol.AZURE_OPENAI,
        ProviderProtocol.BEDROCK_CONVERSE,
        -> fallback(
            connection,
            "${connection.preset.displayName} does not expose a compatible model-list endpoint; using the configured/default model.",
        )
    }

    private suspend fun discoverOpenAiCompatible(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult {
        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("Authorization", "Bearer ${connection.apiKey}")
            putAll(connection.extraHeaders)
        }
        val response = try {
            client.get(
                url = "${connection.baseUrl.trimEnd('/')}/models",
                headers = headers,
                timeoutSeconds = connection.requestTimeoutSeconds,
                sensitiveValues = connection.sensitiveValues(),
                proxyMode = connection.proxyMode,
            )
        } catch (error: ProviderException) {
            if (error.statusCode != null && error.statusCode in UNSUPPORTED_MODEL_LIST_STATUSES) {
                return fallback(
                    connection,
                    "This endpoint does not provide model listing (HTTP ${error.statusCode}); using the configured/default model.",
                )
            }
            throw error
        }
        val payload = parsePayload(connection, response.body)
        val data = when {
            payload.has("data") && payload.get("data").isJsonArray -> payload.getAsJsonArray("data")
            payload.has("models") && payload.get("models").isJsonArray -> payload.getAsJsonArray("models")
            else -> throw invalidPayload(connection)
        }
        val models = data.mapNotNull { element ->
            when {
                element.isJsonPrimitive -> runCatching { element.asString }.getOrNull()
                element.isJsonObject -> element.asJsonObject.stringOrNull("id")
                    ?: element.asJsonObject.stringOrNull("name")?.removePrefix("models/")
                else -> null
            }
        }.normalizedModelIds()
        return if (models.isEmpty()) {
            fallback(connection, "The provider returned no selectable models; using the configured/default model.")
        } else {
            ModelDiscoveryResult(
                models = models,
                discoveredRemotely = true,
                status = "Found ${models.size} available models.",
            )
        }
    }

    private suspend fun discoverAnthropic(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult {
        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("x-api-key", connection.apiKey)
            put(
                "anthropic-version",
                connection.apiVersion.takeIf { it.matches(ANTHROPIC_VERSION) }
                    ?: DEFAULT_ANTHROPIC_VERSION,
            )
            putAll(connection.extraHeaders)
        }
        val models = mutableListOf<String>()
        val seenLastIds = mutableSetOf<String>()
        var afterId: String? = null

        repeat(MAX_ANTHROPIC_PAGES) {
            val url = buildString {
                append(connection.baseUrl.trimEnd('/'))
                append("/models?limit=")
                append(ANTHROPIC_PAGE_SIZE)
                afterId?.let {
                    append("&after_id=")
                    append(URLEncoder.encode(it, StandardCharsets.UTF_8))
                }
            }
            val response = try {
                client.get(
                    url = url,
                    headers = headers,
                    timeoutSeconds = connection.requestTimeoutSeconds,
                    sensitiveValues = connection.sensitiveValues(),
                    proxyMode = connection.proxyMode,
                )
            } catch (error: ProviderException) {
                if (error.statusCode != null && error.statusCode in UNSUPPORTED_MODEL_LIST_STATUSES) {
                    return fallback(
                        connection,
                        "Anthropic model listing is unavailable (HTTP ${error.statusCode}); using the configured/default model.",
                    )
                }
                throw error
            }
            val payload = parsePayload(connection, response.body)
            val items = payload.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw invalidPayload(connection)
            items.forEach { element ->
                if (element.isJsonObject) {
                    element.asJsonObject.stringOrNull("id")
                        ?.takeIf(String::isNotBlank)
                        ?.let(models::add)
                }
            }

            val hasMore = payload.get("has_more")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean
                ?: throw invalidPayload(connection)
            if (!hasMore) {
                val normalized = models.normalizedModelIds()
                return if (normalized.isEmpty()) {
                    fallback(connection, "Anthropic returned no selectable models; using the configured/default model.")
                } else {
                    ModelDiscoveryResult(
                        models = normalized,
                        discoveredRemotely = true,
                        status = "Found ${normalized.size} Anthropic models.",
                    )
                }
            }

            val next = payload.stringOrNull("last_id")?.takeIf(String::isNotBlank)
                ?: throw ProviderException(
                    "${connection.preset.displayName} indicated more models without a usable last_id cursor.",
                )
            if (!seenLastIds.add(next)) {
                throw ProviderException(
                    "${connection.preset.displayName} returned a repeating model-list last_id cursor.",
                )
            }
            afterId = next
        }
        throw ProviderException(
            "${connection.preset.displayName} model listing exceeded $MAX_ANTHROPIC_PAGES pages.",
        )
    }

    private suspend fun discoverGemini(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult {
        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("x-goog-api-key", connection.apiKey)
            putAll(connection.extraHeaders)
        }
        val models = mutableListOf<String>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null

        repeat(MAX_GEMINI_PAGES) {
            val url = buildString {
                append(connection.baseUrl.trimEnd('/'))
                append("/models?pageSize=")
                append(GEMINI_PAGE_SIZE)
                pageToken?.let {
                    append("&pageToken=")
                    append(URLEncoder.encode(it, StandardCharsets.UTF_8))
                }
            }
            val response = client.get(
                url = url,
                headers = headers,
                timeoutSeconds = connection.requestTimeoutSeconds,
                sensitiveValues = connection.sensitiveValues(),
                proxyMode = connection.proxyMode,
            )
            val payload = parsePayload(connection, response.body)
            val items = payload.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw invalidPayload(connection)
            items.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val model = element.asJsonObject
                val supportsGenerateContent = model.get("supportedGenerationMethods")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.any { method ->
                        method.isJsonPrimitive && runCatching { method.asString }.getOrNull()
                            ?.equals("generateContent", ignoreCase = true) == true
                    } == true
                if (supportsGenerateContent) {
                    val id = model.stringOrNull("baseModelId")
                        ?: model.stringOrNull("name")?.removePrefix("models/")
                    if (!id.isNullOrBlank()) models += id
                }
            }

            val next = payload.stringOrNull("nextPageToken")?.takeIf { it.isNotBlank() }
            if (next == null) {
                val normalized = models.normalizedModelIds()
                return if (normalized.isEmpty()) {
                    fallback(connection, "Gemini returned no models that support generateContent; using the configured/default model.")
                } else {
                    ModelDiscoveryResult(
                        models = normalized,
                        discoveredRemotely = true,
                        status = "Found ${normalized.size} Gemini generation models.",
                    )
                }
            }
            if (!seenPageTokens.add(next)) {
                throw ProviderException("${connection.preset.displayName} returned a repeating model-list page token.")
            }
            pageToken = next
        }
        throw ProviderException("${connection.preset.displayName} model listing exceeded $MAX_GEMINI_PAGES pages.")
    }

    private fun parsePayload(connection: ProviderConnection, body: String): JsonObject =
        runCatching { Json.parseObject(body) }.getOrElse { cause ->
            throw invalidPayload(connection, cause)
        }

    private fun invalidPayload(connection: ProviderConnection, cause: Throwable? = null): ProviderException =
        ProviderException(
            "${connection.preset.displayName} returned an invalid model-list response.",
            cause = cause,
        )

    private fun fallback(connection: ProviderConnection, status: String): ModelDiscoveryResult =
        ModelDiscoveryResult(
            models = listOf(connection.model, connection.preset.defaultModel)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct(),
            discoveredRemotely = false,
            status = status,
        )

    private fun Iterable<String>.normalizedModelIds(): List<String> = asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toList()

    private const val GEMINI_PAGE_SIZE = 1_000
    private const val MAX_GEMINI_PAGES = 20
    private const val ANTHROPIC_PAGE_SIZE = 1_000
    private const val MAX_ANTHROPIC_PAGES = 20
    private const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"
    private val ANTHROPIC_VERSION = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val UNSUPPORTED_MODEL_LIST_STATUSES = setOf(404, 405, 501)
}
