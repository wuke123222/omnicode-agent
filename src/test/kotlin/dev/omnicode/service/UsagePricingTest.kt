package dev.omnicode.service

import dev.omnicode.model.TokenUsage
import dev.omnicode.settings.ModelPricing
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertNull(estimateUsageCost("openai", "gpt", usage, listOf(ModelPricing("*", "*", Double.NaN, 1.0))))
        assertNull(estimateUsageCost("openai", "gpt", usage, listOf(ModelPricing("*", "*", 1.0, Double.POSITIVE_INFINITY))))
    }

    @Test
    fun `cost limit requires valid pricing for every provider role`() {
        listOf("主模型", "视觉辅助模型", "专家模型").forEach { purpose ->
            val error = assertFailsWith<PricingUnavailableException> {
                requireModelPricingForCostLimit(
                    maxCostUsd = BigDecimal("2.50"),
                    providerId = "openai",
                    model = "gpt-unpriced",
                    pricing = emptyList(),
                    purpose = purpose,
                )
            }

            assertTrue(error.message.orEmpty().contains(purpose))
            assertTrue(error.message.orEmpty().contains("本次请求尚未发送"))
        }
    }

    @Test
    fun `cost pricing preflight allows a valid rule or a disabled cost limit`() {
        requireModelPricingForCostLimit(
            maxCostUsd = BigDecimal("2.50"),
            providerId = "openai",
            model = "gpt-priced",
            pricing = listOf(ModelPricing("openai", "gpt-*", 1.0, 2.0)),
            purpose = "主模型",
        )
        requireModelPricingForCostLimit(
            maxCostUsd = null,
            providerId = "openai",
            model = "gpt-unpriced",
            pricing = emptyList(),
            purpose = "主模型",
        )
    }
}
