package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.ConnectionDiagnosticCheck
import dev.omnicode.service.ConnectionDiagnosticStatus
import dev.omnicode.service.ConnectionDiagnosticsExporter
import dev.omnicode.service.ConnectionDiagnosticsReport
import dev.omnicode.service.ConnectionDiagnosticsService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class ConnectionDiagnosticsPanel(
    private val diagnostics: ConnectionDiagnosticsService = ConnectionDiagnosticsService.getInstance(),
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val content = ViewportWidthPanel()
    private val status = JBLabel("尚未运行诊断。")
    private val runButton = JButton("运行一键诊断")
    private val exportButton = JButton("导出脱敏诊断包…").apply { isEnabled = false }
    @Volatile
    private var disposed = false
    @Volatile
    private var running = false
    private var lastReport: ConnectionDiagnosticsReport? = null

    init {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        border = JBUI.Borders.empty(10)
        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(18)
        }, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(8)).apply {
            isOpaque = false
            add(exportButton)
            add(runButton)
        }, BorderLayout.SOUTH)
        runButton.addActionListener { runDiagnostics() }
        exportButton.addActionListener { exportReport() }
        render(null)
    }

    override fun dispose() {
        disposed = true
        scope.cancel()
    }

    internal fun refresh() {
        if (!shouldRenderDiagnosticsRefresh(running)) return
        render(lastReport)
    }

    private fun buildHeader(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 10, 2)
        add(JBLabel("一键连接诊断").apply {
            font = JBFont.h2().asBold()
            alignmentX = LEFT_ALIGNMENT
        })
        add(JBLabel(DIAGNOSTICS_SCOPE_DESCRIPTION).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            border = JBUI.Borders.emptyTop(4)
            alignmentX = LEFT_ALIGNMENT
            toolTipText = boundedTooltipHtml(DIAGNOSTICS_SCOPE_DESCRIPTION)
        })
        add(status.apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small().asBold()
            border = JBUI.Borders.emptyTop(4)
        })
    }

    private fun runDiagnostics() {
        if (running || disposed) return
        running = true
        runButton.isEnabled = false
        exportButton.isEnabled = false
        setStatus(
            if (lastReport == null) "诊断中…" else "诊断中… · 下方保留上次报告，完成后自动更新",
            OmniCodeUiPalette.secondary,
        )
        if (lastReport == null) showTransientMessage("正在执行有界、无凭据的连通性探测…")
        scope.launch {
            val result = runCatching { diagnostics.diagnoseCurrentConfiguration() }
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                running = false
                runButton.isEnabled = true
                result.onSuccess { report ->
                    lastReport = report
                    exportButton.isEnabled = true
                    render(report)
                }.onFailure {
                    exportButton.isEnabled = lastReport != null
                    setStatus(
                        if (lastReport == null) {
                            "诊断失败；未导出原始异常"
                        } else {
                            "诊断失败；下方仍为上次成功报告"
                        },
                        OmniCodeUiPalette.error,
                    )
                    if (lastReport == null) {
                        showTransientMessage("诊断服务未完成。请检查 IDE 日志或重试。")
                    }
                }
            }
        }
    }

    private fun render(report: ConnectionDiagnosticsReport?) {
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        if (report == null) {
            setStatus("尚未运行诊断", OmniCodeUiPalette.secondary)
            content.add(emptyCard())
        } else {
            setStatus(
                "${report.overallStatus.name} · ${report.durationMillis} ms · " +
                    TIME_FORMAT.format(report.generatedAt.atZone(ZoneId.systemDefault())),
                diagnosticColor(report.overallStatus),
            )
            report.checks.groupBy(ConnectionDiagnosticCheck::category).forEach { (category, checks) ->
                content.add(JBLabel(category.name).apply {
                    font = JBFont.label().asBold()
                    foreground = OmniCodeUiPalette.secondary
                    border = JBUI.Borders.empty(6, 2, 5, 2)
                })
                checks.forEach { check ->
                    content.add(checkCard(check))
                    content.add(Box.createVerticalStrut(JBUI.scale(6)))
                }
            }
        }
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    private fun emptyCard(): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(18)
        val message = "点击“运行一键诊断”；网络检查只访问当前配置的 Provider Base URL，不附带 API Key，也不跟随重定向。"
        add(JBLabel(message).apply { toolTipText = boundedTooltipHtml(message) })
    }

    private fun showTransientMessage(message: String) {
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.add(RoundedSurfacePanel(
            fillColor = OmniCodeUiPalette.surface,
            outlineColor = OmniCodeUiPalette.border,
            radius = 10,
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(18)
            add(JBLabel(message).apply { toolTipText = boundedTooltipHtml(message) })
        })
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    private fun checkCard(check: ConnectionDiagnosticCheck): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = if (check.status == ConnectionDiagnosticStatus.FAIL) OmniCodeUiPalette.error else OmniCodeUiPalette.border,
        radius = 9,
    ).apply {
        layout = BorderLayout(JBUI.scale(8), JBUI.scale(3))
        border = JBUI.Borders.empty(9)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            val displayTitle = diagnosticsDisplayTitle(check.id, check.title)
            add(JBLabel(displayTitle).apply {
                font = JBFont.label().asBold()
                toolTipText = boundedTooltipHtml(displayTitle)
            }, BorderLayout.CENTER)
            add(JBLabel("${check.status.name} · ${check.durationMillis} ms").apply {
                foreground = diagnosticColor(check.status)
                font = JBFont.small().asBold()
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBLabel("<html>${escapeHtml(check.summary)}</html>").apply {
            foreground = OmniCodeUiPalette.primary
            toolTipText = boundedTooltipHtml(check.summary)
        }, BorderLayout.CENTER)
        check.recoverySuggestion?.let { suggestion ->
            add(JBLabel("<html>建议：${escapeHtml(suggestion)}</html>").apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                toolTipText = boundedTooltipHtml(suggestion)
            }, BorderLayout.SOUTH)
        }
    }

    private fun exportReport() {
        val report = lastReport ?: return
        val chooser = JFileChooser().apply {
            dialogTitle = "导出 OmniCode 脱敏诊断包"
            selectedFile = java.io.File(
                "omnicode-diagnostics-${FILE_TIME_FORMAT.format(report.generatedAt.atZone(ZoneId.systemDefault()))}.zip",
            )
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val destination = chooser.selectedFile.toPath().let { path ->
            if (path.fileName.toString().lowercase().endsWith(".zip")) path
            else path.resolveSibling(path.fileName.toString() + ".zip")
        }
        setStatus("正在写入脱敏诊断包…", OmniCodeUiPalette.secondary)
        exportButton.isEnabled = false
        scope.launch {
            val result = runCatching {
                ConnectionDiagnosticsPackageWriter().write(
                    ConnectionDiagnosticsExporter().export(report),
                    destination,
                )
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                exportButton.isEnabled = true
                val message = result.fold(
                    onSuccess = { "已导出：${it.fileName}" },
                    onFailure = { it.message ?: "诊断包导出失败" },
                )
                setStatus(message, if (result.isSuccess) OmniCodeUiPalette.success else OmniCodeUiPalette.error)
            }
        }
    }

    private fun setStatus(message: String, color: java.awt.Color) {
        status.text = message
        status.toolTipText = boundedTooltipHtml(message)
        status.foreground = color
    }

    private fun diagnosticColor(status: ConnectionDiagnosticStatus) = when (status) {
        ConnectionDiagnosticStatus.PASS -> OmniCodeUiPalette.success
        ConnectionDiagnosticStatus.WARN -> OmniCodeUiPalette.warning
        ConnectionDiagnosticStatus.FAIL -> OmniCodeUiPalette.error
        ConnectionDiagnosticStatus.SKIP -> OmniCodeUiPalette.secondary
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

internal const val DIAGNOSTICS_SCOPE_DESCRIPTION: String =
    "检查 Provider 凭据是否存在（不验证 API Key 有效性）、本地推测模型工具 / 视觉能力，并探测代理、DNS、TLS、MCP OAuth 与沙箱；不会发送密钥。"

internal fun diagnosticsDisplayTitle(id: String, fallback: String): String = when (id) {
    "provider.credentials" -> "Provider 凭据存在性（不验证有效性）"
    "model.tools" -> "模型工具调用能力（本地推测）"
    "model.vision" -> "主模型视觉能力（本地推测）"
    "model.vision_assistant" -> "视觉助手能力（本地推测）"
    else -> fallback
}

internal fun shouldRenderDiagnosticsRefresh(running: Boolean): Boolean = !running
