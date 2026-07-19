package dev.omnicode.ui

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectFileMentionsTest {
    @Test
    fun `detects only active at-prefixed token at caret`() {
        assertEquals("src/main", activeFileMention("inspect @src/main", 17)?.query)
        assertEquals("", activeFileMention("@", 1)?.query)
        assertNull(activeFileMention("mail@example.com", 16))
        assertNull(activeFileMention("inspect @src main", 17))
    }

    @Test
    fun `finds supported files with stable relevance and prunes generated directories`() {
        val root = createTempDirectory("omnicode-mentions")
        try {
            root.resolve("src").createDirectories().resolve("Research.kt").writeText("class Research")
            root.resolve("notes").createDirectories().resolve("research.md").writeText("notes")
            root.resolve("node_modules").createDirectories().resolve("research.js").writeText("ignored")

            val results = findProjectFileMentions(root, "research")

            assertTrue(results.any { it.relativePath == "notes/research.md" })
            assertTrue(results.any { it.relativePath == "src/Research.kt" })
            assertTrue(results.none { it.relativePath.contains("node_modules") })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `project scan can stop promptly when a superseding query cancels it`() {
        val root = createTempDirectory("omnicode-mentions-cancel")
        try {
            repeat(20) { index -> root.resolve("file-$index.kt").writeText("class File$index") }
            var checks = 0

            val results = findProjectFileMentions(root, "file", continueScanning = { ++checks <= 3 })

            assertTrue(checks <= 4)
            assertTrue(results.size < 12)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
