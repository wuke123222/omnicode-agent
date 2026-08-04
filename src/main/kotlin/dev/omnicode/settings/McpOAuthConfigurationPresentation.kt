package dev.omnicode.settings

import dev.omnicode.mcp.oauth.McpOAuthClientRegistrationCapability
import dev.omnicode.mcp.oauth.McpOAuthConfigurationPreview

internal data class McpOAuthDiscoveryPresentation(
    val details: String,
    val clientGuidance: String,
)

/** Preserve an explicit least-privilege choice; only fill scopes when the field is empty. */
internal fun discoveredOAuthScopes(
    current: String,
    discovered: Set<String>,
): String = current.takeIf { it.isNotBlank() }
    ?: discovered.joinToString(" ")

internal fun presentMcpOAuthDiscovery(
    preview: McpOAuthConfigurationPreview,
    configuredClientId: String,
): McpOAuthDiscoveryPresentation {
    val scopes = preview.scopes.joinToString(" ").ifBlank { "由授权服务器决定" }
    val clientGuidance = when {
        configuredClientId.isNotBlank() -> "Client ID 已配置；登录时会使用该公开客户端。"
        preview.clientRegistrationCapability == McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION ->
            "支持动态客户端注册；Client ID 可留空，登录时自动注册，凭据仅存 PasswordSafe。"
        preview.clientRegistrationCapability == McpOAuthClientRegistrationCapability.CLIENT_ID_METADATA_DOCUMENT ->
            "需要 Client ID：服务器仅声明 Client ID Metadata Document（当前暂不支持）。请填入服务商提供的公开 Client ID，保存后登录。"
        preview.clientRegistrationCapability == McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION_INCOMPATIBLE ->
            "需要 Client ID：服务器动态注册要求当前不支持的客户端认证方式。请填入服务商提供的公开 Client ID，保存后登录。"
        else ->
            "需要 Client ID：服务器未提供动态客户端注册。请填入服务商提供的公开 Client ID，保存后登录。"
    }
    return McpOAuthDiscoveryPresentation(
        details = buildString {
            appendLine("OAuth 已发现 · ${boundedOAuthText(preview.issuer.toASCIIString())}")
            appendLine("资源元数据：${boundedOAuthText(preview.protectedResourceMetadataUri.toASCIIString())}")
            appendLine("授权元数据：${boundedOAuthText(preview.authorizationServerMetadataUri.toASCIIString())}")
            appendLine("授权端点：${boundedOAuthText(preview.authorizationEndpoint.toASCIIString())}")
            appendLine("Token 端点：${boundedOAuthText(preview.tokenEndpoint.toASCIIString())}")
            preview.registrationEndpoint?.let {
                appendLine("注册端点：${boundedOAuthText(it.toASCIIString())}")
            }
            appendLine("Scopes：${boundedOAuthText(scopes, MAX_SCOPE_SUMMARY_CHARS)}")
            append(clientGuidance)
        },
        clientGuidance = clientGuidance,
    )
}

private fun boundedOAuthText(value: String, maxChars: Int = MAX_URL_SUMMARY_CHARS): String =
    if (value.length <= maxChars) value else value.take(maxChars - 1) + "…"

private const val MAX_URL_SUMMARY_CHARS = 360
private const val MAX_SCOPE_SUMMARY_CHARS = 600
