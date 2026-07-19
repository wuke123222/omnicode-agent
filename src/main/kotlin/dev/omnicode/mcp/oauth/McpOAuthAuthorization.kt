package dev.omnicode.mcp.oauth

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object McpOAuthAuthorization {
    fun buildUrl(
        metadata: McpAuthorizationServerMetadata,
        clientId: String,
        redirectUri: URI,
        resource: URI,
        scopes: Set<String>,
        pkce: McpPkcePair,
        state: String,
    ): URI {
        val endpoint = runCatching {
            requireAuthorizationServerUri(metadata.authorizationEndpoint, "OAuth authorization endpoint")
        }.getOrElse { throw McpOAuthException("OAuth authorization endpoint is invalid or insecure", it) }
        if ("S256" !in metadata.codeChallengeMethodsSupported) {
            throw McpOAuthException("Authorization server does not advertise required PKCE S256 support")
        }
        requireClientId(clientId)
        runCatching { requireRedirectUri(redirectUri) }.getOrElse {
            throw McpOAuthException("OAuth redirect URI is invalid or insecure", it)
        }
        val canonicalResource = runCatching { canonicalMcpResource(resource) }.getOrElse {
            throw McpOAuthException("OAuth resource URL is invalid or insecure", it)
        }
        if (pkce.method != "S256" || !McpOAuthPkce.isValidVerifier(pkce.verifier)) {
            throw McpOAuthException("OAuth authorization requires a valid PKCE S256 pair")
        }
        val expectedChallenge = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pkce.verifier.toByteArray(StandardCharsets.US_ASCII))
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        if (!java.security.MessageDigest.isEqual(
                expectedChallenge.toByteArray(StandardCharsets.US_ASCII),
                pkce.challenge.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            throw McpOAuthException("OAuth PKCE verifier and challenge do not match")
        }
        if (state.length !in 32..512 || state.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw McpOAuthException("OAuth state must be a bounded high-entropy value")
        }
        val parameters = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri.toASCIIString(),
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "resource" to canonicalResource.toASCIIString(),
        )
        scopes.takeIf(Set<String>::isNotEmpty)?.let { selected ->
            parameters["scope"] = validatedScopes(selected).joinToString(" ")
        }
        val encodedParameters = encodeForm(parameters)
        val authorizationUrl = endpoint.toASCIIString() + if (endpoint.rawQuery == null) {
            "?$encodedParameters"
        } else {
            "&$encodedParameters"
        }
        if (authorizationUrl.length > MAX_AUTHORIZATION_QUERY_CHARS) {
            throw McpOAuthException("OAuth authorization URL exceeds the supported length")
        }
        return URI(authorizationUrl)
    }
}

internal fun encodeForm(parameters: Map<String, String>): String = parameters.entries.joinToString("&") { (name, value) ->
    "${formEncode(name)}=${formEncode(value)}"
}

private fun formEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

internal fun requireClientId(clientId: String) {
    if (clientId.isBlank() || clientId.length > 8_192 || clientId.any { it == '\r' || it == '\n' || it == '\u0000' }) {
        throw McpOAuthException("OAuth client ID is missing or exceeds the supported length")
    }
}

internal fun validatedScopes(scopes: Set<String>): Set<String> = scopes
    .onEach { scope ->
        if (scope.isBlank() || scope.length > 256 || !scope.all { it.code in 0x21..0x7e }) {
            throw McpOAuthException("OAuth scope is invalid or exceeds the supported length")
        }
    }
    .take(128)
    .toCollection(linkedSetOf())
    .also {
        if (it.size != scopes.size) throw McpOAuthException("OAuth scope count exceeds the supported limit")
    }

private const val MAX_AUTHORIZATION_QUERY_CHARS = 16_384
