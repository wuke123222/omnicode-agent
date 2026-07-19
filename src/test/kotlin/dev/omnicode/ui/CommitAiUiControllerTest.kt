package dev.omnicode.ui

import dev.omnicode.provider.ProviderException
import dev.omnicode.service.CommitAiErrorCode
import dev.omnicode.service.CommitAiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CommitAiUiControllerTest {
    @Test
    fun `disabled error directs users to platform settings`() {
        val message = friendlyError(
            CommitAiException(CommitAiErrorCode.DISABLED, "internal detail"),
        )

        assertEquals("Commit AI 尚未启用，请在 OmniCode 平台设置中开启。", message)
    }

    @Test
    fun `credential errors do not expose provider details`() {
        val message = friendlyError(
            ProviderException("Authorization failed for sk-secret", statusCode = 401),
        )

        assertEquals("当前供应商拒绝了已保存的凭据，请检查 API Key。", message)
        assertFalse(message.contains("sk-secret"))
    }

    @Test
    fun `unexpected errors do not expose internal messages`() {
        val message = friendlyError(IllegalStateException("secret internal detail"))

        assertEquals("无法生成 Commit 信息。", message)
    }
}
