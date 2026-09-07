package dev.omnicode.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalCliSessionStateServiceTest {
    @Test
    fun `native session ids are bounded persisted and cleared per conversation`() {
        val service = LocalCliSessionStateService()
        val context = service.context("conversation-1", "opencode")
        assertNull(context.resumeSessionId)

        context.onSessionStarted("ses_safe-123")
        assertEquals("ses_safe-123", service.context("conversation-1", "opencode").resumeSessionId)

        service.clearConversation("conversation-1")
        assertNull(service.context("conversation-1", "opencode").resumeSessionId)
    }

    @Test
    fun `invalid persisted native identities are discarded`() {
        val service = LocalCliSessionStateService()
        service.loadState(
            LocalCliSessionStateService.StoredState(
                linkedMapOf(
                    "conversation-1:opencode" to "../../unsafe",
                    "conversation-2:opencode" to "ses_valid",
                ),
            ),
        )

        assertNull(service.context("conversation-1", "opencode").resumeSessionId)
        assertEquals("ses_valid", service.context("conversation-2", "opencode").resumeSessionId)
    }

    @Test
    fun `OpenCode resume argument remains a separate argv item`() {
        val base = CliTool.OPENCODE.buildArgs("hello", "opencode/model")
        val resumed = openCodeArgsWithSession(base, "ses_123")

        assertEquals("hello", resumed.last())
        assertEquals(listOf("--session", "ses_123"), resumed.takeLast(3).take(2))
        assertFailsWith<IllegalArgumentException> { openCodeArgsWithSession(base, "../../bad") }
    }

    @Test
    fun `imported native session can be bound and resumed`() {
        val service = LocalCliSessionStateService()
        assertTrue(service.bind("conversation-1", "opencode", "ses_abc123"))
        assertEquals("ses_abc123", service.context("conversation-1", "opencode").resumeSessionId)
    }

    @Test
    fun `invalid imported session is rejected`() {
        val service = LocalCliSessionStateService()
        assertFalse(service.bind("conversation-1", "opencode", "bad/session"))
        assertEquals(null, service.context("conversation-1", "opencode").resumeSessionId)
    }
}
