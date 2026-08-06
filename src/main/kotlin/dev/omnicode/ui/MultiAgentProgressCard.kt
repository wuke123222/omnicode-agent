package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.text.NumberFormat
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

internal enum class DelegateProgressStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
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
    val backend: String = "",
    val nativeThreadId: String? = null,
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
    private val backendLabel = JBLabel().apply {
        foreground = OmniCodeUiPalette.timelineMuted
        font = JBFont.small()
        isVisible = false
    }
    private val rowById = linkedMapOf<String, DelegateProgressRow>()
    private val snapshotById = linkedMapOf<String, DelegateProgressSnapshot>()
    /** Full event text retained only for the active UI card; the compact row remains bounded. */
    private val fullObjectiveById = linkedMapOf<String, String>()
    private val fullSummaryById = linkedMapOf<String, String>()

    val delegateCount: Int get() = snapshotById.size

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(9, 12)
        minimumSize = Dimension(0, JBUI.scale(52))
        accessibleContext?.accessibleName = "Team 协作进度"

        add(StretchPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel("Codex 原生子代理", AllIcons.Actions.Lightning, SwingConstants.LEADING).apply {
                foreground = OmniCodeUiPalette.primary
                font = JBFont.label().asBold()
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(backendLabel)
                add(countLabel)
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(rows.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.CENTER)
    }

    fun startDelegate(
        agentId: String,
        displayName: String,
        objective: String,
        role: String = "",
        backend: String = "",
        nativeThreadId: String? = null,
    ): Boolean {
        val id = agentId.trim()
        if (id.isEmpty()) return false
        if (id in snapshotById) {
            if (nativeThreadId != null) {
                val current = snapshotById[id] ?: return false
                val linked = current.copy(
                    backend = backend.trim().ifBlank { current.backend },
                    nativeThreadId = nativeThreadId,
                )
                snapshotById[id] = linked
                rowById[id]?.update(
                    linked,
                    fullObjective = fullObjectiveById[id].orEmpty().ifBlank { linked.objective },
                    fullSummary = fullSummaryById[id].orEmpty().ifBlank { linked.summary },
                )
                updateSummary()
                refreshLayout()
            }
            return false
        }
        val snapshot = DelegateProgressSnapshot(
            agentId = id,
            displayName = displayName.trim().ifBlank { "协作者" },
            objective = boundedDelegatePreview(objective.trim(), MAX_DELEGATE_OBJECTIVE_CHARS),
            role = role.trim(),
            status = DelegateProgressStatus.RUNNING,
            backend = backend.trim(),
            nativeThreadId = nativeThreadId,
        )
        fullObjectiveById[id] = boundedDelegatePreview(objective.trim(), MAX_DETAIL_OBJECTIVE_CHARS)
        fullSummaryById.putIfAbsent(id, "")
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
        detail: String = summary,
        fallbackDisplayName: String = "协作者",
        fallbackObjective: String = "",
        fallbackRole: String = "",
        backend: String = "",
        nativeThreadId: String? = null,
    ): Boolean {
        val id = agentId.trim()
        if (id.isEmpty()) return false
        val added = id !in snapshotById && startDelegate(
            id,
            fallbackDisplayName,
            fallbackObjective,
            fallbackRole,
            backend,
            nativeThreadId,
        )
        val current = snapshotById[id] ?: return false
        val completed = current.copy(
            status = status,
            summary = boundedDelegatePreview(summary.trim(), MAX_DELEGATE_SUMMARY_CHARS),
            tokens = tokens.coerceAtLeast(0),
            backend = backend.trim().ifBlank { current.backend },
            nativeThreadId = nativeThreadId ?: current.nativeThreadId,
        )
        fullSummaryById[id] = boundedDelegatePreview(detail.trim().ifBlank { summary.trim() }, MAX_DETAIL_SUMMARY_CHARS)
        snapshotById[id] = completed
        rowById[id]?.update(
            completed,
            fullObjective = fullObjectiveById[id].orEmpty().ifBlank { completed.objective },
            fullSummary = fullSummaryById[id].orEmpty().ifBlank { completed.summary },
        )
        updateSummary()
        refreshLayout()
        return added
    }

    fun updateDelegate(
        agentId: String,
        detail: String,
        backend: String = "",
        nativeThreadId: String? = null,
    ): Boolean {
        val id = agentId.trim()
        val current = snapshotById[id] ?: return false
        val boundedDetail = boundedDelegatePreview(detail.trim(), MAX_DELEGATE_SUMMARY_CHARS)
        val updated = current.copy(
            summary = boundedDetail.ifBlank { current.summary },
            backend = backend.trim().ifBlank { current.backend },
            nativeThreadId = nativeThreadId ?: current.nativeThreadId,
        )
        snapshotById[id] = updated
        if (boundedDetail.isNotBlank()) fullSummaryById[id] = boundedDelegatePreview(detail.trim(), MAX_DETAIL_SUMMARY_CHARS)
        rowById[id]?.update(
            updated,
            fullObjective = fullObjectiveById[id].orEmpty().ifBlank { updated.objective },
            fullSummary = fullSummaryById[id].orEmpty().ifBlank { updated.summary },
        )
        updateSummary()
        refreshLayout()
        return true
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
        val partial = snapshotById.values.count { it.status == DelegateProgressStatus.PARTIAL }
        val running = snapshotById.values.count { it.status == DelegateProgressStatus.RUNNING }
        val failed = snapshotById.size - completed - partial - running
        countLabel.text = buildList {
            if (running > 0) add("$running 运行")
            if (completed > 0) add("$completed 完成")
            if (partial > 0) add("$partial 阶段结果")
            if (failed > 0) add("$failed 异常")
        }.joinToString(" · ").ifBlank { "等待委派" }
        countLabel.toolTipText = "共 ${snapshotById.size} 个协作者"
        val backends = snapshotById.values.map { it.backend }.filter(String::isNotBlank).distinct()
        backendLabel.text = backends.joinToString(" · ")
        backendLabel.toolTipText = backendLabel.text
        backendLabel.isVisible = backends.isNotEmpty()
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
    private var latestValue: DelegateProgressSnapshot = initial
    private var latestFullObjective: String = initial.objective
    private var latestFullSummary: String = initial.summary
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
    private val detailsButton = flatButton("查看处理内容", "展开该协作者的完整、有界处理摘要").apply {
        isVisible = false
        addActionListener { showDetails(latestValue) }
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
                        add(detailsButton)
                        add(status)
                        add(usage)
                    }, BorderLayout.EAST)
            })
            add(objective)
            add(summary)
        }, BorderLayout.CENTER)
        update(initial)
    }

    fun update(
        value: DelegateProgressSnapshot,
        fullObjective: String = value.objective,
        fullSummary: String = value.summary,
    ) {
        latestValue = value
        latestFullObjective = fullObjective
        latestFullSummary = fullSummary
        title.text = listOf(value.displayName, value.role).filter(String::isNotBlank).distinct().joinToString(" · ")
        title.toolTipText = title.text
        objective.text = value.objective.ifBlank { "正在处理分配的任务" }
        objective.toolTipText = value.objective.takeIf(String::isNotBlank)
        summary.text = value.summary
        summary.toolTipText = value.summary.takeIf(String::isNotBlank)
        summary.isVisible = value.summary.isNotBlank()
        detailsButton.isVisible = value.objective.isNotBlank() || value.summary.isNotBlank()
        usage.text = value.tokens.takeIf { it > 0 }?.let {
            "${NumberFormat.getIntegerInstance().format(it)} tokens"
        }.orEmpty()
        stateIcon.icon = when (value.status) {
            DelegateProgressStatus.RUNNING -> AnimatedIcon.Default()
            DelegateProgressStatus.COMPLETED -> AllIcons.General.GreenCheckmark
            DelegateProgressStatus.PARTIAL -> AllIcons.General.Warning
            DelegateProgressStatus.FAILED -> AllIcons.General.Error
            DelegateProgressStatus.CANCELLED -> AllIcons.Actions.Cancel
        }
        status.text = when (value.status) {
            DelegateProgressStatus.RUNNING -> "运行中"
            DelegateProgressStatus.COMPLETED -> "完成"
            DelegateProgressStatus.PARTIAL -> "阶段结果"
            DelegateProgressStatus.FAILED -> "失败"
            DelegateProgressStatus.CANCELLED -> "已取消"
        }
        status.foreground = when (value.status) {
            DelegateProgressStatus.RUNNING -> OmniCodeUiPalette.accent
            DelegateProgressStatus.COMPLETED -> OmniCodeUiPalette.success
            DelegateProgressStatus.PARTIAL -> OmniCodeUiPalette.warning
            DelegateProgressStatus.FAILED -> OmniCodeUiPalette.error
            DelegateProgressStatus.CANCELLED -> OmniCodeUiPalette.timelineMuted
        }
        accessibleContext?.accessibleName = "${value.displayName}，${status.text}"
        accessibleContext?.accessibleDescription = listOf(value.objective, value.summary)
            .filter(String::isNotBlank)
            .joinToString("。")
    }

    private fun showDetails(value: DelegateProgressSnapshot) {
        val detailText = buildString {
            appendLine("协作者：${value.displayName}")
            value.role.takeIf(String::isNotBlank)?.let { appendLine("角色：$it") }
            value.backend.takeIf(String::isNotBlank)?.let { appendLine("后端：$it") }
            value.nativeThreadId?.let { appendLine("原生线程：$it") }
            appendLine("状态：${status.text}")
            if (value.tokens > 0) appendLine("用量：${NumberFormat.getIntegerInstance().format(value.tokens)} tokens")
            appendLine()
            appendLine("处理目标")
            appendLine(latestFullObjective.ifBlank { value.objective }.ifBlank { "未提供目标" })
            if (latestFullSummary.isNotBlank()) {
                appendLine()
                appendLine("阶段结果 / 处理内容")
                appendLine(latestFullSummary)
            }
            appendLine()
            append("说明：内容来自该协作者的有界事件摘要；不会展示隐藏提示词、密钥或未授权上下文。")
        }.take(MAX_DETAIL_CHARS)
        val area = JBTextArea(detailText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.small()
            border = JBUI.Borders.empty(8)
            caretPosition = 0
            accessibleContext?.accessibleName = "${value.displayName} 处理内容"
        }
        val popupContent = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JBScrollPane(area).apply {
                preferredSize = Dimension(JBUI.scale(560), JBUI.scale(360))
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                add(flatButton("复制全部", "复制当前协作者的有界处理摘要").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(detailText))
                    }
                })
            }, BorderLayout.SOUTH)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, area)
            .setTitle("${value.displayName} · 处理内容")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showInFocusCenter()
    }

    private companion object {
        const val MAX_DETAIL_CHARS = 32_000
    }
}

private const val MAX_DETAIL_OBJECTIVE_CHARS = 8_000
private const val MAX_DETAIL_SUMMARY_CHARS = 16_000
