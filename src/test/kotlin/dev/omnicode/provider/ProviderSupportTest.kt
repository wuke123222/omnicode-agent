package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSupportTest {
    @Test
    fun `provider preset ids are unique and defaults are usable`() {
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.id }.distinct().size)
        ProviderPresets.all.forEach { preset ->
            assertTrue(preset.defaultBaseUrl.startsWith("http"), preset.id)
            assertTrue(preset.defaultModel.isNotBlank(), preset.id)
        }
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
