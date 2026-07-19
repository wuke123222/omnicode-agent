package dev.omnicode.persistence

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveDataRedactorTest {
    @Test
    fun `redacts recognized provider and source control token prefixes`() {
        val tokens = listOf(
            "sk-ant-api03-0123456789abcdefghij",
            "github_pat_0123456789abcdefghijklmnop",
            "glpat-0123456789abcdef",
            "AIza0123456789abcdefghij",
        )
        val source = tokens.joinToString(separator = "\n") { "tool output: $it" }

        val redacted = DefaultSensitiveDataRedactor().redact(source)

        tokens.forEach { token -> assertFalse(redacted.contains(token), "token must be redacted: $token") }
        assertTrue(redacted.count { it == '[' } == tokens.size)
    }

    @Test
    fun `does not redact ordinary identifiers without a secret prefix`() {
        val value = "build id github_pattern glpatience Aizaic test-key"

        assertTrue(DefaultSensitiveDataRedactor().redact(value).contains(value))
    }
}
