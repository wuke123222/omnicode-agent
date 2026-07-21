package dev.omnicode.plan

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.omnicode.agent.AgentMode
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        service.applyReviewAction(PlanReviewAction.APPROVE_AUTO)
        service.startExecution(assertNotNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT)))
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
        service.applyReviewAction(PlanReviewAction.APPROVE_AUTO)
        service.startExecution(assertNotNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT)))
        service.pauseRunning()

        service.markCompleted(board.steps.first().id)

        assertEquals(PlanStepState.COMPLETED, service.snapshot()!!.steps.first().state)
    }

    @Test
    fun `selected steps cannot run before an explicit current review approval`() {
        val service = PlanBoardService(project())
        val board = service.replaceFromPlan(
            "- [ ] Inspect code\n- [ ] Implement change",
            AgentMode.CLAUDE_PLAN,
        )
        assertTrue(service.approve(board.steps.first().id, true))

        assertEquals(PlanReviewDecision.PENDING, service.snapshot()!!.effectiveReviewDecision)
        assertNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT))
        assertEquals(PlanStepState.APPROVED, service.snapshot()!!.steps.first().state)
    }

    @Test
    fun `manual approval grants exactly one confirmed step at a time`() {
        val service = PlanBoardService(project())
        val board = service.replaceFromPlan(
            "- [ ] Inspect code\n- [ ] Implement change",
            AgentMode.CLAUDE_PLAN,
        )
        service.approveAll()
        assertTrue(service.applyReviewAction(PlanReviewAction.APPROVE_MANUAL))

        val firstRequest = assertNotNull(service.requestExecution(PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION))
        assertEquals(board.steps.first().id, firstRequest.stepId)
        assertTrue(service.isExecutionAuthorized(firstRequest))
        assertNull(service.startExecution(firstRequest.copy(stepId = board.steps.last().id)))
        assertEquals(firstRequest.stepId, assertNotNull(service.startExecution(firstRequest)).id)
        assertFalse(service.isExecutionAuthorized(firstRequest))
        service.markCompleted(firstRequest.stepId!!)

        assertNull(service.nextApprovedStep(), "the next manual step needs another user confirmation")
        val secondRequest = assertNotNull(service.requestExecution(PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION))
        assertEquals(board.steps.last().id, secondRequest.stepId)
    }

    @Test
    fun `automatic approval authorizes the selected queue for the reviewed revision`() {
        val service = PlanBoardService(project())
        val board = service.replaceFromPlan(
            "- [ ] Inspect code\n- [ ] Implement change",
            AgentMode.PLAN,
        )
        service.approve(board.steps.last().id, true)
        assertTrue(service.applyReviewAction(PlanReviewAction.APPROVE_AUTO))

        val request = assertNotNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT))
        assertNull(request.stepId)
        assertTrue(service.isExecutionAuthorized(request))
        assertEquals(board.steps.last().id, service.nextApprovedStep()?.id)
        assertEquals(board.steps.last().id, assertNotNull(service.startExecution(request)).id)
        assertNull(service.startExecution(request))
        assertNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT))
    }

    @Test
    fun `editing a reviewed plan invalidates its decision and stale request`() {
        val service = PlanBoardService(project())
        val board = service.replaceFromPlan(
            "- [ ] Inspect code\n- [ ] Implement change",
            AgentMode.CLAUDE_PLAN,
        )
        service.approveAll()
        service.applyReviewAction(PlanReviewAction.APPROVE_AUTO)
        val request = assertNotNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT))
        val reviewedRevision = service.snapshot()!!.revision

        assertTrue(service.updateStepText(board.steps.last().id, "Implement a safer change"))

        val revised = service.snapshot()!!
        assertTrue(revised.revision > reviewedRevision)
        assertEquals(PlanReviewDecision.PENDING, revised.effectiveReviewDecision)
        assertEquals(PlanExecutionPolicy.NONE, revised.executionPolicy)
        assertEquals(
            PlanStepState.APPROVED,
            revised.steps.last().state,
            "editing keeps the user's step selection but requires a new plan-level approval",
        )
        assertFalse(service.isExecutionAuthorized(request))
        assertNull(service.startExecution(request))
    }

    @Test
    fun `continue and reject decisions remain non executable and observable`() {
        val service = PlanBoardService(project())
        val observed = mutableListOf<PlanReviewDecision>()
        val parent = Disposer.newDisposable()
        service.addListener(parent) { value -> value?.let { observed += it.effectiveReviewDecision } }
        val board = service.replaceFromPlan("- [ ] Inspect\n- [ ] Report", AgentMode.CLAUDE_PLAN)
        service.approve(board.steps.first().id, true)

        assertTrue(service.applyReviewAction(PlanReviewAction.CONTINUE_PLANNING))
        assertEquals(PlanReviewDecision.CONTINUE_PLANNING, observed.last())
        assertNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT))
        assertTrue(service.applyReviewAction(PlanReviewAction.REJECT_AND_KEEP_PLANNING))
        assertEquals(PlanReviewDecision.REJECTED, observed.last())
        assertNull(service.requestExecution(PlanExecutionPolicy.AUTO_AGENT))
        Disposer.dispose(parent)
    }

    @Test
    fun `review decision persists while manual one step permit does not`() {
        val service = PlanBoardService(project())
        service.replaceFromPlan("- [ ] Inspect\n- [ ] Report", AgentMode.CLAUDE_PLAN)
        service.approveAll()
        service.applyReviewAction(PlanReviewAction.APPROVE_MANUAL)
        val request = assertNotNull(service.requestExecution(PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION))
        assertTrue(service.isExecutionAuthorized(request))

        val restored = PlanBoardService(project())
        restored.loadState(service.state)

        val snapshot = restored.snapshot()!!
        assertEquals(PlanReviewDecision.APPROVED_MANUAL, snapshot.effectiveReviewDecision)
        assertEquals(PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION, snapshot.executionPolicy)
        assertNull(snapshot.manualConfirmedStepId)
        assertFalse(restored.isExecutionAuthorized(request))
    }

    @Test
    fun `approval is rejected when no steps were selected`() {
        val service = PlanBoardService(project())
        service.replaceFromPlan("- [ ] Inspect\n- [ ] Report", AgentMode.PLAN)

        assertFalse(service.applyReviewAction(PlanReviewAction.APPROVE_AUTO))
        assertEquals(PlanReviewDecision.PENDING, service.snapshot()!!.effectiveReviewDecision)
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
