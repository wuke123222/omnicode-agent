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
    fun `aggregate responses are bounded and omit repository URLs`() {
        val usage = parseTokenTrackerUsage(
            summaryJson = """{
                "from":"2026-08-09","to":"2026-09-07",
                "totals":{"billable_total_tokens":"5376997219","input_tokens":147709133,"output_tokens":16829835,"cached_input_tokens":5211594241,"reasoning_output_tokens":7150110,"conversation_count":39818,"total_cost_usd":"2270.828912"},
                "rolling":{"last_30d":{"active_days":25,"avg_per_active_day":215079889}}
            }""".trimIndent(),
            dailyJson = """{"data":[{"day":"2026-09-07","billable_total_tokens":2000000,"total_cost_usd":2.5}]}""",
            modelsJson = """{"sources":[{"source":"codex","models":[{"model":"gpt-5.6-sol","totals":{"billable_total_tokens":3757595283,"total_cost_usd":"2165.59"}}]}]}""",
            projectsJson = """{"entries":[{"project_key":"wuke123222/omnicode-agent","project_ref":"https://example.invalid/private.git","billable_total_tokens":"1343606401"}]}""",
            fallbackFrom = "2026-08-09",
            fallbackTo = "2026-09-07",
        )

        assertEquals(5_376_997_219L, usage.totalTokens)
        assertEquals(2_270.828912, usage.costUsd)
        assertEquals("gpt-5.6-sol", usage.models.single().model)
        assertEquals("omnicode-agent", usage.projects.single().name)
        assertTrue(usage.projects.none { "http" in it.name })
    }

    @Test
    fun `nullable aggregate fields degrade to zero without Gson cast failures`() {
        val usage = parseTokenTrackerUsage(
            summaryJson = """{"totals":{"billable_total_tokens":null,"total_cost_usd":null},"rolling":null}""",
            dailyJson = """{"data":[null,{"day":null,"billable_total_tokens":null}]}""",
            modelsJson = """{"sources":[null,{"source":null,"models":[null]}]}""",
            projectsJson = """{"entries":null}""",
            fallbackFrom = "2026-08-09",
            fallbackTo = "2026-09-07",
        )

        assertEquals(0L, usage.totalTokens)
        assertEquals(0.0, usage.costUsd)
        assertTrue(usage.daily.isEmpty())
        assertTrue(usage.models.isEmpty())
        assertTrue(usage.projects.isEmpty())
    }

    @Test
    fun `aggregate failure does not downgrade a healthy local service`() {
        val root = Files.createTempDirectory("omnicode-tokentracker-usage-error")
        try {
            val status = TokenTrackerIntegration(
                environment = emptyMap(),
                userHome = root,
                osName = "Linux",
                probeDashboard = {
                    TokenTrackerDashboardProbe(TokenTrackerDashboardState.READY, "ready")
                },
                loadUsage = { error("aggregate unavailable") },
            ).inspect()

            assertEquals(TokenTrackerDashboardState.READY, status.dashboard.state)
            assertNull(status.usage)
            assertEquals("aggregate unavailable", status.usageError)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `copied commands avoid remote scripts and disable optional telemetry on startup`() {
        assertEquals("npx tokentracker-cli", TOKEN_TRACKER_INSTALL_COMMAND)
        assertEquals(
            "TOKENTRACKER_NO_TELEMETRY=1 npx tokentracker-cli",
            tokenTrackerStartCommand("Linux"),
        )
        assertEquals(
            "\$env:TOKENTRACKER_NO_TELEMETRY='1'; npx tokentracker-cli",
            tokenTrackerStartCommand("Windows 11"),
        )
        assertTrue("curl" !in TOKEN_TRACKER_INSTALL_COMMAND)
        assertTrue("|" !in TOKEN_TRACKER_INSTALL_COMMAND)
    }
}
