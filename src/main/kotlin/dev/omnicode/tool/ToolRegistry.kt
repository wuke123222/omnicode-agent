package dev.omnicode.tool

import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ToolDefinition
import dev.omnicode.util.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
    private val definitionsCache = ConcurrentHashMap<DefinitionCacheKey, List<ToolDefinition>>()
    private val definitionTokensCache = ConcurrentHashMap<DefinitionCacheKey, Long>()

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

    fun definitionsFor(mode: AgentMode, safeOnly: Boolean = false): List<ToolDefinition> {
        val key = DefinitionCacheKey(mode, safeOnly)
        return definitionsCache.computeIfAbsent(key) {
            tools
                .asSequence()
                .filter { isAllowed(it, mode) }
                .filter { !safeOnly || !it.dangerous }
                .map(AgentTool::definition)
                .toList()
        }
    }

    /**
     * Tool schemas are immutable for a registry lifetime. Keep the expensive JSON schema
     * serialization out of every AgentEngine turn; MCP-heavy registries otherwise pay this cost
     * once for each model request even though the provider payload is unchanged.
     */
    fun estimatedDefinitionTokensFor(mode: AgentMode, safeOnly: Boolean = false): Long {
        val key = DefinitionCacheKey(mode, safeOnly)
        return definitionTokensCache.computeIfAbsent(key) {
            val serializedChars = definitionsFor(mode, safeOnly).sumOf { tool ->
                tool.name.length.toLong() +
                    tool.description.length +
                    Json.stringify(tool.inputSchema).length +
                    TOOL_DEFINITION_ENVELOPE_CHARS
            }
            (serializedChars + ESTIMATED_CHARS_PER_TOKEN - 1) / ESTIMATED_CHARS_PER_TOKEN
        }
    }

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

    private data class DefinitionCacheKey(val mode: AgentMode, val safeOnly: Boolean)

    private companion object {
        const val ESTIMATED_CHARS_PER_TOKEN = 4L
        const val TOOL_DEFINITION_ENVELOPE_CHARS = 96L
    }
}
