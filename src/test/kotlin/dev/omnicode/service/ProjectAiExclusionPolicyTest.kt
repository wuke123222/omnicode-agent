package dev.omnicode.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectAiExclusionPolicyTest {
    @Test
    fun `combines ordered ignore files explicit exclusions and non-overridable sensitive paths`() =
        withExclusionRoot { root ->
            Files.writeString(root.resolve(".gitignore"), "build/\n*.generated\n.env\n")
            Files.writeString(root.resolve(".aiignore"), "!keep.generated\n")
            Files.writeString(root.resolve(".omnicodeignore"), "private-notes/\n!.env\n")

            val policy = ProjectAiExclusionPolicy.load(root, explicitExclusions = listOf("manual"))

            assertFalse(policy.failClosed)
            assertTrue(policy.isExcluded("build/output.txt"))
            assertTrue(policy.isExcluded("manual/visible.kt"))
            assertTrue(policy.isExcluded("private-notes/paper.md"))
            assertFalse(policy.isExcluded("keep.generated"))
            assertTrue(policy.isExcluded(".env"), "sensitive paths cannot be re-included by ignore negation")
            assertTrue(policy.isExcluded("config/id_ed25519"))
            assertTrue(policy.isExcluded(".ssh/config"))
            assertTrue(policy.isExcluded("certs/client.pem"))
            assertTrue(policy.isExcluded("deploy/service-account-prod.json"))
            assertTrue(policy.isExcluded("deploy/credentials.yml"))
            assertTrue(policy.isExcluded("oauth/client_secret_123.json"))
            assertFalse(policy.isExcluded("src/Credentials.kt"))
        }

    @Test
    fun `unsafe or incomplete ignore input disables automatic project reads`() = withExclusionRoot { root ->
        Files.write(root.resolve(".aiignore"), byteArrayOf(0, 1, 2))

        val unsafe = ProjectAiExclusionPolicy.load(root)

        assertTrue(unsafe.failClosed)
        assertTrue(unsafe.isExcluded("src/App.kt"))
        assertTrue(unsafe.issues.any { it.relativePath == ".aiignore" })

        Files.delete(root.resolve(".aiignore"))
        Files.writeString(root.resolve(".gitignore"), "ignored/\n" + "x".repeat(100))
        val truncated = ProjectAiExclusionPolicy.load(root, maxIgnoreFileBytes = 8)
        assertTrue(truncated.failClosed)
        assertTrue(truncated.isExcluded("src/App.kt"))
    }

    @Test
    fun `pattern limit fails closed instead of silently dropping later exclusions`() = withExclusionRoot { root ->
        Files.writeString(root.resolve(".gitignore"), "first\nsecond\n")

        val policy = ProjectAiExclusionPolicy.load(root, maxIgnorePatterns = 1)

        assertTrue(policy.failClosed)
        assertTrue(policy.issues.any { it.reason == ProjectAiExclusionPolicyIssueReason.PATTERN_LIMIT })
        assertTrue(policy.isExcluded("otherwise-visible.txt"))
    }
}

private fun withExclusionRoot(block: (Path) -> Unit) {
    val root = createTempDirectory("omnicode-ai-exclusions").toRealPath()
    try {
        block(root)
    } finally {
        if (Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
