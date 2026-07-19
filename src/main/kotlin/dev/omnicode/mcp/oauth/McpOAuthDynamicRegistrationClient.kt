package dev.omnicode.mcp.oauth

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI

/** Optional RFC 7591 Dynamic Client Registration fallback. */
class McpOAuthDynamicRegistrationClient internal constructor(
    private val transport: McpOAuthHttpTransport,
) {
    constructor() : this(JavaMcpOAuthHttpTransport())

    fun register(
        metadata: McpAuthorizationServerMetadata,
        request: McpDynamicClientRegistrationRequest,
    ): McpDynamicClientRegistration {
        val endpoint = metadata.registrationEndpoint
            ?: throw McpOAuthException("Authorization server does not advertise Dynamic Client Registration")
        runCatching { requireAuthorizationServerUri(endpoint, "OAuth registration endpoint") }.getOrElse {
            throw McpOAuthException("OAuth registration endpoint is invalid or insecure", it)
        }
        if (request.redirectUris.isEmpty() || request.redirectUris.size > MAX_REDIRECT_URIS) {
            throw McpOAuthException("OAuth dynamic registration requires a bounded redirect URI list")
        }
        val redirectUris = request.redirectUris.map { redirect ->
            runCatching { requireRedirectUri(redirect) }.getOrElse {
                throw McpOAuthException("OAuth dynamic registration contains an invalid redirect URI", it)
            }
        }.distinct()
        if (request.clientName.isBlank() || request.clientName.length > 256 || request.clientName.contains('\u0000')) {
            throw McpOAuthException("OAuth dynamic registration client name is invalid")
        }
        if (metadata.tokenEndpointAuthMethodsSupported.isNotEmpty() &&
            request.tokenEndpointAuthMethod.wireName !in metadata.tokenEndpointAuthMethodsSupported
        ) {
            throw McpOAuthException(
                "Authorization server does not advertise ${request.tokenEndpointAuthMethod.wireName} client authentication",
            )
        }
        val body = JsonObject().apply {
            add("redirect_uris", JsonArray().apply {
                redirectUris.forEach { add(it.toASCIIString()) }
            })
            addProperty("client_name", request.clientName)
            add("grant_types", JsonArray().apply {
                add("authorization_code")
                add("refresh_token")
            })
            add("response_types", JsonArray().apply { add("code") })
            addProperty("token_endpoint_auth_method", request.tokenEndpointAuthMethod.wireName)
        }
        val json = parseJsonResponse(
            transport.postJson(endpoint, body, "OAuth dynamic registration endpoint"),
            "OAuth dynamic registration endpoint",
        )
        val clientId = json.string("client_id", 8_192)
            ?: throw McpOAuthException("OAuth dynamic registration response is missing client_id")
        requireClientId(clientId)
        val clientSecret = json.string("client_secret", MAX_SECRET_CHARS)?.also {
            requireBoundedSecret(it, "OAuth registered client secret")
        }
        val expiresAt = json.nonNegativeLong("client_secret_expires_at")
        val returnedRedirects = json.stringArray("redirect_uris")?.map { raw ->
            val uri = runCatching { URI(raw) }.getOrElse {
                throw McpOAuthException("OAuth dynamic registration returned an invalid redirect URI")
            }
            runCatching { requireRedirectUri(uri) }.getOrElse {
                throw McpOAuthException("OAuth dynamic registration returned an insecure redirect URI")
            }
        } ?: redirectUris
        if (returnedRedirects.isEmpty()) {
            throw McpOAuthException("OAuth dynamic registration returned an empty redirect URI list")
        }
        val authMethod = json.string("token_endpoint_auth_method", 128)
            ?: request.tokenEndpointAuthMethod.wireName
        if (authMethod !in setOf("none", "client_secret_post")) {
            throw McpOAuthException("OAuth dynamic registration returned an unsupported client authentication method")
        }
        return McpDynamicClientRegistration(
            clientId = clientId,
            clientSecret = clientSecret,
            clientSecretExpiresAtEpochSeconds = expiresAt,
            redirectUris = returnedRedirects,
            tokenEndpointAuthMethod = authMethod,
        )
    }

    private fun JsonObject.string(name: String, maxChars: Int): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw McpOAuthException("OAuth dynamic registration returned an invalid $name")
        }
        return element.asString.takeIf { it.isNotBlank() && it.length <= maxChars && !it.contains('\u0000') }
            ?: throw McpOAuthException("OAuth dynamic registration returned an invalid $name")
    }

    private fun JsonObject.nonNegativeLong(name: String): Long? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber && !element.asJsonPrimitive.isString) {
            throw McpOAuthException("OAuth dynamic registration returned an invalid $name")
        }
        val value = runCatching { element.asString }.getOrNull()
            ?.takeIf { it.matches(Regex("[0-9]+")) }
            ?.toLongOrNull()
            ?: throw McpOAuthException("OAuth dynamic registration returned an invalid $name")
        return value
    }

    private fun JsonObject.stringArray(name: String): List<String>? {
        val element = get(name) ?: return null
        if (!element.isJsonArray || element.asJsonArray.size() > MAX_REDIRECT_URIS) {
            throw McpOAuthException("OAuth dynamic registration returned an invalid $name")
        }
        return element.asJsonArray.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString || item.asString.length > 8_192) {
                throw McpOAuthException("OAuth dynamic registration returned an invalid $name")
            }
            item.asString
        }
    }

    private companion object {
        const val MAX_REDIRECT_URIS = 16
    }
}
