package dev.omnicode.ui

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface NotebookExtractionResult {
    data class Accepted(
        val text: String,
        val includedCells: Int,
        val truncated: Boolean,
    ) : NotebookExtractionResult

    data class Rejected(val message: String) : NotebookExtractionResult
}

/** Extracts only Markdown/code cell sources. Outputs, attachments and metadata are streamed past. */
internal fun extractJupyterNotebook(bytes: ByteArray): NotebookExtractionResult {
    if (bytes.size.toLong() > MAX_NOTEBOOK_BYTES) {
        return NotebookExtractionResult.Rejected(
            "Jupyter Notebook 过大，最大 ${attachmentDisplaySize(MAX_NOTEBOOK_BYTES)}。",
        )
    }
    val decoded = decodeNotebookUtf8(bytes)
        ?: return NotebookExtractionResult.Rejected("Jupyter Notebook 不是有效的 UTF-8 JSON。")
    return try {
        JsonReader(StringReader(decoded)).use { reader ->
            reader.strictness = Strictness.STRICT
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                return NotebookExtractionResult.Rejected("Jupyter Notebook 顶层必须是 JSON 对象。")
            }
            val output = StringBuilder()
            var totalCells = 0
            var includedCells = 0
            var truncated = false
            var foundCells = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "cells" -> {
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                            reader.skipValue()
                            continue
                        }
                        foundCells = true
                        reader.beginArray()
                        while (reader.hasNext()) {
                            totalCells++
                            if (totalCells > MAX_NOTEBOOK_CELLS || output.length >= MAX_NOTEBOOK_EXTRACTED_CHARS) {
                                truncated = true
                                reader.skipValue()
                                continue
                            }
                            val cell = readNotebookCell(reader)
                                ?: return NotebookExtractionResult.Rejected(
                                    "Jupyter Notebook 的第 $totalCells 个 cell 格式无效。",
                                )
                            if (cell.truncated) truncated = true
                            if (cell.type !in NOTEBOOK_INCLUDED_CELL_TYPES || cell.source.isBlank()) continue
                            if (!isSafeTextAttachment(cell.source)) {
                                return NotebookExtractionResult.Rejected(
                                    "Jupyter Notebook 的第 $totalCells 个 cell 包含 NUL 或过多控制字符。",
                                )
                            }
                            val label = if (cell.type == "markdown") "Markdown" else "Code"
                            val section = buildString {
                                if (output.isNotEmpty()) append("\n\n")
                                append("[Notebook cell ").append(totalCells).append(" · ").append(label).append("]\n")
                                append(cell.source.trimEnd())
                            }
                            val remaining = MAX_NOTEBOOK_EXTRACTED_CHARS - output.length
                            if (section.length > remaining) {
                                output.append(section, 0, remaining)
                                truncated = true
                            } else {
                                output.append(section)
                            }
                            includedCells++
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return NotebookExtractionResult.Rejected("Jupyter Notebook JSON 后存在额外内容。")
            }
            if (!foundCells) return NotebookExtractionResult.Rejected("Jupyter Notebook 缺少 cells 数组。")
            if (includedCells == 0) {
                return NotebookExtractionResult.Rejected("Jupyter Notebook 中没有可读取的 Markdown 或代码 cell。")
            }
            val marker = "\n\n[Notebook 内容已按安全上限截断]"
            if (truncated) {
                if (output.length + marker.length > MAX_NOTEBOOK_EXTRACTED_CHARS) {
                    output.setLength((MAX_NOTEBOOK_EXTRACTED_CHARS - marker.length).coerceAtLeast(0))
                }
                output.append(marker)
            }
            NotebookExtractionResult.Accepted(output.toString(), includedCells, truncated)
        }
    } catch (_: Exception) {
        NotebookExtractionResult.Rejected("Jupyter Notebook JSON 格式无效或结构过于复杂。")
    }
}

private data class NotebookCell(
    val type: String,
    val source: String,
    val truncated: Boolean,
)

private fun readNotebookCell(reader: JsonReader): NotebookCell? {
    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        reader.skipValue()
        return null
    }
    var type = ""
    var source = NotebookSource("", false)
    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "cell_type" -> {
                type = if (reader.peek() == JsonToken.STRING) reader.nextString().lowercase() else {
                    reader.skipValue()
                    ""
                }
            }
            "source" -> source = readNotebookSource(reader)
            // In particular, outputs and Markdown attachments may contain very large Base64 values.
            // JsonReader.skipValue streams across them without materializing a JsonElement tree.
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    return NotebookCell(type, source.text, source.truncated)
}

private data class NotebookSource(val text: String, val truncated: Boolean)

private fun readNotebookSource(reader: JsonReader): NotebookSource {
    val output = StringBuilder()
    var truncated = false

    fun append(value: String) {
        val remaining = MAX_NOTEBOOK_CELL_CHARS - output.length
        if (remaining <= 0) {
            truncated = true
        } else if (value.length > remaining) {
            output.append(value, 0, remaining)
            truncated = true
        } else {
            output.append(value)
        }
    }

    when (reader.peek()) {
        JsonToken.STRING -> append(reader.nextString())
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.STRING) append(reader.nextString()) else reader.skipValue()
            }
            reader.endArray()
        }
        JsonToken.NULL -> reader.nextNull()
        else -> reader.skipValue()
    }
    return NotebookSource(output.toString(), truncated)
}

private fun decodeNotebookUtf8(bytes: ByteArray): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

internal const val MAX_NOTEBOOK_BYTES: Long = 2L * 1_024 * 1_024
internal const val MAX_NOTEBOOK_EXTRACTED_CHARS: Int = 48_000
internal const val MAX_NOTEBOOK_CELL_CHARS: Int = 12_000
internal const val MAX_NOTEBOOK_CELLS: Int = 200
private val NOTEBOOK_INCLUDED_CELL_TYPES = setOf("markdown", "code")
