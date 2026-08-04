package dev.omnicode.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.application.PathManager
import dev.omnicode.persistence.BoundedJsonlStore
import dev.omnicode.tool.ProjectPathGuard
import dev.omnicode.tool.readProjectFileSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-scoped review state for changes made by agent workflows. The hot map is backed by a
 * bounded, redacted IDE-system ledger so the review survives an IDE restart.
 *
 * The first `before` content is retained for the lifetime of a workflow/path while
 * each subsequent [recordChange] replaces only the latest `after` content. Every
 * mutation checks the expected SHA-256 and the guarded project path before and
 * again inside a single IDE write command.
 */
@Service(Service.Level.PROJECT)
class TaskChangeReviewService private constructor(
    private val project: Project,
    private val fileAccess: TaskChangeFileAccess,
    private val writeCommands: TaskChangeWriteCommandRunner,
    private val persistence: TaskChangeReviewPersistence?,
) {
    /** Public one-argument constructor also makes the project service easy to construct in tests. */
    constructor(project: Project) : this(
        project = project,
        fileAccess = IdeTaskChangeFileAccess,
        writeCommands = IdeTaskChangeWriteCommandRunner,
        persistence = TaskChangeReviewPersistence.forProject(project),
    )

    internal constructor(
        project: Project,
        fileAccess: TaskChangeFileAccess,
        writeCommands: TaskChangeWriteCommandRunner,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit,
    ) : this(project, fileAccess, writeCommands, null)

    private val lock = Any()
    private val workflows = linkedMapOf<String, LinkedHashMap<String, TrackedFile>>()

    init {
        persistence?.load().orEmpty().forEach { snapshot ->
            val files = snapshot.files.associateTo(LinkedHashMap()) { file ->
                file.relativePath to file.toTrackedFile()
            }
            if (files.isNotEmpty()) workflows[snapshot.workflowId] = files
        }
    }

    /**
     * Records a completed file write. The actual project file must already equal [after].
     * A repeated path must supply the content expected immediately before that write.
     * Existing-file no-ops are omitted from review and return null.
     */
    fun recordChange(
        workflowId: String,
        relativePath: String,
        before: String?,
        after: String,
    ): TaskChangedFile? = synchronized(lock) {
        validateWorkflowId(workflowId)
        validateContentSize("before", before)
        validateContentSize("after", after)
        val guarded = guardedPath(relativePath)
        val canonicalPath = canonicalRelativePath(guarded)
        val actual = fileAccess.read(project, guarded)
            ?: throw TaskChangeConflictException("$canonicalPath no longer exists after the recorded write")
        val afterHash = sha256(after)
        if (actual.sha256 != afterHash) {
            throw TaskChangeConflictException("$canonicalPath does not match the recorded after content")
        }

        val files = workflows.getOrPut(workflowId) { linkedMapOf() }
        val previous = files[canonicalPath]
        if (previous != null) {
            val expectedBefore = render(previous)
            val beforeMatches = if (expectedBefore.exists) {
                before != null && sha256(before) == sha256(expectedBefore.text)
            } else {
                before == null
            }
            if (!beforeMatches) {
                throw TaskChangeConflictException(
                    "$canonicalPath before content does not match the previous review state",
                )
            }
        }
        val firstBefore = if (previous != null) previous.beforeContent else before
        if (firstBefore != null && firstBefore == after) {
            files.remove(canonicalPath)
            if (files.isEmpty()) workflows.remove(workflowId)
            persistWorkflow(workflowId)
            return@synchronized null
        }

        val hunks = buildTrackedHunks(canonicalPath, firstBefore, after)
        val tracked = TrackedFile(
            relativePath = canonicalPath,
            beforeContent = firstBefore,
            afterContent = after,
            hunks = hunks,
        )
        files[canonicalPath] = tracked
        persistWorkflow(workflowId)
        tracked.toModel()
    }

    /** Returns every tracked file in deterministic project-relative path order. */
    fun listFiles(workflowId: String): List<TaskChangedFile> = synchronized(lock) {
        validateWorkflowId(workflowId)
        workflows[workflowId]
            ?.values
            ?.sortedBy(TrackedFile::relativePath)
            ?.map { it.toModel() }
            .orEmpty()
    }

    /** Returns the deterministic diff blocks for one tracked file. */
    fun listHunks(workflowId: String, relativePath: String): List<TaskChangeHunk> = synchronized(lock) {
        trackedFile(workflowId, relativePath).toModel().hunks
    }

    /** Returns a complete immutable workflow snapshot. Unknown workflows are represented as empty. */
    fun review(workflowId: String): TaskChangeReview = TaskChangeReview(workflowId, listFiles(workflowId))

    /** Workflow ids ordered with the most recently first recorded workflow first. */
    fun workflowIds(): List<String> = synchronized(lock) { workflows.keys.toList().asReversed() }

    /** Accepts the latest agent version of a file, reapplying rolled-back hunks when necessary. */
    fun keepFile(workflowId: String, relativePath: String): TaskChangedFile = synchronized(lock) {
        updateWholeFile(workflowId, relativePath, TaskChangeDecision.KEPT)
    }

    /** Accepts one hunk, reapplying it when that hunk had previously been rolled back. */
    fun keepHunk(workflowId: String, relativePath: String, hunkId: String): TaskChangedFile = synchronized(lock) {
        updateHunk(workflowId, relativePath, hunkId, TaskChangeDecision.KEPT)
    }

    /** Restores the file's original content, or deletes it when the workflow created it. */
    fun rollbackFile(workflowId: String, relativePath: String): TaskChangedFile = synchronized(lock) {
        updateWholeFile(workflowId, relativePath, TaskChangeDecision.ROLLED_BACK)
    }

    /** Reverses one uniquely anchored diff hunk and fails closed if the anchor is stale or ambiguous. */
    fun rollbackHunk(workflowId: String, relativePath: String, hunkId: String): TaskChangedFile = synchronized(lock) {
        updateHunk(workflowId, relativePath, hunkId, TaskChangeDecision.ROLLED_BACK)
    }

    /**
     * Restores every tracked path to its first `before` state. All paths and hashes are
     * preflighted together before the write command starts, and the complete preflight
     * is repeated inside that command before the first file is touched.
     */
    fun rollbackTask(workflowId: String): TaskChangeReview = synchronized(lock) {
        validateWorkflowId(workflowId)
        val files = workflows[workflowId]
            ?.values
            ?.sortedBy(TrackedFile::relativePath)
            .orEmpty()
        if (files.isEmpty()) return@synchronized TaskChangeReview(workflowId, emptyList())

        val operations = files.map { tracked ->
            val current = render(tracked)
            val rolledBack = render(tracked.withAllDecisions(TaskChangeDecision.ROLLED_BACK))
            PreparedFileOperation(tracked, current, rolledBack)
        }
        preflightAll(operations)
        if (operations.any { it.current.stateKey() != it.target.stateKey() }) {
            writeCommands.run(project, "OmniCode: Roll back task $workflowId") {
                // No write occurs until every path passes the second, atomic preflight.
                preflightAll(operations)
                operations.forEach { operation ->
                    if (operation.current.stateKey() != operation.target.stateKey()) {
                        applyFileState(operation.tracked.relativePath, operation.current, operation.target)
                    }
                }
            }
        }
        files.forEach { it.hunks.forEach { hunk -> hunk.decision = TaskChangeDecision.ROLLED_BACK } }
        persistWorkflow(workflowId)
        TaskChangeReview(workflowId, files.map { it.toModel() })
    }

    /** Removes in-memory review metadata without changing project files. */
    fun clear(workflowId: String) {
        validateWorkflowId(workflowId)
        synchronized(lock) {
            workflows.remove(workflowId)
            persistence?.delete(workflowId)
        }
    }

    private fun updateWholeFile(
        workflowId: String,
        relativePath: String,
        decision: TaskChangeDecision,
    ): TaskChangedFile {
        val tracked = trackedFile(workflowId, relativePath)
        val current = render(tracked)
        preflight(tracked.relativePath, current)
        val targetTracked = tracked.withAllDecisions(decision)
        val target = render(targetTracked)
        if (current.stateKey() != target.stateKey()) {
            writeCommands.run(project, commandName(decision, tracked.relativePath)) {
                preflight(tracked.relativePath, current)
                applyFileState(tracked.relativePath, current, target)
            }
        }
        tracked.hunks.forEach { it.decision = decision }
        persistWorkflow(workflowId)
        return tracked.toModel()
    }

    private fun updateHunk(
        workflowId: String,
        relativePath: String,
        hunkId: String,
        decision: TaskChangeDecision,
    ): TaskChangedFile {
        require(decision != TaskChangeDecision.MIXED && decision != TaskChangeDecision.PENDING)
        val tracked = trackedFile(workflowId, relativePath)
        val hunk = tracked.hunks.singleOrNull { it.id == hunkId }
            ?: throw TaskChangeNotFoundException("Unknown hunk $hunkId for ${tracked.relativePath}")
        val current = render(tracked)
        preflight(tracked.relativePath, current)
        if (hunk.decision == decision) return tracked.toModel()

        val targetTracked = tracked.withDecision(hunkId, decision)
        val target = render(targetTracked)
        if (current.stateKey() == target.stateKey()) {
            hunk.decision = decision
            return tracked.toModel()
        }
        val transition = prepareHunkTransition(current, target, hunkId)
        writeCommands.run(project, commandName(decision, tracked.relativePath)) {
            val actual = preflight(tracked.relativePath, current)
            val transitioned = transition.apply(actual.text)
            if (transitioned != target.text) {
                throw TaskChangeConflictException(
                    "${tracked.relativePath} no longer has one unique match for hunk $hunkId",
                )
            }
            applyFileState(tracked.relativePath, current, target, transitioned)
        }
        hunk.decision = decision
        persistWorkflow(workflowId)
        return tracked.toModel()
    }

    /** Imports the current project Git worktree diff into the durable review ledger. */
    fun importGitDiff(workflowId: String): TaskChangeReview = synchronized(lock) {
        validateWorkflowId(workflowId)
        GitChangeReviewImporter(project, fileAccess).importChangedFiles().forEach { change ->
            runCatching {
                recordChange(workflowId, change.relativePath, change.before, change.after)
            }
        }
        review(workflowId)
    }

    private fun persistWorkflow(workflowId: String) {
        val snapshot = workflows[workflowId]
            ?.values
            ?.sortedBy(TrackedFile::relativePath)
            ?.map { it.toModel() }
            ?.takeIf { it.isNotEmpty() }
        if (snapshot == null) persistence?.delete(workflowId)
        else persistence?.save(TaskChangeReviewSnapshot(workflowId, snapshot))
    }

    private fun prepareHunkTransition(
        current: RenderedFile,
        target: RenderedFile,
        hunkId: String,
    ): UniqueTextTransition {
        if (current.exists != target.exists) {
            // Creation review uses one synthetic whole-file hunk.
            return UniqueTextTransition(current.text, target.text, wholeDocument = true)
        }
        val span = current.spans[hunkId]
            ?: throw TaskChangeNotFoundException("Hunk $hunkId is not renderable in the current file")
        val replacementSpan = target.spans[hunkId]
            ?: throw TaskChangeNotFoundException("Hunk $hunkId is not renderable in the target file")
        val replacement = target.text.substring(replacementSpan.start, replacementSpan.endExclusive)
        val transition = uniqueAnchoredTransition(current.text, span.start, span.endExclusive, replacement)
        if (transition.apply(current.text) != target.text) {
            throw TaskChangeConflictException("hunk $hunkId does not produce the reviewed target content")
        }
        return transition
    }

    private fun preflightAll(operations: List<PreparedFileOperation>) {
        operations.forEach { operation -> preflight(operation.tracked.relativePath, operation.current) }
    }

    private fun preflight(relativePath: String, expected: RenderedFile): TaskChangeDiskSnapshot {
        val path = guardedPath(relativePath)
        val actual = fileAccess.read(project, path)
        if (!expected.exists) {
            if (actual != null || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw TaskChangeConflictException("$relativePath was expected to be absent")
            }
            return TaskChangeDiskSnapshot("", MISSING_HASH)
        }
        if (actual == null) throw TaskChangeConflictException("$relativePath no longer exists")
        val expectedHash = sha256(expected.text)
        if (actual.sha256 != expectedHash) {
            throw TaskChangeConflictException("$relativePath changed outside this task review")
        }
        return actual
    }

    private fun applyFileState(
        relativePath: String,
        expected: RenderedFile,
        target: RenderedFile,
        verifiedTargetText: String? = null,
    ) {
        val path = guardedPath(relativePath)
        // Close path and content races immediately before the individual mutation.
        preflight(relativePath, expected)
        if (!target.exists) {
            fileAccess.delete(project, path)
            if (fileAccess.read(project, guardedPath(relativePath)) != null) {
                throw TaskChangeConflictException("$relativePath could not be deleted")
            }
        } else {
            val content = verifiedTargetText ?: target.text
            if (content != target.text) {
                throw TaskChangeConflictException("$relativePath reviewed target content changed")
            }
            fileAccess.write(project, path, content)
            val written = fileAccess.read(project, guardedPath(relativePath))
                ?: throw TaskChangeConflictException("$relativePath was not written")
            if (written.sha256 != sha256(target.text)) {
                throw TaskChangeConflictException("$relativePath write did not produce the reviewed content")
            }
        }
    }

    private fun trackedFile(workflowId: String, relativePath: String): TrackedFile {
        validateWorkflowId(workflowId)
        val canonical = canonicalRelativePath(guardedPath(relativePath))
        return workflows[workflowId]?.get(canonical)
            ?: throw TaskChangeNotFoundException("No task change for $canonical in workflow $workflowId")
    }

    private fun guardedPath(relativePath: String): Path {
        require(relativePath.isNotBlank()) { "path must not be blank" }
        require(!Path.of(relativePath).isAbsolute) { "path must be project-relative" }
        val path = ProjectPathGuard.resolve(project, relativePath)
        require(path != ProjectPathGuard.root(project)) { "path must identify a project file" }
        return path
    }

    private fun canonicalRelativePath(path: Path): String =
        ProjectPathGuard.root(project).relativize(path).joinToString("/") { it.toString() }

    private fun validateWorkflowId(workflowId: String) {
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }
        require(workflowId.length <= 240) { "workflowId is too long" }
    }

    private fun validateContentSize(label: String, content: String?) {
        if (content == null) return
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_REVIEW_FILE_BYTES) {
            "$label content is larger than 5 MB"
        }
    }

    private fun commandName(decision: TaskChangeDecision, relativePath: String): String = when (decision) {
        TaskChangeDecision.KEPT -> "OmniCode: Keep $relativePath"
        TaskChangeDecision.ROLLED_BACK -> "OmniCode: Roll back $relativePath"
        else -> error("Unsupported write decision: $decision")
    }

    private fun TrackedFile.toModel(): TaskChangedFile {
        val rendered = render(this)
        val decisions = hunks.map(TrackedHunk::decision).toSet()
        val fileDecision = when {
            decisions.isEmpty() -> TaskChangeDecision.PENDING
            decisions.size == 1 -> decisions.single()
            else -> TaskChangeDecision.MIXED
        }
        return TaskChangedFile(
            relativePath = relativePath,
            beforeContent = beforeContent,
            afterContent = afterContent,
            expectedCurrentContent = rendered.text.takeIf { rendered.exists },
            beforeSha256 = beforeContent?.let(::sha256),
            afterSha256 = sha256(afterContent),
            expectedCurrentSha256 = rendered.text.takeIf { rendered.exists }?.let(::sha256),
            decision = fileDecision,
            hunks = hunks.map { it.toModel() },
        )
    }

    companion object {
        fun getInstance(project: Project): TaskChangeReviewService =
            project.getService(TaskChangeReviewService::class.java)
    }
}

internal data class TaskChangeDiskSnapshot(
    val text: String,
    val sha256: String = sha256(text),
)

/** Bounded durable snapshot used to restore review state after the IDE restarts. */
internal data class TaskChangeReviewSnapshot(
    val workflowId: String,
    val files: List<TaskChangedFile>,
    val updatedAt: Instant = Instant.now(),
) {
    val id: String get() = workflowId
}

private fun TaskChangedFile.toTrackedFile(): TrackedFile = TrackedFile(
    relativePath = relativePath,
    beforeContent = beforeContent,
    afterContent = afterContent,
    hunks = hunks.map { hunk ->
        TrackedHunk(
            id = hunk.id,
            beforeStartIndex = (hunk.beforeStartLine - 1).coerceAtLeast(0),
            beforeLineCount = hunk.beforeLineCount,
            afterStartIndex = (hunk.afterStartLine - 1).coerceAtLeast(0),
            afterLineCount = hunk.afterLineCount,
            beforeText = hunk.beforeText,
            afterText = hunk.afterText,
            decision = hunk.decision,
        )
    },
)

/** Small read-only Git adapter. It never mutates the repository and ignores untracked files. */
private class GitChangeReviewImporter(
    private val project: Project,
    private val fileAccess: TaskChangeFileAccess,
) {
    fun importChangedFiles(): List<GitChangedFile> {
        val root = runCatching { ProjectPathGuard.root(project) }.getOrNull() ?: return emptyList()
        val git = locateGit() ?: return emptyList()
        val result = runCatching {
            val process = ProcessBuilder(
                git.toString(), "-C", root.toString(), "diff", "--name-only", "--diff-filter=ACMRTUXB", "--",
            ).redirectErrorStream(true).start()
            if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            process
        }.getOrNull() ?: return emptyList()
        if (result.exitValue() != 0) return emptyList()
        return result.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.mapNotNull { raw ->
                val relative = raw.trim().replace('\\', '/')
                if (relative.isBlank() || relative.contains('\u0000')) return@mapNotNull null
                val safePath = runCatching { ProjectPathGuard.resolve(project, relative) }.getOrNull() ?: return@mapNotNull null
                val after = fileAccess.read(project, safePath)?.text ?: return@mapNotNull null
                val before = readHead(git, root, relative) ?: return@mapNotNull null
                if (before == after) null else GitChangedFile(relative, before, after)
            }.take(MAX_IMPORTED_FILES).toList()
        }
    }

    private fun readHead(git: Path, root: Path, relative: String): String? = runCatching {
        val process = ProcessBuilder(git.toString(), "-C", root.toString(), "show", "HEAD:$relative")
            .redirectErrorStream(false)
            .start()
        if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) null
        else process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
    }.getOrNull()

    private fun locateGit(): Path? = sequenceOf(
        System.getenv("GIT_EXECUTABLE"),
        "/usr/bin/git",
        "/opt/homebrew/bin/git",
        "/usr/local/bin/git",
    ).filterNotNull().map(Path::of).firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    private companion object { const val MAX_IMPORTED_FILES = 128 }
}

private data class GitChangedFile(val relativePath: String, val before: String, val after: String)

/** Global bounded JSONL storage keeps review metadata while avoiding project files and secrets. */
internal class TaskChangeReviewPersistence private constructor(
    private val projectKey: String,
    private val store: BoundedJsonlStore<TaskChangeReviewSnapshot>,
) {
    fun load(): List<TaskChangeReviewSnapshot> = store.readAll()
        .filter { it.workflowId.startsWith("$projectKey:") }
        .map { it.copy(workflowId = it.workflowId.removePrefix("$projectKey:")) }

    fun save(snapshot: TaskChangeReviewSnapshot) {
        store.upsert(snapshot.copy(workflowId = scopedId(snapshot.workflowId)))
    }

    fun delete(workflowId: String) {
        store.update { records -> records.filterNot { it.workflowId == scopedId(workflowId) } }
    }

    private fun scopedId(workflowId: String): String = "$projectKey:$workflowId"

    companion object {
        fun forProject(project: Project): TaskChangeReviewPersistence {
            val basePath = requireNotNull(project.basePath) { "The project has no filesystem root" }
            val key = sha256(basePath).take(32)
            val store = BoundedJsonlStore(
                path = Path.of(PathManager.getSystemPath()).resolve("omnicode/task-change-reviews.jsonl"),
                recordType = TaskChangeReviewSnapshot::class.java,
                maxRecords = 512,
                maxLineChars = 32 * 1_048_576,
                maxFileBytes = 256L * 1_048_576L,
                idSelector = TaskChangeReviewSnapshot::id,
                sanitizer = { snapshot -> snapshot.copy(files = snapshot.files.take(256)) },
                validator = { snapshot -> snapshot.workflowId.isNotBlank() && snapshot.files.size <= 256 },
                cacheRecords = true,
            )
            return TaskChangeReviewPersistence(key, store)
        }
    }
}

internal interface TaskChangeFileAccess {
    fun read(project: Project, path: Path): TaskChangeDiskSnapshot?
    fun write(project: Project, path: Path, content: String)
    fun delete(project: Project, path: Path)
}

internal fun interface TaskChangeWriteCommandRunner {
    fun run(project: Project, name: String, action: () -> Unit)
}

private object IdeTaskChangeFileAccess : TaskChangeFileAccess {
    override fun read(project: Project, path: Path): TaskChangeDiskSnapshot? =
        readProjectFileSnapshot(project, path)?.let { TaskChangeDiskSnapshot(it.text, it.sha256) }

    override fun write(project: Project, path: Path, content: String) {
        val parent = VfsUtil.createDirectories(requireNotNull(path.parent).toString())
        val file = parent.findChild(path.fileName.toString())
            ?: parent.createChildData(this, path.fileName.toString())
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)
        if (document != null) {
            document.setText(content)
            documents.saveDocument(document)
        } else {
            VfsUtil.saveText(file, content)
        }
        file.refresh(false, false)
    }

    override fun delete(project: Project, path: Path) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: throw TaskChangeConflictException("${path.fileName} disappeared before deletion")
        file.delete(this)
    }
}

private object IdeTaskChangeWriteCommandRunner : TaskChangeWriteCommandRunner {
    override fun run(project: Project, name: String, action: () -> Unit) {
        val failure = AtomicReference<Throwable?>(null)
        val runnable = Runnable {
            runCatching {
                WriteCommandAction.writeCommandAction(project)
                    .withName(name)
                    .run<RuntimeException>(action)
            }.onFailure(failure::set)
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) runnable.run() else application.invokeAndWait(runnable)
        failure.get()?.let { throw it }
    }
}

private data class TrackedFile(
    val relativePath: String,
    val beforeContent: String?,
    val afterContent: String,
    val hunks: List<TrackedHunk>,
) {
    fun withDecision(hunkId: String, decision: TaskChangeDecision): TrackedFile = copy(
        hunks = hunks.map { hunk ->
            hunk.copy(decision = if (hunk.id == hunkId) decision else hunk.decision)
        },
    )

    fun withAllDecisions(decision: TaskChangeDecision): TrackedFile = copy(
        hunks = hunks.map { it.copy(decision = decision) },
    )
}

private data class TrackedHunk(
    val id: String,
    val beforeStartIndex: Int,
    val beforeLineCount: Int,
    val afterStartIndex: Int,
    val afterLineCount: Int,
    val beforeText: String,
    val afterText: String,
    var decision: TaskChangeDecision = TaskChangeDecision.PENDING,
) {
    fun toModel(): TaskChangeHunk = TaskChangeHunk(
        id = id,
        beforeStartLine = beforeStartIndex + 1,
        beforeLineCount = beforeLineCount,
        afterStartLine = afterStartIndex + 1,
        afterLineCount = afterLineCount,
        beforeText = beforeText,
        afterText = afterText,
        decision = decision,
    )
}

private data class PreparedFileOperation(
    val tracked: TrackedFile,
    val current: RenderedFile,
    val target: RenderedFile,
)

private data class RenderedFile(
    val exists: Boolean,
    val text: String,
    val spans: Map<String, TextSpan>,
) {
    fun stateKey(): Pair<Boolean, String> = exists to text
}

private data class TextSpan(val start: Int, val endExclusive: Int)

private fun render(file: TrackedFile): RenderedFile {
    if (file.beforeContent == null) {
        val creation = file.hunks.single()
        val exists = creation.decision != TaskChangeDecision.ROLLED_BACK
        return RenderedFile(
            exists = exists,
            text = if (exists) file.afterContent else "",
            spans = mapOf(creation.id to TextSpan(0, if (exists) file.afterContent.length else 0)),
        )
    }

    val afterLines = splitLinesRetainingSeparators(file.afterContent)
    val result = StringBuilder(file.afterContent.length)
    val spans = linkedMapOf<String, TextSpan>()
    var afterCursor = 0
    file.hunks.forEach { hunk ->
        for (index in afterCursor until hunk.afterStartIndex) result.append(afterLines[index])
        val start = result.length
        result.append(if (hunk.decision == TaskChangeDecision.ROLLED_BACK) hunk.beforeText else hunk.afterText)
        spans[hunk.id] = TextSpan(start, result.length)
        afterCursor = hunk.afterStartIndex + hunk.afterLineCount
    }
    for (index in afterCursor until afterLines.size) result.append(afterLines[index])
    return RenderedFile(true, result.toString(), spans)
}

private fun buildTrackedHunks(relativePath: String, before: String?, after: String): List<TrackedHunk> {
    if (before == null) {
        return listOf(
            TrackedHunk(
                id = hunkId(relativePath, 0, 0, 0, splitLinesRetainingSeparators(after).size, "", after),
                beforeStartIndex = 0,
                beforeLineCount = 0,
                afterStartIndex = 0,
                afterLineCount = splitLinesRetainingSeparators(after).size,
                beforeText = "",
                afterText = after,
            ),
        )
    }
    return buildLineHunks(before, after).map { hunk ->
        TrackedHunk(
            id = hunkId(
                relativePath,
                hunk.beforeStartIndex,
                hunk.beforeLineCount,
                hunk.afterStartIndex,
                hunk.afterLineCount,
                hunk.beforeText,
                hunk.afterText,
            ),
            beforeStartIndex = hunk.beforeStartIndex,
            beforeLineCount = hunk.beforeLineCount,
            afterStartIndex = hunk.afterStartIndex,
            afterLineCount = hunk.afterLineCount,
            beforeText = hunk.beforeText,
            afterText = hunk.afterText,
        )
    }
}

private data class LineHunk(
    val beforeStartIndex: Int,
    val beforeLineCount: Int,
    val afterStartIndex: Int,
    val afterLineCount: Int,
    val beforeText: String,
    val afterText: String,
)

private enum class EditKind { EQUAL, DELETE, INSERT }
private data class LineEdit(val kind: EditKind, val text: String)

/** Myers line diff with deterministic tie-breaking and no quadratic matrix allocation. */
private fun buildLineHunks(before: String, after: String): List<LineHunk> {
    val beforeLines = splitLinesRetainingSeparators(before)
    val afterLines = splitLinesRetainingSeparators(after)
    var commonPrefix = 0
    while (
        commonPrefix < beforeLines.size &&
        commonPrefix < afterLines.size &&
        beforeLines[commonPrefix] == afterLines[commonPrefix]
    ) {
        commonPrefix++
    }
    var commonSuffix = 0
    while (
        commonSuffix < beforeLines.size - commonPrefix &&
        commonSuffix < afterLines.size - commonPrefix &&
        beforeLines[beforeLines.lastIndex - commonSuffix] == afterLines[afterLines.lastIndex - commonSuffix]
    ) {
        commonSuffix++
    }
    val beforeMiddle = beforeLines.subList(commonPrefix, beforeLines.size - commonSuffix)
    val afterMiddle = afterLines.subList(commonPrefix, afterLines.size - commonSuffix)
    if (beforeMiddle.isEmpty() && afterMiddle.isEmpty()) return emptyList()
    val edits = myersLineEdits(beforeMiddle, afterMiddle)
        ?: return listOf(
            LineHunk(
                beforeStartIndex = commonPrefix,
                beforeLineCount = beforeMiddle.size,
                afterStartIndex = commonPrefix,
                afterLineCount = afterMiddle.size,
                beforeText = beforeMiddle.joinToString(""),
                afterText = afterMiddle.joinToString(""),
            ),
        )
    val hunks = mutableListOf<LineHunk>()
    var beforeLine = commonPrefix
    var afterLine = commonPrefix
    var hunkBeforeStart = -1
    var hunkAfterStart = -1
    var deleted = StringBuilder()
    var inserted = StringBuilder()
    var deletedLines = 0
    var insertedLines = 0

    fun flush() {
        if (hunkBeforeStart < 0) return
        hunks += LineHunk(
            beforeStartIndex = hunkBeforeStart,
            beforeLineCount = deletedLines,
            afterStartIndex = hunkAfterStart,
            afterLineCount = insertedLines,
            beforeText = deleted.toString(),
            afterText = inserted.toString(),
        )
        hunkBeforeStart = -1
        hunkAfterStart = -1
        deleted = StringBuilder()
        inserted = StringBuilder()
        deletedLines = 0
        insertedLines = 0
    }

    edits.forEach { edit ->
        when (edit.kind) {
            EditKind.EQUAL -> {
                flush()
                beforeLine++
                afterLine++
            }
            EditKind.DELETE -> {
                if (hunkBeforeStart < 0) {
                    hunkBeforeStart = beforeLine
                    hunkAfterStart = afterLine
                }
                deleted.append(edit.text)
                deletedLines++
                beforeLine++
            }
            EditKind.INSERT -> {
                if (hunkBeforeStart < 0) {
                    hunkBeforeStart = beforeLine
                    hunkAfterStart = afterLine
                }
                inserted.append(edit.text)
                insertedLines++
                afterLine++
            }
        }
    }
    flush()
    return hunks
}

private fun myersLineEdits(before: List<String>, after: List<String>): List<LineEdit>? {
    if (before.isEmpty()) return after.map { LineEdit(EditKind.INSERT, it) }
    if (after.isEmpty()) return before.map { LineEdit(EditKind.DELETE, it) }
    val max = before.size + after.size
    if (max > MAX_MYERS_LINES) return null
    val offset = max + 1
    val vector = IntArray(max * 2 + 3)
    val trace = ArrayList<IntArray>()
    var finalDistance = -1
    search@ for (distance in 0..minOf(max, MAX_MYERS_EDIT_DISTANCE)) {
        trace += vector.copyOf()
        for (diagonal in -distance..distance step 2) {
            val index = offset + diagonal
            var x = if (
                diagonal == -distance ||
                (diagonal != distance && vector[index - 1] < vector[index + 1])
            ) {
                vector[index + 1]
            } else {
                vector[index - 1] + 1
            }
            var y = x - diagonal
            while (x < before.size && y < after.size && before[x] == after[y]) {
                x++
                y++
            }
            vector[index] = x
            if (x >= before.size && y >= after.size) {
                finalDistance = distance
                break@search
            }
        }
    }
    if (finalDistance < 0) return null

    var x = before.size
    var y = after.size
    val reversed = ArrayList<LineEdit>(before.size + after.size)
    for (distance in finalDistance downTo 1) {
        val vectorBeforeDistance = trace[distance]
        val diagonal = x - y
        val previousDiagonal = if (
            diagonal == -distance ||
            (diagonal != distance &&
                vectorBeforeDistance[offset + diagonal - 1] < vectorBeforeDistance[offset + diagonal + 1])
        ) {
            diagonal + 1
        } else {
            diagonal - 1
        }
        val previousX = vectorBeforeDistance[offset + previousDiagonal]
        val previousY = previousX - previousDiagonal
        while (x > previousX && y > previousY) {
            reversed += LineEdit(EditKind.EQUAL, before[x - 1])
            x--
            y--
        }
        if (x == previousX) {
            reversed += LineEdit(EditKind.INSERT, after[y - 1])
            y--
        } else {
            reversed += LineEdit(EditKind.DELETE, before[x - 1])
            x--
        }
    }
    while (x > 0 && y > 0) {
        check(before[x - 1] == after[y - 1]) { "Invalid diff reconstruction" }
        reversed += LineEdit(EditKind.EQUAL, before[x - 1])
        x--
        y--
    }
    while (x > 0) reversed += LineEdit(EditKind.DELETE, before[--x])
    while (y > 0) reversed += LineEdit(EditKind.INSERT, after[--y])
    reversed.reverse()
    return reversed
}

internal data class UniqueTextTransition(
    val oldText: String,
    val newText: String,
    val wholeDocument: Boolean = false,
) {
    fun apply(source: String): String {
        if (wholeDocument) {
            if (source != oldText) throw TaskChangeConflictException("whole-file transition is stale")
            return newText
        }
        if (oldText.isEmpty()) throw TaskChangeConflictException("empty hunk anchor is ambiguous")
        val first = source.indexOf(oldText)
        if (first < 0) throw TaskChangeConflictException("hunk anchor no longer matches")
        if (source.indexOf(oldText, first + 1) >= 0) {
            throw TaskChangeConflictException("hunk anchor matches more than once")
        }
        return source.replaceRange(first, first + oldText.length, newText)
    }
}

/** Builds the smallest exponentially expanded unique anchor around an exact text range. */
internal fun uniqueAnchoredTransition(
    source: String,
    start: Int,
    endExclusive: Int,
    replacement: String,
): UniqueTextTransition {
    require(start in 0..source.length && endExclusive in start..source.length) { "Invalid hunk range" }
    if (start == 0 && endExclusive == source.length) {
        return UniqueTextTransition(source, replacement, wholeDocument = true)
    }

    var context = 0
    while (true) {
        val left = (start - context).coerceAtLeast(0)
        val right = (endExclusive + context).coerceAtMost(source.length)
        val candidate = source.substring(left, right)
        if (candidate.isNotEmpty()) {
            val first = source.indexOf(candidate)
            if (first >= 0 && source.indexOf(candidate, first + 1) < 0) {
                val replacementWithContext = buildString {
                    append(source, left, start)
                    append(replacement)
                    append(source, endExclusive, right)
                }
                return UniqueTextTransition(candidate, replacementWithContext)
            }
        }
        if (left == 0 && right == source.length) {
            // A non-empty full document can occur only once at offset zero.
            if (source.isNotEmpty()) return UniqueTextTransition(source, source.replaceRange(start, endExclusive, replacement))
            return UniqueTextTransition(source, replacement, wholeDocument = true)
        }
        context = if (context == 0) 16 else (context * 2).coerceAtMost(source.length)
    }
}

private fun splitLinesRetainingSeparators(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) {
            lines += text.substring(start)
            break
        }
        lines += text.substring(start, newline + 1)
        start = newline + 1
    }
    return lines
}

private fun hunkId(
    relativePath: String,
    beforeStart: Int,
    beforeCount: Int,
    afterStart: Int,
    afterCount: Int,
    beforeText: String,
    afterText: String,
): String = sha256(
    buildString {
        append("omnicode-task-change-hunk-v1\u0000")
        append(relativePath).append('\u0000')
        append(beforeStart).append(':').append(beforeCount).append('\u0000')
        append(afterStart).append(':').append(afterCount).append('\u0000')
        append(beforeText).append('\u0000').append(afterText)
    },
)

internal fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private const val MAX_REVIEW_FILE_BYTES = 5_000_000
private const val MAX_MYERS_LINES = 10_000
private const val MAX_MYERS_EDIT_DISTANCE = 400
private const val MISSING_HASH = "MISSING"
