package dev.omnicode.settings

import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OmniCodeSettingsProfilesTest {
    @Test
    fun `switching providers preserves endpoint and model per provider`() {
        val service = OmniCodeSettingsService()
        service.loadState(OmniCodeSettingsState().apply {
            providerId = "deepseek"
            baseUrl = "https://deepseek.example/v1"
            model = "deepseek-custom"
        })

        val openAiDefault = service.activateProvider("openai")
        assertEquals(ProviderPresets.byId("openai").defaultModel, openAiDefault.model)

        service.update(openAiDefault.copy(
            baseUrl = "https://openai.example/v1",
            model = "gpt-custom",
        ))
        assertEquals("deepseek-custom", service.activateProvider("deepseek").model)
        assertEquals("https://deepseek.example/v1", service.snapshot().baseUrl)

        assertEquals("gpt-custom", service.activateProvider("openai").model)
        assertEquals("https://openai.example/v1", service.snapshot().baseUrl)
    }

    @Test
    fun `profile update saves multiple providers and activates selected one`() {
        val service = OmniCodeSettingsService()
        val openAi = service.snapshotFor("openai").copy(model = "gpt-profile")
        val anthropic = service.snapshotFor("anthropic").copy(model = "claude-profile")

        service.updateProfiles("anthropic", listOf(openAi, anthropic))

        assertEquals("anthropic", service.snapshot().providerId)
        assertEquals("claude-profile", service.snapshot().model)
        assertEquals("gpt-profile", service.snapshotFor("openai").model)
    }

    @Test
    fun `reasoning effort and its output floor are retained per provider`() {
        val service = OmniCodeSettingsService()
        val openAi = service.snapshotFor("openai").copy(
            model = "gpt-5.6-sol",
            maxOutputTokens = 8_192,
            reasoningEffort = ReasoningEffort.MAX,
        )
        val anthropic = service.snapshotFor("anthropic").copy(
            model = "claude-sonnet-4-6",
            maxOutputTokens = 8_192,
            reasoningEffort = ReasoningEffort.MEDIUM,
        )

        service.updateProfiles("openai", listOf(openAi, anthropic))

        assertEquals(ReasoningEffort.MAX, service.snapshot().reasoningEffort)
        assertEquals(65_536, service.snapshot().maxOutputTokens)
        assertEquals(ReasoningEffort.MEDIUM, service.activateProvider("anthropic").reasoningEffort)
        assertEquals(16_384, service.snapshot().maxOutputTokens)
        assertEquals(ReasoningEffort.MAX, service.activateProvider("openai").reasoningEffort)
        assertEquals(65_536, service.snapshot().maxOutputTokens)
    }

    @Test
    fun `vision helper model is retained with its provider profile`() {
        val service = OmniCodeSettingsService()
        service.update(service.snapshotFor("openai").copy(
            model = "gpt-text-only",
        ))
        service.updateVisionModels(mapOf("openai" to "gpt-4.1-mini"))

        service.activateProvider("deepseek")
        assertEquals("gpt-4.1-mini", service.visionModelFor("openai"))
        service.activateProvider("openai")
        assertEquals("gpt-4.1-mini", service.visionModelFor(service.snapshot().providerId))
    }

    @Test
    fun `legacy active settings migrate into a provider profile`() {
        val service = OmniCodeSettingsService()
        service.loadState(OmniCodeSettingsState().apply {
            providerId = "deepseek"
            baseUrl = " https://legacy.example/v1 "
            model = " legacy-model "
        })

        val profile = service.snapshotFor("deepseek")
        assertEquals("https://legacy.example/v1", profile.baseUrl)
        assertEquals("legacy-model", profile.model)
        assertEquals("legacy-model", service.state.providerProfiles.single().model)
    }

    @Test
    fun `legacy missing or invalid reasoning effort falls back to auto`() {
        val service = OmniCodeSettingsService()
        service.loadState(OmniCodeSettingsState().apply {
            providerId = "openai"
            reasoningEffort = "obsolete-ultra"
            maxOutputTokens = 512
            providerProfiles += ProviderProfileState().also { profile ->
                profile.providerId = "anthropic"
                profile.reasoningEffort = "not-a-level"
                profile.maxOutputTokens = 1_024
            }
        })

        assertEquals(ReasoningEffort.AUTO, service.snapshot().reasoningEffort)
        assertEquals(8_192, service.snapshot().maxOutputTokens)
        assertEquals(ReasoningEffort.AUTO, service.snapshotFor("anthropic").reasoningEffort)
        assertEquals("auto", service.state.reasoningEffort)
        assertEquals("auto", service.state.providerProfiles.first { it.providerId == "anthropic" }.reasoningEffort)
    }

    @Test
    fun `reasoning effort chooses bounded provider request timeouts`() {
        val expected = mapOf(
            ReasoningEffort.AUTO to 120L,
            ReasoningEffort.NONE to 120L,
            ReasoningEffort.MINIMAL to 120L,
            ReasoningEffort.LOW to 120L,
            ReasoningEffort.MEDIUM to 180L,
            ReasoningEffort.HIGH to 300L,
            ReasoningEffort.XHIGH to 600L,
            ReasoningEffort.MAX to 1_800L,
        )

        expected.forEach { (effort, timeout) ->
            assertEquals(timeout, requestTimeoutSecondsForReasoning(effort), effort.name)
        }
    }

    @Test
    fun `removed active provider cannot overwrite the default provider profile`() {
        val openAi = ProviderPresets.byId("openai")
        val service = OmniCodeSettingsService()
        service.loadState(OmniCodeSettingsState().apply {
            providerId = "removed-provider"
            baseUrl = "https://removed-provider.example/v1"
            model = "removed-model"
            providerProfiles += ProviderProfileState().also { profile ->
                profile.providerId = openAi.id
                profile.baseUrl = "https://openai-profile.example/v1"
                profile.model = "gpt-profile"
            }
        })

        assertEquals(openAi.id, service.snapshot().providerId)
        assertEquals("https://openai-profile.example/v1", service.snapshot().baseUrl)
        assertEquals("gpt-profile", service.snapshot().model)
        assertFalse(service.profileSnapshots().containsKey("removed-provider"))
    }

    @Test
    fun `unknown profile updates cannot overwrite a known provider`() {
        val service = OmniCodeSettingsService()
        service.update(service.snapshotFor("openai").copy(
            baseUrl = "https://known-openai.example/v1",
            model = "known-model",
        ))

        service.updateProfiles(
            activeProviderId = "removed-provider",
            snapshots = listOf(
                OmniCodeSettingsSnapshot(
                    providerId = "removed-provider",
                    baseUrl = "https://removed-provider.example/v1",
                    model = "removed-model",
                    region = "us-east-1",
                    apiVersion = "v1",
                    maxOutputTokens = 1_024,
                ),
            ),
        )

        assertEquals("openai", service.snapshot().providerId)
        assertEquals("https://known-openai.example/v1", service.snapshot().baseUrl)
        assertEquals("known-model", service.snapshot().model)
    }
}
