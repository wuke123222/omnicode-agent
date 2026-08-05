package dev.omnicode.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/** Scroll content that always adopts the viewport width instead of being clipped at its preferred width. */
internal class ViewportWidthPanel : JPanel(), Scrollable {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(18)

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = if (orientation == SwingConstants.VERTICAL) {
        (visibleRect.height - JBUI.scale(24)).coerceAtLeast(JBUI.scale(18))
    } else {
        (visibleRect.width - JBUI.scale(24)).coerceAtLeast(JBUI.scale(18))
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/**
 * A FlowLayout variant whose preferred height reflects wrapping at the container's
 * current available width. Standard FlowLayout lays out multiple rows but reports
 * only one row, so BorderLayout and BoxLayout otherwise clip the later rows.
 */
internal class WrapLayout(
    align: Int = FlowLayout.LEFT,
    hgap: Int = JBUI.scale(5),
    vgap: Int = JBUI.scale(5),
) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = true, availableWidth(target))

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false, availableWidth(target))

    internal fun preferredLayoutSize(target: Container, availableWidth: Int): Dimension =
        layoutSize(target, preferred = true, availableWidth)

    private fun availableWidth(target: Container): Int {
        val parent = target.parent
        val parentWidth = parent?.width?.takeIf { it > 0 }?.let { width ->
            width - parent.insets.left - parent.insets.right
        }
        return parentWidth ?: target.width.takeIf { it > 0 } ?: Int.MAX_VALUE
    }

    private fun layoutSize(target: Container, preferred: Boolean, availableWidth: Int): Dimension =
        synchronized(target.treeLock) {
            val insets = target.insets
            val horizontalInsets = insets.left + insets.right + hgap * 2
            val usableWidth = if (availableWidth == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                (availableWidth - horizontalInsets).coerceAtLeast(1)
            }
            var rowWidth = 0
            var rowHeight = 0
            var widestRow = 0
            var totalHeight = insets.top + insets.bottom + vgap * 2
            var rows = 0

            target.components.asSequence().filter(Component::isVisible).forEach { component ->
                val size = if (preferred) component.preferredSize else component.minimumSize
                val addedWidth = if (rowWidth == 0) size.width else hgap + size.width
                if (rowWidth > 0 && rowWidth + addedWidth > usableWidth) {
                    widestRow = maxOf(widestRow, rowWidth)
                    totalHeight += rowHeight
                    if (rows > 0) totalHeight += vgap
                    rows++
                    rowWidth = size.width
                    rowHeight = size.height
                } else {
                    rowWidth += addedWidth
                    rowHeight = maxOf(rowHeight, size.height)
                }
            }
            if (rowWidth > 0) {
                widestRow = maxOf(widestRow, rowWidth)
                totalHeight += rowHeight
                if (rows > 0) totalHeight += vgap
            }
            Dimension(widestRow + horizontalInsets, totalHeight)
        }
}

/** Responsive action row suitable for BorderLayout.SOUTH and BoxLayout cards. */
internal class WrappingActionPanel(
    alignment: Int = FlowLayout.RIGHT,
    horizontalGap: Int = JBUI.scale(5),
    verticalGap: Int = JBUI.scale(5),
) : JPanel(WrapLayout(alignment, horizontalGap, verticalGap)) {
    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val wrap = layout as WrapLayout
        val parentWidth = parent?.width?.takeIf { it > 0 }?.let { width ->
            width - parent.insets.left - parent.insets.right
        }
        // A detached component can be measured before its parent receives a width (notably
        // during Tool Window construction and deterministic Swing smoke tests). Measuring as a
        // single unbounded row makes the later narrow layout clip its wrapped children. A small
        // conservative fallback reserves enough rows until the real parent width is available.
        val available = parentWidth ?: width.takeIf { it > 0 } ?: JBUI.scale(DEFAULT_WRAP_MEASURE_WIDTH)
        return wrap.preferredLayoutSize(this, available)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private companion object {
        const val DEFAULT_WRAP_MEASURE_WIDTH: Int = 200
    }
}

internal fun boundedTooltipHtml(value: String, maxCharacters: Int = 2_000): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    val bounded = if (normalized.length <= maxCharacters) normalized else {
        normalized.take(maxCharacters) + "\n…（工具提示已截断）"
    }
    return "<html><body width='520'>${escapeTooltipHtml(bounded).replace("\n", "<br>")}</body></html>"
}

private fun escapeTooltipHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
