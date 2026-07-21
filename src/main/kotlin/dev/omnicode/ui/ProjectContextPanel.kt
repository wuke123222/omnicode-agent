package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.LargeRepositoryContextService
import dev.omnicode.service.ProjectContextPathPolicy
import dev.omnicode.service.ProjectRuleIssueReason
import dev.omnicode.service.ProjectRulesResult
import dev.omnicode.service.ProjectRulesService
import dev.omnicode.service.PinnedProjectContext
import dev.omnicode.service.RepositoryContextHit
import dev.omnicode.service.RepositorySearchResult
import dev.omnicode.settings.ProjectContextSettingsService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class ProjectContextPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val settings = ProjectContextSettingsService.getInstance(project)
    private val rules = ProjectRulesService.getInstance(project)
    private val context = LargeRepositoryContextService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val content = ViewportWidthPanel()
    private val status = JBLabel("")
    private val pathField = JBTextField().apply { emptyText.text = "项目相对路径，例如 src/main/App.kt" }
    private val searchField = JBTextField().apply { emptyText.text = "搜索符号或关键词" }
    private val searchKind = ComboBox(arrayOf("符号", "关键词"))
    @Volatile
    private var disposed = false
    private var refreshGeneration = 0
    private var searchResult: RepositorySearchResult? = null

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
        refresh()
    }

    override fun dispose() {
        disposed = true
        scope.cancel()
    }

    internal fun refresh() {
        if (disposed) return
        val snapshot = settings.snapshot()
        val generation = ++refreshGeneration
        status.text = "正在刷新规则与固定上下文…"
        status.toolTipText = boundedTooltipHtml(status.text)
        status.foreground = OmniCodeUiPalette.secondary
        scope.launch(Dispatchers.IO) {
            val loadedRules = runCatching { rules.loadRules() }.getOrNull()
            val pinned = runCatching { context.pinnedContext() }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (disposed || generation != refreshGeneration) return@invokeLater
                renderContext(snapshot, loadedRules, pinned)
                status.text = "上下文已刷新"
                status.toolTipText = boundedTooltipHtml(status.text)
                status.foreground = OmniCodeUiPalette.secondary
            }
        }
    }

    private fun renderContext(
        snapshot: dev.omnicode.settings.ProjectContextSettings,
        loadedRules: ProjectRulesResult?,
        pinned: PinnedProjectContext?,
    ) {
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        content.add(sectionTitle("本轮自动上下文"))
        content.add(infoCard(buildString {
            append("规则 ").append(loadedRules?.appliedRules?.size ?: 0)
            append(" · 固定文件 ").append(snapshot.pinnedPaths.size)
            append(" · 排除路径 ").append(snapshot.excludedPaths.size)
            pinned?.occupancy?.let { occupancy ->
                append(" · ").append(occupancy.usedCharacters).append('/').append(occupancy.characterBudget)
                    .append(" 字符（").append(occupancy.percentUsed).append("%，≈")
                    .append(occupancy.estimatedTokens).append(" tokens）")
            }
        }))

        content.add(sectionTitle("项目规则"))
        if (loadedRules?.appliedRules.isNullOrEmpty()) {
            content.add(infoCard("未发现 AGENTS.md、CLAUDE.md 或 .omnicode/rules/*.md。"))
        } else {
            loadedRules!!.appliedRules.forEach { rule ->
                content.add(rowCard(
                    title = rule.relativePath,
                    detail = "${rule.includedCharacters} 字符${if (rule.truncated) " · 已截断" else ""}",
                ))
                content.add(Box.createVerticalStrut(JBUI.scale(5)))
            }
        }
        loadedRules?.let { result ->
            val stats = result.truncation
            content.add(JBLabel(
                "发现 ${stats.discoveredFiles} · 应用 ${stats.appliedFiles} · 忽略 ${stats.ignoredFiles} · " +
                    "拒绝 ${stats.rejectedFiles} · 截断 ${stats.truncatedFiles}",
            ).apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                border = JBUI.Borders.empty(5, 2)
                toolTipText = "忽略规则来自 .gitignore、.aiignore 与 .omnicodeignore（按顺序合并）"
            })
            if (result.issues.isNotEmpty()) {
                content.add(sectionTitle("忽略与加载提示"))
                result.issues.take(MAX_VISIBLE_RULE_ISSUES).forEach { issue ->
                    content.add(rowCard(issue.relativePath, ruleIssueLabel(issue.reason)))
                    content.add(Box.createVerticalStrut(JBUI.scale(4)))
                }
                if (result.issues.size > MAX_VISIBLE_RULE_ISSUES) {
                    content.add(infoCard("另有 ${result.issues.size - MAX_VISIBLE_RULE_ISSUES} 条提示未展开。"))
                }
            }
        }

        content.add(sectionTitle("固定与排除"))
        content.add(pathActions())
        snapshot.pinnedPaths.forEach { path ->
            content.add(pathRow(path, "已固定", "取消固定") { settings.unpin(path); refresh() })
        }
        snapshot.excludedPaths.forEach { path ->
            content.add(pathRow(path, "已排除自动上下文", "重新包含") { settings.include(path); refresh() })
        }

        content.add(sectionTitle("IntelliJ PSI / 符号索引搜索"))
        content.add(searchActions())
        searchResult?.let { result ->
            content.add(JBLabel(buildString {
                append(result.mode.name).append(" · ").append(result.hits.size).append(" 条")
                if (result.degraded) append(" · 索引中，已降级到固定文件")
                if (result.truncated) append(" · 已截断")
            }).apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                border = JBUI.Borders.empty(5, 2)
            })
            result.hits.forEach { hit ->
                content.add(searchHit(hit))
                content.add(Box.createVerticalStrut(JBUI.scale(4)))
            }
        }
        status.toolTipText = boundedTooltipHtml(status.text)
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    private fun buildHeader(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 10, 2)
        add(JBLabel("项目规则与大仓库上下文").apply { font = JBFont.h2().asBold() }, BorderLayout.WEST)
        add(JButton("刷新").apply { addActionListener { refresh() } }, BorderLayout.EAST)
        add(status.apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            border = JBUI.Borders.emptyTop(5)
        }, BorderLayout.SOUTH)
    }

    private fun pathActions(): JComponent = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(6)
        add(pathField, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4)).apply {
            add(JButton("固定当前文件").apply { addActionListener { pinCurrentEditorFile() } })
            add(JButton("固定").apply { addActionListener { updatePath(pin = true) } })
            add(JButton("排除").apply { addActionListener { updatePath(pin = false) } })
        }, BorderLayout.SOUTH)
    }

    private fun searchActions(): JComponent = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
        isOpaque = false
        add(searchField, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4)).apply {
            add(searchKind)
            add(JButton("搜索").apply { addActionListener { runSearch() } })
        }, BorderLayout.SOUTH)
    }

    private fun updatePath(pin: Boolean) {
        val path = pathField.text.trim()
        val result = runCatching { if (pin) settings.pin(path) else settings.exclude(path) }
        status.text = result.fold(
            onSuccess = { if (pin) "已固定 $path" else "已从自动上下文排除 $path" },
            onFailure = { it.message ?: "路径无效" },
        )
        status.toolTipText = boundedTooltipHtml(status.text)
        status.foreground = if (result.isSuccess) OmniCodeUiPalette.success else OmniCodeUiPalette.error
        if (result.isSuccess) pathField.text = ""
        refresh()
    }

    private fun pinCurrentEditorFile() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val root = runCatching { ProjectContextPathPolicy.projectRoot(project) }.getOrNull()
        val relative = if (file != null && root != null) {
            ProjectContextPathPolicy.relativeOrNull(root, file.path)
        } else null
        if (relative == null) {
            status.text = "当前编辑器没有工作区内文件。"
            status.toolTipText = boundedTooltipHtml(status.text)
            status.foreground = OmniCodeUiPalette.error
            return
        }
        pathField.text = relative
        updatePath(pin = true)
    }

    private fun runSearch() {
        val query = searchField.text.trim()
        if (query.isBlank()) return
        val symbolSearch = searchKind.selectedIndex == 0
        status.text = "正在查询 IntelliJ 索引…"
        status.toolTipText = boundedTooltipHtml(status.text)
        scope.launch {
            val result = runCatching {
                if (symbolSearch) context.searchSymbols(query, 50)
                else context.searchKeywords(query, 50)
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                result.onSuccess {
                    searchResult = it
                    status.text = "索引搜索完成。"
                    status.foreground = OmniCodeUiPalette.secondary
                }.onFailure {
                    status.text = it.message ?: "索引搜索失败"
                    status.foreground = OmniCodeUiPalette.error
                }
                status.toolTipText = boundedTooltipHtml(status.text)
                refresh()
            }
        }
    }

    private fun sectionTitle(value: String): JComponent = JBLabel(value).apply {
        font = JBFont.label().asBold()
        foreground = OmniCodeUiPalette.primary
        border = JBUI.Borders.empty(9, 2, 5, 2)
        alignmentX = LEFT_ALIGNMENT
    }

    private fun infoCard(value: String): JComponent = rowCard(value, "")

    private fun rowCard(
        title: String,
        detail: String,
        titleTooltip: String = title,
        detailTooltip: String = detail,
    ): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 8,
    ).apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(8)
        add(JBLabel(title).apply { toolTipText = boundedTooltipHtml(titleTooltip) }, BorderLayout.CENTER)
        if (detail.isNotBlank()) add(JBLabel(detail).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            toolTipText = boundedTooltipHtml(detailTooltip)
        }, BorderLayout.EAST)
    }

    private fun pathRow(path: String, detail: String, actionLabel: String, action: () -> Unit): JComponent =
        RoundedSurfacePanel(OmniCodeUiPalette.surface, OmniCodeUiPalette.border, 8).apply {
            layout = BorderLayout(JBUI.scale(6), 0)
            border = JBUI.Borders.empty(7)
            add(JBLabel(path).apply { toolTipText = boundedTooltipHtml(path) }, BorderLayout.CENTER)
            add(JBLabel(detail).apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                toolTipText = boundedTooltipHtml(detail)
            }, BorderLayout.WEST)
            add(JButton(actionLabel).apply { addActionListener { action() } }, BorderLayout.EAST)
        }

    private fun searchHit(hit: RepositoryContextHit): JComponent = rowCard(
        title = buildString {
            append(hit.relativePath)
            if (hit.line > 0) append(':').append(hit.line)
            hit.symbolName?.let { append(" · ").append(it) }
        },
        detail = hit.preview.replace('\n', ' ').take(180),
        titleTooltip = buildString {
            append(hit.relativePath)
            if (hit.line > 0) append(':').append(hit.line)
            hit.symbolName?.let { append(" · ").append(it) }
        },
        detailTooltip = hit.preview,
    ).apply {
        add(JButton("打开").apply { addActionListener { openSearchHit(hit) } }, BorderLayout.WEST)
    }

    private fun openSearchHit(hit: RepositoryContextHit) {
        val root = runCatching { ProjectContextPathPolicy.projectRoot(project) }.getOrNull() ?: return
        val path = runCatching { ProjectContextPathPolicy.resolve(root, hit.relativePath) }.getOrNull() ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        OpenFileDescriptor(
            project,
            file,
            (hit.line - 1).coerceAtLeast(0),
            (hit.column - 1).coerceAtLeast(0),
        ).navigate(true)
    }

    private fun ruleIssueLabel(reason: ProjectRuleIssueReason): String = when (reason) {
        ProjectRuleIssueReason.IGNORED -> "已由项目忽略文件排除"
        ProjectRuleIssueReason.UNSAFE_PATH -> "路径不安全，已拒绝"
        ProjectRuleIssueReason.INVALID_UTF8_OR_BINARY -> "非可信 UTF-8 文本，已拒绝"
        ProjectRuleIssueReason.NOT_A_REGULAR_FILE -> "不是普通文件，已拒绝"
        ProjectRuleIssueReason.READ_FAILED -> "读取失败"
        ProjectRuleIssueReason.FILE_LIMIT -> "超过规则文件数量上限"
        ProjectRuleIssueReason.COMBINED_LIMIT -> "超过规则上下文上限，已截断"
        ProjectRuleIssueReason.DISCOVERY_LIMIT -> "规则目录扫描达到上限"
    }

    private companion object {
        const val MAX_VISIBLE_RULE_ISSUES = 16
    }
}
