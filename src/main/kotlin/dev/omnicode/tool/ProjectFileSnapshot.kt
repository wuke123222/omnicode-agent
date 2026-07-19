package dev.omnicode.tool

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class ProjectFileSnapshot(
    val text: String,
    val sha256: String,
)

internal fun readProjectFileSnapshot(project: Project, path: Path): ProjectFileSnapshot? {
    if (!Files.isRegularFile(path)) return null
    require(Files.size(path) <= 5_000_000) { "File is larger than 5 MB" }
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    val application = ApplicationManager.getApplication()
    val documentText = virtualFile?.let { file ->
        val readDocument = Computable {
            FileDocumentManager.getInstance().getDocument(file)?.text
        }
        if (application.isReadAccessAllowed) readDocument.compute()
        else application.runReadAction(readDocument)
    }
    val text = documentText ?: Files.readString(path, StandardCharsets.UTF_8)
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    return ProjectFileSnapshot(text, hash)
}
