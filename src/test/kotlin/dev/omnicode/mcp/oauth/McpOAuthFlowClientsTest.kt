package dev.omnicode.mcp.oauth

import com.google.gson.JsonObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthFlowClientsTest {
    @Test
    fun `authorization URL includes resource PKCE state and scopes`() {
        val pkce = McpOAuthPkce.generate()
        val state = McpOAuthState.generate()
        val uri = McpOAuthAuthorization.buildUrl(
            metadata = metadata(),
            clientId = "client-1",
            redirectUri = URI("http://127.0.0.1:32123/callback"),
            resource = URI("https://MCP.example:443/mcp"),
            scopes = linkedSetOf("files:read", "files:write"),
            pkce = pkce,
            state = state,
        )
        val query = parseForm(uri.rawQuery)
        assertEquals("code", query["response_type"])
        assertEquals("https://mcp.example/mcp", query["resource"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(pkce.challenge, query["code_challenge"])
        assertEquals(state, query["state"])
        assertEquals("files:read files:write", query["scope"])
    }

    @Test
    fun `token exchange sends resource and client secret only in POST body`() {
        lateinit var captured: McpOAuthHttpRequest
        val transport = McpOAuthHttpTransport { request ->
            captured = request
            jsonResponse(JsonObject().apply {
                addProperty("access_token", "access-secret")
                addProperty("refresh_token", "refresh-secret")
                addProperty("token_type", "Bearer")
                addProperty("scope", "files:read")
                addProperty("expires_in", 60)
            })
        }
        val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC)
        val tokens = McpOAuthTokenClient(transport, clock).exchangeAuthorizationCode(
            McpAuthorizationCodeRequest(
                metadata = metadata(),
                clientId = "client-1",
                clientSecret = "client-secret",
                tokenEndpointAuthMethod = McpTokenEndpointAuthMethod.CLIENT_SECRET_POST,
                code = "authorization-code",
                codeVerifier = McpOAuthPkce.generate().verifier,
                redirectUri = URI("http://127.0.0.1:32123/callback"),
                resource = URI("https://mcp.example/mcp"),
            ),
        )

        val form = parseForm(captured.body!!.toString(StandardCharsets.UTF_8))
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("https://mcp.example/mcp", form["resource"])
        assertEquals("client-secret", form["client_secret"])
        assertFalse(captured.uri.toString().contains("client-secret"))
        assertEquals("access-secret", tokens.accessToken)
        assertEquals("refresh-secret", tokens.refreshToken)
        assertEquals(1_060_000, tokens.expiresAtEpochMillis)
    }

    @Test
    fun `refresh includes resource and permits rotated token omission`() {
        lateinit var captured: McpOAuthHttpRequest
        val client = McpOAuthTokenClient(
            McpOAuthHttpTransport { request ->
                captured = request
                jsonResponse(JsonObject().apply {
                    addProperty("access_token", "new-access")
                    addProperty("token_type", "Bearer")
                })
            },
            Clock.systemUTC(),
        )
        val tokens = client.refresh(
            McpRefreshTokenRequest(
                metadata = metadata(),
                clientId = "public-client",
                refreshToken = "old-refresh",
                resource = URI("https://mcp.example/mcp"),
            ),
        )
        val form = parseForm(captured.body!!.toString(StandardCharsets.UTF_8))
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("old-refresh", form["refresh_token"])
        assertEquals("https://mcp.example/mcp", form["resource"])
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `token endpoint errors never expose request secrets or hostile descriptions`() {
        val client = McpOAuthTokenClient(
            McpOAuthHttpTransport {
                jsonResponse(JsonObject().apply {
                    addProperty("error", "invalid_grant")
                    addProperty("error_description", "rejected authorization-code and client-secret")
                }, status = 400)
            },
            Clock.systemUTC(),
        )
        val error = assertFailsWith<McpOAuthException> {
            client.exchangeAuthorizationCode(
                McpAuthorizationCodeRequest(
                    metadata = metadata(),
                    clientId = "client-1",
                    clientSecret = "client-secret",
                    tokenEndpointAuthMethod = McpTokenEndpointAuthMethod.CLIENT_SECRET_POST,
                    code = "authorization-code",
                    codeVerifier = McpOAuthPkce.generate().verifier,
                    redirectUri = URI("http://127.0.0.1:32123/callback"),
                    resource = URI("https://mcp.example/mcp"),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("invalid_grant"))
        assertFalse(error.message.orEmpty().contains("authorization-code"))
        assertFalse(error.message.orEmpty().contains("client-secret"))
    }

    @Test
    fun `dynamic registration defaults to public client and returns credentials metadata`() {
        lateinit var captured: McpOAuthHttpRequest
        val registration = McpOAuthDynamicRegistrationClient(McpOAuthHttpTransport { request ->
            captured = request
            jsonResponse(JsonObject().apply {
                addProperty("client_id", "registered-client")
                addProperty("client_secret", "registered-secret")
                addProperty("client_secret_expires_at", 2_000_000_000L)
                add("redirect_uris", strings("http://127.0.0.1:8123/callback"))
                addProperty("token_endpoint_auth_method", "client_secret_post")
            }, status = 201)
        }).register(
            metadata(),
            McpDynamicClientRegistrationRequest(
                redirectUris = listOf(URI("http://127.0.0.1:8123/callback")),
            ),
        )

        val requestJson = com.google.gson.JsonParser.parseString(
            captured.body!!.toString(StandardCharsets.UTF_8),
        ).asJsonObject
        assertEquals("none", requestJson.get("token_endpoint_auth_method").asString)
        assertEquals("registered-client", registration.clientId)
        assertEquals("registered-secret", registration.clientSecret)
        assertEquals(2_000_000_000L, registration.clientSecretExpiresAtEpochSeconds)
        assertEquals("client_secret_post", registration.tokenEndpointAuthMethod)
    }

    private fun metadata(): McpAuthorizationServerMetadata = McpAuthorizationServerMetadata(
        issuer = URI("https://login.example"),
        authorizationEndpoint = URI("https://login.example/authorize"),
        tokenEndpoint = URI("https://login.example/token"),
        registrationEndpoint = URI("https://login.example/register"),
        codeChallengeMethodsSupported = setOf("S256"),
        scopesSupported = emptySet(),
        tokenEndpointAuthMethodsSupported = setOf("none", "client_secret_post"),
        clientIdMetadataDocumentSupported = false,
        metadataUri = URI("https://login.example/.well-known/oauth-authorization-server"),
    )

    private fun parseForm(value: String): Map<String, String> = value.split('&').associate { pair ->
        val (name, encoded) = pair.split('=', limit = 2)
        URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(encoded, StandardCharsets.UTF_8)
    }
}
