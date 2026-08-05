package dev.omnicode.ui

import java.awt.image.BufferedImage
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic component-level screenshot smoke test.
 *
 * The same harness is intentionally usable by a Remote Robot runner: it fixes the viewport,
 * paints the component off-screen, and compares a digest without writing user data to disk.
 * Full IDE-window baselines still belong in a platform CI job because they depend on the IDE
 * renderer, theme, scale factor, and display backend.
 */
class UiScreenshotRegressionTest {
    @Test
    fun `tabular chart paints deterministically at narrow and wide widths`() {
        val summary = analyzeTabularText(
            "time,value,loss\n1,10,0.9\n2,14,0.7\n3,18,0.4\n4,15,0.3",
            ',',
        ) ?: error("expected table summary")
        val narrow = screenshotDigest(TabularChartPanel(summary), 360, 178)
        val wide = screenshotDigest(TabularChartPanel(summary), 720, 178)
        assertTrue(narrow.isNotBlank())
        assertTrue(wide.isNotBlank())
        assertEquals(narrow, screenshotDigest(TabularChartPanel(summary), 360, 178))
        assertEquals(wide, screenshotDigest(TabularChartPanel(summary), 720, 178))
    }

    private fun screenshotDigest(component: javax.swing.JComponent, width: Int, height: Int): String {
        component.setSize(width, height)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(width)
        for (y in 0 until height) {
            image.getRGB(0, y, width, 1, row, 0, width)
            row.forEach { pixel ->
                digest.update((pixel ushr 24).toByte())
                digest.update((pixel ushr 16).toByte())
                digest.update((pixel ushr 8).toByte())
                digest.update(pixel.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
