package dev.omnicode.ui

import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownFileReferenceTest {
    @Test
    fun `recognizes space colon and github line forms as project relative references`() {
        val source = "检查 src/pages/Dashboard.vue 148–169、src/api/client.ts:27、`docs/guide.md#L8—L12` 和 test\\AppTest.kt#L6。"

        assertEquals(
            listOf(
                ToolFileReference("src/pages/Dashboard.vue", 148, 169),
                ToolFileReference("src/api/client.ts", 27),
                ToolFileReference("docs/guide.md", 8, 12),
                ToolFileReference("test/AppTest.kt", 6),
            ),
            projectFileReferenceSpans(source).map(ToolFileReferenceSpan::reference),
        )
    }

    @Test
    fun `rejects external traversal malformed and prose lookalike references`() {
        val source = listOf(
            "/tmp/outside.kt:9",
            "../outside.kt:10",
            "https://example.com/App.kt#L11",
            "chapter:12",
            "src/App.kt:20-10",
            "C:\\outside\\App.kt:13",
            "src/App.kt:0",
        ).joinToString(" ")

        assertTrue(projectFileReferenceSpans(source).isEmpty())
    }

    @Test
    fun `rendered reference is styled and activates the existing file callback`() {
        SwingUtilities.invokeAndWait {
            val opened = mutableListOf<ToolFileReference>()
            val pane = LightweightMarkdownPane(opened::add)
            pane.setRawText("已修复 **src/pages/Dashboard.vue 148-169**，请审阅。")
            pane.finalizeMarkdown()
            val offset = pane.text.indexOf("src/pages/Dashboard.vue")

            assertEquals(ToolFileReference("src/pages/Dashboard.vue", 148, 169), pane.fileReferenceAt(offset))
            assertTrue(StyleConstants.isUnderline(pane.styledDocument.getCharacterElement(offset).attributes))
            assertTrue(pane.activateFileReferenceAt(offset))
            assertEquals(listOf(ToolFileReference("src/pages/Dashboard.vue", 148, 169)), opened)
            pane.caretPosition = offset
            assertTrue(pane.activateFileReferenceAtCaret())
            assertEquals(
                listOf(
                    ToolFileReference("src/pages/Dashboard.vue", 148, 169),
                    ToolFileReference("src/pages/Dashboard.vue", 148, 169),
                ),
                opened,
            )
            assertFalse(pane.activateFileReferenceAt(pane.text.indexOf("已修复")))
            assertNull(pane.fileReferenceAt(-1))
        }
    }

    @Test
    fun `fenced source code does not turn incidental line syntax into navigation`() {
        SwingUtilities.invokeAndWait {
            val pane = LightweightMarkdownPane()
            pane.setRawText("```text\nsrc/App.kt:12\n```\n真实引用 src/App.kt#L21")
            pane.finalizeMarkdown()
            val first = pane.text.indexOf("src/App.kt")
            val second = pane.text.lastIndexOf("src/App.kt")

            assertNull(pane.fileReferenceAt(first))
            assertEquals(ToolFileReference("src/App.kt", 21), pane.fileReferenceAt(second))
        }
    }
}
