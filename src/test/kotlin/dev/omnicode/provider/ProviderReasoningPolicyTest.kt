package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderReasoningPolicyTest {
    @Test
    fun `GPT 5_6 full speed resolves to max plus pro on Responses`() {
        val resolved = resolveReasoningEffort(
            providerId = "openai",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
            model = "gpt-5.6-sol",
            requested = ReasoningEffort.MAX,
        )

        assertEquals(ReasoningEffort.MAX, resolved.effective)
        assertEquals("max", resolved.wireValue)
        assertEquals(ReasoningWireFormat.OPENAI_RESPONSES, resolved.wireFormat)
        assertTrue(resolved.openAiProMode)
    }

    @Test
    fun `older GPT full speed uses its verified highest level`() {
        val resolved = resolveReasoningEffort(
            providerId = "openai",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
            model = "gpt-5.4",
            requested = ReasoningEffort.MAX,
        )

        assertEquals(ReasoningEffort.XHIGH, resolved.effective)
        assertEquals("xhigh", resolved.wireValue)
        assertFalse(resolved.openAiProMode)
    }

    @Test
    fun `OpenRouter uses its reasoning object dialect`() {
        val resolved = resolveReasoningEffort(
            providerId = "openrouter",
            protocol = ProviderProtocol.OPENAI_CHAT,
            model = "openai/gpt-5.4",
            requested = ReasoningEffort.HIGH,
        )

        assertEquals(ReasoningWireFormat.OPENROUTER, resolved.wireFormat)
        assertEquals("high", resolved.wireValue)
    }

    @Test
    fun `Gemini 2_5 maps levels to bounded budgets`() {
        val medium = resolveReasoningEffort(
            "gemini",
            ProviderProtocol.GEMINI,
            "gemini-2.5-pro",
            ReasoningEffort.MEDIUM,
        )
        val maximum = resolveReasoningEffort(
            "gemini",
            ProviderProtocol.GEMINI,
            "gemini-2.5-pro",
            ReasoningEffort.MAX,
        )

        assertEquals(ReasoningWireFormat.GEMINI_BUDGET, medium.wireFormat)
        assertEquals(8_192, medium.thinkingBudget)
        assertEquals(32_768, maximum.thinkingBudget)
    }

    @Test
    fun `Gemini 3 pro hides unsupported minimal instead of inventing a mapping`() {
        val resolved = resolveReasoningEffort(
            "gemini",
            ProviderProtocol.GEMINI,
            "gemini-3.1-pro",
            ReasoningEffort.MINIMAL,
        )

        assertFalse(resolved.supported)
        assertEquals(ReasoningWireFormat.UNSUPPORTED, resolved.wireFormat)
    }

    @Test
    fun `Gemini model families expose only documented thinking levels`() {
        val pro = reasoningEffortOptions("gemini", ProviderProtocol.GEMINI, "gemini-3.1-pro")
        val image = reasoningEffortOptions(
            "gemini",
            ProviderProtocol.GEMINI,
            "gemini-3.1-flash-lite-image",
        )

        assertEquals(
            listOf(
                ReasoningEffort.AUTO,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            pro,
        )
        assertEquals(
            listOf(ReasoningEffort.AUTO, ReasoningEffort.MINIMAL, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            image,
        )
    }

    @Test
    fun `Bedrock unknown models stay agent-only instead of inventing request fields`() {
        val nova = resolveReasoningEffort(
            "bedrock",
            ProviderProtocol.BEDROCK_CONVERSE,
            "amazon.nova-2-lite-v1:0",
            ReasoningEffort.MAX,
        )
        val unknown = resolveReasoningEffort(
            "bedrock",
            ProviderProtocol.BEDROCK_CONVERSE,
            "deepseek.r1-v1:0",
            ReasoningEffort.HIGH,
        )

        assertEquals(ReasoningWireFormat.BEDROCK_NOVA, nova.wireFormat)
        assertEquals(ReasoningEffort.HIGH, nova.effective)
        assertTrue(unknown.supported)
        assertEquals(ReasoningWireFormat.OMIT, unknown.wireFormat)
    }

    @Test
    fun `unknown compatible models use agent-only intensity without invalid wire fields`() {
        val resolved = resolveReasoningEffort(
            "groq",
            ProviderProtocol.OPENAI_CHAT,
            "llama-3.3-70b-versatile",
            ReasoningEffort.MAX,
        )
        val options = reasoningEffortOptions(
            "groq",
            ProviderProtocol.OPENAI_CHAT,
            "llama-3.3-70b-versatile",
        )

        assertTrue(resolved.supported)
        assertEquals(ReasoningWireFormat.OMIT, resolved.wireFormat)
        assertEquals(ReasoningEffort.HIGH, resolved.effective)
        assertEquals(
            listOf(
                ReasoningEffort.AUTO,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            options,
        )
    }

    @Test
    fun `GPT 5 pro only exposes high and full speed mapped to high`() {
        val options = reasoningEffortOptions(
            "openai",
            ProviderProtocol.OPENAI_RESPONSES,
            "gpt-5-pro",
        )
        val full = resolveReasoningEffort(
            "openai",
            ProviderProtocol.OPENAI_RESPONSES,
            "gpt-5-pro",
            ReasoningEffort.MAX,
        )

        assertEquals(listOf(ReasoningEffort.AUTO, ReasoningEffort.HIGH, ReasoningEffort.MAX), options)
        assertEquals(ReasoningEffort.HIGH, full.effective)
        assertEquals("high", full.wireValue)
    }

    @Test
    fun `Anthropic effort is native only on documented model families`() {
        val legacy = resolveReasoningEffort(
            "anthropic",
            ProviderProtocol.ANTHROPIC_MESSAGES,
            "claude-sonnet-4-5",
            ReasoningEffort.HIGH,
        )
        val opus = reasoningEffortOptions(
            "anthropic",
            ProviderProtocol.ANTHROPIC_MESSAGES,
            "claude-opus-4-7",
        )

        assertEquals(ReasoningWireFormat.OMIT, legacy.wireFormat)
        assertTrue(legacy.explanation.contains("Agent"))
        assertTrue(ReasoningEffort.XHIGH in opus)
        assertTrue(ReasoningEffort.MAX in opus)
    }

    @Test
    fun `Nova capability matching excludes other Nova 2 products`() {
        val sonic = resolveReasoningEffort(
            "bedrock",
            ProviderProtocol.BEDROCK_CONVERSE,
            "amazon.nova-2-sonic-v1:0",
            ReasoningEffort.HIGH,
        )

        assertEquals(ReasoningWireFormat.OMIT, sonic.wireFormat)
    }

    @Test
    fun `reasoning levels recommend enough room for hidden tokens and visible output`() {
        assertEquals(8_192, ReasoningEffort.LOW.recommendedOutputTokenFloor())
        assertEquals(32_768, ReasoningEffort.HIGH.recommendedOutputTokenFloor())
        assertEquals(65_536, ReasoningEffort.MAX.recommendedOutputTokenFloor())
    }

    @Test
    fun `model-aware full speed reserves documented 128k output families`() {
        val openAi = ProviderConnection(
            preset = ProviderPresets.byId("openai"),
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-5.6-sol",
            apiKey = "test",
            reasoningEffort = ReasoningEffort.MAX,
        )
        val resolution = openAi.requireReasoningResolution()

        assertEquals(131_072, openAi.recommendedOutputTokenFloor(resolution))
    }
}
