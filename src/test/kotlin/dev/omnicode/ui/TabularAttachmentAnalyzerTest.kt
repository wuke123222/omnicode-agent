package dev.omnicode.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TabularAttachmentAnalyzerTest {
    @Test
    fun `csv summary infers headers numeric stats and bounded trend`() {
        val summary = assertNotNull(analyzeTabularText(
            "time,value,label\n1,10,ok\n2,20,ok\n3,15,hold\n",
            ',',
        ))

        assertEquals(3, summary.dataRows)
        assertEquals(3, summary.columns)
        assertEquals("value", summary.columnSummaries[1].name)
        assertEquals(3, summary.columnSummaries[1].numericRows)
        assertEquals(10.0, summary.columnSummaries[1].minimum)
        assertEquals(20.0, summary.columnSummaries[1].maximum)
        assertEquals(listOf(10.0, 20.0, 15.0), summary.columnSummaries[1].samples)
        assertEquals(listOf("time", "value"), summary.chartColumns.map { it.name })
        assertTrue(summary.render().contains("趋势"))
    }

    @Test
    fun `quoted delimiters and multiline cells do not create false columns`() {
        val summary = assertNotNull(analyzeTabularText(
            "name\tnote\nA\t\"hello\tworld\"\nB\t\"line 1\nline 2\"\n",
            '\t',
        ))

        assertEquals(2, summary.dataRows)
        assertEquals(2, summary.columns)
        assertEquals(2, summary.columnSummaries[1].nonBlankRows)
    }

    @Test
    fun `large input is capped without unbounded rows`() {
        val summary = assertNotNull(analyzeTabularText(
            buildString {
                appendLine("x,y")
                repeat(900) { append(it).append(',').append(it * 2).appendLine() }
            },
            ',',
        ))

        assertTrue(summary.dataRows <= 500)
        assertTrue(summary.truncated)
    }
}
