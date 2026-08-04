package dev.omnicode.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Embeds the third-party TokenTracker dashboard in the Usage sidebar page.
 *
 * OmniCode intentionally does not render a second usage dashboard or merge its own estimates into
 * this view. Its local usage records remain available to runtime recovery/audit code, while the
 * user-facing usage, cost, trend and model breakdown come solely from TokenTracker.
 */
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
    private val detail = JBLabel("用量、费用和趋势由 TokenTracker 提供。")
    private val openButton = JButton("外部打开").apply {
        isEnabled = false
        toolTipText = TOKEN_TRACKER_DASHBOARD_URL
        addActionListener {
            if (lastStatus?.dashboard?.state == TokenTrackerDashboardState.READY) {
                browse(TOKEN_TRACKER_DASHBOARD_URL)
            }
        }
    }
    private val commandButton = JButton("复制启动命令").apply {
        toolTipText = "只复制 npx 命令，不会由 OmniCode 执行"
        addActionListener { copySuggestedCommand() }
    }
    private val refreshButton = JButton("检测").apply {
        addActionListener { refresh() }
    }
    private var lastStatus: TokenTrackerStatus? = null
    private val cardsLayout = CardLayout()
    private val cards = JPanel(cardsLayout)
    private val browserHost = JPanel(BorderLayout())
    private var browser: JBCefBrowser? = null

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
                add(JBLabel("本页用量、费用和趋势全部来自 TokenTracker。"), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(connection, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        cards.isOpaque = false
        cards.add(instructionCard(), EMPTY_CARD)
        cards.add(browserHost, BROWSER_CARD)
        cards.add(loadingCard(), LOADING_CARD)
        add(cards, BorderLayout.CENTER)

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
        detail.text = "正在检查 TokenTracker 本地面板；不会读取或复制它的用量数据。"
        refreshButton.isEnabled = false
        cardsLayout.show(cards, LOADING_CARD)
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
        browser?.dispose()
        browser = null
    }

    private fun show(status: TokenTrackerStatus) {
        lastStatus = status
        refreshButton.isEnabled = true
        openButton.isEnabled = status.dashboard.state == TokenTrackerDashboardState.READY
        commandButton.text = "复制启动命令"
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
            } ?: append(" · 可使用 npx（需要 Node.js 20+）")
        }.take(MAX_STATUS_CHARS)
        if (status.dashboard.state == TokenTrackerDashboardState.READY) {
            showDashboard()
        } else {
            cardsLayout.show(cards, EMPTY_CARD)
        }
    }

    private fun copySuggestedCommand() {
        val command = tokenTrackerStartCommand()
        copyText(command)
        detail.text = "已复制 TokenTracker 启动命令（默认关闭匿名遥测）。请在终端审阅并运行；首次启动会配置 AI 工具 hooks。"
    }

    private fun showDashboard() {
        if (browser == null) {
            val supported = runCatching { JBCefApp.isSupported() }.getOrDefault(false)
            if (!supported) {
                detail.text = "当前 IDE 不支持内嵌浏览器，可点击“外部打开”查看 TokenTracker。"
                cardsLayout.show(cards, EMPTY_CARD)
                return
            }
            browser = runCatching {
                JBCefBrowser().also { embedded ->
                    embedded.setOpenLinksInExternalBrowser(true)
                    embedded.loadURL(TOKEN_TRACKER_DASHBOARD_URL)
                }
            }.onFailure { error ->
                detail.text = "内嵌 TokenTracker 失败：${safeEmbeddedBrowserError(error)}；可使用外部打开。"
            }.getOrNull()
            browser?.let { embedded ->
                browserHost.removeAll()
                browserHost.add(embedded.component, BorderLayout.CENTER)
                browserHost.revalidate()
                browserHost.repaint()
            }
        } else {
            browser?.loadURL(TOKEN_TRACKER_DASHBOARD_URL)
        }
        cardsLayout.show(cards, if (browser != null) BROWSER_CARD else EMPTY_CARD)
    }

    private fun instructionCard(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(18, 14)
        add(JBLabel("TokenTracker 尚未连接").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }, BorderLayout.NORTH)
        add(JBTextArea(
            "用量、费用统计和趋势分析由 TokenTracker 提供。\n\n" +
                "在终端运行复制的 npx 命令，TokenTracker 会在本机 127.0.0.1:7680 启动仪表盘；" +
                "完成后点击刷新，仪表盘会直接嵌入此处。OmniCode 不读取 TokenTracker 数据库，也不会上传 API Key。",
        ).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(10, 0)
            foreground = UIManager.getColor("Label.disabledForeground")
        }, BorderLayout.CENTER)
    }

    private fun loadingCard(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(18, 14)
        add(JBLabel("正在连接 TokenTracker…"), BorderLayout.NORTH)
    }

    private fun safeEmbeddedBrowserError(error: Throwable): String =
        error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(180)
            ?.ifBlank { null }
            ?: error::class.java.simpleName

    private companion object {
        const val EMPTY_CARD = "empty"
        const val BROWSER_CARD = "browser"
        const val LOADING_CARD = "loading"
        const val MAX_STATUS_CHARS = 360
    }
}
