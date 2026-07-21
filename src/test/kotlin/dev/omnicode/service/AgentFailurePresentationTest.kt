package dev.omnicode.service

import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.provider.ProviderException
import dev.omnicode.tool.SandboxUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFailurePresentationTest {
    @Test
    fun `authentication failures direct users to provider settings without leaking secrets`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("invalid sk-secret", statusCode = 401, responseBody = "sk-secret"),
        )

        assertEquals(AgentFailureKind.AUTHENTICATION, failure.kind)
        assertEquals(AgentRecoveryAction.CONFIGURE_PROVIDER, failure.recoveryAction)
        assertFalse(failure.transcriptText().contains("sk-secret"))
    }

    @Test
    fun `connect timeout is distinct from an ordinary network failure`() {
        val timeout = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("HTTP connect timed out", networkFailure = true),
        )
        val reset = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("connection reset", networkFailure = true),
        )

        assertEquals(AgentFailureKind.NETWORK_TIMEOUT, timeout.kind)
        assertEquals(AgentFailureKind.NETWORK, reset.kind)
        assertTrue(timeout.detail.contains("代理"))
    }

    @Test
    fun `rate limits preserve the submission instead of suggesting provider configuration`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("too many requests", statusCode = 429, retryAfterMillis = 1_000),
        )

        assertEquals(AgentFailureKind.RATE_LIMIT, failure.kind)
        assertEquals(AgentRecoveryAction.RESTORE_AND_RETRY, failure.recoveryAction)
    }

    @Test
    fun `model capability errors direct users to the model selector`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("model does not support tool calls", statusCode = 400),
        )

        assertEquals(AgentFailureKind.MODEL_CAPABILITY, failure.kind)
        assertEquals(AgentRecoveryAction.SWITCH_MODEL, failure.recoveryAction)
    }

    @Test
    fun `budget and sandbox failures have dedicated recovery destinations`() {
        val budget = classifyAgentFailure(AgentRunStatus.BUDGET_EXHAUSTED, null)
        val sandbox = classifyAgentFailure(
            AgentRunStatus.FAILED,
            SandboxUnavailableException("WORKSPACE_WRITE unavailable"),
        )

        assertEquals(AgentRecoveryAction.ADJUST_BUDGET, budget.recoveryAction)
        assertEquals(AgentRecoveryAction.OPEN_SANDBOX, sandbox.recoveryAction)
    }
}
