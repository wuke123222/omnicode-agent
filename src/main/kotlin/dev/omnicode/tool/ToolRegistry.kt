package dev.omnicode.tool

import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ToolDefinition
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ToolDescriptor(
    val name: String,
    val effect: ToolEffect,
    val dangerous: Boolean,
    val definitionDigest: String,
)

class ToolRegistry(
    private val runCommandTool: AgentTool = RunCommandTool(),
    additionalTools: List<AgentTool> = emptyList(),
) {
    private val tools: List<AgentTool> = listOf(
        ListFilesTool(),
        ReadFileTool(),
        SearchTextTool(),
        SearchProjectContextTool(),
        InspectProjectHarnessTool(),
        ListIdeProblemsTool(),
        ApplyPatchTool(),
        ApplyChangeTool(),
        runCommandTool,
    ) + additionalTools
    private val byName: Map<String, AgentTool>

    init {
        require(tools.none { it.name.isBlank() }) { "Harness preflight rejected a blank tool name" }
        val duplicateNames = tools.groupingBy(AgentTool::name).eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        require(duplicateNames.isEmpty()) {
            "Harness preflight rejected duplicate tool names: ${duplicateNames.joinToString()}"
        }
        byName = tools.associateBy(AgentTool::name)
    }

    val definitions = tools.map(AgentTool::definition)

    fun find(name: String): AgentTool? = byName[name]

    fun definitionsFor(mode: AgentMode, safeOnly: Boolean = false): List<ToolDefinition> = tools
        .asSequence()
        .filter { isAllowed(it, mode) }
        .filter { !safeOnly || !it.dangerous }
        .map(AgentTool::definition)
        .toList()

    fun descriptorsFor(mode: AgentMode, safeOnly: Boolean = false): List<ToolDescriptor> = tools
        .asSequence()
        .filter { isAllowed(it, mode) }
        .filter { !safeOnly || !it.dangerous }
        .map { tool ->
            ToolDescriptor(
                name = tool.name,
                effect = tool.effect,
                dangerous = tool.dangerous,
                definitionDigest = MessageDigest.getInstance("SHA-256")
                    .digest(
                        (tool.description + "\u0000" + tool.inputSchema.toString())
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                    .joinToString("") { "%02x".format(it) },
            )
        }
        .toList()

    fun findAllowed(name: String, mode: AgentMode): AgentTool? = find(name)?.takeIf { isAllowed(it, mode) }

    fun isAllowed(tool: AgentTool, mode: AgentMode): Boolean = when (mode) {
        AgentMode.AGENT -> true
        AgentMode.PLAN -> tool.effect == ToolEffect.READ_ONLY
        AgentMode.CLAUDE_PLAN -> tool.effect == ToolEffect.READ_ONLY || tool === runCommandTool
        AgentMode.RESEARCH -> tool.effect == ToolEffect.READ_ONLY || tool.effect == ToolEffect.COMMAND
    }
}
