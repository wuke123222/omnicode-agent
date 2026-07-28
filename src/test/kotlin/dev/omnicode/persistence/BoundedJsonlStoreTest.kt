package dev.omnicode.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedJsonlStoreTest {
    private lateinit var root: Path

    @BeforeTest
    fun createStoreDirectory() {
        root = Files.createTempDirectory("omnicode-bounded-jsonl-test")
    }

    @AfterTest
    fun deleteStoreDirectory() {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `upsert compacts before publishing a file beyond its byte budget`() {
        val path = root.resolve("checkpoints.jsonl")
        val store = store(path)
        val secondRecord = TestRecord("workflow-b", "b".repeat(72))

        store.upsert(TestRecord("workflow-a", "initial-" + "a".repeat(64)))
        store.upsert(secondRecord)
        repeat(12) { revision ->
            val latest = TestRecord("workflow-a", "revision-$revision-" + "a".repeat(60))
            store.upsert(latest)

            assertTrue(Files.size(path) <= MAX_FILE_BYTES)
            assertEquals(latest, store.find("workflow-a"))
        }

        val reloaded = store(path)
        assertEquals(secondRecord, reloaded.find("workflow-b"))
        assertEquals("revision-11-" + "a".repeat(60), reloaded.find("workflow-a")?.value)
        assertTrue(Files.size(path) <= MAX_FILE_BYTES)
    }

    @Test
    fun `record and byte compaction retain unresolved protected records`() {
        val path = root.resolve("protected.jsonl")
        val store = store(path, maxRecords = 2, protectedRecords = true)
        val protected = TestRecord("unknown-side-effect", "protected-" + "p".repeat(44), protected = true)
        val newest = TestRecord("latest", "latest-" + "c".repeat(44))

        store.upsert(protected)
        store.upsert(TestRecord("middle", "middle-" + "b".repeat(44)))
        store.upsert(newest)

        assertEquals(protected, store.find(protected.id))
        assertEquals(newest, store.find(newest.id))
        assertNull(store.find("middle"))
        assertTrue(Files.size(path) <= MAX_FILE_BYTES)
    }

    @Test
    fun `protected capacity rejects new records before changing the durable file`() {
        val path = root.resolve("protected-full.jsonl")
        val store = store(path, maxRecords = 2, protectedRecords = true)
        store.upsert(TestRecord("protected-a", "a", protected = true))
        store.upsert(TestRecord("protected-b", "b", protected = true))
        val before = Files.readString(path)

        assertFailsWith<IllegalArgumentException> {
            store.upsert(TestRecord("ordinary-c", "c"))
        }
        assertEquals(before, Files.readString(path))
        assertNull(store.find("ordinary-c"))

        assertFailsWith<IllegalArgumentException> {
            store.upsert(TestRecord("protected-c", "c", protected = true))
        }
        assertEquals(before, Files.readString(path))
        assertNull(store.find("protected-c"))
    }

    @Test
    fun `another store observes append records without eagerly rewriting them`() {
        val path = root.resolve("shared.jsonl")
        val first = store(path)
        val second = store(path)
        first.upsert(TestRecord("workflow-a", "first"))
        first.upsert(TestRecord("workflow-a", "second"))

        assertEquals("second", second.find("workflow-a")?.value)
        assertEquals(2, Files.readAllLines(path).count(String::isNotBlank))

        second.upsert(TestRecord("workflow-b", "other"))
        assertEquals("other", first.find("workflow-b")?.value)
        assertEquals(3, Files.readAllLines(path).count(String::isNotBlank))
    }

    @Test
    fun `symbolic link storage fails closed`() {
        val target = root.resolve("outside.jsonl")
        Files.writeString(target, "")
        val link = root.resolve("linked.jsonl")
        if (runCatching { Files.createSymbolicLink(link, target.fileName) }.isFailure) return

        assertFailsWith<IllegalArgumentException> {
            store(link).upsert(TestRecord("workflow-a", "blocked"))
        }
        assertEquals("", Files.readString(target))
    }

    private fun store(
        path: Path,
        maxRecords: Int = 4,
        protectedRecords: Boolean = false,
    ): BoundedJsonlStore<TestRecord> = BoundedJsonlStore(
        path = path,
        recordType = TestRecord::class.java,
        maxRecords = maxRecords,
        maxLineChars = 256,
        maxFileBytes = MAX_FILE_BYTES,
        idSelector = TestRecord::id,
        sanitizer = { it },
        validator = { it.id.isNotBlank() },
        cacheRecords = true,
        protectedFromEviction = { protectedRecords && it.protected },
    )

    private data class TestRecord(
        val id: String,
        val value: String,
        val protected: Boolean = false,
    )

    private companion object {
        const val MAX_FILE_BYTES = 250L
    }
}
