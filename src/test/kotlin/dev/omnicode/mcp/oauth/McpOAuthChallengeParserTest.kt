package dev.omnicode.mcp.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthChallengeParserTest {
    @Test
    fun `parses bearer challenge among other schemes and preserves quoted commas`() {
        val challenge = McpOAuthChallengeParser.parse(
            listOf(
                "Basic realm=\"legacy\", Bearer resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource\", " +
                    "scope=\"files:read files:write\", error_description=\"login, required\"",
            ),
        )

        assertNotNull(challenge)
        assertEquals("https://mcp.example/.well-known/oauth-protected-resource", challenge.resourceMetadata.toString())
        assertEquals(setOf("files:read", "files:write"), challenge.scopes)
        assertEquals("login, required", challenge.errorDescription)
    }

    @Test
    fun `returns null when no bearer challenge exists`() {
        assertNull(McpOAuthChallengeParser.parse(listOf("Basic realm=\"test\"")))
    }

    @Test
    fun `prefers bearer challenge that advertises protected resource metadata`() {
        val challenge = McpOAuthChallengeParser.parse(
            listOf(
                "Bearer realm=\"legacy\"",
                "Bearer resource_metadata=\"https://mcp.example/metadata\", scope=\"mcp:use\"",
            ),
        )
        assertEquals("https://mcp.example/metadata", challenge?.resourceMetadata.toString())
        assertEquals(setOf("mcp:use"), challenge?.scopes)
    }

    @Test
    fun `PKCE and state are high entropy and verifiable`() {
        val first = McpOAuthPkce.generate()
        val second = McpOAuthPkce.generate()
        assertTrue(first.verifier.length in 43..128)
        assertEquals(43, first.challenge.length)
        assertEquals("S256", first.method)
        assertNotEquals(first.verifier, second.verifier)

        val state = McpOAuthState.generate()
        assertTrue(McpOAuthState.matches(state, state))
        assertFalse(McpOAuthState.matches(state, "$state-x"))
        assertFalse(McpOAuthState.matches(state, null))
    }
}
