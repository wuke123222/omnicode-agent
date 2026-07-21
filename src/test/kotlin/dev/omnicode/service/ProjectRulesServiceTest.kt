package dev.omnicode.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectRulesServiceTest {
    @Test
    fun `discovers deterministic project rules and applies ordered ignore files`() = withProjectRoot { root ->
        Files.writeString(root.resolve("AGENTS.md"), "agents rule")
        Files.writeString(root.resolve("CLAUDE.md"), "claude rule")
        root.resolve(".omnicode/rules").createDirectories()
        Files.writeString(root.resolve(".omnicode/rules/drop.md"), "drop rule")
        Files.writeString(root.resolve(".omnicode/rules/keep.md"), "keep rule")
        Files.writeString(
            root.resolve(".gitignore"),
            "CLAUDE.md\n.omnicode/rules/*.md\n",
        )
        Files.writeString(
            root.resolve(".omnicodeignore"),
            "!.omnicode/rules/keep.md\n",
        )

        val result = ProjectRulesLoader(root).load()

        assertEquals(ProjectRuleTrust.PROJECT_DATA, result.trust)
        assertEquals(listOf("AGENTS.md", ".omnicode/rules/keep.md"), result.appliedRulePaths)
        assertTrue(result.combinedText.contains("untrusted project data"))
        assertTrue(result.combinedText.contains("agents rule"))
        assertTrue(result.combinedText.contains("keep rule"))
        assertFalse(result.combinedText.contains("claude rule"))
        assertFalse(result.combinedText.contains("drop rule"))
        assertEquals(4, result.truncation.discoveredFiles)
        assertEquals(2, result.truncation.appliedFiles)
        assertEquals(2, result.truncation.ignoredFiles)
    }

    @Test
    fun `rejects symbolic links malformed utf8 and binary rule data`() = withProjectRoot { root ->
        val outside = createTempDirectory("omnicode-rules-outside")
        try {
            Files.writeString(outside.resolve("outside.md"), "outside secret")
            Files.createSymbolicLink(root.resolve("AGENTS.md"), outside.resolve("outside.md"))
            root.resolve(".omnicode/rules").createDirectories()
            Files.write(root.resolve(".omnicode/rules/binary.md"), byteArrayOf(0, 1, 2, 3))
            Files.write(root.resolve(".omnicode/rules/malformed.md"), byteArrayOf(0xC3.toByte(), 0x28))

            val result = ProjectRulesLoader(root).load()

            assertTrue(result.appliedRules.isEmpty())
            assertTrue(result.combinedText.isEmpty())
            assertEquals(3, result.truncation.rejectedFiles)
            assertTrue(result.issues.any {
                it.relativePath == "AGENTS.md" && it.reason == ProjectRuleIssueReason.UNSAFE_PATH
            })
            assertEquals(
                2,
                result.issues.count { it.reason == ProjectRuleIssueReason.INVALID_UTF8_OR_BINARY },
            )
        } finally {
            deleteRecursively(outside)
        }
    }

    @Test
    fun `truncates only at a valid utf8 boundary and reports omitted bytes`() = withProjectRoot { root ->
        Files.writeString(root.resolve("AGENTS.md"), "你好世界")

        val result = ProjectRulesLoader(
            root,
            ProjectRuleLoadingLimits(
                maxRuleFileBytes = 7,
                maxIgnoreFileBytes = 1_024,
                maxCombinedCharacters = 1_024,
                maxRuleFiles = 4,
                maxDiscoveryEntries = 4,
                maxIgnorePatterns = 10,
            ),
        ).load()

        val rule = result.appliedRules.single()
        assertEquals("你好", rule.content)
        assertEquals(6, rule.includedBytes)
        assertTrue(rule.truncated)
        assertEquals(1, result.truncation.truncatedFiles)
        assertEquals(6, result.truncation.omittedBytes)
        assertTrue(result.combinedText.length <= 1_024)
    }

    @Test
    fun `does not hide malformed utf8 merely because the file is truncated`() = withProjectRoot { root ->
        Files.write(root.resolve("AGENTS.md"), "abcdef".toByteArray() + byteArrayOf(0xFF.toByte(), 'z'.code.toByte()))

        val result = ProjectRulesLoader(
            root,
            ProjectRuleLoadingLimits(
                maxRuleFileBytes = 7,
                maxIgnoreFileBytes = 1_024,
                maxCombinedCharacters = 1_024,
                maxRuleFiles = 4,
                maxDiscoveryEntries = 4,
                maxIgnorePatterns = 10,
            ),
        ).load()

        assertTrue(result.appliedRules.isEmpty())
        assertTrue(result.issues.any { it.reason == ProjectRuleIssueReason.INVALID_UTF8_OR_BINARY })
    }

    @Test
    fun `combined budget counts clipped and fully omitted rules as truncated`() = withProjectRoot { root ->
        Files.writeString(root.resolve("AGENTS.md"), "a".repeat(2_000))
        Files.writeString(root.resolve("CLAUDE.md"), "claude")

        val result = ProjectRulesLoader(
            root,
            ProjectRuleLoadingLimits(
                maxRuleFileBytes = 4_096,
                maxIgnoreFileBytes = 1_024,
                maxCombinedCharacters = 1_024,
                maxRuleFiles = 4,
                maxDiscoveryEntries = 4,
                maxIgnorePatterns = 10,
            ),
        ).load()

        assertEquals(1, result.appliedRules.size)
        assertEquals(2, result.truncation.truncatedFiles)
        assertTrue(result.truncation.omittedKnownCharacters > 0)
        assertTrue(result.combinedText.length <= 1_024)
    }

    @Test
    fun `common gitignore globs support roots directories double stars classes and negation`() {
        val matcher = ProjectIgnoreMatcher(
            ProjectIgnoreMatcher.parse(
                """
                build/
                *.tmp
                !keep.tmp
                /root.md
                docs/**/draft[0-9].md
                \!literal.md
                \#literal.md
                """.trimIndent(),
            ),
        )

        assertTrue(matcher.isIgnored("build/out.txt"))
        assertTrue(matcher.isIgnored("src/build/out.txt"))
        assertTrue(matcher.isIgnored("nested/file.tmp"))
        assertFalse(matcher.isIgnored("nested/keep.tmp"))
        assertTrue(matcher.isIgnored("root.md"))
        assertFalse(matcher.isIgnored("nested/root.md"))
        assertTrue(matcher.isIgnored("docs/api/v2/draft7.md"))
        assertFalse(matcher.isIgnored("docs/api/v2/final7.md"))
        assertTrue(matcher.isIgnored("!literal.md"))
        assertTrue(matcher.isIgnored("#literal.md"))
    }

    @Test
    fun `explicit AI exclusions apply to project rule discovery`() = withProjectRoot { root ->
        Files.writeString(root.resolve("AGENTS.md"), "must not enter context")
        Files.writeString(root.resolve("CLAUDE.md"), "safe rule")

        val result = ProjectRulesLoader(root, explicitExclusions = listOf("AGENTS.md")).load()

        assertEquals(listOf("CLAUDE.md"), result.appliedRulePaths)
        assertFalse(result.combinedText.contains("must not enter context"))
        assertTrue(result.issues.any {
            it.relativePath == "AGENTS.md" && it.reason == ProjectRuleIssueReason.IGNORED
        })
    }

    @Test
    fun `unsafe ignore policy disables rule discovery before any rule content is read`() = withProjectRoot { root ->
        Files.write(root.resolve(".gitignore"), byteArrayOf(0, 1, 2))
        Files.writeString(root.resolve("AGENTS.md"), "must never be read into context")

        val result = ProjectRulesLoader(root).load()

        assertTrue(result.appliedRules.isEmpty())
        assertTrue(result.combinedText.isEmpty())
        assertEquals(0, result.truncation.discoveredFiles)
        assertTrue(result.issues.any { it.relativePath == ".gitignore" })
    }
}

private fun withProjectRoot(block: (Path) -> Unit) {
    val root = createTempDirectory("omnicode-project-rules").toRealPath()
    try {
        block(root)
    } finally {
        deleteRecursively(root)
    }
}

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
