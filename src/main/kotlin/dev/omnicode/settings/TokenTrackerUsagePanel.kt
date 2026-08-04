package dev.omnicode.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.UIManager

/** Optional companion card. Built-in OmniCode usage accounting remains independent of it. */
internal class TokenTrackerUsagePanel(
    private val integration: TokenTrackerIntegration = TokenTrackerIntegration(),
    private val browse: (String) -> Unit = BrowserUtil::browse,
    private val copyText: (String) -> Unit = { value ->
        CopyPasteManager.getInstance().setContents(StringSelection(value))
    },
) : JPanel(BorderLayout(JBUI.scale(10), JBUI.scale(8))), Disposable {
    private val disposed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val connection = JBLabel("尚未检测")
    private val detail = JBLabel("OmniCode 内置统计始终独立可用。")
    private val openButton = JButton("打开本地面板").apply {
        isEnabled = false
        toolTipText = TOKEN_TRACKER_DASHBOARD_URL
        addActionListener {
            if (lastStatus?.dashboard?.state == TokenTrackerDashboardState.READY) {
                browse(TOKEN_TRACKER_DASHBOARD_URL)
            }
        }
    }
    private val commandButton = JButton("复制安装命令").apply {
        toolTipText = "只复制命令，不会由 OmniCode 执行"
        addActionListener { copySuggestedCommand() }
    }
    private val refreshButton = JButton("检测").apply {
        addActionListener { refresh() }
    }
    private var lastStatus: TokenTrackerStatus? = null

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(
                UIManager.getColor("Component.borderColor")
                    ?: UIManager.getColor("Separator.foreground")
                    ?: Color.GRAY,
            ),
            JBUI.Borders.empty(10, 12),
        )

        add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                isOpaque = false
                add(JBLabel("TokenTracker（可选，本地）").apply {
                    font = font.deriveFont(Font.BOLD)
                }, BorderLayout.NORTH)
                add(JBLabel("跨工具用量面板；不会替代或接收 OmniCode 内置统计。"), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(connection, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        add(JBTextArea(
            "仅检测 127.0.0.1:7680 和本机 PATH。安装、启动均只复制命令；" +
                "TokenTracker 首次启动会配置已检测到的 AI 工具 hooks，请先审阅。",
        ).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            foreground = UIManager.getColor("Label.disabledForeground")
        }, BorderLayout.CENTER)

        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(detail, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JButton("官方说明").apply {
                    toolTipText = TOKEN_TRACKER_DOCUMENTATION_URL
                    addActionListener { browse(TOKEN_TRACKER_DOCUMENTATION_URL) }
                })
                add(commandButton)
                add(openButton)
                add(refreshButton)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    fun refresh() {
        if (disposed.get()) return
        val currentGeneration = generation.incrementAndGet()
        connection.text = "检测中…"
        detail.text = "正在检查固定回环端口和本机 CLI，不读取任何用量数据。"
        refreshButton.isEnabled = false
        AppExecutorUtil.getAppExecutorService().submit {
            val status = integration.inspect()
            ApplicationManager.getApplication().invokeLater({
                if (disposed.get() || generation.get() != currentGeneration) return@invokeLater
                show(status)
            }, ModalityState.any())
        }
    }

    override fun dispose() {
        disposed.set(true)
        generation.incrementAndGet()
    }

    private fun show(status: TokenTrackerStatus) {
        lastStatus = status
        refreshButton.isEnabled = true
        openButton.isEnabled = status.dashboard.state == TokenTrackerDashboardState.READY
        commandButton.text = if (status.cliExecutable == null) "复制安装命令" else "复制启动命令"
        connection.text = when (status.dashboard.state) {
            TokenTrackerDashboardState.READY -> "● 已连接"
            TokenTrackerDashboardState.NOT_RUNNING -> "○ 未运行"
            TokenTrackerDashboardState.UNVERIFIED_SERVICE -> "! 端口被占用"
            TokenTrackerDashboardState.ERROR -> "! 检测失败"
        }
        detail.text = buildString {
            append(status.dashboard.detail)
            status.cliExecutable?.let { executable ->
                append(" · CLI：")
                append(executable)
            } ?: append(" · 未检测到 CLI（需要 Node.js 20+）")
        }.take(MAX_STATUS_CHARS)
    }

    private fun copySuggestedCommand() {
        val installed = lastStatus?.cliExecutable != null
        val command = if (installed) tokenTrackerStartCommand() else TOKEN_TRACKER_INSTALL_COMMAND
        copyText(command)
        detail.text = if (installed) {
            "已复制启动命令（默认关闭 TokenTracker 匿名遥测）；首次运行前请审阅其 hooks 变更。"
        } else {
            "已复制全局安装命令；OmniCode 不会执行它。安装完成后请再次检测。"
        }
    }

    private companion object {
        const val MAX_STATUS_CHARS = 360
    }
}
