package dev.omnicode.provider

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliToolDiscoveryTest {
    @Test
    fun `runtime path keeps the inherited order and adds the selected CLI directory once`() {
        val separator = File.pathSeparator
        val executable = File("/tmp/omnicode-cli/opencode")

        val entries = CliToolDiscovery.runtimePath(
            "/first${separator}/second${separator}/first",
            executable,
        ).split(separator)

        assertEquals(listOf("/first", "/second", executable.parent), entries.take(3))
    }

    @Test
    fun `runtime path preserves a case-insensitive Windows path key`() {
        val environment = linkedMapOf("Path" to "/existing")

        CliToolDiscovery.applyRuntimePath(environment, File("/tmp/omnicode-cli/pi"))

        assertTrue("PATH" !in environment)
        assertTrue(environment.getValue("Path").split(File.pathSeparator).contains("/tmp/omnicode-cli"))
    }
}
