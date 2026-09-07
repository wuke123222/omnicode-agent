package dev.omnicode.ui.web

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import dev.omnicode.settings.TOKEN_TRACKER_DASHBOARD_URL
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Hosts TokenTracker as a top-level JCEF document inside the IDE.
 *
 * TokenTracker is a full third-party application with dynamic module loading, client-side routing,
 * storage and worker assumptions. Loading it as a nested sandboxed iframe inside OmniCode can leave
 * a blank document on some JCEF versions even though the loopback service is healthy. A dedicated
 * browser keeps it inside the IDE without relaxing OmniCode's own WebView CSP or bridge boundary.
 */
internal class TokenTrackerDashboardDialog(project: Project) : DialogWrapper(project, false) {
    private val dashboard = JBCefBrowser(TOKEN_TRACKER_DASHBOARD_URL).apply {
        setOpenLinksInExternalBrowser(true)
        setPageBackgroundColor("#151719")
    }

    init {
        title = "TokenTracker 使用统计"
        setCancelButtonText("关闭")
        init()
        Disposer.register(disposable, dashboard)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(1100, 720)
        add(dashboard.component, BorderLayout.CENTER)
    }

    override fun createActions() = arrayOf(cancelAction)

    override fun getDimensionServiceKey(): String = "OmniCode.TokenTrackerDashboard"
}
