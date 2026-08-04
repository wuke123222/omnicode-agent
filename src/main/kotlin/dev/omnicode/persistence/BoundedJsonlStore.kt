package dev.omnicode.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
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
    private val cacheRecords: Boolean = false,
    private val protectedFromEviction: (T) -> Boolean = { false },
    /** Optional read-time normalizer for legacy records whose semantic defaults must be preserved. */
    private val normalizer: ((T) -> T)? = null,
) {
    private val path = path.toAbsolutePath().normalize()
    private val lockPath = this.path.resolveSibling("${this.path.fileName}.lock")
    private val processLock = PROCESS_LOCKS.computeIfAbsent(this.path) { ReentrantLock() }
    private val knownIds = linkedSetOf<String>()
    private val indexedRecords = LinkedHashMap<String, T>()
    private val indexedRecordBytes = mutableMapOf<String, Long>()

    private var indexInitialized = false
    private var indexedFileStamp: FileStamp? = null
    private var indexedLineCount = 0
    private var cachedRecordBytes = 0L

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

        persistLatest(id, sanitized)
        true
    }

    fun readAll(): List<T> = locked {
        ensureIndex()
        retainedRecords(readValid().records)
    }

    fun find(id: String): T? = locked {
        ensureIndex()
        findIndexedRecord(id)
    }

    /**
     * Durably appends a latest-wins record and compacts periodically. Hot checkpoint updates
     * therefore keep their fsync boundary without rewriting every retained record on each turn.
     */
    fun upsert(record: T, select: (T?, T) -> T = { _, candidate -> candidate }): T = locked {
        require(cacheRecords) { "Append-only upsert requires record caching" }
        ensureIndex()
        val candidate = checkedSanitized(record)
        val id = idSelector(candidate)
        val existing = findIndexedRecord(id)
        val selected = checkedSanitized(select(existing, candidate))
        require(idSelector(selected) == id) { "Persistence record id cannot change during upsert" }
        if (existing == selected) return@locked selected

        persistLatest(id, selected)
        selected
    }

    /** Atomically transforms one existing record while retaining the append-only hot path. */
    fun updateExisting(id: String, transform: (T) -> T): T? = locked {
        require(cacheRecords) { "Append-only update requires record caching" }
        ensureIndex()
        val existing = findIndexedRecord(id) ?: return@locked null
        val updated = checkedSanitized(transform(existing))
        require(idSelector(updated) == id) { "Persistence record id cannot change during update" }
        if (existing == updated) return@locked existing

        persistLatest(id, updated)
        updated
    }

    fun update(transform: (List<T>) -> List<T>): List<T> = locked {
        ensureIndex()
        val current = retainedRecords(readValid().records)
        val transformed = transform(current).map(::checkedSanitized)
        val retained = retainedRecords(transformed)
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
        val currentStamp = fileStamp()
        if (indexInitialized && indexedFileStamp == currentStamp) return

        val read = readValid()
        val deduplicated = deduplicate(read.records)
        // Cached latest-wins stores may tighten their sanitizer over time (for example,
        // recovery checkpoints now keep only a bounded message slice). Normalize legacy
        // records on the first read so old oversized revisions do not keep slowing every
        // subsequent startup.
        val normalized = if (cacheRecords) deduplicated.map(::checkedNormalized) else deduplicated
        val retained = retainedRecords(normalized)
        // Repeated latest-wins lines are the normal hot format. Another open project must not
        // rewrite the shared file merely because it observes those lines before this instance.
        val rewritten = read.invalidLineCount > 0 || read.truncated ||
            normalized != deduplicated ||
            normalized.size != retained.size ||
            read.records.size.toLong() > bufferedLineLimit()
        if (rewritten) {
            writeAtomic(retained)
        }
        rebuildIndex(retained, if (rewritten) retained.size else read.records.size)
    }

    private fun compact() {
        val retained = retainedRecords(readValid().records)
        writeAtomic(retained)
        rebuildIndex(retained)
    }

    /**
     * Never publishes an oversized append-only file. When the next durable line would cross the
     * byte bound, publish the latest retained set atomically instead so a crash cannot make the
     * next reader start inside an older recovery record.
     */
    private fun persistLatest(id: String, record: T) {
        val lineBytes = serializedLine(record)
        val newIdWouldCrossRecordLimit = id !in knownIds && knownIds.size >= maxRecords
        // Checkpoint records can legitimately shrink after a bounded recovery snapshot is
        // introduced. Compact that replacement immediately instead of carrying old, oversized
        // revisions forever; otherwise the next project startup must parse the whole append log.
        val cachedPreviousBytes = indexedRecordBytes[id]
        val replacementShrankSignificantly = cachedPreviousBytes != null &&
            cachedPreviousBytes >= lineBytes.size.toLong() * 2L
        if (newIdWouldCrossRecordLimit || fileSize() + lineBytes.size.toLong() > maxFileBytes) {
            val current = deduplicate(readValid().records)
            val retained = retainedRecords(current + record)
            require(retained.any { idSelector(it) == id }) {
                "Newest persistence record exceeds the configured file budget"
            }
            writeAtomic(retained)
            rebuildIndex(retained)
            return
        }

        if (replacementShrankSignificantly) {
            val current = deduplicate(readValid().records)
            val retained = retainedRecords(current.filterNot { idSelector(it) == id } + record)
            writeAtomic(retained)
            rebuildIndex(retained)
            return
        }

        appendLine(lineBytes)
        putIndexed(id, record)
        indexedLineCount++
        indexedFileStamp = fileStamp()
        if (shouldCompact()) compact()
    }

    private fun retainedRecords(records: List<T>): List<T> =
        withinFileBudget(withinRecordBudget(deduplicate(records)))

    private fun withinRecordBudget(records: List<T>): List<T> {
        if (records.size <= maxRecords) return records
        val protectedIds = records.asSequence()
            .filter(protectedFromEviction)
            .map(idSelector)
            .toSet()
        require(protectedIds.size <= maxRecords) {
            "Protected persistence records exceed the configured record limit"
        }
        val optionalSlots = maxRecords - protectedIds.size
        val retainedIds = protectedIds.toMutableSet()
        records.asReversed().asSequence()
            .filterNot { idSelector(it) in protectedIds }
            .take(optionalSlots)
            .mapTo(retainedIds, idSelector)
        return records.filter { idSelector(it) in retainedIds }
    }

    private fun withinFileBudget(records: List<T>): List<T> {
        if (records.isEmpty()) return records
        val serializedBytes = records.associate { record ->
            idSelector(record) to serializedLine(record).size.toLong().also { bytes ->
                require(bytes <= maxFileBytes) { "Persistence record exceeds the configured file byte limit" }
            }
        }
        val protectedIds = records.asSequence()
            .filter(protectedFromEviction)
            .map(idSelector)
            .toSet()
        var retainedBytes = protectedIds.sumOf { requireNotNull(serializedBytes[it]) }
        require(retainedBytes <= maxFileBytes) {
            "Protected persistence records exceed the configured file byte limit"
        }
        val retainedIds = protectedIds.toMutableSet()
        for (record in records.asReversed()) {
            val id = idSelector(record)
            if (id in retainedIds) continue
            val bytes = requireNotNull(serializedBytes[id])
            if (retainedBytes + bytes > maxFileBytes) break
            retainedIds += id
            retainedBytes += bytes
        }
        return records.filter { idSelector(it) in retainedIds }
    }

    private fun rebuildIndex(records: List<T>, lineCount: Int = records.size) {
        knownIds.clear()
        indexedRecords.clear()
        indexedRecordBytes.clear()
        cachedRecordBytes = 0L
        records.forEach { record -> knownIds += idSelector(record) }
        if (cacheRecords) records.takeLast(MAX_CACHED_RECORDS).forEach(::cacheRecord)
        indexInitialized = true
        indexedLineCount = lineCount
        indexedFileStamp = fileStamp()
    }

    private fun putIndexed(id: String, record: T) {
        knownIds.remove(id)
        knownIds += id
        if (cacheRecords) cacheRecord(record)
    }

    private fun findIndexedRecord(id: String): T? {
        indexedRecords[id]?.let { return it }
        val record = deduplicate(readValid().records).firstOrNull { idSelector(it) == id }
        if (record != null && cacheRecords) cacheRecord(record)
        return record
    }

    private fun cacheRecord(record: T) {
        val id = idSelector(record)
        indexedRecordBytes.remove(id)?.let { cachedRecordBytes -= it }
        indexedRecords.remove(id)
        val bytes = serializedLine(record).size.toLong()
        if (bytes > MAX_CACHED_RECORD_BYTES) return
        while (indexedRecords.size >= MAX_CACHED_RECORDS || cachedRecordBytes + bytes > MAX_CACHED_RECORD_BYTES) {
            val eldestId = indexedRecords.keys.firstOrNull() ?: break
            indexedRecords.remove(eldestId)
            indexedRecordBytes.remove(eldestId)?.let { cachedRecordBytes -= it }
        }
        indexedRecords[id] = record
        indexedRecordBytes[id] = bytes
        cachedRecordBytes += bytes
    }

    private fun shouldCompact(): Boolean {
        return knownIds.size > maxRecords ||
            indexedLineCount.toLong() > bufferedLineLimit() ||
            (indexedFileStamp?.size ?: fileSize()) > maxFileBytes ||
            // A long latest-wins history can stay below the line/file caps while still making
            // every restart parse many superseded copies of the same checkpoint.
            indexedLineCount > knownIds.size + maxOf(32, knownIds.size)
    }

    private fun bufferedLineLimit(): Long = maxRecords.toLong()
        .plus(minOf(maxRecords.toLong() * 3L, MAX_BUFFERED_UPSERT_LINES.toLong()))

    private fun checkedSanitized(record: T): T {
        val sanitized = sanitizer(record)
        require(runCatching { validator(sanitized) }.getOrDefault(false)) { "Invalid persistence record" }
        require(idSelector(sanitized).isNotBlank()) { "Persistence record id must not be blank" }
        val serialized = PersistenceJson.gson.toJson(sanitized)
        require(serialized.length <= maxLineChars) { "Persistence record exceeds the configured size limit" }
        return sanitized
    }

    private fun checkedNormalized(record: T): T {
        val normalized = (normalizer ?: sanitizer)(record)
        require(runCatching { validator(normalized) }.getOrDefault(false)) { "Invalid persistence record" }
        require(idSelector(normalized).isNotBlank()) { "Persistence record id must not be blank" }
        require(PersistenceJson.gson.toJson(normalized).length <= maxLineChars) {
            "Persistence record exceeds the configured size limit"
        }
        return normalized
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

    private fun appendLine(bytes: ByteArray) {
        ensureParentDirectory()
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            writeFully(channel, ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        restrictFilePermissions(path)
    }

    private fun serializedLine(record: T): ByteArray =
        (PersistenceJson.gson.toJson(record) + "\n").toByteArray(StandardCharsets.UTF_8)

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
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
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

    private fun fileStamp(): FileStamp {
        val attributes = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        }.getOrNull() ?: return FileStamp(0L, 0L, null)
        return FileStamp(
            size = attributes.size(),
            modifiedMillis = attributes.lastModifiedTime().toMillis(),
            fileKey = attributes.fileKey()?.toString(),
        )
    }

    private fun <R> locked(action: () -> R): R = processLock.withLock {
        ensureParentDirectory()
        require(!Files.isSymbolicLink(path) && !Files.isSymbolicLink(lockPath)) {
            "Persistence files must not be symbolic links"
        }
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
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

    private data class FileStamp(
        val size: Long,
        val modifiedMillis: Long,
        val fileKey: String?,
    )

    private companion object {
        const val MAX_BUFFERED_UPSERT_LINES = 128
        const val MAX_CACHED_RECORDS = 8
        const val MAX_CACHED_RECORD_BYTES = 8L * 1_048_576L
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
