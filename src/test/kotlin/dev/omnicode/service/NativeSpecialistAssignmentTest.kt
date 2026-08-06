package dev.omnicode.service

import dev.omnicode.agent.AgentRole
import dev.omnicode.provider.CodexNativeSubagentEvent
import dev.omnicode.tool.SpecialistTaskRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeSpecialistAssignmentTest {
    @Test
    fun `native child prompt wins over completion order`() {
        val explorer = request("explorer", AgentRole.EXPLORER, "Inspect the indexing path")
        val reviewer = request("reviewer", AgentRole.REVIEWER, "Review the approval boundary")

        val selected = chooseNativeSpecialistRequest(
            requests = listOf(explorer, reviewer),
            event = CodexNativeSubagentEvent(
                threadId = "thr-review",
                status = "running",
                prompt = "Review the approval boundary and report exact evidence.",
            ),
            alreadyAssigned = emptySet(),
        )

        assertEquals("reviewer", selected.agentId)
    }

    @Test
    fun `assignment falls back to first unassigned task when prompt has no hint`() {
        val explorer = request("explorer", AgentRole.EXPLORER, "Inspect the indexing path")
        val reviewer = request("reviewer", AgentRole.REVIEWER, "Review the approval boundary")

        val selected = chooseNativeSpecialistRequest(
            requests = listOf(explorer, reviewer),
            event = CodexNativeSubagentEvent(threadId = "thr-unknown", status = "running"),
            alreadyAssigned = setOf(explorer),
        )

        assertEquals("reviewer", selected.agentId)
    }

    private fun request(id: String, role: AgentRole, objective: String) = SpecialistTaskRequest(
        workflowId = "workflow",
        delegationId = "delegation",
        agentId = id,
        parentAgentId = "lead",
        role = role,
        roleName = id,
        objective = objective,
        originalGoal = "goal",
    )
}
