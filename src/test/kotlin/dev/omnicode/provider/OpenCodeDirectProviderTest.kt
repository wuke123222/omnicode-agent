package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertTrue

class OpenCodeDirectProviderTest {
    @Test
    fun `factory routes OpenCode to direct CCGUI-compatible JSON stream`() {
        val provider = ProviderFactory.create(
            ProviderConnection(
                preset = ProviderPresets.byId("cli-opencode"),
                baseUrl = "cli://local",
                model = "default",
                apiKey = "",
            ),
        )

        assertTrue(provider is CliToolProvider)
    }
}
