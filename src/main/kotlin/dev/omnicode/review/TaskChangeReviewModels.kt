package dev.omnicode.review

/** The review decision for a complete file or one diff hunk. */
enum class TaskChangeDecision {
    PENDING,
    KEPT,
    ROLLED_BACK,
    MIXED,
}

/**
 * One deterministic line-diff block. Line numbers are one-based; a side with
 * [beforeLineCount] or [afterLineCount] equal to zero denotes an insertion or deletion.
 */
data class TaskChangeHunk(
    /** Stable SHA-256 of the path, ranges, and changed text. */
    val id: String,
    val beforeStartLine: Int,
    val beforeLineCount: Int,
    val afterStartLine: Int,
    val afterLineCount: Int,
    val beforeText: String,
    val afterText: String,
    val decision: TaskChangeDecision,
)

/** A project-relative file and all changes made by one workflow. */
data class TaskChangedFile(
    val relativePath: String,
    /** Null means that the file did not exist before the workflow first changed it. */
    val beforeContent: String?,
    /** The most recent complete content recorded after an agent write. */
    val afterContent: String,
    /** Null means that review decisions currently expect the file not to exist. */
    val expectedCurrentContent: String?,
    val beforeSha256: String?,
    val afterSha256: String,
    val expectedCurrentSha256: String?,
    val decision: TaskChangeDecision,
    val hunks: List<TaskChangeHunk>,
)

/** Immutable snapshot consumed by a task-level change review UI. */
data class TaskChangeReview(
    val workflowId: String,
    val files: List<TaskChangedFile>,
)

/** A stale or externally modified file prevented a review operation. */
class TaskChangeConflictException(message: String) : IllegalStateException("FILE_CONFLICT: $message")

/** A requested workflow, path, or hunk is not present in the review state. */
class TaskChangeNotFoundException(message: String) : IllegalArgumentException(message)
