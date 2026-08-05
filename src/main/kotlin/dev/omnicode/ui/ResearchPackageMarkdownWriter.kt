package dev.omnicode.ui

import dev.omnicode.service.ReproducibleResearchPackage
import dev.omnicode.service.ReproducibleResearchPackageExporter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

class ResearchPackageWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal enum class ResearchPackageWritePolicy {
    /** Publish only if the destination does not exist at commit time. */
    CREATE_NEW,

    /** Replace only the exact regular file whose identity the user confirmed. */
    REPLACE_MATCHING,
}

internal data class ResearchPackageTargetIdentity(
    val path: Path,
    val fileKey: String,
    val size: Long,
    val lastModifiedTime: FileTime,
    val creationTime: FileTime,
    val contentSha256: String,
)

/**
 * Strict side-effect layer for a path explicitly chosen by the user. The caller must opt into
 * replacement and pass the exact NOFOLLOW identity that was shown at confirmation time.
 */
internal class ResearchPackageMarkdownWriter(
    private val maxBytes: Int = ReproducibleResearchPackageExporter.DEFAULT_MAX_EXPORT_BYTES,
    private val beforeCommit: (Path) -> Unit = {},
) {
    init {
        require(maxBytes in ReproducibleResearchPackageExporter.MIN_EXPORT_BYTES..
            ReproducibleResearchPackageExporter.MAX_EXPORT_BYTES)
    }

    /** Safe compatibility entry point: a two-argument write is always create-only. */
    @Throws(ResearchPackageWriteException::class)
    fun writeAtomically(export: ReproducibleResearchPackage, selectedPath: Path): Path =
        writeAtomically(export, selectedPath, ResearchPackageWritePolicy.CREATE_NEW)

    @Throws(ResearchPackageWriteException::class)
    fun captureTargetIdentity(selectedPath: Path): ResearchPackageTargetIdentity {
        val destination = selectedPath.toAbsolutePath().normalize()
        validatePathAndParent(destination)
        return readRegularFileIdentity(destination, "The selected replacement target")
    }

    @Throws(ResearchPackageWriteException::class)
    fun writeAtomically(
        export: ReproducibleResearchPackage,
        selectedPath: Path,
        policy: ResearchPackageWritePolicy,
        expectedTarget: ResearchPackageTargetIdentity? = null,
    ): Path {
        val destination = selectedPath.toAbsolutePath().normalize()
        validatePathAndParent(destination)
        validatePolicy(destination, policy, expectedTarget)
        val bytes = export.markdown.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maxBytes) {
            throw ResearchPackageWriteException(
                "Research package is ${bytes.size} bytes; the write limit is $maxBytes bytes.",
            )
        }

        val parent = destination.parent
            ?: throw ResearchPackageWriteException("A Markdown export destination must have a parent directory.")
        val temporary = try {
            Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
        } catch (error: IOException) {
            throw ResearchPackageWriteException("Unable to create a temporary export file.", error)
        }
        try {
            restrictToOwner(temporary)
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }

            beforeCommit(destination)
            validatePathAndParent(destination)
            when (policy) {
                ResearchPackageWritePolicy.CREATE_NEW -> publishCreateNew(temporary, destination)
                ResearchPackageWritePolicy.REPLACE_MATCHING -> {
                    val expected = requireNotNull(expectedTarget)
                    requireMatchingTarget(destination, expected)
                    publishReplacement(temporary, destination)
                }
            }
            restrictToOwner(destination)
            forceDirectory(parent)
            return destination
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun validatePolicy(
        destination: Path,
        policy: ResearchPackageWritePolicy,
        expectedTarget: ResearchPackageTargetIdentity?,
    ) {
        when (policy) {
            ResearchPackageWritePolicy.CREATE_NEW -> {
                if (expectedTarget != null) {
                    throw ResearchPackageWriteException("CREATE_NEW must not receive a replacement identity.")
                }
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw ResearchPackageWriteException(
                        "The destination already exists; explicit replacement confirmation is required.",
                    )
                }
            }
            ResearchPackageWritePolicy.REPLACE_MATCHING -> {
                val expected = expectedTarget
                    ?: throw ResearchPackageWriteException(
                        "REPLACE_MATCHING requires the target identity captured at user confirmation.",
                    )
                if (expected.path != destination) {
                    throw ResearchPackageWriteException("The replacement identity belongs to a different path.")
                }
                requireMatchingTarget(destination, expected)
            }
        }
    }

    /**
     * Java's ATOMIC_MOVE contract does not guarantee no-replace semantics when the target exists.
     * A same-directory hard-link publication is atomic and fails if any file appears at the target.
     * The replacement path below retains ATOMIC_MOVE for confirmed overwrite operations.
     */
    private fun publishCreateNew(temporary: Path, destination: Path) {
        try {
            Files.createLink(destination, temporary)
        } catch (error: FileAlreadyExistsException) {
            throw ResearchPackageWriteException(
                "The destination appeared after selection; no file was overwritten.",
                error,
            )
        } catch (error: IOException) {
            throw ResearchPackageWriteException(
                "The selected filesystem could not atomically create the research package without overwrite.",
                error,
            )
        }
    }

    private fun publishReplacement(temporary: Path, destination: Path) {
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            throw ResearchPackageWriteException(
                "The selected filesystem could not atomically replace the research package.",
                error,
            )
        }
    }

    private fun requireMatchingTarget(destination: Path, expected: ResearchPackageTargetIdentity) {
        val current = readRegularFileIdentity(destination, "The replacement target")
        if (current != expected) {
            throw ResearchPackageWriteException(
                "The replacement target changed after confirmation; no file was overwritten.",
            )
        }
    }

    private fun readRegularFileIdentity(path: Path, label: String): ResearchPackageTargetIdentity {
        val attributes = readRegularFileAttributes(path, label)
        if (attributes.size() > maxBytes) {
            throw ResearchPackageWriteException(
                "$label is ${attributes.size()} bytes; replacement identity is limited to $maxBytes bytes.",
            )
        }
        val contentSha256 = hashRegularFile(path, label)
        val revalidated = readRegularFileAttributes(path, label)
        // Some Windows volumes/JDK combinations legally return a null fileKey. Keep
        // replacement usable there without dropping the race checks: creation time,
        // size, mtime and the bounded content digest form the conservative fallback.
        val fileKey = attributes.fileKey()?.toString()
            ?: fallbackFileKey(path, attributes)
        val revalidatedFileKey = revalidated.fileKey()?.toString()
            ?: fallbackFileKey(path, revalidated)
        if (fileKey != revalidatedFileKey ||
            attributes.size() != revalidated.size() ||
            attributes.lastModifiedTime() != revalidated.lastModifiedTime() ||
            attributes.creationTime() != revalidated.creationTime()
        ) {
            throw ResearchPackageWriteException(
                "$label changed while its identity was captured; no file was overwritten.",
            )
        }
        return ResearchPackageTargetIdentity(
            path = path.toAbsolutePath().normalize(),
            fileKey = fileKey,
            size = attributes.size(),
            lastModifiedTime = attributes.lastModifiedTime(),
            creationTime = attributes.creationTime(),
            contentSha256 = contentSha256,
        )
    }

    private fun fallbackFileKey(path: Path, attributes: BasicFileAttributes): String =
        "fallback:${path.toAbsolutePath().normalize()}:${attributes.creationTime().toMillis()}"

    private fun readRegularFileAttributes(path: Path, label: String): BasicFileAttributes {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: NoSuchFileException) {
            throw ResearchPackageWriteException("$label no longer exists; no file was overwritten.", error)
        } catch (error: IOException) {
            throw ResearchPackageWriteException("Unable to read $label identity without following links.", error)
        }
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw ResearchPackageWriteException("$label is no longer the confirmed regular file.")
        }
        return attributes
    }

    private fun hashRegularFile(path: Path, label: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(16 * 1024)
        var total = 0L
        try {
            Files.newByteChannel(
                path,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                while (true) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxBytes) {
                        throw ResearchPackageWriteException(
                            "$label changed beyond the $maxBytes byte identity limit; no file was overwritten.",
                        )
                    }
                    digest.update(buffer.array(), 0, read)
                }
            }
        } catch (error: ResearchPackageWriteException) {
            throw error
        } catch (error: IOException) {
            throw ResearchPackageWriteException("Unable to hash $label without following links.", error)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun validatePathAndParent(destination: Path) {
        val fileName = destination.fileName?.toString().orEmpty()
        if (!fileName.lowercase(Locale.ROOT).endsWith(".md")) {
            throw ResearchPackageWriteException("Research packages must use a .md file extension.")
        }
        val parent = destination.parent
            ?: throw ResearchPackageWriteException("A Markdown export destination must have a parent directory.")
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw ResearchPackageWriteException("The selected parent must be an existing, non-symbolic-link directory.")
        }
        if (Files.isSymbolicLink(destination)) {
            throw ResearchPackageWriteException("Refusing to use a symbolic-link export destination.")
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw ResearchPackageWriteException("The selected destination is not a regular file.")
        }
    }

    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun forceDirectory(parent: Path) {
        runCatching {
            FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
