package dev.omnicode.service

import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.ToolAvailabilityEntry
import dev.omnicode.provider.CLI_BRIDGE_TOOL_NAMES
import dev.omnicode.provider.LocalAgentEngineRegistry
import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.provider.nativeHostBridgeToolName
import dev.omnicode.tool.ToolDescriptor

/** A bounded description of the exact host tool surface prepared for one turn. */
internal fun toolAvailabilitySnapshot(
    descriptors: List<ToolDescriptor>,
    mode: AgentMode,
    transport: HostToolTransport,
    enabledDatabaseCount: Int,
    credentialedDatabaseCount: Int,
    configuredMcpCount: Int,
    connectedMcpToolCount: Int,
    mcpErrorCount: Int,
    recentlyTestedDatabaseCount: Int = 0,
    recentlyTestedMcpCount: Int = 0,
): AgentEvent.ToolAvailability {
    val exposed = when (transport) {
        HostToolTransport.STRUCTURED -> descriptors
        HostToolTransport.CLI_BRIDGE -> descriptors.filter { it.name in CLI_BRIDGE_TOOL_NAMES }
        HostToolTransport.MARKER_BRIDGE -> descriptors.filter { nativeHostBridgeToolName(it.name) }.take(12)
        HostToolTransport.NATIVE_UNBRIDGED -> emptyList()
    }
    val entries = exposed.asSequence()
        .filter { SAFE_TOOL_NAME.matches(it.name) }
        .take(30)
        .map { descriptor ->
            when (descriptor.name) {
                "query_database" -> when {
                    enabledDatabaseCount == 0 -> ToolAvailabilityEntry(
                        descriptor.name, "configuration", "未启用数据库连接；在设置中保存 URL 与凭据后才能查询。",
                    )
                    credentialedDatabaseCount == 0 -> ToolAvailabilityEntry(
                        descriptor.name, "configuration", "连接已启用，但当前 URL 对应的密码尚未保存在 Password Safe。",
                    )
                    else -> ToolAvailabilityEntry(
                        descriptor.name, "approval", "已配置 $credentialedDatabaseCount 个带凭据的连接，其中 $recentlyTestedDatabaseCount 个最近测试通过；当前会话仍需审批和实时连接。",
                    )
                }
                else -> ToolAvailabilityEntry(
                    descriptor.name,
                    if (descriptor.dangerous) "approval" else "available",
                    if (descriptor.dangerous) "已暴露给当前引擎；执行前仍受审批、沙箱和审计约束。"
                    else "已暴露给当前引擎；不代表本轮已调用。",
                )
            }
        }
        .toMutableList()

    if (entries.none { it.name == "query_database" }) {
        entries += ToolAvailabilityEntry(
            "query_database", "unsupported",
            if (mode != AgentMode.AGENT) "当前模式不开放外部数据库查询。"
            else "当前引擎未接入 OmniCode 的数据库工具；保存连接不会让原生代理自动获得该工具。",
        )
    }
    if (configuredMcpCount > 0 || connectedMcpToolCount > 0 || mcpErrorCount > 0) {
        entries += ToolAvailabilityEntry(
            "mcp", when {
                mode != AgentMode.AGENT || transport !in setOf(HostToolTransport.STRUCTURED, HostToolTransport.MARKER_BRIDGE) -> "unsupported"
                connectedMcpToolCount == 0 -> "configuration"
                transport == HostToolTransport.MARKER_BRIDGE && exposed.none { it.name.startsWith("mcp__") } -> "configuration"
                else -> "approval"
            }, when {
                mode != AgentMode.AGENT -> "当前模式不会启动 MCP 服务。"
                transport !in setOf(HostToolTransport.STRUCTURED, HostToolTransport.MARKER_BRIDGE) -> "已配置 MCP，但当前引擎不接收 OmniCode 的 MCP 工具；可切换支持结构化工具调用的 API 引擎。"
                connectedMcpToolCount == 0 -> "已配置 $configuredMcpCount 个服务，其中 $recentlyTestedMcpCount 个最近测试通过；本轮没有连接成功的工具，请检查当前引擎与认证。"
                else -> "本轮连接 $connectedMcpToolCount 个工具、$mcpErrorCount 个连接错误；每次调用仍需审批，文本桥接需模型实际发起规范请求。"
            },
        )
    }
    return AgentEvent.ToolAvailability(
        entries = entries.take(40),
        note = if (transport == HostToolTransport.NATIVE_UNBRIDGED) {
            "当前引擎自行管理原生工具，OmniCode 宿主工具尚未桥接；此卡不代表原生工具目录。"
        } else if (transport == HostToolTransport.MARKER_BRIDGE) {
            "本轮已向原生引擎提供 ${exposed.size} 个宿主工具的有界文本协议；只有收到规范调用并经 AgentEngine 审批执行才算可用，目录不证明模型已调用。"
        } else {
            "本轮宿主已准备 ${exposed.size} 个工具；目录不是模型调用或执行成功证明，连接、授权和审批仍以实际调用为准。"
        },
    )
}

internal enum class HostToolTransport { STRUCTURED, CLI_BRIDGE, MARKER_BRIDGE, NATIVE_UNBRIDGED }

internal fun formatToolAvailabilitySummary(event: AgentEvent.ToolAvailability): String = buildString {
    appendLine(event.note)
    event.entries.forEach { entry ->
        append(entry.name).append(" · ").append(entry.state).append(" · ").appendLine(entry.detail)
    }
}.take(3_000)

internal fun parseToolAvailabilitySummary(message: String): Pair<String, List<ToolAvailabilityEntry>> {
    val note = message.lineSequence().firstOrNull().orEmpty().take(500)
    val entries = message.lineSequence().drop(1).take(40).mapNotNull { line ->
        val parts = line.split(" · ", limit = 3)
        if (parts.size != 3 || !SAFE_TOOL_NAME.matches(parts[0]) ||
            parts[1] !in setOf("available", "approval", "configuration", "unsupported")) null
        else runCatching { ToolAvailabilityEntry(parts[0], parts[1], parts[2].take(240)) }.getOrNull()
    }.toList()
    return note to entries
}

/** Codex/DSH preserve native tools; host database/MCP requests use a bounded marker fallback. */
internal fun hostToolTransport(protocol: ProviderProtocol): HostToolTransport = when (protocol) {
    ProviderProtocol.CLI_CODEX, ProviderProtocol.CODEX_APP_SERVER,
    ProviderProtocol.CLI_DSH -> HostToolTransport.MARKER_BRIDGE
    ProviderProtocol.CLI_QODER -> HostToolTransport.CLI_BRIDGE
    else -> if (LocalAgentEngineRegistry.forProtocol(protocol) != null)
        HostToolTransport.CLI_BRIDGE else HostToolTransport.STRUCTURED
}

private val SAFE_TOOL_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")
