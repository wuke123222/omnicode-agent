package dev.omnicode.service

import dev.omnicode.settings.OmniCodeSettingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProviderModelCatalogServiceTest {
    @Test
    fun `cache key includes every setting that can change model discovery`() {
        val base = snapshot()

        assertNotEquals(
            providerModelCatalogCacheKey(base),
            providerModelCatalogCacheKey(base.copy(providerId = "anthropic")),
        )
        assertNotEquals(
            providerModelCatalogCacheKey(base),
            providerModelCatalogCacheKey(base.copy(baseUrl = "https://other.example/v1")),
        )
        assertNotEquals(
            providerModelCatalogCacheKey(base),
            providerModelCatalogCacheKey(base.copy(region = "eu-west-1")),
        )
        assertNotEquals(
            providerModelCatalogCacheKey(base),
            providerModelCatalogCacheKey(base.copy(apiVersion = "2023-06-01")),
        )
        assertEquals(
            providerModelCatalogCacheKey(base),
            providerModelCatalogCacheKey(base.copy(model = "another-model")),
        )
    }

    @Test
    fun `transient discovery errors are not cached`() {
        assertEquals(
            false,
            shouldCacheProviderModelCatalog(
                ProviderModelCatalog(
                    providerId = "openai",
                    providerName = "OpenAI",
                    models = listOf("gpt-5"),
                    discoveredRemotely = false,
                    status = "Unable to load models",
                    error = "HTTP 429",
                ),
            ),
        )
        assertEquals(
            true,
            shouldCacheProviderModelCatalog(
                ProviderModelCatalog(
                    providerId = "openai",
                    providerName = "OpenAI",
                    models = listOf("gpt-5"),
                    discoveredRemotely = true,
                    status = "Found 1 available model.",
                ),
            ),
        )
    }

    @Test
    fun `catalog requests are independent and invalidation rejects queued late delivery`() {
        val queue = ProviderModelCatalogRequestQueue()
        var firstFinished = 0
        var secondFinished = 0
        val first = queue.register { firstFinished++ }
        val second = queue.register { secondFinished++ }

        assertEquals(2, queue.activeCount())
        assertTrue(queue.claim(first))
        first.onFinished()
        assertEquals(1, firstFinished)
        assertEquals(1, queue.activeCount())

        val canceled = queue.cancelAll()
        canceled.forEach { it.onFinished() }
        assertEquals(listOf(second.id), canceled.map { it.id })
        assertEquals(1, secondFinished)
        assertFalse(queue.claim(second), "an invalidated request must not deliver after cancellation")
        assertFalse(queue.ifActive(second) { error("stale request action must not run") })
        assertEquals(0, queue.activeCount())
    }

    private fun snapshot(): OmniCodeSettingsSnapshot = OmniCodeSettingsSnapshot(
        providerId = "openai",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-5",
        region = "us-east-1",
        apiVersion = "2025-04-01-preview",
        maxOutputTokens = 8_192,
    )
}
