package dev.omnicode.tool

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory

class ListFilesTool : AgentTool {
    override val name = "list_files"
    override val description =
        "List a bounded set of model-visible files under a project-relative path. Prefer a narrow path; use search_text for symbols. Project AI ignores, sensitive files, build, and VCS paths are skipped."
    override val dangerous = false
    override val effect = ToolEffect.READ_ONLY
    override val inputSchema: JsonObject = objectSchema {
        stringProperty("path", "Project-relative directory. Use '.' for the project root.")
        integerProperty("max_depth", "Maximum recursion depth.", 3, 1, 8)
        integerProperty("limit", "Maximum returned entries. Keep this small and narrow the path when possible.", DEFAULT_LIST_LIMIT, 20, 300)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult = withContext(Dispatchers.IO) {
        val relative = arguments.string("path", ".")
        val maxDepth = arguments.int("max_depth", 3).coerceIn(1, 8)
        val limit = arguments.int("limit", DEFAULT_LIST_LIMIT).coerceIn(MIN_LIST_LIMIT, MAX_LIST_LIMIT)
        val access = ProjectContextToolAccess.load(context.project)
        access.rejectionForRequestedPath(relative)?.let { return@withContext it }
        val root = ProjectPathGuard.root(context.project)
        val start = ProjectPathGuard.resolve(context.project, relative)
        require(Files.exists(start)) {
            "Path does not exist: $relative. Verify the parent directory, then retry with a narrower path or use search_text."
        }
        require(start.isDirectory()) { "Path is not a directory: $relative" }

        val coroutine = currentCoroutineContext()
        val lines = mutableListOf<String>()
        Files.walkFileTree(start, setOf(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                if (dir != start && dir.fileName?.toString() in IGNORED_NAMES) return FileVisitResult.SKIP_SUBTREE
                if (dir != start && runCatching { ProjectPathGuard.validate(context.project, dir) }.isFailure) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (dir != start && access.isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE
                if (dir != start && lines.size < limit) lines += root.relativize(dir).toString() + "/"
                return if (lines.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                if (!attrs.isSymbolicLink &&
                    !access.isExcluded(file) &&
                    runCatching { ProjectPathGuard.validate(context.project, file) }.isSuccess
                ) {
                    lines += root.relativize(file).toString()
                }
                return if (lines.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        lines.sort()
        ToolExecutionResult(
            if (lines.isEmpty()) "(empty directory)" else lines.joinToString("\n") +
                if (lines.size >= limit) {
                    "\n[truncated at $limit entries; narrow path or use search_text]"
                } else {
                    ""
                },
        )
    }

    private companion object {
        const val DEFAULT_LIST_LIMIT = 160
        const val MIN_LIST_LIMIT = 20
        const val MAX_LIST_LIMIT = 300
    }
}

internal val IGNORED_NAMES = setOf(".git", ".idea", ".gradle", "build", "out", "node_modules", "dist", "target", ".venv")

internal fun JsonObject.string(name: String, default: String = ""): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString ?: default

internal fun JsonObject.int(name: String, default: Int): Int =
    get(name)?.takeUnless { it.isJsonNull }?.asInt ?: default

internal fun JsonObject.bool(name: String, default: Boolean): Boolean =
    get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
