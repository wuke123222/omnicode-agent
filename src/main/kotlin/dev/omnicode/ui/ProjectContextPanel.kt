package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.LargeRepositoryContextService
import dev.omnicode.service.ProjectContextPathPolicy
import dev.omnicode.service.ProjectRuleIssueReason
import dev.omnicode.service.ProjectRulesResult
import dev.omnicode.service.ProjectRulesService
import dev.omnicode.service.ProjectHarnessReport
import dev.omnicode.service.ProjectHarnessService
import dev.omnicode.service.HarnessFeedbackLoop
import dev.omnicode.service.PinnedProjectContext
import dev.omnicode.service.RepositoryContextHit
import dev.omnicode.service.RepositorySearchResult
import dev.omnicode.service.ProjectIntelligenceDossierExporter
import dev.omnicode.service.ProjectIntelligenceDossierInput
import dev.omnicode.service.toJsonArrayText
import dev.omnicode.settings.ProjectContextSettingsService
import dev.omnicode.commercial.OmniCodeEntitlementService
import dev.omnicode.commercial.OmniCodePaidFeature
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class ProjectContextPanel(
    private val project: Project,
    private val sendToChat: (String) -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    private val settings = ProjectContextSettingsService.getInstance(project)
    private val rules = ProjectRulesService.getInstance(project)
    private val context = LargeRepositoryContextService.getInstance(project)
    private val harness = ProjectHarnessService.getInstance(project)
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
    private var advancedHarnessExpanded = false
    private var latestRules: ProjectRulesResult? = null
    private var latestPinned: PinnedProjectContext? = null
    private var latestHarnessReport: ProjectHarnessReport? = null

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
        status.text = "正在检查项目文件、规则与验证方式…"
        status.toolTipText = boundedTooltipHtml(status.text)
        status.foreground = OmniCodeUiPalette.secondary
        scope.launch(Dispatchers.IO) {
            val loadedRules = runCatching { rules.loadRules() }.getOrNull()
            val pinned = runCatching { context.pinnedContext() }.getOrNull()
            val harnessReport = runCatching { harness.inspect() }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (disposed || generation != refreshGeneration) return@invokeLater
                renderContext(snapshot, loadedRules, pinned, harnessReport)
                status.text = "检查完成 · 只读取项目元数据，未运行命令"
                status.toolTipText = boundedTooltipHtml(status.text)
                status.foreground = OmniCodeUiPalette.secondary
            }
        }
    }

    private fun renderContext(
        snapshot: dev.omnicode.settings.ProjectContextSettings,
        loadedRules: ProjectRulesResult?,
        pinned: PinnedProjectContext?,
        harnessReport: ProjectHarnessReport?,
    ) {
        latestRules = loadedRules
        latestPinned = pinned
        latestHarnessReport = harnessReport
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        content.add(sectionTitle("开始使用"))
        if (harnessReport == null) {
            content.add(stackedCard(
                title = "暂时无法检查项目准备情况",
                detail = "你仍可聊天和阅读代码。OmniCode 没有执行任何项目命令。",
                footer = "下一步：点击右上角“刷新”；若仍失败，再运行连接诊断。",
            ))
        } else {
            val guidance = harnessReport.userGuidance()
            content.add(stackedCard(
                title = guidance.title,
                detail = guidance.summary,
                footer = "推荐下一步：${guidance.nextAction}",
            ))
            content.add(harnessActions(harnessReport))
            content.add(harnessAdvancedDetails(harnessReport))
        }

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
        add(JBLabel("项目准备与上下文").apply { font = JBFont.h2().asBold() }, BorderLayout.WEST)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(5), 0).apply {
            isOpaque = false
            add(JButton("导出档案").apply {
                toolTipText = "导出有界、脱敏的项目智能档案。"
                addActionListener { exportProjectDossier() }
            })
            add(JButton("刷新").apply { addActionListener { refresh() } })
        }, BorderLayout.EAST)
        add(status.apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            border = JBUI.Borders.emptyTop(5)
        }, BorderLayout.SOUTH)
    }

    private fun harnessActions(report: ProjectHarnessReport): JComponent = WrappingActionPanel(
        FlowLayout.LEFT,
        JBUI.scale(4),
        JBUI.scale(5),
    ).apply {
        isOpaque = false
        add(JButton("让 Agent 验证项目").apply {
            isEnabled = report.feedbackLoops.isNotEmpty() && report.safeForModel
            toolTipText = if (isEnabled) {
                "把已识别的验证方式交给 Agent；实际命令仍需通过当前审批与沙箱。"
            } else {
                "尚未识别到可用验证方式。"
            }
            addActionListener {
                sendToChat(
                    "请先调用 inspect_project_harness，列出准备采用的反馈回路及精确 argv；" +
                        "再在当前模式、审批、沙箱、预算、checkpoint 和审计边界内执行适合本次任务的验证。" +
                        "拒绝、失败或超时后停止后续副作用，并汇总真实证据与剩余风险。",
                )
            }
        })
        add(JButton(if (report.feedbackLoops.isEmpty()) "复制配置起点" else "复制可选配置示例").apply {
            toolTipText = "只复制安全 JSON 示例，不创建文件，也不运行命令。"
            addActionListener { copyHarnessTemplate(report) }
        })
    }

    private fun exportProjectDossier() {
        val access = OmniCodeEntitlementService.getInstance()
            .access(OmniCodePaidFeature.PROJECT_INTELLIGENCE_DOSSIER)
        if (!access.allowed) {
            Messages.showInfoMessage(
                this,
                "${access.message}\n\n项目规则、上下文搜索、Harness 和所有基础 Agent 能力仍然免费。",
                "OmniCode Pro",
            )
            return
        }
        val report = ProjectIntelligenceDossierExporter.markdown(
            ProjectIntelligenceDossierInput(
                projectName = project.name,
                rules = latestRules,
                pinned = latestPinned,
                harness = latestHarnessReport,
            ),
        )
        val chooser = JFileChooser().apply {
            dialogTitle = "导出项目智能档案"
            selectedFile = java.io.File("omnicode-project-dossier.md")
            fileFilter = FileNameExtensionFilter("Markdown dossier (*.md)", "md")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile.toPath()
        scope.launch(Dispatchers.IO) {
            runCatching { Files.writeString(target, report) }
                .onSuccess {
                    ApplicationManager.getApplication().invokeLater {
                        status.text = "项目智能档案已导出：${target.fileName}"
                        status.foreground = OmniCodeUiPalette.success
                    }
                }
                .onFailure { error ->
                    ApplicationManager.getApplication().invokeLater {
                        status.text = "项目智能档案写入失败：${error.message.orEmpty()}"
                        status.foreground = OmniCodeUiPalette.error
                    }
                }
        }
    }

    private fun harnessAdvancedDetails(report: ProjectHarnessReport): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(5)
        val details = ViewportWidthPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            isVisible = advancedHarnessExpanded

            add(sectionTitle("高级状态"))
            add(infoCard(buildString {
                append("成熟度启发式 ").append(report.readiness).append(" · ")
                    .append(report.score).append("/100")
                append(" · 元数据 ").append(if (report.safeForModel) "可安全提供给模型" else "已失败关闭")
                append(" · 配置 ").append(report.configurationStatus)
                if (report.truncated) append(" · 发现结果已按上限截断")
            }))
            add(advancedHarnessActions())

            add(sectionTitle("运行时安全边界"))
            report.runtimeControls.forEach { control ->
                add(stackedCard(control.label, control.summary))
                add(Box.createVerticalStrut(JBUI.scale(5)))
            }

            add(sectionTitle("验证方式 · 仅发现，尚未运行"))
            if (report.feedbackLoops.isEmpty()) {
                add(infoCard("尚未发现测试或检查命令；配置不是使用聊天和代码阅读的前提。"))
            } else {
                report.feedbackLoops.forEach { loop ->
                    add(feedbackLoopCard(loop))
                    add(Box.createVerticalStrut(JBUI.scale(5)))
                }
            }

            add(sectionTitle("知识地图"))
            if (report.evidence.isEmpty()) {
                add(infoCard("没有可展示的项目元数据。"))
            } else {
                report.evidence.forEach { item ->
                    add(rowCard(
                        title = item.path,
                        detail = "${item.kind} · ${if (item.configured) "显式配置" else "自动发现"}",
                        titleTooltip = item.label,
                    ))
                    add(Box.createVerticalStrut(JBUI.scale(5)))
                }
            }

            add(sectionTitle("缺口与建议"))
            if (report.issues.isEmpty()) {
                add(infoCard("本地启发式检查未发现缺口；仍应以真实测试与 CI 结果为准。"))
            } else {
                report.issues.forEach { issue ->
                    add(stackedCard(
                        title = "${issue.severity} · ${issue.summary}",
                        detail = issue.recoverySuggestion,
                    ))
                    add(Box.createVerticalStrut(JBUI.scale(5)))
                }
            }
        }
        val toggle = JButton(if (advancedHarnessExpanded) "收起 Harness 高级详情" else "查看 Harness 高级详情").apply {
            toolTipText = "查看成熟度分数、精确 argv、知识地图和运行时安全边界。"
            addActionListener {
                advancedHarnessExpanded = !advancedHarnessExpanded
                details.isVisible = advancedHarnessExpanded
                text = if (advancedHarnessExpanded) "收起 Harness 高级详情" else "查看 Harness 高级详情"
                this@ProjectContextPanel.revalidate()
                this@ProjectContextPanel.repaint()
            }
        }
        add(toggle, BorderLayout.NORTH)
        add(details, BorderLayout.CENTER)
    }

    private fun advancedHarnessActions(): JComponent = WrappingActionPanel(
        FlowLayout.LEFT,
        JBUI.scale(4),
        JBUI.scale(5),
    ).apply {
        isOpaque = false
        add(JButton("打开 .omnicode/harness.json").apply { addActionListener { openHarnessConfig() } })
        add(JButton("让 Agent 帮我完善配置").apply {
            addActionListener {
                sendToChat(
                    "请调用 inspect_project_harness，并根据当前项目生成或完善 .omnicode/harness.json version 1。" +
                        "只允许 knowledge 路径、feedbackLoops 的 id/label/argv 和 guardrails 的 label/path；" +
                        "不要写 shell 字符串、密钥、环境变量、免审批或沙箱降级字段。先展示草案，待我批准后再修改。",
                )
            }
        })
    }

    private fun copyHarnessTemplate(report: ProjectHarnessReport) {
        CopyPasteManager.getInstance().setContents(StringSelection(report.safeConfigurationTemplate()))
        status.text = "安全配置示例已复制 · 未创建文件，未运行命令"
        status.foreground = OmniCodeUiPalette.success
        status.toolTipText = boundedTooltipHtml(status.text)
    }

    private fun openHarnessConfig() {
        val root = runCatching { ProjectContextPathPolicy.projectRoot(project) }.getOrNull()
        val path = root?.let {
            runCatching { ProjectContextPathPolicy.resolve(it, ".omnicode/harness.json") }.getOrNull()
        }
        val file = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (file == null || file.isDirectory) {
            status.text = "尚无 .omnicode/harness.json；可先生成配置草案。"
            status.foreground = OmniCodeUiPalette.warning
            status.toolTipText = boundedTooltipHtml(status.text)
            return
        }
        OpenFileDescriptor(project, file).navigate(true)
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

    private fun feedbackLoopCard(loop: HarnessFeedbackLoop): JComponent = stackedCard(
        title = loop.label,
        detail = loop.argv.toJsonArrayText(),
        footer = "${loop.id} · 来源 ${loop.sourcePath} · argv 仅展示，尚未运行",
    )

    private fun stackedCard(title: String, detail: String, footer: String = ""): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = OmniCodeUiPalette.border,
        radius = 8,
    ).apply {
        layout = BorderLayout(JBUI.scale(4), JBUI.scale(4))
        border = JBUI.Borders.empty(8)
        add(JBLabel(title).apply {
            putClientProperty("html.disable", true)
            font = JBFont.label().asBold()
            toolTipText = boundedTooltipHtml(title)
        }, BorderLayout.NORTH)
        add(JBTextArea(detail).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            border = JBUI.Borders.empty()
            toolTipText = boundedTooltipHtml(detail)
        }, BorderLayout.CENTER)
        if (footer.isNotBlank()) {
            add(JBLabel(footer).apply {
                putClientProperty("html.disable", true)
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                toolTipText = boundedTooltipHtml(footer)
            }, BorderLayout.SOUTH)
        }
    }

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
        add(JBLabel(title).apply {
            putClientProperty("html.disable", true)
            toolTipText = boundedTooltipHtml(titleTooltip)
        }, BorderLayout.CENTER)
        if (detail.isNotBlank()) add(JBLabel(detail).apply {
            putClientProperty("html.disable", true)
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            toolTipText = boundedTooltipHtml(detailTooltip)
        }, BorderLayout.EAST)
    }

    private fun pathRow(path: String, detail: String, actionLabel: String, action: () -> Unit): JComponent =
        RoundedSurfacePanel(OmniCodeUiPalette.surface, OmniCodeUiPalette.border, 8).apply {
            layout = BorderLayout(JBUI.scale(6), 0)
            border = JBUI.Borders.empty(7)
            add(JBLabel(path).apply {
                putClientProperty("html.disable", true)
                toolTipText = boundedTooltipHtml(path)
            }, BorderLayout.CENTER)
            add(JBLabel(detail).apply {
                putClientProperty("html.disable", true)
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
