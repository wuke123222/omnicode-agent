
package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.omnicode.service.OmniCodeProjectService
import dev.omnicode.ui.web.OmniCodeWebViewPanel

class OmniCodeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OmniCodeWebViewPanel(project, project.service<OmniCodeProjectService>())
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        val newChatAction = object : DumbAwareAction("新建对话", "新建对话", AllIcons.General.Add) {
            override fun actionPerformed(event: AnActionEvent) = panel.startNewChat()

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = panel.canOpenNewChat()
            }
        }
        val historyAction = object : DumbAwareAction("历史记录", "历史记录", AllIcons.Vcs.History) {
            override fun actionPerformed(event: AnActionEvent) = panel.showHistory()

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = panel.canOpenNewChat()
            }
        }
        val settingsAction = object : DumbAwareAction("设置", "在侧边栏中配置 OmniCode", AllIcons.General.Settings) {
            override fun actionPerformed(event: AnActionEvent) = panel.openSettings()
        }
        toolWindow.setTitleActions(listOf(newChatAction, historyAction, settingsAction))
    }
}
