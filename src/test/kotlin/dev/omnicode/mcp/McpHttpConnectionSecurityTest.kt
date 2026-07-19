package dev.omnicode.mcp

import com.google.gson.JsonObject
import dev.omnicode.settings.McpBearerTokenReader
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpHttpConnectionSecurityTest {
    @Test
    fun `network approval happens before the HTTP client is created and trust is fingerprint scoped`() = runBlocking {
        val events = mutableListOf<String>()
        val trust = InMemoryMcpTrustStore()
        var approvals = 0
        val connector = ApprovedMcpHttpClientConnector(
            approvalGate = McpHttpApprovalGate {
                approvals++
                events += "approval:${it.endpoint}"
                McpLaunchApprovalDecision.TRUST_CONFIGURATION
            },
            trustStore = trust,
            projectId = "project",
            tokenReader = McpBearerTokenReader { "token-from-password-safe" },
            clientFactory = { config, token ->
                events += "connect:${config.url}:$token"
                FakeHttpMcpClient(config)
            },
        )
        val first = config("https://mcp.example.com/api")

        connector.connect(first).close()
        connector.connect(first).close()
        connector.connect(first.copy(url = "https://mcp.example.com/changed")).close()

        assertEquals(2, approvals)
        assertTrue(events.first().startsWith("approval:"))
        assertEquals(3, events.count { it.startsWith("connect:") })
        assertTrue(events.filter { it.startsWith("connect:") }.all { it.endsWith(":token-from-password-safe") })
    }

    @Test
    fun `rejected network approval never creates a client`() = runBlocking {
        var connected = false
        val connector = ApprovedMcpHttpClientConnector(
            approvalGate = McpHttpApprovalGate { McpLaunchApprovalDecision.REJECT },
            trustStore = InMemoryMcpTrustStore(),
            projectId = "project",
            tokenReader = McpBearerTokenReader { "" },
            clientFactory = { config, _ ->
                connected = true
                FakeHttpMcpClient(config)
            },
        )

        assertFailsWith<McpHttpConnectionRejectedException> {
            connector.connect(config("https://mcp.example.com/api"))
        }
        assertTrue(!connected)
    }

    @Test
    fun `OAuth token refresh happens only after external connection approval`() = runBlocking {
        val events = mutableListOf<String>()
        val connector = ApprovedMcpHttpClientConnector(
            approvalGate = McpHttpApprovalGate {
                events += "approval"
                McpLaunchApprovalDecision.ALLOW_ONCE
            },
            trustStore = InMemoryMcpTrustStore(),
            projectId = "project",
            tokenReader = McpBearerTokenReader { error("Bearer store must not be used for OAuth") },
            clientFactory = { config, token ->
                events += "connect:$token"
                FakeHttpMcpClient(config)
            },
            oauthTokenReader = {
                events += "oauth-token"
                "oauth-access"
            },
        )

        connector.connect(config("https://mcp.example.com/api").copy(httpAuthMode = McpHttpAuthMode.OAUTH)).close()

        assertEquals(listOf("approval", "oauth-token", "connect:oauth-access"), events)
    }

    @Test
    fun `HTTP trust fingerprint includes authentication configuration`() {
        val base = config("https://mcp.example.com/api")
        val endpoint = base.url
        val bearer = mcpHttpConnectionFingerprint(base, endpoint)
        val oauth = mcpHttpConnectionFingerprint(
            base.copy(
                httpAuthMode = McpHttpAuthMode.OAUTH,
                oauthClientId = "public-client",
                oauthScopes = listOf("tools:read"),
            ),
            endpoint,
        )

        assertTrue(bearer != oauth)
    }

    @Test
    fun `OAuth 401 refreshes once with the bounded challenge before reconnecting`() = runBlocking {
        val tokens = mutableListOf<String>()
        val connector = ApprovedMcpHttpClientConnector(
            approvalGate = McpHttpApprovalGate { McpLaunchApprovalDecision.ALLOW_ONCE },
            trustStore = InMemoryMcpTrustStore(),
            projectId = "project",
            tokenReader = McpBearerTokenReader { "" },
            clientFactory = { config, token ->
                tokens += token
                if (tokens.size == 1) {
                    throw McpHttpAuthorizationChallengeException(
                        401,
                        listOf("Bearer resource_metadata=\"https://mcp.example.com/oauth-resource\""),
                    )
                }
                FakeHttpMcpClient(config)
            },
            oauthTokenReader = { "expired" },
            oauthTokenRefresher = { _, challenge ->
                assertEquals(1, challenge.size)
                "refreshed"
            },
        )

        connector.connect(config("https://mcp.example.com/api").copy(httpAuthMode = McpHttpAuthMode.OAUTH)).close()

        assertEquals(listOf("expired", "refreshed"), tokens)
    }

    private fun config(url: String): McpServerConfig = McpServerConfig(
        id = "server",
        name = "Remote MCP",
        enabled = true,
        command = "",
        arguments = emptyList(),
        environmentKeys = emptySet(),
        workingDirectory = ".",
        transport = McpTransport.HTTP,
        url = url,
    )
}

private class InMemoryMcpTrustStore : McpLaunchTrustStore {
    private val values = mutableSetOf<Triple<String, String, String>>()

    override fun isTrusted(serverId: String, projectId: String, fingerprint: String): Boolean =
        Triple(serverId, projectId, fingerprint) in values

    override fun trust(serverId: String, projectId: String, fingerprint: String) {
        values.removeIf { it.first == serverId && it.second == projectId }
        values += Triple(serverId, projectId, fingerprint)
    }
}

private class FakeHttpMcpClient(
    override val config: McpServerConfig,
) : McpClient {
    override suspend fun listTools(): List<McpToolDescriptor> = emptyList()

    override suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult =
        McpToolCallResult("ok", false)

    override fun close() = Unit
}
