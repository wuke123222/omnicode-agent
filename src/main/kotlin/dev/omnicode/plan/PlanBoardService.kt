package dev.omnicode.plan

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.omnicode.agent.AgentMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class PlanStepState {
    DRAFT,
    APPROVED,
    SKIPPED,
    RUNNING,
    COMPLETED,
    FAILED,
    PAUSED,
}

/** The durable result of reviewing the current plan revision. */
enum class PlanReviewDecision {
    PENDING,
    CONTINUE_PLANNING,
    APPROVED_MANUAL,
    APPROVED_AUTO,
    REJECTED,
}

/** How an approved plan may cross the read-only planning boundary. */
enum class PlanExecutionPolicy {
    NONE,
    MANUAL_STEP_CONFIRMATION,
    AUTO_AGENT,
}

enum class PlanReviewAction {
    CONTINUE_PLANNING,
    APPROVE_MANUAL,
    APPROVE_AUTO,
    REJECT_AND_KEEP_PLANNING,
}

data class PlanExecutionRequest(
    val boardId: String,
    val reviewRevision: Long,
    val policy: PlanExecutionPolicy,
    /** Present for manual execution so callers cannot silently substitute another step. */
    val stepId: String? = null,
)

data class PlanStep(
    val id: String,
    val text: String,
    val state: PlanStepState = PlanStepState.DRAFT,
    val attempts: Int = 0,
    val lastError: String = "",
)

data class PlanBoard(
    val id: String,
    val title: String,
    val sourceMode: AgentMode,
    val sourceFingerprint: String,
    val sourceText: String,
    val steps: List<PlanStep>,
    val revision: Long,
    val reviewDecision: PlanReviewDecision,
    val reviewRevision: Long,
    val reviewedAt: Instant?,
    /** A deliberately transient one-step permit. It is never restored after an IDE restart. */
    val manualConfirmedStepId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val hasRunningStep: Boolean get() = steps.any { it.state == PlanStepState.RUNNING }
    val approvedCount: Int get() = steps.count { it.state == PlanStepState.APPROVED }
    val completedCount: Int get() = steps.count { it.state == PlanStepState.COMPLETED }
    val effectiveReviewDecision: PlanReviewDecision
        get() = reviewDecision.takeIf { reviewRevision == revision } ?: PlanReviewDecision.PENDING
    val executionPolicy: PlanExecutionPolicy
        get() = when (effectiveReviewDecision) {
            PlanReviewDecision.APPROVED_MANUAL -> PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION
            PlanReviewDecision.APPROVED_AUTO -> PlanExecutionPolicy.AUTO_AGENT
            else -> PlanExecutionPolicy.NONE
        }
}

class PlanStepPersistentState {
    var id: String = ""
    var text: String = ""
    var state: String = PlanStepState.DRAFT.name
    var attempts: Int = 0
    var lastError: String = ""
}

class PlanBoardPersistentState {
    var id: String = ""
    var title: String = ""
    var sourceMode: String = AgentMode.PLAN.name
    var sourceFingerprint: String = ""
    var sourceText: String = ""
    var createdAtEpochMillis: Long = 0L
    var updatedAtEpochMillis: Long = 0L
    var revision: Long = 1L
    var reviewDecision: String = PlanReviewDecision.PENDING.name
    var reviewRevision: Long = 0L
    var reviewedAtEpochMillis: Long = 0L
    var steps: MutableList<PlanStepPersistentState> = mutableListOf()
}

@Service(Service.Level.PROJECT)
@State(
    name = "OmniCodePlanBoard",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class PlanBoardService(private val project: Project) : PersistentStateComponent<PlanBoardPersistentState> {
    @Volatile
    private var current: PlanBoard? = null
    private val listeners = CopyOnWriteArrayList<(PlanBoard?) -> Unit>()

    override fun getState(): PlanBoardPersistentState = synchronized(this) {
        current?.toPersistentState() ?: PlanBoardPersistentState()
    }

    override fun loadState(state: PlanBoardPersistentState) {
        synchronized(this) {
            current = state.toBoardOrNull()
        }
        notifyListeners()
    }

    fun snapshot(): PlanBoard? = synchronized(this) { current?.copy(steps = current!!.steps.toList()) }

    fun replaceFromPlan(
        planText: String,
        mode: AgentMode,
        preserveFromBoardId: String? = null,
    ): PlanBoard {
        require(mode == AgentMode.PLAN || mode == AgentMode.CLAUDE_PLAN) {
            "A plan board can only originate from a planning mode"
        }
        val parsed = PlanDocumentParser.parse(planText)
        val now = Instant.now()
        val previous = synchronized(this) { current?.takeIf { it.id == preserveFromBoardId } }
        val unusedPreviousSteps = previous?.steps?.toMutableList().orEmpty().toMutableList()
        val mergedSteps = parsed.steps.mapIndexed { index, parsedStep ->
            val previousStep = unusedPreviousSteps.firstOrNull { it.text == parsedStep.text }
                ?.also(unusedPreviousSteps::remove)
            val preservedState = when {
                parsedStep.completed -> PlanStepState.COMPLETED
                previousStep?.state == PlanStepState.COMPLETED -> PlanStepState.COMPLETED
                previousStep?.state == PlanStepState.SKIPPED -> PlanStepState.SKIPPED
                else -> PlanStepState.DRAFT
            }
            PlanStep(
                id = previousStep?.id ?: stableStepId(planText, index, parsedStep.text),
                text = parsedStep.text,
                state = preservedState,
                attempts = previousStep?.attempts ?: 0,
            )
        }
        val board = PlanBoard(
            id = previous?.id ?: UUID.randomUUID().toString(),
            title = parsed.title,
            sourceMode = mode,
            sourceFingerprint = planFingerprint(planText),
            sourceText = planText.take(MAX_PLAN_SOURCE_CHARS),
            steps = mergedSteps,
            revision = nextRevision(previous?.revision ?: 0L),
            reviewDecision = PlanReviewDecision.PENDING,
            reviewRevision = 0L,
            reviewedAt = null,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )
        setBoard(board)
        return board
    }

    fun clear() = setBoard(null)

    fun updateStepText(stepId: String, text: String): Boolean = mutatePlan { board ->
        board.copy(steps = board.steps.map { step ->
            if (step.id != stepId) return@map step
            val bounded = normalizeStepText(text)
            if (bounded.isBlank() || bounded == step.text || step.state == PlanStepState.RUNNING) step
            else step.copy(
                text = bounded,
                lastError = "",
            )
        })
    }

    fun approve(stepId: String, approved: Boolean): Boolean = mutatePlan { board ->
        board.copy(steps = board.steps.map { step ->
            if (step.id != stepId || step.state == PlanStepState.RUNNING || step.state == PlanStepState.COMPLETED) step
            else step.copy(
                state = if (approved) PlanStepState.APPROVED else PlanStepState.DRAFT,
                lastError = if (approved) "" else step.lastError,
            )
        })
    }

    fun approveAll(): Boolean = mutatePlan { board ->
        board.copy(steps = board.steps.map { step ->
            if (step.state == PlanStepState.DRAFT || step.state == PlanStepState.FAILED || step.state == PlanStepState.PAUSED) {
                step.copy(state = PlanStepState.APPROVED, lastError = "")
            } else step
        })
    }

    fun skip(stepId: String): Boolean = mutatePlan { board ->
        board.copy(steps = board.steps.map { step ->
            if (step.id != stepId || step.state == PlanStepState.RUNNING || step.state == PlanStepState.COMPLETED) step
            else step.copy(state = PlanStepState.SKIPPED, lastError = "")
        })
    }

    fun restore(stepId: String): Boolean = mutatePlan { board ->
        board.copy(steps = board.steps.map { step ->
            if (step.id == stepId && step.state in setOf(PlanStepState.SKIPPED, PlanStepState.FAILED, PlanStepState.PAUSED)) {
                step.copy(state = PlanStepState.DRAFT, lastError = "")
            } else step
        })
    }

    /** Records a user decision against exactly the current editable revision. */
    fun applyReviewAction(action: PlanReviewAction): Boolean = mutateBoard { board ->
        val decision = when (action) {
            PlanReviewAction.CONTINUE_PLANNING -> PlanReviewDecision.CONTINUE_PLANNING
            PlanReviewAction.APPROVE_MANUAL -> PlanReviewDecision.APPROVED_MANUAL
            PlanReviewAction.APPROVE_AUTO -> PlanReviewDecision.APPROVED_AUTO
            PlanReviewAction.REJECT_AND_KEEP_PLANNING -> PlanReviewDecision.REJECTED
        }
        if (decision in setOf(PlanReviewDecision.APPROVED_MANUAL, PlanReviewDecision.APPROVED_AUTO) && board.approvedCount == 0) {
            return@mutateBoard board
        }
        board.copy(
            reviewDecision = decision,
            reviewRevision = board.revision,
            reviewedAt = Instant.now(),
            manualConfirmedStepId = null,
        )
    }

    /**
     * Creates a bounded execution request after a durable approval decision. Manual mode grants
     * only the next approved step; auto mode grants the approved queue for this exact revision.
     */
    fun requestExecution(policy: PlanExecutionPolicy): PlanExecutionRequest? {
        var request: PlanExecutionRequest? = null
        mutateBoard { board ->
            if (policy == PlanExecutionPolicy.NONE || board.executionPolicy != policy || board.hasRunningStep) {
                return@mutateBoard board
            }
            val next = board.steps.firstOrNull { it.state == PlanStepState.APPROVED } ?: return@mutateBoard board
            request = PlanExecutionRequest(
                boardId = board.id,
                reviewRevision = board.reviewRevision,
                policy = policy,
                stepId = next.id.takeIf { policy == PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION },
            )
            if (policy == PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION) {
                board.copy(manualConfirmedStepId = next.id)
            } else {
                // The request itself is observable by the caller; no transient permit is needed.
                board
            }
        }
        return request
    }

    fun isExecutionAuthorized(request: PlanExecutionRequest): Boolean = snapshot()?.let { board ->
        board.authorizes(request)
    } == true

    /**
     * Atomically consumes a revision-bound execution request and starts exactly the step it grants.
     * This closes the gap between a UI preflight check and the actual DRAFT/APPROVED -> RUNNING
     * transition: an edit or a different review decision racing with the click fails closed.
     */
    fun startExecution(request: PlanExecutionRequest): PlanStep? {
        var started: PlanStep? = null
        mutateBoard { board ->
            if (!board.authorizes(request) || board.hasRunningStep) return@mutateBoard board
            val target = when (request.policy) {
                PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION -> board.steps.firstOrNull {
                    it.id == request.stepId && it.state == PlanStepState.APPROVED
                }
                PlanExecutionPolicy.AUTO_AGENT -> board.steps.firstOrNull { it.state == PlanStepState.APPROVED }
                PlanExecutionPolicy.NONE -> null
            } ?: return@mutateBoard board
            val running = target.copy(
                state = PlanStepState.RUNNING,
                attempts = target.attempts + 1,
                lastError = "",
            )
            started = running
            board.copy(
                steps = board.steps.map { step -> if (step.id == target.id) running else step },
                manualConfirmedStepId = null,
            )
        }
        return started
    }

    fun markCompleted(stepId: String): Boolean = mutateStep(stepId) { step ->
        if (step.state == PlanStepState.RUNNING || step.state == PlanStepState.PAUSED) {
            step.copy(state = PlanStepState.COMPLETED, lastError = "")
        } else step
    }

    fun markFailed(stepId: String, error: String): Boolean = mutateStep(stepId) { step ->
        if (step.state == PlanStepState.RUNNING) {
            step.copy(state = PlanStepState.FAILED, lastError = error.trim().take(MAX_PLAN_ERROR_CHARS))
        } else step
    }

    fun pauseRunning(): PlanStep? {
        var paused: PlanStep? = null
        mutateBoard { board ->
            board.copy(steps = board.steps.map { step ->
                if (step.state == PlanStepState.RUNNING) {
                    step.copy(state = PlanStepState.PAUSED).also { paused = it }
                } else step
            })
        }
        return paused
    }

    fun nextApprovedStep(): PlanStep? = snapshot()?.let { board ->
        when (board.executionPolicy) {
            PlanExecutionPolicy.NONE -> null
            PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION -> board.steps.firstOrNull {
                it.id == board.manualConfirmedStepId && it.state == PlanStepState.APPROVED
            }
            PlanExecutionPolicy.AUTO_AGENT -> board.steps.firstOrNull { it.state == PlanStepState.APPROVED }
        }
    }

    fun retry(stepId: String): Boolean = mutateStep(stepId) { step ->
        if (step.state == PlanStepState.FAILED || step.state == PlanStepState.PAUSED) {
            step.copy(state = PlanStepState.APPROVED, lastError = "")
        } else step
    }

    fun addListener(parent: Disposable, listener: (PlanBoard?) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun mutateStep(stepId: String, transform: (PlanStep) -> PlanStep): Boolean = mutateBoard { board ->
        board.copy(steps = board.steps.map { step -> if (step.id == stepId) transform(step) else step })
    }

    private fun PlanBoard.authorizes(request: PlanExecutionRequest): Boolean =
        id == request.boardId &&
            reviewRevision == request.reviewRevision &&
            executionPolicy == request.policy &&
            when (request.policy) {
                PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION ->
                    request.stepId != null && manualConfirmedStepId == request.stepId
                PlanExecutionPolicy.AUTO_AGENT -> request.stepId == null && approvedCount > 0
                PlanExecutionPolicy.NONE -> false
            }

    private fun mutatePlan(transform: (PlanBoard) -> PlanBoard): Boolean = mutateBoard { board ->
        val transformed = transform(board)
        if (transformed == board) board
        else transformed.copy(
            revision = nextRevision(board.revision),
            reviewDecision = PlanReviewDecision.PENDING,
            reviewRevision = 0L,
            reviewedAt = null,
            manualConfirmedStepId = null,
        )
    }

    private fun mutateBoard(transform: (PlanBoard) -> PlanBoard): Boolean {
        val changed = synchronized(this) {
            val before = current ?: return false
            val transformed = transform(before)
            if (transformed == before) return false
            current = transformed.copy(updatedAt = Instant.now())
            true
        }
        if (changed) notifyListeners()
        return changed
    }

    private fun setBoard(board: PlanBoard?) {
        synchronized(this) { current = board }
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = snapshot()
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private fun PlanBoard.toPersistentState(): PlanBoardPersistentState = PlanBoardPersistentState().also { state ->
        state.id = id
        state.title = title
        state.sourceMode = sourceMode.name
        state.sourceFingerprint = sourceFingerprint
        state.sourceText = sourceText.take(MAX_PLAN_SOURCE_CHARS)
        state.createdAtEpochMillis = createdAt.toEpochMilli()
        state.updatedAtEpochMillis = updatedAt.toEpochMilli()
        state.revision = revision
        state.reviewDecision = reviewDecision.name
        state.reviewRevision = reviewRevision
        state.reviewedAtEpochMillis = reviewedAt?.toEpochMilli() ?: 0L
        state.steps = steps.take(MAX_PLAN_STEPS).map { step ->
            PlanStepPersistentState().also { value ->
                value.id = step.id
                value.text = normalizeStepText(step.text)
                value.state = step.state.name
                value.attempts = step.attempts.coerceIn(0, MAX_PLAN_ATTEMPTS)
                value.lastError = step.lastError.take(MAX_PLAN_ERROR_CHARS)
            }
        }.toMutableList()
    }

    private fun PlanBoardPersistentState.toBoardOrNull(): PlanBoard? {
        if (id.isBlank() || steps.isEmpty()) return null
        val safeSteps = steps.asSequence().take(MAX_PLAN_STEPS).mapNotNull { value ->
            val text = normalizeStepText(value.text)
            if (value.id.isBlank() || text.isBlank()) return@mapNotNull null
            PlanStep(
                id = value.id.take(128),
                text = text,
                state = runCatching { PlanStepState.valueOf(value.state) }.getOrDefault(PlanStepState.DRAFT)
                    .takeUnless { it == PlanStepState.RUNNING }
                    ?: PlanStepState.PAUSED,
                attempts = value.attempts.coerceIn(0, MAX_PLAN_ATTEMPTS),
                lastError = value.lastError.take(MAX_PLAN_ERROR_CHARS),
            )
        }.toList()
        if (safeSteps.isEmpty()) return null
        val created = createdAtEpochMillis.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: Instant.now()
        val updated = updatedAtEpochMillis.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: created
        val safeRevision = revision.coerceAtLeast(1L)
        val safeDecision = runCatching { PlanReviewDecision.valueOf(reviewDecision) }
            .getOrDefault(PlanReviewDecision.PENDING)
        val safeReviewRevision = reviewRevision.takeIf { it == safeRevision } ?: 0L
        return PlanBoard(
            id = id.take(128),
            title = title.trim().ifBlank { "实施计划" }.take(MAX_PLAN_TITLE_CHARS),
            sourceMode = runCatching { AgentMode.valueOf(sourceMode) }
                .getOrDefault(AgentMode.PLAN)
                .takeIf { it == AgentMode.PLAN || it == AgentMode.CLAUDE_PLAN }
                ?: AgentMode.PLAN,
            sourceFingerprint = sourceFingerprint.take(64),
            sourceText = sourceText.take(MAX_PLAN_SOURCE_CHARS),
            steps = safeSteps,
            revision = safeRevision,
            reviewDecision = safeDecision.takeIf { safeReviewRevision == safeRevision } ?: PlanReviewDecision.PENDING,
            reviewRevision = safeReviewRevision,
            reviewedAt = reviewedAtEpochMillis.takeIf { it > 0 && safeReviewRevision == safeRevision }
                ?.let(Instant::ofEpochMilli),
            manualConfirmedStepId = null,
            createdAt = created,
            updatedAt = maxOf(created, updated),
        )
    }

    companion object {
        fun getInstance(project: Project): PlanBoardService = project.getService(PlanBoardService::class.java)
    }
}

internal data class ParsedPlanStep(val text: String, val completed: Boolean = false)
internal data class ParsedPlanDocument(val title: String, val steps: List<ParsedPlanStep>)

internal object PlanDocumentParser {
    private val CHECKLIST = Regex("^\\s*[-*]\\s*\\[([ xX])]\\s+(.+?)\\s*$")
    private val NUMBERED = Regex("^\\s*\\d{1,2}[.)]\\s+(.+?)\\s*$")
    private val HEADING = Regex("^#{1,3}\\s+(.+?)\\s*$")

    fun parse(value: String): ParsedPlanDocument {
        val bounded = value.replace("\u0000", "").take(MAX_PLAN_SOURCE_CHARS)
        val lines = bounded.lines()
        val title = lines.asSequence()
            .mapNotNull { HEADING.matchEntire(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            ?.take(MAX_PLAN_TITLE_CHARS)
            ?: "实施计划"
        val checklist = collectSteps(lines, CHECKLIST, textGroup = 2, completionGroup = 1)
        val numbered = if (checklist.size >= 2) emptyList() else collectSteps(lines, NUMBERED)
        val steps = (checklist.takeIf { it.size >= 2 } ?: numbered.takeIf { it.size >= 2 })
            ?.take(MAX_PLAN_STEPS)
            ?: listOf(ParsedPlanStep(fallbackStep(bounded)))
        return ParsedPlanDocument(title, steps)
    }

    private fun collectSteps(
        lines: List<String>,
        marker: Regex,
        textGroup: Int = 1,
        completionGroup: Int? = null,
    ): List<ParsedPlanStep> {
        val result = mutableListOf<ParsedPlanStep>()
        var current: StringBuilder? = null
        var completed = false
        fun flush() {
            val text = current?.toString()?.let(::normalizeStepText).orEmpty()
            if (text.isNotBlank()) result += ParsedPlanStep(text, completed)
        }
        lines.forEach { line ->
            val match = marker.matchEntire(line)
            if (match != null) {
                flush()
                current = StringBuilder(match.groupValues[textGroup])
                completed = completionGroup?.let { match.groupValues[it].equals("x", ignoreCase = true) } == true
            } else if (current != null && line.isNotBlank() && (line.firstOrNull()?.isWhitespace() == true || line.trimStart().startsWith("- "))) {
                if (current!!.length < MAX_PLAN_STEP_CHARS) current!!.append('\n').append(line.trim())
            }
        }
        flush()
        return result
    }

    private fun fallbackStep(value: String): String = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { HEADING.matches(it) }
        .take(6)
        .joinToString(" ")
        .let(::normalizeStepText)
        .ifBlank { "复核当前项目状态并完成用户批准的计划。" }
}

internal fun planFingerprint(value: String): String = sha256(value).take(16)

private fun stableStepId(plan: String, index: Int, text: String): String =
    "step-${sha256("$index\u0000${planFingerprint(plan)}\u0000$text").take(20)}"

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun normalizeStepText(value: String): String = value
    .replace("\u0000", "")
    .trim()
    .take(MAX_PLAN_STEP_CHARS)

private fun nextRevision(value: Long): Long = if (value >= Long.MAX_VALUE) 1L else value + 1L

private const val MAX_PLAN_STEPS = 20
private const val MAX_PLAN_STEP_CHARS = 4_000
private const val MAX_PLAN_SOURCE_CHARS = 64_000
private const val MAX_PLAN_TITLE_CHARS = 160
private const val MAX_PLAN_ERROR_CHARS = 1_000
private const val MAX_PLAN_ATTEMPTS = 100
