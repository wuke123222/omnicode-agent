package dev.omnicode.tool

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class ApplyChangeTool : AgentTool {
    override val name = "apply_change"
    override val description = "Create or replace one UTF-8 project file after showing an approval preview. Use the SHA-256 returned by read_file, or MISSING for a new file."
    override val dangerous = true
    override val effect = ToolEffect.MUTATING
    override val inputSchema: JsonObject = objectSchema(required = listOf("path", "expected_sha256", "new_content")) {
        stringProperty("path", "Project-relative file path.")
        stringProperty("expected_sha256", "Current SHA-256 from read_file, or the literal MISSING for a new file.")
        stringProperty("new_content", "Complete desired UTF-8 file content.")
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(context.mode == dev.omnicode.agent.AgentMode.AGENT) {
            "${context.mode.name}_MODE_BLOCKED: File changes are disabled in ${context.mode.name} mode."
        }
        val relative = arguments.string("path")
        ProjectContextToolAccess.load(context.project).rejectionForRequestedPath(relative)?.let {
            return@withContext it
        }
        val expectedHash = arguments.string("expected_sha256")
        val newContent = arguments.string("new_content")
        require(newContent.toByteArray(StandardCharsets.UTF_8).size <= 2_000_000) { "New content is larger than 2 MB" }
        val path = ProjectPathGuard.resolve(context.project, relative)
        val before = readProjectFileSnapshot(context.project, path)?.text
        verifyHash(context.project, path, expectedHash)

        val approved = context.approvalGate.approve(
            ApprovalRequest(
                toolName = name,
                title = if (before == null) "Create $relative" else "Modify $relative",
                details = changeSummary(relative, before, newContent),
                risk = "This writes a project file. The change is recorded as one IDE command and can be undone.",
                diff = ApprovalDiff(relative, before.orEmpty(), newContent),
            ),
        )
        if (!approved) return@withContext ToolExecutionResult("REJECTED_BY_USER: File change was not applied.", true)

        val error = AtomicReference<Throwable?>(null)
        val write = Runnable {
            runCatching {
                val validatedPath = ProjectPathGuard.resolve(context.project, relative)
                verifyHash(context.project, validatedPath, expectedHash)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(validatedPath)?.let { existing ->
                    val status = ReadonlyStatusHandler.getInstance(context.project).ensureFilesWritable(listOf(existing))
                    require(!status.hasReadonlyFiles()) { "File is read-only: $relative" }
                }
                WriteCommandAction.writeCommandAction(context.project)
                    .withName("OmniCode: Apply $relative")
                    .run<RuntimeException> {
                        // Repeat path and content checks inside the write command to close the approval race.
                        val finalPath = ProjectPathGuard.resolve(context.project, relative)
                        verifyHash(context.project, finalPath, expectedHash)
                        val parent = VfsUtil.createDirectories(finalPath.parent.toString())
                        val file = parent.findChild(finalPath.fileName.toString())
                            ?: parent.createChildData(this, finalPath.fileName.toString())
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

        context.changeRecorder?.record(relative, before, newContent)
        val resultHash = sha256(context.project, path)
        ToolExecutionResult("Applied $relative successfully. New SHA-256: $resultHash")
    }

    private fun verifyHash(project: com.intellij.openapi.project.Project, path: java.nio.file.Path, expected: String) {
        if (expected == "MISSING") {
            require(!Files.exists(path)) { "FILE_CONFLICT: File now exists; read it before replacing it." }
            return
        }
        require(expected.matches(Regex("[a-fA-F0-9]{64}"))) { "expected_sha256 must be a 64-character hash or MISSING" }
        val snapshot = readProjectFileSnapshotForHash(project, path)
            ?: error("FILE_CONFLICT: File no longer exists.")
        require(snapshot.equals(expected, ignoreCase = true)) { "FILE_CONFLICT: File changed after it was read." }
    }

    private fun sha256(project: com.intellij.openapi.project.Project, path: java.nio.file.Path): String =
        requireNotNull(readProjectFileSnapshotForHash(project, path))

    private fun readProjectFileSnapshotForHash(
        project: com.intellij.openapi.project.Project,
        path: java.nio.file.Path,
    ): String? = readProjectFileSnapshot(project, path)?.sha256

    private fun changeSummary(path: String, old: String?, new: String): String {
        val oldLines = old?.lineSequence()?.count() ?: 0
        val newLines = new.lineSequence().count()
        return "File: $path\nOld: $oldLines lines · New: $newLines lines\nReview the complete diff before approving."
    }
}
