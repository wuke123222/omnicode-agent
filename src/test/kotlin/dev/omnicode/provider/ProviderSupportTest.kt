package dev.omnicode.provider

import dev.omnicode.agent.AgentMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.omnicode.util.Json

class ProviderSupportTest {
    @Test
    fun `Codex completed turn finishes without a lost acknowledgement but rejects stale turn ids`() {
        val observed = 1_000_000_000L
        assertFalse(codexNativeTurnCanFinish(false, true, observed, observed + 3_000_000_000L))
        assertTrue(codexNativeTurnCanFinish(true, true, observed, observed))
        assertFalse(codexNativeTurnCanFinish(true, false, observed, observed + 1_999_000_000L))
        assertTrue(codexNativeTurnCanFinish(true, false, observed, observed + 2_000_000_000L))
        assertFalse(codexNativeCurrentTurnMatches("old-turn", "current-turn"))
        assertTrue(codexNativeCurrentTurnMatches("current-turn", "current-turn"))
    }

    @Test
    fun `provider preset ids are unique and defaults are usable`() {
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.id }.distinct().size)
        ProviderPresets.all.forEach { preset ->
            val isCliProvider = LocalAgentEngineRegistry.forProtocol(preset.protocol) != null ||
                preset.protocol == ProviderProtocol.CLI_QODER
            assertTrue(
                preset.defaultBaseUrl.startsWith("http") ||
                    preset.protocol == ProviderProtocol.CODEX_APP_SERVER ||
                    isCliProvider,
                preset.id,
            )
            assertTrue(preset.defaultModel.isNotBlank(), preset.id)
        }
    }

    @Test
    fun `native Codex backend is hidden from provider selection and does not require an api key`() {
        val preset = ProviderPresets.byId("codex-native")

        assertTrue(ProviderPresets.all.none { it.protocol == ProviderProtocol.CODEX_APP_SERVER })
        assertEquals(ProviderProtocol.CODEX_APP_SERVER, preset.protocol)
        assertEquals("codex://local", preset.defaultBaseUrl)
        assertEquals("codex-default", preset.defaultModel)
        assertTrue(preset.apiKeyOptional)
    }

    @Test
    fun `native subagent connection does not inherit the lead provider credentials`() {
        val lead = ProviderConnection(
            preset = ProviderPresets.byId("openai"),
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-5.6-sol",
            apiKey = "secret",
        )

        val subagent = codexNativeSubagentConnection(lead)

        assertEquals("codex-native-subagent", subagent.preset.id)
        assertEquals(ProviderProtocol.CODEX_APP_SERVER, subagent.preset.protocol)
        assertEquals("codex://local", subagent.baseUrl)
        assertEquals("codex-default", subagent.model)
        assertTrue(subagent.apiKey.isEmpty())
        assertEquals(1_800L, subagent.requestTimeoutSeconds)
    }

    @Test
    fun `native child lifecycle keeps the latest status per thread`() {
        val latest = latestNativeSubagentEvents(
            listOf(
                CodexNativeSubagentEvent(threadId = "thr-a", status = "running"),
                CodexNativeSubagentEvent(threadId = "thr-b", status = "running"),
                CodexNativeSubagentEvent(threadId = "thr-a", status = "completed", detail = "done"),
                CodexNativeSubagentEvent(threadId = "", status = "completed"),
            ),
        )

        assertEquals(listOf("thr-a", "thr-b"), latest.map { it.threadId })
        assertEquals("completed", latest.first().status)
        assertEquals("done", latest.first().detail)
    }

    @Test
    fun `native app server initialization opts into current collaboration events`() {
        val params = codexNativeInitializeParams(
            name = "test-client",
            title = "Test client",
            version = "1.0",
            experimentalApi = true,
        )

        assertEquals("test-client", params.getAsJsonObject("clientInfo").get("name").asString)
        val capabilities = params.getAsJsonObject("capabilities")
        assertTrue(capabilities.get("experimentalApi").asBoolean)
        assertFalse(capabilities.get("requestAttestation").asBoolean)
    }

    @Test
    fun `unregistered native dynamic tool is rejected with protocol shaped failure`() {
        val result = codexNativeUnavailableDynamicToolResult()

        assertFalse(result.get("success").asBoolean)
        val items = result.getAsJsonArray("contentItems")
        assertEquals(1, items.size())
        assertEquals("inputText", items[0].asJsonObject.get("type").asString)
        assertTrue(items[0].asJsonObject.get("text").asString.contains("No action was run"))
        assertFalse(result.has("decision"))
    }

    @Test
    fun `native approvals are scoped to the active turn and reject undisclosed escalation`() {
        val method = "item/commandExecution/requestApproval"
        val ordinary = Json.parseObject(
            """{"threadId":"thread-1","turnId":"turn-1","kind":"command","command":"pwd"}""",
        )
        assertTrue(codexNativeApprovalEligible(method, ordinary, "thread-1", "turn-1", AgentMode.AGENT))
        assertFalse(codexNativeApprovalEligible(method, ordinary, "thread-2", "turn-1", AgentMode.AGENT))
        assertFalse(codexNativeApprovalEligible(method, ordinary, "thread-1", "turn-2", AgentMode.AGENT))
        assertFalse(codexNativeApprovalEligible(method, ordinary, "thread-1", null, AgentMode.AGENT))
        assertFalse(codexNativeApprovalEligible(method, ordinary, "thread-1", "turn-1", AgentMode.PLAN))
        assertFalse(codexNativeApprovalEligible(
            method,
            Json.parseObject("""{"threadId":"thread-1","turnId":"turn-1"}"""),
            "thread-1", "turn-1", AgentMode.AGENT,
        ))
        assertFalse(codexNativeApprovalEligible(
            method,
            Json.parseObject("""{"threadId":"thread-1","turnId":"turn-1","command":"pwd","availableDecisions":"accept"}"""),
            "thread-1", "turn-1", AgentMode.AGENT,
        ))
        assertFalse(codexNativeApprovalEligible(
            method,
            Json.parseObject("""{"threadId":"thread-1","turnId":"turn-1","kind":"writeStdin"}"""),
            "thread-1", "turn-1", AgentMode.AGENT,
        ))
        assertFalse(codexNativeApprovalEligible(
            method,
            Json.parseObject("""{"threadId":"thread-1","turnId":"turn-1","additionalPermissions":{"network":{"enabled":true}}}"""),
            "thread-1", "turn-1", AgentMode.AGENT,
        ))
        assertFalse(codexNativeApprovalEligible(
            "item/fileChange/requestApproval",
            Json.parseObject("""{"threadId":"thread-1","turnId":"turn-1","grantRoot":"/tmp"}"""),
            "thread-1", "turn-1", AgentMode.AGENT,
        ))
    }

    @Test
    fun `native Codex usage derives current turn from cumulative snapshots`() {
        fun event(turn: String, input: Long, output: Long, lastInput: Long, lastOutput: Long) = Json.parseObject(
            """{"turnId":"$turn","tokenUsage":{"total":{"inputTokens":$input,"outputTokens":$output},"last":{"inputTokens":$lastInput,"outputTokens":$lastOutput}}}""",
        )

        val first = codexNativeTurnUsageUpdate(event("turn-2", 11_500, 20, 1_500, 5), "turn-2", null)!!
        assertEquals(1_500, first.usage.inputTokens)
        assertEquals(5, first.usage.outputTokens)
        val second = codexNativeTurnUsageUpdate(event("turn-2", 12_100, 35, 600, 15), "turn-2", first)!!
        assertEquals(2_100, second.usage.inputTokens)
        assertEquals(20, second.usage.outputTokens)
        assertEquals(second, codexNativeTurnUsageUpdate(event("turn-2", 12_100, 35, 600, 15), "turn-2", second))
        assertEquals(null, codexNativeTurnUsageUpdate(event("turn-1", 12_300, 40, 200, 5), "turn-2", second))
        assertEquals(null, codexNativeTurnUsageUpdate(event("turn-2", 11_900, 30, 100, 5), "turn-2", second))
    }

    @Test
    fun `native parent completion is not confused with a child completion`() {
        assertTrue(codexNativeIsParentThreadEvent("parent", "parent"))
        assertFalse(codexNativeIsParentThreadEvent("child", "parent"))
        assertFalse(codexNativeIsParentThreadEvent(null, "parent"))
    }

    @Test
    fun `native child activity status follows protocol kind rather than item envelope`() {
        // App Server reports subAgentActivity(kind=started) through item/completed.
        assertEquals("running", codexNativeSubagentStatus("started", itemStarted = false))
        assertEquals("running", codexNativeSubagentStatus("interacted", itemStarted = false))
        assertEquals("interrupted", codexNativeSubagentStatus("interrupted", itemStarted = false))
        assertEquals("failed", codexNativeSubagentStatus("errored", itemStarted = false))
        assertEquals("completed", codexNativeSubagentStatus("shutdown", itemStarted = false))
    }

    @Test
    fun `native app server response stream reconnect notices are classified narrowly`() {
        val notice = codexNativeReconnectNotice(
            Json.parseObject(
                """{"error":{"message":"Reconnecting... 2/5","codexErrorInfo":{"responseStreamDisconnected":{}},"additionalDetails":"request timed out","willRetry":true}}""",
            ),
        )

        assertEquals(2, notice?.attempt)
        assertEquals(5, notice?.total)
        assertEquals(true, notice?.willRetry)
        assertFalse(codexNativeReconnectIsTerminal(notice!!))
        assertEquals(
            "Codex 原生上游响应流已断开，App Server 正在自动重连（2/5）…",
            codexNativeReconnectStatus(notice),
        )
        assertTrue(
            codexNativeReconnectNotice(
                Json.parseObject("""{"message":"invalid api key","willRetry":false}"""),
            ) == null,
        )
    }

    @Test
    fun `native Codex treats outer willRetry as authoritative and retry exhaustion as terminal`() {
        val retrying = codexNativeReconnectNotice(
            Json.parseObject(
                """{"error":{"message":"Reconnecting... 5/5","codexErrorInfo":{"responseStreamDisconnected":{"httpStatusCode":502}}},"willRetry":true,"threadId":"thread-1","turnId":"turn-1"}""",
            ),
        )!!
        assertEquals(true, retrying.willRetry)
        assertEquals(502, retrying.httpStatusCode)
        assertFalse(codexNativeReconnectIsTerminal(retrying))

        val exhausted = codexNativeReconnectNotice(
            Json.parseObject(
                """{"error":{"message":"response retries exhausted","codexErrorInfo":{"responseTooManyFailedAttempts":{"httpStatusCode":502}}},"willRetry":false,"threadId":"thread-1","turnId":"turn-1"}""",
            ),
        )!!
        assertTrue(codexNativeReconnectIsTerminal(exhausted))
        assertFalse(codexNativeReconnectFailureMessage().contains("请检查 Codex 登录"))
        assertTrue(codexNativeReconnectFailureMessage().contains("登录有效"))
        assertTrue(codexNativeReconnectFailureMessage().contains("无需重新登录"))
    }

    @Test
    fun `native Codex creates a replacement only for a definitely missing rollout`() {
        assertTrue(
            codexNativeResumeSessionMissing(
                ProviderException("Codex 原生 App Server 请求 thread/resume 失败：rollout not found"),
            ),
        )
        assertFalse(
            codexNativeResumeSessionMissing(
                ProviderException("Codex 原生 App Server 请求 thread/resume 失败：request timed out", networkFailure = true),
            ),
        )
    }

    @Test
    fun `OpenCode Zen preset uses the official gateway and Big Pickle default`() {
        val preset = ProviderPresets.byId("opencode")

        assertEquals("OpenCode Zen", preset.displayName)
        assertEquals(ProviderProtocol.OPENCODE_ZEN, preset.protocol)
        assertEquals("https://opencode.ai/zen/v1", preset.defaultBaseUrl)
        assertEquals("big-pickle", preset.defaultModel)
        assertFalse(preset.apiKeyOptional)
    }

    @Test
    fun `provider errors redact secrets`() {
        val secret = "sk-super-secret-value"
        val sanitized = sanitizeProviderText("server echoed $secret", listOf(secret)).orEmpty()
        assertFalse(sanitized.contains(secret))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `streamed values tolerate complete and incremental provider chunks`() {
        assertEquals("read_file", mergeStreamedValue("read_", "file"))
        assertEquals("read_file", mergeStreamedValue("read_file", "read_file"))
        assertEquals("read_file", mergeStreamedValue("read_", "read_file"))
    }

    @Test
    fun `vision routing stays conservative for OpenAI compatible text models`() {
        fun connection(protocol: ProviderProtocol, model: String) = ProviderConnection(
            preset = ProviderPreset("test", "Test", protocol, "https://example.test", model),
            baseUrl = "https://example.test",
            model = model,
            apiKey = "",
        )

        assertTrue(connection(ProviderProtocol.OPENAI_RESPONSES, "gpt-5.6").likelySupportsVision())
        assertFalse(connection(ProviderProtocol.OPENAI_CHAT, "deepseek-chat").likelySupportsVision())
        assertTrue(connection(ProviderProtocol.GEMINI, "gemini-2.5-pro").likelySupportsVision())
    }
}
