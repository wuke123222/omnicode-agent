package dev.omnicode.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * A small, dependency-free chart for numeric CSV/TSV columns.
 *
 * It deliberately draws only the bounded samples produced by [analyzeTabularText]. The chart is
 * a local preview: it is not serialized, sent to a provider, or treated as an instruction.
 */
internal class TabularChartPanel(
    private val summary: TabularAttachmentSummary,
) : JComponent() {
    private val chartColumns = summary.chartColumns

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(520), JBUI.scale(178))
        minimumSize = Dimension(JBUI.scale(260), JBUI.scale(140))
        toolTipText = chartColumns.joinToString("、") { it.name }
        accessibleContext?.accessibleName = "${summary.formatLabel} 数值趋势图"
        accessibleContext?.accessibleDescription = if (chartColumns.isEmpty()) {
            "没有足够的数值样本生成图表"
        } else {
            "显示 ${chartColumns.joinToString("、") { it.name }} 的本地有界趋势"
        }
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (chartColumns.isEmpty()) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val left = JBUI.scale(38)
            val right = JBUI.scale(12)
            val top = JBUI.scale(28)
            val bottom = JBUI.scale(26)
            val width = (width - left - right).coerceAtLeast(1)
            val height = (height - top - bottom).coerceAtLeast(1)
            val allValues = chartColumns.flatMap { it.samples }
            val minimum = allValues.minOrNull() ?: return
            val maximum = allValues.maxOrNull() ?: return
            val range = (maximum - minimum).takeUnless { it == 0.0 } ?: 1.0

            g.color = OmniCodeUiPalette.secondary
            g.font = g.font.deriveFont(g.font.size2D * 0.85f)
            g.drawString(formatAxisValue(maximum), 2, top + g.fontMetrics.ascent)
            g.drawString(formatAxisValue(minimum), 2, top + height)
            g.color = OmniCodeUiPalette.border
            g.stroke = BasicStroke(1f)
            g.drawLine(left, top, left, top + height)
            g.drawLine(left, top + height, left + width, top + height)

            chartColumns.forEachIndexed { seriesIndex, column ->
                val values = column.samples
                if (values.size < 2) return@forEachIndexed
                val color = CHART_COLORS[seriesIndex % CHART_COLORS.size]
                g.color = color
                g.stroke = BasicStroke(JBUI.scale(2).toFloat())
                var previousX = left.toDouble()
                var previousY = yFor(values.first(), minimum, range, top, height)
                values.drop(1).forEachIndexed { index, value ->
                    val x = left + (index + 1) * width / (values.size - 1).toDouble()
                    val y = yFor(value, minimum, range, top, height)
                    g.drawLine(previousX.toInt(), previousY.toInt(), x.toInt(), y.toInt())
                    previousX = x
                    previousY = y
                }
                g.fillOval(left - 3, previousY.toInt() - 3, 6, 6)
                val labelX = left + JBUI.scale(8) + seriesIndex * JBUI.scale(120)
                g.fillRoundRect(labelX, JBUI.scale(5), JBUI.scale(8), JBUI.scale(8), 4, 4)
                g.color = OmniCodeUiPalette.primary
                g.drawString(column.name.take(16), labelX + JBUI.scale(12), JBUI.scale(13))
            }
            g.color = OmniCodeUiPalette.secondary
            g.drawString("本地样本 · ${summary.dataRows} 行", left, height + top + JBUI.scale(18))
        } finally {
            g.dispose()
        }
    }

    private fun yFor(value: Double, minimum: Double, range: Double, top: Int, height: Int): Double =
        top + height - ((value - minimum) / range).coerceIn(0.0, 1.0) * height

    private fun formatAxisValue(value: Double): String = String.format(java.util.Locale.ROOT, "%.4g", value)

    private companion object {
        val CHART_COLORS = listOf(
            JBColor(ColorPalette.blue, ColorPalette.blueDark),
            JBColor(ColorPalette.green, ColorPalette.greenDark),
            JBColor(ColorPalette.orange, ColorPalette.orangeDark),
            JBColor(ColorPalette.purple, ColorPalette.purpleDark),
        )
    }

    private object ColorPalette {
        val blue = java.awt.Color(0x2F, 0x6B, 0xD7)
        val blueDark = java.awt.Color(0x7A, 0xA2, 0xF7)
        val green = java.awt.Color(0x2E, 0x8B, 0x57)
        val greenDark = java.awt.Color(0x73, 0xC9, 0x91)
        val orange = java.awt.Color(0xC7, 0x7B, 0x19)
        val orangeDark = java.awt.Color(0xE3, 0xAE, 0x63)
        val purple = java.awt.Color(0x7A, 0x4E, 0xA3)
        val purpleDark = java.awt.Color(0xC5, 0x9A, 0xE8)
    }
}
