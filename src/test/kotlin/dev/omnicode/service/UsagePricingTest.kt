package dev.omnicode.service

import dev.omnicode.model.TokenUsage
import dev.omnicode.settings.ModelPricing
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsagePricingTest {
    @Test
    fun `wildcard pricing calculates input and output cost`() {
        val cost = estimateUsageCost(
            providerId = "openai",
            model = "gpt-test",
            usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 500_000),
            pricing = listOf(ModelPricing("*", "*", 2.0, 8.0)),
        )

        assertEquals(BigDecimal("6.00000000"), cost)
    }

    @Test
    fun `exact provider and model override wildcard rules`() {
        val cost = estimateUsageCost(
            providerId = "OPENAI",
            model = "GPT-SPECIAL",
            usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000),
            pricing = listOf(
                ModelPricing("*", "*", 1.0, 1.0),
                ModelPricing("openai", "gpt-*", 2.0, 3.0),
                ModelPricing("openai", "gpt-special", 4.0, 5.0),
            ),
        )

        assertEquals(BigDecimal("9.00000000"), cost)
    }

    @Test
    fun `missing or zero pricing remains unpriced`() {
        val usage = TokenUsage(inputTokens = 100, outputTokens = 20)

        assertNull(estimateUsageCost("openai", "gpt", usage, emptyList()))
        assertNull(estimateUsageCost("openai", "gpt", usage, listOf(ModelPricing("*", "*", 0.0, 0.0))))
    }
}
