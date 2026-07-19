package dev.omnicode.mcp.oauth

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.util.Locale

/** MCP 2025-11-25 authorization-server discovery (RFC 9728, RFC 8414, and OIDC). */
class McpOAuthDiscoveryClient internal constructor(
    private val transport: McpOAuthHttpTransport,
) {
    constructor() : this(JavaMcpOAuthHttpTransport())

    fun discover(
        mcpEndpoint: URI,
        wwwAuthenticate: List<String> = emptyList(),
    ): McpOAuthDiscoveryResult {
        val resource = runCatching { canonicalMcpResource(mcpEndpoint) }.getOrElse {
            throw McpOAuthException("MCP resource URL is invalid or insecure", it)
        }
        val challenge = McpOAuthChallengeParser.parse(wwwAuthenticate)
        val protectedResource = discoverProtectedResource(resource, challenge)
        val authorizationServer = discoverAuthorizationServer(protectedResource.authorizationServers.first())
        val challengeScopes = challenge?.scopes.orEmpty()
        val requestedScopes = challengeScopes.ifEmpty { protectedResource.scopesSupported }
        return McpOAuthDiscoveryResult(
            resource = resource,
            challenge = challenge,
            challengeScopes = challengeScopes,
            requestedScopes = requestedScopes,
            protectedResource = protectedResource,
            authorizationServer = authorizationServer,
        )
    }

    private fun discoverProtectedResource(
        resource: URI,
        challenge: McpOAuthChallenge?,
    ): McpProtectedResourceMetadata {
        val challengeUri = challenge?.resourceMetadata
        if (challengeUri != null && resource.scheme.equals("https", ignoreCase = true) &&
            !challengeUri.scheme.equals("https", ignoreCase = true)
        ) {
            throw McpOAuthException("Remote MCP resource metadata must use HTTPS")
        }
        val candidates = if (challengeUri != null) {
            if (challengeUri.scheme.equals("http", ignoreCase = true) &&
                (!resource.scheme.equals("http", ignoreCase = true) || !isLoopbackHost(resource.host))
            ) {
                throw McpOAuthException("Remote MCP resources must advertise resource metadata over HTTPS")
            }
            listOf(challengeUri)
        } else {
            protectedResourceWellKnownUris(resource)
        }
        candidates.forEach { metadataUri ->
            val response = transport.getJson(metadataUri, "OAuth protected-resource metadata endpoint")
            if (response.statusCode == 404) return@forEach
            val json = parseJsonResponse(response, "OAuth protected-resource metadata endpoint")
            return parseProtectedResource(json, metadataUri, resource)
        }
        throw McpOAuthException("MCP protected-resource metadata was not found")
    }

    private fun parseProtectedResource(
        json: JsonObject,
        metadataUri: URI,
        expectedResource: URI,
    ): McpProtectedResourceMetadata {
        val resource = requiredUri(json, "resource", "protected-resource metadata")
        val canonicalResource = runCatching { canonicalMcpResource(resource) }.getOrElse {
            throw McpOAuthException("Protected-resource metadata contains an invalid resource URL")
        }
        if (canonicalResource != expectedResource) {
            throw McpOAuthException("Protected-resource metadata does not describe the requested MCP resource")
        }
        val authorizationServers = stringArray(json, "authorization_servers", required = true)
            .map { raw ->
                val issuer = parseUri(raw, "authorization server issuer")
                runCatching { requireAuthorizationIssuer(issuer) }.getOrElse {
                    throw McpOAuthException("Protected-resource metadata contains an insecure authorization server issuer")
                }
            }
            .distinct()
        if (authorizationServers.isEmpty()) {
            throw McpOAuthException("Protected-resource metadata does not identify an authorization server")
        }
        return McpProtectedResourceMetadata(
            resource = canonicalResource,
            authorizationServers = authorizationServers,
            scopesSupported = stringArray(json, "scopes_supported", required = false).toBoundedScopeSet(),
            metadataUri = metadataUri,
        )
    }

    private fun discoverAuthorizationServer(issuer: URI): McpAuthorizationServerMetadata {
        authorizationServerWellKnownUris(issuer).forEach { metadataUri ->
            val response = transport.getJson(metadataUri, "OAuth authorization-server metadata endpoint")
            if (response.statusCode == 404) return@forEach
            return parseAuthorizationServer(
                parseJsonResponse(response, "OAuth authorization-server metadata endpoint"),
                metadataUri,
                issuer,
            )
        }
        throw McpOAuthException("OAuth authorization-server metadata was not found")
    }

    private fun parseAuthorizationServer(
        json: JsonObject,
        metadataUri: URI,
        expectedIssuer: URI,
    ): McpAuthorizationServerMetadata {
        val issuer = requiredUri(json, "issuer", "authorization-server metadata")
        if (normalizeIssuer(issuer) != normalizeIssuer(expectedIssuer)) {
            throw McpOAuthException("Authorization-server metadata issuer does not match the discovered issuer")
        }
        val authorizationEndpoint = secureEndpoint(json, "authorization_endpoint")
        val tokenEndpoint = secureEndpoint(json, "token_endpoint")
        val registrationEndpoint = optionalUri(json, "registration_endpoint")?.let { endpoint ->
            runCatching { requireAuthorizationServerUri(endpoint, "OAuth registration endpoint") }.getOrElse {
                throw McpOAuthException("Authorization-server metadata contains an insecure registration endpoint")
            }
        }
        val pkceMethods = stringArray(json, "code_challenge_methods_supported", required = false).toSet()
        if ("S256" !in pkceMethods) {
            throw McpOAuthException("Authorization server does not advertise required PKCE S256 support")
        }
        return McpAuthorizationServerMetadata(
            issuer = issuer,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = registrationEndpoint,
            codeChallengeMethodsSupported = pkceMethods,
            scopesSupported = stringArray(json, "scopes_supported", required = false).toBoundedScopeSet(),
            tokenEndpointAuthMethodsSupported = stringArray(
                json,
                "token_endpoint_auth_methods_supported",
                required = false,
            ).toSet(),
            clientIdMetadataDocumentSupported = json.optionalBoolean("client_id_metadata_document_supported") == true,
            metadataUri = metadataUri,
        )
    }

    private fun secureEndpoint(json: JsonObject, name: String): URI {
        val endpoint = requiredUri(json, name, "authorization-server metadata")
        return runCatching { requireAuthorizationServerUri(endpoint, "OAuth ${name.replace('_', ' ')}") }.getOrElse {
            throw McpOAuthException("Authorization-server metadata contains an insecure ${name.replace('_', ' ')}")
        }
    }

    private fun protectedResourceWellKnownUris(resource: URI): List<URI> {
        val origin = rawHttpUri(resource.scheme, resource.host, resource.port, "")
        val resourcePath = resource.rawPath.orEmpty().takeUnless { it.isEmpty() || it == "/" }
        val hasResourceSpecificLocation = resourcePath != null || resource.rawQuery != null
        return listOfNotNull(
            if (hasResourceSpecificLocation) {
                rawHttpUri(
                    resource.scheme,
                    resource.host,
                    resource.port,
                    "/.well-known/oauth-protected-resource${resourcePath.orEmpty()}",
                    resource.rawQuery,
                )
            } else {
                null
            },
            origin.resolve("/.well-known/oauth-protected-resource"),
        ).distinct()
    }

    private fun authorizationServerWellKnownUris(issuer: URI): List<URI> {
        val origin = rawHttpUri(issuer.scheme, issuer.host, issuer.port, "")
        val issuerPath = issuer.rawPath.orEmpty().takeUnless { it.isEmpty() || it == "/" }
        return if (issuerPath == null) {
            listOf(
                origin.resolve("/.well-known/oauth-authorization-server"),
                origin.resolve("/.well-known/openid-configuration"),
            )
        } else {
            listOf(
                origin.resolve("/.well-known/oauth-authorization-server$issuerPath"),
                origin.resolve("/.well-known/openid-configuration$issuerPath"),
                rawHttpUri(
                    issuer.scheme,
                    issuer.host,
                    issuer.port,
                    issuer.rawPath.trimEnd('/') + "/.well-known/openid-configuration",
                ),
            )
        }
    }

    private fun requireAuthorizationIssuer(value: URI): URI {
        requireAuthorizationServerUri(value, "OAuth authorization server issuer")
        require(value.rawQuery == null) { "OAuth authorization server issuer must not contain a query" }
        return value
    }

    private fun normalizeIssuer(value: URI): URI {
        requireAuthorizationIssuer(value)
        val scheme = value.scheme.lowercase(Locale.ROOT)
        val port = value.port.takeUnless { it == 443 } ?: -1
        return rawHttpUri(scheme, value.host.lowercase(Locale.ROOT), port, value.rawPath.orEmpty())
    }

    private fun requiredUri(json: JsonObject, name: String, label: String): URI = optionalUri(json, name)
        ?: throw McpOAuthException("OAuth $label is missing $name")

    private fun optionalUri(json: JsonObject, name: String): URI? {
        val element = json.get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw McpOAuthException("OAuth metadata field $name must be a URL string")
        }
        return parseUri(element.asString, name)
    }

    private fun parseUri(value: String, label: String): URI {
        if (value.length > MAX_URL_CHARS) throw McpOAuthException("OAuth $label URL exceeds the supported length")
        return runCatching { URI(value) }.getOrElse { throw McpOAuthException("OAuth $label is not a valid URL") }
    }

    private fun stringArray(json: JsonObject, name: String, required: Boolean): List<String> {
        val element = json.get(name)
        if (element == null || element.isJsonNull) {
            if (required) throw McpOAuthException("OAuth metadata is missing $name")
            return emptyList()
        }
        if (!element.isJsonArray) throw McpOAuthException("OAuth metadata field $name must be an array")
        if (element.asJsonArray.size() > MAX_ARRAY_ITEMS) {
            throw McpOAuthException("OAuth metadata field $name exceeds the supported item limit")
        }
        return element.asJsonArray.map { item: JsonElement ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
                throw McpOAuthException("OAuth metadata field $name contains a non-string value")
            }
            item.asString.takeIf { it.length <= MAX_FIELD_CHARS && !it.contains('\u0000') }
                ?: throw McpOAuthException("OAuth metadata field $name contains an invalid value")
        }
    }

    private fun List<String>.toBoundedScopeSet(): Set<String> = flatMap { raw ->
        McpOAuthChallengeParser.parseScope(raw).toList()
    }.toCollection(linkedSetOf())

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            throw McpOAuthException("OAuth metadata field $name must be boolean")
        }
        return element.asBoolean
    }

    private companion object {
        const val MAX_URL_CHARS = 8_192
        const val MAX_ARRAY_ITEMS = 128
        const val MAX_FIELD_CHARS = 8_192
    }
}
