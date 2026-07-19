package dev.omnicode.mcp.oauth

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpOAuthDiscoveryClientTest {
    @Test
    fun `discovers path resource then RFC8414 path issuer and uses challenge scopes`() {
        val requested = mutableListOf<URI>()
        val transport = McpOAuthHttpTransport { request ->
            requested += request.uri
            when (request.uri.toString()) {
                "https://mcp.example/.well-known/oauth-protected-resource/team/mcp" -> jsonResponse(
                    JsonObject().apply {
                        addProperty("resource", "https://mcp.example/team/mcp")
                        add("authorization_servers", strings("https://login.example/tenant"))
                        add("scopes_supported", strings("baseline"))
                    },
                )
                "https://login.example/.well-known/oauth-authorization-server/tenant" -> jsonResponse(
                    authorizationMetadata("https://login.example/tenant"),
                )
                else -> McpOAuthHttpResponse(404, emptyMap(), ByteArray(0))
            }
        }

        val result = McpOAuthDiscoveryClient(transport).discover(
            URI("https://MCP.EXAMPLE:443/team/mcp"),
            listOf("Bearer scope=\"files:read files:write\""),
        )

        assertEquals(URI("https://mcp.example/team/mcp"), result.resource)
        assertEquals(setOf("files:read", "files:write"), result.challengeScopes)
        assertEquals(result.challengeScopes, result.requestedScopes)
        assertEquals(
            listOf(
                URI("https://mcp.example/.well-known/oauth-protected-resource/team/mcp"),
                URI("https://login.example/.well-known/oauth-authorization-server/tenant"),
            ),
            requested,
        )
    }

    @Test
    fun `falls back from path resource metadata to root and from RFC8414 to OIDC`() {
        val requested = mutableListOf<String>()
        val transport = McpOAuthHttpTransport { request ->
            requested += request.uri.toString()
            when (request.uri.toString()) {
                "https://mcp.example/.well-known/oauth-protected-resource" -> jsonResponse(
                    JsonObject().apply {
                        addProperty("resource", "https://mcp.example/mcp")
                        add("authorization_servers", strings("https://login.example"))
                        add("scopes_supported", strings("mcp:use"))
                    },
                )
                "https://login.example/.well-known/openid-configuration" -> jsonResponse(
                    authorizationMetadata("https://login.example"),
                )
                else -> McpOAuthHttpResponse(404, emptyMap(), ByteArray(0))
            }
        }

        val result = McpOAuthDiscoveryClient(transport).discover(URI("https://mcp.example/mcp"))

        assertEquals(setOf("mcp:use"), result.requestedScopes)
        assertEquals(
            listOf(
                "https://mcp.example/.well-known/oauth-protected-resource/mcp",
                "https://mcp.example/.well-known/oauth-protected-resource",
                "https://login.example/.well-known/oauth-authorization-server",
                "https://login.example/.well-known/openid-configuration",
            ),
            requested,
        )
    }

    @Test
    fun `uses challenge metadata URL and rejects resource mixup`() {
        val transport = McpOAuthHttpTransport {
            jsonResponse(
                JsonObject().apply {
                    addProperty("resource", "https://attacker.example/mcp")
                    add("authorization_servers", strings("https://login.example"))
                },
            )
        }
        val error = assertFailsWith<McpOAuthException> {
            McpOAuthDiscoveryClient(transport).discover(
                URI("https://mcp.example/mcp"),
                listOf("Bearer resource_metadata=\"https://mcp.example/custom-metadata\""),
            )
        }
        assertTrue(error.message.orEmpty().contains("does not describe"))
    }

    @Test
    fun `remote MCP challenge cannot redirect metadata discovery to loopback`() {
        var contacted = false
        val client = McpOAuthDiscoveryClient(McpOAuthHttpTransport {
            contacted = true
            error("loopback metadata must never be requested")
        })

        assertFailsWith<McpOAuthException> {
            client.discover(
                URI("https://mcp.example/mcp"),
                listOf("Bearer resource_metadata=\"http://127.0.0.1:9000/private\""),
            )
        }
        assertTrue(!contacted)
    }

    @Test
    fun `rejects insecure authorization endpoints and missing PKCE advertisement`() {
        fun discovery(authMetadata: JsonObject): McpOAuthDiscoveryClient {
            var count = 0
            return McpOAuthDiscoveryClient(McpOAuthHttpTransport {
                count++
                if (count == 1) {
                    jsonResponse(JsonObject().apply {
                        addProperty("resource", "https://mcp.example/mcp")
                        add("authorization_servers", strings("https://login.example"))
                    })
                } else {
                    jsonResponse(authMetadata)
                }
            })
        }

        val insecure = authorizationMetadata("https://login.example").apply {
            addProperty("token_endpoint", "http://127.0.0.1/token")
        }
        assertFailsWith<McpOAuthException> {
            discovery(insecure).discover(URI("https://mcp.example/mcp"))
        }

        val missingPkce = authorizationMetadata("https://login.example").apply {
            remove("code_challenge_methods_supported")
        }
        assertFailsWith<McpOAuthException> {
            discovery(missingPkce).discover(URI("https://mcp.example/mcp"))
        }
    }

    private fun authorizationMetadata(issuer: String): JsonObject = JsonObject().apply {
        addProperty("issuer", issuer)
        addProperty("authorization_endpoint", "https://login.example/authorize")
        addProperty("token_endpoint", "https://login.example/token")
        addProperty("registration_endpoint", "https://login.example/register")
        add("code_challenge_methods_supported", strings("S256"))
        add("token_endpoint_auth_methods_supported", strings("none", "client_secret_post"))
    }
}

internal fun strings(vararg values: String): JsonArray = JsonArray().apply { values.forEach(::add) }

internal fun jsonResponse(json: JsonObject, status: Int = 200): McpOAuthHttpResponse = McpOAuthHttpResponse(
    statusCode = status,
    headers = mapOf("Content-Type" to listOf("application/json")),
    body = json.toString().toByteArray(StandardCharsets.UTF_8),
)
