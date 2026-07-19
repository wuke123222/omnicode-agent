package dev.omnicode.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpEnvironmentCredentialStoreTest {
    @Test
    fun `accepts portable environment variable names`() {
        assertTrue(isValidMcpEnvironmentKey("GITHUB_TOKEN"))
        assertTrue(isValidMcpEnvironmentKey("token2"))
        assertFalse(isValidMcpEnvironmentKey("2TOKEN"))
        assertFalse(isValidMcpEnvironmentKey("TOKEN=value"))
        assertFalse(isValidMcpEnvironmentKey("TOKEN-NAME"))
    }

    @Test
    fun `HTTP credential coordinates reject blank or unreasonably long server ids`() {
        validateMcpHttpServerId("server-id")
        assertFailsWith<IllegalArgumentException> { validateMcpHttpServerId(" ") }
        assertFailsWith<IllegalArgumentException> { validateMcpHttpServerId("x".repeat(201)) }
    }
}
