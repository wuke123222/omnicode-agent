package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.NumberFormat
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

internal enum class DelegateProgressStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

internal data class DelegateProgressSnapshot(
    val agentId: String,
    val displayName: String,
    val role: String,
    val objective: String,
    val status: DelegateProgressStatus,
    val summary: String = "",
    val tokens: Long = 0,
)

/** Keeps specialist activity grouped so it never interleaves with the lead agent's answer. */
internal class MultiAgentProgressCard : RoundedSurfacePanel(
    fillColor = OmniCodeUiPalette.timelineCard,
    outlineColor = OmniCodeUiPalette.timelineBorder,
    radius = 8,
) {
    private val rows = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val countLabel = JBLabel("等待委派").apply {
        foreground = OmniCodeUiPalette.timelineMuted
        font = JBFont.small()
    }
    private val rowById = linkedMapOf<String, DelegateProgressRow>()
    private val snapshotById = linkedMapOf<String, DelegateProgressSnapshot>()

    val delegateCount: Int get() = snapshotById.size

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(9, 12)
        minimumSize = Dimension(0, JBUI.scale(52))
        accessibleContext?.accessibleName = "Team 协作进度"

        add(StretchPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel("Team 协作", AllIcons.Actions.Lightning, SwingConstants.LEADING).apply {
                foreground = OmniCodeUiPalette.primary
                font = JBFont.label().asBold()
            }, BorderLayout.WEST)
            add(countLabel, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(rows.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.CENTER)
    }

    fun startDelegate(
        agentId: String,
        displayName: String,
        objective: String,
        role: String = "",
    ): Boolean {
        val id = agentId.trim()
        if (id.isEmpty() || id in snapshotById) return false
        val snapshot = DelegateProgressSnapshot(
            agentId = id,
            displayName = displayName.trim().ifBlank { "协作者" },
            objective = boundedDelegatePreview(objective.trim(), MAX_DELEGATE_OBJECTIVE_CHARS),
            role = role.trim(),
            status = DelegateProgressStatus.RUNNING,
        )
        val row = DelegateProgressRow(snapshot)
        if (rows.componentCount > 0) rows.add(Box.createVerticalStrut(JBUI.scale(5)))
        rows.add(row)
        rowById[id] = row
        snapshotById[id] = snapshot
        updateSummary()
        refreshLayout()
        return true
    }

    fun completeDelegate(
        agentId: String,
        status: DelegateProgressStatus,
        summary: String,
        tokens: Long,
        fallbackDisplayName: String = "协作者",
        fallbackObjective: String = "",
        fallbackRole: String = "",
    ): Boolean {
        val id = agentId.trim()
        if (id.isEmpty()) return false
        val added = id !in snapshotById && startDelegate(id, fallbackDisplayName, fallbackObjective, fallbackRole)
        val current = snapshotById[id] ?: return false
        val completed = current.copy(
            status = status,
            summary = boundedDelegatePreview(summary.trim(), MAX_DELEGATE_SUMMARY_CHARS),
            tokens = tokens.coerceAtLeast(0),
        )
        snapshotById[id] = completed
        rowById[id]?.update(completed)
        updateSummary()
        refreshLayout()
        return added
    }

    fun finishPendingDelegates(cancelled: Boolean) {
        snapshotById.values.filter { it.status == DelegateProgressStatus.RUNNING }.forEach { snapshot ->
            completeDelegate(
                agentId = snapshot.agentId,
                status = if (cancelled) DelegateProgressStatus.CANCELLED else DelegateProgressStatus.FAILED,
                summary = if (cancelled) "主任务已取消。" else "主任务结束前未返回结果。",
                tokens = snapshot.tokens,
            )
        }
    }

    fun snapshots(): List<DelegateProgressSnapshot> = snapshotById.values.toList()

    private fun updateSummary() {
        val completed = snapshotById.values.count { it.status == DelegateProgressStatus.COMPLETED }
        val running = snapshotById.values.count { it.status == DelegateProgressStatus.RUNNING }
        val failed = snapshotById.size - completed - running
        countLabel.text = buildList {
            if (running > 0) add("$running 运行")
            if (completed > 0) add("$completed 完成")
            if (failed > 0) add("$failed 异常")
        }.joinToString(" · ").ifBlank { "等待委派" }
        countLabel.toolTipText = "共 ${snapshotById.size} 个协作者"
        accessibleContext?.accessibleDescription = countLabel.toolTipText
    }

    private fun refreshLayout() {
        revalidate()
        repaint()
        parent?.revalidate()
    }

    private companion object {
        const val MAX_DELEGATE_OBJECTIVE_CHARS = 500
        const val MAX_DELEGATE_SUMMARY_CHARS = 1_200
    }
}

internal fun boundedDelegatePreview(value: String, maxChars: Int): String {
    require(maxChars > 0)
    if (value.length <= maxChars) return value
    val marker = "…（已截断）"
    return value.take((maxChars - marker.length).coerceAtLeast(0)) + marker.take(maxChars)
}

private class DelegateProgressRow(initial: DelegateProgressSnapshot) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val stateIcon = JBLabel()
    private val title = JBLabel().apply {
        foreground = OmniCodeUiPalette.primary
        font = JBFont.label().asBold()
    }
    private val objective = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        rows = 1
        border = JBUI.Borders.empty()
        foreground = OmniCodeUiPalette.timelineMuted
        font = JBFont.small()
    }
    private val status = JBLabel().apply { font = JBFont.small() }
    private val usage = JBLabel().apply {
        foreground = OmniCodeUiPalette.timelineMuted
        font = JBFont.small()
    }
    private val summary = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        rows = 1
        border = JBUI.Borders.emptyTop(3)
        foreground = OmniCodeUiPalette.secondary
        font = JBFont.small()
        isVisible = false
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(5, 2)
        add(stateIcon, BorderLayout.WEST)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(StretchPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(title, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(status)
                    add(usage)
                }, BorderLayout.EAST)
            })
            add(objective)
            add(summary)
        }, BorderLayout.CENTER)
        update(initial)
    }

    fun update(value: DelegateProgressSnapshot) {
        title.text = listOf(value.displayName, value.role).filter(String::isNotBlank).distinct().joinToString(" · ")
        title.toolTipText = title.text
        objective.text = value.objective.ifBlank { "正在处理分配的任务" }
        objective.toolTipText = value.objective.takeIf(String::isNotBlank)
        summary.text = value.summary
        summary.toolTipText = value.summary.takeIf(String::isNotBlank)
        summary.isVisible = value.summary.isNotBlank()
        usage.text = value.tokens.takeIf { it > 0 }?.let {
            "${NumberFormat.getIntegerInstance().format(it)} tokens"
        }.orEmpty()
        stateIcon.icon = when (value.status) {
            DelegateProgressStatus.RUNNING -> AnimatedIcon.Default()
            DelegateProgressStatus.COMPLETED -> AllIcons.General.GreenCheckmark
            DelegateProgressStatus.FAILED -> AllIcons.General.Error
            DelegateProgressStatus.CANCELLED -> AllIcons.Actions.Cancel
        }
        status.text = when (value.status) {
            DelegateProgressStatus.RUNNING -> "运行中"
            DelegateProgressStatus.COMPLETED -> "完成"
            DelegateProgressStatus.FAILED -> "失败"
            DelegateProgressStatus.CANCELLED -> "已取消"
        }
        status.foreground = when (value.status) {
            DelegateProgressStatus.RUNNING -> OmniCodeUiPalette.accent
            DelegateProgressStatus.COMPLETED -> OmniCodeUiPalette.success
            DelegateProgressStatus.FAILED -> OmniCodeUiPalette.error
            DelegateProgressStatus.CANCELLED -> OmniCodeUiPalette.timelineMuted
        }
        accessibleContext?.accessibleName = "${value.displayName}，${status.text}"
        accessibleContext?.accessibleDescription = listOf(value.objective, value.summary)
            .filter(String::isNotBlank)
            .joinToString("。")
    }
}
