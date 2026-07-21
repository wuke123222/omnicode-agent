package dev.omnicode.agent

import dev.omnicode.model.TokenUsage
import java.math.BigDecimal

/**
 * A workflow-scoped token and cost ledger shared by a lead agent and its delegates.
 *
 * Reservations make the hard limit deterministic when agents contact providers concurrently.
 * A successful provider response must be committed, while failures and cancellation must release
 * the reservation. Actual usage is always recorded, even when a provider reports more than was
 * reserved; the snapshot then reports [SharedAgentBudgetSnapshot.hardLimitExceeded] and rejects
 * every subsequent reservation.
 */
class SharedAgentBudgetLedger(
    val maxTotalTokens: Long = Long.MAX_VALUE,
    val maxInputTokens: Long = Long.MAX_VALUE,
    val maxOutputTokens: Long = Long.MAX_VALUE,
    val maxCostUsd: BigDecimal? = null,
    warningRatio: Double = 0.8,
    private val estimator: (TokenUsage) -> BigDecimal? = { null },
    private val agentEstimator: ((String, TokenUsage) -> BigDecimal?)? = null,
    initialUsage: TokenUsage = TokenUsage(),
) {
    private val warningThresholdUsd = maxCostUsd?.multiply(BigDecimal.valueOf(warningRatio))
    private val lock = Any()
    private val reservationOwner = Any()
    private val reservations = LinkedHashMap<Long, PendingReservation>()
    private val usageByAgent = LinkedHashMap<String, TokenUsage>().apply {
        if (initialUsage.inputTokens > 0 || initialUsage.outputTokens > 0) put("lead", initialUsage)
    }
    private var usage = initialUsage
    private var nextReservationId = 1L
    private var costWarningIssued = false
    private var usageOverflowed = false

    init {
        require(maxTotalTokens > 0) { "maxTotalTokens must be positive" }
        require(maxInputTokens > 0) { "maxInputTokens must be positive" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(initialUsage.inputTokens >= 0 && initialUsage.outputTokens >= 0) { "initialUsage must not be negative" }
        require(maxCostUsd == null || maxCostUsd.signum() > 0) { "maxCostUsd must be positive" }
        require(warningRatio in 0.0..1.0) { "warningRatio must be between 0 and 1" }
    }

    @Throws(SharedAgentBudgetExceededException::class)
    fun reserve(agentId: String, projectedUsage: TokenUsage): SharedAgentBudgetReservation = synchronized(lock) {
        requireValidAgentId(agentId)
        requireValidUsage(projectedUsage)

        val currentReserved = reservedUsageLocked()
        val currentAggregate = addUsage(usage, currentReserved)
        val projectedAggregate = addUsage(currentAggregate, projectedUsage)
        val inputExceeded = additionExceedsLimit(
            currentAggregate.inputTokens,
            projectedUsage.inputTokens,
            maxInputTokens,
        )
        val outputExceeded = additionExceedsLimit(
            currentAggregate.outputTokens,
            projectedUsage.outputTokens,
            maxOutputTokens,
        )
        val totalExceeded = usageSumExceedsLimit(maxTotalTokens, currentAggregate, projectedUsage)
        val projectedCost = if (inputExceeded || outputExceeded || totalExceeded) {
            null
        } else {
            estimate(
                projectedAggregate,
                projectedUsageByAgentLocked(agentId, projectedUsage),
            )
        }
        if (snapshotLocked().hardLimitExceeded ||
            inputExceeded ||
            outputExceeded ||
            totalExceeded ||
            (projectedCost != null && maxCostUsd != null && projectedCost > maxCostUsd)
        ) {
            throw SharedAgentBudgetExceededException(
                snapshot = snapshotLocked(),
                requestedUsage = projectedUsage,
                projectedUsage = projectedAggregate,
                projectedCostUsd = projectedCost,
                maxTotalTokens = maxTotalTokens,
                maxInputTokens = maxInputTokens,
                maxOutputTokens = maxOutputTokens,
                maxCostUsd = maxCostUsd,
            )
        }

        val id = nextReservationId++
        reservations[id] = PendingReservation(agentId, projectedUsage)
        val warning = costWarningLocked(projectedCost, projected = true)
        SharedAgentBudgetReservation(
            owner = reservationOwner,
            id = id,
            agentId = agentId,
            projectedUsage = projectedUsage,
            snapshot = snapshotLocked(),
            warning = warning,
        )
    }

    fun commit(
        reservation: SharedAgentBudgetReservation,
        actualUsage: TokenUsage,
    ): SharedAgentBudgetUpdate = synchronized(lock) {
        requireValidUsage(actualUsage)
        val pending = removeMatchingReservationLocked(reservation)
        usageOverflowed = usageOverflowed || usageAdditionOverflows(usage, actualUsage)
        usage = addUsage(usage, actualUsage)
        usageByAgent[pending.agentId] = addUsage(usageByAgent[pending.agentId] ?: TokenUsage(), actualUsage)
        val snapshot = snapshotLocked()
        SharedAgentBudgetUpdate(
            snapshot = snapshot,
            warning = costWarningLocked(snapshot.estimatedCostUsd, projected = false),
        )
    }

    fun release(reservation: SharedAgentBudgetReservation): SharedAgentBudgetSnapshot = synchronized(lock) {
        removeMatchingReservationLocked(reservation)
        snapshotLocked()
    }

    fun snapshot(): SharedAgentBudgetSnapshot = synchronized(lock) {
        snapshotLocked()
    }

    private fun removeMatchingReservationLocked(reservation: SharedAgentBudgetReservation): PendingReservation {
        check(reservation.owner === reservationOwner) { "Budget reservation belongs to another ledger" }
        val pending = reservations[reservation.id]
            ?: throw IllegalStateException("Budget reservation ${reservation.id} is no longer active")
        check(pending.agentId == reservation.agentId && pending.projectedUsage == reservation.projectedUsage) {
            "Budget reservation ${reservation.id} does not match the active reservation"
        }
        reservations.remove(reservation.id)
        return pending
    }

    private fun snapshotLocked(): SharedAgentBudgetSnapshot {
        val reserved = reservedUsageLocked()
        val projected = addUsage(usage, reserved)
        val committedCost = estimate(usage, usageByAgent)
        val projectedCost = estimate(projected, projectedUsageByAgentLocked())
        return SharedAgentBudgetSnapshot(
            usage = usage,
            reservedUsage = reserved,
            usageByAgent = usageByAgent.toMap(),
            estimatedCostUsd = committedCost,
            projectedCostUsd = projectedCost,
            activeReservations = reservations.size,
            hardLimitExceeded = usageOverflowed ||
                usage.inputTokens > maxInputTokens ||
                usage.outputTokens > maxOutputTokens ||
                usageSumExceedsLimit(maxTotalTokens, usage) ||
                (committedCost != null && maxCostUsd != null && committedCost > maxCostUsd),
        )
    }

    private fun reservedUsageLocked(): TokenUsage = reservations.values.fold(TokenUsage()) { total, reservation ->
        addUsage(total, reservation.projectedUsage)
    }

    private fun projectedUsageByAgentLocked(
        requestedAgentId: String? = null,
        requestedUsage: TokenUsage = TokenUsage(),
    ): Map<String, TokenUsage> {
        val projected = LinkedHashMap(usageByAgent)
        reservations.values.forEach { reservation ->
            projected[reservation.agentId] = addUsage(
                projected[reservation.agentId] ?: TokenUsage(),
                reservation.projectedUsage,
            )
        }
        requestedAgentId?.let { agentId ->
            projected[agentId] = addUsage(projected[agentId] ?: TokenUsage(), requestedUsage)
        }
        return projected
    }

    private fun costWarningLocked(cost: BigDecimal?, projected: Boolean): SharedAgentCostWarning? {
        val limit = maxCostUsd ?: return null
        val threshold = warningThresholdUsd ?: return null
        if (costWarningIssued || cost == null || cost < threshold) return null
        costWarningIssued = true
        return SharedAgentCostWarning(cost, limit, projected)
    }

    private fun estimate(value: TokenUsage, byAgent: Map<String, TokenUsage>): BigDecimal? {
        val perAgent = agentEstimator ?: return estimator(value)?.takeIf { it.signum() >= 0 }
        if (byAgent.isEmpty()) return perAgent("lead", value)?.takeIf { it.signum() >= 0 }
        var total = BigDecimal.ZERO
        byAgent.forEach { (agentId, usage) ->
            val cost = perAgent(agentId, usage)?.takeIf { it.signum() >= 0 } ?: return null
            total = total.add(cost)
        }
        return total
    }

    private data class PendingReservation(
        val agentId: String,
        val projectedUsage: TokenUsage,
    )
}

class SharedAgentBudgetReservation internal constructor(
    internal val owner: Any,
    internal val id: Long,
    val agentId: String,
    val projectedUsage: TokenUsage,
    val snapshot: SharedAgentBudgetSnapshot,
    val warning: SharedAgentCostWarning?,
)

data class SharedAgentBudgetSnapshot(
    val usage: TokenUsage,
    val reservedUsage: TokenUsage,
    val usageByAgent: Map<String, TokenUsage>,
    val estimatedCostUsd: BigDecimal?,
    val projectedCostUsd: BigDecimal?,
    val activeReservations: Int,
    val hardLimitExceeded: Boolean,
) {
    val aggregateUsage: TokenUsage get() = usage
}

data class SharedAgentBudgetUpdate(
    val snapshot: SharedAgentBudgetSnapshot,
    val warning: SharedAgentCostWarning?,
)

data class SharedAgentCostWarning(
    val estimatedCostUsd: BigDecimal,
    val maxCostUsd: BigDecimal,
    val projected: Boolean,
)

class SharedAgentBudgetExceededException(
    val snapshot: SharedAgentBudgetSnapshot,
    val requestedUsage: TokenUsage,
    val projectedUsage: TokenUsage,
    val projectedCostUsd: BigDecimal?,
    val maxTotalTokens: Long,
    val maxInputTokens: Long,
    val maxOutputTokens: Long,
    val maxCostUsd: BigDecimal?,
) : IllegalStateException(
    buildString {
        append("Shared agent budget rejected the provider reservation because ")
        val inputExceeded = projectedUsage.inputTokens > maxInputTokens
        val outputExceeded = projectedUsage.outputTokens > maxOutputTokens
        val tokenExceeded = projectedUsage.safeTotalTokens() > maxTotalTokens
        var hasReason = false
        if (inputExceeded) {
            append("the projected ${projectedUsage.inputTokens} input tokens exceed the workflow limit of $maxInputTokens")
            hasReason = true
        }
        if (outputExceeded) {
            if (hasReason) append(", ")
            append("the projected ${projectedUsage.outputTokens} output tokens exceed the workflow limit of $maxOutputTokens")
            hasReason = true
        }
        if (tokenExceeded) {
            if (hasReason) append(", ")
            append("the projected ")
            append(projectedUsage.safeTotalTokens())
            append(" tokens exceed the workflow limit of ")
            append(maxTotalTokens)
            hasReason = true
        }
        if (projectedCostUsd != null && maxCostUsd != null && projectedCostUsd > maxCostUsd) {
            if (hasReason) append(" and ")
            append("the projected cost $")
            append(projectedCostUsd.stripTrailingZeros().toPlainString())
            append(" exceeds $")
            append(maxCostUsd.stripTrailingZeros().toPlainString())
            hasReason = true
        }
        if (!hasReason) {
            append("the workflow hard limit has no remaining capacity")
        }
        append('.')
    },
)

private fun requireValidAgentId(agentId: String) {
    require(agentId.isNotBlank()) { "agentId must not be blank" }
    require(agentId.length <= MAX_AGENT_ID_CHARS) { "agentId exceeds $MAX_AGENT_ID_CHARS characters" }
}

private fun requireValidUsage(usage: TokenUsage) {
    require(usage.inputTokens >= 0) { "inputTokens must not be negative" }
    require(usage.outputTokens >= 0) { "outputTokens must not be negative" }
}

private fun addUsage(left: TokenUsage, right: TokenUsage): TokenUsage = TokenUsage(
    inputTokens = saturatingAdd(left.inputTokens, right.inputTokens),
    outputTokens = saturatingAdd(left.outputTokens, right.outputTokens),
)

private fun TokenUsage.safeTotalTokens(): Long = saturatingAdd(inputTokens, outputTokens)

private fun usageAdditionOverflows(left: TokenUsage, right: TokenUsage): Boolean =
    additionOverflows(left.inputTokens, right.inputTokens) ||
        additionOverflows(left.outputTokens, right.outputTokens)

private fun usageSumExceedsLimit(limit: Long, vararg usages: TokenUsage): Boolean {
    var remaining = limit
    usages.forEach { usage ->
        if (usage.inputTokens > remaining) return true
        remaining -= usage.inputTokens
        if (usage.outputTokens > remaining) return true
        remaining -= usage.outputTokens
    }
    return false
}

private fun additionExceedsLimit(left: Long, right: Long, limit: Long): Boolean =
    left > limit || right > limit - left

private fun additionOverflows(left: Long, right: Long): Boolean =
    right > Long.MAX_VALUE - left

private fun saturatingAdd(left: Long, right: Long): Long =
    if (additionOverflows(left, right)) Long.MAX_VALUE else left + right
