package dev.omnicode.tool

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.settings.ProjectContextSettingsService
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectContextToolExclusionTest {
    @Test
    fun `file listing is bounded and tells the model to narrow its query`() = runBlocking {
        withToolProject { root, project, _ ->
            repeat(25) { index -> Files.writeString(root.resolve("file-$index.txt"), "ok") }

            val listed = ListFilesTool().execute(
                json("path" to ".", "max_depth" to 1, "limit" to 20),
                toolContext(project),
            )

            assertFalse(listed.isError)
            assertTrue(listed.content.contains("[truncated at 20 entries; narrow path or use search_text]"))
            assertTrue(listed.content.lineSequence().count { it.endsWith(".txt") } <= 20)
        }
    }

    @Test
    fun `list read and search never expose ignored sensitive or explicitly excluded files`() = runBlocking {
        withToolProject { root, project, settings ->
            root.resolve("private").createDirectories()
            root.resolve("manual").createDirectories()
            root.resolve(".ssh").createDirectories()
            Files.writeString(root.resolve(".aiignore"), "ignored.txt\nprivate/\n")
            Files.writeString(root.resolve("safe.txt"), "SAFE_VISIBLE_MARKER")
            Files.writeString(root.resolve("ignored.txt"), "ULTRA_SECRET_MARKER ignored")
            Files.writeString(root.resolve("private/notes.md"), "ULTRA_SECRET_MARKER private")
            Files.writeString(root.resolve("manual/excluded.md"), "ULTRA_SECRET_MARKER explicit")
            Files.writeString(root.resolve(".env"), "ULTRA_SECRET_MARKER env")
            Files.writeString(root.resolve(".ssh/config"), "ULTRA_SECRET_MARKER ssh")
            settings.exclude("manual")
            val context = toolContext(project)

            val listed = ListFilesTool().execute(json("path" to ".", "max_depth" to 4), context)
            assertFalse(listed.isError)
            assertTrue(listed.content.contains("safe.txt"))
            assertFalse(listed.content.contains("ignored.txt"))
            assertFalse(listed.content.contains("private/"))
            assertFalse(listed.content.contains("manual/"))
            assertFalse(listed.content.contains(".env"))
            assertFalse(listed.content.contains(".ssh"))

            listOf("ignored.txt", "private/notes.md", "manual/excluded.md", ".env", ".ssh/config").forEach { path ->
                val read = ReadFileTool().execute(json("path" to path), context)
                assertTrue(read.isError, path)
                assertTrue(read.content.startsWith(PROJECT_CONTEXT_EXCLUDED_CODE), path)
                assertFalse(read.content.contains("ULTRA_SECRET_MARKER"), path)
            }

            listOf(ApplyChangeTool(), ApplyPatchTool()).forEach { tool ->
                val mutation = tool.execute(json("path" to "ignored.txt"), context)
                assertTrue(mutation.isError, tool.name)
                assertTrue(mutation.content.startsWith(PROJECT_CONTEXT_EXCLUDED_CODE), tool.name)
                assertTrue(Files.readString(root.resolve("ignored.txt")).contains("ULTRA_SECRET_MARKER"))
            }

            val searched = SearchTextTool().execute(json("query" to "ULTRA_SECRET_MARKER", "path" to "."), context)
            assertFalse(searched.isError)
            assertFalse(searched.content.contains("ULTRA_SECRET_MARKER"))
            assertFalse(searched.content.contains("ignored.txt"))
            assertFalse(searched.content.contains("private/"))
            assertFalse(searched.content.contains("manual/"))
            assertFalse(searched.content.contains(".env"))

            val blockedSearch = SearchTextTool().execute(
                json("query" to "anything", "path" to "private"),
                context,
            )
            assertTrue(blockedSearch.isError)
            assertTrue(blockedSearch.content.startsWith(PROJECT_CONTEXT_EXCLUDED_CODE))
        }
    }

    @Test
    fun `malformed ignore policy fails all general project reads closed`() = runBlocking {
        withToolProject { root, project, _ ->
            Files.write(root.resolve(".omnicodeignore"), byteArrayOf(0, 1, 2))
            Files.writeString(root.resolve("visible.txt"), "MUST_NOT_LEAK")
            val context = toolContext(project)

            val results = listOf(
                ListFilesTool().execute(json("path" to "."), context),
                ReadFileTool().execute(json("path" to "visible.txt"), context),
                SearchTextTool().execute(json("query" to "MUST_NOT_LEAK", "path" to "."), context),
            )

            results.forEach { result ->
                assertTrue(result.isError)
                assertTrue(result.content.startsWith(PROJECT_CONTEXT_EXCLUDED_CODE))
                assertFalse(result.content.contains("MUST_NOT_LEAK"))
            }
        }
    }

    private fun toolContext(project: Project) = ToolExecutionContext(
        project = project,
        approvalGate = ApprovalGate { true },
        mode = AgentMode.AGENT,
    )
}

private fun json(vararg values: Pair<String, Any>): JsonObject = JsonObject().apply {
    values.forEach { (name, value) ->
        when (value) {
            is String -> addProperty(name, value)
            is Number -> addProperty(name, value)
            is Boolean -> addProperty(name, value)
            else -> error("Unsupported test JSON value")
        }
    }
}

private suspend fun withToolProject(block: suspend (Path, Project, ProjectContextSettingsService) -> Unit) {
    val root = createTempDirectory("omnicode-tool-context").toRealPath()
    val services = mutableMapOf<Class<*>, Any>()
    val project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> root.toString()
            "getService" -> services[args?.firstOrNull()]
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
    val settings = ProjectContextSettingsService(project)
    services[ProjectContextSettingsService::class.java] = settings
    try {
        block(root, project, settings)
    } finally {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
