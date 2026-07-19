package dev.omnicode.ui

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class ActiveFileMention(
    val start: Int,
    val end: Int,
    val query: String,
)

internal data class ProjectFileMention(
    val path: Path,
    val relativePath: String,
)

internal fun activeFileMention(text: String, caret: Int): ActiveFileMention? {
    val end = caret.coerceIn(0, text.length)
    var start = end
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    if (start >= end || text[start] != '@') return null
    val query = text.substring(start + 1, end)
    if (query.length > MAX_MENTION_QUERY_CHARS || query.any { it == '\u0000' || it == '@' }) return null
    return ActiveFileMention(start, end, query)
}

internal fun findProjectFileMentions(
    root: Path,
    query: String,
    limit: Int = 12,
    scanLimit: Int = 8_000,
    continueScanning: () -> Boolean = { true },
): List<ProjectFileMention> {
    require(limit > 0 && scanLimit > 0)
    val normalizedRoot = root.toAbsolutePath().normalize()
    if (!Files.isDirectory(normalizedRoot)) return emptyList()
    val normalizedQuery = query.trim().lowercase().replace('\\', '/')
    val candidates = mutableListOf<ProjectFileMention>()
    var visited = 0
    Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!continueScanning()) return FileVisitResult.TERMINATE
            if (dir != normalizedRoot && dir.fileName?.toString()?.lowercase() in IGNORED_DIRECTORIES) {
                return FileVisitResult.SKIP_SUBTREE
            }
            return if (visited >= scanLimit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!continueScanning()) return FileVisitResult.TERMINATE
            if (++visited > scanLimit) return FileVisitResult.TERMINATE
            if (!attrs.isRegularFile || !AttachmentIntake.supports(file)) return FileVisitResult.CONTINUE
            val relative = normalizedRoot.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')
            if (normalizedQuery.isEmpty() || relative.lowercase().contains(normalizedQuery)) {
                candidates += ProjectFileMention(file.toAbsolutePath().normalize(), relative)
            }
            return FileVisitResult.CONTINUE
        }
    })
    return candidates.sortedWith(
        compareBy<ProjectFileMention> { mentionScore(it.relativePath, normalizedQuery) }
            .thenBy { it.relativePath.length }
            .thenBy { it.relativePath.lowercase() },
    ).take(limit)
}

private fun mentionScore(path: String, query: String): Int {
    if (query.isEmpty()) return 3
    val normalized = path.lowercase()
    val name = normalized.substringAfterLast('/')
    return when {
        name == query -> 0
        name.startsWith(query) -> 1
        normalized.startsWith(query) -> 2
        else -> 3
    }
}

private const val MAX_MENTION_QUERY_CHARS = 120
private val IGNORED_DIRECTORIES = setOf(
    ".git", ".gradle", ".idea", ".venv", "venv", "node_modules", "build", "dist", "out", "target",
)
