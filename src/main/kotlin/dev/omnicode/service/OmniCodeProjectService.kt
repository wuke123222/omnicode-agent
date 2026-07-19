package dev.omnicode.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentEngine
import dev.omnicode.agent.AgentCostBudget
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentEventSink
import dev.omnicode.agent.AgentLimits
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunResult
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.agent.ToolApprovalOutcome
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.TokenUsage
import dev.omnicode.model.UserSubmission
import dev.omnicode.mcp.McpToolConnector
import dev.omnicode.mcp.ApprovedMcpHttpClientConnector
import dev.omnicode.provider.ProviderFactory
import dev.omnicode.provider.ProviderException
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.likelySupportsVision
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.OmniCodeLocalStore
import dev.omnicode.persistence.PersistenceRetention
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.ToolApprovalDecision
import dev.omnicode.persistence.ToolExecutionRecord
import dev.omnicode.persistence.ToolExecutionStatus
import dev.omnicode.persistence.UsageRecord
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.ModelPricing
import dev.omnicode.settings.OmniCodeSettingsService
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.tool.RunCommandTool
import dev.omnicode.tool.SandboxedMcpProcessLauncher
import dev.omnicode.tool.ToolRegistry
import dev.omnicode.util.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
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
    private var conversationHistory: List<ConversationMessage> = emptyList()
    private var conversationId: String = UUID.randomUUID().toString()
    private var conversationCreatedAt: Instant = Instant.now()
    private var conversationMode: AgentMode = AgentMode.AGENT

    /**
     * Starts one agent run for this project. Concurrent runs are rejected so tool
     * observations and the in-memory conversation cannot interleave.
     */
    fun startRun(
        userMessage: String,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean = startRun(userMessage, AgentMode.AGENT, approvalGate, callbacks)

    fun startRun(
        userMessage: String,
        mode: AgentMode,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean = startRun(UserSubmission(userMessage), mode, approvalGate, callbacks)

    fun startRun(
        submission: UserSubmission,
        mode: AgentMode,
        approvalGate: ApprovalGate,
        callbacks: AgentRunCallbacks,
    ): Boolean {
        val prompt = submission.prompt.trim()
        if (prompt.isEmpty() && submission.attachments.isEmpty()) return false
        val userMessage = submission.copy(prompt = prompt).toMessage()

        val resultDelivered = AtomicBoolean(false)
        val priorMessages: List<ConversationMessage>
        val runId = UUID.randomUUID().toString()
        val activeConversationId: String
        val activeConversationCreatedAt: Instant
        lateinit var job: Job

        synchronized(stateLock) {
            if (activeJob != null) return false
            priorMessages = conversationHistory.toList()
            activeConversationId = conversationId
            activeConversationCreatedAt = conversationCreatedAt
            job = coroutineScope.launch(start = CoroutineStart.LAZY) {
                val result = executeAgent(
                    userMessage = userMessage,
                    priorMessages = priorMessages,
                    approvalGate = approvalGate,
                    callbacks = callbacks,
                    runId = runId,
                    activeConversationId = activeConversationId,
                    mode = mode,
                )
                if (updateConversationCheckpoint(result)) {
                    persistSafely("conversation history") {
                        persistConversation(
                            id = activeConversationId,
                            createdAt = activeConversationCreatedAt,
                            messages = result.messages,
                            mode = result.mode,
                            status = result.status,
                        )
                    }
                }
                deliverResult(resultDelivered, callbacks, result)
            }
            activeJob = job
        }

        dispatchEdt { callbacks.onRunningChanged(true) }
        job.invokeOnCompletion { cause ->
            val wasCurrentRun = synchronized(stateLock) {
                if (activeJob === job) {
                    activeJob = null
                    true
                } else {
                    false
                }
            }

            if (resultDelivered.compareAndSet(false, true)) {
                val fallback = completionFallback(userMessage, priorMessages, cause, mode)
                if (updateConversationCheckpoint(fallback)) {
                    coroutineScope.launch {
                        persistSafely("fallback conversation history") {
                            persistConversation(
                                id = activeConversationId,
                                createdAt = activeConversationCreatedAt,
                                messages = fallback.messages,
                                mode = fallback.mode,
                                status = fallback.status,
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

    fun cancelCurrentRun(): Boolean {
        val job = synchronized(stateLock) { activeJob } ?: return false
        job.cancel(CancellationException("Cancelled by user"))
        return true
    }

    fun isRunning(): Boolean = synchronized(stateLock) { activeJob != null }

    fun clearHistory(): Boolean = synchronized(stateLock) {
        if (activeJob != null) return false
        conversationHistory = emptyList()
        conversationId = UUID.randomUUID().toString()
        conversationCreatedAt = Instant.now()
        conversationMode = AgentMode.AGENT
        true
    }

    fun historySnapshot(): List<ConversationMessage> = synchronized(stateLock) {
        conversationHistory.toList()
    }

    fun conversationModeSnapshot(): AgentMode = synchronized(stateLock) { conversationMode }

    private fun updateConversationCheckpoint(result: AgentRunResult): Boolean {
        if (!hasConversationCheckpoint(result.messages)) return false
        synchronized(stateLock) {
            conversationHistory = result.messages.toList()
            conversationMode = result.mode
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
        mode: AgentMode,
    ): AgentRunResult {
        val eventDispatcher = CoalescingEventDispatcher(callbacks)
        var requestMessages = priorMessages + userMessage
        return try {
            val connection = OmniCodeSettingsService.getInstance().providerConnectionAsync()
            val provider = ProviderFactory.create(connection)
            val preparedUserMessage = prepareImagesForProvider(userMessage, connection, approvalGate, eventDispatcher)
            requestMessages = priorMessages + preparedUserMessage
            val maxOutputTokens = OmniCodeSettingsService.getInstance().snapshot().maxOutputTokens
            val platform = OmniCodePlatformSettingsService.getInstance().snapshot()
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
                val registry = ToolRegistry(
                    runCommandTool = RunCommandTool(platform.sandboxMode),
                    additionalTools = skillTools + mcpBundle?.tools.orEmpty(),
                )
                val pendingToolExecutions = mutableMapOf<String, PendingToolExecution>()
                val auditFailureReported = AtomicBoolean(false)
                val runtime = platform.agentRuntime
                val engine = AgentEngine(
                    project = project,
                    provider = provider,
                    approvalGate = approvalGate,
                    tools = registry,
                    limits = AgentLimits(
                        maxIterations = runtime.maxIterations,
                        maxToolCalls = runtime.maxToolCalls,
                        maxWallTime = java.time.Duration.ofSeconds(runtime.maxWallTimeSeconds.toLong()),
                        maxToolTime = java.time.Duration.ofSeconds(runtime.maxToolTimeSeconds.toLong()),
                        maxInputTokens = runtime.maxInputTokens,
                        maxOutputTokensPerTurn = maxOutputTokens,
                        maxOutputTokens = maxOf(runtime.maxOutputTokens, maxOutputTokens.toLong()),
                        providerMaxAttempts = runtime.providerMaxAttempts,
                    ),
                    costBudget = AgentCostBudget(
                        maxUsd = runtime.maxRunCostUsd?.let(BigDecimal::valueOf),
                        warningRatio = runtime.costWarningRatio,
                        estimator = { usage ->
                            estimateUsageCost(
                                connection.preset.id,
                                connection.model,
                                usage,
                                platform.pricing,
                            )
                        },
                    ),
                    events = AgentEventSink { event ->
                        if (event is AgentEvent.ToolRequested ||
                            event is AgentEvent.ToolApprovalResolved ||
                            event is AgentEvent.ToolCompleted
                        ) {
                            val failure = persistSafely("tool audit") {
                                auditToolEvent(
                                    event = event,
                                    runId = runId,
                                    conversationId = activeConversationId,
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
                )
                val result = engine.run(preparedUserMessage, priorMessages, mode)
                persistSafely("usage") {
                    recordUsage(
                        runId = runId,
                        providerId = connection.preset.id,
                        model = connection.model,
                        usage = result.usage,
                        pricing = platform.pricing,
                        mode = mode,
                    )
                }?.let { failure ->
                    eventDispatcher.emit(AgentEvent.Status("Usage could not be persisted: $failure"))
                }
                result
            } finally {
                mcpBundle?.close()
            }
        } catch (cancelled: CancellationException) {
            AgentRunResult(
                status = AgentRunStatus.CANCELLED,
                finalText = "Run cancelled.",
                messages = requestMessages,
                usage = TokenUsage(),
                error = cancelled,
                mode = mode,
            )
        } catch (error: Throwable) {
            AgentRunResult(
                status = AgentRunStatus.FAILED,
                finalText = "Unable to start the agent: ${safeErrorMessage(error)}",
                messages = requestMessages,
                usage = TokenUsage(),
                error = error,
                mode = mode,
            )
        } finally {
            eventDispatcher.flushNow()
        }
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
        val description = ProviderFactory.create(visionConnection).complete(
            ModelRequest(listOf(visionPrompt), emptyList(), maxOutputTokens = 1_200, temperature = 0.0),
        ).text.trim()
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
        conversationId: String,
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
                pending[pendingToolKey(event.callId, event.name)] = execution
                withContext(Dispatchers.IO) {
                    localStore.recordToolExecution(
                        ToolExecutionRecord(
                            executionId = execution.id,
                            runId = runId,
                            toolName = event.name,
                            status = ToolExecutionStatus.REQUESTED,
                            projectId = projectId,
                            conversationId = conversationId,
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
                val key = pendingToolKey(event.callId, event.name)
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
                val execution = pending.remove(pendingToolKey(event.callId, event.name)) ?: PendingToolExecution(
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

    private fun pendingToolKey(callId: String, toolName: String): String =
        callId.takeIf { it.isNotBlank() } ?: "legacy:$toolName"

    private suspend fun recordUsage(
        runId: String,
        providerId: String,
        model: String,
        usage: TokenUsage,
        pricing: List<ModelPricing>,
        mode: AgentMode,
    ) {
        if (usage.totalTokens <= 0) return
        val cost = estimateUsageCost(providerId, model, usage, pricing)
        withContext(Dispatchers.IO) {
            localStore.recordUsage(
                UsageRecord(
                    runId = runId,
                    providerId = providerId,
                    model = model,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    estimatedCostUsd = cost,
                    projectId = projectId,
                    mode = mode,
                ),
            )
        }
    }

    private suspend fun persistConversation(
        id: String,
        createdAt: Instant,
        messages: List<ConversationMessage>,
        mode: AgentMode,
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
                    mode = mode,
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
    ): AgentRunResult {
        val cancelled = cause is CancellationException
        return AgentRunResult(
            status = if (cancelled) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED,
            finalText = if (cancelled) "Run cancelled." else "Agent run ended without a result.",
            messages = priorMessages + userMessage,
            usage = TokenUsage(),
            error = cause,
            mode = mode,
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

    companion object {
        private const val EVENT_FLUSH_MS = 40L
        private val LOG = Logger.getInstance(OmniCodeProjectService::class.java)

        private fun projectFingerprint(path: String): String {
            val normalized = path.ifBlank { "unknown-project" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
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
