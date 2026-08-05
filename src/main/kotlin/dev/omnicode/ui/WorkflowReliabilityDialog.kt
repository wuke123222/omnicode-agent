package dev.omnicode.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.WorkflowReliabilitySnapshot
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/** A compact, readable reliability view kept outside the task list's orchestration code. */
internal class WorkflowReliabilityDialog(
    project: Project,
    private val snapshot: WorkflowReliabilitySnapshot,
) : DialogWrapper(project) {
    init {
        title = "任务可靠性中心"
        setResizable(true)
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = JBUI.size(680, 520)
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        add(summaryCard(), BorderLayout.NORTH)
        add(JBScrollPane(stageContent()).apply {
            border = JBUI.Borders.emptyTop(8)
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }, BorderLayout.CENTER)
    }

    private fun summaryCard(): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(12)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("运行概览").apply { font = JBFont.h2().asBold() })
            add(JBLabel("workflow ${snapshot.workflowId.take(48)}").apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
            })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(WrappingActionPanel(FlowLayout.LEFT, JBUI.scale(14), JBUI.scale(4)).apply {
                isOpaque = false
                add(metric("总耗时", formatDuration(snapshot.totalDurationMillis)))
                add(metric("模型请求", snapshot.modelRequestCount.toString()))
                add(metric("工具失败", snapshot.toolFailureCount.toString()))
                add(metric("供应商重试", snapshot.retryCount.toString()))
                add(metric("恢复点", snapshot.recoveryPointCount.toString()))
            })
        }, BorderLayout.CENTER)
    }

    private fun metric(title: String, value: String): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JBLabel(value).apply {
            font = JBFont.label().asBold()
            foreground = OmniCodeUiPalette.primary
        })
        add(JBLabel(title).apply {
            font = JBFont.small()
            foreground = OmniCodeUiPalette.secondary
        })
    }

    private fun stageContent(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(sectionTitle("阶段与恢复"))
        if (snapshot.stages.isEmpty()) {
            add(infoCard("尚未记录阶段完成事件。任务可能在启动阶段失败，或旧版本没有可靠性 ledger。"))
        } else {
            snapshot.stages.forEach { stage ->
                add(infoCard(
                    "${stage.stage} · ${formatDuration(stage.durationMillis)} · " +
                        when (stage.success) { true -> "完成"; false -> "失败"; null -> "进行中" },
                    stage.detail,
                ))
                add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }
        if (snapshot.retryReasons.isNotEmpty()) {
            add(sectionTitle("重试原因"))
            add(infoCard(snapshot.retryReasons.joinToString("\n") { "• $it" }))
        }
        add(sectionTitle("事件轨迹（最近 ${snapshot.events.size} 条）"))
        val events = snapshot.events.asReversed()
        add(infoCard(events.joinToString("\n") { event ->
            val stage = event.stage?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            "${event.recordedAt} · ${event.type.name.lowercase()}$stage · ${event.message.take(180)}"
        }.ifBlank { "没有可显示的事件。" }))
        add(Box.createVerticalStrut(JBUI.scale(8)))
    }

    private fun sectionTitle(text: String): JComponent = JBLabel(text).apply {
        font = JBFont.label().asBold()
        foreground = OmniCodeUiPalette.secondary
        border = JBUI.Borders.empty(8, 2, 5, 2)
    }

    private fun infoCard(title: String, detail: String = ""): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 8,
    ).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(9)
        add(JBLabel("<html>${escapeHtml(title).replace("\n", "<br>")}</html>").apply {
            font = JBFont.label().asBold()
            foreground = OmniCodeUiPalette.primary
        })
        if (detail.isNotBlank()) add(JBLabel("<html>${escapeHtml(detail)}</html>").apply {
            font = JBFont.small()
            foreground = OmniCodeUiPalette.secondary
            border = JBUI.Borders.emptyTop(4)
        })
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")

    private fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis.coerceAtLeast(0)} ms"
        millis < 60_000 -> "${"%.1f".format(millis / 1_000.0)} s"
        else -> "${millis / 60_000}m ${millis / 1_000 % 60}s"
    }
}
