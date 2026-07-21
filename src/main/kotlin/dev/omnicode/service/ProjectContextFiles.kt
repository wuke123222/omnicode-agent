package dev.omnicode.service

import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Shared filesystem boundary for repository-authored context. */
internal object ProjectContextPathPolicy {
    fun projectRoot(project: Project): Path {
        val basePath = requireNotNull(project.basePath) { "The project has no filesystem root" }
        return root(Path.of(basePath))
    }

    fun root(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isDirectory(normalized)) { "Project root is not a directory" }
        // The IDE may have opened a project through a symlink. Canonicalize that one boundary once;
        // descendants are still required to contain no symlink components.
        return normalized.toRealPath()
    }

    fun normalizeRelative(root: Path, input: String): String {
        require(input.isNotBlank()) { "Project-relative path must not be blank" }
        require(input.length <= MAX_PROJECT_CONTEXT_PATH_CHARS) { "Project-relative path is too long" }
        require(input.none { it == '\u0000' || it == '\n' || it == '\r' || it.isISOControl() }) {
            "Project-relative path contains control characters"
        }
        val portable = input.replace('\\', '/').removePrefix("./")
        require(!portable.startsWith('/') && !portable.startsWith("//")) {
            "Absolute paths are not allowed"
        }
        require(!WINDOWS_DRIVE_PATH.matches(portable)) { "Windows drive paths are not allowed" }
        val parsed = Path.of(portable)
        require(!parsed.isAbsolute) { "Absolute paths are not allowed" }
        val resolved = root.resolve(parsed).normalize().toAbsolutePath()
        require(resolved != root && resolved.startsWith(root)) { "Path escapes the project root" }
        rejectSymlinkComponents(root, resolved)
        return root.relativize(resolved).joinToString("/") { it.toString() }
    }

    fun resolve(root: Path, input: String): Path = root.resolve(normalizeRelative(root, input)).normalize()

    fun validateExisting(root: Path, candidate: Path): Path {
        val normalized = candidate.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Path escapes the project root" }
        rejectSymlinkComponents(root, normalized)
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(normalized)) { "Symbolic links are not allowed in project context" }
            val real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS)
            require(real.startsWith(root)) { "Path escapes the project root" }
        }
        return normalized
    }

    fun relativeOrNull(root: Path, absolutePath: String): String? = runCatching {
        val candidate = Path.of(absolutePath).toAbsolutePath().normalize()
        if (!candidate.startsWith(root) || candidate == root) return null
        val relative = root.relativize(candidate).joinToString("/") { it.toString() }
        normalizeRelative(root, relative)
    }.getOrNull()

    private fun rejectSymlinkComponents(root: Path, candidate: Path) {
        var cursor = root
        root.relativize(candidate).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Symbolic links are not allowed in project context" }
            }
        }
        val existingAncestor = generateSequence(candidate) { it.parent }
            .firstOrNull { it.startsWith(root) && Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: root
        val realAncestor = existingAncestor.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(realAncestor.startsWith(root)) { "Path escapes the project root" }
    }
}

internal data class BoundedUtf8File(
    val text: String,
    val totalBytes: Long,
    val bytesRead: Int,
    val truncated: Boolean,
)

internal class UnsafeProjectContextFileException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Strict, bounded reader used for rules and pinned repository context. */
internal object BoundedProjectFileReader {
    fun read(root: Path, candidate: Path, maxBytes: Int): BoundedUtf8File {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val path = try {
            ProjectContextPathPolicy.validateExisting(root, candidate)
        } catch (error: IllegalArgumentException) {
            throw UnsafeProjectContextFileException(error.message ?: "Unsafe project path", error)
        }
        val before = readRegularAttributes(path)
        val requestedBytes = minOf(before.size(), maxBytes.toLong()).toInt()
        val bytes = ByteArray(requestedBytes)
        var offset = 0
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        try {
            Files.newByteChannel(path, options).use { channel ->
                while (offset < bytes.size) {
                    val read = channel.read(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                    if (read < 0) break
                    if (read == 0) continue
                    offset += read
                }
            }
        } catch (error: IOException) {
            throw UnsafeProjectContextFileException("Cannot safely read project context file", error)
        }
        val after = readRegularAttributes(path)
        if (before.fileKey() != null && after.fileKey() != null && before.fileKey() != after.fileKey()) {
            throw UnsafeProjectContextFileException("Project context file changed while it was being read")
        }
        if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
            throw UnsafeProjectContextFileException("Project context file changed while it was being read")
        }
        val actual = if (offset == bytes.size) bytes else bytes.copyOf(offset)
        val truncated = after.size() > actual.size
        val decoded = decodeStrictUtf8(actual, allowIncompleteSuffix = truncated)
        val text = decoded.text.removePrefix("\uFEFF")
        if (text.any { it == '\u0000' || (it.isISOControl() && it !in ALLOWED_TEXT_CONTROLS) }) {
            throw UnsafeProjectContextFileException("Binary or control-character data is not valid project context")
        }
        return BoundedUtf8File(
            text = text,
            totalBytes = after.size(),
            bytesRead = decoded.bytesUsed,
            truncated = truncated,
        )
    }

    private fun readRegularAttributes(path: Path): BasicFileAttributes {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            throw UnsafeProjectContextFileException("Project context file is not readable", error)
        }
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw UnsafeProjectContextFileException("Project context must be a regular non-symbolic-link file")
        }
        return attributes
    }

    private fun decodeStrictUtf8(bytes: ByteArray, allowIncompleteSuffix: Boolean): DecodedUtf8 {
        val attempts = if (allowIncompleteSuffix) 0..minOf(3, bytes.size) else 0..0
        var lastFailure: CharacterCodingException? = null
        for (removed in attempts) {
            if (removed > 0 && !isIncompleteUtf8Suffix(bytes, removed)) continue
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return DecodedUtf8(
                    text = decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.size - removed)).toString(),
                    bytesUsed = bytes.size - removed,
                )
            } catch (error: CharacterCodingException) {
                lastFailure = error
            }
        }
        throw UnsafeProjectContextFileException("Project context file is not valid UTF-8", lastFailure)
    }

    private fun isIncompleteUtf8Suffix(bytes: ByteArray, removed: Int): Boolean {
        val start = bytes.size - removed
        if (start < 0 || removed !in 1..3) return false
        val first = bytes[start].toInt() and 0xFF
        val expectedLength = when (first) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return false
        }
        if (expectedLength <= removed) return false
        for (index in start + 1 until bytes.size) {
            val value = bytes[index].toInt() and 0xFF
            if (value !in 0x80..0xBF) return false
        }
        if (removed >= 2) {
            val second = bytes[start + 1].toInt() and 0xFF
            if (first == 0xE0 && second < 0xA0) return false
            if (first == 0xED && second > 0x9F) return false
            if (first == 0xF0 && second < 0x90) return false
            if (first == 0xF4 && second > 0x8F) return false
        }
        return true
    }

    private data class DecodedUtf8(val text: String, val bytesUsed: Int)
}

internal fun isPathExcluded(relativePath: String, exclusions: Collection<String>): Boolean = exclusions.any { excluded ->
    relativePath == excluded || relativePath.startsWith("$excluded/")
}

private const val MAX_PROJECT_CONTEXT_PATH_CHARS = 1_024
private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
private val ALLOWED_TEXT_CONTROLS = setOf('\t', '\n', '\r')
