package dev.omnicode.ui.web

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDTarget
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.omnicode.tool.ProjectPathGuard
import dev.omnicode.ui.AttachmentIntake
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The Project tool window does not export browser `File` objects on an IDE-internal drag. */
internal class ProjectFileDropTarget(
    private val project: Project,
    private val onDropFiles: (List<String>) -> Unit,
) : DnDTarget {
    override fun update(event: DnDEvent): Boolean {
        val files = projectFileDropPaths(project, event)
        event.setDropPossible(files.isNotEmpty(), if (files.isNotEmpty()) "添加到 OmniCode 输入框" else "只支持项目内安全文件")
        return files.isNotEmpty()
    }

    override fun drop(event: DnDEvent) {
        val files = projectFileDropPaths(project, event)
        if (files.isNotEmpty()) onDropFiles(files)
    }
}

/** Resolve only path metadata here. File contents are read after submission by AttachmentIntake. */
internal fun projectFileDropPaths(project: Project, transferable: Transferable): List<String> {
    if (project.isDisposed) return emptyList()
    val psiPaths = runCatching {
        AbstractProjectViewPane.getTransferedPsiElements(transferable)
            ?.mapNotNull { (it as? PsiFile)?.virtualFile?.takeIf { file -> file.isInLocalFileSystem }?.toNioPath() }
            .orEmpty()
    }.getOrDefault(emptyList())
    val osPaths = if (psiPaths.isEmpty() && transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        runCatching {
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                ?.mapNotNull { (it as? File)?.toPath() }
                .orEmpty()
        }.getOrDefault(emptyList())
    } else emptyList()
    return safeProjectDropPaths(project, psiPaths + osPaths)
}

internal fun safeProjectDropPaths(project: Project, candidates: List<Path>): List<String> {
    if (project.isDisposed) return emptyList()
    val root = runCatching { ProjectPathGuard.root(project) }.getOrElse { return emptyList() }
    return candidates.asSequence().take(32).mapNotNull { candidate ->
        runCatching {
            val path = ProjectPathGuard.validate(project, candidate)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && AttachmentIntake.supports(path))
            root.relativize(path).toString()
        }.getOrNull()
    }.distinct().take(AttachmentIntake.MAX_ATTACHMENTS).toList()
}
