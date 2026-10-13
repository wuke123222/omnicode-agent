package dev.omnicode.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OmniCodeV3MigrationTest {
    @Test
    fun `legacy credential cleanup is deferred and best effort`() {
        var queued: (() -> Unit)? = null
        var cleanupRan = false
        var reportedFailure = false

        OmniCodeV3Migration.deferLegacyCredentialCleanup(
            enqueue = { task -> queued = task },
            cleanup = {
                cleanupRan = true
                error("Password Safe is unavailable")
            },
            onFailure = { reportedFailure = it.message == "Password Safe is unavailable" },
        )

        assertFalse(cleanupRan, "cleanup must not run inline during Tool Window construction")
        assertFalse(reportedFailure, "background cleanup has not run yet")
        assertTrue(queued != null, "cleanup should be scheduled")

        val backgroundTask = queued ?: error("cleanup task was not scheduled")
        backgroundTask()

        assertTrue(cleanupRan)
        assertTrue(reportedFailure, "cleanup failure should be reported without escaping")
    }

    @Test
    fun `unavailable background executor does not block migration`() {
        var reportedFailure = false

        OmniCodeV3Migration.deferLegacyCredentialCleanup(
            enqueue = { error("application is disposing") },
            cleanup = { error("cleanup must not run") },
            onFailure = { reportedFailure = it.message == "application is disposing" },
        )

        assertTrue(reportedFailure, "scheduling failure should also be best effort")
    }
}
