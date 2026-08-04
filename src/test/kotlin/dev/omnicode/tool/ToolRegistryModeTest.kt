package dev.omnicode.tool

import com.google.gson.JsonObject
import dev.omnicode.agent.AgentMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolRegistryModeTest {
    @Test
    fun `claude plan exposes its constrained command runner but not arbitrary command tools`() {
        val runCommand = StubTool("run_command", ToolEffect.COMMAND)
        val arbitraryCommand = StubTool("arbitrary_command", ToolEffect.COMMAND)
        val readOnly = StubTool("inspect", ToolEffect.READ_ONLY)
        val registry = ToolRegistry(
            runCommandTool = runCommand,
            additionalTools = listOf(arbitraryCommand, readOnly),
        )

        val claudePlanNames = registry.definitionsFor(AgentMode.CLAUDE_PLAN).mapTo(mutableSetOf()) { it.name }
        assertTrue("run_command" in claudePlanNames)
        assertTrue("inspect" in claudePlanNames)
        assertFalse("arbitrary_command" in claudePlanNames)
        assertTrue(registry.findAllowed("run_command", AgentMode.CLAUDE_PLAN) === runCommand)

        assertFalse("run_command" in registry.definitionsFor(AgentMode.PLAN).map { it.name })
        assertTrue("run_command" in registry.definitionsFor(AgentMode.RESEARCH).map { it.name })
        AgentMode.entries.forEach { mode ->
            assertTrue("inspect_project_harness" in registry.definitionsFor(mode).map { it.name })
        }
    }

    @Test
    fun `definitions and schema token estimates are cached for a registry lifetime`() {
        val registry = ToolRegistry()

        assertSame(
            registry.definitionsFor(AgentMode.AGENT),
            registry.definitionsFor(AgentMode.AGENT),
        )
        val first = registry.estimatedDefinitionTokensFor(AgentMode.AGENT)
        assertTrue(first > 0)
        assertTrue(first == registry.estimatedDefinitionTokensFor(AgentMode.AGENT))
    }

    private class StubTool(
        override val name: String,
        override val effect: ToolEffect,
    ) : AgentTool {
        override val description: String = name
        override val inputSchema: JsonObject = JsonObject()
        override val dangerous: Boolean = effect != ToolEffect.READ_ONLY

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolExecutionResult = ToolExecutionResult(name)
    }
}
