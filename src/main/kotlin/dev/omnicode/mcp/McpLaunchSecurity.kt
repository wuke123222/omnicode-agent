package dev.omnicode.mcp

import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.persistence.OmniCodeLocalStore
import dev.omnicode.persistence.ToolApprovalDecision
import dev.omnicode.persistence.ToolExecutionRecord
import dev.omnicode.persistence.ToolExecutionStatus
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.SandboxMode
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

data class McpLaunchApprovalRequest(
    val serverName: String,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val sandboxMode: SandboxMode,
    val environmentKeys: Set<String>,
    val executablePath: String,
    val fingerprint: String,
) {
    fun details(): String = buildString {
        appendLine("服务器：$serverName")
        appendLine("命令：${renderCommand(command, arguments)}")
        appendLine("工作目录：$workingDirectory")
        appendLine("真实可执行文件：$executablePath")
        appendLine("沙箱：${sandboxMode.name}")
        appendLine("环境变量名：${environmentKeys.sorted().joinToString().ifBlank { "无" }}")
        append("配置指纹：${fingerprint.take(16)}…")
    }

    fun risk(): String = when (sandboxMode) {
        SandboxMode.WORKSPACE_WRITE ->
            "这会启动第三方本地进程。进程受 workspace-write 边界限制，但仍可读写当前工作区。"
        SandboxMode.DANGER_FULL_ACCESS ->
            "这会启动没有系统级文件或网络隔离的第三方进程，可能访问工作区外数据或产生外部副作用。"
    }

    private fun renderCommand(command: String, arguments: List<String>): String =
        (listOf(command) + arguments).joinToString(" ") { argument ->
            if (argument.isNotEmpty() && argument.none { it.isWhitespace() || it == '"' || it == '\'' }) {
                argument
            } else {
                "\"${argument.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
        }
}

enum class McpLaunchApprovalDecision {
    ALLOW_ONCE,
    TRUST_CONFIGURATION,
    REJECT,
}

fun interface McpLaunchApprovalGate {
    suspend fun approveMcpLaunch(request: McpLaunchApprovalRequest): McpLaunchApprovalDecision
}

internal fun ApprovalGate.asMcpLaunchApprovalGate(): McpLaunchApprovalGate =
    (this as? McpLaunchApprovalGate) ?: McpLaunchApprovalGate { request ->
        if (
            approve(
                ApprovalRequest(
                    toolName = "mcp_launch",
                    title = "启动 MCP 服务器 ${request.serverName}",
                    details = request.details(),
                    risk = request.risk(),
                ),
            )
        ) {
            McpLaunchApprovalDecision.ALLOW_ONCE
        } else {
            McpLaunchApprovalDecision.REJECT
        }
    }

internal interface McpLaunchTrustStore {
    fun isTrusted(serverId: String, projectId: String, fingerprint: String): Boolean
    fun trust(serverId: String, projectId: String, fingerprint: String)
}

internal class SettingsMcpLaunchTrustStore(
    private val settings: OmniCodePlatformSettingsService = OmniCodePlatformSettingsService.getInstance(),
) : McpLaunchTrustStore {
    override fun isTrusted(serverId: String, projectId: String, fingerprint: String): Boolean =
        settings.isMcpLaunchTrusted(serverId, projectId, fingerprint)

    override fun trust(serverId: String, projectId: String, fingerprint: String) {
        settings.trustMcpLaunch(serverId, projectId, fingerprint, System.currentTimeMillis())
    }
}

internal enum class McpLaunchAuditOutcome {
    APPROVAL_REQUESTED,
    APPROVED_ONCE,
    TRUSTED_CONFIGURATION,
    PERSISTENT_TRUST_USED,
    REJECTED,
    STARTED,
    FAILED,
}

internal data class McpLaunchAuditEvent(
    val executionId: String,
    val projectId: String,
    val serverName: String,
    val details: String,
    val outcome: McpLaunchAuditOutcome,
    val errorMessage: String? = null,
    val recordedAt: Instant = Instant.now(),
)

internal fun interface McpLaunchAuditSink {
    fun record(event: McpLaunchAuditEvent)
}

internal class LocalMcpLaunchAuditSink(
    private val store: OmniCodeLocalStore = OmniCodeLocalStore.default(),
) : McpLaunchAuditSink {
    override fun record(event: McpLaunchAuditEvent) {
        runCatching {
            store.recordToolExecution(
                ToolExecutionRecord(
                    executionId = event.executionId,
                    runId = event.executionId,
                    projectId = event.projectId,
                    toolName = "mcp_launch",
                    status = event.outcome.status(),
                    dangerous = true,
                    approvalDecision = event.outcome.approvalDecision(),
                    inputSummary = event.details,
                    outputSummary = event.outcome.summary(),
                    errorMessage = event.errorMessage,
                    recordedAt = event.recordedAt,
                    mode = AgentMode.AGENT,
                ),
            )
        }
    }

    private fun McpLaunchAuditOutcome.status(): ToolExecutionStatus = when (this) {
        McpLaunchAuditOutcome.APPROVAL_REQUESTED -> ToolExecutionStatus.REQUESTED
        McpLaunchAuditOutcome.APPROVED_ONCE,
        McpLaunchAuditOutcome.TRUSTED_CONFIGURATION,
        McpLaunchAuditOutcome.PERSISTENT_TRUST_USED,
        -> ToolExecutionStatus.RUNNING
        McpLaunchAuditOutcome.REJECTED -> ToolExecutionStatus.REJECTED
        McpLaunchAuditOutcome.STARTED -> ToolExecutionStatus.COMPLETED
        McpLaunchAuditOutcome.FAILED -> ToolExecutionStatus.FAILED
    }

    private fun McpLaunchAuditOutcome.approvalDecision(): ToolApprovalDecision = when (this) {
        McpLaunchAuditOutcome.APPROVAL_REQUESTED -> ToolApprovalDecision.NOT_REQUESTED
        McpLaunchAuditOutcome.REJECTED -> ToolApprovalDecision.REJECTED
        McpLaunchAuditOutcome.APPROVED_ONCE,
        McpLaunchAuditOutcome.TRUSTED_CONFIGURATION,
        McpLaunchAuditOutcome.PERSISTENT_TRUST_USED,
        McpLaunchAuditOutcome.STARTED,
        -> ToolApprovalDecision.APPROVED
        McpLaunchAuditOutcome.FAILED -> ToolApprovalDecision.NOT_REQUESTED
    }

    private fun McpLaunchAuditOutcome.summary(): String = when (this) {
        McpLaunchAuditOutcome.APPROVAL_REQUESTED -> "MCP launch approval requested"
        McpLaunchAuditOutcome.APPROVED_ONCE -> "MCP launch approved once"
        McpLaunchAuditOutcome.TRUSTED_CONFIGURATION -> "MCP launch configuration trusted"
        McpLaunchAuditOutcome.PERSISTENT_TRUST_USED -> "MCP launch allowed by persistent trust"
        McpLaunchAuditOutcome.REJECTED -> "MCP launch rejected"
        McpLaunchAuditOutcome.STARTED -> "MCP process started"
        McpLaunchAuditOutcome.FAILED -> "MCP process failed to start"
    }
}

internal fun mcpProjectIdentity(project: Project): String = sha256Hex(
    project.basePath.orEmpty().toByteArray(StandardCharsets.UTF_8),
)

internal fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

class McpLaunchRejectedException(serverName: String) : IllegalStateException(
    "MCP server '$serverName' was not started because launch approval was rejected.",
)
