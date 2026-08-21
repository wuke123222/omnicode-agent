package dev.omnicode.service

import dev.omnicode.agent.AgentRunStatus
import dev.omnicode.agent.ContextBudgetExceededException
import dev.omnicode.agent.ProviderOutputLimitReachedException
import dev.omnicode.agent.UserMessageTooLargeException
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
        assertEquals(AgentRecoveryAction.RUN_DIAGNOSTICS, timeout.recoveryAction)
        assertEquals("运行连接诊断", timeout.recoveryLabel)
        assertEquals(AgentFailureKind.NETWORK, reset.kind)
        assertTrue(timeout.detail.contains("代理"))
    }

    @Test
    fun `TLS handshake failures direct users to connection diagnostics`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException(
                "Model API network request failed: TLS handshake failed: Remote host terminated the handshake",
                networkFailure = true,
            ),
        )

        assertEquals(AgentFailureKind.NETWORK, failure.kind)
        assertEquals("TLS 握手失败", failure.title)
        assertEquals(AgentRecoveryAction.RUN_DIAGNOSTICS, failure.recoveryAction)
        assertTrue(failure.detail.contains("证书"))
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

    @Test
    fun `context and provider output boundaries offer targeted recovery`() {
        val context = classifyAgentFailure(
            AgentRunStatus.BUDGET_EXHAUSTED,
            ContextBudgetExceededException("too large"),
        )
        val output = classifyAgentFailure(
            AgentRunStatus.BUDGET_EXHAUSTED,
            ProviderOutputLimitReachedException(),
        )

        assertEquals(AgentFailureKind.MODEL_CAPABILITY, context.kind)
        assertEquals(AgentRecoveryAction.EDIT_AND_RETRY, context.recoveryAction)
        assertTrue(context.detail.contains("上下文"))
        assertEquals(AgentFailureKind.MODEL_CAPABILITY, output.kind)
        assertEquals(AgentRecoveryAction.CONFIGURE_PROVIDER, output.recoveryAction)
        assertTrue(output.detail.contains("单次模型响应上限"))
    }

    @Test
    fun `oversized submission returns to the composer instead of runtime controls`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.BUDGET_EXHAUSTED,
            UserMessageTooLargeException(64_000),
        )

        assertEquals(AgentRecoveryAction.EDIT_AND_RETRY, failure.recoveryAction)
        assertTrue(failure.title.contains("输入"))
        assertTrue(failure.detail.contains("运行时长"))
    }

    @Test
    fun `locally authored provider failures keep their actionable message`() {
        val cli = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("OpenCode CLI 退出码 1，未产生输出。\nCLI 错误输出（截断）：\nUnknown argument"),
        )
        val vision = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("当前模型可能不支持图片。请在供应商设置的“视觉辅助模型”中选择一个可识图模型，或切换主模型。"),
        )

        assertEquals(AgentRecoveryAction.EDIT_AND_RETRY, cli.recoveryAction)
        assertTrue(cli.detail.contains("退出码 1"))
        assertTrue(cli.detail.contains("Unknown argument"))
        assertTrue(vision.detail.contains("视觉辅助模型"))
    }

    @Test
    fun `status-carrying provider messages are still never copied into the transcript`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException("server exploded with sk-leaked-secret", statusCode = 500),
        )

        assertFalse(failure.transcriptText().contains("sk-leaked-secret"))
    }

    @Test
    fun `unknown non-provider failures reveal the exception class but not its message`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            IllegalStateException("secret local path /home/user"),
        )

        assertEquals(AgentFailureKind.UNKNOWN, failure.kind)
        assertTrue(failure.detail.contains("IllegalStateException"))
        assertFalse(failure.detail.contains("/home/user"))
    }

    @Test
    fun `CLI watchdog timeout is classified as a network timeout`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            ProviderException(
                "OpenCode CLI 超过 600 秒未完成请求，已终止 CLI 进程。可在供应商设置中调大请求超时。",
                networkFailure = true,
            ),
        )

        assertEquals(AgentFailureKind.NETWORK_TIMEOUT, failure.kind)
    }

    @Test
    fun `unpriced cost boundary opens the pricing page`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            PricingUnavailableException("raw model pricing detail"),
        )

        assertEquals(AgentFailureKind.CONFIGURATION, failure.kind)
        assertEquals(AgentRecoveryAction.CONFIGURE_PRICING, failure.recoveryAction)
        assertTrue(failure.detail.contains("价格配置"))
    }

    @Test
    fun `untrusted historical cost opens runtime budget settings`() {
        val failure = classifyAgentFailure(
            AgentRunStatus.FAILED,
            CostBaselineUnavailableException("legacy checkpoint"),
        )

        assertEquals(AgentFailureKind.BUDGET, failure.kind)
        assertEquals(AgentRecoveryAction.ADJUST_BUDGET, failure.recoveryAction)
        assertTrue(failure.detail.contains("旧检查点"))
    }
}
