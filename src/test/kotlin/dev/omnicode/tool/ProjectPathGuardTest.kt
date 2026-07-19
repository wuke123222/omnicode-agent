package dev.omnicode.tool

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectPathGuardTest {
    @Test
    fun `allows ordinary project files and blocks sensitive names`() {
        withTempDirectory { root ->
            val project = projectAt(root)
            assertEquals(root.resolve("src/App.kt"), ProjectPathGuard.resolve(project, "src/App.kt"))
            assertFailsWith<IllegalArgumentException> { ProjectPathGuard.resolve(project, ".env") }
            assertFailsWith<IllegalArgumentException> { ProjectPathGuard.resolve(project, ".env.development") }
            assertEquals(root.resolve(".env.example"), ProjectPathGuard.resolve(project, ".env.example"))
            assertFailsWith<IllegalArgumentException> { ProjectPathGuard.resolve(project, "keys/private.pem") }
            assertFailsWith<IllegalArgumentException> { ProjectPathGuard.resolve(project, "../outside.txt") }
        }
    }

    @Test
    fun `blocks symbolic link escapes`() {
        withTempDirectory { root ->
            val outside = createTempDirectory("omnicode-outside")
            try {
                Files.writeString(outside.resolve("secret.txt"), "secret")
                Files.createSymbolicLink(root.resolve("escape"), outside)
                assertFailsWith<IllegalArgumentException> {
                    ProjectPathGuard.resolve(projectAt(root), "escape/secret.txt")
                }
            } finally {
                deleteRecursively(outside)
            }
        }
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

    private fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("omnicode-project").toRealPath()
        try {
            block(root)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
