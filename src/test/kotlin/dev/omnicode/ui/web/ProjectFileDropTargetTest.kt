package dev.omnicode.ui.web

import com.intellij.ide.dnd.DnDEvent
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectFileDropTargetTest {
    @Test
    fun `native file list accepts only bounded safe project files`() {
        val root = createTempDirectory("omnicode-drop").toRealPath()
        val outside = Files.createTempFile("omnicode-outside", ".txt")
        try {
            val source = Files.createDirectories(root.resolve("src")).resolve("App.kt")
            Files.writeString(source, "class App")
            Files.writeString(root.resolve(".env"), "secret")
            Files.createSymbolicLink(root.resolve("alias.kt"), source)
            val transferred = fileListTransferable(listOf(source, source, root.resolve(".env"), root.resolve("alias.kt"), outside))
            assertEquals(listOf("src/App.kt"), projectFileDropPaths(projectAt(root), transferred))
        } finally {
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `IDE drop target adds a project file without reading or submitting it`() {
        val root = createTempDirectory("omnicode-ide-drop").toRealPath()
        try {
            val file = root.resolve("README.md")
            Files.writeString(file, "private test content")
            val accepted = mutableListOf<List<String>>()
            var dropPossible = false
            val transferable = fileListTransferable(listOf(file))
            val event = Proxy.newProxyInstance(DnDEvent::class.java.classLoader, arrayOf(DnDEvent::class.java)) { _, method, args ->
                when (method.name) {
                    "getTransferDataFlavors" -> transferable.transferDataFlavors
                    "isDataFlavorSupported" -> transferable.isDataFlavorSupported(args!![0] as DataFlavor)
                    "getTransferData" -> transferable.getTransferData(args!![0] as DataFlavor)
                    "setDropPossible" -> { dropPossible = args!![0] as Boolean; null }
                    else -> null
                }
            } as DnDEvent
            val target = ProjectFileDropTarget(projectAt(root)) { accepted += it }
            assertTrue(target.update(event))
            assertTrue(dropPossible)
            target.drop(event)
            assertEquals(listOf(listOf("README.md")), accepted)
        } finally {
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun fileListTransferable(paths: List<Path>): Transferable = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any = paths.map(Path::toFile)
    }

    private fun projectAt(root: Path): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader, arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> root.toString()
            "isDisposed" -> false
            "toString" -> "TestProject"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    } as Project
}
