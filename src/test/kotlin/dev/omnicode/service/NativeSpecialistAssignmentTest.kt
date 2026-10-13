package dev.omnicode.service

import dev.omnicode.agent.AgentRole
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.provider.CodexNativeSubagentEvent
import dev.omnicode.tool.SpecialistTaskRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `cli team fallback creates bounded non overlapping assignments`() {
        val tasks = defaultTeamDelegationArguments(
            ConversationMessage(MessageRole.USER, "审阅跨模块登录流程并给出修复计划"),
        ).getAsJsonArray("tasks")

        assertEquals(3, tasks.size())
        assertEquals("explorer", tasks[0].asJsonObject.get("role").asString)
        assertEquals("reviewer", tasks[1].asJsonObject.get("role").asString)
        assertEquals("planner", tasks[2].asJsonObject.get("role").asString)
        assertTrue(tasks.all { it.asJsonObject.get("objective").asString.contains("用户请求") })
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
