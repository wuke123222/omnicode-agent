package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.omnicode.util.Json

class ProviderSupportTest {
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
    fun `native app server response stream reconnect notices are classified narrowly`() {
        val notice = codexNativeReconnectNotice(
            Json.parseObject(
                """{"error":{"message":"Reconnecting... 2/5","codexErrorInfo":{"responseStreamDisconnected":{}},"additionalDetails":"request timed out","willRetry":true}}""",
            ),
        )

        assertEquals(2, notice?.attempt)
        assertEquals(5, notice?.total)
        assertEquals(
            "Codex 原生响应流已断开，App Server 正在重连（2/5）…",
            codexNativeReconnectStatus(notice!!),
        )
        assertTrue(
            codexNativeReconnectNotice(
                Json.parseObject("""{"message":"invalid api key","willRetry":false}"""),
            ) == null,
        )
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
