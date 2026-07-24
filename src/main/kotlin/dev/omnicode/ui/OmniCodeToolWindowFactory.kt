
package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.omnicode.service.OmniCodeProjectService

class OmniCodeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OmniCodeToolWindowPanel(project, project.service<OmniCodeProjectService>())
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        val newChatAction = object : DumbAwareAction("新建对话", "新建对话", AllIcons.General.Add) {
            override fun actionPerformed(event: AnActionEvent) = panel.startNewChat()

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = panel.canStartNewChat()
            }
        }
        val historyAction = object : DumbAwareAction("历史记录", "历史记录", AllIcons.Vcs.History) {
            override fun actionPerformed(event: AnActionEvent) = panel.showHistory()

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = panel.canStartNewChat()
            }
        }
        val settingsAction = object : DumbAwareAction("设置", "在侧边栏中配置 OmniCode", AllIcons.General.Settings) {
            override fun actionPerformed(event: AnActionEvent) = panel.openSettings()
        }
        toolWindow.setTitleActions(listOf(newChatAction, historyAction, settingsAction))
        toolWindow.setAdditionalGearActions(DefaultActionGroup().apply {
            add(object : DumbAwareAction("生成 Commit Message…") {
                override fun actionPerformed(event: AnActionEvent) = panel.generateCommitMessage()

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = panel.canGenerateCommitMessage()
                }
            })
            add(object : DumbAwareAction("导出可复现实验研究包…") {
                override fun actionPerformed(event: AnActionEvent) = panel.exportResearchPackage()

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = panel.canExportResearchPackage()
                }
            })
            addSeparator()
            add(object : DumbAwareAction("供应商与 API Key…") {
                override fun actionPerformed(event: AnActionEvent) = panel.openSettings(OmniCodeSettingsPage.PROVIDERS)
            })
            add(object : DumbAwareAction("切换供应商…") {
                override fun actionPerformed(event: AnActionEvent) = panel.showProviderSelector()

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = panel.canStartNewChat()
                }
            })
            add(object : DumbAwareAction("平台、MCP、提示词与 Skills…") {
                override fun actionPerformed(event: AnActionEvent) = panel.openSettings(OmniCodeSettingsPage.MCP)
            })
            add(object : DumbAwareAction("用量与对话历史…") {
                override fun actionPerformed(event: AnActionEvent) = panel.openSettings(OmniCodeSettingsPage.USAGE)
            })
        })
    }
}
