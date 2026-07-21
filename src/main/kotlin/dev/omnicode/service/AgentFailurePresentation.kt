package dev.omnicode.service

import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.agent.ContextBudgetExceededException
import dev.omnicode.agent.SharedAgentBudgetExceededException
import dev.omnicode.provider.ProviderException
import dev.omnicode.tool.SandboxUnavailableException
import kotlinx.coroutines.CancellationException

enum class AgentFailureKind {
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMIT,
    NETWORK_TIMEOUT,
    NETWORK,
    MODEL_CAPABILITY,
    BUDGET,
    SANDBOX,
    CONFIGURATION,
    CANCELLED,
    UNKNOWN,
}

enum class AgentRecoveryAction {
    CONFIGURE_PROVIDER,
    SWITCH_MODEL,
    RESTORE_AND_RETRY,
    ADJUST_BUDGET,
    OPEN_SANDBOX,
    EDIT_AND_RETRY,
}

data class AgentFailurePresentation(
    val kind: AgentFailureKind,
    val title: String,
    val detail: String,
    val recoveryLabel: String,
    val recoveryTooltip: String,
    val recoveryAction: AgentRecoveryAction,
)

/**
 * Converts provider, budget, and local runtime failures into a stable user-facing taxonomy.
 * Messages are deliberately deterministic: raw provider bodies and exception messages may contain
 * credentials, prompts, local paths, or other data that must not be copied into the transcript.
 */
fun classifyAgentFailure(
    status: AgentRunStatus,
    error: Throwable?,
): AgentFailurePresentation {
    val causes = error.causeSequence().toList()
    val provider = causes.filterIsInstance<ProviderException>().firstOrNull()
    val searchable = causes.joinToString(" ") { cause ->
        "${cause::class.java.simpleName} ${cause.message.orEmpty()}"
    }.lowercase()

    return when {
        status == AgentRunStatus.CANCELLED || causes.any { it is CancellationException } -> presentation(
            AgentFailureKind.CANCELLED,
            "任务已取消",
            "已完成的内容仍保留在对话中；可以恢复原任务和附件后继续编辑。",
            "恢复任务",
            AgentRecoveryAction.EDIT_AND_RETRY,
        )

        status == AgentRunStatus.BUDGET_EXHAUSTED ||
            causes.any { it is SharedAgentBudgetExceededException || it is ContextBudgetExceededException } -> presentation(
            AgentFailureKind.BUDGET,
            "已达到运行预算",
            "任务已安全停止，已取得的结果不会丢失。可以提高 Token、时间或费用上限后继续。",
            "调整预算并恢复",
            AgentRecoveryAction.ADJUST_BUDGET,
        )

        causes.any { it is SandboxUnavailableException } ||
            searchable.contains("workspace_write unavailable") || searchable.contains("sandbox unavailable") -> presentation(
            AgentFailureKind.SANDBOX,
            "沙箱当前不可用",
            "系统无法建立所选的命令隔离边界，因此拒绝继续执行。请检查或切换沙箱模式。",
            "打开沙箱设置",
            AgentRecoveryAction.OPEN_SANDBOX,
        )

        provider?.statusCode == 401 -> presentation(
            AgentFailureKind.AUTHENTICATION,
            "API Key 验证失败",
            "供应商拒绝了当前凭据。请检查 Key、接口地址以及该 Key 所属的 API 平台。",
            "检查 API 配置",
            AgentRecoveryAction.CONFIGURE_PROVIDER,
        )

        provider?.statusCode == 403 -> presentation(
            AgentFailureKind.PERMISSION,
            "当前账号没有访问权限",
            "凭据已被供应商识别，但无权访问所选模型或接口。请检查项目、区域和模型权限。",
            "检查账号与模型",
            AgentRecoveryAction.CONFIGURE_PROVIDER,
        )

        provider?.statusCode == 429 -> presentation(
            AgentFailureKind.RATE_LIMIT,
            "供应商请求过于频繁",
            "当前请求触发了速率或额度限制。草稿和附件已保留，请稍后恢复后重试。",
            "恢复后稍后重试",
            AgentRecoveryAction.RESTORE_AND_RETRY,
        )

        provider?.networkFailure == true && isTimeout(searchable) -> presentation(
            AgentFailureKind.NETWORK_TIMEOUT,
            "连接模型超时",
            "在限定时间内没有连接到模型服务。请检查网络、代理和接口地址，然后恢复任务。",
            "检查连接并恢复",
            AgentRecoveryAction.CONFIGURE_PROVIDER,
        )

        provider?.networkFailure == true -> presentation(
            AgentFailureKind.NETWORK,
            "无法连接模型服务",
            "网络连接失败。请检查代理、接口地址和本地模型服务是否正在运行。",
            "检查连接并恢复",
            AgentRecoveryAction.CONFIGURE_PROVIDER,
        )

        looksLikeModelCapabilityFailure(provider, searchable) -> presentation(
            AgentFailureKind.MODEL_CAPABILITY,
            "当前模型不支持这项任务",
            "模型可能不支持工具调用、图片、所选推理强度或当前请求格式。请切换模型后重试。",
            "切换模型并恢复",
            AgentRecoveryAction.SWITCH_MODEL,
        )

        looksLikeConfigurationFailure(searchable) -> presentation(
            AgentFailureKind.CONFIGURATION,
            "模型配置尚未就绪",
            "请检查供应商、接口地址、模型名称和凭据是否已经完整保存。",
            "检查配置",
            AgentRecoveryAction.CONFIGURE_PROVIDER,
        )

        else -> presentation(
            AgentFailureKind.UNKNOWN,
            "任务未能完成",
            "运行过程中发生异常。已完成的输出、原任务和附件仍会保留，可编辑后重新发送。",
            "编辑后重发",
            AgentRecoveryAction.EDIT_AND_RETRY,
        )
    }
}

fun AgentFailurePresentation.transcriptText(): String = "$title\n\n$detail"

private fun presentation(
    kind: AgentFailureKind,
    title: String,
    detail: String,
    recoveryLabel: String,
    action: AgentRecoveryAction,
): AgentFailurePresentation = AgentFailurePresentation(
    kind = kind,
    title = title,
    detail = detail,
    recoveryLabel = recoveryLabel,
    recoveryTooltip = detail,
    recoveryAction = action,
)

private fun Throwable?.causeSequence(): Sequence<Throwable> = sequence {
    var current = this@causeSequence
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}

private fun isTimeout(searchable: String): Boolean =
    searchable.contains("timeout") || searchable.contains("timed out")

private fun looksLikeModelCapabilityFailure(provider: ProviderException?, searchable: String): Boolean {
    if (provider?.statusCode !in setOf(400, 404, 409, 422)) return false
    return MODEL_CAPABILITY_MARKERS.any(searchable::contains)
}

private fun looksLikeConfigurationFailure(searchable: String): Boolean =
    CONFIGURATION_MARKERS.any(searchable::contains)

private val MODEL_CAPABILITY_MARKERS = listOf(
    "model",
    "unsupported",
    "not support",
    "tool call",
    "tool_choice",
    "vision",
    "image",
    "reasoning",
    "context length",
)

private val CONFIGURATION_MARKERS = listOf(
    "api key",
    "credential",
    "provider not configured",
    "endpoint",
    "base url",
    "请先保存",
    "未配置",
    "missing",
)
