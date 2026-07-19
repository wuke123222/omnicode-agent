package dev.omnicode.mcp.oauth

import java.net.URI

/** A parsed Bearer challenge from an MCP resource server. */
data class McpOAuthChallenge(
    val resourceMetadata: URI?,
    val scopes: Set<String>,
    val error: String? = null,
    val errorDescription: String? = null,
)

/** RFC 9728 protected-resource metadata used by MCP authorization discovery. */
data class McpProtectedResourceMetadata(
    val resource: URI,
    val authorizationServers: List<URI>,
    val scopesSupported: Set<String>,
    val metadataUri: URI,
)

/** The RFC 8414/OIDC fields required by the MCP authorization-code flow. */
data class McpAuthorizationServerMetadata(
    val issuer: URI,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val registrationEndpoint: URI?,
    val codeChallengeMethodsSupported: Set<String>,
    val scopesSupported: Set<String>,
    val tokenEndpointAuthMethodsSupported: Set<String>,
    val clientIdMetadataDocumentSupported: Boolean,
    val metadataUri: URI,
)

data class McpOAuthDiscoveryResult(
    val resource: URI,
    val challenge: McpOAuthChallenge?,
    val challengeScopes: Set<String>,
    val requestedScopes: Set<String>,
    val protectedResource: McpProtectedResourceMetadata,
    val authorizationServer: McpAuthorizationServerMetadata,
)

data class McpPkcePair(
    val verifier: String,
    val challenge: String,
    val method: String = "S256",
)

enum class McpTokenEndpointAuthMethod(val wireName: String) {
    NONE("none"),
    CLIENT_SECRET_POST("client_secret_post"),
}

data class McpAuthorizationCodeRequest(
    val metadata: McpAuthorizationServerMetadata,
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpointAuthMethod: McpTokenEndpointAuthMethod = McpTokenEndpointAuthMethod.NONE,
    val code: String,
    val codeVerifier: String,
    val redirectUri: URI,
    val resource: URI,
)

data class McpRefreshTokenRequest(
    val metadata: McpAuthorizationServerMetadata,
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpointAuthMethod: McpTokenEndpointAuthMethod = McpTokenEndpointAuthMethod.NONE,
    val refreshToken: String,
    val resource: URI,
    val scopes: Set<String> = emptySet(),
)

/**
 * OAuth token response. A null [refreshToken] means the caller should retain the previous
 * refresh token when this object came from a refresh operation.
 */
data class McpOAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val scopes: Set<String>,
    val expiresAtEpochMillis: Long?,
) {
    val scope: Set<String>
        get() = scopes
}

data class McpDynamicClientRegistrationRequest(
    val redirectUris: List<URI>,
    val clientName: String = "OmniCode",
    val tokenEndpointAuthMethod: McpTokenEndpointAuthMethod = McpTokenEndpointAuthMethod.NONE,
)

data class McpDynamicClientRegistration(
    val clientId: String,
    val clientSecret: String?,
    /** RFC 7591 seconds since Unix epoch. Zero means the secret does not expire. */
    val clientSecretExpiresAtEpochSeconds: Long?,
    val redirectUris: List<URI>,
    val tokenEndpointAuthMethod: String,
)

open class McpOAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal class McpOAuthEndpointException(
    label: String,
    val statusCode: Int,
    val oauthError: String?,
) : McpOAuthException(
    "$label returned HTTP $statusCode" + oauthError?.let { " ($it)" }.orEmpty(),
) {
    val requiresNewLogin: Boolean
        get() = oauthError == "invalid_grant" || oauthError == "invalid_client"
}
