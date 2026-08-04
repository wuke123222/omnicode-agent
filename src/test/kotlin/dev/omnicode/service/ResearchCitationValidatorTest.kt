package dev.omnicode.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchCitationValidatorTest {
    @Test
    fun `validates entries and reports duplicate keys and dois offline`() {
        val report = ResearchCitationValidator.validate(
            """
            @article{Smith2024,
              title = {A result},
              doi = {10.1234/ABC-1}
            }
            @inproceedings{smith2024,
              title = {Same key},
              doi = {10.1234/abc-1}
            }
            """.trimIndent(),
        )

        assertEquals(2, report.entries.size)
        assertEquals(listOf("smith2024"), report.duplicateKeys)
        assertEquals(listOf("10.1234/abc-1"), report.duplicateDois)
        assertFalse(report.networkChecked)
        assertFalse(report.isValid)
    }

    @Test
    fun `marks malformed doi and oversized source without network claims`() {
        val report = ResearchCitationValidator.validate(
            "@article{demo, doi={not-a-doi}}" + "x".repeat(ResearchCitationValidator.MAX_SOURCE_CHARS),
        )

        assertTrue(report.truncated)
        assertTrue(report.issues.any { it.code == CitationValidationIssue.Code.MALFORMED_DOI })
        assertTrue(report.issues.any { it.code == CitationValidationIssue.Code.SOURCE_TRUNCATED })
        assertFalse(report.networkChecked)
    }

    @Test
    fun `empty source is actionable`() {
        val report = ResearchCitationValidator.validate(" \n ")

        assertTrue(report.entries.isEmpty())
        assertEquals(listOf(CitationValidationIssue.Code.EMPTY_SOURCE), report.issues.map { it.code })
    }
}
