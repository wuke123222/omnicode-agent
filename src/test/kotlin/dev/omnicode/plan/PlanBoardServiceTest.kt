package dev.omnicode.plan

import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanBoardServiceTest {
    @Test
    fun `parser prefers editable checklist steps and keeps bounded details`() {
        val parsed = PlanDocumentParser.parse(
            """
            # Authentication migration

            Notes before the final plan.

            - [ ] Add token rotation
              - Files: src/Auth.kt
              - Validate: AuthTest
            - [ ] Update session recovery
              - Files: src/Session.kt
            - [ ] Run focused tests
            """.trimIndent(),
        )

        assertEquals("Authentication migration", parsed.title)
        assertEquals(3, parsed.steps.size)
        assertTrue(parsed.steps.first().text.contains("src/Auth.kt"))
    }

    @Test
    fun `parser accepts numbered Claude style plan`() {
        val parsed = PlanDocumentParser.parse(
            """
            ## Proposed approach
            1. Inspect the current API boundary
            2. Implement the smallest compatible change
            3. Validate the regression suite
            """.trimIndent(),
        )

        assertEquals(3, parsed.steps.size)
        assertEquals("Inspect the current API boundary", parsed.steps.first().text)
    }

    @Test
    fun `parser preserves completed checklist markers`() {
        val parsed = PlanDocumentParser.parse(
            """
            - [x] Inspect the current boundary
            - [ ] Implement the approved change
            """.trimIndent(),
        )

        assertTrue(parsed.steps.first().completed)
        assertEquals(false, parsed.steps.last().completed)
    }

    @Test
    fun `fingerprint changes with plan content`() {
        assertNotEquals(planFingerprint("first"), planFingerprint("second"))
        assertEquals(planFingerprint("first"), planFingerprint("first"))
    }

    @Test
    fun `continued planning preserves completed and skipped decisions without auto approving new work`() {
        val service = PlanBoardService(project())
        val original = service.replaceFromPlan(
            """
            - [ ] Inspect the current boundary
            - [ ] Update the implementation
            - [ ] Run focused tests
            """.trimIndent(),
            AgentMode.CLAUDE_PLAN,
        )
        service.approve(original.steps[0].id, true)
        service.markRunning(original.steps[0].id)
        service.markCompleted(original.steps[0].id)
        service.skip(original.steps[1].id)

        val revised = service.replaceFromPlan(
            """
            - [ ] Inspect the current boundary
            - [ ] Update the implementation
            - [ ] Add a regression test
            """.trimIndent(),
            AgentMode.CLAUDE_PLAN,
            preserveFromBoardId = original.id,
        )

        assertEquals(original.id, revised.id)
        assertEquals(PlanStepState.COMPLETED, revised.steps[0].state)
        assertEquals(PlanStepState.SKIPPED, revised.steps[1].state)
        assertEquals(PlanStepState.DRAFT, revised.steps[2].state)
    }

    @Test
    fun `a completion racing with pause wins over paused state`() {
        val service = PlanBoardService(project())
        val board = service.replaceFromPlan(
            "- [ ] First step\n- [ ] Second step",
            AgentMode.PLAN,
        )
        service.approve(board.steps.first().id, true)
        service.markRunning(board.steps.first().id)
        service.pauseRunning()

        service.markCompleted(board.steps.first().id)

        assertEquals(PlanStepState.COMPLETED, service.snapshot()!!.steps.first().state)
    }

    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "isDisposed" -> false
            "getName" -> "test"
            "toString" -> "PlanBoardTestProject"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    } as Project
}
