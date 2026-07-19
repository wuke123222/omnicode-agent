package dev.omnicode.mcp

import com.intellij.openapi.project.Project
import dev.omnicode.settings.McpBearerTokenReader
import dev.omnicode.settings.McpHttpCredentialStore
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.mcp.oauth.McpOAuthSessionManager
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import java.nio.charset.StandardCharsets

data class McpHttpApprovalRequest(
    val serverName: String,
    val endpoint: String,
    val bearerTokenConfigured: Boolean,
    val fingerprint: String,
    val authenticationDescription: String = if (bearerTokenConfigured) {
        "PasswordSafe 中的 Bearer Token"
    } else {
        "未配置 Bearer Token"
    },
) {
    fun details(): String = buildString {
        appendLine("服务器：$serverName")
        appendLine("MCP Endpoint：$endpoint")
        appendLine("认证：$authenticationDescription")
        append("配置指纹：${fingerprint.take(16)}…")
    }

    fun risk(): String =
        "这会连接外部 MCP 服务并向其公开后续 MCP 工具调用参数。服务可能产生网络或系统副作用。"
}

fun interface McpHttpApprovalGate {
    suspend fun approveMcpHttp(request: McpHttpApprovalRequest): McpLaunchApprovalDecision
}

internal fun ApprovalGate.asMcpHttpApprovalGate(): McpHttpApprovalGate =
    (this as? McpHttpApprovalGate) ?: McpHttpApprovalGate { request ->
        if (
            approve(
                ApprovalRequest(
                    toolName = "mcp_http_connect",
                    title = "连接 MCP 服务 ${request.serverName}",
                    details = request.details(),
                    risk = request.risk(),
                ),
            )
        ) {
            McpLaunchApprovalDecision.ALLOW_ONCE
        } else {
            McpLaunchApprovalDecision.REJECT
        }
    }

fun interface McpHttpClientConnector {
    suspend fun connect(config: McpServerConfig): McpClient
}

class ApprovedMcpHttpClientConnector internal constructor(
    private val approvalGate: McpHttpApprovalGate,
    private val trustStore: McpLaunchTrustStore,
    private val projectId: String,
    private val tokenReader: McpBearerTokenReader,
    private val clientFactory: suspend (McpServerConfig, String) -> McpClient,
    private val oauthTokenReader: suspend (McpServerConfig) -> String = { "" },
    private val oauthTokenRefresher: suspend (McpServerConfig, List<String>) -> String = { config, _ ->
        oauthTokenReader(config)
    },
) : McpHttpClientConnector {
    constructor(project: Project, approvalGate: ApprovalGate) : this(
        project,
        approvalGate,
        McpOAuthSessionManager(),
    )

    private constructor(
        project: Project,
        approvalGate: ApprovalGate,
        oauthSessions: McpOAuthSessionManager,
    ) : this(
        approvalGate = approvalGate.asMcpHttpApprovalGate(),
        trustStore = SettingsMcpLaunchTrustStore(),
        projectId = mcpProjectIdentity(project),
        tokenReader = McpHttpCredentialStore.getInstance(),
        clientFactory = { config, token -> McpStreamableHttpClient.connect(config, token) },
        oauthTokenReader = { config -> oauthSessions.accessToken(config) },
        oauthTokenRefresher = { config, challenge ->
            oauthSessions.accessToken(config, forceRefresh = true, wwwAuthenticate = challenge)
        },
    )

    override suspend fun connect(config: McpServerConfig): McpClient {
        val endpoint = validateMcpHttpEndpoint(config.url).toASCIIString()
        val fingerprint = mcpHttpConnectionFingerprint(config, endpoint)
        val tokenConfigured = config.httpAuthMode == McpHttpAuthMode.BEARER && tokenReader.load(config.id).isNotBlank()
        val request = McpHttpApprovalRequest(
            serverName = config.name,
            endpoint = endpoint,
            bearerTokenConfigured = tokenConfigured,
            fingerprint = fingerprint,
            authenticationDescription = when (config.httpAuthMode) {
                McpHttpAuthMode.NONE -> "无认证"
                McpHttpAuthMode.BEARER -> if (tokenConfigured) "PasswordSafe 中的 Bearer Token" else "未配置 Bearer Token"
                McpHttpAuthMode.OAUTH -> "OAuth 2.1 / PKCE 访问令牌"
            },
        )
        if (!trustStore.isTrusted(config.id, projectId, fingerprint)) {
            when (approvalGate.approveMcpHttp(request)) {
                McpLaunchApprovalDecision.ALLOW_ONCE -> Unit
                McpLaunchApprovalDecision.TRUST_CONFIGURATION ->
                    trustStore.trust(config.id, projectId, fingerprint)
                McpLaunchApprovalDecision.REJECT -> throw McpHttpConnectionRejectedException(config.name)
            }
        }
        // Resolve credentials only after network approval. OAuth refresh can contact the
        // authorization server, so it must not happen before this external side effect is gated.
        val normalized = config.copy(url = endpoint)
        val token = when (config.httpAuthMode) {
            McpHttpAuthMode.NONE -> ""
            McpHttpAuthMode.BEARER -> tokenReader.load(config.id)
            McpHttpAuthMode.OAUTH -> oauthTokenReader(normalized)
        }
        return try {
            clientFactory(normalized, token)
        } catch (challenge: McpHttpAuthorizationChallengeException) {
            if (config.httpAuthMode != McpHttpAuthMode.OAUTH || challenge.statusCode != 401) throw challenge
            val refreshed = oauthTokenRefresher(normalized, challenge.wwwAuthenticate)
            clientFactory(normalized, refreshed)
        }
    }
}

class McpHttpConnectionRejectedException(serverName: String) : IllegalStateException(
    "MCP HTTP server '$serverName' was not connected because external network approval was rejected.",
)

internal fun mcpHttpConnectionFingerprint(config: McpServerConfig, normalizedEndpoint: String): String = sha256Hex(
    buildString {
        append("mcp-http\u0000v2\u0000")
        append(config.id).append('\u0000')
        append(config.name).append('\u0000')
        append(normalizedEndpoint).append('\u0000')
        append(config.httpAuthMode.id).append('\u0000')
        append(config.oauthClientId).append('\u0000')
        append(config.oauthScopes.joinToString(" "))
    }.toByteArray(StandardCharsets.UTF_8),
)
