package dev.omnicode.service

import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.TokenUsage
import dev.omnicode.provider.ModelProvider
import dev.omnicode.settings.CommitAiSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommitAiServiceTest {
    @Test
    fun `generates one editable message with provider metadata`() = runBlocking {
        val provider = RecordingProvider(
            ModelResponse(
                blocks = listOf(ContentBlock.Text("feat: 更新缓存\n\nthis body must be removed")),
                usage = TokenUsage(120, 18),
            ),
        )
        val service = CommitAiService(
            settingsSource = settings(
                includeBody = false,
                language = "Chinese",
                prompt = "Write a conventional commit message.",
            ),
            stagedDiffSource = StagedDiffSource { StagedDiff("diff --git a/a.kt b/a.kt\n+val cached = true") },
            providerResolver = CommitAiProviderResolver { _ ->
                CommitAiProviderTarget(provider, "OpenAI", "gpt-test", 8_192)
            },
        )

        val result = service.generate(createTempDirectory("commit-ai-project"))

        assertEquals("feat: 更新缓存", result.text)
        assertEquals(TokenUsage(120, 18), result.usage)
        assertEquals("OpenAI", result.provider)
        assertEquals("gpt-test", result.model)
        val request = assertNotNull(provider.request)
        assertTrue(request.tools.isEmpty())
        assertEquals(192, request.maxOutputTokens)
        assertEquals(0.2, request.temperature)
        assertTrue(request.messages[0].toString().contains("Write a conventional commit message"))
        assertTrue(request.messages[0].toString().contains("untrusted repository data"))
        assertTrue(request.messages[0].toString().contains("Chinese"))
        assertTrue(request.messages[1].toString().contains("<staged-diff>"))
        assertTrue(request.messages[1].toString().contains("+val cached = true"))
    }

    @Test
    fun `empty staged diff fails before resolving a provider`() {
        var providerResolved = false
        val service = CommitAiService(
            settingsSource = settings(),
            stagedDiffSource = StagedDiffSource { StagedDiff(" \n\t") },
            providerResolver = CommitAiProviderResolver { _ ->
                providerResolved = true
                error("provider should not be resolved")
            },
        )

        val error = assertFailsWith<CommitAiException> {
            runBlocking { service.generate(createTempDirectory("commit-ai-empty")) }
        }

        assertEquals(CommitAiErrorCode.NO_STAGED_CHANGES, error.code)
        assertTrue(error.message.orEmpty().contains("no staged changes", ignoreCase = true))
        assertFalse(providerResolved)
    }

    @Test
    fun `disabled setting fails before reading git`() {
        var diffRead = false
        val service = CommitAiService(
            settingsSource = settings(enabled = false),
            stagedDiffSource = StagedDiffSource {
                diffRead = true
                StagedDiff("diff")
            },
            providerResolver = CommitAiProviderResolver { _ -> error("not reached") },
        )

        val error = assertFailsWith<CommitAiException> {
            runBlocking { service.generate(createTempDirectory("commit-ai-disabled")) }
        }

        assertEquals(CommitAiErrorCode.DISABLED, error.code)
        assertFalse(diffRead)
    }

    @Test
    fun `empty provider response is explicit`() {
        val provider = RecordingProvider(ModelResponse(emptyList()))
        val service = CommitAiService(
            settingsSource = settings(),
            stagedDiffSource = StagedDiffSource { StagedDiff("diff --git a/a b/a") },
            providerResolver = CommitAiProviderResolver { _ ->
                CommitAiProviderTarget(provider, "Provider", "model", 512)
            },
        )

        val error = assertFailsWith<CommitAiException> {
            runBlocking { service.generate(createTempDirectory("commit-ai-empty-provider")) }
        }

        assertEquals(CommitAiErrorCode.EMPTY_MODEL_RESPONSE, error.code)
    }

    @Test
    fun `git source uses fixed argv and a cleaned environment`() = runBlocking {
        val root = createTempDirectory("commit-ai-git-root").toRealPath()
        val executable = Files.createTempFile("commit-ai-git", "-fake").toRealPath()
        var captured: GitProcessSpec? = null
        val source = GitStagedDiffSource(
            gitExecutableResolver = { executable },
            processExecutor = GitProcessExecutor { spec ->
                captured = spec
                GitProcessResult(0, "diff text", "", false, false)
            },
        )

        val result = source.read(root)

        assertEquals("diff text", result.text)
        val spec = assertNotNull(captured)
        assertEquals(root, spec.workingDirectory)
        assertEquals(
            listOf(
                executable.toString(),
                "--no-pager",
                "diff",
                "--cached",
                "--no-color",
                "--no-ext-diff",
                "--no-textconv",
                "--",
            ),
            spec.argv,
        )
        assertFalse(spec.argv.any { it in setOf("sh", "bash", "zsh", "cmd", "powershell") })
        assertFalse(spec.environment.keys.any { it.contains("TOKEN", true) || it.contains("SECRET", true) })
        assertFalse(spec.environment.containsKey("HOME"))
        assertFalse(spec.environment.containsKey("SSH_AUTH_SOCK"))
        assertEquals("0", spec.environment["GIT_TERMINAL_PROMPT"])
        assertEquals("1", spec.environment["GIT_CONFIG_NOSYSTEM"])
        assertEquals(15_000L, spec.timeoutMillis)
        assertEquals(120_000, spec.maxStdoutChars)
    }

    @Test
    fun `oversized staged diff is rejected`() {
        val root = createTempDirectory("commit-ai-large-diff")
        val executable = Files.createTempFile("commit-ai-git", "-fake")
        val source = GitStagedDiffSource(
            gitExecutableResolver = { executable },
            processExecutor = GitProcessExecutor {
                GitProcessResult(0, "partial", "", false, true)
            },
        )

        val error = assertFailsWith<CommitAiException> {
            runBlocking { source.read(root) }
        }

        assertEquals(CommitAiErrorCode.DIFF_TOO_LARGE, error.code)
    }

    @Test
    fun `git timeout is reported distinctly`() {
        val root = createTempDirectory("commit-ai-timeout")
        val executable = Files.createTempFile("commit-ai-git", "-fake")
        val source = GitStagedDiffSource(
            gitExecutableResolver = { executable },
            processExecutor = GitProcessExecutor {
                GitProcessResult(-1, "", "", true, false)
            },
        )

        val error = assertFailsWith<CommitAiException> {
            runBlocking { source.read(root) }
        }

        assertEquals(CommitAiErrorCode.GIT_TIMEOUT, error.code)
    }

    private fun settings(
        enabled: Boolean = true,
        includeBody: Boolean = true,
        language: String = "Auto",
        prompt: String = "Generate a commit message.",
    ): CommitAiSettingsSource = CommitAiSettingsSource {
        CommitAiSettings(enabled, includeBody, language, prompt)
    }

    private class RecordingProvider(
        private val response: ModelResponse,
    ) : ModelProvider {
        override val id: String = "recording"
        var request: ModelRequest? = null

        override suspend fun complete(
            request: ModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): ModelResponse {
            this.request = request
            return response
        }
    }
}
