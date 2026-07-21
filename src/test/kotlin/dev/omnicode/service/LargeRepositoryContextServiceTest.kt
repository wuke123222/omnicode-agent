package dev.omnicode.service

import dev.omnicode.settings.ProjectContextSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeRepositoryContextServiceTest {
    @Test
    fun `pinned context is bounded reports occupancy and respects exclusions`() = withContextRoot { root ->
        root.resolve("src").createDirectories()
        root.resolve("generated").createDirectories()
        Files.writeString(root.resolve("src/App.kt"), "a".repeat(2_000))
        Files.writeString(root.resolve("generated/Generated.kt"), "must not be included")

        val context = PinnedContextCollector(root).collect(
            ProjectContextSettings(
                pinnedPaths = listOf("src/App.kt", "generated/Generated.kt", "missing.kt"),
                excludedPaths = listOf("generated"),
            ),
            maxCharacters = 1_024,
            maxCharactersPerFile = 512,
        )

        val file = context.files.single()
        assertEquals("src/App.kt", file.relativePath)
        assertEquals(512, file.content.length)
        assertTrue(file.truncated)
        assertEquals(1, context.truncatedFiles)
        assertTrue(context.combinedText.length <= 1_024)
        assertEquals(context.combinedText.length, context.occupancy.usedCharacters)
        assertEquals((context.combinedText.length.toLong() + 3) / 4, context.occupancy.estimatedTokens)
        assertTrue(context.occupancy.percentUsed in 1..100)
        assertTrue(context.issues.any { it.relativePath == "generated/Generated.kt" })
        assertTrue(context.issues.any { it.relativePath == "missing.kt" })
        assertTrue(!context.combinedText.contains("must not be included"))
    }

    @Test
    fun `pinned context rejects unsafe and binary files without losing safe files`() = withContextRoot { root ->
        val outside = createTempDirectory("omnicode-pinned-outside")
        try {
            Files.writeString(root.resolve("safe.txt"), "safe context")
            Files.write(root.resolve("binary.txt"), byteArrayOf(0, 1, 2))
            Files.createSymbolicLink(root.resolve("linked.txt"), outside.resolve("outside.txt"))

            val context = PinnedContextCollector(root).collect(
                ProjectContextSettings(
                    pinnedPaths = listOf("safe.txt", "binary.txt", "linked.txt", "../escape.txt"),
                ),
                maxCharacters = 1_024,
                maxCharactersPerFile = 512,
            )

            assertEquals(listOf("safe.txt"), context.files.map(PinnedProjectFileContext::relativePath))
            assertTrue(context.combinedText.contains("safe context"))
            assertEquals(3, context.issues.size)
        } finally {
            deleteContextTestTree(outside)
        }
    }

    @Test
    fun `multibyte pinned file truncation preserves valid text`() = withContextRoot { root ->
        Files.writeString(root.resolve("unicode.md"), "界".repeat(1_000))

        val context = PinnedContextCollector(root).collect(
            ProjectContextSettings(pinnedPaths = listOf("unicode.md")),
            maxCharacters = 1_024,
            maxCharactersPerFile = 257,
        )

        assertEquals("界".repeat(257), context.files.single().content)
        assertTrue(context.files.single().truncated)
        assertTrue(context.omittedBytes > 0)
    }

    @Test
    fun `pinned context applies ignore files and hard sensitive exclusions before reading`() =
        withContextRoot { root ->
            Files.writeString(root.resolve(".gitignore"), "ignored.md\n")
            Files.writeString(root.resolve("safe.md"), "safe context")
            Files.writeString(root.resolve("ignored.md"), "ignored secret marker")
            Files.writeString(root.resolve(".env"), "API_TOKEN=secret marker")
            Files.writeString(root.resolve("private.pem"), "private key marker")

            val context = PinnedContextCollector(root).collect(
                ProjectContextSettings(
                    pinnedPaths = listOf("safe.md", "ignored.md", ".env", "private.pem"),
                ),
                maxCharacters = 2_048,
                maxCharactersPerFile = 512,
            )

            assertEquals(listOf("safe.md"), context.files.map(PinnedProjectFileContext::relativePath))
            assertTrue(context.combinedText.contains("safe context"))
            assertTrue(!context.combinedText.contains("secret marker"))
            assertTrue(!context.combinedText.contains("private key marker"))
            assertEquals(3, context.issues.count { it.relativePath in setOf("ignored.md", ".env", "private.pem") })
        }

    @Test
    fun `pinned context fails closed when ignore policy cannot be read safely`() = withContextRoot { root ->
        Files.write(root.resolve(".omnicodeignore"), byteArrayOf(0, 1, 2))
        Files.writeString(root.resolve("safe.md"), "must not be exposed")

        val context = PinnedContextCollector(root).collect(
            ProjectContextSettings(pinnedPaths = listOf("safe.md")),
            maxCharacters = 1_024,
            maxCharactersPerFile = 512,
        )

        assertTrue(context.files.isEmpty())
        assertTrue(context.combinedText.isEmpty())
        assertTrue(context.issues.any { it.relativePath == ".omnicodeignore" })
        assertTrue(context.issues.any { it.relativePath == "safe.md" })
    }
}

private fun withContextRoot(block: (Path) -> Unit) {
    val root = createTempDirectory("omnicode-large-context").toRealPath()
    try {
        block(root)
    } finally {
        deleteContextTestTree(root)
    }
}

private fun deleteContextTestTree(root: Path) {
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
