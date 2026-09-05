package dev.omnicode.ui.web

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.AgentExecutionStrategy
import dev.omnicode.agent.AgentMode
import dev.omnicode.agent.AgentRunResult
import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.agent.ChatEventEnvelopeMapper
import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.UserAttachment
import dev.omnicode.model.UserSubmission
import dev.omnicode.mcp.McpCatalogQuery
import dev.omnicode.mcp.McpCatalogEntry
import dev.omnicode.mcp.ApprovedMcpHttpClientConnector
import dev.omnicode.mcp.McpClient
import dev.omnicode.mcp.McpMarketplaceCatalog
import dev.omnicode.mcp.McpMarketplaceDirectory
import dev.omnicode.mcp.McpStdioClient
import dev.omnicode.mcp.validateMcpHttpEndpoint
import dev.omnicode.mcp.oauth.McpOAuthLoginApproval
import dev.omnicode.mcp.oauth.McpOAuthSessionManager
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.WorkflowEventRecord
import dev.omnicode.persistence.WorkflowEventType
import dev.omnicode.plan.PlanBoard
import dev.omnicode.plan.PlanBoardService
import dev.omnicode.plan.PlanExecutionPolicy
import dev.omnicode.plan.PlanReviewAction
import dev.omnicode.plan.PlanStepState
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ProviderProtocol
import dev.omnicode.provider.ReasoningEffort
import dev.omnicode.provider.CliTool
import dev.omnicode.provider.CliToolDiscovery
import dev.omnicode.provider.LocalAgentEngineContract
import dev.omnicode.provider.LocalAgentEngineRegistry
import dev.omnicode.review.TaskChangeReviewService
import dev.omnicode.service.AgentRunCallbacks
import dev.omnicode.service.ConnectionDiagnosticCheck
import dev.omnicode.service.ConnectionDiagnosticsService
import dev.omnicode.service.OmniCodeProjectService
import dev.omnicode.service.OmniCodeV3Migration
import dev.omnicode.service.ProviderModelCatalogService
import dev.omnicode.settings.OmniCodeCredentialStore
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.OmniCodeSettingsService
import dev.omnicode.settings.OmniCodeSettingsSnapshot
import dev.omnicode.settings.ProjectContextSettingsService
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpHttpCredentialStore
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpServerState
import dev.omnicode.settings.McpTransport
import dev.omnicode.settings.PromptTemplateState
import dev.omnicode.settings.ProviderSecrets
import dev.omnicode.settings.SandboxMode
import dev.omnicode.settings.SkillSourceState
import dev.omnicode.settings.TokenTrackerIntegration
import dev.omnicode.settings.TokenTrackerDashboardState
import dev.omnicode.settings.TOKEN_TRACKER_DASHBOARD_URL
import dev.omnicode.settings.TOKEN_TRACKER_DOCUMENTATION_URL
import dev.omnicode.settings.tokenTrackerStartCommand
import dev.omnicode.settings.inspectSkillSource
import dev.omnicode.settings.normalizeOAuthScopes
import dev.omnicode.settings.parseCommandLine
import dev.omnicode.ui.AttachmentIntake
import dev.omnicode.ui.AttachmentIntakeResult
import dev.omnicode.ui.ImageAttachmentInspection
import dev.omnicode.ui.ModalApprovalGate
import dev.omnicode.ui.inspectImageAttachment
import dev.omnicode.ui.isSafeTextAttachment
import dev.omnicode.ui.planStepExecutionPrompt
import dev.omnicode.ui.planStepTranscriptText
import dev.omnicode.tool.SandboxedMcpProcessLauncher
import dev.omnicode.util.Json
import dev.omnicode.workshop.WorkshopSettingsService
import dev.omnicode.workshop.WorkshopCatalog
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.swing.SwingUtilities
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CCGUI-style three-view shell. All repository mutations still travel through
 * [OmniCodeProjectService], its approval gate, and the existing sandbox/tool policy.
 * The browser receives presentation data only; it never receives PasswordSafe values.
 */
class OmniCodeWebViewPanel(
    private val project: Project,
    private val service: OmniCodeProjectService,
) : JPanel(BorderLayout()), Disposable {
    private val disposed = AtomicBoolean(false)
    private val pageGeneration = PAGE_GENERATION.incrementAndGet()
    private val ready = AtomicBoolean(false)
    private val activeClientInstanceId = AtomicReference<String?>(null)
    private val pendingMessages = ConcurrentLinkedQueue<String>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val marketplaceDirectory = McpMarketplaceDirectory()
    private val marketplaceLoadGeneration = AtomicLong()
    @Volatile private var marketplaceLoadJob: Job? = null
    @Volatile private var diagnosticsJob: Job? = null
    @Volatile private var marketplaceEntries: List<McpCatalogEntry> = McpMarketplaceCatalog.entries
    private val oauthSessions by lazy(::McpOAuthSessionManager)
    private val mcpHttpCredentials by lazy(McpHttpCredentialStore::getInstance)
    private var browser: JBCefBrowser? = null
    private var query: JBCefJSQuery? = null
    private val planBoardService = PlanBoardService.getInstance(project)
    /** Prevents double-clicks from racing review mutations for one workflow. */
    private val reviewMutationsInFlight = ConcurrentHashMap.newKeySet<String>()
    private var activePlanStepId: String? = null
    private var activePlanConversationId: String? = null
    private var autoContinueApprovedPlan = false
    private var planningRevisionBoardId: String? = null

    init {
        OmniCodeV3Migration.migrate(project)
        planBoardService.activateConversation(service.conversationIdSnapshot())
        planBoardService.addListener(this) { board -> emitPlan(board) }
        isOpaque = true
        if (runCatching { JBCefApp.isSupported() }.getOrDefault(false)) {
            initializeBrowser()
        } else {
            add(buildUnsupportedPanel(), BorderLayout.CENTER)
        }
    }

    fun startNewChat() {
        // The detached task may finish against its captured conversation, but automatic plan
        // continuation must never jump into the newly visible chat.
        if (activePlanStepId != null) autoContinueApprovedPlan = false
        service.startDetachedConversation()
        val sessionId = service.conversationIdSnapshot()
        planBoardService.activateConversation(sessionId)
        emit("session.reset", jsonObject {
            addProperty("sessionId", sessionId)
            addProperty("mode", AgentMode.AGENT.name)
            addProperty("strategy", AgentExecutionStrategy.AUTO.name)
        })
        sendHistory()
    }

    fun canOpenNewChat(): Boolean = !disposed.get()

    fun showHistory() = emit("navigation", jsonObject { addProperty("view", "history") })

    fun openSettings() = emit("navigation", jsonObject { addProperty("view", "settings") })

    /** Prefills the persistent React composer without submitting or reading the referenced file. */
    fun prefillChat(prompt: String) {
        if (prompt.isBlank()) return
        emit("composer.prefill", jsonObject { addProperty("text", prompt.take(MAX_PROMPT_CHARS)) })
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        pendingMessages.clear()
        marketplaceLoadJob?.cancel()
        diagnosticsJob?.cancel()
        backgroundScope.cancel()
        query?.let { if (!it.isDisposed) it.dispose() }
        query = null
        browser?.let { if (!it.isDisposed) Disposer.dispose(it) }
        browser = null
    }

    private fun initializeBrowser() {
        val embedded = JBCefBrowser()
        embedded.setOpenLinksInExternalBrowser(false)
        embedded.disableNavigation()
        embedded.setPageBackgroundColor("#151719")
        val jsQuery = JBCefJSQuery.create(embedded as JBCefBrowserBase)
        jsQuery.addHandler { raw ->
            val response = handleBridgeMessage(raw)
            JBCefJSQuery.Response(response)
        }
        browser = embedded
        query = jsQuery
        add(embedded.component, BorderLayout.CENTER)

        val html = javaClass.getResourceAsStream(WEB_RESOURCE)?.use { stream ->
            stream.readNBytes(MAX_WEB_RESOURCE_BYTES).toString(StandardCharsets.UTF_8)
        }
        if (html.isNullOrBlank()) {
            removeAll()
            add(buildUnsupportedPanel("UI 资源缺失，请重新安装 OmniCode。"), BorderLayout.CENTER)
            return
        }
        val bridgeScript = """
            <script>
            window.__OMNICODE_PAGE_GENERATION__ = $pageGeneration;
            window.omnicodeSend = function(message) { ${jsQuery.inject("message")} };
            </script>
        """.trimIndent()
        embedded.loadHTML(html.replace("</head>", "$bridgeScript\n</head>"), "https://omnicode.invalid/")
    }

    private fun buildUnsupportedPanel(
        message: String = "当前 IDE 运行时不支持 JCEF。OmniCode 保留安全边界，但 3.0 主界面无法启动。",
    ): JPanel = JPanel(BorderLayout()).apply {
        add(JBLabel(message), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("打开帮助").apply {
                addActionListener { BrowserUtil.browse(CCGUI_HELP_URL) }
            })
        }, BorderLayout.SOUTH)
    }

    private fun handleBridgeMessage(raw: String): String {
        if (disposed.get()) return "disposed"
        if (raw.length > MAX_BRIDGE_MESSAGE_CHARS) {
            emitNotification("请求过大，已拒绝。")
            return "too-large"
        }
        val command = runCatching { Json.parseObject(raw) }.getOrElse {
            emitNotification("无法解析界面请求。")
            return "invalid-json"
        }
        val schemaVersion = command.intOrNull("schemaVersion")
        val generation = command.longOrNull("pageGeneration")
        val name = command.stringOrNull("command")
        val requestId = command.stringOrNull("requestId")?.takeIf(SAFE_BRIDGE_ID::matches)
        val clientInstanceId = command.stringOrNull("clientInstanceId")?.takeIf(SAFE_BRIDGE_ID::matches)
        if (schemaVersion != BRIDGE_SCHEMA_VERSION || generation != pageGeneration || name !in ALLOWED_COMMANDS) {
            LOG.warn("Rejected stale or unsupported WebView command: $name")
            emitCommandError(requestId, name, "界面请求已失效，请重新载入 OmniCode。")
            return "rejected"
        }
        if (requestId == null || clientInstanceId == null) {
            LOG.warn("Rejected WebView command without a valid request or client instance ID: $name")
            emitCommandError(requestId, name, "界面请求标识无效，请重新载入 OmniCode。")
            return "rejected"
        }
        if (name != "frontend.ready" && activeClientInstanceId.get() != clientInstanceId) {
            LOG.warn("Rejected WebView command from an inactive page instance: $name")
            emitCommandError(requestId, name, "页面已更新，此操作未执行。请在当前页面重试。")
            return "stale-client"
        }
        val payload = command.objectOrEmpty("payload")
        return runCatching {
            if (name == "frontend.ready") activeClientInstanceId.set(clientInstanceId)
            dispatchCommand(name!!, payload)
            emitCommandAccepted(requestId, name)
            "accepted"
        }.getOrElse { error ->
            LOG.warn("WebView command failed: $name", error)
            emitCommandError(requestId, name, safeUiError(error))
            "failed"
        }
    }

    private fun dispatchCommand(command: String, payload: JsonObject) {
        when (command) {
            "frontend.ready" -> {
                ready.set(true)
                flushPendingMessages()
                sendBootstrap()
            }
            "session.new" -> startNewChat()
            "session.cancel" -> {
                val targetSession = payload.stringOrNull("sessionId")
                    ?.takeIf(String::isNotBlank)
                    ?.take(256)
                    ?: service.conversationIdSnapshot()
                if (!service.cancelRun(targetSession)) emitNotification("该会话当前没有运行中的任务。")
            }
            "session.send" -> sendSession(payload)
            "session.list" -> sendHistory()
            "session.load" -> restoreSession(payload.requiredString("id", 256))
            "session.delete" -> deleteSession(payload.requiredString("id", 256))
            "session.favorite" -> setConversationFavorite(payload)
            "session.export" -> exportConversation(payload.requiredString("id", 256))
            "session.fork" -> forkConversation(payload.requiredString("id", 256))
            "session.rewind" -> rewindConversation(payload)
            "settings.snapshot" -> emit("settings", settingsSnapshot())
            "settings.saveProvider" -> saveProvider(payload)
            "provider.select" -> selectProvider(payload)
            "settings.sandbox" -> updateSandbox(payload)
            "settings.historyRetention" -> updateHistoryRetention(payload)
            "settings.usageRetention" -> updateUsageRetention(payload)
            "settings.commitAi" -> updateCommitAi(payload)
            "settings.agentRuntime" -> updateAgentRuntime(payload)
            "settings.projectContext" -> updateProjectContext(payload)
            "settings.pet" -> updatePet(payload)
            "provider.models" -> refreshModels()
            "plan.updateStep" -> updatePlanStep(payload)
            "plan.approve" -> approvePlanStep(payload)
            "plan.approveAll" -> planBoardService.approveAll()
            "plan.skip" -> mutatePlanStep(payload, planBoardService::skip)
            "plan.restore" -> mutatePlanStep(payload, planBoardService::restore)
            "plan.retry" -> mutatePlanStep(payload, planBoardService::retry)
            "plan.review" -> reviewPlan(payload)
            "plan.continue" -> continuePlanning()
            "plan.pause" -> pausePlanExecution()
            "review.snapshot" -> emitLatestReview()
            "review.keepFile" -> mutateReviewFile(payload, keep = true)
            "review.rollbackFile" -> mutateReviewFile(payload, keep = false)
            "review.keepHunk" -> mutateReviewHunk(payload, keep = true)
            "review.rollbackHunk" -> mutateReviewHunk(payload, keep = false)
            "review.rollbackTask" -> rollbackReviewTask(payload)
            "navigation.openFile" -> openFile(payload)
            "navigation.openExternal" -> openExternal(payload)
            "navigation.view" -> navigateToView(payload)
            "composer.prefill" -> emit("composer.prefill", payload)
            "composer.searchFiles" -> searchProjectFiles(payload)
            "ui.notify" -> emitNotification(payload.stringOrNull("message").orEmpty().take(500))
            "usage.open" -> openTokenTracker()
            "usage.status" -> sendTokenTrackerStatus()
            "usage.copyStartCommand" -> copyTokenTrackerStartCommand()
            "connection.diagnose" -> runConnectionDiagnostics()
            "runtime.probe" -> probeLocalRuntimes()
            "mcp.catalog" -> sendMcpCatalog(payload)
            "mcp.installDraft" -> installMcpDraft(payload)
            "mcp.save" -> saveMcpServer(payload)
            "mcp.test" -> testMcpServer(payload)
            "mcp.delete" -> deleteMcpServer(payload)
            "mcp.saveBearer" -> saveMcpBearer(payload)
            "mcp.clearBearer" -> clearMcpBearer(payload)
            "mcp.oauthDiscover" -> discoverMcpOAuth(payload)
            "mcp.oauthLogin" -> loginMcpOAuth(payload)
            "mcp.oauthLogout" -> logoutMcpOAuth(payload)
            "prompt.save" -> savePrompt(payload)
            "prompt.delete" -> deletePrompt(payload)
            "skill.save" -> saveSkill(payload)
            "skill.delete" -> deleteSkill(payload)
        }
    }

    private fun sendTokenTrackerStatus() {
        backgroundScope.launch {
            val status = TokenTrackerIntegration().inspect()
            emit("usage.status", jsonObject {
                addProperty("state", status.dashboard.state.name)
                addProperty("detail", status.dashboard.detail)
                addProperty("cliInstalled", status.cliExecutable != null)
                addProperty("dashboardUrl", TOKEN_TRACKER_DASHBOARD_URL)
                addProperty("installCommand", tokenTrackerStartCommand())
                addProperty("documentationUrl", TOKEN_TRACKER_DOCUMENTATION_URL)
            })
        }
    }

    private fun openTokenTracker() {
        backgroundScope.launch {
            val status = TokenTrackerIntegration().inspect()
            if (status.dashboard.state == TokenTrackerDashboardState.READY) {
                BrowserUtil.browse(TOKEN_TRACKER_DASHBOARD_URL)
                emitNotification("已打开 TokenTracker 本地面板。")
            } else {
                emitNotification("TokenTracker 尚未就绪：${status.dashboard.detail} 请先运行 ${tokenTrackerStartCommand()}，再重新检查。")
            }
        }
    }

    private fun copyTokenTrackerStartCommand() {
        val command = tokenTrackerStartCommand()
        runCatching {
            val selection = java.awt.datatransfer.StringSelection(command)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }.onSuccess {
            emitNotification("已复制 TokenTracker 启动命令。")
        }.onFailure {
            emitNotification("无法访问系统剪贴板；请手动运行：$command")
        }
    }

    private fun navigateToView(payload: JsonObject) {
        val view = payload.requiredString("view", 32).lowercase()
        require(view in setOf("chat", "history", "settings")) { "不支持的页面。" }
        emit("navigation", jsonObject { addProperty("view", view) })
    }

    /**
     * Runs the redacted, bounded connection checks off the EDT. The report contains only
     * capability/status data; credential values and raw process/network errors never cross the
     * WebView boundary.
     */
    private fun runConnectionDiagnostics() {
        diagnosticsJob?.cancel()
        emit("diagnostics", jsonObject {
            addProperty("state", "running")
            addProperty("overallStatus", "RUNNING")
            addProperty("durationMillis", 0L)
            add("checks", JsonArray())
        })
        diagnosticsJob = backgroundScope.launch {
            try {
                val report = ConnectionDiagnosticsService.getInstance().diagnoseCurrentConfiguration()
                emit("diagnostics", jsonObject {
                    addProperty("state", "success")
                    addProperty("schemaVersion", report.schemaVersion)
                    addProperty("generatedAt", report.generatedAt.toString())
                    addProperty("durationMillis", report.durationMillis)
                    addProperty("overallStatus", report.overallStatus.name)
                    addProperty("passCount", report.checks.count { it.status.name == "PASS" })
                    addProperty("warnCount", report.checks.count { it.status.name == "WARN" })
                    addProperty("failCount", report.checks.count { it.status.name == "FAIL" })
                    addProperty("skipCount", report.checks.count { it.status.name == "SKIP" })
                    add("checks", JsonArray().apply {
                        report.checks.take(MAX_DIAGNOSTIC_CHECKS).forEach { check -> addDiagnosticCheck(check) }
                    })
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emit("diagnostics", jsonObject {
                    addProperty("state", "error")
                    addProperty("overallStatus", "FAIL")
                    addProperty("message", "连接诊断未完成。请稍后重试；如果持续失败，请检查 IDE 网络代理和日志。")
                    add("checks", JsonArray())
                })
            }
        }
    }

    private fun JsonArray.addDiagnosticCheck(check: ConnectionDiagnosticCheck) {
        add(jsonObject {
            addProperty("id", check.id)
            addProperty("category", check.category.name)
            addProperty("title", check.title)
            addProperty("status", check.status.name)
            addProperty("summary", check.summary.take(MAX_DIAGNOSTIC_TEXT_CHARS))
            addProperty("durationMillis", check.durationMillis)
            check.recoverySuggestion?.takeIf(String::isNotBlank)?.let {
                addProperty("recoverySuggestion", it.take(MAX_DIAGNOSTIC_TEXT_CHARS))
            }
        })
    }

    private fun sendSession(payload: JsonObject) {
        val rawText = payload.stringOrNull("text").orEmpty().take(MAX_PROMPT_CHARS)
        val commandMode = when {
            rawText.isComposerCommand("/claude-plan") -> AgentMode.CLAUDE_PLAN
            rawText.isComposerCommand("/plan") -> AgentMode.PLAN
            rawText.isComposerCommand("/review") -> AgentMode.RESEARCH
            rawText.isComposerCommand("/agent") -> AgentMode.AGENT
            else -> null
        }
        val text = when (commandMode) {
            AgentMode.CLAUDE_PLAN -> rawText.removeComposerCommand("/claude-plan")
            AgentMode.PLAN -> rawText.removeComposerCommand("/plan")
            AgentMode.RESEARCH -> rawText.removeComposerCommand("/review")
            AgentMode.AGENT -> rawText.removeComposerCommand("/agent")
            else -> rawText
        }
        val mode = commandMode ?: enumValueOrDefault(payload.stringOrNull("mode"), AgentMode.AGENT)
        val strategy = enumValueOrDefault(payload.stringOrNull("strategy"), AgentExecutionStrategy.AUTO)
        val attachments = decodeAttachments(payload.arrayOrEmpty("attachments"))
        if (text.isBlank() && attachments.isEmpty()) return

        val sessionId = service.conversationIdSnapshot()
        val preservedPlanBoardId = planningRevisionBoardId.takeIf {
            mode == AgentMode.PLAN || mode == AgentMode.CLAUDE_PLAN
        }
        val turnId = UUID.randomUUID().toString()
        val clientMessageId = payload.stringOrNull("clientMessageId")
            ?.takeIf(SAFE_CLIENT_MESSAGE_ID::matches)
            ?: "$turnId-user"
        val optimistic = jsonObject {
            addProperty("schemaVersion", BRIDGE_SCHEMA_VERSION)
            addProperty("pageGeneration", pageGeneration)
            addProperty("sessionId", sessionId)
            addProperty("turnId", turnId)
            // Reuse the page-created id so React merges the native acknowledgement
            // into the optimistic row instead of rendering the same message twice.
            addProperty("blockId", clientMessageId)
            addProperty("sequence", 0L)
            addProperty("kind", "message.user")
            addProperty("phase", "completed")
            addProperty("at", Instant.now().toString())
            add("payload", jsonObject {
                addProperty("text", text.ifBlank { attachments.joinToString("、") { it.fileName } })
                add("attachments", JsonArray().apply { attachments.forEach { add(it.fileName) } })
            })
        }
        emit("event", optimistic)

        val mapper = ChatEventEnvelopeMapper(pageGeneration, sessionId, turnId)
        val accepted = service.startRun(
            UserSubmission(text, attachments),
            mode,
            strategy,
            ModalApprovalGate(project),
            AgentRunCallbacks(
                onRunningChanged = { running ->
                    emit("running", jsonObject {
                        addProperty("sessionId", sessionId)
                        addProperty("running", running)
                    })
                    if (!running) sendHistory()
                },
                onEvent = { event -> emit("event", mapper.map(event).toJson()) },
                onResult = { result -> finishSessionRun(sessionId, turnId, mode, result, preservedPlanBoardId) },
            ),
        )
        if (!accepted) {
            emit("send.rejected", jsonObject {
                addProperty("sessionId", sessionId)
                addProperty("clientMessageId", clientMessageId)
                addProperty("message", "当前会话已有任务在运行。你可以停止它，或新建会话并行处理。")
            })
        } else if (mode == AgentMode.PLAN || mode == AgentMode.CLAUDE_PLAN) {
            planningRevisionBoardId = null
        }
        if (accepted) sendHistory()
    }

    private fun finishSessionRun(
        sessionId: String,
        turnId: String,
        mode: AgentMode,
        result: AgentRunResult,
        preservedPlanBoardId: String? = null,
    ) {
        val executingStep = activePlanStepId
        val executingConversation = activePlanConversationId
        if (executingStep != null && executingConversation == sessionId) {
            finishPlanStep(executingConversation, executingStep, result)
        } else if ((mode == AgentMode.PLAN || mode == AgentMode.CLAUDE_PLAN) && result.status == AgentRunStatus.COMPLETED) {
            planBoardService.replaceFromPlan(result.finalText, mode, preservedPlanBoardId, sessionId)
        }
        // Keep the live block tree mounted. Replacing it with a persistence snapshot at the
        // terminal boundary used to remove tool/phase cards and made real-time and restored
        // conversations visibly different. Explicit history navigation still uses
        // `session.loaded`; a completed live turn receives one stable terminal envelope.
        emit("event", jsonObject {
            addProperty("schemaVersion", BRIDGE_SCHEMA_VERSION)
            addProperty("pageGeneration", pageGeneration)
            addProperty("sessionId", sessionId)
            addProperty("turnId", turnId)
            addProperty("blockId", "$turnId-result")
            addProperty("sequence", Long.MAX_VALUE)
            addProperty("kind", "run.completed")
            addProperty("phase", when (result.status) {
                AgentRunStatus.COMPLETED -> "completed"
                AgentRunStatus.CANCELLED -> "warning"
                AgentRunStatus.FAILED, AgentRunStatus.BUDGET_EXHAUSTED -> "failed"
            })
            addProperty("at", Instant.now().toString())
            add("payload", jsonObject {
                addProperty("title", when (result.status) {
                    AgentRunStatus.COMPLETED -> "任务完成"
                    AgentRunStatus.CANCELLED -> "任务已停止"
                    AgentRunStatus.FAILED -> "任务未完成"
                    AgentRunStatus.BUDGET_EXHAUSTED -> "任务达到运行边界"
                })
                result.error?.message?.let { addProperty("message", it.take(1_000)) }
            })
        })
        if (result.workflowId.isNotBlank()) emitReview(result.workflowId, sessionId)
        sendHistory()
    }

    private fun updatePlanStep(payload: JsonObject) {
        val stepId = payload.requiredString("stepId", 128)
        val text = payload.requiredRawString("text", 2_000)
        if (!planBoardService.updateStepText(stepId, text)) emitNotification("计划步骤未改变或当前不可编辑。")
    }

    private fun approvePlanStep(payload: JsonObject) {
        val stepId = payload.requiredString("stepId", 128)
        val approved = payload.booleanOrNull("approved") ?: false
        if (!planBoardService.approve(stepId, approved)) emitNotification("该步骤当前不可审批。")
    }

    private fun mutatePlanStep(payload: JsonObject, operation: (String) -> Boolean) {
        if (!operation(payload.requiredString("stepId", 128))) emitNotification("该步骤状态未改变。")
    }

    private fun reviewPlan(payload: JsonObject) {
        val action = enumValueOrDefault(payload.stringOrNull("action"), PlanReviewAction.REJECT_AND_KEEP_PLANNING)
        if (!planBoardService.applyReviewAction(action)) {
            emitNotification("至少批准一个步骤后才能执行。")
            return
        }
        when (action) {
            PlanReviewAction.APPROVE_MANUAL -> executeApprovedPlan(PlanExecutionPolicy.MANUAL_STEP_CONFIRMATION)
            PlanReviewAction.APPROVE_AUTO -> executeApprovedPlan(PlanExecutionPolicy.AUTO_AGENT)
            PlanReviewAction.CONTINUE_PLANNING -> continuePlanning()
            PlanReviewAction.REJECT_AND_KEEP_PLANNING -> emitNotification("计划保留为只读探索；可继续编辑后重新审批。")
        }
    }

    private fun executeApprovedPlan(policy: PlanExecutionPolicy) {
        // A different conversation may continue in the background. Plan execution is scoped to
        // the conversation currently visible in the panel; a global running check made an
        // unrelated chat block the user's current plan.
        if (service.isConversationBusy(service.conversationIdSnapshot())) {
            emitNotification("当前任务仍在运行；请先停止后再执行计划。")
            return
        }
        val request = planBoardService.requestExecution(policy) ?: run {
            emitNotification("计划未批准、已被编辑或没有可执行步骤。")
            return
        }
        val step = planBoardService.startExecution(request) ?: run {
            emitNotification("步骤授权已失效，请重新审批当前计划。")
            return
        }
        val board = planBoardService.snapshot() ?: return
        val conversationId = service.conversationIdSnapshot()
        activePlanStepId = step.id
        activePlanConversationId = conversationId
        autoContinueApprovedPlan = policy == PlanExecutionPolicy.AUTO_AGENT
        val mapper = ChatEventEnvelopeMapper(
            pageGeneration,
            conversationId,
            "plan-${board.id}-${step.id}-${step.attempts}",
        )
        val accepted = service.startRun(
            UserSubmission(planStepExecutionPrompt(board, step.id)),
            AgentMode.AGENT,
            AgentExecutionStrategy.AUTO,
            ModalApprovalGate(project),
            AgentRunCallbacks(
                onRunningChanged = { running -> emit("running", jsonObject {
                    addProperty("sessionId", conversationId)
                    addProperty("running", running)
                }) },
                onEvent = { event -> emit("event", mapper.map(event).toJson()) },
                onResult = { result ->
                    finishSessionRun(conversationId, "plan-${board.id}-${step.id}", AgentMode.AGENT, result)
                },
            ),
        )
        if (!accepted) {
            activePlanStepId = null
            activePlanConversationId = null
            autoContinueApprovedPlan = false
            planBoardService.markFailed(step.id, "任务未启动；当前会话可能正被占用")
            emitNotification("计划步骤未能启动。")
        } else {
            emit("plan.execution", jsonObject {
                addProperty("stepId", step.id)
                addProperty("transcript", planStepTranscriptText(board, step.id))
            })
        }
    }

    private fun finishPlanStep(conversationId: String, stepId: String, result: AgentRunResult) {
        activePlanStepId = null
        activePlanConversationId = null
        when (result.status) {
            AgentRunStatus.COMPLETED -> planBoardService.markCompleted(conversationId, stepId)
            AgentRunStatus.CANCELLED -> {
                if (planBoardService.snapshot(conversationId)?.steps?.any { it.id == stepId && it.state == PlanStepState.PAUSED } != true) {
                    planBoardService.markFailed(conversationId, stepId, "执行已取消")
                }
                autoContinueApprovedPlan = false
            }
            AgentRunStatus.FAILED -> {
                planBoardService.markFailed(conversationId, stepId, result.error?.message ?: "步骤执行失败")
                autoContinueApprovedPlan = false
            }
            AgentRunStatus.BUDGET_EXHAUSTED -> {
                planBoardService.pauseRunning(conversationId)
                autoContinueApprovedPlan = false
            }
        }
        if (result.status == AgentRunStatus.COMPLETED && autoContinueApprovedPlan && service.conversationIdSnapshot() == conversationId) {
            ApplicationManager.getApplication().invokeLater {
                if (!disposed.get()) executeApprovedPlan(PlanExecutionPolicy.AUTO_AGENT)
            }
        }
    }

    private fun continuePlanning() {
        val board = planBoardService.snapshot() ?: run {
            emitNotification("当前没有可继续完善的计划。")
            return
        }
        if (service.isConversationBusy(service.conversationIdSnapshot())) {
            emitNotification("请先停止当前任务，再继续规划。")
            return
        }
        planningRevisionBoardId = board.id
        emit("composer.prefill", jsonObject {
            addProperty("mode", board.sourceMode.name)
            addProperty("text", buildString {
                append("继续完善计划 ").append(board.sourceFingerprint).append("。仅做只读探索，不修改文件。\n\n")
                board.steps.forEachIndexed { index, step ->
                    append(index + 1).append(". [")
                        .append(if (step.state == PlanStepState.COMPLETED) 'x' else ' ')
                        .append("] ").append(step.text).append(" · ").append(step.state.name).append('\n')
                }
            }.take(MAX_PROMPT_CHARS))
        })
    }

    private fun pausePlanExecution() {
        autoContinueApprovedPlan = false
        val conversationId = activePlanConversationId
        if (activePlanStepId != null && conversationId != null && service.cancelRun(conversationId)) {
            planBoardService.pauseRunning(conversationId)
            emitNotification("正在安全暂停当前计划步骤。")
        } else emitNotification("当前没有执行中的计划步骤。")
    }

    private fun restoreSession(id: String) {
        service.restoreConversation(id) { restored ->
            if (restored) {
                val sessionId = service.conversationIdSnapshot()
                planBoardService.activateConversation(sessionId)
                if (service.isConversationRunning(sessionId)) {
                    emit("session.loaded", jsonObject {
                        addProperty("sessionId", sessionId)
                        addProperty("running", true)
                        addProperty("mode", service.conversationModeSnapshot().name)
                        addProperty("strategy", service.conversationStrategySnapshot().name)
                        add("blocks", conversationBlocks(service.historySnapshot(), sessionId))
                    })
                    // The initial checkpoint is deliberately small and may predate the latest
                    // stage/tool events. Ask the service for its durable + live tail immediately
                    // so switching to a running session does not show an empty/stale transcript.
                    emitCurrentTimeline("session.liveTimeline")
                } else {
                    emitCurrentTimeline("session.loaded")
                }
                emitLatestReview()
            } else emitNotification("无法恢复该会话；它可能已被删除。")
        }
    }

    private fun deleteSession(id: String) {
        service.deleteConversation(id) { deleted ->
            if (deleted) planBoardService.removeConversation(id)
            emitNotification(if (deleted) "会话已删除。" else "无法删除会话。")
            sendHistory()
        }
    }

    private fun setConversationFavorite(payload: JsonObject) {
        val id = payload.requiredString("id", 256)
        val favorite = payload.booleanOrNull("favorite")
            ?: throw IllegalArgumentException("收藏状态无效。")
        service.listConversationHistory { records ->
            val record = records.firstOrNull { it.id == id }
            if (record == null) {
                emitNotification("会话不存在或已被删除。")
                return@listConversationHistory
            }
            OmniCodePlatformSettingsService.getInstance().setConversationFavorite(
                projectId = record.projectId,
                conversationId = record.id,
                favorite = favorite,
            )
            sendHistory()
        }
    }

    /** Exports only the selected conversation messages; workflow metadata and tool output stay
     * native and are intentionally omitted from this user-initiated download. */
    private fun exportConversation(id: String) {
        service.listConversationHistory { records ->
            val record = records.firstOrNull { it.id == id }
            if (record == null) {
                emitNotification("会话不存在或已被删除。")
                return@listConversationHistory
            }
            val title = record.title.trim().ifBlank { "omnicode-session" }
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-')
                .ifBlank { "omnicode-session" }
            val markdown = buildString {
                append("# ").append(record.title.trim().ifBlank { "OmniCode 会话" }).append("\n\n")
                append("导出时间：").append(Instant.now()).append("\n\n")
                record.messages.forEach { message ->
                    append("## ").append(
                        when (message.role) {
                            dev.omnicode.persistence.SnapshotRole.USER -> "用户"
                            dev.omnicode.persistence.SnapshotRole.ASSISTANT -> "OmniCode"
                            dev.omnicode.persistence.SnapshotRole.SYSTEM -> "系统"
                            dev.omnicode.persistence.SnapshotRole.TOOL -> "工具"
                        },
                    ).append("\n\n")
                    append(message.text).append("\n\n")
                }
            }.take(MAX_EXPORT_CHARS)
            emit("session.exported", jsonObject {
                addProperty("filename", "$title.md")
                addProperty("content", markdown)
            })
        }
    }

    private fun forkConversation(id: String) {
        service.forkConversation(id) { nextId ->
            if (nextId == null) {
                emitNotification("无法分叉该会话：没有可恢复的消息快照。")
                return@forkConversation
            }
            planBoardService.activateConversation(nextId)
            emit("session.reset", jsonObject {
                addProperty("sessionId", nextId)
                addProperty("mode", service.conversationModeSnapshot().name)
                addProperty("strategy", service.conversationStrategySnapshot().name)
            })
            emitCurrentTimeline("session.loaded")
            sendHistory()
        }
    }

    private fun rewindConversation(payload: JsonObject) {
        val id = payload.stringOrNull("id")?.takeIf(String::isNotBlank)?.take(256)
            ?: service.conversationIdSnapshot()
        val messageIndex = payload.intOrNull("messageIndex")
            ?: throw IllegalArgumentException("恢复位置无效。")
        require(messageIndex >= 0) { "恢复位置无效。" }
        service.forkConversationAt(id, messageIndex) { nextId ->
            if (nextId == null) {
                emitNotification("无法从该消息恢复：没有可用的持久化快照。")
                return@forkConversationAt
            }
            planBoardService.activateConversation(nextId)
            emit("session.reset", jsonObject {
                addProperty("sessionId", nextId)
                addProperty("mode", service.conversationModeSnapshot().name)
                addProperty("strategy", service.conversationStrategySnapshot().name)
            })
            emitCurrentTimeline("session.loaded")
            sendHistory()
        }
    }

    private fun emitLatestReview() {
        val conversationId = service.conversationIdSnapshot()
        service.listConversationHistory { records ->
            val workflowId = records.firstOrNull { it.id == conversationId }?.workflowId
            if (workflowId.isNullOrBlank()) {
                emit("review.clear", jsonObject { addProperty("sessionId", conversationId) })
            } else {
                emitReview(workflowId, conversationId)
            }
        }
    }

    private fun mutateReviewFile(payload: JsonObject, keep: Boolean) {
        val workflowId = payload.requiredString("workflowId", 256)
        val path = payload.requiredString("path", 4_096)
        enqueueReviewMutation(workflowId, {
            val review = TaskChangeReviewService.getInstance(project)
            if (keep) review.keepFile(workflowId, path) else review.rollbackFile(workflowId, path)
        }, payload.stringOrNull("sessionId")) { sessionId ->
            emitReview(workflowId, sessionId)
            emitNotification(if (keep) "已保留 $path" else "已回退 $path；仍可选择保留来恢复 Agent 版本。")
        }
    }

    private fun mutateReviewHunk(payload: JsonObject, keep: Boolean) {
        val workflowId = payload.requiredString("workflowId", 256)
        val path = payload.requiredString("path", 4_096)
        val hunkId = payload.requiredString("hunkId", 512)
        enqueueReviewMutation(workflowId, {
            val review = TaskChangeReviewService.getInstance(project)
            if (keep) review.keepHunk(workflowId, path, hunkId)
            else review.rollbackHunk(workflowId, path, hunkId)
        }, payload.stringOrNull("sessionId")) { sessionId ->
            emitReview(workflowId, sessionId)
            emitNotification(if (keep) "已保留 $path 的所选变更块。" else "已回退 $path 的所选变更块。")
        }
    }

    private fun rollbackReviewTask(payload: JsonObject) {
        val workflowId = payload.requiredString("workflowId", 256)
        enqueueReviewMutation(workflowId, {
            TaskChangeReviewService.getInstance(project).rollbackTask(workflowId)
        }, payload.stringOrNull("sessionId")) { sessionId ->
            emitReview(workflowId, sessionId)
            emitNotification("已回退本次任务的全部已记录修改；每个文件仍可单独恢复 Agent 版本。")
        }
    }

    private fun enqueueReviewMutation(
        workflowId: String,
        mutation: () -> Unit,
        requestedConversationId: String?,
        onSuccess: (String) -> Unit,
    ) {
        if (!reviewMutationsInFlight.add(workflowId)) {
            emitNotification("该任务的审阅操作仍在处理中，请稍候。")
            return
        }
        // Review actions can arrive after the user switched chats. The session id carried by the
        // review payload is authoritative; falling back to the selected conversation preserves
        // compatibility with older WebViews that did not send it.
        val targetConversationId = requestedConversationId
            ?.takeIf(String::isNotBlank)
            ?.take(256)
            ?: service.conversationIdSnapshot()
        if (!service.beginTaskReviewMutation(targetConversationId)) {
            reviewMutationsInFlight.remove(workflowId)
            emitNotification("该会话仍有任务在运行；请停止任务后再审阅修改。")
            return
        }
        backgroundScope.launch {
            try {
                runCatching { mutation() }
                    .onSuccess { onSuccess(targetConversationId) }
                    .onFailure { emitNotification(actionableReviewError(it)) }
            } finally {
                service.endTaskReviewMutation(targetConversationId)
                reviewMutationsInFlight.remove(workflowId)
            }
        }
    }

    private fun emitReview(workflowId: String, sessionId: String = service.conversationIdSnapshot()) {
        val files = runCatching { TaskChangeReviewService.getInstance(project).listFiles(workflowId) }
            .getOrElse {
                emitNotification(actionableReviewError(it))
                return
            }
        emit("review", jsonObject {
            addProperty("sessionId", sessionId)
            addProperty("workflowId", workflowId)
            add("files", JsonArray().apply {
                files.take(MAX_REVIEW_FILES).forEach { file -> add(jsonObject {
                    addProperty("path", file.relativePath)
                    addProperty("decision", file.decision.name)
                    addProperty("added", file.hunks.sumOf { it.afterLineCount })
                    addProperty("removed", file.hunks.sumOf { it.beforeLineCount })
                    add("hunks", JsonArray().apply {
                        file.hunks.take(MAX_REVIEW_HUNKS_PER_FILE).forEach { hunk -> add(jsonObject {
                            addProperty("id", hunk.id)
                            addProperty("beforeStart", hunk.beforeStartLine)
                            addProperty("afterStart", hunk.afterStartLine)
                            addProperty("before", hunk.beforeText.take(MAX_REVIEW_HUNK_CHARS))
                            addProperty("after", hunk.afterText.take(MAX_REVIEW_HUNK_CHARS))
                            addProperty("beforeCount", hunk.beforeLineCount)
                            addProperty("afterCount", hunk.afterLineCount)
                            addProperty("decision", hunk.decision.name)
                        }) }
                    })
                }) }
            })
        })
    }

    private fun actionableReviewError(error: Throwable): String {
        val detail = error.message?.lineSequence()?.firstOrNull()?.take(500) ?: error::class.java.simpleName
        return if (detail.startsWith("FILE_CONFLICT:")) {
            "$detail。文件已在任务外变化，为避免覆盖你的修改，本次操作已停止；请先打开 Diff 核对。"
        } else "变更审阅失败：$detail"
    }

    private fun sendBootstrap() {
        val sessionId = service.conversationIdSnapshot()
        val cachedModels = service<ProviderModelCatalogService>().cachedCurrent()?.models.orEmpty()
        val initial = jsonObject {
            addProperty("projectName", project.name.trim().ifBlank { "OmniCode" })
            addProperty("sessionId", sessionId)
            // Only the selected conversation controls the visible composer state. Other chats
            // remain independently resumable from History and must not make a new chat appear
            // globally busy.
            addProperty("running", service.isConversationRunning(sessionId))
            addProperty("mode", service.conversationModeSnapshot().name)
            addProperty("strategy", service.conversationStrategySnapshot().name)
            addProperty("providerStatus", "正在检测供应商…")
            addProperty("providerConfigured", false)
            add("blocks", conversationBlocks(service.historySnapshot(), sessionId))
            add("history", JsonArray())
            add("settings", settingsSnapshot())
            add("models", JsonArray().apply { cachedModels.forEach(::add) })
            planBoardService.snapshot()?.let { add("plan", planJson(it)) }
        }
        emit("bootstrap", initial)
        // The frontend is now an authenticated, ready page instance. Probe TokenTracker only
        // after bootstrap so a status response cannot race the ready handshake and be rejected
        // as a stale-client message.
        sendTokenTrackerStatus()
        // Also hydrate running conversations. The service merges its bounded live tail with the
        // durable ledger, so a WebView recreated during a turn can render the current stages.
        emitCurrentTimeline("session.liveTimeline")
        service.refreshProviderStatus { status ->
            emit("provider.status", jsonObject {
                addProperty("configured", status.configured)
                addProperty("text", status.text)
                addProperty("providerName", status.providerName)
                addProperty("model", status.model)
            })
        }
        sendHistory()
        emitLatestReview()
    }

    private fun sendHistory() {
        service.listConversationHistory { records -> emit("history", historyEntries(records)) }
    }

    private fun saveProvider(payload: JsonObject) {
        val providerId = payload.requiredString("providerId", 128)
        val preset = ProviderPresets.all.firstOrNull { it.id == providerId }
            ?: throw IllegalArgumentException("未知供应商。")
        val current = OmniCodeSettingsService.getInstance().snapshotFor(providerId)
        val baseUrl = if (preset.protocol.name.startsWith("CLI_")) {
            "cli://local"
        } else {
            payload.requiredString("baseUrl", 4_096)
        }
        val updated = current.copy(
            baseUrl = baseUrl,
            model = payload.requiredString("model", 512),
            reasoningEffort = ReasoningEffort.fromPersisted(payload.stringOrNull("reasoningEffort").orEmpty()),
        )
        val apiKey = payload.stringOrNull("apiKey").orEmpty()
        if (apiKey.isNotBlank()) {
            require(apiKey.length <= MAX_SECRET_CHARS && apiKey.none(Char::isISOControl)) { "API Key 格式无效。" }
            OmniCodeCredentialStore.getInstance().save(providerId, baseUrl, ProviderSecrets(apiKey = apiKey))
        }
        OmniCodeSettingsService.getInstance().update(updated)
        project.service<ProviderModelCatalogService>().invalidate()
        emitNotification("已保存并启用 ${preset.displayName}。")
        emit("settings", settingsSnapshot())
        service.refreshProviderStatus { status -> emit("provider.status", jsonObject { addProperty("text", status.text) }) }
        refreshModels()
    }

    /**
     * Activates an already persisted provider profile without moving credentials through JCEF.
     * The composer uses this command for its engine picker; editing remains in Settings.
     */
    private fun selectProvider(payload: JsonObject) {
        val providerId = payload.requiredString("providerId", 128)
        val preset = ProviderPresets.all.firstOrNull { it.id == providerId }
            ?: throw IllegalArgumentException("未知供应商。")
        val modelSettings = OmniCodeSettingsService.getInstance()
        modelSettings.update(modelSettings.snapshotFor(providerId))
        project.service<ProviderModelCatalogService>().invalidate()
        emit("settings", settingsSnapshot())
        emitNotification("已切换到 ${preset.displayName}。")
        service.refreshProviderStatus { status ->
            emit("provider.status", jsonObject {
                addProperty("configured", status.configured)
                addProperty("text", status.text)
                addProperty("providerName", status.providerName)
                addProperty("model", status.model)
            })
        }
        refreshModels()
    }

    private fun refreshModels() {
        service<ProviderModelCatalogService>().loadCurrent(forceRefresh = true) { catalog ->
            emit("models", jsonObject {
                addProperty("providerId", catalog.providerId)
                addProperty("status", catalog.status)
                catalog.error?.let { addProperty("error", it) }
                add("models", JsonArray().apply { catalog.models.forEach(::add) })
            })
            catalog.error?.let(::emitNotification)
        }
    }

    private fun updateSandbox(payload: JsonObject) {
        val mode = enumValueOrDefault(payload.stringOrNull("mode"), SandboxMode.WORKSPACE_WRITE)
        OmniCodePlatformSettingsService.getInstance().update { it.sandboxMode = mode.name }
        emit("settings", settingsSnapshot())
    }

    private fun updateHistoryRetention(payload: JsonObject) {
        val value = payload.intOrNull("value")?.coerceIn(1, 1_000) ?: return
        OmniCodePlatformSettingsService.getInstance().update { it.historyRetention = value }
        emit("settings", settingsSnapshot())
    }

    private fun updateUsageRetention(payload: JsonObject) {
        val value = payload.intOrNull("value")?.coerceIn(1, 3_650) ?: return
        OmniCodePlatformSettingsService.getInstance().update { it.usageRetentionDays = value }
        emit("settings", settingsSnapshot())
    }

    private fun updateCommitAi(payload: JsonObject) {
        val enabled = payload.booleanOrNull("enabled") ?: return
        OmniCodePlatformSettingsService.getInstance().update { it.commitAiEnabled = enabled }
        emit("settings", settingsSnapshot())
    }

    private fun updateAgentRuntime(payload: JsonObject) {
        val continuous = payload.booleanOrNull("continuousExecution") ?: return
        val attempts = payload.intOrNull("providerMaxAttempts")?.coerceIn(1, 10) ?: return
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.agentContinuousExecution = continuous
            state.agentProviderMaxAttempts = attempts
        }
        emit("settings", settingsSnapshot())
    }

    private fun updateProjectContext(payload: JsonObject) {
        val pinned = payload.requiredRawString("pinnedPaths", 32_000)
            .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
        val excluded = payload.requiredRawString("excludedPaths", 32_000)
            .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
        val context = ProjectContextSettingsService.getInstance(project)
        val previous = context.snapshot()
        runCatching {
            // Exclusions win if a path appears in both lists. The service validates project-relative
            // paths, ignored files, sensitive files and configured bounds before persisting them.
            context.setExcludedPaths(excluded)
            context.setPinnedPaths(pinned.filterNot { candidate ->
                excluded.any { candidate == it || candidate.startsWith("$it/") }
            })
        }.onFailure {
            runCatching {
                context.setExcludedPaths(previous.excludedPaths)
                context.setPinnedPaths(previous.pinnedPaths)
            }
            throw it
        }
        emit("settings", settingsSnapshot())
        emitNotification("项目上下文规则已保存。")
    }

    private fun updatePet(payload: JsonObject) {
        val workshop = WorkshopSettingsService.getInstance()
        payload.stringOrNull("themeId")?.let(workshop::selectTheme)
        payload.stringOrNull("petId")?.let(workshop::selectPet)
        payload.booleanOrNull("enabled")?.let(workshop::setPetEnabled)
        val petX = payload.intOrNull("petX")
        val petY = payload.intOrNull("petY")
        if (petX != null && petY != null) workshop.saveEmbeddedPetPosition(petX, petY)
        emit("settings", settingsSnapshot())
    }

    private fun probeLocalRuntimes() {
        val engines = LocalAgentEngineRegistry.all
        emit("runtime.reset", jsonObject { addProperty("count", engines.size) })
        engines.forEach { engine ->
            ApplicationManager.getApplication().executeOnPooledThread {
                emit("runtime.status", probeLocalRuntime(engine))
            }
        }
    }

    private fun probeLocalRuntime(engine: LocalAgentEngineContract): JsonObject {
        val tool = engine.tool
        val executable = CliToolDiscovery.resolveExecutable(tool, null)
            ?: return runtimeStatus(engine, false, "未安装", "", "未在 IDE PATH 或常见安装目录中找到。")
        val builder = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + engine.versionArguments)
            .redirectErrorStream(true)
        CliToolDiscovery.applyRuntimePath(builder.environment(), executable)
        val process = runCatching { builder.start() }.getOrElse { error ->
            return runtimeStatus(engine, false, "无法启动", executable.absolutePath, safeUiError(error))
        }
        return try {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(CLI_PROBE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                runtimeStatus(engine, false, "检测超时", executable.absolutePath, "版本命令超过 ${CLI_PROBE_SECONDS} 秒。")
            } else {
                val output = process.inputStream.readNBytes(CLI_PROBE_BYTES).toString(StandardCharsets.UTF_8)
                    .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
                runtimeStatus(
                    engine,
                    process.exitValue() == 0,
                    output.ifBlank { if (process.exitValue() == 0) "已安装" else "退出码 ${process.exitValue()}" },
                    executable.absolutePath,
                    output.takeIf { process.exitValue() != 0 }.orEmpty(),
                )
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun runtimeStatus(engine: LocalAgentEngineContract, runnable: Boolean, version: String, path: String, diagnostic: String): JsonObject =
        jsonObject {
            addProperty("id", engine.id)
            addProperty("name", engine.displayName)
            addProperty("runnable", runnable)
            addProperty("version", version)
            addProperty("path", path)
            addProperty(
                "diagnostic",
                diagnostic.ifBlank {
                    if (runnable) "版本检测通过；登录状态与模型权限将在选择后验证。" else "无法运行版本检测。"
                },
            )
            addProperty("modelDiscovery", engine.modelDiscovery != dev.omnicode.provider.LocalModelDiscovery.NONE)
            addProperty("nativeResume", engine.supportsNativeResume)
            addProperty("nativeHistory", engine.supportsNativeHistory)
            addProperty("sessionContinuity", engine.sessionContinuity.name)
        }

    private fun sendMcpCatalog(payload: JsonObject) {
        val query = payload.stringOrNull("query").orEmpty().take(160)
        val forceRefresh = payload.booleanOrNull("forceRefresh") ?: false
        val generation = marketplaceLoadGeneration.incrementAndGet()
        // Searching or reopening the marketplace must not leave earlier registry requests
        // running in the background. Only the latest query is allowed to update the WebView.
        marketplaceLoadJob?.cancel()
        val localEntries = McpMarketplaceCatalog.search(marketplaceEntries, McpCatalogQuery(text = query, maxResults = 80))
        emitMcpCatalog(localEntries)
        emit("mcp.catalogState", jsonObject {
            addProperty("state", "loading")
            addProperty("total", marketplaceEntries.size)
            addProperty("shown", localEntries.size)
            addProperty("notice", "正在同步官方 MCP Registry；本地精选仍可使用。")
            addProperty("fromCache", false)
        })
        marketplaceLoadJob = backgroundScope.launch {
            val snapshot = try {
                marketplaceDirectory.load(forceRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == marketplaceLoadGeneration.get()) emit("mcp.catalogState", jsonObject {
                    addProperty("state", "error")
                    addProperty("total", marketplaceEntries.size)
                    addProperty("shown", localEntries.size)
                    addProperty("notice", "Registry 目录加载失败：${error.message.orEmpty().take(240)}")
                    addProperty("fromCache", false)
                })
                return@launch
            }
            if (disposed.get() || generation != marketplaceLoadGeneration.get()) return@launch
            marketplaceEntries = snapshot.entries
            val entries = McpMarketplaceCatalog.search(
                snapshot.entries,
                McpCatalogQuery(text = query, maxResults = 80),
            )
            emitMcpCatalog(entries)
            emit("mcp.catalogState", jsonObject {
                addProperty("state", if (snapshot.registryAvailable) "ready" else "offline")
                addProperty("total", snapshot.entries.size)
                addProperty("shown", entries.size)
                addProperty("notice", snapshot.notice.ifBlank {
                    if (snapshot.registryAvailable) "Registry 已同步；搜索覆盖全部目录。" else "Registry 暂不可用，当前显示本地精选。"
                })
                addProperty("fromCache", snapshot.registryResult?.fromCache == true)
            })
        }
    }

    private fun emitMcpCatalog(entries: List<McpCatalogEntry>) {
        emit("mcp.catalog", JsonArray().apply {
            entries.forEach { entry -> add(jsonObject {
                addProperty("id", entry.id)
                addProperty("name", entry.name)
                addProperty("publisher", entry.publisher)
                addProperty("description", entry.description)
                addProperty("source", entry.source.name)
                addProperty("category", entry.category.displayName)
                addProperty("risk", entry.riskLevel.name)
                addProperty("riskSummary", entry.riskSummary)
                add("tags", JsonArray().apply { entry.tags.forEach(::add) })
                add("options", JsonArray().apply { entry.installOptions.forEach { option -> add(jsonObject {
                    addProperty("id", option.id)
                    addProperty("name", option.displayName)
                    addProperty("kind", option.kind.name)
                }) } })
            }) }
        })
    }

    private fun installMcpDraft(payload: JsonObject) {
        val entryId = payload.requiredString("entryId", 128)
        val optionId = payload.requiredString("optionId", 128)
        val entry = marketplaceEntries.firstOrNull { it.id == entryId }
            ?: McpMarketplaceCatalog.find(entryId)
            ?: throw IllegalArgumentException("MCP 市场条目已更新，请刷新市场后重试。")
        val draft = McpMarketplaceCatalog.createDraft(entry, optionId)
        val config = draft.config
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.mcpServers.removeIf { it.id == config.id }
            state.mcpServers += McpServerState().apply {
                id = config.id
                name = config.name
                enabled = false
                transport = config.transport.id
                command = config.command
                arguments = config.arguments.joinToString(" ", transform = ::quoteStoredArgument)
                environmentKeys = config.environmentKeys.joinToString(",")
                workingDirectory = config.workingDirectory
                url = config.url
                httpAuthMode = config.httpAuthMode.id
                oauthClientId = config.oauthClientId
                oauthScopes = config.oauthScopes.joinToString(" ")
            }
        }
        emit("settings", settingsSnapshot())
        emit("mcp.draft", jsonObject {
            addProperty("id", config.id)
            val runtimeWarning = if (config.transport == McpTransport.STDIO) {
                runtimeInstallGuidance(config.command.lowercase())
            } else null
            add("warnings", JsonArray().apply {
                draft.warnings.forEach(::add)
                runtimeWarning?.let(::add)
            })
        })
    }

    private fun saveMcpServer(payload: JsonObject) {
        val id = payload.stringOrNull("id")?.takeIf(String::isNotBlank)?.take(128) ?: UUID.randomUUID().toString()
        val transport = McpTransport.fromId(payload.requiredString("transport", 32))
        val name = payload.requiredString("name", 120)
        val command = payload.stringOrNull("command").orEmpty().trim().take(1_024)
        val arguments = payload.stringOrNull("arguments").orEmpty().take(16_384)
        val workingDirectory = payload.stringOrNull("workingDirectory").orEmpty().trim().ifBlank { "." }.take(4_096)
        val url = payload.stringOrNull("url").orEmpty().trim().take(4_096)
        if (transport == McpTransport.STDIO) require(command.isNotBlank() && command.none(Char::isISOControl)) {
            "stdio MCP 必须填写启动命令。"
        } else validateMcpHttpEndpoint(url)
        val authMode = McpHttpAuthMode.fromId(payload.stringOrNull("httpAuthMode").orEmpty())
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.mcpServers.removeIf { it.id == id }
            state.mcpServers += McpServerState().apply {
                this.id = id
                this.name = name
                enabled = payload.booleanOrNull("enabled") ?: false
                this.transport = transport.id
                this.command = command
                this.arguments = arguments
                environmentKeys = payload.stringOrNull("environmentKeys").orEmpty().take(4_096)
                this.workingDirectory = workingDirectory
                this.url = url
                httpAuthMode = authMode.id
                oauthClientId = payload.stringOrNull("oauthClientId").orEmpty().take(2_048)
                oauthScopes = payload.stringOrNull("oauthScopes").orEmpty().take(4_096)
            }
        }
        emitNotification("MCP 配置已保存；首次连接仍需通过安全审批。")
        emit("settings", settingsSnapshot())
    }

    private fun deleteMcpServer(payload: JsonObject) {
        val id = payload.requiredString("id", 128)
        OmniCodePlatformSettingsService.getInstance().update { it.mcpServers.removeIf { server -> server.id == id } }
        OmniCodePlatformSettingsService.getInstance().clearMcpLaunchTrusts(id)
        runCatching { mcpHttpCredentials.clear(id) }
        runCatching { oauthSessions.logout(id) }
        emit("settings", settingsSnapshot())
    }

    private fun saveMcpBearer(payload: JsonObject) {
        val config = persistedMcpConfig(payload.requiredString("id", 128))
        require(config.transport == McpTransport.HTTP && config.httpAuthMode == McpHttpAuthMode.BEARER) {
            "请先保存使用 Bearer 认证的 HTTP MCP 配置。"
        }
        ApplicationManager.getApplication().invokeLater {
            val token = Messages.showPasswordDialog(
                project,
                "输入 ${config.name} 的 Bearer Token。它只会保存到 IDE PasswordSafe，不会进入 WebView、日志或配置文件。",
                "保存 MCP Bearer Token",
                Messages.getQuestionIcon(),
            ) ?: return@invokeLater
            if (token.isBlank()) {
                emitMcpAuth(config.id, "error", "Token 不能为空。")
                return@invokeLater
            }
            runCatching { mcpHttpCredentials.save(config.id, token) }.fold(
                onSuccess = {
                    emitMcpAuth(config.id, "success", "Bearer Token 已安全保存到 PasswordSafe。")
                    emit("settings", settingsSnapshot())
                },
                onFailure = { emitMcpAuth(config.id, "error", "无法写入 PasswordSafe。") },
            )
        }
    }

    private fun clearMcpBearer(payload: JsonObject) {
        val id = payload.requiredString("id", 128)
        runCatching { mcpHttpCredentials.clear(id) }.fold(
            onSuccess = {
                emitMcpAuth(id, "success", "Bearer Token 已清除。")
                emit("settings", settingsSnapshot())
            },
            onFailure = { emitMcpAuth(id, "error", "无法更新 PasswordSafe。") },
        )
    }

    private fun discoverMcpOAuth(payload: JsonObject) {
        val config = persistedOAuthConfig(payload.requiredString("id", 128))
        backgroundScope.launch {
            val approved = onEdt {
                Messages.showYesNoDialog(
                    project,
                    "将连接 ${config.url} 读取 OAuth 资源和授权服务器元数据。此步骤不发送任何 Token，且请求受超时、大小和重定向限制。",
                    "自动发现 MCP OAuth",
                    "开始发现",
                    "取消",
                    Messages.getWarningIcon(),
                ) == Messages.YES
            }
            if (!approved) {
                emitMcpAuth(config.id, "idle", "已取消 OAuth 自动发现，未发起网络请求。")
                return@launch
            }
            emitMcpAuth(config.id, "running", "正在发现 OAuth 元数据…")
            runCatching { oauthSessions.discoverConfiguration(config) }.fold(
                onSuccess = { preview ->
                    val scopes = preview.scopes.joinToString(" ")
                    if (config.oauthScopes.isEmpty() && scopes.isNotBlank()) {
                        OmniCodePlatformSettingsService.getInstance().update { state ->
                            state.mcpServers.firstOrNull { it.id == config.id }?.oauthScopes = scopes
                        }
                    }
                    val registration = when (preview.clientRegistrationCapability.name) {
                        "DYNAMIC_REGISTRATION" -> "支持动态客户端注册，Client ID 可留空。"
                        else -> "需要服务商提供的公开 Client ID。"
                    }
                    emitMcpAuth(
                        config.id,
                        "success",
                        "发现成功：${preview.issuer.host}；Scopes：${scopes.ifBlank { "由服务器决定" }}；$registration",
                    )
                    emit("settings", settingsSnapshot())
                },
                onFailure = { emitMcpAuth(config.id, "error", "OAuth 发现失败：${safeMcpDetail(it)}") },
            )
        }
    }

    private fun loginMcpOAuth(payload: JsonObject) {
        val config = persistedOAuthConfig(payload.requiredString("id", 128))
        emitMcpAuth(config.id, "running", "正在发现授权服务并准备浏览器登录…")
        backgroundScope.launch {
            runCatching {
                oauthSessions.login(
                    config = config,
                    confirm = ::confirmMcpOAuthLogin,
                    openBrowser = { uri -> onEdt { BrowserUtil.browse(uri.toASCIIString()) } },
                )
            }.fold(
                onSuccess = { session ->
                    val scopes = session.scopes.take(4).joinToString(" ").ifBlank { "服务器默认权限" }
                    emitMcpAuth(config.id, "success", "OAuth 登录成功：${URI(session.issuer).host} · $scopes")
                    emit("settings", settingsSnapshot())
                },
                onFailure = { emitMcpAuth(config.id, "error", "OAuth 登录失败：${safeMcpDetail(it)}") },
            )
        }
    }

    private fun logoutMcpOAuth(payload: JsonObject) {
        val id = payload.requiredString("id", 128)
        runCatching { oauthSessions.logout(id) }.fold(
            onSuccess = {
                emitMcpAuth(id, "success", "OAuth 凭据已从 PasswordSafe 清除。")
                emit("settings", settingsSnapshot())
            },
            onFailure = { emitMcpAuth(id, "error", "无法清除 OAuth 凭据。") },
        )
    }

    private fun persistedMcpConfig(id: String): McpServerConfig =
        OmniCodePlatformSettingsService.getInstance().snapshot().mcpServers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("请先保存 MCP 配置。")

    private fun persistedOAuthConfig(id: String): McpServerConfig = persistedMcpConfig(id).also { config ->
        require(config.transport == McpTransport.HTTP && config.httpAuthMode == McpHttpAuthMode.OAUTH) {
            "请先保存使用 OAuth 2.1 / PKCE 的 HTTP MCP 配置。"
        }
    }

    private suspend fun confirmMcpOAuthLogin(approval: McpOAuthLoginApproval): Boolean = onEdt {
        val scopes = approval.scopes.joinToString(" ").ifBlank { "由授权服务器决定" }
        val client = if (approval.dynamicRegistration) "动态注册公开客户端" else "使用已配置 Client ID"
        Messages.showYesNoDialog(
            project,
            "MCP：${approval.serverName}\n资源：${approval.resource}\n授权服务器：${approval.issuer}\n权限：$scopes\n客户端：$client\n回调：${approval.redirectUri}\n\n继续后将打开系统浏览器；凭据只保存到 PasswordSafe。",
            "授权 OmniCode 连接 MCP",
            "继续",
            "取消",
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    private fun emitMcpAuth(id: String, state: String, message: String) = emit("mcp.auth", jsonObject {
        addProperty("id", id)
        addProperty("state", state)
        addProperty("message", message.take(800))
    })

    private fun safeMcpDetail(error: Throwable): String =
        error.message?.lineSequence()?.firstOrNull()?.take(500) ?: error::class.java.simpleName

    /**
     * Explicit connection test. Nothing is launched while settings are rendered or a catalog
     * draft is installed; the user must press Test, and the existing native approval + sandbox
     * path remains authoritative for local processes and remote HTTP connections.
     */
    private fun testMcpServer(payload: JsonObject) {
        val config = mcpConfigFromPayload(payload).copy(enabled = true)
        emit("mcp.test", jsonObject {
            addProperty("id", config.id)
            addProperty("state", "running")
            addProperty("message", "等待安全审批并连接…")
        })
        backgroundScope.launch {
            val result = runCatching {
                val gate = ModalApprovalGate(project)
                val client: McpClient = when (config.transport) {
                    McpTransport.STDIO -> McpStdioClient.connect(
                        config,
                        SandboxedMcpProcessLauncher(
                            project,
                            OmniCodePlatformSettingsService.getInstance().snapshot().sandboxMode,
                            gate,
                        ),
                    )
                    McpTransport.HTTP -> ApprovedMcpHttpClientConnector(project, gate).connect(config)
                }
                client.use { connected -> connected.listTools() }
            }
            emit("mcp.test", jsonObject {
                addProperty("id", config.id)
                result.fold(
                    onSuccess = { tools ->
                        addProperty("state", "success")
                        addProperty("message", "连接成功，发现 ${tools.size} 个工具。")
                        add("tools", JsonArray().apply { tools.take(50).forEach { add(it.name) } })
                    },
                    onFailure = { error ->
                        addProperty("state", "error")
                        addProperty("message", actionableMcpError(error))
                    },
                )
            })
        }
    }

    private fun mcpConfigFromPayload(payload: JsonObject): McpServerConfig {
        val id = payload.stringOrNull("id")?.trim()?.takeIf(String::isNotBlank)?.take(128)
            ?: "mcp-test-${UUID.randomUUID()}"
        val name = payload.requiredString("name", 120)
        val transport = McpTransport.fromId(payload.requiredString("transport", 32))
        return when (transport) {
            McpTransport.STDIO -> {
                val command = payload.requiredString("command", 1_024)
                val environmentKeys = payload.stringOrNull("environmentKeys").orEmpty()
                    .split(Regex("[\\s,]+"))
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .onEach { require(MCP_ENVIRONMENT_KEY.matches(it)) { "环境变量名无效：$it" } }
                    .take(128)
                    .toSet()
                McpServerConfig(
                    id = id,
                    name = name,
                    enabled = true,
                    command = command,
                    arguments = parseCommandLine(payload.stringOrNull("arguments").orEmpty()),
                    environmentKeys = environmentKeys,
                    workingDirectory = payload.stringOrNull("workingDirectory").orEmpty().trim().ifBlank { "." },
                )
            }
            McpTransport.HTTP -> McpServerConfig(
                id = id,
                name = name,
                enabled = true,
                command = "",
                arguments = emptyList(),
                environmentKeys = emptySet(),
                workingDirectory = ".",
                transport = McpTransport.HTTP,
                url = validateMcpHttpEndpoint(payload.requiredString("url", 4_096)).toASCIIString(),
                httpAuthMode = McpHttpAuthMode.fromId(payload.stringOrNull("httpAuthMode").orEmpty()),
                oauthClientId = payload.stringOrNull("oauthClientId").orEmpty().trim().take(2_048),
                oauthScopes = normalizeOAuthScopes(payload.stringOrNull("oauthScopes").orEmpty()),
            )
        }
    }

    private fun actionableMcpError(error: Throwable): String {
        val detail = error.message?.lineSequence()?.firstOrNull()?.take(280) ?: error::class.java.simpleName
        val normalized = detail.lowercase()
        return when {
            "not found" in normalized || "no such file" in normalized ->
                "$detail。${runtimeInstallGuidance(normalized)}"
            "oauth" in normalized || "401" in normalized || "unauthorized" in normalized ->
                "$detail。请保存配置后完成 OAuth 登录或 Bearer 凭据，再重新测试。"
            "timeout" in normalized || "timed out" in normalized ->
                "$detail。请检查服务进程、Endpoint、DNS、代理和防火墙。"
            else -> "$detail。请核对命令、参数、工作目录和服务端日志后重试。"
        }
    }

    /** Display-only dependency guidance; the plugin never installs packages or runs a shell. */
    private fun runtimeInstallGuidance(error: String): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val platform = when {
            "windows" in os -> "Windows"
            "mac" in os || "darwin" in os -> "macOS"
            "linux" in os -> "Linux"
            else -> "当前系统"
        }
        val packageHint = when {
            "uvx" in error || "uv " in error -> when (platform) {
                "Windows" -> "可在终端执行 `winget install astral-sh.uv`"
                "macOS" -> "可在终端执行 `brew install uv`"
                "Linux" -> "可用系统包管理器安装 `uv`（例如 `pipx install uv`）"
                else -> "请安装 Astral uv，并确保 `uvx` 在 IntelliJ PATH 中"
            }
            "node" in error || "npx" in error || "npm" in error -> when (platform) {
                "Windows" -> "可在终端执行 `winget install OpenJS.NodeJS.LTS`"
                "macOS" -> "可在终端执行 `brew install node`"
                "Linux" -> "可用系统包管理器安装 Node.js（同时提供 npm/npx）"
                else -> "请安装 Node.js（同时提供 npm/npx）"
            }
            "python" in error || "pip" in error -> when (platform) {
                "Windows" -> "请从 python.org 安装 Python，并勾选 Add to PATH"
                "macOS" -> "可在终端执行 `brew install python`"
                "Linux" -> "可用系统包管理器安装 Python 3 与 pip"
                else -> "请安装 Python 3 与 pip"
            }
            else -> "请在“设置 → 依赖”重新检测该 MCP 所需运行时"
        }
        return "当前系统：$platform。$packageHint；安装后点击“重新检测本地引擎”，再测试 MCP。"
    }

    private fun savePrompt(payload: JsonObject) {
        val id = payload.stringOrNull("id")?.takeIf(String::isNotBlank)?.take(128) ?: UUID.randomUUID().toString()
        val name = payload.requiredString("name", 120)
        val shortcut = payload.requiredString("shortcut", 64).removePrefix("!")
        val content = payload.requiredRawString("content", 32_000)
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.promptTemplates.removeIf { it.id == id }
            state.promptTemplates += PromptTemplateState().apply {
                this.id = id; this.name = name; this.shortcut = shortcut; this.content = content
            }
        }
        emit("settings", settingsSnapshot())
    }

    private fun searchProjectFiles(payload: JsonObject) {
        val query = payload.stringOrNull("query").orEmpty().trim().lowercase().take(120)
        if (query.length < 1) {
            emit("composer.files", JsonArray())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val base = project.basePath?.let(Path::of)?.normalize()
            if (base == null) {
                emit("composer.files", JsonArray())
                return@executeOnPooledThread
            }
            val matches = mutableListOf<String>()
            ProjectRootManager.getInstance(project).fileIndex.iterateContent { file ->
                if (matches.size >= MAX_FILE_SUGGESTIONS) return@iterateContent false
                if (!file.isDirectory && !isSensitiveReferenceName(file.name)) {
                    val nio = runCatching { file.toNioPath().normalize() }.getOrNull()
                    val relative = nio?.takeIf { it.startsWith(base) }?.let(base::relativize)?.toString()?.replace('\\', '/')
                    if (!relative.isNullOrBlank() && relative.lowercase().contains(query)) matches += relative
                }
                true
            }
            emit("composer.files", JsonArray().apply { matches.sortedWith(compareBy<String> { it.length }.thenBy { it }).forEach(::add) })
        }
    }

    private fun isSensitiveReferenceName(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == ".env" || normalized.startsWith(".env.") ||
            normalized in setOf("id_rsa", "id_ed25519", "credentials", "credentials.json", "secrets.json") ||
            normalized.endsWith(".p12") || normalized.endsWith(".pfx") || normalized.endsWith(".jks")
    }

    private fun deletePrompt(payload: JsonObject) {
        val id = payload.requiredString("id", 128)
        OmniCodePlatformSettingsService.getInstance().update { it.promptTemplates.removeIf { prompt -> prompt.id == id } }
        emit("settings", settingsSnapshot())
    }

    private fun saveSkill(payload: JsonObject) {
        val id = payload.stringOrNull("id")?.takeIf(String::isNotBlank)?.take(128) ?: UUID.randomUUID().toString()
        val name = payload.requiredString("name", 120)
        val path = payload.requiredString("path", 4_096)
        val inspection = inspectSkillSource(path, project.basePath)
        require(inspection.isValid) { inspection.message }
        OmniCodePlatformSettingsService.getInstance().update { state ->
            state.skillSources.removeIf { it.id == id }
            state.skillSources += SkillSourceState().apply {
                this.id = id; this.name = name; this.path = path; enabled = payload.booleanOrNull("enabled") ?: true
            }
        }
        emitNotification(inspection.message)
        emit("settings", settingsSnapshot())
    }

    private fun deleteSkill(payload: JsonObject) {
        val id = payload.requiredString("id", 128)
        OmniCodePlatformSettingsService.getInstance().update { it.skillSources.removeIf { skill -> skill.id == id } }
        emit("settings", settingsSnapshot())
    }

    private fun openFile(payload: JsonObject) {
        val raw = payload.requiredString("path", 4_096).replace('\\', '/')
        val line = (payload.intOrNull("line") ?: 1).coerceAtLeast(1)
        val endLine = (payload.intOrNull("end") ?: line).coerceAtLeast(line)
        val projectRoot = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: throw IllegalStateException("项目没有本地目录。")
        val requested = Path.of(raw).let { if (it.isAbsolute) it else projectRoot.resolve(it) }.normalize()
        require(requested.startsWith(projectRoot)) { "只能打开当前项目中的文件。" }
        val realRoot = runCatching { projectRoot.toRealPath() }.getOrDefault(projectRoot)
        val realFile = runCatching { requested.toRealPath() }.getOrElse { throw IllegalArgumentException("文件不存在。") }
        require(realFile.startsWith(realRoot) && Files.isRegularFile(realFile)) { "文件不在当前项目安全边界内。" }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realFile)
            ?: throw IllegalArgumentException("IDE 无法定位该文件。")
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, virtualFile, line - 1, 0), true)
            if (editor != null && endLine > line) {
                val document = editor.document
                val start = (line - 1).coerceAtMost(document.lineCount - 1)
                val end = (endLine - 1).coerceAtMost(document.lineCount - 1)
                val startOffset = document.getLineStartOffset(start)
                val endOffset = document.getLineEndOffset(end)
                editor.selectionModel.setSelection(startOffset, endOffset)
                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
            }
        }
    }

    private fun openExternal(payload: JsonObject) {
        val url = payload.requiredString("url", 4_096)
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
            "仅允许打开 HTTPS 或本机链接。"
        }
        BrowserUtil.browse(url)
    }

    private fun settingsSnapshot(): JsonObject {
        val modelSettings = OmniCodeSettingsService.getInstance()
        val active = modelSettings.snapshot()
        val preset = ProviderPresets.byId(active.providerId)
        val platform = OmniCodePlatformSettingsService.getInstance().snapshot()
        val workshopService = WorkshopSettingsService.getInstance()
        val workshop = workshopService.snapshot()
        val petPlacement = workshopService.placementSnapshot()
        val resolvedWorkshop = WorkshopCatalog.resolve(workshop)
        val projectContext = ProjectContextSettingsService.getInstance(project).snapshot()
        val hasCredential = preset.apiKeyOptional || runCatching {
            OmniCodeCredentialStore.getInstance().load(active.providerId, active.baseUrl).apiKey.isNotBlank()
        }.getOrDefault(false)
        return jsonObject {
            add("provider", providerJson(active, hasCredential))
            add("providers", JsonArray().apply {
                ProviderPresets.all.forEach { entry ->
                    val profile = modelSettings.snapshotFor(entry.id)
                    val profileCredential = entry.apiKeyOptional || runCatching {
                        OmniCodeCredentialStore.getInstance().load(entry.id, profile.baseUrl).apiKey.isNotBlank()
                    }.getOrDefault(false)
                    add(jsonObject {
                        addProperty("id", entry.id)
                        addProperty("name", entry.displayName)
                        addProperty("defaultBaseUrl", entry.defaultBaseUrl)
                        addProperty("defaultModel", entry.defaultModel)
                        addProperty("cli", entry.protocol.name.startsWith("CLI_"))
                        addProperty("baseUrl", profile.baseUrl)
                        addProperty("model", profile.model)
                        addProperty("credentialConfigured", profileCredential)
                    })
                }
            })
            add("platform", jsonObject {
                addProperty("sandboxMode", platform.sandboxMode.name)
                addProperty("historyEnabled", platform.historyEnabled)
                addProperty("historyRetention", platform.historyRetention)
                addProperty("usageRetentionDays", platform.usageRetentionDays)
                addProperty("mcpCount", platform.mcpServers.size)
                addProperty("skillCount", platform.skillSources.size)
                addProperty("promptCount", platform.promptTemplates.size)
                addProperty("commitAiEnabled", platform.commitAi.enabled)
                addProperty("agentContinuousExecution", platform.agentRuntime.continuousExecution)
                addProperty("providerMaxAttempts", platform.agentRuntime.providerMaxAttempts)
            })
            add("theme", jsonObject {
                addProperty("id", workshop.themeId)
                addProperty("petEnabled", workshop.petEnabled)
                addProperty("petId", workshop.petId)
                addProperty("petX", petPlacement.embeddedX ?: 8_800)
                addProperty("petY", petPlacement.embeddedY ?: 7_600)
                add("palette", jsonObject {
                    addProperty("background", resolvedWorkshop.theme.palette.background)
                    addProperty("surface", resolvedWorkshop.theme.palette.surface)
                    addProperty("elevatedSurface", resolvedWorkshop.theme.palette.elevatedSurface)
                    addProperty("primaryText", resolvedWorkshop.theme.palette.primaryText)
                    addProperty("secondaryText", resolvedWorkshop.theme.palette.secondaryText)
                    addProperty("accent", resolvedWorkshop.theme.palette.accent)
                    addProperty("border", resolvedWorkshop.theme.palette.border)
                    addProperty("success", resolvedWorkshop.theme.palette.success)
                    addProperty("warning", resolvedWorkshop.theme.palette.warning)
                    addProperty("error", resolvedWorkshop.theme.palette.error)
                })
            })
            add("themes", JsonArray().apply {
                WorkshopCatalog.themes.forEach { theme -> add(jsonObject {
                    addProperty("id", theme.id)
                    addProperty("name", theme.displayName)
                    addProperty("description", theme.description)
                }) }
            })
            add("pets", JsonArray().apply {
                WorkshopCatalog.pets.forEach { pet -> add(jsonObject {
                    addProperty("id", pet.id)
                    addProperty("name", pet.displayName)
                    addProperty("description", pet.description)
                    addProperty("glyph", pet.glyph)
                    addProperty("accent", pet.accentColor)
                }) }
            })
            add("projectContext", jsonObject {
                add("pinnedPaths", JsonArray().apply { projectContext.pinnedPaths.forEach(::add) })
                add("excludedPaths", JsonArray().apply { projectContext.excludedPaths.forEach(::add) })
            })
            add("mcpServers", JsonArray().apply {
                platform.mcpServers.forEach { server ->
                    add(jsonObject {
                        addProperty("id", server.id)
                        addProperty("name", server.name)
                        addProperty("enabled", server.enabled)
                        addProperty("transport", server.transport.id)
                        addProperty("command", server.command)
                        addProperty("arguments", server.arguments.joinToString(" ", transform = ::quoteStoredArgument))
                        addProperty("environmentKeys", server.environmentKeys.joinToString(","))
                        addProperty("workingDirectory", server.workingDirectory)
                        addProperty("url", server.url)
                        addProperty("httpAuthMode", server.httpAuthMode.id)
                        addProperty("oauthClientId", server.oauthClientId)
                        addProperty("oauthScopes", server.oauthScopes.joinToString(" "))
                        addProperty(
                            "bearerConfigured",
                            server.httpAuthMode == McpHttpAuthMode.BEARER &&
                                runCatching { mcpHttpCredentials.hasToken(server.id) }.getOrDefault(false),
                        )
                        addProperty(
                            "oauthConfigured",
                            server.httpAuthMode == McpHttpAuthMode.OAUTH &&
                                runCatching { oauthSessions.hasSession(server.id) }.getOrDefault(false),
                        )
                        addProperty(
                            "oauthUsable",
                            server.httpAuthMode == McpHttpAuthMode.OAUTH &&
                                runCatching { oauthSessions.hasUsableSession(server) }.getOrDefault(false),
                        )
                    })
                }
            })
            add("prompts", JsonArray().apply {
                platform.promptTemplates.forEach { prompt ->
                    add(jsonObject {
                        addProperty("id", prompt.id)
                        addProperty("name", prompt.name)
                        addProperty("shortcut", prompt.shortcut)
                        addProperty("content", prompt.content)
                    })
                }
            })
            add("skills", JsonArray().apply {
                platform.skillSources.forEach { skill ->
                    add(jsonObject {
                        addProperty("id", skill.id)
                        addProperty("name", skill.name)
                        addProperty("path", skill.path)
                        addProperty("enabled", skill.enabled)
                    })
                }
            })
        }
    }

    private fun providerJson(snapshot: OmniCodeSettingsSnapshot, credentialConfigured: Boolean): JsonObject = jsonObject {
        addProperty("id", snapshot.providerId)
        addProperty("baseUrl", snapshot.baseUrl)
        addProperty("model", snapshot.model)
        addProperty("reasoningEffort", snapshot.reasoningEffort.persistedValue)
        addProperty("maxOutputTokens", snapshot.maxOutputTokens)
        addProperty("credentialConfigured", credentialConfigured)
    }

    private fun decodeAttachments(values: JsonArray): List<UserAttachment> {
        require(values.size() <= AttachmentIntake.MAX_ATTACHMENTS) {
            "一次最多添加 ${AttachmentIntake.MAX_ATTACHMENTS} 个附件。"
        }
        return values.map { value ->
            val attachment = value.asObjectOrNull() ?: throw IllegalArgumentException("附件格式无效。")
            val localPath = attachment.stringOrNull("localPath")
            if (localPath.isNullOrBlank()) decodeAttachment(attachment) else decodeProjectAttachment(localPath)
        }.distinctBy { it.fileName to it.content.hashCode() }
    }

    private fun decodeProjectAttachment(relativePath: String): UserAttachment {
        require(relativePath.length <= 4_096 && relativePath.none(Char::isISOControl)) { "项目文件路径无效。" }
        val root = project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: throw IllegalStateException("项目没有本地目录。")
        val requested = root.resolve(relativePath).normalize()
        require(requested.startsWith(root) && !Files.isSymbolicLink(requested)) { "只能引用工作区内的非符号链接文件。" }
        val realRoot = root.toRealPath()
        val realFile = requested.toRealPath()
        require(realFile.startsWith(realRoot) && Files.isRegularFile(realFile)) { "引用文件不在项目安全边界内。" }
        return when (val result = AttachmentIntake.read(realFile)) {
            is AttachmentIntakeResult.Accepted -> result.attachment
            is AttachmentIntakeResult.Rejected -> throw IllegalArgumentException(result.message)
        }
    }

    private fun decodeAttachment(value: JsonObject): UserAttachment {
        val suppliedName = value.requiredString("fileName", 255)
        val fileName = Path.of(suppliedName).fileName?.toString().orEmpty()
        require(fileName == suppliedName && fileName.isNotBlank() && fileName.none(Char::isISOControl)) { "附件文件名无效。" }
        val mediaType = value.requiredString("mediaType", 128).lowercase()
        val kind = when (value.requiredString("kind", 16)) {
            "image" -> AttachmentKind.IMAGE
            "markdown" -> AttachmentKind.MARKDOWN
            "text" -> AttachmentKind.TEXT
            else -> throw IllegalArgumentException("不支持的附件类型。")
        }
        val content = value.requiredRawString("content", MAX_BRIDGE_MESSAGE_CHARS)
        return when (kind) {
            AttachmentKind.IMAGE -> {
                require(mediaType in SAFE_IMAGE_MEDIA_TYPES) { "不支持该图片格式。" }
                val encoded = content.substringAfter("base64,", missingDelimiterValue = "")
                require(encoded.isNotBlank()) { "图片数据格式无效。" }
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }
                    .getOrElse { throw IllegalArgumentException("图片数据损坏。") }
                require(bytes.size.toLong() <= AttachmentIntake.MAX_IMAGE_BYTES) { "图片超过 5 MB。" }
                when (val inspection = inspectImageAttachment(bytes, mediaType)) {
                    is ImageAttachmentInspection.Invalid -> throw IllegalArgumentException(inspection.message)
                    is ImageAttachmentInspection.Valid -> Unit
                }
                UserAttachment(fileName, kind, mediaType, bytes.size.toLong(), encoded)
            }
            AttachmentKind.MARKDOWN, AttachmentKind.TEXT -> {
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                val maxBytes = if (kind == AttachmentKind.MARKDOWN) AttachmentIntake.MAX_MARKDOWN_BYTES else AttachmentIntake.MAX_TEXT_BYTES
                require(bytes.size.toLong() <= maxBytes) { "附件超过安全大小限制。" }
                require(content.isNotBlank() && isSafeTextAttachment(content)) { "附件不是安全的 UTF-8 文本。" }
                UserAttachment(fileName, kind, mediaType, bytes.size.toLong(), content)
            }
        }
    }

    private fun conversationBlocks(messages: List<ConversationMessage>, sessionId: String): JsonArray = JsonArray().apply {
        messages.forEachIndexed { messageIndex, message ->
            val visibleText = message.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
            if (visibleText.isNotBlank()) {
                add(jsonObject {
                    addProperty("id", "$sessionId-history-$messageIndex")
                    addProperty("role", if (message.role == MessageRole.USER) "user" else if (message.role == MessageRole.ASSISTANT) "assistant" else "system")
                    addProperty("kind", "message.${message.role.name.lowercase()}")
                    addProperty("phase", "completed")
                    addProperty("text", visibleText.take(MAX_HISTORY_BLOCK_CHARS))
                })
            }
            message.blocks.forEachIndexed { blockIndex, block ->
                when (block) {
                    is ContentBlock.ToolCall -> add(toolHistoryBlock(sessionId, messageIndex, blockIndex, block.name, block.arguments.toString(), false))
                    is ContentBlock.ToolResult -> add(toolHistoryBlock(sessionId, messageIndex, blockIndex, "工具结果", block.content, block.isError))
                    is ContentBlock.Image -> add(jsonObject {
                        addProperty("id", "$sessionId-image-$messageIndex-$blockIndex")
                        addProperty("role", "system")
                        addProperty("kind", "attachment.image")
                        addProperty("phase", "completed")
                        addProperty("title", "图片附件")
                        addProperty("text", "${block.fileName} · ${block.mediaType}")
                        addProperty("status", "success")
                    })
                    is ContentBlock.Text, is ContentBlock.TransientProjectContext -> Unit
                }
            }
        }
    }

    private fun toolHistoryBlock(
        sessionId: String,
        messageIndex: Int,
        blockIndex: Int,
        title: String,
        text: String,
        failed: Boolean,
    ): JsonObject = jsonObject {
        addProperty("id", "$sessionId-tool-$messageIndex-$blockIndex")
        addProperty("role", "system")
        addProperty("kind", "tool.completed")
        addProperty("phase", if (failed) "failed" else "completed")
        addProperty("title", title.take(128))
        addProperty("text", text.take(MAX_HISTORY_BLOCK_CHARS))
        addProperty("status", if (failed) "error" else "success")
    }

    private fun emitCurrentTimeline(type: String) {
        val sessionId = service.conversationIdSnapshot()
        val messages = service.historySnapshot()
        service.conversationWorkflowEvents(sessionId) { events ->
            // The service now merges its bounded live event tail with the durable ledger. Do not
            // suppress this callback merely because the selected conversation is still running:
            // it is exactly the case where a rebuilt WebView needs to recover its visible stages.
            if (disposed.get() || service.conversationIdSnapshot() != sessionId) return@conversationWorkflowEvents
            emit(type, jsonObject {
                addProperty("sessionId", sessionId)
                addProperty("running", service.isConversationRunning(sessionId))
                addProperty("mode", service.conversationModeSnapshot().name)
                addProperty("strategy", service.conversationStrategySnapshot().name)
                add("blocks", conversationTimelineBlocks(messages, sessionId, events))
            })
        }
    }

    private fun conversationTimelineBlocks(
        messages: List<ConversationMessage>,
        sessionId: String,
        events: List<WorkflowEventRecord>,
    ): JsonArray = conversationBlocks(messages, sessionId).apply {
        events.takeLast(MAX_HISTORY_WORKFLOW_EVENTS).forEach { event -> add(workflowHistoryBlock(sessionId, event)) }
    }

    private fun workflowHistoryBlock(sessionId: String, event: WorkflowEventRecord): JsonObject = jsonObject {
        addProperty("id", "$sessionId-workflow-${event.id}")
        addProperty("role", "system")
        val failed = event.success == false || event.type == WorkflowEventType.TOOL_FAILURE
        val warning = event.type == WorkflowEventType.MODEL_RETRY
        addProperty("kind", when (event.type) {
            WorkflowEventType.STAGE_STARTED -> "stage.started"
            WorkflowEventType.STAGE_COMPLETED -> "stage.completed"
            WorkflowEventType.MODEL_REQUEST -> "provider.requested"
            WorkflowEventType.MODEL_RETRY -> "provider.retry"
            WorkflowEventType.TOOL_FAILURE -> "tool.completed"
            WorkflowEventType.CHECKPOINT, WorkflowEventType.RECOVERY_POINT -> "run.checkpoint"
            WorkflowEventType.STATUS -> "status"
        })
        addProperty("phase", if (failed) "failed" else if (warning) "warning" else "completed")
        addProperty("title", when (event.type) {
            WorkflowEventType.STAGE_STARTED -> "阶段开始 · ${event.stage.orEmpty().ifBlank { "未命名" }}"
            WorkflowEventType.STAGE_COMPLETED -> "阶段完成 · ${event.stage.orEmpty().ifBlank { "未命名" }}"
            WorkflowEventType.MODEL_REQUEST -> "模型请求 #${event.iteration}"
            WorkflowEventType.MODEL_RETRY -> "模型请求重试"
            WorkflowEventType.TOOL_FAILURE -> "工具失败 · ${event.stage.orEmpty().removePrefix("tool:").ifBlank { "未知工具" }}"
            WorkflowEventType.CHECKPOINT -> "检查点已保存"
            WorkflowEventType.RECOVERY_POINT -> "恢复点已保存"
            WorkflowEventType.STATUS -> "运行状态"
        })
        addProperty("text", event.message.take(MAX_HISTORY_BLOCK_CHARS))
        addProperty("status", if (failed) "error" else if (warning) "warning" else "success")
    }

    private fun historyEntries(records: List<ConversationRecord>): JsonArray = JsonArray().apply {
        records.forEach { record ->
            add(jsonObject {
                addProperty("id", record.id)
                addProperty("title", record.title.take(240))
                addProperty("updatedAt", record.updatedAt.toString())
                addProperty("status", if (service.isConversationRunning(record.id)) {
                    "RUNNING"
                } else {
                    (record.lastRunStatus ?: AgentRunStatus.COMPLETED).name
                })
                addProperty("messageCount", record.messages.size)
                addProperty(
                    "favorite",
                    OmniCodePlatformSettingsService.getInstance().isConversationFavorite(
                        record.projectId,
                        record.id,
                    ),
                )
            })
        }
    }

    private fun emitPlan(board: PlanBoard?) = emit("plan", board?.let(::planJson) ?: com.google.gson.JsonNull.INSTANCE)

    private fun planJson(board: PlanBoard): JsonObject = jsonObject {
        addProperty("id", board.id)
        addProperty("mode", board.sourceMode.name)
        addProperty("title", board.title)
        addProperty("revision", board.revision)
        addProperty("decision", board.effectiveReviewDecision.name)
        addProperty("approvedCount", board.approvedCount)
        addProperty("completedCount", board.completedCount)
        add("steps", JsonArray().apply {
            board.steps.forEach { step -> add(jsonObject {
                addProperty("id", step.id)
                addProperty("text", step.text)
                addProperty("state", step.state.name)
                addProperty("attempts", step.attempts)
                addProperty("lastError", step.lastError)
            }) }
        })
    }

    private fun emitNotification(message: String) {
        if (message.isBlank()) return
        emit("notification", jsonObject { addProperty("message", message.take(1_000)) })
    }

    private fun emitCommandAccepted(requestId: String, command: String) {
        emit("command.accepted", jsonObject {
            addProperty("requestId", requestId)
            addProperty("command", command)
        }, requestId)
    }

    private fun emitCommandError(requestId: String?, command: String?, message: String) {
        if (requestId == null) {
            emitNotification(message)
            return
        }
        emit("command.error", jsonObject {
            addProperty("command", command.orEmpty().take(128))
            addProperty("message", message.take(800))
        }, requestId)
    }

    private fun emit(type: String, payload: JsonElement, requestId: String? = null) {
        if (disposed.get()) return
        val message = Json.stringify(jsonObject {
            addProperty("type", type)
            addProperty("pageGeneration", pageGeneration)
            requestId?.let { addProperty("requestId", it) }
            add("payload", payload)
        })
        if (!ready.get()) {
            if (pendingMessages.size < MAX_PENDING_MESSAGES) pendingMessages.offer(message)
            return
        }
        executeJavaScript(message)
    }

    private fun flushPendingMessages() {
        while (true) executeJavaScript(pendingMessages.poll() ?: break)
    }

    private fun executeJavaScript(message: String) {
        val currentBrowser = browser ?: return
        val script = "window.__omnicodeReceive && window.__omnicodeReceive($message);"
        ApplicationManager.getApplication().invokeLater {
            if (!disposed.get() && !currentBrowser.isDisposed) currentBrowser.cefBrowser.executeJavaScript(script, currentBrowser.cefBrowser.url, 0)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback

    companion object {
        private val LOG = Logger.getInstance(OmniCodeWebViewPanel::class.java)
        private val PAGE_GENERATION = AtomicLong()
        private const val WEB_RESOURCE = "/webview/index.html"
        private const val CCGUI_HELP_URL = "https://github.com/wuke123222/omnicode-agent"
        private const val BRIDGE_SCHEMA_VERSION = 1
        private const val MAX_WEB_RESOURCE_BYTES = 8 * 1_024 * 1_024
        private const val MAX_BRIDGE_MESSAGE_CHARS = 8 * 1_024 * 1_024
        private const val MAX_PENDING_MESSAGES = 256
        private const val MAX_PROMPT_CHARS = 200_000
        private const val MAX_HISTORY_BLOCK_CHARS = 32_000
        private const val MAX_EXPORT_CHARS = 2 * 1_024 * 1_024
        private const val MAX_SECRET_CHARS = 64_000
        private const val MAX_FILE_SUGGESTIONS = 80
        private const val MAX_REVIEW_FILES = 200
        private const val MAX_REVIEW_HUNKS_PER_FILE = 200
        private const val MAX_REVIEW_HUNK_CHARS = 8_000
        private const val MAX_HISTORY_WORKFLOW_EVENTS = 256
        private const val MAX_DIAGNOSTIC_CHECKS = 128
        private const val MAX_DIAGNOSTIC_TEXT_CHARS = 800
        private const val CLI_PROBE_SECONDS = 5L
        private const val CLI_PROBE_BYTES = 4_096
        private val SAFE_CLIENT_MESSAGE_ID = Regex("client-[A-Za-z0-9._:-]{1,240}")
        private val SAFE_BRIDGE_ID = Regex("[A-Za-z0-9._:-]{1,240}")
        private val SAFE_IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private val ALLOWED_COMMANDS = setOf(
            "frontend.ready", "session.new", "session.cancel", "session.send", "session.list",
            "session.load", "session.delete", "session.favorite", "session.export", "session.fork", "session.rewind", "settings.snapshot", "settings.saveProvider", "provider.select",
            "settings.sandbox", "settings.historyRetention", "settings.usageRetention", "settings.commitAi",
            "settings.agentRuntime", "settings.projectContext", "settings.pet", "provider.models",
            "navigation.openFile", "navigation.openExternal", "navigation.view",
            "plan.updateStep", "plan.approve", "plan.approveAll", "plan.skip", "plan.restore",
            "plan.retry", "plan.review", "plan.continue", "plan.pause",
            "review.snapshot", "review.keepFile", "review.rollbackFile", "review.keepHunk",
            "review.rollbackHunk", "review.rollbackTask",
            "composer.prefill", "composer.searchFiles", "ui.notify", "usage.open", "usage.status", "usage.copyStartCommand", "connection.diagnose", "runtime.probe", "mcp.catalog",
            "mcp.installDraft", "mcp.save", "mcp.test", "mcp.delete", "mcp.saveBearer", "mcp.clearBearer",
            "mcp.oauthDiscover", "mcp.oauthLogin", "mcp.oauthLogout", "prompt.save", "prompt.delete",
            "skill.save", "skill.delete",
        )
        private val MCP_ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    }
}

private inline fun jsonObject(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

private fun String.isComposerCommand(command: String): Boolean =
    equals(command, ignoreCase = true) || startsWith("$command ", ignoreCase = true)

private fun String.removeComposerCommand(command: String): String =
    removePrefixIgnoreCase(command).trimStart()

private suspend fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    return suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            if (!continuation.isActive) return@invokeLater
            runCatching(block).fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(it) },
            )
        }
    }
}

private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }
    ?.asJsonPrimitive?.takeIf { it.isString }?.asString

private fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }
    ?.runCatching { asInt }?.getOrNull()

private fun JsonObject.longOrNull(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }
    ?.runCatching { asLong }?.getOrNull()

private fun JsonObject.booleanOrNull(name: String): Boolean? = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }
    ?.runCatching { asBoolean }?.getOrNull()

private fun JsonObject.objectOrEmpty(name: String): JsonObject = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonObject }
    ?.asJsonObject ?: JsonObject()

private fun JsonObject.arrayOrEmpty(name: String): JsonArray = get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonArray }
    ?.asJsonArray ?: JsonArray()

private fun JsonObject.requiredString(name: String, maxChars: Int): String = stringOrNull(name)?.trim()?.also {
    require(it.isNotBlank() && it.length <= maxChars && it.none(Char::isISOControl)) { "$name 格式无效。" }
} ?: throw IllegalArgumentException("缺少 $name。")

private fun JsonObject.requiredRawString(name: String, maxChars: Int): String = stringOrNull(name)?.also {
    require(it.length <= maxChars) { "$name 超过安全长度限制。" }
} ?: throw IllegalArgumentException("缺少 $name。")

private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

private fun safeUiError(error: Throwable): String = error.message?.lineSequence()?.firstOrNull()?.take(800)
    ?: "操作失败，请重试。"

private fun quoteStoredArgument(value: String): String = when {
    value.isEmpty() -> "\"\""
    value.all { it.isLetterOrDigit() || it in "-._/:@" } -> value
    else -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
