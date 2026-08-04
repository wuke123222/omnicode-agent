package dev.omnicode.settings

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenTrackerIntegrationTest {
    @Test
    fun `dashboard accepts only a successful TokenTracker page`() {
        val ready = classifyTokenTrackerDashboard(
            200,
            "<html><title>TokenTracker</title></html>",
        )
        val unknown = classifyTokenTrackerDashboard(200, "<html><title>Another service</title></html>")
        val redirect = classifyTokenTrackerDashboard(302, "TokenTracker")

        assertEquals(TokenTrackerDashboardState.READY, ready.state)
        assertEquals(TokenTrackerDashboardState.UNVERIFIED_SERVICE, unknown.state)
        assertEquals(TokenTrackerDashboardState.UNVERIFIED_SERVICE, redirect.state)
    }

    @Test
    fun `CLI discovery ignores relative PATH entries and finds an executable absolute entry`() {
        val root = Files.createTempDirectory("omnicode-tokentracker")
        try {
            val bin = root.resolve("bin").createDirectories()
            val executable = bin.resolve("tokentracker")
            Files.writeString(executable, "#!/bin/sh\n")
            assertTrue(executable.toFile().setExecutable(true))

            val found = findTokenTrackerExecutable(
                environment = mapOf("PATH" to ".${java.io.File.pathSeparator}$bin"),
                userHome = root.resolve("home"),
                osName = "Mac OS X",
            )

            assertEquals(executable, found)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CLI discovery does not treat a directory as an installed executable`() {
        val root = Files.createTempDirectory("omnicode-tokentracker-dir")
        try {
            root.resolve("bin/tokentracker").createDirectories()

            val found = findTokenTrackerExecutable(
                environment = mapOf("PATH" to root.resolve("bin").toString()),
                userHome = root.resolve("home"),
                osName = "Linux",
            )

            assertNull(found)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `inspection combines read-only CLI discovery with injected local probe`() {
        val root = Files.createTempDirectory("omnicode-tokentracker-inspect")
        try {
            val integration = TokenTrackerIntegration(
                environment = mapOf("PATH" to root.toString()),
                userHome = root.resolve("home"),
                osName = "Linux",
                probeDashboard = {
                    TokenTrackerDashboardProbe(
                        TokenTrackerDashboardState.NOT_RUNNING,
                        "not running",
                    )
                },
            )

            val status = integration.inspect()

            assertNull(status.cliExecutable)
            assertEquals(TokenTrackerDashboardState.NOT_RUNNING, status.dashboard.state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `copied commands avoid remote scripts and disable optional telemetry on startup`() {
        assertEquals("npm install --global tokentracker-cli", TOKEN_TRACKER_INSTALL_COMMAND)
        assertEquals(
            "TOKENTRACKER_NO_TELEMETRY=1 tokentracker",
            tokenTrackerStartCommand("Linux"),
        )
        assertEquals(
            "\$env:TOKENTRACKER_NO_TELEMETRY='1'; tokentracker",
            tokenTrackerStartCommand("Windows 11"),
        )
        assertTrue("curl" !in TOKEN_TRACKER_INSTALL_COMMAND)
        assertTrue("|" !in TOKEN_TRACKER_INSTALL_COMMAND)
    }
}
