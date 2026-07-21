package dev.omnicode.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import dev.omnicode.persistence.SensitiveDataRedactor
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.mcp.McpLaunchApprovalDecision
import dev.omnicode.mcp.McpLaunchApprovalGate
import dev.omnicode.mcp.McpLaunchApprovalRequest
import dev.omnicode.mcp.McpHttpApprovalGate
import dev.omnicode.mcp.McpHttpApprovalRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.coroutines.resume

class ModalApprovalGate(
    private val project: Project,
) : ApprovalGate, McpLaunchApprovalGate, McpHttpApprovalGate {
    override suspend fun approve(request: ApprovalRequest): Boolean = suspendCancellableCoroutine { continuation ->
        val showDialog = Runnable {
            if (!continuation.isActive || project.isDisposed) {
                if (continuation.isActive) continuation.resume(false)
                return@Runnable
            }

            val approved = runCatching {
                if (request.diff != null) {
                    DiffApprovalDialog(project, request).showExplicitlyAndGet()
                } else {
                    Messages.showDialog(
                        project,
                        approvalMessage(request),
                        request.title,
                        arrayOf("仅本次允许", "拒绝"),
                        1,
                        Messages.getWarningIcon(),
                    ) == 0
                }
            }.getOrDefault(false)
            if (continuation.isActive) continuation.resume(approved)
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            showDialog.run()
        } else {
            application.invokeLater(showDialog, ModalityState.any())
        }
    }

    override suspend fun approveMcpLaunch(
        request: McpLaunchApprovalRequest,
    ): McpLaunchApprovalDecision = suspendCancellableCoroutine { continuation ->
        val showDialog = Runnable {
            if (!continuation.isActive || project.isDisposed) {
                if (continuation.isActive) continuation.resume(McpLaunchApprovalDecision.REJECT)
                return@Runnable
            }

            val decision = runCatching {
                when (
                    Messages.showDialog(
                        project,
                        buildString {
                            appendLine(request.details())
                            appendLine()
                            appendLine("风险：${request.risk()}")
                            appendLine()
                            append("“信任此配置”仅对当前项目和上述指纹生效；配置或可执行文件变化后会重新询问。")
                        },
                        "启动 MCP 服务器 ${request.serverName}",
                        arrayOf("仅本次允许", "信任此配置", "拒绝"),
                        2,
                        Messages.getWarningIcon(),
                    )
                ) {
                    0 -> McpLaunchApprovalDecision.ALLOW_ONCE
                    1 -> McpLaunchApprovalDecision.TRUST_CONFIGURATION
                    else -> McpLaunchApprovalDecision.REJECT
                }
            }.getOrDefault(McpLaunchApprovalDecision.REJECT)
            if (continuation.isActive) continuation.resume(decision)
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            showDialog.run()
        } else {
            application.invokeLater(showDialog, ModalityState.any())
        }
    }

    override suspend fun approveMcpHttp(
        request: McpHttpApprovalRequest,
    ): McpLaunchApprovalDecision = suspendCancellableCoroutine { continuation ->
        val showDialog = Runnable {
            if (!continuation.isActive || project.isDisposed) {
                if (continuation.isActive) continuation.resume(McpLaunchApprovalDecision.REJECT)
                return@Runnable
            }

            val decision = runCatching {
                when (
                    Messages.showDialog(
                        project,
                        buildString {
                            appendLine(request.details())
                            appendLine()
                            appendLine("风险：${request.risk()}")
                            appendLine()
                            append("“信任此配置”仅对当前项目、Endpoint 和配置指纹生效；URL 变化后会重新询问。")
                        },
                        "连接 MCP 服务 ${request.serverName}",
                        arrayOf("仅本次允许", "信任此配置", "拒绝"),
                        2,
                        Messages.getWarningIcon(),
                    )
                ) {
                    0 -> McpLaunchApprovalDecision.ALLOW_ONCE
                    1 -> McpLaunchApprovalDecision.TRUST_CONFIGURATION
                    else -> McpLaunchApprovalDecision.REJECT
                }
            }.getOrDefault(McpLaunchApprovalDecision.REJECT)
            if (continuation.isActive) continuation.resume(decision)
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            showDialog.run()
        } else {
            application.invokeLater(showDialog, ModalityState.any())
        }
    }

    private fun approvalMessage(request: ApprovalRequest): String = buildString {
        appendLine(request.details)
        appendLine()
        appendLine("风险：${request.risk}")
        appendLine()
        append("是否允许 ${request.toolName} 仅执行一次？")
    }
}

private class DiffApprovalDialog(
    project: Project,
    private val approval: ApprovalRequest,
) : DialogWrapper(project) {
    private val summary = diffApprovalSummary(approval)
    private val panelDisposable = Disposer.newDisposable("OmniCode diff approval")
    private val requestPanel: DiffRequestPanel =
        DiffManager.getInstance().createRequestPanel(project, panelDisposable, null)

    init {
        title = summary.title
        setOKButtonText(DIFF_APPLY_ACTION_LABEL)
        setCancelButtonText(DIFF_REJECT_ACTION_LABEL)
        configureExplicitDiffApprovalActions(getOKAction(), getCancelAction())
        val diff = requireNotNull(approval.diff)
        val factory = DiffContentFactory.getInstance()
        requestPanel.setRequest(
            SimpleDiffRequest(
                summary.title,
                factory.create(diff.before),
                factory.create(diff.after),
                "修改前",
                "修改后",
            ),
        )
        init()
    }

    fun showExplicitlyAndGet(): Boolean {
        show()
        return isExplicitDiffApproval(exitCode)
    }

    override fun createNorthPanel(): JComponent = diffApprovalSummaryPanel(summary)

    override fun createCenterPanel(): JComponent = requestPanel.component.apply {
        preferredSize = Dimension(900, 650)
    }

    override fun getDimensionServiceKey(): String = "OmniCode.ApplyChangeApproval"

    override fun dispose() {
        Disposer.dispose(panelDisposable)
        super.dispose()
    }
}

internal data class DiffApprovalSummary(
    val title: String,
    val tool: String,
    val target: String,
    val risk: String,
    val details: String,
)

internal fun diffApprovalSummary(
    approval: ApprovalRequest,
    redactor: SensitiveDataRedactor = DefaultSensitiveDataRedactor(),
): DiffApprovalSummary {
    val diff = requireNotNull(approval.diff) { "Diff approval requires a diff" }
    fun protected(value: String, fallback: String): String = redactor
        .redact(value.take(MAX_APPROVAL_METADATA_CHARS))
        .trim()
        .ifBlank { fallback }

    return DiffApprovalSummary(
        title = protected(approval.title, "审阅文件修改"),
        tool = protected(approval.toolName, "未知工具"),
        target = protected(diff.path, "未提供"),
        risk = protected(approval.risk, "此操作会修改项目文件。"),
        details = protected(approval.details, "请检查完整差异后再决定是否应用。"),
    )
}

/**
 * File writes fail closed: Enter and the initial focus reject the proposal. Applying a
 * change therefore requires selecting the explicitly labelled apply action.
 */
internal fun configureExplicitDiffApprovalActions(applyAction: Action, rejectAction: Action) {
    applyAction.putValue(DialogWrapper.DEFAULT_ACTION, null)
    applyAction.putValue(DialogWrapper.FOCUSED_ACTION, null)
    rejectAction.putValue(DialogWrapper.DEFAULT_ACTION, true)
    rejectAction.putValue(DialogWrapper.FOCUSED_ACTION, true)
}

internal fun isExplicitDiffApproval(exitCode: Int): Boolean = exitCode == DialogWrapper.OK_EXIT_CODE

private fun diffApprovalSummaryPanel(summary: DiffApprovalSummary): JComponent = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(0, 0, 12, 0)
    add(
        JBLabel("确认本次文件修改").apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyBottom(8)
        },
        BorderLayout.NORTH,
    )
    add(
        JPanel(GridBagLayout()).apply {
            isOpaque = false
            addSummaryRow(0, "工具", summary.tool)
            addSummaryRow(1, "目标", summary.target)
            addSummaryRow(2, "风险", summary.risk)
            addSummaryRow(3, "详情", summary.details)
        },
        BorderLayout.CENTER,
    )
}

private fun JPanel.addSummaryRow(row: Int, label: String, value: String) {
    add(
        JBLabel("$label：").apply {
            font = font.deriveFont(Font.BOLD)
        },
        GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0, 2, 10)
        },
    )
    add(
        JBTextArea(value).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            accessibleContext.accessibleName = label
        },
        GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0)
        },
    )
}

private const val MAX_APPROVAL_METADATA_CHARS = 8_000
internal const val DIFF_APPLY_ACTION_LABEL = "仅本次应用"
internal const val DIFF_REJECT_ACTION_LABEL = "拒绝"
