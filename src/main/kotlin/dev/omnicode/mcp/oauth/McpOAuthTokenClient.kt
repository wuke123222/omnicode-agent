package dev.omnicode.mcp.oauth

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Clock

class McpOAuthTokenClient internal constructor(
    private val transport: McpOAuthHttpTransport,
    private val clock: Clock,
) {
    constructor() : this(JavaMcpOAuthHttpTransport(), Clock.systemUTC())

    fun exchangeAuthorizationCode(request: McpAuthorizationCodeRequest): McpOAuthTokens {
        validateCommon(
            metadata = request.metadata,
            clientId = request.clientId,
            clientSecret = request.clientSecret,
            authMethod = request.tokenEndpointAuthMethod,
            resource = request.resource,
        )
        requireBoundedSecret(request.code, "OAuth authorization code")
        if (!McpOAuthPkce.isValidVerifier(request.codeVerifier)) {
            throw McpOAuthException("OAuth PKCE code verifier is invalid")
        }
        runCatching { requireRedirectUri(request.redirectUri) }.getOrElse {
            throw McpOAuthException("OAuth redirect URI is invalid or insecure", it)
        }
        val parameters = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to request.clientId,
            "code" to request.code,
            "code_verifier" to request.codeVerifier,
            "redirect_uri" to request.redirectUri.toASCIIString(),
            "resource" to canonicalMcpResource(request.resource).toASCIIString(),
        )
        addClientAuthentication(parameters, request.clientSecret, request.tokenEndpointAuthMethod)
        return requestTokens(request.metadata, parameters)
    }

    fun refresh(request: McpRefreshTokenRequest): McpOAuthTokens {
        validateCommon(
            metadata = request.metadata,
            clientId = request.clientId,
            clientSecret = request.clientSecret,
            authMethod = request.tokenEndpointAuthMethod,
            resource = request.resource,
        )
        requireBoundedSecret(request.refreshToken, "OAuth refresh token")
        val parameters = linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to request.clientId,
            "refresh_token" to request.refreshToken,
            "resource" to canonicalMcpResource(request.resource).toASCIIString(),
        )
        request.scopes.takeIf(Set<String>::isNotEmpty)?.let {
            parameters["scope"] = validatedScopes(it).joinToString(" ")
        }
        addClientAuthentication(parameters, request.clientSecret, request.tokenEndpointAuthMethod)
        return requestTokens(request.metadata, parameters)
    }

    private fun validateCommon(
        metadata: McpAuthorizationServerMetadata,
        clientId: String,
        clientSecret: String?,
        authMethod: McpTokenEndpointAuthMethod,
        resource: java.net.URI,
    ) {
        runCatching { requireAuthorizationServerUri(metadata.tokenEndpoint, "OAuth token endpoint") }.getOrElse {
            throw McpOAuthException("OAuth token endpoint is invalid or insecure", it)
        }
        requireClientId(clientId)
        runCatching { canonicalMcpResource(resource) }.getOrElse {
            throw McpOAuthException("OAuth resource URL is invalid or insecure", it)
        }
        if (metadata.tokenEndpointAuthMethodsSupported.isNotEmpty() &&
            authMethod.wireName !in metadata.tokenEndpointAuthMethodsSupported
        ) {
            throw McpOAuthException("OAuth token endpoint does not advertise ${authMethod.wireName} client authentication")
        }
        when (authMethod) {
            McpTokenEndpointAuthMethod.NONE -> if (clientSecret != null) {
                throw McpOAuthException("OAuth public client authentication must not include a client secret")
            }
            McpTokenEndpointAuthMethod.CLIENT_SECRET_POST -> requireBoundedSecret(
                clientSecret.orEmpty(),
                "OAuth client secret",
            )
        }
    }

    private fun addClientAuthentication(
        parameters: MutableMap<String, String>,
        clientSecret: String?,
        authMethod: McpTokenEndpointAuthMethod,
    ) {
        if (authMethod == McpTokenEndpointAuthMethod.CLIENT_SECRET_POST) {
            parameters["client_secret"] = clientSecret!!
        }
    }

    private fun requestTokens(
        metadata: McpAuthorizationServerMetadata,
        parameters: Map<String, String>,
    ): McpOAuthTokens {
        val response = transport.postForm(metadata.tokenEndpoint, encodeForm(parameters), "OAuth token endpoint")
        val json = parseJsonResponse(response, "OAuth token endpoint")
        val accessToken = json.requiredSecret("access_token", "access token")
        val refreshToken = json.optionalSecret("refresh_token", "refresh token")
        val tokenType = json.requiredString("token_type", 64)
        if (!tokenType.equals("Bearer", ignoreCase = true)) {
            throw McpOAuthException("OAuth token endpoint returned an unsupported token type")
        }
        val scopes = json.optionalString("scope", 32_768)
            ?.let(McpOAuthChallengeParser::parseScope)
            .orEmpty()
        val expiresAt = json.optionalNonNegativeLong("expires_in")?.let { seconds ->
            if (seconds > MAX_TOKEN_LIFETIME_SECONDS) {
                throw McpOAuthException("OAuth token lifetime exceeds the supported limit")
            }
            runCatching { Math.addExact(clock.millis(), Math.multiplyExact(seconds, 1_000L)) }.getOrElse {
                throw McpOAuthException("OAuth token lifetime is invalid")
            }
        }
        return McpOAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            scopes = scopes,
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun JsonObject.requiredSecret(name: String, label: String): String = optionalSecret(name, label)
        ?: throw McpOAuthException("OAuth token endpoint response is missing $label")

    private fun JsonObject.optionalSecret(name: String, label: String): String? {
        val value = optionalString(name, MAX_SECRET_CHARS) ?: return null
        if (value.isBlank() || value.contains('\u0000')) {
            throw McpOAuthException("OAuth token endpoint returned an invalid $label")
        }
        return value
    }

    private fun JsonObject.requiredString(name: String, maxChars: Int): String = optionalString(name, maxChars)
        ?: throw McpOAuthException("OAuth token endpoint response is missing $name")

    private fun JsonObject.optionalString(name: String, maxChars: Int): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw McpOAuthException("OAuth token endpoint returned an invalid $name")
        }
        return element.asString.takeIf { it.length <= maxChars && !it.contains('\u0000') }
            ?: throw McpOAuthException("OAuth token endpoint returned an invalid $name")
    }

    private fun JsonObject.optionalNonNegativeLong(name: String): Long? {
        val element: JsonElement = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber && !element.asJsonPrimitive.isString) {
            throw McpOAuthException("OAuth token endpoint returned an invalid $name")
        }
        val raw = runCatching { element.asString }.getOrNull()
        val value = raw?.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
            ?: throw McpOAuthException("OAuth token endpoint returned an invalid $name")
        return value
    }

    private companion object {
        const val MAX_TOKEN_LIFETIME_SECONDS = 10L * 365 * 24 * 60 * 60
    }
}
