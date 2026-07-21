package dev.omnicode.service

import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.OmniCodeSettingsSnapshot
import dev.omnicode.settings.SandboxMode
import java.time.Instant

enum class ConnectionDiagnosticStatus {
    PASS,
    WARN,
    FAIL,
    SKIP,
}

enum class ConnectionDiagnosticCategory {
    PROVIDER,
    NETWORK,
    MODEL,
    MCP,
    SANDBOX,
}

data class ConnectionDiagnosticCheck(
    val id: String,
    val category: ConnectionDiagnosticCategory,
    val title: String,
    val status: ConnectionDiagnosticStatus,
    val summary: String,
    val durationMillis: Long,
    val recoverySuggestion: String? = null,
) {
    init {
        require(id.matches(Regex("[a-z0-9._-]{1,120}"))) { "Invalid diagnostic check id" }
        require(title.isNotBlank() && title.length <= 160) { "Invalid diagnostic title" }
        require(summary.isNotBlank() && summary.length <= 2_000) { "Invalid diagnostic summary" }
        require(durationMillis >= 0L) { "Diagnostic duration must not be negative" }
        require(recoverySuggestion == null || recoverySuggestion.length <= 2_000) {
            "Diagnostic recovery suggestion is too long"
        }
    }
}

data class ConnectionDiagnosticsReport(
    val generatedAt: Instant,
    val durationMillis: Long,
    val checks: List<ConnectionDiagnosticCheck>,
    val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1) { "Unsupported connection diagnostics schema" }
        require(durationMillis >= 0L) { "Report duration must not be negative" }
        require(checks.size <= 512) { "Connection diagnostics report contains too many checks" }
        require(checks.map(ConnectionDiagnosticCheck::id).distinct().size == checks.size) {
            "Diagnostic check ids must be unique"
        }
    }

    val overallStatus: ConnectionDiagnosticStatus
        get() = when {
            checks.any { it.status == ConnectionDiagnosticStatus.FAIL } -> ConnectionDiagnosticStatus.FAIL
            checks.any { it.status == ConnectionDiagnosticStatus.WARN } -> ConnectionDiagnosticStatus.WARN
            checks.any { it.status == ConnectionDiagnosticStatus.PASS } -> ConnectionDiagnosticStatus.PASS
            else -> ConnectionDiagnosticStatus.SKIP
        }

    fun count(status: ConnectionDiagnosticStatus): Int = checks.count { it.status == status }
}

/** Secret presence only. Actual credential values must never enter a diagnostics request. */
data class ConnectionDiagnosticsProviderCredentials(
    val apiKeyConfigured: Boolean = false,
    val secondarySecretConfigured: Boolean = false,
    val sessionTokenConfigured: Boolean = false,
    val environmentApiKeyConfigured: Boolean = false,
    val environmentCredentialBlocked: Boolean = false,
    val awsCredentialPairConfigured: Boolean = false,
    val inspectionFailed: Boolean = false,
)

/** A secret-free view of locally cached OAuth metadata and token readiness. */
data class ConnectionDiagnosticsOAuthState(
    val sessionPresent: Boolean = false,
    val configurationMatches: Boolean = false,
    val resourceMetadataPresent: Boolean = false,
    val issuerMetadataPresent: Boolean = false,
    val tokenEndpointMetadataPresent: Boolean = false,
    val clientIdPresent: Boolean = false,
    val accessTokenPresent: Boolean = false,
    val refreshTokenPresent: Boolean = false,
    val accessTokenExpired: Boolean = false,
)

/** Secret presence and cached metadata for one MCP entry, keyed by its opaque server id. */
data class ConnectionDiagnosticsMcpCredentials(
    val inspectionAvailable: Boolean = true,
    val bearerTokenConfigured: Boolean = false,
    val configuredEnvironmentSecretCount: Int = 0,
    val oauth: ConnectionDiagnosticsOAuthState = ConnectionDiagnosticsOAuthState(),
) {
    init {
        require(configuredEnvironmentSecretCount >= 0) { "MCP secret count must not be negative" }
    }
}

data class ConnectionDiagnosticsInput(
    val provider: OmniCodeSettingsSnapshot,
    val providerCredentials: ConnectionDiagnosticsProviderCredentials,
    val visionAssistantModel: String = "",
    val mcpServers: List<McpServerConfig> = emptyList(),
    val mcpCredentialsByServerId: Map<String, ConnectionDiagnosticsMcpCredentials> = emptyMap(),
    val sandboxMode: SandboxMode = SandboxMode.DEFAULT,
)

data class ConnectionDiagnosticsTimeouts(
    val dnsMillis: Long = 2_500L,
    val connectMillis: Long = 3_000L,
    val requestMillis: Long = 5_000L,
) {
    init {
        require(dnsMillis in 100L..10_000L) { "DNS timeout must be between 100 ms and 10 seconds" }
        require(connectMillis in 100L..10_000L) { "Connect timeout must be between 100 ms and 10 seconds" }
        require(requestMillis in connectMillis..15_000L) {
            "Request timeout must be at least the connect timeout and at most 15 seconds"
        }
    }
}

enum class ConnectionDiagnosticsNetworkFailure {
    DNS_NOT_FOUND,
    TIMEOUT,
    TLS,
    CONNECTION,
    PROTOCOL,
    OTHER,
}

data class ConnectionDiagnosticsDnsResult(
    val addressCount: Int = 0,
    val failure: ConnectionDiagnosticsNetworkFailure? = null,
) {
    init {
        require(addressCount >= 0) { "DNS address count must not be negative" }
        require((failure == null) == (addressCount > 0)) {
            "DNS result must contain either resolved addresses or a failure"
        }
    }
}

data class ConnectionDiagnosticsEndpointResult(
    val statusCode: Int? = null,
    val tlsEstablished: Boolean = false,
    val failure: ConnectionDiagnosticsNetworkFailure? = null,
) {
    init {
        require(statusCode == null || statusCode in 100..599) { "Invalid HTTP status code" }
        require(failure == null || statusCode == null) { "Endpoint result cannot be both successful and failed" }
        require(failure != null || statusCode != null) { "Endpoint result must contain a status or failure" }
    }
}

data class ConnectionDiagnosticsExport(
    val markdown: String,
    val json: String,
)
