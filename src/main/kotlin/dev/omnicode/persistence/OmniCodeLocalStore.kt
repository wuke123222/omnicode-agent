package dev.omnicode.persistence

import com.intellij.openapi.application.PathManager
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight local persistence for non-sensitive usage, conversation snapshots,
 * and tool audit records. Free-form text is redacted and bounded before it reaches disk.
 */
class OmniCodeLocalStore(
    val storageRoot: Path,
    private val retention: PersistenceRetention = PersistenceRetention(),
    private val redactor: SensitiveDataRedactor = DefaultSensitiveDataRedactor(),
) {
    private val usageStore = BoundedJsonlStore(
        path = storageRoot.resolve(USAGE_FILE),
        recordType = UsageRecord::class.java,
        maxRecords = retention.maxUsageRecords,
        maxLineChars = USAGE_MAX_LINE_CHARS,
        maxFileBytes = USAGE_MAX_FILE_BYTES,
        idSelector = UsageRecord::id,
        sanitizer = ::sanitizeUsage,
        validator = ::isValidUsage,
    )
    private val conversationStore = BoundedJsonlStore(
        path = storageRoot.resolve(CONVERSATIONS_FILE),
        recordType = ConversationRecord::class.java,
        maxRecords = retention.maxConversations,
        maxLineChars = conversationMaxLineChars(),
        maxFileBytes = CONVERSATIONS_MAX_FILE_BYTES,
        idSelector = ConversationRecord::id,
        sanitizer = ::sanitizeConversation,
        validator = ::isValidConversation,
    )
    private val toolStore = BoundedJsonlStore(
        path = storageRoot.resolve(TOOL_EXECUTIONS_FILE),
        recordType = ToolExecutionRecord::class.java,
        maxRecords = retention.maxToolExecutionRecords,
        maxLineChars = toolMaxLineChars(),
        maxFileBytes = TOOL_EXECUTIONS_MAX_FILE_BYTES,
        idSelector = ToolExecutionRecord::id,
        sanitizer = ::sanitizeToolExecution,
        validator = ::isValidToolExecution,
    )
    private val workflowCheckpointStore = BoundedJsonlStore(
        path = storageRoot.resolve(WORKFLOW_CHECKPOINTS_FILE),
        recordType = WorkflowCheckpoint::class.java,
        maxRecords = retention.maxWorkflowCheckpoints,
        maxLineChars = checkpointMaxLineChars(),
        maxFileBytes = WORKFLOW_CHECKPOINTS_MAX_FILE_BYTES,
        idSelector = WorkflowCheckpoint::workflowId,
        sanitizer = ::sanitizeWorkflowCheckpoint,
        validator = ::isValidWorkflowCheckpoint,
    )
    private val usagePruneLock = Any()

    @Volatile
    private var lastUsagePruneDay: Long = Long.MIN_VALUE

    /** Returns false when the id already exists in the retained usage window. */
    fun recordUsage(record: UsageRecord): Boolean {
        pruneExpiredUsage()
        return usageStore.append(record)
    }

    /** Upserts cumulative usage for a replay-safe run id, such as a resumed workflow. */
    fun saveUsage(record: UsageRecord): UsageRecord {
        pruneExpiredUsage()
        val sanitized = sanitizeUsage(record)
        val records = usageStore.update { current ->
            (current.filterNot { it.id == sanitized.id } + sanitized)
                .sortedBy(UsageRecord::recordedAt)
        }
        return requireNotNull(records.firstOrNull { it.id == sanitized.id })
    }

    fun queryUsage(query: UsageQuery = UsageQuery()): List<UsageRecord> {
        pruneExpiredUsage()
        val limit = query.limit.coerceIn(0, retention.maxUsageRecords)
        if (limit == 0) return emptyList()
        val projectId = query.projectId?.let(::identifier)
        val workflowId = query.workflowId?.let(::identifier)
        val agentId = query.agentId?.let(::identifier)
        val providerId = query.providerId?.let(::identifier)
        val model = query.model?.let { safeText(it, MAX_IDENTIFIER_CHARS) }
        return usageStore.readAll()
            .asSequence()
            .filter { projectId == null || it.projectId == projectId }
            .filter { workflowId == null || it.workflowId == workflowId }
            .filter { agentId == null || it.agentId == agentId }
            .filter { providerId == null || it.providerId == providerId }
            .filter { model == null || it.model == model }
            .filter { query.fromInclusive == null || !it.recordedAt.isBefore(query.fromInclusive) }
            .filter { query.toExclusive == null || it.recordedAt.isBefore(query.toExclusive) }
            .filter { query.mode == null || it.mode == query.mode }
            .filter { query.strategy == null || it.strategy == query.strategy }
            .sortedByDescending(UsageRecord::recordedAt)
            .take(limit)
            .toList()
    }

    fun summarizeUsage(
        query: UsageQuery = UsageQuery(limit = retention.maxUsageRecords),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): UsageSummary {
        val records = queryUsage(query.copy(limit = retention.maxUsageRecords))
        val daily = records
            .groupBy { it.recordedAt.atZone(zoneId).toLocalDate() }
            .toSortedMap()
            .map { (date, dayRecords) ->
                DailyUsageSummary(
                    date = date,
                    runCount = dayRecords.size,
                    inputTokens = dayRecords.sumOf(UsageRecord::inputTokens),
                    outputTokens = dayRecords.sumOf(UsageRecord::outputTokens),
                    estimatedCostUsd = sumCost(dayRecords.mapNotNull(UsageRecord::estimatedCostUsd)),
                    pricedRunCount = dayRecords.count { it.estimatedCostUsd != null },
                )
            }
        return UsageSummary(
            runCount = records.size,
            inputTokens = records.sumOf(UsageRecord::inputTokens),
            outputTokens = records.sumOf(UsageRecord::outputTokens),
            estimatedCostUsd = sumCost(records.mapNotNull(UsageRecord::estimatedCostUsd)),
            pricedRunCount = records.count { it.estimatedCostUsd != null },
            daily = daily,
        )
    }

    /** Upserts one latest conversation snapshot and returns its sanitized persisted form. */
    fun saveConversation(record: ConversationRecord): ConversationRecord {
        val sanitized = sanitizeConversation(record)
        val records = conversationStore.update { current ->
            (current.filterNot { it.id == sanitized.id } + sanitized)
                .sortedBy(ConversationRecord::updatedAt)
        }
        return requireNotNull(records.firstOrNull { it.id == sanitized.id })
    }

    fun conversation(id: String): ConversationRecord? {
        val safeId = identifier(id)
        return conversationStore.readAll().firstOrNull { it.id == safeId }
    }

    fun conversations(
        projectId: String? = null,
        limit: Int = 50,
    ): List<ConversationRecord> {
        val boundedLimit = limit.coerceIn(0, retention.maxConversations)
        if (boundedLimit == 0) return emptyList()
        val safeProjectId = projectId?.let(::identifier)
        return conversationStore.readAll()
            .asSequence()
            .filter { safeProjectId == null || it.projectId == safeProjectId }
            .sortedByDescending(ConversationRecord::updatedAt)
            .take(boundedLimit)
            .toList()
    }

    fun deleteConversation(id: String): Boolean {
        val safeId = identifier(id)
        val removed = AtomicBoolean(false)
        conversationStore.update { current ->
            current.filterNot { record ->
                (record.id == safeId).also { matched ->
                    if (matched) removed.set(true)
                }
            }
        }
        return removed.get()
    }

    /** Returns false when this audit record id is already retained. */
    fun recordToolExecution(record: ToolExecutionRecord): Boolean = toolStore.append(record)

    fun queryToolExecutions(query: ToolExecutionQuery = ToolExecutionQuery()): List<ToolExecutionRecord> {
        val limit = query.limit.coerceIn(0, retention.maxToolExecutionRecords)
        if (limit == 0) return emptyList()
        val projectId = query.projectId?.let(::identifier)
        val conversationId = query.conversationId?.let(::identifier)
        val workflowId = query.workflowId?.let(::identifier)
        val agentId = query.agentId?.let(::identifier)
        val runId = query.runId?.let(::identifier)
        val executionId = query.executionId?.let(::identifier)
        val toolName = query.toolName?.let(::identifier)
        return toolStore.readAll()
            .asSequence()
            .filter { projectId == null || it.projectId == projectId }
            .filter { conversationId == null || it.conversationId == conversationId }
            .filter { workflowId == null || it.workflowId == workflowId }
            .filter { agentId == null || it.agentId == agentId }
            .filter { runId == null || it.runId == runId }
            .filter { executionId == null || it.executionId == executionId }
            .filter { toolName == null || it.toolName == toolName }
            .filter { query.status == null || it.status == query.status }
            .filter { query.fromInclusive == null || !it.recordedAt.isBefore(query.fromInclusive) }
            .filter { query.toExclusive == null || it.recordedAt.isBefore(query.toExclusive) }
            .filter { query.mode == null || it.mode == query.mode }
            .sortedByDescending(ToolExecutionRecord::recordedAt)
            .take(limit)
            .toList()
    }

    /** Atomically upserts the latest durable state for one workflow. */
    fun saveWorkflowCheckpoint(checkpoint: WorkflowCheckpoint): WorkflowCheckpoint {
        val sanitized = sanitizeWorkflowCheckpoint(checkpoint)
        val records = workflowCheckpointStore.update { current ->
            val existing = current.firstOrNull { it.workflowId == sanitized.workflowId }
            val retained = if (existing != null && existing.updatedAt.isAfter(sanitized.updatedAt)) {
                existing
            } else {
                sanitized
            }
            (current.filterNot { it.workflowId == sanitized.workflowId } + retained)
                .sortedBy(WorkflowCheckpoint::updatedAt)
        }
        return requireNotNull(records.firstOrNull { it.workflowId == sanitized.workflowId })
    }

    fun workflowCheckpoint(workflowId: String): WorkflowCheckpoint? {
        val safeWorkflowId = identifier(workflowId)
        return workflowCheckpointStore.readAll().firstOrNull { it.workflowId == safeWorkflowId }
    }

    /** Atomically mutates one existing workflow without replacing unrelated newer fields. */
    fun updateWorkflowCheckpoint(
        workflowId: String,
        transform: (WorkflowCheckpoint) -> WorkflowCheckpoint,
    ): WorkflowCheckpoint? {
        val safeWorkflowId = identifier(workflowId)
        val records = workflowCheckpointStore.update { current ->
            current.map { checkpoint ->
                if (checkpoint.workflowId != safeWorkflowId) return@map checkpoint
                transform(checkpoint).also { updated ->
                    require(updated.workflowId == checkpoint.workflowId && updated.runId == checkpoint.runId) {
                        "Workflow checkpoint identity cannot change during an atomic update"
                    }
                }
            }.sortedBy(WorkflowCheckpoint::updatedAt)
        }
        return records.firstOrNull { it.workflowId == safeWorkflowId }
    }

    fun workflowCheckpoints(
        projectId: String? = null,
        limit: Int = 50,
    ): List<WorkflowCheckpoint> {
        val boundedLimit = limit.coerceIn(0, retention.maxWorkflowCheckpoints)
        if (boundedLimit == 0) return emptyList()
        val safeProjectId = projectId?.let(::identifier)
        return workflowCheckpointStore.readAll()
            .asSequence()
            .filter { safeProjectId == null || it.projectId == safeProjectId }
            .sortedByDescending(WorkflowCheckpoint::updatedAt)
            .take(boundedLimit)
            .toList()
    }

    /** Includes INTERRUPTED checkpoints and legacy records whose state was not persisted. */
    fun unfinishedWorkflowCheckpoints(
        projectId: String? = null,
        limit: Int = 50,
    ): List<WorkflowCheckpoint> = workflowCheckpoints(projectId, retention.maxWorkflowCheckpoints)
        .asSequence()
        .filterNot(WorkflowCheckpoint::isTerminal)
        .take(limit.coerceIn(0, retention.maxWorkflowCheckpoints))
        .toList()

    /**
     * Converts every active checkpoint into an explicit recovery point. Repeated calls are
     * idempotent and return the number of records changed by this invocation.
     */
    fun markUnfinishedWorkflowCheckpointsInterrupted(
        projectId: String? = null,
        interruptedAt: Instant = Instant.now(),
    ): Int {
        val safeProjectId = projectId?.let(::identifier)
        var changed = 0
        workflowCheckpointStore.update { current ->
            current.map { checkpoint ->
                val belongsToProject = safeProjectId == null || checkpoint.projectId == safeProjectId
                if (belongsToProject && !checkpoint.isTerminal && checkpoint.state != WorkflowCheckpointState.INTERRUPTED) {
                    changed++
                    checkpoint.copy(
                        state = WorkflowCheckpointState.INTERRUPTED,
                        version = CURRENT_WORKFLOW_CHECKPOINT_VERSION,
                        updatedAt = maxOf(checkpoint.updatedAt, interruptedAt),
                    )
                } else {
                    checkpoint
                }
            }
        }
        return changed
    }

    fun deleteWorkflowCheckpoint(workflowId: String): Boolean {
        val safeWorkflowId = identifier(workflowId)
        val removed = AtomicBoolean(false)
        workflowCheckpointStore.update { current ->
            current.filterNot { checkpoint ->
                (checkpoint.workflowId == safeWorkflowId).also { matched ->
                    if (matched) removed.set(true)
                }
            }
        }
        return removed.get()
    }

    /**
     * Atomically removes and returns the exact non-terminal checkpoint the caller previously
     * presented to the user. A newer checkpoint with the same workflow ID is retained.
     */
    fun takeUnfinishedWorkflowCheckpoint(
        workflowId: String,
        expectedRunId: String,
        expectedUpdatedAt: Instant,
    ): WorkflowCheckpoint? {
        val safeWorkflowId = identifier(workflowId)
        val safeRunId = identifier(expectedRunId)
        var removed: WorkflowCheckpoint? = null
        workflowCheckpointStore.update { current ->
            current.filterNot { checkpoint ->
                val matches = removed == null &&
                    checkpoint.workflowId == safeWorkflowId &&
                    checkpoint.runId == safeRunId &&
                    checkpoint.updatedAt == expectedUpdatedAt &&
                    !checkpoint.isTerminal
                if (matches) removed = checkpoint
                matches
            }
        }
        return removed
    }

    /**
     * Atomically restores one checkpoint only while its workflow ID is absent. Unlike the normal
     * upsert path, this never replaces an older, equal-timestamp, or newer concurrent record.
     */
    fun restoreWorkflowCheckpointIfAbsent(checkpoint: WorkflowCheckpoint): Boolean {
        val sanitized = sanitizeWorkflowCheckpoint(checkpoint)
        var inserted = false
        val records = workflowCheckpointStore.update { current ->
            if (current.any { it.workflowId == sanitized.workflowId }) {
                current
            } else {
                inserted = true
                (current + sanitized).sortedBy(WorkflowCheckpoint::updatedAt)
            }
        }
        if (!inserted) return false
        check(records.any {
            it.workflowId == sanitized.workflowId &&
                it.runId == sanitized.runId &&
                it.updatedAt == sanitized.updatedAt
        }) { "Restored workflow checkpoint was rejected by bounded persistence" }
        return true
    }

    /** Deletes successful checkpoints while retaining failures for recovery and diagnostics. */
    fun deleteCompletedWorkflowCheckpoints(projectId: String? = null): Int {
        val safeProjectId = projectId?.let(::identifier)
        var removed = 0
        workflowCheckpointStore.update { current ->
            current.filterNot { checkpoint ->
                val matches = checkpoint.state == WorkflowCheckpointState.COMPLETED &&
                    (safeProjectId == null || checkpoint.projectId == safeProjectId)
                if (matches) removed++
                matches
            }
        }
        return removed
    }

    fun clearUsage() = usageStore.clear()

    fun clearConversations() = conversationStore.clear()

    fun clearToolExecutions() = toolStore.clear()

    fun clearWorkflowCheckpoints() = workflowCheckpointStore.clear()

    fun clearAll() {
        clearUsage()
        clearConversations()
        clearToolExecutions()
        clearWorkflowCheckpoints()
    }

    private fun sanitizeUsage(record: UsageRecord): UsageRecord = record.copy(
        id = identifier(record.id),
        runId = identifier(record.runId),
        projectId = identifier(record.projectId),
        providerId = identifier(record.providerId),
        model = safeText(record.model, MAX_IDENTIFIER_CHARS),
        workflowId = record.workflowId?.let(::identifier),
        agentId = record.agentId?.let(::identifier),
        parentAgentId = record.parentAgentId?.let(::identifier),
    )

    private fun pruneExpiredUsage() {
        if (retention.usageRetentionDays == Int.MAX_VALUE) return
        val now = Instant.now()
        val epochDay = now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        synchronized(usagePruneLock) {
            if (lastUsagePruneDay == epochDay) return
            val cutoff = now.minus(retention.usageRetentionDays.toLong(), ChronoUnit.DAYS)
            usageStore.update { records -> records.filterNot { it.recordedAt.isBefore(cutoff) } }
            lastUsagePruneDay = epochDay
        }
    }

    private fun sanitizeConversation(record: ConversationRecord): ConversationRecord {
        val messages = record.messages.map { message ->
            message.copy(
                text = safeText(message.text, retention.maxMessageChars),
                toolName = message.toolName?.let(::identifier),
                toolCallId = message.toolCallId?.let(::identifier),
            )
        }.let(::boundedMessages)
        return record.copy(
            id = identifier(record.id),
            projectId = identifier(record.projectId),
            title = safeText(record.title, MAX_TITLE_CHARS),
            messages = messages,
            mode = record.mode ?: dev.omnicode.agent.AgentMode.AGENT,
            lastRunStatus = record.lastRunStatus ?: dev.omnicode.agent.AgentRunStatus.COMPLETED,
            workflowId = record.workflowId?.let(::identifier),
            agentId = record.agentId?.let(::identifier),
            parentAgentId = record.parentAgentId?.let(::identifier),
        )
    }

    private fun sanitizeToolExecution(record: ToolExecutionRecord): ToolExecutionRecord = record.copy(
        id = identifier(record.id),
        executionId = identifier(record.executionId),
        runId = identifier(record.runId),
        projectId = identifier(record.projectId),
        conversationId = record.conversationId?.let(::identifier),
        toolName = identifier(record.toolName),
        toolCallId = record.toolCallId?.let(::identifier),
        workflowId = record.workflowId?.let(::identifier),
        agentId = record.agentId?.let(::identifier),
        parentAgentId = record.parentAgentId?.let(::identifier),
        inputSummary = record.inputSummary?.let { safeText(it, retention.maxToolSummaryChars) },
        outputSummary = record.outputSummary?.let { safeText(it, retention.maxToolSummaryChars) },
        errorMessage = record.errorMessage?.let { safeText(it, MAX_ERROR_CHARS) },
    )

    private fun sanitizeWorkflowCheckpoint(record: WorkflowCheckpoint): WorkflowCheckpoint = record.copy(
        workflowId = identifier(record.workflowId),
        runId = identifier(record.runId),
        projectId = identifier(record.projectId),
        conversationId = record.conversationId?.let(::identifier),
        agentId = identifier(record.agentId),
        parentAgentId = record.parentAgentId?.let(::identifier),
        messages = boundedMessages(
            record.messages.map { message ->
                message.copy(
                    text = safeText(message.text, retention.maxMessageChars),
                    toolName = message.toolName?.let(::identifier),
                    toolCallId = message.toolCallId?.let(::identifier),
                )
            },
        ),
        observations = record.observations.takeLast(retention.maxMessagesPerConversation).map { observation ->
            observation.copy(
                toolCallId = identifier(observation.toolCallId),
                toolName = identifier(observation.toolName),
                text = safeText(observation.text, retention.maxToolSummaryChars),
            )
        },
        state = record.state ?: WorkflowCheckpointState.INTERRUPTED,
        version = CURRENT_WORKFLOW_CHECKPOINT_VERSION,
        pendingTool = record.pendingTool?.let { pending ->
            pending.copy(
                executionId = identifier(pending.executionId),
                toolCallId = identifier(pending.toolCallId),
                toolName = identifier(pending.toolName),
                argumentsJson = safeText(pending.argumentsJson, retention.maxToolSummaryChars),
            )
        },
        pendingApproval = record.pendingApproval?.let { approval ->
            approval.copy(
                approvalId = identifier(approval.approvalId),
                toolCallId = identifier(approval.toolCallId),
                toolName = identifier(approval.toolName),
                title = safeText(approval.title, MAX_TITLE_CHARS),
                risk = safeText(approval.risk, MAX_ERROR_CHARS),
            )
        },
        pendingProviderAttempt = record.pendingProviderAttempt?.let { attempt ->
            attempt.copy(idempotencyKey = identifier(attempt.idempotencyKey))
        },
        delegates = record.delegates.takeLast(MAX_CHECKPOINT_DELEGATES).map { delegate ->
            delegate.copy(
                delegationId = identifier(delegate.delegationId),
                agentId = identifier(delegate.agentId),
                parentAgentId = delegate.parentAgentId?.let(::identifier),
                role = safeText(delegate.role, MAX_IDENTIFIER_CHARS),
                objective = safeText(delegate.objective, retention.maxToolSummaryChars),
                summary = safeText(delegate.summary, retention.maxToolSummaryChars),
            )
        },
    )

    private fun boundedMessages(messages: List<MessageSnapshot>): List<MessageSnapshot> {
        if (messages.size <= retention.maxMessagesPerConversation) return messages
        val firstSystem = messages.firstOrNull { it.role == SnapshotRole.SYSTEM }
        val tailSize = retention.maxMessagesPerConversation - if (firstSystem == null) 0 else 1
        val tail = if (tailSize == 0) emptyList() else messages.takeLast(tailSize)
        return buildList {
            if (firstSystem != null && firstSystem !in tail) add(firstSystem)
            addAll(tail)
        }.takeLast(retention.maxMessagesPerConversation)
    }

    private fun safeText(value: String, maxChars: Int): String = redactor.redact(value).take(maxChars)

    private fun identifier(value: String): String = safeText(value.trim(), MAX_IDENTIFIER_CHARS)

    private fun isValidUsage(record: UsageRecord): Boolean =
        record.id.isNotBlank() &&
            record.runId.isNotBlank() &&
            record.providerId.isNotBlank() &&
            record.model.isNotBlank() &&
            record.inputTokens >= 0 &&
            record.outputTokens >= 0 &&
            record.estimatedCostUsd?.signum()?.let { it >= 0 } != false &&
            runCatching { record.recordedAt.toEpochMilli() }.isSuccess

    private fun isValidConversation(record: ConversationRecord): Boolean =
        record.id.isNotBlank() &&
            record.projectId.isNotBlank() &&
            record.title.length <= MAX_TITLE_CHARS &&
            !record.updatedAt.isBefore(record.createdAt) &&
            record.messages.size <= retention.maxMessagesPerConversation &&
            record.messages.all { message ->
                message.text.length <= retention.maxMessageChars &&
                    message.role.name.isNotBlank() &&
                    runCatching { message.recordedAt.toEpochMilli() }.isSuccess
            }

    private fun isValidToolExecution(record: ToolExecutionRecord): Boolean =
        record.id.isNotBlank() &&
            record.executionId.isNotBlank() &&
            record.runId.isNotBlank() &&
            record.toolName.isNotBlank() &&
            record.status.name.isNotBlank() &&
            record.approvalDecision.name.isNotBlank() &&
            record.durationMillis?.let { it >= 0 } != false &&
            runCatching { record.recordedAt.toEpochMilli() }.isSuccess

    private fun isValidWorkflowCheckpoint(record: WorkflowCheckpoint): Boolean =
        record.workflowId.isNotBlank() &&
            record.runId.isNotBlank() &&
            record.projectId.isNotBlank() &&
            record.agentId.isNotBlank() &&
            record.iteration >= 0 &&
            record.requiredImageAttachments >= 0 &&
            record.version in 0..CURRENT_WORKFLOW_CHECKPOINT_VERSION &&
            !record.updatedAt.isBefore(record.createdAt) &&
            record.messages.size <= retention.maxMessagesPerConversation &&
            record.messages.all { message ->
                message.text.length <= retention.maxMessageChars &&
                    runCatching { message.recordedAt.toEpochMilli() }.isSuccess
            } &&
            record.observations.size <= retention.maxMessagesPerConversation &&
            record.observations.all { observation ->
                observation.toolCallId.isNotBlank() &&
                    observation.toolName.isNotBlank() &&
                    observation.text.length <= retention.maxToolSummaryChars &&
                    runCatching { observation.recordedAt.toEpochMilli() }.isSuccess
            } &&
            isValidWorkflowBudget(record.budget) &&
            record.pendingTool?.let { pending ->
                pending.executionId.isNotBlank() &&
                    pending.toolCallId.isNotBlank() &&
                    pending.toolName.isNotBlank() &&
                    runCatching { pending.requestedAt.toEpochMilli() }.isSuccess
            } != false &&
            record.pendingApproval?.let { approval ->
                approval.approvalId.isNotBlank() &&
                    approval.toolCallId.isNotBlank() &&
                    approval.toolName.isNotBlank() &&
                    runCatching { approval.requestedAt.toEpochMilli() }.isSuccess
            } != false &&
            record.pendingProviderAttempt?.let { attempt ->
                attempt.idempotencyKey.isNotBlank() &&
                    attempt.attempt > 0 &&
                    attempt.projectedInputTokens >= 0 &&
                    attempt.projectedOutputTokens >= 0 &&
                    runCatching { attempt.startedAt.toEpochMilli() }.isSuccess
            } != false &&
            record.delegates.size <= MAX_CHECKPOINT_DELEGATES &&
            record.delegates.all { delegate ->
                delegate.delegationId.isNotBlank() &&
                    delegate.agentId.isNotBlank() &&
                    delegate.role.isNotBlank() &&
                    delegate.iteration >= 0
            } &&
            runCatching { record.createdAt.toEpochMilli() }.isSuccess &&
            runCatching { record.updatedAt.toEpochMilli() }.isSuccess

    private fun isValidWorkflowBudget(budget: WorkflowBudgetSnapshot): Boolean =
        budget.inputTokens >= 0 &&
            budget.outputTokens >= 0 &&
            budget.reservedInputTokens >= 0 &&
            budget.reservedOutputTokens >= 0 &&
            budget.maxInputTokens > 0 &&
            budget.maxOutputTokens > 0 &&
            budget.maxTotalTokens > 0 &&
            budget.toolCalls >= 0 &&
            budget.maxToolCalls > 0 &&
            budget.estimatedCostUsd?.signum()?.let { it >= 0 } != false &&
            budget.projectedCostUsd?.signum()?.let { it >= 0 } != false &&
            (budget.estimatedCostUsd == null ||
                budget.projectedCostUsd == null ||
                budget.projectedCostUsd >= budget.estimatedCostUsd) &&
            budget.costBasisVersion in 0..1 &&
            budget.maxCostUsd?.signum()?.let { it > 0 } != false

    private fun conversationMaxLineChars(): Int {
        val requested = retention.maxMessagesPerConversation.toLong() * (retention.maxMessageChars + 2_048L)
        return requested.coerceIn(MIN_CONVERSATION_LINE_CHARS.toLong(), MAX_CONVERSATION_LINE_CHARS.toLong()).toInt()
    }

    private fun toolMaxLineChars(): Int =
        (retention.maxToolSummaryChars.toLong() * 2L + MAX_ERROR_CHARS + 8_192L)
            .coerceAtMost(MAX_TOOL_LINE_CHARS.toLong())
            .toInt()

    private fun checkpointMaxLineChars(): Int {
        val messages = retention.maxMessagesPerConversation.toLong() * (retention.maxMessageChars + 2_048L)
        val observations = retention.maxMessagesPerConversation.toLong() * (retention.maxToolSummaryChars + 1_024L)
        return (messages + observations + 1_048_576L)
            .coerceIn(MIN_CONVERSATION_LINE_CHARS.toLong(), MAX_CHECKPOINT_LINE_CHARS.toLong())
            .toInt()
    }

    private fun sumCost(costs: List<BigDecimal>): BigDecimal? =
        if (costs.isEmpty()) null else costs.fold(BigDecimal.ZERO, BigDecimal::add)

    companion object {
        private const val USAGE_FILE = "usage.jsonl"
        private const val CONVERSATIONS_FILE = "conversations.jsonl"
        private const val TOOL_EXECUTIONS_FILE = "tool-executions.jsonl"
        private const val WORKFLOW_CHECKPOINTS_FILE = "workflow-checkpoints.jsonl"

        private const val MAX_IDENTIFIER_CHARS = 256
        private const val MAX_TITLE_CHARS = 512
        private const val MAX_ERROR_CHARS = 8_192
        private const val USAGE_MAX_LINE_CHARS = 32_768
        private const val MIN_CONVERSATION_LINE_CHARS = 1_048_576
        private const val MAX_CONVERSATION_LINE_CHARS = 64 * 1_048_576
        private const val MAX_TOOL_LINE_CHARS = 1_048_576
        private const val MAX_CHECKPOINT_LINE_CHARS = 64 * 1_048_576
        private const val MAX_CHECKPOINT_DELEGATES = 64

        private const val USAGE_MAX_FILE_BYTES = 32L * 1_048_576L
        private const val CONVERSATIONS_MAX_FILE_BYTES = 128L * 1_048_576L
        private const val TOOL_EXECUTIONS_MAX_FILE_BYTES = 128L * 1_048_576L
        private const val WORKFLOW_CHECKPOINTS_MAX_FILE_BYTES = 256L * 1_048_576L

        fun default(
            retention: PersistenceRetention = PersistenceRetention(),
            redactor: SensitiveDataRedactor = DefaultSensitiveDataRedactor(),
        ): OmniCodeLocalStore = OmniCodeLocalStore(
            storageRoot = Path.of(PathManager.getSystemPath()).resolve("omnicode"),
            retention = retention,
            redactor = redactor,
        )
    }
}
