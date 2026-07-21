package dev.omnicode.service

import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.provider.ReasoningEffort
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import dev.omnicode.settings.OmniCodeSettingsSnapshot
import dev.omnicode.settings.SandboxMode
import dev.omnicode.tool.SandboxCapability
import dev.omnicode.tool.SandboxEnforcement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionDiagnosticsServiceTest {
    @Test
    fun `provider probe uses exact configured endpoint and no credential contract`() = runBlocking {
        val probe = FakeNetworkProbe(
            dns = ConnectionDiagnosticsDnsResult(addressCount = 2),
            endpoint = ConnectionDiagnosticsEndpointResult(statusCode = 401, tlsEstablished = true),
        )
        val report = service(probe).diagnose(
            input(
                credentials = ConnectionDiagnosticsProviderCredentials(apiKeyConfigured = true),
            ),
        )

        assertEquals(listOf("api.example.test"), probe.resolvedHosts)
        assertEquals(listOf(URI("https://api.example.test/v1")), probe.probedEndpoints)
        assertStatus(report, "provider.credentials", ConnectionDiagnosticStatus.PASS)
        assertStatus(report, "network.dns", ConnectionDiagnosticStatus.PASS)
        assertStatus(report, "network.tls_http", ConnectionDiagnosticStatus.PASS)
        assertStatus(report, "model.tools", ConnectionDiagnosticStatus.PASS)
        assertStatus(report, "model.vision", ConnectionDiagnosticStatus.PASS)
        assertTrue(report.checks.all { it.durationMillis >= 0L })
    }

    @Test
    fun `unsafe Base URL blocks every network probe and exports no embedded secret`() = runBlocking {
        val probe = FakeNetworkProbe()
        val secret = "sk-proj-0123456789-secret"
        val report = service(probe).diagnose(
            input().copy(
                provider = settings("https://user:$secret@api.example.test/v1?api_key=$secret"),
                providerCredentials = ConnectionDiagnosticsProviderCredentials(apiKeyConfigured = true),
            ),
        )

        assertTrue(probe.resolvedHosts.isEmpty())
        assertTrue(probe.probedEndpoints.isEmpty())
        assertStatus(report, "provider.base_url", ConnectionDiagnosticStatus.FAIL)
        assertStatus(report, "network.dns", ConnectionDiagnosticStatus.SKIP)
        assertStatus(report, "network.tls_http", ConnectionDiagnosticStatus.SKIP)
        val exported = ConnectionDiagnosticsExporter(userHome = "/Users/tester").export(report)
        assertFalse(exported.markdown.contains(secret))
        assertFalse(exported.json.contains(secret))
        assertFalse(exported.markdown.contains("api_key", ignoreCase = true))
    }

    @Test
    fun `DNS timeout is bounded while independent endpoint diagnostics still run`() = runBlocking {
        val probe = object : ConnectionDiagnosticsNetworkProbe {
            var endpointCalls = 0

            override suspend fun resolve(host: String, timeoutMillis: Long): ConnectionDiagnosticsDnsResult {
                delay(5_000L)
                return ConnectionDiagnosticsDnsResult(addressCount = 1)
            }

            override suspend fun probe(
                endpoint: URI,
                connectTimeoutMillis: Long,
                requestTimeoutMillis: Long,
            ): ConnectionDiagnosticsEndpointResult {
                endpointCalls++
                return ConnectionDiagnosticsEndpointResult(statusCode = 200, tlsEstablished = true)
            }
        }
        val started = System.nanoTime()
        val report = service(
            probe,
            timeouts = ConnectionDiagnosticsTimeouts(dnsMillis = 100, connectMillis = 100, requestMillis = 200),
        ).diagnose(input())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L

        assertTrue(elapsedMillis < 2_000L, "injected DNS work must be cut off by the service timeout")
        assertStatus(report, "network.dns", ConnectionDiagnosticStatus.FAIL)
        assertStatus(report, "network.tls_http", ConnectionDiagnosticStatus.PASS)
        assertEquals(1, probe.endpointCalls)
    }

    @Test
    fun `MCP inspection is static and reports OAuth metadata completeness`() = runBlocking {
        val probe = FakeNetworkProbe()
        val stdio = McpServerConfig(
            id = "stdio-1",
            name = "Local MCP",
            enabled = true,
            command = "",
            arguments = emptyList(),
            environmentKeys = setOf("SERVICE_TOKEN"),
            workingDirectory = ".",
            transport = McpTransport.STDIO,
        )
        val oauth = McpServerConfig(
            id = "oauth-1",
            name = "Remote MCP",
            enabled = true,
            command = "",
            arguments = emptyList(),
            environmentKeys = emptySet(),
            workingDirectory = ".",
            transport = McpTransport.HTTP,
            url = "https://mcp.example.test/rpc",
            httpAuthMode = McpHttpAuthMode.OAUTH,
            oauthScopes = listOf("tools.read"),
        )
        val report = service(probe).diagnose(
            input().copy(
                mcpServers = listOf(stdio, oauth),
                mcpCredentialsByServerId = mapOf(
                    "stdio-1" to ConnectionDiagnosticsMcpCredentials(configuredEnvironmentSecretCount = 0),
                    "oauth-1" to ConnectionDiagnosticsMcpCredentials(
                        oauth = ConnectionDiagnosticsOAuthState(
                            sessionPresent = true,
                            configurationMatches = true,
                            resourceMetadataPresent = true,
                            issuerMetadataPresent = true,
                            tokenEndpointMetadataPresent = false,
                            clientIdPresent = true,
                            accessTokenPresent = true,
                        ),
                    ),
                ),
            ),
        )

        assertStatus(report, "mcp.1.configuration", ConnectionDiagnosticStatus.FAIL)
        assertStatus(report, "mcp.1.credentials", ConnectionDiagnosticStatus.WARN)
        assertStatus(report, "mcp.2.configuration", ConnectionDiagnosticStatus.PASS)
        assertStatus(report, "mcp.2.oauth_metadata", ConnectionDiagnosticStatus.FAIL)
        assertEquals(1, probe.probedEndpoints.size, "MCP endpoints must never enter the network probe")
        assertEquals(URI("https://api.example.test/v1"), probe.probedEndpoints.single())
    }

    @Test
    fun `danger full access and missing sandbox backend are visible warnings and failures`() = runBlocking {
        val unavailable = SandboxCapability(
            mode = SandboxMode.WORKSPACE_WRITE,
            enforcement = SandboxEnforcement.UNAVAILABLE,
            available = false,
            enforced = false,
            summary = "/Users/private/sandbox backend unavailable",
        )
        val report = service(FakeNetworkProbe(), capability = unavailable).diagnose(
            input().copy(sandboxMode = SandboxMode.DANGER_FULL_ACCESS),
        )

        assertStatus(report, "sandbox.mode", ConnectionDiagnosticStatus.WARN)
        assertStatus(report, "sandbox.enforcement", ConnectionDiagnosticStatus.FAIL)
        assertTrue(report.checks.none { it.summary.contains("/Users/private") })
    }

    @Test
    fun `proxy properties are validated without retaining host or password values`() = runBlocking {
        val proxyPassword = "proxy-password-must-not-leak"
        val report = service(
            FakeNetworkProbe(),
            properties = mapOf(
                "https.proxyHost" to "proxy.private.example",
                "https.proxyPort" to "not-a-port",
                "https.proxyPassword" to proxyPassword,
            ),
        ).diagnose(input())

        assertStatus(report, "network.proxy", ConnectionDiagnosticStatus.FAIL)
        val export = ConnectionDiagnosticsExporter(userHome = null).export(report)
        assertFalse(export.markdown.contains(proxyPassword))
        assertFalse(export.json.contains(proxyPassword))
        assertFalse(export.markdown.contains("proxy.private.example"))
    }

    @Test
    fun `local model classification identifies specialized models and a valid vision helper`() = runBlocking {
        val report = service(FakeNetworkProbe()).diagnose(
            input().copy(
                provider = settings(model = "text-embedding-3-large"),
                visionAssistantModel = "gpt-4.1-mini",
            ),
        )

        assertStatus(report, "model.tools", ConnectionDiagnosticStatus.FAIL)
        assertStatus(report, "model.vision", ConnectionDiagnosticStatus.WARN)
        assertStatus(report, "model.vision_assistant", ConnectionDiagnosticStatus.PASS)
    }

    private fun service(
        probe: ConnectionDiagnosticsNetworkProbe,
        timeouts: ConnectionDiagnosticsTimeouts = ConnectionDiagnosticsTimeouts(),
        properties: Map<String, String> = emptyMap(),
        capability: SandboxCapability = SandboxCapability(
            mode = SandboxMode.WORKSPACE_WRITE,
            enforcement = SandboxEnforcement.MACOS_SANDBOX_EXEC,
            available = true,
            enforced = true,
            summary = "available",
        ),
    ): ConnectionDiagnosticsService {
        var nanos = 0L
        return ConnectionDiagnosticsService(
            networkProbe = probe,
            timeouts = timeouts,
            propertyReader = properties::get,
            environmentReader = { null },
            sandboxCapability = { capability },
            clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
            nanoTime = {
                nanos += 1_000_000L
                nanos
            },
        )
    }

    private fun input(
        credentials: ConnectionDiagnosticsProviderCredentials = ConnectionDiagnosticsProviderCredentials(
            apiKeyConfigured = true,
        ),
    ) = ConnectionDiagnosticsInput(
        provider = settings(),
        providerCredentials = credentials,
        sandboxMode = SandboxMode.WORKSPACE_WRITE,
    )

    private fun settings(
        baseUrl: String = "https://api.example.test/v1",
        model: String = "gpt-5.6-sol",
    ) = OmniCodeSettingsSnapshot(
        providerId = "openai",
        baseUrl = baseUrl,
        model = model,
        region = "us-east-1",
        apiVersion = "2025-04-01-preview",
        maxOutputTokens = 8_192,
        reasoningEffort = ReasoningEffort.AUTO,
    )

    private fun assertStatus(
        report: ConnectionDiagnosticsReport,
        id: String,
        expected: ConnectionDiagnosticStatus,
    ) {
        val check = assertNotNull(report.checks.firstOrNull { it.id == id }, "missing check $id")
        assertEquals(expected, check.status, "$id: ${check.summary}")
    }

    private class FakeNetworkProbe(
        private val dns: ConnectionDiagnosticsDnsResult = ConnectionDiagnosticsDnsResult(addressCount = 1),
        private val endpoint: ConnectionDiagnosticsEndpointResult =
            ConnectionDiagnosticsEndpointResult(statusCode = 200, tlsEstablished = true),
    ) : ConnectionDiagnosticsNetworkProbe {
        val resolvedHosts = mutableListOf<String>()
        val probedEndpoints = mutableListOf<URI>()

        override suspend fun resolve(host: String, timeoutMillis: Long): ConnectionDiagnosticsDnsResult {
            resolvedHosts += host
            return dns
        }

        override suspend fun probe(
            endpoint: URI,
            connectTimeoutMillis: Long,
            requestTimeoutMillis: Long,
        ): ConnectionDiagnosticsEndpointResult {
            probedEndpoints += endpoint
            return this.endpoint
        }
    }
}
