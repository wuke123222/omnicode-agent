package dev.omnicode.ui

import com.intellij.ide.dnd.FileCopyPasteUtil
import dev.omnicode.model.UserAttachment
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

internal data class AcceptedAttachmentPath(
    val sourceKey: String,
    val attachment: UserAttachment,
)

internal data class RejectedAttachmentPath(
    val fileName: String,
    val message: String,
)

internal data class AttachmentBatchResult(
    val accepted: List<AcceptedAttachmentPath>,
    val rejected: List<RejectedAttachmentPath>,
    val omittedByLimit: Int,
)

internal object AttachmentBatchIntake {
    const val MAX_DROP_CANDIDATES = 32

    fun sourceKey(path: Path): String = path.toAbsolutePath().normalize().toString()

    fun read(paths: List<Path>, availableSlots: Int): AttachmentBatchResult {
        if (availableSlots <= 0 || paths.isEmpty()) {
            return AttachmentBatchResult(emptyList(), emptyList(), paths.size)
        }

        val accepted = mutableListOf<AcceptedAttachmentPath>()
        val rejected = mutableListOf<RejectedAttachmentPath>()
        val candidates = paths.take(MAX_DROP_CANDIDATES)
        var omitted = (paths.size - candidates.size).coerceAtLeast(0)

        for ((index, path) in candidates.withIndex()) {
            if (accepted.size >= availableSlots) {
                omitted += candidates.size - index
                break
            }
            when (val result = AttachmentIntake.read(path)) {
                is AttachmentIntakeResult.Accepted -> accepted += AcceptedAttachmentPath(
                    sourceKey = sourceKey(path),
                    attachment = result.attachment,
                )
                is AttachmentIntakeResult.Rejected -> rejected += RejectedAttachmentPath(
                    fileName = path.fileName?.toString().orEmpty().ifBlank { "未知文件" },
                    message = result.message,
                )
            }
        }
        return AttachmentBatchResult(accepted, rejected, omitted)
    }
}

internal fun attachmentPathsFromDropPayload(attachedObject: Any?): List<Path> {
    if (attachedObject == null) return emptyList()
    return runCatching {
        FileCopyPasteUtil.getFileListFromAttachedObject(attachedObject)
            .map { it.toPath().toAbsolutePath().normalize() }
    }.getOrDefault(emptyList())
}

internal fun attachmentBatchStatus(acceptedNames: List<String>, rejectedCount: Int): String = when {
    acceptedNames.size == 1 && rejectedCount == 0 -> "已添加 ${acceptedNames.single()}"
    acceptedNames.isNotEmpty() && rejectedCount == 0 -> "已添加 ${acceptedNames.size} 个附件"
    acceptedNames.isNotEmpty() -> "已添加 ${acceptedNames.size} 个附件；$rejectedCount 个未添加"
    rejectedCount > 0 -> "$rejectedCount 个附件未添加"
    else -> "没有可添加的附件"
}

internal suspend fun <T> captureAttachmentWork(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
