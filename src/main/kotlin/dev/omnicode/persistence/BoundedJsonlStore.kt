package dev.omnicode.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class BoundedJsonlStore<T : Any>(
    path: Path,
    private val recordType: Class<T>,
    private val maxRecords: Int,
    private val maxLineChars: Int,
    private val maxFileBytes: Long,
    private val idSelector: (T) -> String,
    private val sanitizer: (T) -> T,
    private val validator: (T) -> Boolean,
) {
    private val path = path.toAbsolutePath().normalize()
    private val lockPath = this.path.resolveSibling("${this.path.fileName}.lock")
    private val processLock = PROCESS_LOCKS.computeIfAbsent(this.path) { ReentrantLock() }
    private val knownIds = linkedSetOf<String>()

    private var indexInitialized = false
    private var indexedFileSize = -1L

    init {
        require(maxRecords > 0)
        require(maxLineChars > 0)
        require(maxFileBytes > 0)
        require(maxFileBytes <= Int.MAX_VALUE)
    }

    fun append(record: T): Boolean = locked {
        ensureIndex()
        val sanitized = checkedSanitized(record)
        val id = idSelector(sanitized)
        if (id in knownIds) return@locked false

        appendLine(sanitized)
        knownIds += id
        indexedFileSize = fileSize()

        if (knownIds.size > maxRecords || indexedFileSize > maxFileBytes) {
            compact()
        }
        true
    }

    fun readAll(): List<T> = locked {
        ensureIndex()
        deduplicate(readValid().records).takeLast(maxRecords)
    }

    fun update(transform: (List<T>) -> List<T>): List<T> = locked {
        ensureIndex()
        val current = deduplicate(readValid().records).takeLast(maxRecords)
        val transformed = transform(current).map(::checkedSanitized)
        val retained = deduplicate(transformed).takeLast(maxRecords)
        writeAtomic(retained)
        rebuildIndex(retained)
        retained
    }

    fun clear() {
        locked {
            writeAtomic(emptyList())
            rebuildIndex(emptyList())
        }
    }

    private fun ensureIndex() {
        val currentSize = fileSize()
        if (indexInitialized && indexedFileSize == currentSize) return

        val read = readValid()
        val retained = deduplicate(read.records).takeLast(maxRecords)
        if (read.invalidLineCount > 0 || read.truncated || read.records.size != retained.size) {
            writeAtomic(retained)
        }
        rebuildIndex(retained)
    }

    private fun compact() {
        val retained = deduplicate(readValid().records).takeLast(maxRecords)
        writeAtomic(retained)
        rebuildIndex(retained)
    }

    private fun rebuildIndex(records: List<T>) {
        knownIds.clear()
        records.forEach { record -> knownIds += idSelector(record) }
        indexInitialized = true
        indexedFileSize = fileSize()
    }

    private fun checkedSanitized(record: T): T {
        val sanitized = sanitizer(record)
        require(runCatching { validator(sanitized) }.getOrDefault(false)) { "Invalid persistence record" }
        require(idSelector(sanitized).isNotBlank()) { "Persistence record id must not be blank" }
        val serialized = PersistenceJson.gson.toJson(sanitized)
        require(serialized.length <= maxLineChars) { "Persistence record exceeds the configured size limit" }
        return sanitized
    }

    private fun deduplicate(records: List<T>): List<T> {
        val byId = LinkedHashMap<String, T>()
        records.forEach { record ->
            val id = runCatching { idSelector(record) }.getOrNull().orEmpty()
            if (id.isNotBlank()) {
                byId.remove(id)
                byId[id] = record
            }
        }
        return byId.values.toList()
    }

    private fun appendLine(record: T) {
        ensureParentDirectory()
        val serialized = PersistenceJson.gson.toJson(record) + "\n"
        val bytes = serialized.toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            writeFully(channel, ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        restrictFilePermissions(path)
    }

    private fun readValid(): ReadResult<T> {
        if (!Files.isRegularFile(path)) return ReadResult(emptyList(), 0, false)
        val bounded = readBoundedText()
        val records = ArrayList<T>()
        var invalidLines = 0
        bounded.text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            if (line.length > maxLineChars) {
                invalidLines++
                return@forEach
            }
            val record = runCatching { PersistenceJson.gson.fromJson(line, recordType) }.getOrNull()
            val valid = record != null && runCatching {
                validator(record) && idSelector(record).isNotBlank()
            }.getOrDefault(false)
            if (valid) {
                records += requireNotNull(record)
            } else {
                invalidLines++
            }
        }
        return ReadResult(records, invalidLines, bounded.truncated)
    }

    private fun readBoundedText(): BoundedText {
        val size = fileSize()
        if (size == 0L) return BoundedText("", false)
        val bytesToRead = minOf(size, maxFileBytes).toInt()
        val start = size - bytesToRead
        val buffer = ByteBuffer.allocate(bytesToRead)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Keep reading until the selected suffix has been consumed.
            }
        }
        buffer.flip()
        var text = StandardCharsets.UTF_8.decode(buffer).toString()
        if (start > 0) {
            text = text.substringAfter('\n', "")
        }
        return BoundedText(text, start > 0)
    }

    private fun writeAtomic(records: List<T>) {
        ensureParentDirectory()
        val temporary = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                records.forEach { record ->
                    val line = PersistenceJson.gson.toJson(record) + "\n"
                    writeFully(channel, ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8)))
                }
                channel.force(true)
            }
            restrictFilePermissions(temporary)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictFilePermissions(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun ensureParentDirectory() {
        Files.createDirectories(path.parent)
        restrictDirectoryPermissions(path.parent)
    }

    private fun fileSize(): Long = runCatching { Files.size(path) }.getOrDefault(0L)

    private fun <R> locked(action: () -> R): R = processLock.withLock {
        ensureParentDirectory()
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            restrictFilePermissions(lockPath)
            channel.lock().use { action() }
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private data class ReadResult<T>(
        val records: List<T>,
        val invalidLineCount: Int,
        val truncated: Boolean,
    )

    private data class BoundedText(
        val text: String,
        val truncated: Boolean,
    )

    private companion object {
        val PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )

        fun restrictDirectoryPermissions(path: Path) {
            runCatching { Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS) }
        }

        fun restrictFilePermissions(path: Path) {
            runCatching { Files.setPosixFilePermissions(path, FILE_PERMISSIONS) }
        }
    }
}
