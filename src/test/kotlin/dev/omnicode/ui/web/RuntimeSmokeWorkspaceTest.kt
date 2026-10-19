package dev.omnicode.ui.web

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeSmokeWorkspaceTest {
    @Test
    fun `cleanup removes only its own temporary directory and never follows links`() {
        val outside = Files.createTempFile("omnicode-smoke-outside-", ".txt")
        val workspace = RuntimeSmokeWorkspace.create()
        try {
            Files.writeString(outside, "keep")
            Files.writeString(workspace.resolve("inside.txt"), "remove")
            val linkCreated = runCatching { Files.createSymbolicLink(workspace.resolve("outside-link"), outside) }.isSuccess
            RuntimeSmokeWorkspace.cleanup(workspace)
            assertFalse(Files.exists(workspace))
            assertTrue(Files.exists(outside))
            assertTrue(Files.readString(outside) == "keep")
            if (linkCreated) assertFalse(Files.exists(workspace.resolve("outside-link")))
        } finally {
            RuntimeSmokeWorkspace.cleanup(workspace)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `cleanup rejects a non smoke-test path`() {
        val outside = Files.createTempDirectory("omnicode-other-")
        try {
            assertFailsWith<IllegalArgumentException> { RuntimeSmokeWorkspace.cleanup(outside) }
            assertTrue(Files.exists(outside))
        } finally {
            Files.deleteIfExists(outside)
        }
    }
}
