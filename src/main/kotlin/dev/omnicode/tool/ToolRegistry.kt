package dev.omnicode.tool

import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ToolDefinition

class ToolRegistry(
    runCommandTool: AgentTool = RunCommandTool(),
    additionalTools: List<AgentTool> = emptyList(),
) {
    private val tools: List<AgentTool> = listOf(
        ListFilesTool(),
        ReadFileTool(),
        SearchTextTool(),
        SearchProjectContextTool(),
        ListIdeProblemsTool(),
        ApplyPatchTool(),
        ApplyChangeTool(),
        runCommandTool,
    ) + additionalTools
    private val byName = tools.associateBy(AgentTool::name)

    val definitions = tools.map(AgentTool::definition)

    fun find(name: String): AgentTool? = byName[name]

    fun definitionsFor(mode: AgentMode): List<ToolDefinition> = tools
        .asSequence()
        .filter { isAllowed(it, mode) }
        .map(AgentTool::definition)
        .toList()

    fun findAllowed(name: String, mode: AgentMode): AgentTool? = find(name)?.takeIf { isAllowed(it, mode) }

    fun isAllowed(tool: AgentTool, mode: AgentMode): Boolean = when (mode) {
        AgentMode.AGENT -> true
        AgentMode.PLAN -> tool.effect == ToolEffect.READ_ONLY
        AgentMode.CLAUDE_PLAN -> tool.effect == ToolEffect.READ_ONLY
        AgentMode.RESEARCH -> tool.effect == ToolEffect.READ_ONLY || tool.effect == ToolEffect.COMMAND
    }
}
