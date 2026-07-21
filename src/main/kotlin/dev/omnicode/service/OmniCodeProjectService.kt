package dev.omnicode.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentEngine
import dev.omnicode.agent.AgentCostBudget
import dev.omnicode.agent.AgentCheckpointSink
import dev.omnicode.agent.AgentExecutionCheckpoint
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentEventSink
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentIdentity
import dev.omnicode.agent.AgentLimits
import dev.omnicode.agent.AgentPendingTool
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRole
import dev.omnicode.agent.AgentRunResult
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.agent.ContextSelector
import dev.omnicode.agent.SharedAgentBudgetExceededException
import dev.omnicode.agent.SharedAgentBudgetLedger
import dev.omnicode.agent.ToolApprovalOutcome
import dev.omnicode.agent.estimatedResponseOutputTokens
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.TokenUsage
import dev.omnicode.model.UserAttachment
import dev.omnicode.model.UserSubmission
import dev.omnicode.model.AttachmentKind
import dev.omnicode.mcp.McpToolConnector
import dev.omnicode.mcp.ApprovedMcpHttpClientConnector
import dev.omnicode.provider.ProviderFactory
import dev.omnicode.provider.ProviderException
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ReasoningEffort
import dev.omnicode.provider.likelySupportsVision
import dev.omnicode.provider.recommendedOutputTokenFloor
import dev.omnicode.provider.requireReasoningResolution
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.OmniCodeLocalStore
import dev.omnicode.persistence.PersistenceRetention
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.ToolApprovalDecision
import dev.omnicode.persistence.ToolExecutionRecord
import dev.omnicode.persistence.ToolExecutionStatus
import dev.omnicode.persistence.UsageRecord
import dev.omnicode.persistence.DelegateCheckpointSnapshot
import dev.omnicode.persistence.PendingApprovalSnapshot
import dev.omnicode.persistence.PendingToolSnapshot
import dev.omnicode.persistence.WorkflowBudgetSnapshot
import dev.omnicode.persistence.WorkflowCheckpoint
import dev.omnicode.persistence.WorkflowCheckpointState
import dev.omnicode.persistence.WorkflowObservationSnapshot
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.AgentRuntimeSettings
import dev.omnicode.settings.ModelPricing
import dev.omnicode.settings.OmniCodeSettingsService
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.tool.DelegateSpecialistsTool
import dev.omnicode.tool.RunCommandTool
import dev.omnicode.tool.SandboxedMcpProcessLauncher
import dev.omnicode.tool.SpecialistTaskRequest
import dev.omnicode.tool.SpecialistTaskRunner
import dev.omnicode.tool.ToolRegistry
import dev.omnicode.util.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class AgentRunCallbacks(
    val onRunningChanged: (Boolean) -> Unit = {},
    val onEvent: (AgentEvent) -> Unit = {},
    val onResult: (AgentRunResult) -> Unit = {},
)

data class ProviderStatus(
    val configured: Boolean,
    val text: String,
    val providerName: String = "",
    val model: String = "",
)

data class RecoverableWorkflow(
    val workflowId: String,
    val conversationId: String?,
    val title: String,
    val mode: AgentMode,
    val strategy: AgentExecutionStrategy,
    val iteration: Int,
    val updatedAt: Instant,
    val pendingToolName: String? = null,
    val pendingToolDangerous: Boolean = false,
    val requiredImageAttachments: Int = 0,
)

@Service(Service.Level.PROJECT)
class OmniCodeProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val stateLock = Any()
    private val localStore = OmniCodePlatformSettingsService.getInstance().snapshot().let { platform ->
        OmniCodeLocalStore.default(
            retention = PersistenceRetention(
                maxConversations = platform.historyRetention,
                usageRetentionDays = platform.usageRetentionDays,
            ),
        )
    }
    private val projectId = projectFingerprint(project.basePath.orEmpty())

    private var activeJob: Job? = null
    private var activeRunId: String? = null
    private var explicitlyCancelledRunId: String? = null
    private var conversationHistory: List<ConversationMessage> = emptyList()
    private var conversationId: String = UUID.randomUUID().toString()
    private var conversationCreatedAt: Instant = Instant.now()
    private var conversationMode: AgentMode = AgentMode.AGENT
    private var conversationStrategy: AgentExecutionStrategy = AgentExecutionStrategy.SINGLE

    /**
     * Starts one agent run for this project. Concurrent runs are rejected so tool
     * observations and the in-memory conversation cannot interleave.
     */
    fun startRun(
        userMessage: String,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean = startRun(
        userMessage,
        AgentMode.AGENT,
        AgentExecutionStrategy.SINGLE,
        approvalGate,
        callbacks,
    )

    fun startRun(
        userMessage: String,
        mode: AgentMode,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean = startRun(userMessage, mode, AgentExecutionStrategy.SINGLE, approvalGate, callbacks)

    fun startRun(
        userMessage: String,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean = startRun(UserSubmission(userMessage), mode, strategy, approvalGate, callbacks)

    fun startRun(
        submission: UserSubmission,
        mode: AgentMode,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean = startRun(submission, mode, AgentExecutionStrategy.SINGLE, approvalGate, callbacks)

    fun startRun(
        submission: UserSubmission,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean {
        val prompt = submission.prompt.trim()
        if (prompt.isEmpty() && submission.attachments.isEmpty()) return false
        val userMessage = submission.copy(prompt = prompt).toMessage()

        return startPreparedRun(
            userMessage = userMessage,
            mode = mode,
            strategy = strategy,
            approvalGate = approvalGate,
            callbacks = callbacks,
        )
    }

    private fun startPreparedRun(
        userMessage: ConversationMessage,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
        recovery: RecoveryStart? = null,
    ): Boolean {

        val resultDelivered = AtomicBoolean(false)
        val priorMessages: List<ConversationMessage>
        val runId = recovery?.workflowId ?: UUID.randomUUID().toString()
        val activeConversationId: String
        val activeConversationCreatedAt: Instant
        lateinit var job: Job

        synchronized(stateLock) {
            if (activeJob != null) return false
            if (recovery != null) {
                conversationId = recovery.conversationId
                conversationCreatedAt = recovery.createdAt
                conversationHistory = recovery.priorMessages.toList()
                conversationMode = mode
                conversationStrategy = strategy
            }
            priorMessages = recovery?.priorMessages?.toList() ?: conversationHistory.toList()
            activeConversationId = recovery?.conversationId ?: conversationId
            activeConversationCreatedAt = recovery?.createdAt ?: conversationCreatedAt
            job = coroutineScope.launch(start = CoroutineStart.LAZY) {
                persistSafely("initial workflow checkpoint") {
                    persistInitialWorkflowCheckpoint(
                        runId = runId,
                        conversationId = activeConversationId,
                        createdAt = activeConversationCreatedAt,
                        messages = priorMessages + userMessage,
                        mode = mode,
                        strategy = strategy,
                    )
                }
                val result = executeAgent(
                    userMessage = userMessage,
                    priorMessages = priorMessages,
                    approvalGate = approvalGate,
                    callbacks = callbacks,
                    runId = runId,
                    activeConversationId = activeConversationId,
                    checkpointCreatedAt = activeConversationCreatedAt,
                    mode = mode,
                    strategy = strategy,
                )
                if (updateConversationCheckpoint(result)) {
                    persistSafely("conversation history") {
                        persistConversation(
                            id = activeConversationId,
                            createdAt = activeConversationCreatedAt,
                            messages = result.messages,
                            workflowId = result.workflowId,
                            mode = result.mode,
                            strategy = result.strategy,
                            status = result.status,
                        )
                    }
                }
                persistSafely("terminal workflow checkpoint") {
                    persistTerminalWorkflowCheckpoint(
                        result = result,
                        conversationId = activeConversationId,
                        createdAt = activeConversationCreatedAt,
                        keepRecoverable = (recovery != null && result.status != AgentRunStatus.COMPLETED) ||
                            (result.status == AgentRunStatus.CANCELLED && !wasExplicitlyCancelled(runId)),
                    )
                }
                deliverResult(resultDelivered, callbacks, result)
            }
            activeJob = job
            activeRunId = runId
            explicitlyCancelledRunId = null
        }

        dispatchEdt { callbacks.onRunningChanged(true) }
        job.invokeOnCompletion { cause ->
            val explicitCancellation = synchronized(stateLock) { explicitlyCancelledRunId == runId }
            val wasCurrentRun = synchronized(stateLock) {
                if (activeJob === job) {
                    activeJob = null
                    activeRunId = null
                    if (explicitlyCancelledRunId == runId) explicitlyCancelledRunId = null
                    true
                } else {
                    false
                }
            }

            if (resultDelivered.compareAndSet(false, true)) {
                val fallback = completionFallback(userMessage, priorMessages, cause, mode, strategy, runId)
                if (updateConversationCheckpoint(fallback)) {
                    coroutineScope.launch {
                        persistSafely("fallback conversation history") {
                            persistConversation(
                                id = activeConversationId,
                                createdAt = activeConversationCreatedAt,
                                messages = fallback.messages,
                                workflowId = fallback.workflowId,
                                mode = fallback.mode,
                                strategy = fallback.strategy,
                                status = fallback.status,
                            )
                        }
                        persistSafely("fallback workflow checkpoint") {
                            persistTerminalWorkflowCheckpoint(
                                result = fallback,
                                conversationId = activeConversationId,
                                createdAt = activeConversationCreatedAt,
                                keepRecoverable = recovery != null ||
                                    (fallback.status == AgentRunStatus.CANCELLED && !explicitCancellation),
                            )
                        }
                    }
                }
                dispatchEdt { callbacks.onResult(fallback) }
            }
            if (wasCurrentRun) {
                dispatchEdt { callbacks.onRunningChanged(false) }
            }
        }
        job.start()
        return true
    }

    fun listRecoverableWorkflows(callback: (List<RecoverableWorkflow>) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val workflows = runCatching {
                // A Tool Window can be recreated while the project service still owns a live run.
                // Never relabel that active checkpoint as interrupted merely because a new panel opened.
                if (!isRunning()) localStore.markUnfinishedWorkflowCheckpointsInterrupted(projectId)
                localStore.unfinishedWorkflowCheckpoints(projectId, 20).map(::recoverableWorkflow)
            }.getOrDefault(emptyList())
            dispatchEdt { callback(workflows) }
        }
    }

    fun discardRecoverableWorkflow(workflowId: String, callback: (Boolean) -> Unit = {}) {
        coroutineScope.launch(Dispatchers.IO) {
            val deleted = runCatching { localStore.deleteWorkflowCheckpoint(workflowId) }.getOrDefault(false)
            dispatchEdt { callback(deleted) }
        }
    }

    fun resumeWorkflow(
        workflowId: String,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
        reattachedImages: List<UserAttachment> = emptyList(),
        onStarted: (Boolean) -> Unit = {},
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val checkpoint = runCatching { localStore.workflowCheckpoint(workflowId) }
                .getOrNull()
                ?.takeUnless(WorkflowCheckpoint::isTerminal)
            if (checkpoint == null) {
                dispatchEdt { onStarted(false) }
                return@launch
            }
            val requiredImages = checkpoint.requiredImageAttachments
            val safeImages = reattachedImages.filter { it.kind == AttachmentKind.IMAGE }
            if (safeImages.size < requiredImages) {
                dispatchEdt { onStarted(false) }
                return@launch
            }
            val restored = messagesFromWorkflowCheckpoint(checkpoint)
            val instruction = UserSubmission(
                prompt = resumeWorkflowInstruction(checkpoint),
                attachments = safeImages,
            ).toMessage()
            val started = startPreparedRun(
                userMessage = instruction,
                mode = checkpoint.mode ?: AgentMode.AGENT,
                strategy = checkpoint.strategy ?: AgentExecutionStrategy.SINGLE,
                approvalGate = approvalGate,
                callbacks = callbacks,
                recovery = RecoveryStart(
                    workflowId = checkpoint.workflowId,
                    conversationId = checkpoint.conversationId ?: UUID.randomUUID().toString(),
                    createdAt = checkpoint.createdAt,
                    priorMessages = restored,
                ),
            )
            dispatchEdt { onStarted(started) }
        }
    }

    fun cancelCurrentRun(): Boolean {
        val job = synchronized(stateLock) {
            val current = activeJob ?: return false
            explicitlyCancelledRunId = activeRunId
            current
        }
        job.cancel(CancellationException("Cancelled by user"))
        return true
    }

    /** Stops work for Tool Window/IDE lifecycle changes while retaining a resumable checkpoint. */
    fun interruptCurrentRun(): Boolean {
        val job = synchronized(stateLock) { activeJob } ?: return false
        job.cancel(CancellationException("Interrupted by IDE lifecycle"))
        return true
    }

    fun isRunning(): Boolean = synchronized(stateLock) { activeJob != null }

    private fun wasExplicitlyCancelled(runId: String): Boolean = synchronized(stateLock) {
        explicitlyCancelledRunId == runId
    }

    fun clearHistory(): Boolean = synchronized(stateLock) {
        if (activeJob != null) return false
        conversationHistory = emptyList()
        conversationId = UUID.randomUUID().toString()
        conversationCreatedAt = Instant.now()
        conversationMode = AgentMode.AGENT
        conversationStrategy = AgentExecutionStrategy.SINGLE
        true
    }

    fun historySnapshot(): List<ConversationMessage> = synchronized(stateLock) {
        conversationHistory.toList()
    }

    fun conversationModeSnapshot(): AgentMode = synchronized(stateLock) { conversationMode }

    fun conversationStrategySnapshot(): AgentExecutionStrategy = synchronized(stateLock) { conversationStrategy }

    private fun updateConversationCheckpoint(result: AgentRunResult): Boolean {
        if (!hasConversationCheckpoint(result.messages)) return false
        synchronized(stateLock) {
            conversationHistory = result.messages.toList()
            conversationMode = result.mode
            conversationStrategy = result.strategy
        }
        return true
    }

    fun listConversationHistory(callback: (List<ConversationRecord>) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val records = runCatching { localStore.conversations(projectId, 100) }.getOrDefault(emptyList())
            dispatchEdt { callback(records) }
        }
    }

    fun restoreConversation(id: String, callback: (Boolean) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val record = runCatching { localStore.conversation(id) }.getOrNull()
            val restored = record?.let(::messagesFromConversationRecord).orEmpty()
            val accepted = synchronized(stateLock) {
                if (activeJob != null || record == null) return@synchronized false
                conversationId = record.id
                conversationCreatedAt = record.createdAt
                conversationHistory = restored
                conversationMode = record.mode ?: AgentMode.AGENT
                conversationStrategy = record.strategy ?: AgentExecutionStrategy.SINGLE
                true
            }
            dispatchEdt { callback(accepted) }
        }
    }

    fun deleteConversation(id: String, callback: (Boolean) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val deleted = runCatching { localStore.deleteConversation(id) }.getOrDefault(false)
            dispatchEdt { callback(deleted) }
        }
    }

    /** Reads provider settings off the EDT and returns only non-secret display data. */
    fun refreshProviderStatus(callback: (ProviderStatus) -> Unit) {
        coroutineScope.launch {
            val status = runCatching {
                val connection = OmniCodeSettingsService.getInstance().providerConnectionAsync()
                ProviderFactory.create(connection)
                val hasCredentials = connection.preset.apiKeyOptional || connection.apiKey.isNotBlank()
                ProviderStatus(
                    configured = hasCredentials,
                    text = if (hasCredentials) {
                        "${connection.preset.displayName} · ${connection.model}"
                    } else {
                        "${connection.preset.displayName} · API key missing"
                    },
                    providerName = connection.preset.displayName,
                    model = connection.model,
                )
            }.getOrElse {
                val settings = OmniCodeSettingsService.getInstance().snapshot()
                ProviderStatus(
                    configured = false,
                    text = "Provider not configured",
                    providerName = ProviderPresets.byId(settings.providerId).displayName,
                    model = settings.model,
                )
            }
            dispatchEdt { callback(status) }
        }
    }

    private suspend fun executeAgent(
        userMessage: ConversationMessage,
        priorMessages: List<ConversationMessage>,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
        runId: String,
        activeConversationId: String,
        checkpointCreatedAt: Instant,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
    ): AgentRunResult {
        val eventDispatcher = CoalescingEventDispatcher(callbacks)
        var requestMessages = priorMessages + userMessage
        var workflowLedger: SharedAgentBudgetLedger? = null
        var usageContext: UsagePersistenceContext? = null
        val billedModels = ConcurrentHashMap<String, String>()
        val result = try {
            eventDispatcher.emit(AgentEvent.ExecutionStrategySelected(strategy, runId))
            val settingsService = OmniCodeSettingsService.getInstance()
            val settingsSnapshot = settingsService.snapshot()
            val connection = settingsService.providerConnectionAsync(settingsSnapshot)
            val reasoning = connection.requireReasoningResolution()
            val maxOutputTokens = maxOf(
                settingsSnapshot.maxOutputTokens,
                connection.recommendedOutputTokenFloor(reasoning),
            )
            val platform = OmniCodePlatformSettingsService.getInstance().snapshot()
            val runtime = platform.agentRuntime
            val limits = agentLimits(runtime, maxOutputTokens)
            val resumedCheckpoint = withContext(Dispatchers.IO) { localStore.workflowCheckpoint(runId) }
            val resumedUsage = resumedCheckpoint?.budget?.let { budget ->
                TokenUsage(budget.inputTokens, budget.outputTokens)
            } ?: TokenUsage()
            val resumedIteration = resumedCheckpoint?.iteration ?: 0
            val resumedToolCalls = resumedCheckpoint?.budget?.toolCalls ?: 0
            val resumedPendingTool = resumedCheckpoint?.pendingTool?.let { pending ->
                AgentPendingTool(
                    callId = pending.toolCallId,
                    name = pending.toolName,
                    argumentsJson = pending.argumentsJson,
                    dangerous = pending.dangerous,
                    executionStarted = pending.executionStarted,
                )
            }
            eventDispatcher.emit(
                AgentEvent.Status(
                    "推理强度 · ${connection.reasoningEffort.persistedValue} → " +
                        "${reasoning.effective.persistedValue} · ${reasoning.explanation}",
                ),
            )
            val reasoningContext = reasoningExecutionContext(connection.reasoningEffort)
            billedModels[LEAD_AGENT_ID] = connection.model
            val costEstimator: (TokenUsage) -> BigDecimal? = { usage ->
                estimateUsageCost(
                    connection.preset.id,
                    connection.model,
                    usage,
                    platform.pricing,
                )
            }
            val agentCostEstimator: (String, TokenUsage) -> BigDecimal? = { agentId, usage ->
                estimateUsageCost(
                    connection.preset.id,
                    billedModels[agentId] ?: connection.model,
                    usage,
                    platform.pricing,
                )
            }
            val sharedLedger = SharedAgentBudgetLedger(
                maxTotalTokens = saturatingTokenBudget(limits.maxInputTokens, limits.maxOutputTokens),
                maxInputTokens = limits.maxInputTokens,
                maxOutputTokens = limits.maxOutputTokens,
                maxCostUsd = runtime.maxRunCostUsd?.let(BigDecimal::valueOf),
                warningRatio = runtime.costWarningRatio,
                estimator = costEstimator,
                agentEstimator = agentCostEstimator,
                initialUsage = resumedUsage,
            )
            workflowLedger = sharedLedger
            usageContext = UsagePersistenceContext(
                providerId = connection.preset.id,
                model = connection.model,
            )
            val preparedUserMessage = prepareImagesForProvider(
                userMessage = userMessage,
                primaryConnection = connection,
                approvalGate = approvalGate,
                events = eventDispatcher,
                workflowLedger = sharedLedger,
                billedModels = billedModels,
            )
            requestMessages = priorMessages + preparedUserMessage
            val skillLibrary = SkillLibrary(project)
            val skillTools = listOf(ListSkillsTool(skillLibrary), LoadSkillTool(skillLibrary))
            // Connecting an MCP server starts an external process, so only Agent mode may
            // connect; Plan and Research skip it rather than merely hiding tool schemas.
            val mcpBundle = when (mode) {
                AgentMode.AGENT -> McpToolConnector(
                    SandboxedMcpProcessLauncher(project, platform.sandboxMode, approvalGate),
                    ApprovedMcpHttpClientConnector(project, approvalGate),
                ).connect(platform.mcpServers)
                AgentMode.PLAN,
                AgentMode.RESEARCH,
                -> null
            }
            try {
                mcpBundle?.errors.orEmpty().forEach { error ->
                    eventDispatcher.emit(AgentEvent.Status("MCP ${error.serverName}: ${error.message}"))
                }
                val pendingToolExecutions = ConcurrentHashMap<String, PendingToolExecution>()
                val auditFailureReported = AtomicBoolean(false)
                val aggregateUsageEventLock = Any()
                val specialistRegistry = ToolRegistry(
                    runCommandTool = RunCommandTool(platform.sandboxMode),
                    additionalTools = skillTools,
                )
                val specialistRunner = SpecialistTaskRunner { request ->
                    val identity = AgentIdentity(
                        agentId = request.agentId,
                        parentAgentId = request.parentAgentId,
                        role = request.role,
                        displayName = specialistDisplayName(request.role),
                    )
                    val specialistEvents = AgentEventSink { event ->
                        if (event is AgentEvent.ToolRequested ||
                            event is AgentEvent.ToolApprovalResolved ||
                            event is AgentEvent.ToolCompleted
                        ) {
                            val failure = persistSafely("specialist tool audit") {
                                auditToolEvent(
                                    event = event,
                                    runId = runId,
                                    workflowId = runId,
                                    conversationId = activeConversationId,
                                    identity = identity,
                                    strategy = strategy,
                                    tools = specialistRegistry,
                                    pending = pendingToolExecutions,
                                    mode = AgentMode.PLAN,
                                )
                            }
                            if (failure != null && event is AgentEvent.ToolApprovalResolved) {
                                throw IllegalStateException("Specialist approval audit could not be persisted: $failure")
                            }
                        }
                        when (event) {
                            is AgentEvent.UsageUpdated -> synchronized(aggregateUsageEventLock) {
                                eventDispatcher.emit(
                                    AgentEvent.UsageUpdated(sharedLedger.snapshot().usage, event.at),
                                )
                            }
                            is AgentEvent.BudgetWarning -> eventDispatcher.emit(event)
                            else -> Unit
                        }
                        // Specialist text, status, and tool events stay isolated. Only aggregate budget/usage and the
                        // delegation tool's bounded lifecycle summaries reach the main transcript.
                    }
                    val specialistEngine = AgentEngine(
                        project = project,
                        provider = ProviderFactory.create(connection),
                        approvalGate = approvalGate,
                        tools = specialistRegistry,
                        limits = specialistLimits(limits),
                        costBudget = AgentCostBudget(),
                        events = specialistEvents,
                        identity = identity,
                        sharedLedger = sharedLedger,
                        systemContext = listOf(specialistSystemContext(request), reasoningContext)
                            .filter(String::isNotBlank)
                            .joinToString("\n\n"),
                    )
                    val result = specialistEngine.run(
                        userMessage = specialistUserMessage(request),
                        priorMessages = emptyList(),
                        mode = AgentMode.PLAN,
                    )
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    result
                }
                val delegateTool = if (strategy == AgentExecutionStrategy.TEAM) {
                    DelegateSpecialistsTool(
                        workflowId = runId,
                        parentAgentId = LEAD_AGENT_ID,
                        originalGoal = delegationGoal(preparedUserMessage),
                        runner = specialistRunner,
                        events = AgentEventSink(eventDispatcher::emit),
                        usageForAgent = { agentId ->
                            sharedLedger.snapshot().usageByAgent[agentId] ?: TokenUsage()
                        },
                    )
                } else {
                    null
                }
                val registry = ToolRegistry(
                    runCommandTool = RunCommandTool(platform.sandboxMode),
                    additionalTools = skillTools + mcpBundle?.tools.orEmpty() + listOfNotNull(delegateTool),
                )
                val leadIdentity = AgentIdentity(
                    agentId = LEAD_AGENT_ID,
                    role = AgentRole.LEAD,
                    displayName = "Lead",
                )
                val engine = AgentEngine(
                    project = project,
                    provider = ProviderFactory.create(connection),
                    approvalGate = approvalGate,
                    tools = registry,
                    limits = limits,
                    costBudget = AgentCostBudget(),
                    events = AgentEventSink { event ->
                        if (event is AgentEvent.ToolRequested ||
                            event is AgentEvent.ToolApprovalResolved ||
                            event is AgentEvent.ToolCompleted
                        ) {
                            val failure = persistSafely("tool audit") {
                                auditToolEvent(
                                    event = event,
                                    runId = runId,
                                    workflowId = runId,
                                    conversationId = activeConversationId,
                                    identity = leadIdentity,
                                    strategy = strategy,
                                    tools = registry,
                                    pending = pendingToolExecutions,
                                    mode = mode,
                                )
                            }
                            if (failure != null) {
                                if (event is AgentEvent.ToolApprovalResolved) {
                                    throw IllegalStateException("Approval audit could not be persisted: $failure")
                                }
                                if (auditFailureReported.compareAndSet(false, true)) {
                                    eventDispatcher.emit(AgentEvent.Status("Tool audit could not be persisted: $failure"))
                                }
                            }
                        }
                        eventDispatcher.emit(event)
                    },
                    checkpoints = AgentCheckpointSink { checkpoint ->
                        persistRuntimeWorkflowCheckpoint(
                            runId = runId,
                            conversationId = activeConversationId,
                            createdAt = checkpointCreatedAt,
                            checkpoint = checkpoint,
                            limits = limits,
                            ledger = sharedLedger,
                            mode = mode,
                            strategy = strategy,
                        )
                    },
                    identity = leadIdentity,
                    sharedLedger = sharedLedger,
                    initialUsage = resumedUsage,
                    initialIteration = resumedIteration,
                    initialToolCalls = resumedToolCalls,
                    initialPendingTool = resumedPendingTool,
                    systemContext = listOf(
                        TEAM_LEAD_CONTEXT.takeIf { strategy == AgentExecutionStrategy.TEAM }.orEmpty(),
                        reasoningContext,
                    ).filter(String::isNotBlank).joinToString("\n\n"),
                )
                val engineResult = engine.run(preparedUserMessage, priorMessages, mode)
                val result = engineResult.copy(
                    usage = sharedLedger.snapshot().usage,
                    strategy = strategy,
                    workflowId = runId,
                    delegates = delegateTool?.completedSummaries().orEmpty(),
                )
                result
            } finally {
                mcpBundle?.close()
            }
        } catch (cancelled: CancellationException) {
            val failure = classifyAgentFailure(AgentRunStatus.CANCELLED, cancelled)
            AgentRunResult(
                status = AgentRunStatus.CANCELLED,
                finalText = failure.transcriptText(),
                messages = requestMessages,
                usage = workflowLedger?.snapshot()?.usage ?: TokenUsage(),
                error = cancelled,
                mode = mode,
                strategy = strategy,
                workflowId = runId,
            )
        } catch (error: SharedAgentBudgetExceededException) {
            val failure = classifyAgentFailure(AgentRunStatus.BUDGET_EXHAUSTED, error)
            AgentRunResult(
                status = AgentRunStatus.BUDGET_EXHAUSTED,
                finalText = failure.transcriptText(),
                messages = requestMessages,
                usage = workflowLedger?.snapshot()?.usage ?: TokenUsage(),
                error = error,
                mode = mode,
                strategy = strategy,
                workflowId = runId,
            )
        } catch (error: Throwable) {
            val failure = classifyAgentFailure(AgentRunStatus.FAILED, error)
            AgentRunResult(
                status = AgentRunStatus.FAILED,
                finalText = failure.transcriptText(),
                messages = requestMessages,
                usage = workflowLedger?.snapshot()?.usage ?: TokenUsage(),
                error = error,
                mode = mode,
                strategy = strategy,
                workflowId = runId,
            )
        } finally {
            eventDispatcher.flushNow()
        }
        usageContext?.let { context ->
            persistSafely("usage") {
                recordUsage(
                    runId = runId,
                    workflowId = runId,
                    providerId = context.providerId,
                    model = workflowModelLabel(context.model, billedModels.values),
                    usage = result.usage,
                    estimatedCostUsd = workflowLedger?.snapshot()?.estimatedCostUsd,
                    mode = mode,
                    strategy = strategy,
                )
            }?.let { failure ->
                eventDispatcher.emit(AgentEvent.Status("Usage could not be persisted: $failure"))
            }
        }
        return result
    }

    private fun agentLimits(runtime: AgentRuntimeSettings, maxOutputTokensPerTurn: Int): AgentLimits = AgentLimits(
        maxIterations = runtime.maxIterations,
        maxToolCalls = runtime.maxToolCalls,
        maxWallTime = java.time.Duration.ofSeconds(runtime.maxWallTimeSeconds.toLong()),
        maxToolTime = java.time.Duration.ofSeconds(runtime.maxToolTimeSeconds.toLong()),
        maxInputTokens = runtime.maxInputTokens,
        maxOutputTokensPerTurn = maxOutputTokensPerTurn,
        maxOutputTokens = maxOf(runtime.maxOutputTokens, maxOutputTokensPerTurn.toLong()),
        providerMaxAttempts = runtime.providerMaxAttempts,
    )

    private fun specialistLimits(base: AgentLimits): AgentLimits {
        val inputShare = maxOf(1_000L, base.maxInputTokens / 3L)
        val outputShare = maxOf(1_000L, base.maxOutputTokens / 3L)
        return base.copy(
            maxIterations = minOf(base.maxIterations, 8),
            maxToolCalls = minOf(base.maxToolCalls, 12),
            maxInputTokens = inputShare,
            maxOutputTokens = outputShare,
            maxOutputTokensPerTurn = minOf(base.maxOutputTokensPerTurn, outputShare.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            maxContextChars = minOf(base.maxContextChars, 96_000),
            maxObservationChars = minOf(base.maxObservationChars, 16_000),
        )
    }

    private fun specialistUserMessage(request: SpecialistTaskRequest): ConversationMessage = ConversationMessage(
        MessageRole.USER,
        """
        Original user goal:
        ${request.originalGoal}

        Your assigned read-only objective:
        ${request.objective}

        Inspect only the evidence required for this objective. Return concise findings with project-relative file paths,
        symbols, and validation gaps. Do not propose or perform side effects, and do not assume sibling-agent results.
        """.trimIndent(),
    )

    private fun specialistSystemContext(request: SpecialistTaskRequest): String = when (request.role) {
        AgentRole.EXPLORER ->
            "Map the relevant code path and report direct observations, entry points, dependencies, and unresolved facts."
        AgentRole.PLANNER ->
            "Turn inspected project evidence into the smallest viable implementation sequence and concrete validation list."
        AgentRole.REVIEWER ->
            "Look for correctness, security, concurrency, compatibility, and regression risks, backed by inspected evidence."
        AgentRole.LEAD -> error("A delegated specialist cannot use the LEAD role")
    }

    private fun specialistDisplayName(role: AgentRole): String = when (role) {
        AgentRole.EXPLORER -> "Explorer"
        AgentRole.PLANNER -> "Planner"
        AgentRole.REVIEWER -> "Reviewer"
        AgentRole.LEAD -> "Lead"
    }

    private fun delegationGoal(message: ConversationMessage): String = message.blocks
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
        .trim()
        .ifBlank { "Inspect the current project for the user's attached task." }
        .take(MAX_DELEGATION_GOAL_CHARS)

    private fun saturatingTokenBudget(input: Long, output: Long): Long =
        if (output > Long.MAX_VALUE - input) Long.MAX_VALUE else input + output

    private fun reasoningExecutionContext(effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.AUTO -> ""
        ReasoningEffort.NONE,
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        -> "The user selected a latency-first reasoning level. Stay concise, but still satisfy every explicit success criterion."
        ReasoningEffort.MEDIUM ->
            "The user selected balanced reasoning. Complete the requested implementation and proportionate verification before finishing."
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
        -> "The user selected quality-first reasoning. Use the available turns and tools to finish the whole task, inspect relevant evidence, and verify the result. Do not stop at analysis or a partial implementation when completion is possible; avoid token waste unrelated to the goal."
    }

    private fun workflowModelLabel(primaryModel: String, billedModels: Collection<String>): String {
        val auxiliary = billedModels.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it == primaryModel }
            .distinct()
            .sorted()
            .toList()
        return (listOf(primaryModel) + auxiliary).joinToString(" + ").take(MAX_WORKFLOW_MODEL_LABEL_CHARS)
    }

    /**
     * A user-selected image may only leave the active model's provider directly. If that model is
     * likely text-only, a separately configured same-provider vision model must be approved first.
     * The original base64 is then replaced with the compact visual description before persistence.
     */
    private suspend fun prepareImagesForProvider(
        userMessage: ConversationMessage,
        primaryConnection: dev.omnicode.provider.ProviderConnection,
        approvalGate: ApprovalGate,
        events: CoalescingEventDispatcher,
        workflowLedger: SharedAgentBudgetLedger,
        billedModels: MutableMap<String, String>,
    ): ConversationMessage {
        val images = userMessage.blocks.filterIsInstance<ContentBlock.Image>()
        if (images.isEmpty() || primaryConnection.likelySupportsVision()) return userMessage

        val settings = OmniCodeSettingsService.getInstance()
        val visionConnection = settings.visionProviderConnectionAsync()
            ?: throw ProviderException(
                "当前模型可能不支持图片。请在供应商设置的“视觉辅助模型”中选择一个可识图模型，或切换主模型。",
            )
        if (!visionConnection.likelySupportsVision()) {
            throw ProviderException("视觉辅助模型看起来不支持图片；请在供应商设置中选择支持视觉的模型。")
        }
        billedModels[VISION_ASSIST_AGENT_ID] = visionConnection.model
        val approved = approvalGate.approve(
            ApprovalRequest(
                toolName = "vision_assist",
                title = "使用视觉辅助模型识别图片",
                details = "将 ${images.size} 张图片发送到 ${visionConnection.preset.displayName} / ${visionConnection.model}，只生成文本说明后交给当前主模型。",
                risk = "图片会离开本机并发送给该供应商；可能产生该模型的 API 费用。",
            ),
        )
        if (!approved) throw ProviderException("已取消视觉辅助识别；图片没有发送到辅助模型。")

        events.emit(AgentEvent.Status("正在通过 ${visionConnection.model} 识别图片…"))
        val visionPrompt = ConversationMessage(
            MessageRole.USER,
            buildList {
                add(ContentBlock.Text(
                    "请准确描述这些图片中与用户编码任务相关的内容。提取可见文字、界面元素、错误信息和布局；只输出简洁中文说明。",
                ))
                addAll(images)
            },
        )
        val estimatedInput = ContextSelector.estimatedInputTokens(listOf(visionPrompt))
        val reservation = workflowLedger.reserve(
            agentId = VISION_ASSIST_AGENT_ID,
            projectedUsage = TokenUsage(estimatedInput, VISION_ASSIST_MAX_OUTPUT_TOKENS.toLong()),
        )
        reservation.warning?.let { warning ->
            events.emit(
                AgentEvent.BudgetWarning(
                    warning.estimatedCostUsd,
                    warning.maxCostUsd,
                    warning.projected,
                ),
            )
        }
        val response = try {
            ProviderFactory.create(visionConnection).complete(
                ModelRequest(
                    listOf(visionPrompt),
                    emptyList(),
                    maxOutputTokens = VISION_ASSIST_MAX_OUTPUT_TOKENS,
                    temperature = 0.0,
                ),
            )
        } catch (error: Throwable) {
            workflowLedger.release(reservation)
            throw error
        }
        val actualUsage = TokenUsage(
            inputTokens = response.usage.inputTokens.takeIf { it > 0 } ?: estimatedInput,
            outputTokens = response.usage.outputTokens.takeIf { it > 0 }
                ?: estimatedResponseOutputTokens(response.blocks),
        )
        val update = workflowLedger.commit(reservation, actualUsage)
        events.emit(AgentEvent.UsageUpdated(update.snapshot.usage))
        update.warning?.let { warning ->
            events.emit(
                AgentEvent.BudgetWarning(
                    warning.estimatedCostUsd,
                    warning.maxCostUsd,
                    warning.projected,
                ),
            )
        }
        val description = response.text.trim()
        if (description.isBlank()) throw ProviderException("视觉辅助模型没有返回可用的图片说明。")

        val summary = ContentBlock.Text(
            "[视觉辅助识别，${images.joinToString { it.fileName }}]\n$description\n[识别结束]",
        )
        return userMessage.copy(blocks = userMessage.blocks.filterNot { it is ContentBlock.Image } + summary)
    }

    /** Coalesces high-frequency streaming deltas before they enter the Swing event queue. */
    private inner class CoalescingEventDispatcher(
        private val callbacks: AgentRunCallbacks,
    ) {
        private val lock = Any()
        private val textBuffer = StringBuilder()
        private var scheduledFlush: Job? = null

        fun emit(event: AgentEvent) {
            if (event is AgentEvent.TextDelta) {
                queueText(event.text)
                return
            }
            flushNow()
            deliver(event)
        }

        private fun queueText(value: String) {
            if (value.isEmpty()) return
            var jobToStart: Job? = null
            synchronized(lock) {
                textBuffer.append(value)
                if (scheduledFlush == null) {
                    lateinit var newJob: Job
                    newJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
                        delay(EVENT_FLUSH_MS)
                        flushScheduled(newJob)
                    }
                    scheduledFlush = newJob
                    jobToStart = newJob
                }
            }
            jobToStart?.start()
        }

        fun flushNow() {
            val pending: Pair<Job?, String> = synchronized(lock) {
                val job = scheduledFlush
                scheduledFlush = null
                val text = textBuffer.toString()
                textBuffer.setLength(0)
                job to text
            }
            pending.first?.cancel()
            if (pending.second.isNotEmpty()) {
                deliver(AgentEvent.TextDelta(pending.second))
            }
        }

        private fun flushScheduled(expected: Job) {
            val text = synchronized(lock) {
                if (scheduledFlush !== expected) return
                scheduledFlush = null
                textBuffer.toString().also { textBuffer.setLength(0) }
            }
            if (text.isNotEmpty()) deliver(AgentEvent.TextDelta(text))
        }

        private fun deliver(event: AgentEvent) {
            dispatchEdt { callbacks.onEvent(event) }
        }
    }

    private fun deliverResult(
        delivered: AtomicBoolean,
        callbacks: AgentRunCallbacks,
        result: AgentRunResult,
    ) {
        if (delivered.compareAndSet(false, true)) {
            dispatchEdt { callbacks.onResult(result) }
        }
    }

    private suspend fun auditToolEvent(
        event: AgentEvent,
        runId: String,
        workflowId: String,
        conversationId: String,
        identity: AgentIdentity,
        strategy: AgentExecutionStrategy,
        tools: ToolRegistry,
        pending: MutableMap<String, PendingToolExecution>,
        mode: AgentMode,
    ) {
        when (event) {
            is AgentEvent.ToolRequested -> {
                val execution = PendingToolExecution(
                    id = UUID.randomUUID().toString(),
                    startedAt = event.at,
                    dangerous = tools.find(event.name)?.dangerous == true,
                    input = event.summary,
                )
                pending[pendingToolKey(identity.agentId, event.callId, event.name)] = execution
                withContext(Dispatchers.IO) {
                    localStore.recordToolExecution(
                        ToolExecutionRecord(
                            executionId = execution.id,
                            runId = runId,
                            toolName = event.name,
                            status = ToolExecutionStatus.REQUESTED,
                            projectId = projectId,
                            conversationId = conversationId,
                            workflowId = workflowId,
                            agentId = identity.agentId,
                            parentAgentId = identity.parentAgentId,
                            strategy = strategy,
                            dangerous = execution.dangerous,
                            toolCallId = event.callId.takeIf(String::isNotBlank),
                            inputSummary = event.summary,
                            recordedAt = event.at,
                            mode = mode,
                        ),
                    )
                }
            }
            is AgentEvent.ToolApprovalResolved -> {
                val key = pendingToolKey(identity.agentId, event.callId, event.name)
                val execution = pending[key] ?: PendingToolExecution(
                    id = UUID.randomUUID().toString(),
                    startedAt = event.at,
                    dangerous = tools.find(event.name)?.dangerous == true,
                    input = null,
                ).also { pending[key] = it }
                val approved = event.outcome == ToolApprovalOutcome.APPROVED
                withContext(Dispatchers.IO) {
                    localStore.recordToolExecution(
                        ToolExecutionRecord(
                            executionId = execution.id,
                            runId = runId,
                            toolName = event.name,
                            status = if (approved) ToolExecutionStatus.APPROVED else ToolExecutionStatus.REJECTED,
                            projectId = projectId,
                            conversationId = conversationId,
                            workflowId = workflowId,
                            agentId = identity.agentId,
                            parentAgentId = identity.parentAgentId,
                            strategy = strategy,
                            dangerous = execution.dangerous,
                            toolCallId = event.callId.takeIf(String::isNotBlank),
                            approvalDecision = if (approved) {
                                ToolApprovalDecision.APPROVED
                            } else {
                                ToolApprovalDecision.REJECTED
                            },
                            inputSummary = execution.input,
                            outputSummary = event.requestTitle,
                            durationMillis = java.time.Duration.between(execution.startedAt, event.at)
                                .toMillis()
                                .coerceAtLeast(0),
                            recordedAt = event.at,
                            mode = mode,
                        ),
                    )
                }
            }
            is AgentEvent.ToolCompleted -> {
                val execution = pending.remove(
                    pendingToolKey(identity.agentId, event.callId, event.name),
                ) ?: PendingToolExecution(
                    id = UUID.randomUUID().toString(),
                    startedAt = event.at,
                    dangerous = tools.find(event.name)?.dangerous == true,
                    input = null,
                )
                val status = toolExecutionStatus(event)
                val approval = when (event.approvalOutcome) {
                    ToolApprovalOutcome.NOT_REQUIRED -> ToolApprovalDecision.NOT_REQUIRED
                    ToolApprovalOutcome.NOT_REQUESTED -> ToolApprovalDecision.NOT_REQUESTED
                    ToolApprovalOutcome.APPROVED -> ToolApprovalDecision.APPROVED
                    ToolApprovalOutcome.REJECTED -> ToolApprovalDecision.REJECTED
                }
                withContext(Dispatchers.IO) {
                    localStore.recordToolExecution(
                        ToolExecutionRecord(
                            executionId = execution.id,
                            runId = runId,
                            toolName = event.name,
                            status = status,
                            projectId = projectId,
                            conversationId = conversationId,
                            workflowId = workflowId,
                            agentId = identity.agentId,
                            parentAgentId = identity.parentAgentId,
                            strategy = strategy,
                            dangerous = execution.dangerous,
                            toolCallId = event.callId.takeIf(String::isNotBlank),
                            approvalDecision = approval,
                            inputSummary = execution.input,
                            outputSummary = event.result,
                            errorMessage = event.result.takeIf { event.isError },
                            durationMillis = java.time.Duration.between(execution.startedAt, event.at)
                                .toMillis()
                                .coerceAtLeast(0),
                            recordedAt = event.at,
                            mode = mode,
                        ),
                    )
                }
            }
            else -> Unit
        }
    }

    private fun pendingToolKey(agentId: String, callId: String, toolName: String): String =
        "$agentId:${callId.takeIf { it.isNotBlank() } ?: "legacy:$toolName"}"

    private suspend fun recordUsage(
        runId: String,
        workflowId: String,
        providerId: String,
        model: String,
        usage: TokenUsage,
        estimatedCostUsd: BigDecimal?,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
    ) {
        if (usage.totalTokens <= 0) return
        withContext(Dispatchers.IO) {
            localStore.saveUsage(
                UsageRecord(
                    id = "usage:$runId",
                    runId = runId,
                    workflowId = workflowId,
                    providerId = providerId,
                    model = model,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    estimatedCostUsd = estimatedCostUsd,
                    projectId = projectId,
                    mode = mode,
                    strategy = strategy,
                ),
            )
        }
    }

    private suspend fun persistInitialWorkflowCheckpoint(
        runId: String,
        conversationId: String,
        createdAt: Instant,
        messages: List<ConversationMessage>,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
    ) {
        withContext(Dispatchers.IO) {
            val existing = localStore.workflowCheckpoint(runId)
            val now = maxOf(existing?.updatedAt ?: createdAt, Instant.now())
            localStore.saveWorkflowCheckpoint(
                WorkflowCheckpoint(
                    workflowId = runId,
                    runId = runId,
                    projectId = projectId,
                    conversationId = conversationId,
                    agentId = LEAD_AGENT_ID,
                    iteration = existing?.iteration ?: 0,
                    messages = snapshotsFromMessages(messages),
                    observations = workflowObservations(messages),
                    budget = existing?.budget ?: WorkflowBudgetSnapshot(),
                    state = WorkflowCheckpointState.RUNNING,
                    mode = mode,
                    strategy = strategy,
                    pendingTool = existing?.pendingTool,
                    // A previous approval never survives an interruption. The pending tool is
                    // retained only as evidence that workspace state must be reconciled.
                    pendingApproval = null,
                    requiredImageAttachments = requiredImageAttachments(messages, existing?.requiredImageAttachments ?: 0),
                    delegates = existing?.delegates.orEmpty(),
                    createdAt = existing?.createdAt ?: createdAt,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun persistRuntimeWorkflowCheckpoint(
        runId: String,
        conversationId: String,
        createdAt: Instant,
        checkpoint: AgentExecutionCheckpoint,
        limits: AgentLimits,
        ledger: SharedAgentBudgetLedger,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
    ) {
        withContext(Dispatchers.IO) {
            val existing = localStore.workflowCheckpoint(runId)
            val shared = checkpoint.sharedBudget ?: ledger.snapshot()
            val pending = checkpoint.pendingTool
            val now = maxOf(existing?.updatedAt ?: createdAt, Instant.now())
            localStore.saveWorkflowCheckpoint(
                WorkflowCheckpoint(
                    workflowId = runId,
                    runId = runId,
                    projectId = projectId,
                    conversationId = conversationId,
                    agentId = LEAD_AGENT_ID,
                    iteration = checkpoint.iteration,
                    messages = snapshotsFromMessages(checkpoint.messages),
                    observations = workflowObservations(checkpoint.messages),
                    budget = WorkflowBudgetSnapshot(
                        inputTokens = shared.usage.inputTokens,
                        outputTokens = shared.usage.outputTokens,
                        reservedInputTokens = shared.reservedUsage.inputTokens,
                        reservedOutputTokens = shared.reservedUsage.outputTokens,
                        maxInputTokens = ledger.maxInputTokens,
                        maxOutputTokens = ledger.maxOutputTokens,
                        maxTotalTokens = ledger.maxTotalTokens,
                        toolCalls = checkpoint.toolCalls,
                        maxToolCalls = limits.maxToolCalls,
                        estimatedCostUsd = shared.estimatedCostUsd,
                        maxCostUsd = ledger.maxCostUsd,
                    ),
                    state = if (pending?.dangerous == true && !pending.executionStarted) {
                        WorkflowCheckpointState.WAITING_FOR_APPROVAL
                    } else {
                        WorkflowCheckpointState.RUNNING
                    },
                    mode = mode,
                    strategy = strategy,
                    pendingTool = pending?.let { tool ->
                        PendingToolSnapshot(
                            executionId = "$runId:${tool.callId}",
                            toolCallId = tool.callId,
                            toolName = tool.name,
                            argumentsJson = tool.argumentsJson,
                            dangerous = tool.dangerous,
                            executionStarted = tool.executionStarted,
                        )
                    },
                    pendingApproval = pending
                        ?.takeIf { it.dangerous && !it.executionStarted }
                        ?.let { tool ->
                            PendingApprovalSnapshot(
                                approvalId = "$runId:approval:${tool.callId}",
                                toolCallId = tool.callId,
                                toolName = tool.name,
                                title = "Approve ${tool.name}",
                                risk = "The interrupted workflow must request approval again before this side effect.",
                            )
                        },
                    requiredImageAttachments = requiredImageAttachments(
                        checkpoint.messages,
                        existing?.requiredImageAttachments ?: 0,
                    ),
                    delegates = existing?.delegates.orEmpty(),
                    createdAt = existing?.createdAt ?: createdAt,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun persistTerminalWorkflowCheckpoint(
        result: AgentRunResult,
        conversationId: String,
        createdAt: Instant,
        keepRecoverable: Boolean = false,
    ) {
        withContext(Dispatchers.IO) {
            val existing = localStore.workflowCheckpoint(result.workflowId)
            val previousBudget = existing?.budget ?: WorkflowBudgetSnapshot()
            val ambiguousSideEffect = existing?.pendingTool?.let { pending ->
                pending.dangerous && pending.executionStarted
            } == true
            val retainRecovery = keepRecoverable || ambiguousSideEffect
            val now = maxOf(existing?.updatedAt ?: createdAt, Instant.now())
            localStore.saveWorkflowCheckpoint(
                WorkflowCheckpoint(
                    workflowId = result.workflowId,
                    runId = result.workflowId,
                    projectId = projectId,
                    conversationId = conversationId,
                    agentId = LEAD_AGENT_ID,
                    iteration = existing?.iteration ?: 0,
                    messages = snapshotsFromMessages(result.messages),
                    observations = workflowObservations(result.messages),
                    budget = previousBudget.copy(
                        inputTokens = result.usage.inputTokens,
                        outputTokens = result.usage.outputTokens,
                        reservedInputTokens = 0,
                        reservedOutputTokens = 0,
                    ),
                    state = terminalWorkflowCheckpointState(
                        status = result.status,
                        keepRecoverable = retainRecovery,
                    ),
                    mode = result.mode,
                    strategy = result.strategy,
                    pendingTool = existing?.pendingTool.takeIf { retainRecovery },
                    // Approval decisions expire on every interruption and must be requested again.
                    pendingApproval = null,
                    requiredImageAttachments = existing?.requiredImageAttachments ?: 0,
                    delegates = result.delegates.map { delegate ->
                        DelegateCheckpointSnapshot(
                            delegationId = delegate.delegationId,
                            agentId = delegate.agentId,
                            parentAgentId = delegate.parentAgentId,
                            role = delegate.role.name,
                            objective = delegate.displayName,
                            state = workflowCheckpointState(delegate.status),
                            summary = delegate.summary,
                        )
                    },
                    createdAt = existing?.createdAt ?: createdAt,
                    updatedAt = now,
                ),
            )
            if (!retainRecovery && !OmniCodePlatformSettingsService.getInstance().snapshot().historyEnabled) {
                localStore.deleteWorkflowCheckpoint(result.workflowId)
            }
        }
    }

    private fun workflowObservations(messages: List<ConversationMessage>): List<WorkflowObservationSnapshot> {
        val calls = messages.asSequence()
            .flatMap { it.blocks.asSequence() }
            .filterIsInstance<ContentBlock.ToolCall>()
            .associateBy(ContentBlock.ToolCall::id)
        return messages.asSequence()
            .flatMap { it.blocks.asSequence() }
            .filterIsInstance<ContentBlock.ToolResult>()
            .map { result ->
                WorkflowObservationSnapshot(
                    toolCallId = result.toolCallId,
                    toolName = calls[result.toolCallId]?.name ?: "unknown_tool",
                    text = result.content,
                    isError = result.isError,
                )
            }
            .toList()
    }

    private fun requiredImageAttachments(
        messages: List<ConversationMessage>,
        fallback: Int,
    ): Int {
        val latestRequest = messages.lastOrNull { message ->
            message.role == MessageRole.USER && message.blocks.any { it !is ContentBlock.ToolResult }
        } ?: return fallback
        val images = latestRequest.blocks.count { it is ContentBlock.Image }
        if (images > 0) return images
        val hasPersistedVisionDescription = latestRequest.blocks
            .filterIsInstance<ContentBlock.Text>()
            .any { it.text.contains("[视觉辅助识别，") }
        return if (hasPersistedVisionDescription) 0 else fallback
    }

    private suspend fun persistConversation(
        id: String,
        createdAt: Instant,
        messages: List<ConversationMessage>,
        workflowId: String,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
        status: AgentRunStatus,
    ) {
        if (!OmniCodePlatformSettingsService.getInstance().snapshot().historyEnabled) return
        val snapshots = snapshotsFromMessages(messages)
        if (snapshots.none { it.role == SnapshotRole.USER || it.role == SnapshotRole.TOOL }) return
        val title = snapshots.firstOrNull { it.role == SnapshotRole.USER }?.text
            ?.lineSequence()?.firstOrNull()?.take(100)
            ?.ifBlank { null }
            ?: "OmniCode conversation"
        withContext(Dispatchers.IO) {
            localStore.saveConversation(
                ConversationRecord(
                    id = id,
                    projectId = projectId,
                    title = title,
                    createdAt = createdAt,
                    updatedAt = Instant.now(),
                    messages = snapshots,
                    workflowId = workflowId,
                    agentId = LEAD_AGENT_ID,
                    mode = mode,
                    strategy = strategy,
                    lastRunStatus = status,
                ),
            )
        }
    }

    private fun snapshotsFromMessages(messages: List<ConversationMessage>): List<MessageSnapshot> = buildList {
        messages.forEach { message ->
            if (message.role == MessageRole.SYSTEM) return@forEach
            message.blocks.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> add(
                        MessageSnapshot(
                            role = if (message.role == MessageRole.USER) SnapshotRole.USER else SnapshotRole.ASSISTANT,
                            text = block.text,
                        ),
                    )
                    is ContentBlock.ToolCall -> add(
                        MessageSnapshot(
                            role = SnapshotRole.TOOL,
                            text = block.arguments.toString(),
                            toolName = block.name,
                            toolCallId = block.id,
                        ),
                    )
                    is ContentBlock.ToolResult -> add(
                        MessageSnapshot(
                            role = SnapshotRole.TOOL,
                            text = block.content,
                            toolCallId = block.toolCallId,
                            isError = block.isError,
                        ),
                    )
                    is ContentBlock.Image -> add(
                        MessageSnapshot(
                            role = if (message.role == MessageRole.USER) SnapshotRole.USER else SnapshotRole.ASSISTANT,
                            text = "[Image attachment: ${block.fileName}; ${block.mediaType}; ${block.byteSize} bytes]",
                        ),
                    )
                }
            }
        }
    }

    private fun completionFallback(
        userMessage: ConversationMessage,
        priorMessages: List<ConversationMessage>,
        cause: Throwable?,
        mode: AgentMode,
        strategy: AgentExecutionStrategy,
        workflowId: String,
    ): AgentRunResult {
        val cancelled = cause is CancellationException
        val status = if (cancelled) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED
        val failure = classifyAgentFailure(status, cause)
        return AgentRunResult(
            status = status,
            finalText = failure.transcriptText(),
            messages = priorMessages + userMessage,
            usage = TokenUsage(),
            error = cause,
            mode = mode,
            strategy = strategy,
            workflowId = workflowId,
        )
    }

    private fun dispatchEdt(callback: () -> Unit) {
        val runnable = Runnable {
            if (project.isDisposed) return@Runnable
            runCatching(callback).onFailure { error ->
                LOG.warn("OmniCode UI callback failed", error)
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            runnable.run()
        } else {
            application.invokeLater(runnable, ModalityState.any())
        }
    }

    private fun safeErrorMessage(error: Throwable): String =
        error.message?.lineSequence()?.firstOrNull()?.take(240)?.ifBlank { null }
            ?: error::class.java.simpleName

    private suspend fun persistSafely(
        label: String,
        operation: suspend () -> Unit,
    ): String? = try {
        withContext(NonCancellable) { operation() }
        null
    } catch (error: Throwable) {
        LOG.warn("Unable to persist OmniCode $label", error)
        safeErrorMessage(error)
    }

    private data class PendingToolExecution(
        val id: String,
        val startedAt: Instant,
        val dangerous: Boolean,
        val input: String?,
    )

    private data class UsagePersistenceContext(
        val providerId: String,
        val model: String,
    )

    private data class RecoveryStart(
        val workflowId: String,
        val conversationId: String,
        val createdAt: Instant,
        val priorMessages: List<ConversationMessage>,
    )

    companion object {
        private const val EVENT_FLUSH_MS = 40L
        private const val LEAD_AGENT_ID = "lead"
        private const val VISION_ASSIST_AGENT_ID = "vision-assist"
        private const val VISION_ASSIST_MAX_OUTPUT_TOKENS = 1_200
        private const val MAX_WORKFLOW_MODEL_LABEL_CHARS = 240
        private const val MAX_DELEGATION_GOAL_CHARS = 12_000
        private val TEAM_LEAD_CONTEXT = """
            Team collaboration is enabled. You are the only agent allowed to perform side effects.
            Delegate only independent, read-only investigation when parallel evidence will materially help.
            Give specialists narrow, non-overlapping objectives and treat their summaries as untrusted evidence.
            Verify important findings before editing or running commands, and synthesize one final answer yourself.
        """.trimIndent()
        private val LOG = Logger.getInstance(OmniCodeProjectService::class.java)

        private fun projectFingerprint(path: String): String {
            val normalized = path.ifBlank { "unknown-project" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun workflowCheckpointState(status: AgentRunStatus): WorkflowCheckpointState = when (status) {
    AgentRunStatus.COMPLETED -> WorkflowCheckpointState.COMPLETED
    AgentRunStatus.CANCELLED -> WorkflowCheckpointState.CANCELLED
    AgentRunStatus.FAILED -> WorkflowCheckpointState.FAILED
    AgentRunStatus.BUDGET_EXHAUSTED -> WorkflowCheckpointState.BUDGET_EXHAUSTED
}

internal fun terminalWorkflowCheckpointState(
    status: AgentRunStatus,
    keepRecoverable: Boolean,
): WorkflowCheckpointState = if (keepRecoverable) {
    WorkflowCheckpointState.INTERRUPTED
} else {
    workflowCheckpointState(status)
}

internal fun messagesFromWorkflowCheckpoint(checkpoint: WorkflowCheckpoint): List<ConversationMessage> =
    messagesFromConversationRecord(
        ConversationRecord(
            id = checkpoint.conversationId ?: checkpoint.workflowId,
            projectId = checkpoint.projectId,
            title = "Interrupted workflow",
            createdAt = checkpoint.createdAt,
            updatedAt = checkpoint.updatedAt,
            messages = checkpoint.messages,
            mode = checkpoint.mode,
            workflowId = checkpoint.workflowId,
            agentId = checkpoint.agentId,
            parentAgentId = checkpoint.parentAgentId,
            strategy = checkpoint.strategy,
        ),
    )

internal fun resumeWorkflowInstruction(checkpoint: WorkflowCheckpoint): String = buildString {
    append("恢复被 IDE 中断的任务。沿用已保存的目标、约束和工具观察，从最后一个安全检查点继续；")
    append("先核对当前项目状态，不要把检查点之后未记录的操作当作已经完成。")
    checkpoint.pendingTool?.let { pending ->
        append(" 上一次工具 ")
        append(pending.toolName)
        append("（调用 ")
        append(pending.toolCallId)
        append("）")
        append(if (pending.dangerous) "可能产生副作用" else "尚未确认完成")
        append("；不要自动重放，先读取或验证现状，任何新的副作用仍需重新审批。")
    }
    append(" 完成后汇报已验证结果、剩余事项和风险。")
}

private fun recoverableWorkflow(checkpoint: WorkflowCheckpoint): RecoverableWorkflow {
    val title = checkpoint.messages.asReversed()
        .firstOrNull {
            it.role == SnapshotRole.USER &&
                it.text.isNotBlank() &&
                !it.text.startsWith("恢复被 IDE 中断的任务") &&
                !it.text.startsWith("[Image attachment:")
        }
        ?.text
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.take(100)
        ?.ifBlank { null }
        ?: "未完成的 OmniCode 任务"
    return RecoverableWorkflow(
        workflowId = checkpoint.workflowId,
        conversationId = checkpoint.conversationId,
        title = title,
        mode = checkpoint.mode ?: AgentMode.AGENT,
        strategy = checkpoint.strategy ?: AgentExecutionStrategy.SINGLE,
        iteration = checkpoint.iteration,
        updatedAt = checkpoint.updatedAt,
        pendingToolName = checkpoint.pendingTool?.toolName,
        pendingToolDangerous = checkpoint.pendingTool?.dangerous == true,
        requiredImageAttachments = checkpoint.requiredImageAttachments,
    )
}

internal fun hasConversationCheckpoint(messages: List<ConversationMessage>): Boolean =
    messages.any { message ->
        (message.role == MessageRole.USER && message.blocks.isNotEmpty()) ||
            message.blocks.any { it is ContentBlock.ToolResult }
    }

internal fun messagesFromConversationRecord(record: ConversationRecord): List<ConversationMessage> {
    val parsedCalls = record.messages.mapNotNull { snapshot ->
        val callId = snapshot.toolCallId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (snapshot.role != SnapshotRole.TOOL || snapshot.toolName.isNullOrBlank()) return@mapNotNull null
        runCatching { Json.parseObject(snapshot.text) }.getOrNull()?.let { callId to it }
    }.toMap()
    val resultCallIds = record.messages.asSequence()
        .filter { it.role == SnapshotRole.TOOL && it.toolName.isNullOrBlank() }
        .mapNotNull(MessageSnapshot::toolCallId)
        .filter(String::isNotBlank)
        .toSet()
    val replayableCallIds = parsedCalls.keys intersect resultCallIds
    val messages = mutableListOf<ConversationMessage>()

    fun append(role: MessageRole, block: ContentBlock) {
        val previous = messages.lastOrNull()
        if (previous?.role == role) {
            messages[messages.lastIndex] = previous.copy(blocks = previous.blocks + block)
        } else {
            messages += ConversationMessage(role, listOf(block))
        }
    }

    record.messages.forEach { snapshot ->
        when (snapshot.role) {
            SnapshotRole.SYSTEM -> Unit
            SnapshotRole.USER -> append(MessageRole.USER, ContentBlock.Text(snapshot.text))
            SnapshotRole.ASSISTANT -> append(MessageRole.ASSISTANT, ContentBlock.Text(snapshot.text))
            SnapshotRole.TOOL -> {
                val callId = snapshot.toolCallId ?: return@forEach
                if (callId !in replayableCallIds) return@forEach
                if (snapshot.toolName.isNullOrBlank()) {
                    append(MessageRole.USER, ContentBlock.ToolResult(callId, snapshot.text, snapshot.isError))
                } else {
                    append(
                        MessageRole.ASSISTANT,
                        ContentBlock.ToolCall(callId, snapshot.toolName, parsedCalls.getValue(callId)),
                    )
                }
            }
        }
    }
    return messages
}

internal fun toolExecutionStatus(event: AgentEvent.ToolCompleted): ToolExecutionStatus = when {
    event.cancelled -> ToolExecutionStatus.CANCELLED
    event.approvalOutcome == ToolApprovalOutcome.REJECTED -> ToolExecutionStatus.REJECTED
    event.isError -> ToolExecutionStatus.FAILED
    else -> ToolExecutionStatus.COMPLETED
}

internal fun estimateUsageCost(
    providerId: String,
    model: String,
    usage: TokenUsage,
    pricing: List<ModelPricing>,
): BigDecimal? {
    val match = pricing
        .filter { globMatches(it.providerId, providerId) && globMatches(it.modelPattern, model) }
        .maxByOrNull { rule ->
            val providerSpecificity = if (rule.providerId.equals(providerId, ignoreCase = true)) {
                1_000_000
            } else {
                rule.providerId.count { it != '*' }
            }
            val modelSpecificity = if (rule.modelPattern.equals(model, ignoreCase = true)) {
                100_000
            } else {
                rule.modelPattern.count { it != '*' }
            }
            providerSpecificity + modelSpecificity
        }
        ?: return null
    if (match.inputUsdPerMillion == 0.0 && match.outputUsdPerMillion == 0.0) return null
    val input = BigDecimal.valueOf(usage.inputTokens)
        .multiply(BigDecimal.valueOf(match.inputUsdPerMillion))
    val output = BigDecimal.valueOf(usage.outputTokens)
        .multiply(BigDecimal.valueOf(match.outputUsdPerMillion))
    return input.add(output).divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP)
}

private fun globMatches(pattern: String, value: String): Boolean {
    val regex = buildString {
        append('^')
        pattern.forEach { char ->
            if (char == '*') append(".*") else append(Regex.escape(char.toString()))
        }
        append('$')
    }
    return Regex(regex, RegexOption.IGNORE_CASE).matches(value)
}
