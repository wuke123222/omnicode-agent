package dev.omnicode.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import dev.omnicode.agent.AgentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** One deterministic, context-anchored edit inside an existing file. */
internal data class ExactReplacement(
    val oldText: String,
    val newText: String,
)

/**
 * Applies replacements in order. Every old fragment must occur exactly once in the
 * evolving document, which makes an ambiguous or stale patch fail closed.
 */
internal fun applyExactReplacements(
    source: String,
    replacements: List<ExactReplacement>,
): String {
    require(replacements.isNotEmpty()) { "PATCH_EMPTY: replacements must not be empty" }
    require(replacements.size <= MAX_PATCH_REPLACEMENTS) {
        "PATCH_TOO_LARGE: at most $MAX_PATCH_REPLACEMENTS replacements are allowed"
    }
    var result = source
    replacements.forEachIndexed { index, replacement ->
        require(replacement.oldText.isNotEmpty()) {
            "PATCH_INVALID: replacement ${index + 1} has empty old_text"
        }
        val first = result.indexOf(replacement.oldText)
        require(first >= 0) {
            "PATCH_CONTEXT_NOT_FOUND: replacement ${index + 1} no longer matches the file"
        }
        val second = result.indexOf(replacement.oldText, first + 1)
        require(second < 0) {
            "PATCH_AMBIGUOUS: replacement ${index + 1} matches more than once; include more surrounding context"
        }
        result = result.replaceRange(first, first + replacement.oldText.length, replacement.newText)
    }
    require(result != source) { "PATCH_NO_CHANGES: the patch does not change the file" }
    return result
}

/**
 * Performs small exact edits without requiring the model to resend a complete file.
 * The file hash is checked before approval and again inside the IDE write command.
 */
class ApplyPatchTool : AgentTool {
    override val name: String = "apply_patch"
    override val description: String =
        "Apply one or more exact, uniquely matching text replacements to an existing UTF-8 project file. " +
            "Prefer this over apply_change for localized edits. Read the file first and pass its SHA-256."
    override val dangerous: Boolean = true
    override val effect: ToolEffect = ToolEffect.MUTATING
    override val inputSchema: JsonObject = objectSchema(
        required = listOf("path", "expected_sha256", "replacements"),
    ) {
        stringProperty("path", "Project-relative path of an existing file.")
        stringProperty("expected_sha256", "Current SHA-256 returned by read_file.")
        add("replacements", JsonObject().apply {
            addProperty("type", "array")
            addProperty(
                "description",
                "Ordered exact replacements. Each old_text must match exactly once; include surrounding context when needed.",
            )
            addProperty("minItems", 1)
            addProperty("maxItems", MAX_PATCH_REPLACEMENTS)
            add("items", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    stringProperty("old_text", "Exact current text, including enough context to be unique.")
                    stringProperty("new_text", "Replacement text; use an empty string to delete the matched text.")
                })
                add("required", JsonArray().apply {
                    add("old_text")
                    add("new_text")
                })
                addProperty("additionalProperties", false)
            })
        })
    }

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(context.mode == AgentMode.AGENT) {
            "${context.mode.name}_MODE_BLOCKED: File changes are disabled in ${context.mode.name} mode."
        }
        val relative = arguments.string("path")
        val expectedHash = arguments.string("expected_sha256")
        require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) {
            "expected_sha256 must be the 64-character hash returned by read_file"
        }
        val replacements = parseReplacements(arguments)
        val path = ProjectPathGuard.resolve(context.project, relative)
        val snapshot = requireNotNull(readProjectFileSnapshot(context.project, path)) {
            "PATCH_FILE_MISSING: Read the existing file before patching it."
        }
        require(snapshot.sha256.equals(expectedHash, ignoreCase = true)) {
            "FILE_CONFLICT: File changed after it was read."
        }
        val newContent = applyExactReplacements(snapshot.text, replacements)
        require(newContent.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATCH_RESULT_BYTES) {
            "PATCH_TOO_LARGE: resulting file is larger than 2 MB"
        }

        val approved = context.approvalGate.approve(
            ApprovalRequest(
                toolName = name,
                title = "Patch $relative",
                details = "File: $relative\n${replacements.size} precise replacement(s)\nReview the complete diff before approving.",
                risk = "This writes one project file as a single undoable IDE command.",
                diff = ApprovalDiff(relative, snapshot.text, newContent),
            ),
        )
        if (!approved) return@withContext ToolExecutionResult("REJECTED_BY_USER: File patch was not applied.", true)

        val error = AtomicReference<Throwable?>(null)
        val write = Runnable {
            runCatching {
                val validatedPath = ProjectPathGuard.resolve(context.project, relative)
                val current = requireNotNull(readProjectFileSnapshot(context.project, validatedPath)) {
                    "FILE_CONFLICT: File no longer exists."
                }
                require(current.sha256.equals(expectedHash, ignoreCase = true)) {
                    "FILE_CONFLICT: File changed while awaiting approval."
                }
                val revalidatedContent = applyExactReplacements(current.text, replacements)
                require(revalidatedContent == newContent) {
                    "FILE_CONFLICT: Approved patch no longer produces the reviewed content."
                }
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(validatedPath)?.let { existing ->
                    val status = ReadonlyStatusHandler.getInstance(context.project).ensureFilesWritable(listOf(existing))
                    require(!status.hasReadonlyFiles()) { "File is read-only: $relative" }
                }
                WriteCommandAction.writeCommandAction(context.project)
                    .withName("OmniCode: Patch $relative")
                    .run<RuntimeException> {
                        val finalPath = ProjectPathGuard.resolve(context.project, relative)
                        val finalSnapshot = requireNotNull(readProjectFileSnapshot(context.project, finalPath)) {
                            "FILE_CONFLICT: File no longer exists."
                        }
                        require(finalSnapshot.sha256.equals(expectedHash, ignoreCase = true)) {
                            "FILE_CONFLICT: File changed before the write command."
                        }
                        require(applyExactReplacements(finalSnapshot.text, replacements) == newContent) {
                            "FILE_CONFLICT: Patch context changed before the write command."
                        }
                        val parent = VfsUtil.createDirectories(finalPath.parent.toString())
                        val file = parent.findChild(finalPath.fileName.toString())
                            ?: error("PATCH_FILE_MISSING: File disappeared before it could be written.")
                        val documentManager = FileDocumentManager.getInstance()
                        val document = documentManager.getDocument(file)
                        if (document != null) {
                            document.setText(newContent)
                            documentManager.saveDocument(document)
                        } else {
                            VfsUtil.saveText(file, newContent)
                        }
                        file.refresh(false, false)
                    }
            }.onFailure(error::set)
        }
        if (ApplicationManager.getApplication().isDispatchThread) write.run()
        else ApplicationManager.getApplication().invokeAndWait(write)
        error.get()?.let { throw it }

        val resultHash = requireNotNull(readProjectFileSnapshot(context.project, path)).sha256
        ToolExecutionResult("Patched $relative successfully. New SHA-256: $resultHash")
    }

    private fun parseReplacements(arguments: JsonObject): List<ExactReplacement> {
        val values = arguments.getAsJsonArray("replacements")
            ?: throw IllegalArgumentException("PATCH_INVALID: replacements must be an array")
        require(values.size() in 1..MAX_PATCH_REPLACEMENTS) {
            "PATCH_INVALID: replacements must contain 1 to $MAX_PATCH_REPLACEMENTS items"
        }
        return values.mapIndexed { index, element ->
            require(element.isJsonObject) { "PATCH_INVALID: replacement ${index + 1} must be an object" }
            val value = element.asJsonObject
            require(value.keySet().all { it == "old_text" || it == "new_text" }) {
                "PATCH_INVALID: replacement ${index + 1} contains an unknown field"
            }
            val oldText = value.get("old_text")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw IllegalArgumentException("PATCH_INVALID: replacement ${index + 1} needs old_text")
            val newText = value.get("new_text")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw IllegalArgumentException("PATCH_INVALID: replacement ${index + 1} needs new_text")
            ExactReplacement(oldText, newText)
        }
    }
}

private const val MAX_PATCH_REPLACEMENTS = 64
private const val MAX_PATCH_RESULT_BYTES = 2_000_000
