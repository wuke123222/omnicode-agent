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
import java.io.IOException
import kotlin.io.path.extension

class SearchTextTool : AgentTool {
    override val name = "search_text"
    override val description =
        "Search model-visible project text for a literal string or regex. Respects project AI ignores and sensitive-file rules."
    override val dangerous = false
    override val effect = ToolEffect.READ_ONLY
    override val inputSchema: JsonObject = objectSchema(required = listOf("query")) {
        stringProperty("query", "Literal text or Kotlin/Java regular expression to search for.")
        stringProperty("path", "Project-relative directory to search. Defaults to '.'.")
        booleanProperty("regex", "Interpret query as a regular expression.", false)
        integerProperty("max_results", "Maximum number of matching lines.", 100, 1, 300)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = arguments.string("query")
        require(query.isNotEmpty()) { "query must not be empty" }
        require(query.length <= 500) { "query is longer than 500 characters" }
        val requestedPath = arguments.string("path", ".")
        val access = ProjectContextToolAccess.load(context.project)
        access.rejectionForRequestedPath(requestedPath)?.let { return@withContext it }
        val start = ProjectPathGuard.resolve(context.project, requestedPath)
        require(Files.exists(start)) { "Search path does not exist" }
        val maxResults = arguments.int("max_results", 100).coerceIn(1, 300)
        val regex = if (arguments.bool("regex", false)) Regex(query) else null
        val root = ProjectPathGuard.root(context.project)
        val results = mutableListOf<String>()
        val coroutine = currentCoroutineContext()
        var visited = 0
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                if (dir != start && dir.fileName?.toString() in IGNORED_NAMES) return FileVisitResult.SKIP_SUBTREE
                if (dir != start && access.isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE
                return if (runCatching { ProjectPathGuard.validate(context.project, dir) }.isSuccess) {
                    FileVisitResult.CONTINUE
                } else {
                    FileVisitResult.SKIP_SUBTREE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                if (results.size >= maxResults || visited >= 10_000) return FileVisitResult.TERMINATE
                if (attrs.isSymbolicLink || !isSearchable(file)) return FileVisitResult.CONTINUE
                if (access.isExcluded(file)) return FileVisitResult.CONTINUE
                if (runCatching { ProjectPathGuard.validate(context.project, file) }.isFailure) return FileVisitResult.CONTINUE
                visited++
                try {
                    Files.newBufferedReader(file).useLines { lines ->
                        for ((index, fullLine) in lines.withIndex()) {
                            coroutine.ensureActive()
                            val line = fullLine.take(MAX_SEARCH_LINE_CHARS)
                            val matched = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                            if (matched) {
                                results += "${root.relativize(file)}:${index + 1}: ${line.trim().take(300)}"
                                if (results.size >= maxResults) break
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Files may disappear or become unreadable while the project changes.
                }
                return if (results.size >= maxResults) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        ToolExecutionResult(
            if (results.isEmpty()) "No matches." else results.joinToString("\n") +
                if (results.size >= maxResults) "\n[truncated at $maxResults matches]" else "",
        )
    }

    private fun isSearchable(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        if (path.any { it.toString() in IGNORED_NAMES }) return false
        if (runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE) > 2_000_000) return false
        return path.extension.lowercase() !in BINARY_EXTENSIONS
    }
}

private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "jar", "class", "exe", "dll", "so", "dylib",
    "woff", "woff2", "ttf", "mp3", "mp4", "mov", "avi", "bin",
)

private const val MAX_SEARCH_LINE_CHARS = 20_000
