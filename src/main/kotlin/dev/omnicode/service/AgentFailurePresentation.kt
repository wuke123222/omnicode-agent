package dev.omnicode.service

import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.agent.ContextBudgetExceededException
import dev.omnicode.agent.ProviderOutputLimitReachedException
import dev.omnicode.agent.UserMessageTooLargeException
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
    CONFIGURE_PRICING,
    SWITCH_MODEL,
    RESTORE_AND_RETRY,
    ADJUST_BUDGET,
    OPEN_SANDBOX,
    RUN_DIAGNOSTICS,
    EDIT_AND_RETRY,
}

class PricingUnavailableException(message: String) : IllegalStateException(message)
class CostBaselineUnavailableException(message: String) : IllegalStateException(message)

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
    val sharedBudget = causes.filterIsInstance<SharedAgentBudgetExceededException>().firstOrNull()
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

        causes.any { it is PricingUnavailableException } || sharedBudget?.pricingUnavailable == true -> presentation(
            AgentFailureKind.CONFIGURATION,
            "费用上限缺少可信定价",
            "已安全阻止模型请求。请在“使用统计 → 价格配置”中补全本轮所有模型的价格，" +
                "或关闭费用上限；恢复旧任务时还必须存在可信的历史费用基线。",
            "配置模型价格",
            AgentRecoveryAction.CONFIGURE_PRICING,
        )

        causes.any { it is CostBaselineUnavailableException } -> presentation(
            AgentFailureKind.BUDGET,
            "旧任务缺少可信费用基线",
            "不能安全估算旧检查点中多模型或在途请求的历史费用。请暂时关闭本次任务费用上限后继续，" +
                "或放弃旧检查点重新开始。",
            "调整费用上限",
            AgentRecoveryAction.ADJUST_BUDGET,
        )

        causes.any { it is ContextBudgetExceededException } -> presentation(
            AgentFailureKind.MODEL_CAPABILITY,
            "当前上下文超过可用窗口",
            "系统指令、任务目标和最近消息已经超过当前模型可处理的上下文。请移除不必要的固定文件或附件、只引用必要片段，也可以切换到上下文更大的模型。",
            "精简上下文后重试",
            AgentRecoveryAction.EDIT_AND_RETRY,
        )

        causes.any { it is ProviderOutputLimitReachedException } -> presentation(
            AgentFailureKind.MODEL_CAPABILITY,
            "模型单次输出已达到上限",
            "已生成的阶段结果会保留。可以在 API 与模型中提高单次模型响应上限、切换模型，或继续任务让模型分段完成。",
            "调整单次输出上限",
            AgentRecoveryAction.CONFIGURE_PROVIDER,
        )

        causes.any { it is UserMessageTooLargeException } -> presentation(
            AgentFailureKind.MODEL_CAPABILITY,
            "输入内容过大",
            "请缩短任务描述，或把长文档作为文件分段引用后重试。运行时长、轮次和工具调用设置无法解决输入大小问题。",
            "编辑输入后重试",
            AgentRecoveryAction.EDIT_AND_RETRY,
        )

        status == AgentRunStatus.BUDGET_EXHAUSTED ||
            causes.any { it is SharedAgentBudgetExceededException } -> presentation(
            AgentFailureKind.BUDGET,
            "任务在有限模式下暂停",
            "已取得的结果不会丢失。开启持续执行即可取消累计时间、轮次和工具调用边界；单次操作、安全审批与沙箱保护仍会生效。",
            "开启持续执行并恢复",
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

        provider?.networkFailure == true && isTlsFailure(searchable) -> presentation(
            AgentFailureKind.NETWORK,
            "TLS 握手失败",
            "模型请求尚未到达供应商。请检查 HTTPS 接口地址、IDE/系统代理、VPN 的 TLS 拦截和证书信任；不要关闭证书校验，然后运行连接诊断。",
            "运行连接诊断",
            AgentRecoveryAction.RUN_DIAGNOSTICS,
        )

        provider?.networkFailure == true && isTimeout(searchable) -> presentation(
            AgentFailureKind.NETWORK_TIMEOUT,
            "连接模型超时",
            "在限定时间内没有连接到模型服务。请检查网络、代理和接口地址，然后恢复任务。",
            "运行连接诊断",
            AgentRecoveryAction.RUN_DIAGNOSTICS,
        )

        provider?.networkFailure == true -> presentation(
            AgentFailureKind.NETWORK,
            "无法连接模型服务",
            "网络连接失败。请检查代理、接口地址和本地模型服务是否正在运行。",
            "运行连接诊断",
            AgentRecoveryAction.RUN_DIAGNOSTICS,
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

private fun isTlsFailure(searchable: String): Boolean =
    searchable.contains("tls handshake") ||
        searchable.contains("sslhandshakeexception") ||
        searchable.contains("ssl exception") ||
        searchable.contains("remote host terminated the handshake")

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
