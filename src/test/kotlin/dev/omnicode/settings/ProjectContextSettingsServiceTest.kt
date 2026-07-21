package dev.omnicode.settings

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectContextSettingsServiceTest {
    @Test
    fun `canonicalizes paths and keeps exclusions authoritative over pins`() = withProjectRoot { root ->
        root.resolve("src").createDirectories()
        Files.writeString(root.resolve("src/App.kt"), "class App")
        val service = ProjectContextSettingsService(projectAt(root))

        assertEquals(listOf("src/App.kt"), service.pin("./src\\App.kt").pinnedPaths)
        val excluded = service.exclude("src")
        assertEquals(listOf("src"), excluded.excludedPaths)
        assertTrue(excluded.pinnedPaths.isEmpty())
        assertTrue(service.isExcluded("src/App.kt"))
        assertFailsWith<IllegalArgumentException> { service.pin("src/App.kt") }

        service.include("src")
        assertFalse(service.isExcluded("src/App.kt"))
        assertEquals(listOf("src/App.kt"), service.pin("src/App.kt").pinnedPaths)
        assertTrue(service.unpin("src/App.kt").pinnedPaths.isEmpty())
    }

    @Test
    fun `rejects traversal absolute paths symlinks and oversized collections`() = withProjectRoot { root ->
        val outside = createTempDirectory("omnicode-context-outside")
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside)
            val service = ProjectContextSettingsService(projectAt(root))

            assertFailsWith<IllegalArgumentException> { service.pin("../outside.txt") }
            assertFailsWith<IllegalArgumentException> { service.pin("/tmp/outside.txt") }
            assertFailsWith<IllegalArgumentException> { service.pin("C:\\outside.txt") }
            assertFailsWith<IllegalArgumentException> { service.pin("C:drive-relative.txt") }
            assertFailsWith<IllegalArgumentException> { service.pin("linked/file.txt") }
            assertFailsWith<IllegalArgumentException> {
                service.setPinnedPaths((0..MAX_PINNED_PROJECT_PATHS).map { "files/$it.kt" })
            }
            assertFailsWith<IllegalArgumentException> {
                service.setExcludedPaths((0..MAX_EXCLUDED_PROJECT_PATHS).map { "generated/$it" })
            }
        } finally {
            deleteSettingsTestTree(outside)
        }
    }

    @Test
    fun `load state sanitizes invalid duplicates conflicts and redundant exclusions`() = withProjectRoot { root ->
        root.resolve("src").createDirectories()
        val state = ProjectContextPersistentState().also {
            it.pinnedPaths = mutableListOf("src/App.kt", "./src/App.kt", "../outside", "generated/file.kt")
            it.excludedPaths = mutableListOf("generated/cache", "generated", "../../escape")
        }
        val service = ProjectContextSettingsService(projectAt(root))

        service.loadState(state)

        assertEquals(listOf("src/App.kt"), service.snapshot().pinnedPaths)
        assertEquals(listOf("generated"), service.snapshot().excludedPaths)
        val persisted = service.state
        persisted.pinnedPaths += "mutated/outside/service.kt"
        assertEquals(listOf("src/App.kt"), service.snapshot().pinnedPaths)
    }

    @Test
    fun `ignore files and sensitive paths cannot be pinned or explicitly re-included`() =
        withProjectRoot { root ->
            Files.writeString(root.resolve(".gitignore"), "ignored.md\n")
            Files.writeString(root.resolve("ignored.md"), "ignored")
            Files.writeString(root.resolve(".env"), "TOKEN=secret")
            val service = ProjectContextSettingsService(projectAt(root))

            assertFailsWith<IllegalArgumentException> { service.pin("ignored.md") }
            assertFailsWith<IllegalArgumentException> { service.pin(".env") }
            assertTrue(service.isExcluded("ignored.md"))
            assertTrue(service.isExcluded(".env"))
            service.include("ignored.md")
            assertTrue(service.isExcluded("ignored.md"))
        }

    private fun projectAt(root: Path): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> root.toString()
            "isDisposed" -> false
            "getName" -> "test"
            "toString" -> "TestProject(${root.fileName})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as Project
}

private fun withProjectRoot(block: (Path) -> Unit) {
    val root = createTempDirectory("omnicode-context-settings").toRealPath()
    try {
        block(root)
    } finally {
        deleteSettingsTestTree(root)
    }
}

private fun deleteSettingsTestTree(root: Path) {
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
