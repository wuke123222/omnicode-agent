package dev.omnicode.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OmniCodeEditorContextActionTest {
    @Test
    fun `builds portable current file references`() {
        assertEquals("@src/main/App.kt", editorContextReference("src\\main\\App.kt", null, null))
    }

    @Test
    fun `builds one based selection references`() {
        assertEquals("@src/App.kt:L7", editorContextReference("src/App.kt", 7, 7))
        assertEquals("@src/App.kt:L7-L12", editorContextReference("src/App.kt", 7, 12))
    }

    @Test
    fun `rejects invalid selection ranges`() {
        assertFailsWith<IllegalArgumentException> {
            editorContextReference("src/App.kt", 8, 3)
        }
    }

    @Test
    fun `prefill remains editable and states its source`() {
        assertEquals(
            "请处理当前选中的代码：@src/App.kt:L2-L4\n\n需求：",
            editorContextPrompt("@src/App.kt:L2-L4", selection = true),
        )
    }

    @Test
    fun `external IDE selections are bounded redacted and marked untrusted`() {
        val prompt = externalEditorSelectionPrompt(
            "OPENAI_API_KEY=sk-abcdefghijklmnopqrstuvwxyz123456\n" + "x".repeat(20_000),
            "TerminalView",
        )

        assertTrue(prompt.contains("TerminalView"))
        assertTrue(prompt.contains("不可信数据"))
        assertTrue(prompt.contains("[REDACTED]"))
        assertTrue(prompt.length < 12_500)
    }
}
