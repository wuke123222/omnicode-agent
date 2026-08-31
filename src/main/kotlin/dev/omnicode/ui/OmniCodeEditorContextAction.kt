package dev.omnicode.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.omnicode.ui.web.OmniCodeWebViewPanel
import java.nio.file.Path

/** Opens OmniCode with an editable reference to the current file or editor selection. */
class OmniCodeEditorContextAction : DumbAwareAction() {
    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = project != null && file != null && projectRelativePath(project, file) != null
        event.presentation.text = if (event.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true) {
            "发送选中代码到 OmniCode"
        } else {
            "发送当前文件到 OmniCode"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val relative = projectRelativePath(project, file) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel
        val reference = if (editor != null && selection?.hasSelection() == true) {
            val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
            val lastOffset = (selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)
            val endLine = editor.document.getLineNumber(lastOffset) + 1
            editorContextReference(relative, startLine, endLine)
        } else {
            editorContextReference(relative, null, null)
        }
        val prompt = editorContextPrompt(reference, selection?.hasSelection() == true)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            val panel = toolWindow.contentManager.contents
                .asSequence()
                .mapNotNull { it.component as? OmniCodeWebViewPanel }
                .firstOrNull()
            panel?.prefillChat(prompt)
        }
    }

    private fun projectRelativePath(project: Project, file: VirtualFile): String? {
        val root = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize() ?: return null
        val path = runCatching { Path.of(file.path).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (!path.startsWith(root)) return null
        return root.relativize(path).joinToString("/") { it.toString() }.takeIf(String::isNotBlank)
    }

    private companion object {
        const val TOOL_WINDOW_ID = "OmniCode"
    }
}

internal fun editorContextReference(path: String, startLine: Int?, endLine: Int?): String {
    val normalized = path.replace('\\', '/')
    if (startLine == null || endLine == null) return "@$normalized"
    require(startLine > 0 && endLine >= startLine) { "Invalid editor selection lines" }
    return if (startLine == endLine) "@$normalized:L$startLine" else "@$normalized:L$startLine-L$endLine"
}

internal fun editorContextPrompt(reference: String, selection: Boolean): String = buildString {
    append(if (selection) "请处理当前选中的代码：" else "请处理当前文件：")
    append(reference)
    append("\n\n需求：")
}
