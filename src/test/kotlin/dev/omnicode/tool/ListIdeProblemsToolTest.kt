package dev.omnicode.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListIdeProblemsToolTest {
    @Test
    fun `renders syntax and analysis problem files without source content`() {
        val rendered = renderIdeProblemFiles(
            listOf(
                IdeProblemFile("src/App.kt", syntaxErrors = true),
                IdeProblemFile("src/Auth.kt", syntaxErrors = false),
            ),
        )

        assertTrue(rendered.contains("src/App.kt [syntax errors]"))
        assertTrue(rendered.contains("src/Auth.kt [analysis problems]"))
    }

    @Test
    fun `renders a useful empty state`() {
        assertEquals(
            "The IDE has not reported any problem files in the current project.",
            renderIdeProblemFiles(emptyList()),
        )
    }
}
