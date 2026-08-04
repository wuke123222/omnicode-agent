package dev.omnicode.harness

import com.google.gson.JsonObject
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentIdentity
import dev.omnicode.agent.AgentLimits
import dev.omnicode.agent.AgentMode
import dev.omnicode.tool.AgentTool
import dev.omnicode.tool.ToolEffect
import dev.omnicode.tool.ToolExecutionContext
import dev.omnicode.tool.ToolExecutionResult
import dev.omnicode.tool.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessPreflightTest {
    @Test
    fun `recovery preflight exposes only non-dangerous tools and has a stable surface digest`() {
        val registry = ToolRegistry()
        val spec = spec(recoveryRequiresReadOnly = true)

        val first = HarnessPreflight.inspect(spec, registry)
        val second = HarnessPreflight.inspect(spec, registry)

        assertEquals(HarnessPreflightStatus.DEGRADED_READ_ONLY, first.status)
        assertEquals(first.executionSurfaceDigest, second.executionSurfaceDigest)
        assertEquals(64, first.executionSurfaceDigest.length)
        assertFalse("run_command" in first.effectiveToolNames)
        assertFalse("apply_patch" in first.effectiveToolNames)
        assertTrue("inspect_project_harness" in first.effectiveToolNames)
    }

    @Test
    fun `registry rejects duplicate tool names before a provider can run`() {
        val duplicate = StubTool("same")

        val error = assertFailsWith<IllegalArgumentException> {
            ToolRegistry(additionalTools = listOf(duplicate, StubTool("same")))
        }

        assertTrue(error.message.orEmpty().contains("duplicate tool names"))
    }

    @Test
    fun `registry rejects an unclassified side effect`() {
        val registry = ToolRegistry(
            additionalTools = listOf(StubTool("unsafe", ToolEffect.EXTERNAL, dangerous = false)),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            HarnessPreflight.inspect(spec(), registry)
        }

        assertTrue(error.message.orEmpty().contains("non-read-only tools without approval classification"))
    }

    @Test
    fun `finite workflow treats resumed counters as the next attempt baseline`() {
        val report = HarnessPreflight.inspect(
            spec().copy(
                limits = AgentLimits(maxIterations = 1, maxToolCalls = 1),
                initialIteration = 12,
                initialToolCalls = 34,
            ),
            ToolRegistry(),
        )

        assertEquals(HarnessPreflightStatus.READY, report.status)
    }

    @Test
    fun `continuous workflow accepts resumed counters beyond legacy finite limits`() {
        val limits = AgentLimits(
            maxIterations = 1,
            maxToolCalls = 1,
            enforceWorkflowLimits = false,
        )

        val report = HarnessPreflight.inspect(
            spec().copy(
                limits = limits,
                initialIteration = 12,
                initialToolCalls = 34,
            ),
            ToolRegistry(),
        )

        assertEquals(HarnessPreflightStatus.READY, report.status)
        assertTrue(report.effectiveToolNames.isNotEmpty())
    }

    @Test
    fun `surface digest changes when a stopping policy changes`() {
        val registry = ToolRegistry()
        val base = HarnessPreflight.inspect(spec(), registry)
        val changed = HarnessPreflight.inspect(
            spec().copy(limits = AgentLimits(maxConsecutiveFailures = 4)),
            registry,
        )

        assertFalse(base.executionSurfaceDigest == changed.executionSurfaceDigest)
    }

    private fun spec(recoveryRequiresReadOnly: Boolean = false) = HarnessRunSpec(
        workflowId = "workflow",
        attemptId = "attempt",
        identity = AgentIdentity(),
        mode = AgentMode.AGENT,
        strategy = AgentExecutionStrategy.SINGLE,
        limits = AgentLimits(),
        recoveryRequiresReadOnly = recoveryRequiresReadOnly,
    )

    private class StubTool(
        override val name: String,
        override val effect: ToolEffect = ToolEffect.READ_ONLY,
        override val dangerous: Boolean = false,
    ) : AgentTool {
        override val description: String = name
        override val inputSchema: JsonObject = JsonObject()

        override suspend fun execute(
            arguments: JsonObject,
            context: ToolExecutionContext,
        ): ToolExecutionResult = ToolExecutionResult(name)
    }
}
