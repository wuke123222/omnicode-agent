package dev.omnicode.review

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskChangeReviewServiceTest {
    @Test
    fun `retains first before and replaces latest after per workflow path`() = withProject { root, service, _ ->
        write(root, "src/App.kt", "before\n")
        write(root, "src/App.kt", "first after\n")
        val first = service.recordChange("workflow-1", "src/./App.kt", "before\n", "first after\n")

        write(root, "src/App.kt", "latest after\n")
        val latest = service.recordChange("workflow-1", "src/App.kt", "first after\n", "latest after\n")

        assertEquals("src/App.kt", first?.relativePath)
        assertEquals("before\n", latest?.beforeContent)
        assertEquals("latest after\n", latest?.afterContent)
        assertEquals(sha256("before\n"), latest?.beforeSha256)
        assertEquals(sha256("latest after\n"), latest?.afterSha256)
        val stableIds = latest?.hunks?.map { it.id }
        assertEquals(stableIds, service.listHunks("workflow-1", "src/App.kt").map { it.id })
        assertTrue(latest.orFail().hunks.all { it.id.matches(Regex("[a-f0-9]{64}")) })

        service.clear("workflow-1")
        val rerecorded = service.recordChange("workflow-2", "src/App.kt", "before\n", "latest after\n")
        assertEquals(stableIds, rerecorded?.hunks?.map { it.id })
    }

    @Test
    fun `workflow state is isolated sorted and ignores an existing file no-op`() = withProject { root, service, _ ->
        write(root, "z.txt", "z0")
        write(root, "z.txt", "z1")
        service.recordChange("one", "z.txt", "z0", "z1")
        write(root, "a.txt", "a1")
        service.recordChange("one", "a.txt", null, "a1")

        write(root, "other.txt", "same")
        assertNull(service.recordChange("two", "other.txt", "same", "same"))

        assertEquals(listOf("a.txt", "z.txt"), service.listFiles("one").map { it.relativePath })
        assertTrue(service.listFiles("two").isEmpty())
        service.clear("one")
        assertTrue(service.listFiles("one").isEmpty())
    }

    @Test
    fun `rolls back and reapplies independent hunks with stable decisions`() = withProject { root, service, writes ->
        val before = "alpha\none\nmiddle\ntwo\nomega\n"
        val after = "alpha\nONE\nmiddle\nTWO\nomega\n"
        write(root, "App.txt", after)
        val recorded = service.recordChange("workflow", "App.txt", before, after).orFail()

        assertEquals(2, recorded.hunks.size)
        val first = recorded.hunks.single { it.beforeText == "one\n" }
        val second = recorded.hunks.single { it.beforeText == "two\n" }

        val partial = service.rollbackHunk("workflow", "App.txt", first.id)
        assertEquals("alpha\none\nmiddle\nTWO\nomega\n", Files.readString(root.resolve("App.txt")))
        assertEquals(TaskChangeDecision.MIXED, partial.decision)
        assertEquals(TaskChangeDecision.ROLLED_BACK, partial.hunks.single { it.id == first.id }.decision)

        // Keeping a still-present hunk is metadata-only.
        service.keepHunk("workflow", "App.txt", second.id)
        assertEquals(1, writes.count)
        val kept = service.keepHunk("workflow", "App.txt", first.id)
        assertEquals(after, Files.readString(root.resolve("App.txt")))
        assertEquals(TaskChangeDecision.KEPT, kept.decision)
        assertEquals(2, writes.count)
    }

    @Test
    fun `file rollback restores originals and deletes task-created files`() = withProject { root, service, writes ->
        write(root, "existing.txt", "changed")
        service.recordChange("workflow", "existing.txt", "original", "changed")
        write(root, "created.txt", "created")
        val created = service.recordChange("workflow", "created.txt", null, "created").orFail()

        val restored = service.rollbackFile("workflow", "existing.txt")
        assertEquals("original", Files.readString(root.resolve("existing.txt")))
        assertEquals(TaskChangeDecision.ROLLED_BACK, restored.decision)

        val deleted = service.rollbackHunk("workflow", "created.txt", created.hunks.single().id)
        assertFalse(Files.exists(root.resolve("created.txt"), LinkOption.NOFOLLOW_LINKS))
        assertNull(deleted.expectedCurrentContent)

        service.keepFile("workflow", "created.txt")
        assertEquals("created", Files.readString(root.resolve("created.txt")))
        service.rollbackFile("workflow", "created.txt")
        assertFalse(Files.exists(root.resolve("created.txt"), LinkOption.NOFOLLOW_LINKS))
        assertEquals(4, writes.count)
    }

    @Test
    fun `hunk rollback handles insertions and deletions with empty changed sides`() = withProject { root, service, writes ->
        val before = "head\nremove\nmiddle\ntail\n"
        val after = "head\nmiddle\ninsert\ntail\n"
        write(root, "edits.txt", after)
        val hunks = service.recordChange("workflow", "edits.txt", before, after).orFail().hunks
        val deletion = hunks.single { it.beforeText == "remove\n" && it.afterText.isEmpty() }
        val insertion = hunks.single { it.beforeText.isEmpty() && it.afterText == "insert\n" }

        service.rollbackHunk("workflow", "edits.txt", insertion.id)
        assertEquals("head\nmiddle\ntail\n", Files.readString(root.resolve("edits.txt")))
        service.rollbackHunk("workflow", "edits.txt", deletion.id)
        assertEquals(before, Files.readString(root.resolve("edits.txt")))

        service.keepHunk("workflow", "edits.txt", deletion.id)
        service.keepHunk("workflow", "edits.txt", insertion.id)
        assertEquals(after, Files.readString(root.resolve("edits.txt")))
        assertEquals(4, writes.count)
    }

    @Test
    fun `whole task rollback preflights every hash before writing anything`() = withProject { root, service, writes ->
        write(root, "a.txt", "A after")
        service.recordChange("workflow", "a.txt", "A before", "A after")
        write(root, "b.txt", "B after")
        service.recordChange("workflow", "b.txt", "B before", "B after")
        write(root, "b.txt", "external edit")

        val failure = assertFailsWith<TaskChangeConflictException> { service.rollbackTask("workflow") }

        assertTrue(failure.message.orEmpty().startsWith("FILE_CONFLICT:"))
        assertEquals("A after", Files.readString(root.resolve("a.txt")))
        assertEquals("external edit", Files.readString(root.resolve("b.txt")))
        assertEquals(0, writes.count, "the write command must not start until all files pass preflight")
    }

    @Test
    fun `whole task rollback restores all files even after keep decisions`() = withProject { root, service, writes ->
        write(root, "kept.txt", "after")
        service.recordChange("workflow", "kept.txt", "before", "after")
        service.keepFile("workflow", "kept.txt")
        write(root, "new.txt", "new")
        service.recordChange("workflow", "new.txt", null, "new")

        val review = service.rollbackTask("workflow")

        assertEquals("before", Files.readString(root.resolve("kept.txt")))
        assertFalse(Files.exists(root.resolve("new.txt"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(review.files.all { it.decision == TaskChangeDecision.ROLLED_BACK })
        assertEquals(1, writes.count, "all task paths should share one IDE write command")
    }

    @Test
    fun `single-file rollback rejects an external edit by sha256`() = withProject { root, service, writes ->
        write(root, "file.txt", "after")
        service.recordChange("workflow", "file.txt", "before", "after")
        write(root, "file.txt", "external")

        assertFailsWith<TaskChangeConflictException> { service.rollbackFile("workflow", "file.txt") }
        assertEquals("external", Files.readString(root.resolve("file.txt")))
        assertEquals(0, writes.count)
    }

    @Test
    fun `path guard is repeated inside the write command and blocks a symlink swap`() {
        val root = createTempDirectory("omnicode-review-project").toRealPath()
        val outside = createTempDirectory("omnicode-review-outside").toRealPath()
        try {
            val project = projectAt(root)
            val access = NioTaskChangeFileAccess()
            write(root, "dir/file.txt", "after")
            write(outside, "file.txt", "after")
            val runner = CountingWriteRunner {
                Files.delete(root.resolve("dir/file.txt"))
                Files.delete(root.resolve("dir"))
                Files.createSymbolicLink(root.resolve("dir"), outside)
            }
            val service = TaskChangeReviewService(project, access, runner)
            service.recordChange("workflow", "dir/file.txt", "before", "after")

            assertFailsWith<IllegalArgumentException> { service.rollbackFile("workflow", "dir/file.txt") }
            assertEquals("after", Files.readString(outside.resolve("file.txt")))
            assertEquals(1, runner.count)
        } finally {
            deleteRecursively(root)
            deleteRecursively(outside)
        }
    }

    @Test
    fun `unique inverse replacement rejects stale and ambiguous anchors`() {
        val duplicate = UniqueTextTransition("same", "old")
        assertFailsWith<TaskChangeConflictException> { duplicate.apply("same and same") }

        val source = "left same middle same right"
        val secondStart = source.lastIndexOf("same")
        val anchored = uniqueAnchoredTransition(source, secondStart, secondStart + 4, "changed")
        assertEquals("left same middle changed right", anchored.apply(source))
        assertFailsWith<TaskChangeConflictException> { anchored.apply("same ${anchored.oldText} ${anchored.oldText}") }
    }

    @Test
    fun `repeated change after rolling back a created file preserves missing first before`() = withProject { root, service, _ ->
        write(root, "created.txt", "one")
        service.recordChange("workflow", "created.txt", null, "one")
        service.rollbackFile("workflow", "created.txt")
        write(root, "created.txt", "two")

        val latest = service.recordChange("workflow", "created.txt", null, "two").orFail()

        assertNull(latest.beforeContent)
        service.rollbackTask("workflow")
        assertFalse(Files.exists(root.resolve("created.txt"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `line diff round trips deterministic files with duplicate lines`() = withProject { root, service, _ ->
        val random = Random(0x0C14)
        repeat(80) { index ->
            fun content(): String = List(random.nextInt(0, 12)) {
                listOf("alpha\n", "beta\n", "same\n", "same\n", "tail")[random.nextInt(5)]
            }.joinToString("")

            val before = content()
            var after = content()
            if (after == before) after += "changed-$index\n"
            val path = "round-trip/$index.txt"
            write(root, path, after)
            service.recordChange("workflow-$index", path, before, after).orFail()

            service.rollbackTask("workflow-$index")
            assertEquals(before, Files.readString(root.resolve(path)), "rollback mismatch for case $index")
            service.keepFile("workflow-$index", path)
            assertEquals(after, Files.readString(root.resolve(path)), "keep mismatch for case $index")
        }
    }

    private fun withProject(block: (Path, TaskChangeReviewService, CountingWriteRunner) -> Unit) {
        val root = createTempDirectory("omnicode-review-project").toRealPath()
        try {
            val runner = CountingWriteRunner()
            val service = TaskChangeReviewService(projectAt(root), NioTaskChangeFileAccess(), runner)
            block(root, service, runner)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun write(root: Path, relativePath: String, content: String) {
        val path = root.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    private fun projectAt(root: Path): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> root.toString()
            "isDisposed" -> false
            "getName" -> "review-test"
            "toString" -> "ReviewTestProject(${root.fileName})"
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

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private class NioTaskChangeFileAccess : TaskChangeFileAccess {
    override fun read(project: Project, path: Path): TaskChangeDiskSnapshot? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return TaskChangeDiskSnapshot(Files.readString(path, StandardCharsets.UTF_8))
    }

    override fun write(project: Project, path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    override fun delete(project: Project, path: Path) {
        if (!Files.deleteIfExists(path)) throw TaskChangeConflictException("${path.fileName} disappeared before deletion")
    }
}

private class CountingWriteRunner(
    private val beforeAction: () -> Unit = {},
) : TaskChangeWriteCommandRunner {
    var count: Int = 0
        private set

    override fun run(project: Project, name: String, action: () -> Unit) {
        count++
        beforeAction()
        action()
    }
}

private fun TaskChangedFile?.orFail(): TaskChangedFile = requireNotNull(this)
