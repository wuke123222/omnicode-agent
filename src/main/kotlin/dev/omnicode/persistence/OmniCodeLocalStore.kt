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
    private val usagePruneLock = Any()

    @Volatile
    private var lastUsagePruneDay: Long = Long.MIN_VALUE

    /** Returns false when the id already exists in the retained usage window. */
    fun recordUsage(record: UsageRecord): Boolean {
        pruneExpiredUsage()
        return usageStore.append(record)
    }

    fun queryUsage(query: UsageQuery = UsageQuery()): List<UsageRecord> {
        pruneExpiredUsage()
        val limit = query.limit.coerceIn(0, retention.maxUsageRecords)
        if (limit == 0) return emptyList()
        val projectId = query.projectId?.let(::identifier)
        val providerId = query.providerId?.let(::identifier)
        val model = query.model?.let { safeText(it, MAX_IDENTIFIER_CHARS) }
        return usageStore.readAll()
            .asSequence()
            .filter { projectId == null || it.projectId == projectId }
            .filter { providerId == null || it.providerId == providerId }
            .filter { model == null || it.model == model }
            .filter { query.fromInclusive == null || !it.recordedAt.isBefore(query.fromInclusive) }
            .filter { query.toExclusive == null || it.recordedAt.isBefore(query.toExclusive) }
            .filter { query.mode == null || it.mode == query.mode }
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
        val runId = query.runId?.let(::identifier)
        val executionId = query.executionId?.let(::identifier)
        val toolName = query.toolName?.let(::identifier)
        return toolStore.readAll()
            .asSequence()
            .filter { projectId == null || it.projectId == projectId }
            .filter { conversationId == null || it.conversationId == conversationId }
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

    fun clearUsage() = usageStore.clear()

    fun clearConversations() = conversationStore.clear()

    fun clearToolExecutions() = toolStore.clear()

    fun clearAll() {
        clearUsage()
        clearConversations()
        clearToolExecutions()
    }

    private fun sanitizeUsage(record: UsageRecord): UsageRecord = record.copy(
        id = identifier(record.id),
        runId = identifier(record.runId),
        projectId = identifier(record.projectId),
        providerId = identifier(record.providerId),
        model = safeText(record.model, MAX_IDENTIFIER_CHARS),
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
        inputSummary = record.inputSummary?.let { safeText(it, retention.maxToolSummaryChars) },
        outputSummary = record.outputSummary?.let { safeText(it, retention.maxToolSummaryChars) },
        errorMessage = record.errorMessage?.let { safeText(it, MAX_ERROR_CHARS) },
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

    private fun conversationMaxLineChars(): Int {
        val requested = retention.maxMessagesPerConversation.toLong() * (retention.maxMessageChars + 2_048L)
        return requested.coerceIn(MIN_CONVERSATION_LINE_CHARS.toLong(), MAX_CONVERSATION_LINE_CHARS.toLong()).toInt()
    }

    private fun toolMaxLineChars(): Int =
        (retention.maxToolSummaryChars.toLong() * 2L + MAX_ERROR_CHARS + 8_192L)
            .coerceAtMost(MAX_TOOL_LINE_CHARS.toLong())
            .toInt()

    private fun sumCost(costs: List<BigDecimal>): BigDecimal? =
        if (costs.isEmpty()) null else costs.fold(BigDecimal.ZERO, BigDecimal::add)

    companion object {
        private const val USAGE_FILE = "usage.jsonl"
        private const val CONVERSATIONS_FILE = "conversations.jsonl"
        private const val TOOL_EXECUTIONS_FILE = "tool-executions.jsonl"

        private const val MAX_IDENTIFIER_CHARS = 256
        private const val MAX_TITLE_CHARS = 512
        private const val MAX_ERROR_CHARS = 8_192
        private const val USAGE_MAX_LINE_CHARS = 32_768
        private const val MIN_CONVERSATION_LINE_CHARS = 1_048_576
        private const val MAX_CONVERSATION_LINE_CHARS = 64 * 1_048_576
        private const val MAX_TOOL_LINE_CHARS = 1_048_576

        private const val USAGE_MAX_FILE_BYTES = 32L * 1_048_576L
        private const val CONVERSATIONS_MAX_FILE_BYTES = 128L * 1_048_576L
        private const val TOOL_EXECUTIONS_MAX_FILE_BYTES = 128L * 1_048_576L

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
