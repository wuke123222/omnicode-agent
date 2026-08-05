package dev.omnicode.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant

class ProjectIntelligenceDossierExporterTest {
    @Test
    fun `dossier is bounded and excludes source content`() {
        val report = ProjectIntelligenceDossierExporter.markdown(
            ProjectIntelligenceDossierInput(
                projectName = "demo",
                generatedAt = Instant.parse("2026-08-05T00:00:00Z"),
                rules = ProjectRulesResult(
                    appliedRules = listOf(
                        AppliedProjectRule("AGENTS.md", "SECRET_API_KEY=should-not-be-exported", 32, 32, 32, false),
                    ),
                    combinedText = "SECRET_API_KEY=should-not-be-exported",
                    issues = emptyList(),
                    truncation = ProjectRuleTruncationStats(1, 1, 0, 0, 0, false, 32, 32, 32, 0, 0),
                ),
            ),
        )

        assertTrue(report.contains("AGENTS.md"))
        assertFalse(report.contains("SECRET_API_KEY=should-not-be-exported"))
        assertTrue(report.length <= ProjectIntelligenceDossierExporter.MAX_DOSSIER_CHARS)
    }
}
