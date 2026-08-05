package dev.omnicode.ui

import java.util.Locale
import kotlin.math.roundToInt

/** Local, bounded CSV/TSV inspection used for research-oriented attachment context. */
internal data class TabularColumnSummary(
    val name: String,
    val nonBlankRows: Int,
    val numericRows: Int,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val average: Double? = null,
    val sparkline: String = "",
    /** A tiny, local-only sample used by the attachment chart; never persisted or uploaded. */
    val samples: List<Double> = emptyList(),
)

internal data class TabularAttachmentSummary(
    val delimiter: Char,
    val dataRows: Int,
    val columns: Int,
    val truncated: Boolean,
    val columnSummaries: List<TabularColumnSummary>,
) {
    val formatLabel: String get() = if (delimiter == '\t') "TSV" else "CSV"
    val chartColumns: List<TabularColumnSummary>
        get() = columnSummaries.filter { it.samples.size >= 2 }.take(MAX_CHART_COLUMNS)

    fun render(maxColumns: Int = MAX_RENDER_COLUMNS): String = buildString {
        append("[本地表格摘要 · ").append(formatLabel).append(" · ")
            .append(dataRows).append(" 行 × ").append(columns).append(" 列]")
        columnSummaries.take(maxColumns).forEach { column ->
            append("\n- ").append(column.name).append("：非空 ").append(column.nonBlankRows).append(" 行")
            if (column.numericRows > 0) {
                append(" · 数值 ").append(column.numericRows).append(" 行")
                column.minimum?.let { append(" · min=").append(formatNumber(it)) }
                column.maximum?.let { append(" · max=").append(formatNumber(it)) }
                column.average?.let { append(" · avg=").append(formatNumber(it)) }
                column.sparkline.takeIf(String::isNotBlank)?.let { append(" · 趋势 ").append(it) }
            }
        }
        if (columns > maxColumns) append("\n- 其余 ").append(columns - maxColumns).append(" 列未在摘要中展开")
        if (truncated) append("\n[表格摘要已按本地安全上限截断；原始附件仍未执行]")
        else append("\n[摘要仅由本地有界解析生成]")
    }.take(MAX_RENDER_CHARS)
}

internal fun analyzeTabularText(
    value: CharSequence,
    delimiter: Char,
    maxRows: Int = MAX_ROWS,
    maxColumns: Int = MAX_COLUMNS,
): TabularAttachmentSummary? {
    require(delimiter == ',' || delimiter == '\t')
    if (value.isEmpty() || maxRows <= 0 || maxColumns <= 0) return null
    val scanLimit = minOf(value.length, MAX_SCAN_CHARS)
    val parsed = parseRecords(value, delimiter, scanLimit, maxRows + 1, maxColumns)
    if (parsed.records.isEmpty()) return null
    val first = parsed.records.first()
    val hasHeader = first.any { it.trim().isNotEmpty() && parseNumber(it) == null }
    val header = if (hasHeader) first else emptyList()
    val data = if (hasHeader) parsed.records.drop(1) else parsed.records
    val columns = minOf(maxColumns, maxOf(header.size, data.maxOfOrNull { it.size } ?: 0))
    if (columns == 0) return null
    val summaries = (0 until columns).map { index ->
        val name = header.getOrNull(index)?.trim()?.take(MAX_HEADER_CHARS)
            ?.takeIf(String::isNotBlank) ?: "列 ${index + 1}"
        val values = data.mapNotNull { row -> row.getOrNull(index)?.trim()?.takeIf(String::isNotBlank) }
        val numbers = values.mapNotNull(::parseNumber).filter(Double::isFinite)
        TabularColumnSummary(
            name = name,
            nonBlankRows = values.size,
            numericRows = numbers.size,
            minimum = numbers.minOrNull(),
            maximum = numbers.maxOrNull(),
            average = numbers.takeIf { it.isNotEmpty() }?.average(),
            sparkline = sparkline(numbers),
            samples = numbers.take(MAX_CHART_SAMPLES),
        )
    }
    return TabularAttachmentSummary(
        delimiter = delimiter,
        dataRows = data.size,
        columns = columns,
        truncated = parsed.truncated || value.length > scanLimit || data.size > maxRows,
        columnSummaries = summaries,
    )
}

private data class ParsedRecords(val records: List<List<String>>, val truncated: Boolean)

private fun parseRecords(
    value: CharSequence,
    delimiter: Char,
    scanLimit: Int,
    maxRecords: Int,
    maxColumns: Int,
): ParsedRecords {
    val records = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var truncated = false
    var index = 0

    fun addCell() {
        if (row.size >= maxColumns) truncated = true
        else {
            row += cell.toString().take(MAX_CELL_CHARS)
            if (cell.length > MAX_CELL_CHARS) truncated = true
        }
        cell.setLength(0)
    }
    fun addRow() {
        if (row.isNotEmpty() || cell.isNotEmpty()) addCell()
        if (row.isNotEmpty()) {
            if (records.size < maxRecords) records += row.toList() else truncated = true
        }
        row.clear()
    }

    while (index < scanLimit) {
        val character = value[index]
        if (quoted) {
            when {
                character == '"' && index + 1 < scanLimit && value[index + 1] == '"' -> {
                    cell.append('"'); index++
                }
                character == '"' -> quoted = false
                else -> cell.append(character)
            }
        } else {
            when (character) {
                '"' -> if (cell.isEmpty()) quoted = true else cell.append(character)
                delimiter -> addCell()
                '\n' -> addRow()
                '\r' -> if (index + 1 >= scanLimit || value[index + 1] != '\n') addRow()
                else -> cell.append(character)
            }
        }
        index++
    }
    if (quoted) truncated = true
    if (cell.isNotEmpty() || row.isNotEmpty()) addRow()
    return ParsedRecords(records, truncated)
}

private fun parseNumber(value: String): Double? = value.trim().takeIf(String::isNotBlank)?.toDoubleOrNull()

private fun sparkline(values: List<Double>): String {
    if (values.size < 2) return ""
    val minimum = values.minOrNull() ?: return ""
    val maximum = values.maxOrNull() ?: return ""
    if (minimum == maximum) return "▅".repeat(minOf(values.size, SPARKLINE_POINTS))
    return values.takeLast(SPARKLINE_POINTS).joinToString("") { value ->
        val level = (((value - minimum) / (maximum - minimum)) * 7.0).roundToInt().coerceIn(0, 7)
        SPARKLINE[level].toString()
    }
}

private fun formatNumber(value: Double): String = String.format(Locale.ROOT, "%.4g", value)

private const val MAX_SCAN_CHARS = 512 * 1024
private const val MAX_ROWS = 500
private const val MAX_COLUMNS = 32
private const val MAX_CELL_CHARS = 512
private const val MAX_HEADER_CHARS = 64
private const val MAX_RENDER_COLUMNS = 12
private const val MAX_RENDER_CHARS = 4_096
private const val MAX_CHART_COLUMNS = 4
private const val MAX_CHART_SAMPLES = 64
private const val SPARKLINE_POINTS = 12
private const val SPARKLINE = "▁▂▃▄▅▆▇█"
