package dev.omnicode.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class McpOAuthCredentialStoreTest {
    @Test
    fun `OAuth session codec round trips credentials without exposing them in toString`() {
        val session = sampleSession()
        assertEquals(session, decodeMcpOAuthSession(encodeMcpOAuthSession(session)))
        assertFalse(session.toString().contains("access-secret"))
        assertFalse(session.toString().contains("refresh-secret"))
        assertFalse(session.toString().contains("client-secret"))
    }

    @Test
    fun `OAuth session decoder rejects insecure and malformed records`() {
        val insecure = encodeMcpOAuthSession(sampleSession()).replace(
            "https://auth.example.com/token",
            "http://auth.example.com/token",
        )
        assertNull(decodeMcpOAuthSession(insecure))
        assertNull(decodeMcpOAuthSession("{not-json"))
        assertNull(decodeMcpOAuthSession(""))
    }

    private fun sampleSession(): McpOAuthStoredSession = McpOAuthStoredSession(
        configurationBinding = "a".repeat(64),
        resource = "https://mcp.example.com/mcp",
        issuer = "https://auth.example.com",
        tokenEndpoint = "https://auth.example.com/token",
        clientId = "omnicode-public",
        clientSecret = "client-secret",
        clientSecretExpiresAtEpochSeconds = 0L,
        tokenEndpointAuthMethod = "client_secret_post",
        redirectUri = "http://127.0.0.1:49152/omnicode/oauth/callback/random",
        accessToken = "access-secret",
        refreshToken = "refresh-secret",
        tokenType = "Bearer",
        scopes = listOf("tools:read", "tools:call"),
        expiresAtEpochMillis = 1_900_000_000_000,
    )
}
