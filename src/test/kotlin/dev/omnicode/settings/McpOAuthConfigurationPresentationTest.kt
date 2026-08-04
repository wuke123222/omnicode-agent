package dev.omnicode.settings

import dev.omnicode.mcp.oauth.McpOAuthClientRegistrationCapability
import dev.omnicode.mcp.oauth.McpOAuthConfigurationPreview
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpOAuthConfigurationPresentationTest {
    @Test
    fun `discovered scopes fill an empty field but preserve explicit least privilege scopes`() {
        val discovered = linkedSetOf("tools:read", "tools:write")

        assertEquals("tools:read tools:write", discoveredOAuthScopes("", discovered))
        assertEquals("tools:read", discoveredOAuthScopes("tools:read", discovered))
    }

    @Test
    fun `presentation distinguishes automatic registration from manual client id setup`() {
        val dynamic = presentMcpOAuthDiscovery(
            preview(McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION),
            configuredClientId = "",
        )
        val manual = presentMcpOAuthDiscovery(
            preview(McpOAuthClientRegistrationCapability.MANUAL_CLIENT_ID),
            configuredClientId = "",
        )

        assertTrue(dynamic.clientGuidance.contains("自动注册"))
        assertTrue(dynamic.details.contains("Token 端点"))
        assertTrue(manual.clientGuidance.contains("请填入服务商提供的公开 Client ID"))
    }

    private fun preview(capability: McpOAuthClientRegistrationCapability): McpOAuthConfigurationPreview =
        McpOAuthConfigurationPreview(
            resource = URI("https://mcp.example/mcp"),
            protectedResourceMetadataUri = URI("https://mcp.example/.well-known/oauth-protected-resource/mcp"),
            issuer = URI("https://auth.example"),
            authorizationServerMetadataUri = URI("https://auth.example/.well-known/oauth-authorization-server"),
            authorizationEndpoint = URI("https://auth.example/authorize"),
            tokenEndpoint = URI("https://auth.example/token"),
            registrationEndpoint = if (
                capability == McpOAuthClientRegistrationCapability.DYNAMIC_REGISTRATION
            ) {
                URI("https://auth.example/register")
            } else {
                null
            },
            scopes = linkedSetOf("tools:read"),
            clientRegistrationCapability = capability,
        )
}
