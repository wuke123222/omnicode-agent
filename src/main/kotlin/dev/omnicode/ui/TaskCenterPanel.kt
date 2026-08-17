package dev.omnicode.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.OmniCodeProjectService
import dev.omnicode.service.UnifiedTaskEntry
import dev.omnicode.service.UnifiedTaskStatus
import dev.omnicode.service.ReliabilityReportExporter
import dev.omnicode.service.BatchTaskRecipeExporter
import dev.omnicode.service.BatchTaskRecipeInput
import dev.omnicode.service.EngineeringDigestExporter
import dev.omnicode.service.EngineeringDigestInput
import dev.omnicode.service.GitProgressCollector
import dev.omnicode.commercial.OmniCodeEntitlementService
import dev.omnicode.commercial.OmniCodePaidFeature
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JTextField
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.runBlocking

internal interface TaskCenterActions {
    fun continueTask(task: UnifiedTaskEntry)
    fun retryTask(task: UnifiedTaskEntry)
    fun copyTask(task: UnifiedTaskEntry)
    fun showReliability(task: UnifiedTaskEntry)
    fun restoreCheckpoint(task: UnifiedTaskEntry)
    fun returnToChat()
}

internal class TaskCenterPanel(
    private val project: Project,
    private val service: OmniCodeProjectService,
    private val actions: TaskCenterActions,
) : JPanel(BorderLayout()), Disposable {
    private val content = ViewportWidthPanel()
    private val status = JBLabel("正在读取任务…")
    private val refreshTimer = Timer(TASK_CENTER_REFRESH_MILLIS) {
        if (taskCenterShouldPoll(isShowing, disposed)) refresh(showLoading = false)
    }.apply {
        isRepeats = true
        initialDelay = TASK_CENTER_REFRESH_MILLIS
    }
    private var refreshInFlight = false
    private var renderedTasks: List<UnifiedTaskEntry>? = null
    private var renderedActionsBlocked: Boolean? = null
    @Volatile
    private var disposed = false

    init {
        isOpaque = true
        background = OmniCodeUiPalette.canvas
        border = JBUI.Borders.empty(10)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 2, 10, 2)
            add(JBLabel("统一任务与历史").apply { font = JBFont.h2().asBold() }, BorderLayout.WEST)
            add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(5), 0).apply {
                isOpaque = false
                add(JButton("导入任务包").apply { addActionListener { importWorkflowPackage() } })
                add(JButton("周报").apply {
                    toolTipText = "按 Git 版本差异和任务账本生成可直接发送的工程周报。"
                    addActionListener { exportEngineeringDigest() }
                })
                add(JButton("刷新").apply { addActionListener { refresh() } })
            }, BorderLayout.EAST)
            add(status.apply {
                foreground = OmniCodeUiPalette.secondary
                font = JBFont.small()
                border = JBUI.Borders.emptyTop(4)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
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
            add(JButton("返回聊天").apply { addActionListener { actions.returnToChat() } })
        }, BorderLayout.SOUTH)
        addHierarchyListener { event ->
            val showingChanged = event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
            if (!showingChanged || disposed) return@addHierarchyListener
            if (isShowing) {
                refresh(showLoading = false)
                refreshTimer.start()
            } else {
                refreshTimer.stop()
            }
        }
        refresh()
    }

    override fun dispose() {
        disposed = true
        refreshTimer.stop()
    }

    internal fun refresh(showLoading: Boolean = true) {
        if (disposed || refreshInFlight) return
        refreshInFlight = true
        if (showLoading) {
            status.text = "正在读取任务…"
            status.toolTipText = boundedTooltipHtml(status.text)
        }
        service.listUnifiedTasks { tasks ->
            refreshInFlight = false
            if (disposed) return@listUnifiedTasks
            val actionsBlocked = service.isRunning()
            if (!showLoading && tasks == renderedTasks && actionsBlocked == renderedActionsBlocked) {
                return@listUnifiedTasks
            }
            render(tasks, actionsBlocked)
        }
    }

    private fun render(tasks: List<UnifiedTaskEntry>, actionsBlocked: Boolean) {
        renderedTasks = tasks.toList()
        renderedActionsBlocked = actionsBlocked
        content.removeAll()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        status.text = if (tasks.isEmpty()) "暂无任务。" else "${tasks.size} 个任务 · 运行、恢复、失败和完成记录统一展示"
        status.toolTipText = boundedTooltipHtml(status.text)
        if (tasks.isEmpty()) {
            content.add(JBLabel("发送一个任务后，这里会显示运行状态和恢复入口。"))
        } else {
            tasks.forEach { task ->
                content.add(taskCard(task, actionsBlocked))
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }
        content.add(Box.createVerticalGlue())
        content.revalidate()
        content.repaint()
    }

    private fun taskCard(task: UnifiedTaskEntry, actionsBlocked: Boolean): JComponent = RoundedSurfacePanel(
        fillColor = OmniCodeUiPalette.surface,
        outlineColor = if (task.status == UnifiedTaskStatus.RUNNING) OmniCodeUiPalette.accent else OmniCodeUiPalette.border,
        radius = 10,
    ).apply {
        layout = BorderLayout(JBUI.scale(8), JBUI.scale(5))
        border = JBUI.Borders.empty(10)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(task.title.take(140)).apply {
                font = JBFont.label().asBold()
                toolTipText = boundedTooltipHtml(task.title)
            }, BorderLayout.CENTER)
            add(JBLabel(taskStatusLabel(task.status)).apply {
                foreground = taskStatusColor(task.status)
                font = JBFont.small().asBold()
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        val metadata = taskMeta(task)
        add(JBLabel(metadata).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
            toolTipText = boundedTooltipHtml(
                listOfNotNull(metadata, task.lastEventMessage?.takeIf(String::isNotBlank)?.let { "最近事件：$it" })
                    .joinToString("\n"),
            )
        }, BorderLayout.CENTER)
        add(WrappingActionPanel(FlowLayout.RIGHT, JBUI.scale(5), JBUI.scale(4)).apply {
            isOpaque = false
            if (task.canContinue) add(JButton("继续").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.continueTask(task) }
            })
            if (task.canRetry) add(JButton(
                if (task.requiredImageAttachments > 0) "补图后重试" else "重试",
            ).apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.retryTask(task) }
            })
            add(JButton("复制任务").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.copyTask(task) }
            })
            if (task.workflowId != null) add(JButton("可靠性").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { actions.showReliability(task) }
            })
            if (task.workflowId != null) add(JButton("报告").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { exportReliabilityReport(task) }
            })
            if (task.workflowId != null || task.conversationId != null) add(JButton("配方").apply {
                applyTaskActionAvailability(actionsBlocked)
                toolTipText = "保存当前任务为可复用的批量任务配方。"
                addActionListener { exportBatchRecipe(task) }
            })
            if (task.workflowId != null) add(JButton("导出").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { exportWorkflowPackage(task) }
            })
            if (task.workflowId != null) add(JButton("云端").apply {
                applyTaskActionAvailability(actionsBlocked)
                addActionListener { syncWorkflowPackage(task) }
            })
            if (task.workflowId != null || task.conversationId != null) {
                add(JButton("回到检查点").apply {
                    applyTaskActionAvailability(actionsBlocked)
                    addActionListener { actions.restoreCheckpoint(task) }
                })
            }
        }, BorderLayout.SOUTH)
    }

    private fun JButton.applyTaskActionAvailability(blocked: Boolean) {
        if (!blocked) return
        isEnabled = false
        toolTipText = TASK_ACTIONS_RUNNING_TOOLTIP
        accessibleContext?.accessibleDescription = TASK_ACTIONS_RUNNING_TOOLTIP
    }

    private fun taskMeta(task: UnifiedTaskEntry): String = buildString {
        append(task.mode.name.replace('_', ' ')).append(" · ").append(task.strategy.name)
        append(" · ").append(TIME_FORMAT.format(task.updatedAt.atZone(ZoneId.systemDefault())))
        if (task.iteration > 0) append(" · 第 ").append(task.iteration).append(" 轮")
        val tokens = task.inputTokens + task.outputTokens
        if (tokens > 0) append(" · ").append(tokens).append(" tokens")
        task.currentStage?.let {
            append(" · 阶段 ").append(it)
            task.currentStageDurationMillis?.let { duration -> append("（").append(formatDuration(duration)).append("）") }
        }
        if (task.modelRequestCount > 0) append(" · 模型 ").append(task.modelRequestCount).append(" 次")
        if (task.toolFailureCount > 0) append(" · 工具失败 ").append(task.toolFailureCount)
        if (task.retryCount > 0) append(" · 重试 ").append(task.retryCount)
        task.pendingToolName?.let { append(" · 待确认 ").append(it) }
        if (task.requiredImageAttachments > 0) append(" · 需补 ").append(task.requiredImageAttachments).append(" 张图片")
    }

    private fun exportWorkflowPackage(task: UnifiedTaskEntry) {
        val workflowId = task.workflowId ?: return
        val passphrase = requestPassphrase("为任务包设置一个至少 12 个字符的密码：") ?: return
        val chooser = JFileChooser().apply {
            dialogTitle = "导出 OmniCode 任务包"
            selectedFile = java.io.File("omnicode-task-${workflowId.take(8)}.omnitask")
            fileFilter = FileNameExtensionFilter("OmniCode task package (*.omnitask)", "omnitask")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            passphrase.fill('\u0000')
            return
        }
        service.exportWorkflowPackage(workflowId, passphrase) { result ->
            result.onSuccess { bytes ->
                runCatching { Files.write(chooser.selectedFile.toPath(), bytes) }
                    .onSuccess { status.text = "任务包已导出：${chooser.selectedFile.name}" }
                    .onFailure { error -> status.text = "任务包写入失败：${error.message.orEmpty()}" }
            }.onFailure { error -> status.text = "任务包导出失败：${error.message.orEmpty()}" }
        }
    }

    private fun exportReliabilityReport(task: UnifiedTaskEntry) {
        val workflowId = task.workflowId ?: return
        service.workflowReliability(workflowId) { snapshot ->
            if (snapshot == null) {
                status.text = "没有找到该任务的可靠性记录。"
                return@workflowReliability
            }
            val chooser = JFileChooser().apply {
                dialogTitle = "导出任务可靠性报告"
                selectedFile = java.io.File("omnicode-reliability-${workflowId.take(8)}.md")
                fileFilter = FileNameExtensionFilter("Markdown report (*.md)", "md")
            }
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return@workflowReliability
            val report = ReliabilityReportExporter.markdown(snapshot)
            val target = chooser.selectedFile.toPath()
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { Files.writeString(target, report) }
                    .onSuccess {
                        SwingUtilities.invokeLater { status.text = "可靠性报告已导出：${target.fileName}" }
                    }
                    .onFailure { error ->
                        SwingUtilities.invokeLater { status.text = "可靠性报告写入失败：${error.message.orEmpty()}" }
                    }
            }
        }
    }

    private fun exportBatchRecipe(task: UnifiedTaskEntry) {
        val access = OmniCodeEntitlementService.getInstance()
            .access(OmniCodePaidFeature.BATCH_TASK_RECIPES)
        if (!access.allowed) {
            Messages.showInfoMessage(
                this,
                "${access.message}\n\n现有 Agent、Team、MCP、Git/浏览器和任务包功能仍然免费。",
                "OmniCode Pro",
            )
            return
        }
        service.taskPrompt(task) { prompt ->
            if (prompt.isNullOrBlank()) {
                status.text = "该任务没有可保存的文本目标。"
                return@taskPrompt
            }
            val chooser = JFileChooser().apply {
                dialogTitle = "保存批量任务配方"
                selectedFile = java.io.File("omnicode-recipe-${task.taskId.take(8)}.md")
                fileFilter = FileNameExtensionFilter("Markdown recipe (*.md)", "md")
            }
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return@taskPrompt
            val report = BatchTaskRecipeExporter.markdown(
                BatchTaskRecipeInput(
                    title = task.title,
                    prompt = prompt,
                    mode = task.mode,
                    strategy = task.strategy,
                    requiredImageAttachments = task.requiredImageAttachments,
                ),
            )
            val target = chooser.selectedFile.toPath()
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { Files.writeString(target, report) }
                    .onSuccess {
                        SwingUtilities.invokeLater { status.text = "批量任务配方已保存：${target.fileName}" }
                    }
                    .onFailure { error ->
                        SwingUtilities.invokeLater { status.text = "批量任务配方写入失败：${error.message.orEmpty()}" }
                    }
            }
        }
    }

    private fun exportEngineeringDigest() {
        val access = OmniCodeEntitlementService.getInstance()
            .access(OmniCodePaidFeature.ENGINEERING_WEEKLY_DIGEST)
        if (!access.allowed) {
            Messages.showInfoMessage(
                this,
                "${access.message}\n\n可靠性中心、任务历史、Git/浏览器工具、MCP 和所有基础 Agent 能力仍然免费。",
                "OmniCode Pro",
            )
            return
        }
        val basePath = project.basePath?.takeIf(String::isNotBlank)
        if (basePath == null) {
            status.text = "项目没有本地工作区，无法读取 Git 版本差异。"
            return
        }
        val periodStart = Instant.now().minus(7, ChronoUnit.DAYS)
        val tasks = renderedTasks.orEmpty().filter { task ->
            !task.updatedAt.isBefore(periodStart) || task.status in setOf(
                UnifiedTaskStatus.RUNNING,
                UnifiedTaskStatus.RECOVERABLE,
                UnifiedTaskStatus.WAITING_FOR_APPROVAL,
                UnifiedTaskStatus.PAUSED,
            )
        }
        status.text = "正在读取本地 Git 版本差异并整理任务进度…"
        status.foreground = OmniCodeUiPalette.secondary
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val git = runBlocking { GitProgressCollector().collect(Path.of(basePath), periodDays = 7) }
                EngineeringDigestExporter.markdown(
                    EngineeringDigestInput(
                        projectName = project.name,
                        periodStart = periodStart,
                        git = git,
                        tasks = tasks,
                    ),
                )
            }
            SwingUtilities.invokeLater {
                result.onFailure { error ->
                    status.text = "周报生成失败：${error.message.orEmpty()}"
                    status.foreground = OmniCodeUiPalette.error
                }.onSuccess { report ->
                    val chooser = JFileChooser().apply {
                        dialogTitle = "导出工程进展周报"
                        selectedFile = java.io.File("omnicode-weekly-digest.md")
                        fileFilter = FileNameExtensionFilter("Markdown weekly digest (*.md)", "md")
                    }
                    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                        status.text = "已取消周报导出。"
                        return@onSuccess
                    }
                    val target = chooser.selectedFile.toPath()
                    ApplicationManager.getApplication().executeOnPooledThread {
                        runCatching { Files.writeString(target, report) }
                            .onSuccess {
                                SwingUtilities.invokeLater {
                                    status.text = "工程周报已导出：${target.fileName}"
                                    status.foreground = OmniCodeUiPalette.success
                                }
                            }
                            .onFailure { error ->
                                SwingUtilities.invokeLater {
                                    status.text = "工程周报写入失败：${error.message.orEmpty()}"
                                    status.foreground = OmniCodeUiPalette.error
                                }
                            }
                    }
                }
            }
        }
    }

    private fun importWorkflowPackage() {
        val chooser = JFileChooser().apply {
            dialogTitle = "导入 OmniCode 任务包"
            fileFilter = FileNameExtensionFilter("OmniCode task package (*.omnitask)", "omnitask")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val bytes = runCatching { Files.readAllBytes(chooser.selectedFile.toPath()) }
            .getOrElse { error ->
                status.text = "任务包读取失败：${error.message.orEmpty()}"
                return
            }
        if (bytes.size > MAX_TRANSFER_PACKAGE_BYTES) {
            status.text = "任务包超过 2 MiB 上限。"
            return
        }
        val passphrase = requestPassphrase("输入任务包密码：") ?: return
        service.importWorkflowPackage(bytes, passphrase) { result ->
            result.onSuccess { imported ->
                status.text = "已导入任务：${imported.title.take(80)}（待恢复）"
                refresh(showLoading = false)
            }.onFailure { error -> status.text = "任务包导入失败：${error.message.orEmpty()}" }
        }
    }

    private fun syncWorkflowPackage(task: UnifiedTaskEntry) {
        val workflowId = task.workflowId ?: return
        val endpoint = JTextField(32)
        val token = JPasswordField(32)
        val passphrase = JPasswordField(32)
        val direction = JComboBox(arrayOf("上传到自建 relay", "从自建 relay 下载"))
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("仅支持你自己的 HTTPS relay；OmniCode 只传输已加密任务包。"))
            add(JBLabel("Endpoint（例如 https://sync.example.com）"))
            add(endpoint)
            add(JBLabel("Bearer token（不会保存）"))
            add(token)
            add(JBLabel("任务包密码（不会上传）"))
            add(passphrase)
            add(direction)
        }
        val response = JOptionPane.showConfirmDialog(
            this,
            content,
            "云端任务迁移",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (response != JOptionPane.OK_OPTION) {
            token.password.fill('\u0000')
            passphrase.password.fill('\u0000')
            token.text = ""
            passphrase.text = ""
            return
        }
        val tokenChars = token.password
        val passphraseChars = passphrase.password
        token.text = ""
        passphrase.text = ""
        if (tokenChars.size < 8 || passphraseChars.size < 12 || endpoint.text.trim().isBlank()) {
            tokenChars.fill('\u0000')
            passphraseChars.fill('\u0000')
            status.text = "Endpoint、token 或任务包密码长度不符合要求。"
            return
        }
        if (direction.selectedIndex == 0) {
            service.uploadWorkflowPackageToCloud(
                workflowId = workflowId,
                encryptionPassphrase = passphraseChars,
                endpoint = endpoint.text.trim(),
                bearerToken = tokenChars,
            ) { result ->
                result.onSuccess { status.text = "任务包已上传到自建 relay。" }
                    .onFailure { error -> status.text = "云端上传失败：${error.message.orEmpty()}" }
            }
        } else {
            service.downloadWorkflowPackageFromCloud(
                workflowId = workflowId,
                endpoint = endpoint.text.trim(),
                bearerToken = tokenChars,
                encryptionPassphrase = passphraseChars,
            ) { result ->
                result.onSuccess { imported ->
                    status.text = "已从 relay 导入任务：${imported.title.take(80)}（待恢复）"
                    refresh(showLoading = false)
                }.onFailure { error -> status.text = "云端下载失败：${error.message.orEmpty()}" }
            }
        }
    }

    private fun requestPassphrase(message: String): CharArray? {
        val field = JPasswordField(28)
        val content = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(6))).apply {
            isOpaque = false
            add(JBLabel(message), BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }
        val response = JOptionPane.showConfirmDialog(
            this,
            content,
            "任务包加密",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        val value = field.password
        field.text = ""
        if (response != JOptionPane.OK_OPTION || value.size < 12) {
            value.fill('\u0000')
            if (response == JOptionPane.OK_OPTION) status.text = "密码至少需要 12 个字符。"
            return null
        }
        return value
    }

    private fun taskStatusLabel(value: UnifiedTaskStatus): String = when (value) {
        UnifiedTaskStatus.RUNNING -> "运行中"
        UnifiedTaskStatus.WAITING_FOR_APPROVAL -> "待批准"
        UnifiedTaskStatus.PAUSED -> "已暂停"
        UnifiedTaskStatus.RECOVERABLE -> "待恢复"
        UnifiedTaskStatus.FAILED -> "失败"
        UnifiedTaskStatus.COMPLETED -> "已完成"
        UnifiedTaskStatus.CANCELLED -> "已取消"
        UnifiedTaskStatus.BUDGET_EXHAUSTED -> "有限模式已暂停"
    }

    private fun taskStatusColor(value: UnifiedTaskStatus) = when (value) {
        UnifiedTaskStatus.RUNNING,
        UnifiedTaskStatus.WAITING_FOR_APPROVAL,
        -> OmniCodeUiPalette.accent
        UnifiedTaskStatus.COMPLETED -> OmniCodeUiPalette.success
        UnifiedTaskStatus.FAILED -> OmniCodeUiPalette.error
        UnifiedTaskStatus.PAUSED,
        UnifiedTaskStatus.BUDGET_EXHAUSTED,
        -> OmniCodeUiPalette.warning
        UnifiedTaskStatus.RECOVERABLE,
        UnifiedTaskStatus.CANCELLED,
        -> OmniCodeUiPalette.secondary
    }

    private fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis.coerceAtLeast(0)}ms"
        millis < 60_000 -> "${millis / 1_000}s"
        else -> "${millis / 60_000}m${millis / 1_000 % 60}s"
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        const val MAX_TRANSFER_PACKAGE_BYTES = 2 * 1_048_576
    }
}

internal const val TASK_CENTER_REFRESH_MILLIS: Int = 2_000
internal const val TASK_ACTIONS_RUNNING_TOOLTIP: String = "当前任务正在运行，完成后可继续、重试、复制或恢复检查点。"

internal fun taskCenterShouldPoll(isShowing: Boolean, disposed: Boolean): Boolean = isShowing && !disposed
