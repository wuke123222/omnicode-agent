package dev.omnicode.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplyPatchToolTest {
    @Test
    fun `applies ordered unique replacements`() {
        val result = applyExactReplacements(
            "alpha\nbeta\ngamma\n",
            listOf(
                ExactReplacement("alpha\nbeta", "alpha\nBETA"),
                ExactReplacement("gamma", "delta"),
            ),
        )

        assertEquals("alpha\nBETA\ndelta\n", result)
    }

    @Test
    fun `supports deletion`() {
        assertEquals(
            "before\nafter\n",
            applyExactReplacements(
                "before\nremove me\nafter\n",
                listOf(ExactReplacement("remove me\n", "")),
            ),
        )
    }

    @Test
    fun `fails when context is missing`() {
        val error = assertFailsWith<IllegalArgumentException> {
            applyExactReplacements("current", listOf(ExactReplacement("stale", "new")))
        }

        assertTrue(error.message.orEmpty().startsWith("PATCH_CONTEXT_NOT_FOUND"))
    }

    @Test
    fun `fails when context is ambiguous`() {
        val error = assertFailsWith<IllegalArgumentException> {
            applyExactReplacements("same\nsame\n", listOf(ExactReplacement("same", "changed")))
        }

        assertTrue(error.message.orEmpty().startsWith("PATCH_AMBIGUOUS"))
    }

    @Test
    fun `detects overlapping ambiguous context`() {
        val error = assertFailsWith<IllegalArgumentException> {
            applyExactReplacements("aaa", listOf(ExactReplacement("aa", "b")))
        }

        assertTrue(error.message.orEmpty().startsWith("PATCH_AMBIGUOUS"))
    }

    @Test
    fun `fails closed when a later replacement no longer matches`() {
        val error = assertFailsWith<IllegalArgumentException> {
            applyExactReplacements(
                "one two",
                listOf(
                    ExactReplacement("one", "ONE"),
                    ExactReplacement("one two", "done"),
                ),
            )
        }

        assertTrue(error.message.orEmpty().startsWith("PATCH_CONTEXT_NOT_FOUND"))
    }
}
