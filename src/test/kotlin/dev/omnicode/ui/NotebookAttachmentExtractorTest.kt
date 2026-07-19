package dev.omnicode.ui

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotebookAttachmentExtractorTest {
    @Test
    fun `extracts numbered markdown and code cells without outputs attachments or metadata`() {
        val hiddenBase64 = "A".repeat(100_000)
        val notebook = """
            {
              "cells": [
                {
                  "cell_type": "markdown",
                  "source": ["# Experiment\n", "Explain the result."],
                  "attachments": {"plot.png": {"image/png": "$hiddenBase64"}}
                },
                {
                  "cell_type": "code",
                  "source": "print('safe source')\n",
                  "outputs": [{"data": {"image/png": "$hiddenBase64", "text/plain": "hidden output"}}]
                },
                {"cell_type": "raw", "source": "hidden raw cell"}
              ],
              "metadata": {"secret": "$hiddenBase64"},
              "nbformat": 4
            }
        """.trimIndent()

        val result = assertIs<NotebookExtractionResult.Accepted>(
            extractJupyterNotebook(notebook.toByteArray(StandardCharsets.UTF_8)),
        )

        assertTrue(result.text.contains("[Notebook cell 1 · Markdown]"))
        assertTrue(result.text.contains("# Experiment"))
        assertTrue(result.text.contains("[Notebook cell 2 · Code]"))
        assertTrue(result.text.contains("print('safe source')"))
        assertTrue(!result.text.contains("hidden output"))
        assertTrue(!result.text.contains("hidden raw cell"))
        assertTrue(!result.text.contains(hiddenBase64.take(100)))
        assertTrue(result.text.length < 1_000)
    }

    @Test
    fun `cell and notebook output are truncated with an explicit marker`() {
        val notebook = """
            {"cells":[{"cell_type":"code","source":"${"x".repeat(MAX_NOTEBOOK_CELL_CHARS + 500)}"}]}
        """.trimIndent()

        val result = assertIs<NotebookExtractionResult.Accepted>(
            extractJupyterNotebook(notebook.encodeToByteArray()),
        )

        assertTrue(result.truncated)
        assertTrue(result.text.contains("Notebook 内容已按安全上限截断"))
        assertTrue(result.text.length <= MAX_NOTEBOOK_EXTRACTED_CHARS)
    }

    @Test
    fun `rejects malformed non UTF8 and source control characters`() {
        assertIs<NotebookExtractionResult.Rejected>(extractJupyterNotebook("{".encodeToByteArray()))
        assertIs<NotebookExtractionResult.Rejected>(
            extractJupyterNotebook(byteArrayOf(0xc3.toByte(), 0x28)),
        )
        val controls = """{"cells":[{"cell_type":"markdown","source":"ok\u0000hidden"}]}"""
        val rejected = assertIs<NotebookExtractionResult.Rejected>(
            extractJupyterNotebook(controls.encodeToByteArray()),
        )
        assertTrue(rejected.message.contains("控制字符"))
    }
}
