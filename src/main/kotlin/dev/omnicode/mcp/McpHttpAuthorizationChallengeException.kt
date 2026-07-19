package dev.omnicode.mcp

/**
 * Signals a 401/403 response carrying OAuth Bearer challenge headers.
 *
 * Header values are bounded and redacted by the transport before they reach this exception.
 * Callers can pass [wwwAuthenticate] to `McpOAuthDiscoveryClient.discover`.
 */
class McpHttpAuthorizationChallengeException(
    val statusCode: Int,
    val wwwAuthenticate: List<String>,
) : McpProtocolException(
    if (statusCode == 401) {
        "MCP HTTP authorization is required"
    } else {
        "MCP HTTP authorization has insufficient scope"
    },
)
