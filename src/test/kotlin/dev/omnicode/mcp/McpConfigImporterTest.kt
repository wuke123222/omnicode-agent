package dev.omnicode.mcp

import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class McpConfigImporterTest {
    @Test
    fun `imports stdio config as a disabled credential-name-only draft`() {
        val imported = McpConfigImporter.parse(
            """{"mcpServers":{"github":{"command":"npx","args":["-y","server"],"env":{"GITHUB_TOKEN":"secret-value"}}}}""",
        ).single()

        assertEquals(McpTransport.STDIO, imported.transport)
        assertFalse(imported.enabled)
        assertEquals("npx", imported.command)
        assertEquals(listOf("-y", "server"), imported.arguments)
        assertEquals(setOf("GITHUB_TOKEN"), imported.environmentKeys)
        assertFalse(imported.toString().contains("secret-value"))
    }

    @Test
    fun `imports https server but discards authorization headers`() {
        val imported = McpConfigImporter.parse(
            """{"servers":{"remote":{"type":"http","url":"https://example.com/mcp","headers":{"Authorization":"Bearer secret"}}}}""",
        ).single()

        assertEquals(McpTransport.HTTP, imported.transport)
        assertEquals(McpHttpAuthMode.BEARER, imported.httpAuthMode)
        assertEquals("https://example.com/mcp", imported.url)
        assertFalse(imported.toString().contains("secret"))
    }

    @Test
    fun `rejects insecure remote endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            McpConfigImporter.parse("""{"servers":{"remote":{"url":"http://example.com/mcp"}}}""")
        }
    }
}
