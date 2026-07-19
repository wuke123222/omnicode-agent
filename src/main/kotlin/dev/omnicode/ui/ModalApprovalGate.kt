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
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.mcp.McpLaunchApprovalDecision
import dev.omnicode.mcp.McpLaunchApprovalGate
import dev.omnicode.mcp.McpLaunchApprovalRequest
import dev.omnicode.mcp.McpHttpApprovalGate
import dev.omnicode.mcp.McpHttpApprovalRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Dimension
import javax.swing.JComponent
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
                    DiffApprovalDialog(project, request).showAndGet()
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
    private val panelDisposable = Disposer.newDisposable("OmniCode diff approval")
    private val requestPanel: DiffRequestPanel =
        DiffManager.getInstance().createRequestPanel(project, panelDisposable, null)

    init {
        title = approval.title
        setOKButtonText("仅本次允许")
        setCancelButtonText("拒绝")
        val diff = requireNotNull(approval.diff)
        val factory = DiffContentFactory.getInstance()
        requestPanel.setRequest(
            SimpleDiffRequest(
                approval.title,
                factory.create(diff.before),
                factory.create(diff.after),
                "修改前",
                "修改后",
            ),
        )
        init()
    }

    override fun createCenterPanel(): JComponent = requestPanel.component.apply {
        preferredSize = Dimension(900, 650)
    }

    override fun getDimensionServiceKey(): String = "OmniCode.ApplyChangeApproval"

    override fun dispose() {
        Disposer.dispose(panelDisposable)
        super.dispose()
    }
}
