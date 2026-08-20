package dev.omnicode.settings

import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ProviderProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderProtocolLabelTest {
    @Test
    fun `every protocol has a short subtitle for the provider cards`() {
        ProviderProtocol.entries.forEach { protocol ->
            val label = providerProtocolLabel(protocol)
            assertTrue(label.isNotBlank(), "$protocol should have a card subtitle")
            assertTrue(label.length <= 24, "$protocol subtitle should stay short, was '$label'")
        }
    }

    @Test
    fun `card subtitles distinguish native and compatible OpenAI protocols`() {
        assertEquals("OpenAI Responses", providerProtocolLabel(ProviderProtocol.OPENAI_RESPONSES))
        assertEquals("OpenAI 兼容", providerProtocolLabel(ProviderProtocol.OPENAI_CHAT))
        ProviderPresets.all.filter { it.id.startsWith("cli-") }.forEach { preset ->
            assertEquals("本地 CLI", providerProtocolLabel(preset.protocol))
        }
    }
}
