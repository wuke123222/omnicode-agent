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
        /** Optional, bounded plain-text output preview; binary/display data is never imported. */
        val outputPreview: List<NotebookOutputPreview> = emptyList(),
    ) : NotebookExtractionResult

    data class Rejected(val message: String) : NotebookExtractionResult
}

internal data class NotebookOutputPreview(
    val cellIndex: Int,
    val text: String,
    val truncated: Boolean,
)

/** Extracts only Markdown/code cell sources. Outputs, attachments and metadata are streamed past. */
internal fun extractJupyterNotebook(
    bytes: ByteArray,
    includeOutputPreview: Boolean = false,
): NotebookExtractionResult {
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
            val outputPreview = mutableListOf<NotebookOutputPreview>()
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
                            val cell = readNotebookCell(reader, includeOutputPreview)
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
                            if (includeOutputPreview && cell.outputPreview != null && outputPreview.size < MAX_NOTEBOOK_OUTPUT_PREVIEWS) {
                                outputPreview += NotebookOutputPreview(totalCells, cell.outputPreview.text, cell.outputPreview.truncated)
                            }
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
            NotebookExtractionResult.Accepted(output.toString(), includedCells, truncated, outputPreview.toList())
        }
    } catch (_: Exception) {
        NotebookExtractionResult.Rejected("Jupyter Notebook JSON 格式无效或结构过于复杂。")
    }
}

private data class NotebookCell(
    val type: String,
    val source: String,
    val truncated: Boolean,
    val outputPreview: NotebookOutputText?,
)

private fun readNotebookCell(reader: JsonReader, includeOutputPreview: Boolean): NotebookCell? {
    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        reader.skipValue()
        return null
    }
    var type = ""
    var source = NotebookSource("", false)
    var outputPreview: NotebookOutputText? = null
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
            "outputs" -> if (includeOutputPreview) outputPreview = readNotebookOutputs(reader) else reader.skipValue()
            // In particular, outputs and Markdown attachments may contain very large Base64 values.
            // JsonReader.skipValue streams across them without materializing a JsonElement tree.
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    return NotebookCell(type, source.text, source.truncated, outputPreview)
}

private data class NotebookOutputText(val text: String, val truncated: Boolean)

private fun readNotebookOutputs(reader: JsonReader): NotebookOutputText? {
    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
        reader.skipValue()
        return null
    }
    val output = StringBuilder()
    var truncated = false
    var count = 0
    reader.beginArray()
    while (reader.hasNext()) {
        count++
        if (count > MAX_NOTEBOOK_OUTPUT_ITEMS) {
            truncated = true
            reader.skipValue()
            continue
        }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            continue
        }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "text" -> appendNotebookOutput(reader, output) { truncated = true }
                "data" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (key == "text/plain" || key == "text/html") appendNotebookOutput(reader, output) { truncated = true }
                        else reader.skipValue()
                    }
                    reader.endObject()
                } else reader.skipValue()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }
    reader.endArray()
    return output.takeIf { it.isNotBlank() }?.let { NotebookOutputText(it.toString(), truncated) }
}

private fun appendNotebookOutput(reader: JsonReader, output: StringBuilder, markTruncated: () -> Unit) {
    val value = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.BEGIN_ARRAY -> {
            val joined = StringBuilder()
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.STRING) joined.append(reader.nextString()) else reader.skipValue()
            }
            reader.endArray()
            joined.toString()
        }
        else -> { reader.skipValue(); return }
    }
    if (!isSafeTextAttachment(value)) return
    val remaining = MAX_NOTEBOOK_OUTPUT_CHARS - output.length
    if (remaining <= 0) {
        markTruncated()
    } else if (value.length > remaining) {
        output.append(value, 0, remaining)
        markTruncated()
    } else {
        if (output.isNotEmpty()) output.append('\n')
        output.append(value)
    }
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
internal const val MAX_NOTEBOOK_OUTPUT_CHARS: Int = 8_000
internal const val MAX_NOTEBOOK_OUTPUT_ITEMS: Int = 16
internal const val MAX_NOTEBOOK_OUTPUT_PREVIEWS: Int = 32
private val NOTEBOOK_INCLUDED_CELL_TYPES = setOf("markdown", "code")
