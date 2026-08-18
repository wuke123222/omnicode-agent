package dev.omnicode.settings

import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmniCodeCredentialFlowTest {
    @Test
    fun `failed PasswordSafe save keeps the previous credential baseline`() {
        val saved = ProviderSecrets(apiKey = "saved-key")
        val draft = ProviderSecrets(apiKey = "new-key")

        assertEquals(
            saved,
            credentialBaselineAfterSaveAttempt(saved, draft, saveRequested = true, saveSucceeded = false),
        )
    }

    @Test
    fun `successful PasswordSafe save advances the credential baseline`() {
        val saved = ProviderSecrets(apiKey = "saved-key")
        val draft = ProviderSecrets(apiKey = "new-key")

        assertEquals(
            draft,
            credentialBaselineAfterSaveAttempt(saved, draft, saveRequested = true, saveSucceeded = true),
        )
    }

    @Test
    fun `refresh without a save leaves the credential baseline unchanged`() {
        val saved = ProviderSecrets(apiKey = "saved-key")

        assertEquals(
            saved,
            credentialBaselineAfterSaveAttempt(saved, saved, saveRequested = false, saveSucceeded = true),
        )
    }

    @Test
    fun `raw API key remains unchanged`() {
        val parsed = normalizeApiKeyInput("test-openai-key", "openai")

        assertEquals("test-openai-key", parsed.value)
        assertNull(parsed.sourceVariable)
    }

    @Test
    fun `OpenAI JSON environment snippet is imported`() {
        val parsed = normalizeApiKeyInput(
            """{"OPENAI_API_KEY":"test-openai-key"}""",
            "openai",
        )

        assertEquals("test-openai-key", parsed.value)
        assertEquals("OPENAI_API_KEY", parsed.sourceVariable)
    }

    @Test
    fun `export assignment is imported without quotes`() {
        val parsed = normalizeApiKeyInput(
            "export OPENAI_API_KEY='test-openai-key'",
            "openai",
        )

        assertEquals("test-openai-key", parsed.value)
        assertEquals("OPENAI_API_KEY", parsed.sourceVariable)
    }

    @Test
    fun `named OpenAI key is rejected under another provider`() {
        val error = assertFailsWith<CredentialInputFormatException> {
            normalizeApiKeyInput(
                """{"OPENAI_API_KEY":"test-openai-key"}""",
                "deepseek",
            )
        }

        assertTrue(error.message.orEmpty().contains("当前供应商"))
    }

    @Test
    fun `environment is used only when Password Safe key is empty`() {
        val fromEnvironment = resolveProviderSecrets("openai", ProviderSecrets()) { name ->
            if (name == "OPENAI_API_KEY") "environment-key" else null
        }
        val fromPasswordSafe = resolveProviderSecrets(
            "openai",
            ProviderSecrets(apiKey = "stored-key"),
        ) { "environment-key" }

        assertEquals("environment-key", fromEnvironment.secrets.apiKey)
        assertEquals("OPENAI_API_KEY", fromEnvironment.environmentVariable)
        assertEquals("stored-key", fromPasswordSafe.secrets.apiKey)
        assertNull(fromPasswordSafe.environmentVariable)
    }

    @Test
    fun `environment key is blocked for a custom non-default remote origin`() {
        val resolved = resolveProviderSecrets(
            providerId = "custom",
            stored = ProviderSecrets(),
            environment = { name -> if (name == "OPENAI_API_KEY") "environment-key" else null },
            baseUrl = "https://gateway.example.com/v1",
        )

        assertTrue(resolved.secrets.apiKey.isBlank())
        assertEquals("OPENAI_API_KEY", resolved.blockedEnvironmentVariable)
        assertNull(resolved.environmentVariable)
    }

    @Test
    fun `environment key remains compatible with default and loopback origins`() {
        val environment: (String) -> String? = { name ->
            if (name == "OPENAI_API_KEY") "environment-key" else null
        }

        val defaultOrigin = resolveProviderSecrets("openai", ProviderSecrets(), environment = environment)
        val localProxy = resolveProviderSecrets(
            providerId = "openai",
            stored = ProviderSecrets(),
            environment = environment,
            baseUrl = "http://127.0.0.1:8080/v1",
        )

        assertEquals("environment-key", defaultOrigin.secrets.apiKey)
        assertEquals("environment-key", localProxy.secrets.apiKey)
    }

    @Test
    fun `credential origin mismatch fails with a rebind instruction`() {
        val error = assertFailsWith<CredentialOriginMismatchException> {
            requireMatchingCredentialOrigin("https://api.openai.com", "https://proxy.example.com")
        }

        assertTrue(error.message.orEmpty().contains("阻止复用旧凭据"))
        assertTrue(error.message.orEmpty().contains("保存并加载模型"))
    }

    @Test
    fun `changing only endpoint path does not require credential rebind`() {
        assertTrue(!credentialOriginChanged("https://api.example.com/v1", "https://api.example.com/v2"))
        assertTrue(credentialOriginChanged("https://api.example.com/v1", "https://other.example.com/v1"))
    }

    @Test
    fun `OpenAI 401 message distinguishes API platform credentials`() {
        val message = modelAuthenticationError(
            dev.omnicode.provider.ProviderPresets.byId("openai"),
            "https://api.openai.com/v1",
        )

        assertTrue(message.contains("platform.openai.com/api-keys"))
        assertTrue(message.contains("ChatGPT"))
    }

    @Test
    fun `free-form model change is rejected when retained effort is unsupported`() {
        val openAi = ProviderPresets.byId("openai")
        val edited = OmniCodeSettingsSnapshot(
            providerId = openAi.id,
            baseUrl = openAi.defaultBaseUrl,
            model = "gpt-5.1",
            region = OmniCodeSettingsDefaults.REGION,
            apiVersion = OmniCodeSettingsDefaults.API_VERSION,
            maxOutputTokens = 65_536,
            reasoningEffort = ReasoningEffort.XHIGH,
        )

        val message = providerValidationError(edited)

        assertTrue(message.orEmpty().contains("gpt-5.1"))
        assertTrue(message.orEmpty().contains("XHigh"))
        assertTrue(message.orEmpty().contains("请选择 Auto"))
    }

    @Test
    fun `unsupported persisted effort remains visible until user resolves it`() {
        val anthropic = ProviderPresets.byId("anthropic")

        val state = reasoningEffortEditorState(
            preset = anthropic,
            model = "claude-sonnet-4-6",
            requested = ReasoningEffort.NONE,
        )

        assertEquals(ReasoningEffort.NONE, state.selected)
        assertTrue(state.unsupportedSelection)
        assertTrue(ReasoningEffort.NONE in state.options)
        assertTrue(ReasoningEffort.AUTO in state.options)
    }

    @Test
    fun `supported effort remains valid at settings apply boundary`() {
        val anthropic = ProviderPresets.byId("anthropic")
        val snapshot = OmniCodeSettingsSnapshot(
            providerId = anthropic.id,
            baseUrl = anthropic.defaultBaseUrl,
            model = "claude-sonnet-4-6",
            region = OmniCodeSettingsDefaults.REGION,
            apiVersion = "2023-06-01",
            maxOutputTokens = 16_384,
            reasoningEffort = ReasoningEffort.MEDIUM,
        )

        assertNull(providerValidationError(snapshot))
    }

    @Test
    fun `local CLI provider defaults validate so saving other providers is not blocked`() {
        ProviderPresets.all.filter { it.id.startsWith("cli-") }.forEach { preset ->
            val snapshot = OmniCodeSettingsSnapshot(
                providerId = preset.id,
                baseUrl = preset.defaultBaseUrl,
                model = preset.defaultModel,
                region = OmniCodeSettingsDefaults.REGION,
                apiVersion = OmniCodeSettingsDefaults.API_VERSION,
                maxOutputTokens = OmniCodeSettingsDefaults.MAX_OUTPUT_TOKENS,
                reasoningEffort = ReasoningEffort.AUTO,
            )

            assertNull(providerValidationError(snapshot), "cli preset ${preset.id} should validate")
        }
    }

    @Test
    fun `profile validation error names the offending provider`() {
        val openAi = ProviderPresets.byId("openai")
        val valid = OmniCodeSettingsSnapshot(
            providerId = openAi.id,
            baseUrl = openAi.defaultBaseUrl,
            model = openAi.defaultModel,
            region = OmniCodeSettingsDefaults.REGION,
            apiVersion = OmniCodeSettingsDefaults.API_VERSION,
            maxOutputTokens = OmniCodeSettingsDefaults.MAX_OUTPUT_TOKENS,
            reasoningEffort = ReasoningEffort.AUTO,
        )
        val broken = valid.copy(providerId = "deepseek", baseUrl = "api.deepseek.com")

        val message = providerProfilesValidationError(listOf(valid, broken))

        assertTrue(message.orEmpty().contains("DeepSeek"))
        assertTrue(message.orEmpty().contains("https://"))
        assertNull(providerProfilesValidationError(listOf(valid)))
    }
}
