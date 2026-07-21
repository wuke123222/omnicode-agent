package dev.omnicode.service

import com.google.gson.JsonParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionDiagnosticsExporterTest {
    @Test
    fun `markdown and JSON exports remove credentials authorization paths and environment values`() {
        val token = "sk-proj-0123456789abcdefghijklmnop"
        val report = ConnectionDiagnosticsReport(
            generatedAt = Instant.parse("2026-07-21T00:00:00Z"),
            durationMillis = 12,
            checks = listOf(
                ConnectionDiagnosticCheck(
                    id = "network.test",
                    category = ConnectionDiagnosticCategory.NETWORK,
                    title = "Injected unsafe evidence",
                    status = ConnectionDiagnosticStatus.FAIL,
                    summary = """
                        provider echoed $token
                        Authorization: Bearer opaque-token-0123456789
                        workspace /Users/alice/private/project/file.kt
                        PRIVATE_VALUE=do-not-export-this-environment-value
                    """.trimIndent(),
                    durationMillis = 7,
                    recoverySuggestion = "See file:///Users/alice/private/log.txt?api_key=$token",
                ),
            ),
        )

        val export = ConnectionDiagnosticsExporter(userHome = "/Users/alice").export(report)
        listOf(export.markdown, export.json).forEach { content ->
            assertFalse(content.contains(token))
            assertFalse(content.contains("opaque-token-0123456789"))
            assertFalse(Regex("(?i)Authorization\\s*:").containsMatchIn(content))
            assertFalse(content.contains("/Users/alice"))
            assertFalse(content.contains("do-not-export-this-environment-value"))
            assertTrue(content.contains("[USER_HOME]"))
        }
        assertTrue(JsonParser.parseString(export.json).isJsonObject, "redaction must preserve valid JSON")
    }

    @Test
    fun `report overall status and JSON counts use the most severe result`() {
        val report = ConnectionDiagnosticsReport(
            generatedAt = Instant.EPOCH,
            durationMillis = 3,
            checks = listOf(
                check("provider.pass", ConnectionDiagnosticStatus.PASS),
                check("network.warn", ConnectionDiagnosticStatus.WARN),
                check("mcp.skip", ConnectionDiagnosticStatus.SKIP),
            ),
        )

        assertEquals(ConnectionDiagnosticStatus.WARN, report.overallStatus)
        val json = JsonParser.parseString(ConnectionDiagnosticsExporter(userHome = null).toJson(report)).asJsonObject
        assertEquals("WARN", json.get("overallStatus").asString)
        assertEquals(1, json.getAsJsonObject("counts").get("PASS").asInt)
        assertEquals(1, json.getAsJsonObject("counts").get("WARN").asInt)
        assertEquals(1, json.getAsJsonObject("counts").get("SKIP").asInt)
        assertEquals(0, json.getAsJsonObject("counts").get("FAIL").asInt)
    }

    private fun check(id: String, status: ConnectionDiagnosticStatus) = ConnectionDiagnosticCheck(
        id = id,
        category = ConnectionDiagnosticCategory.PROVIDER,
        title = id,
        status = status,
        summary = "safe",
        durationMillis = 1,
    )
}
