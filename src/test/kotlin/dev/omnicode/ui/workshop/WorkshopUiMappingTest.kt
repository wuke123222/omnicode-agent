package dev.omnicode.ui.workshop

import dev.omnicode.workshop.WorkshopCatalog
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkshopUiMappingTest {
    @Test
    fun `hex colours preserve rgb and optional alpha`() {
        assertEquals(0x12, workshopColor("#123456").red)
        assertEquals(0x34, workshopColor("#123456").green)
        assertEquals(0x56, workshopColor("#123456").blue)
        assertEquals(0x78, workshopColor("#12345678").alpha)
    }

    @Test
    fun `pet catalog choices produce distinct safe shapes`() {
        val cat = WorkshopCatalog.resolve(
            WorkshopCatalog.defaultSelection().copy(petEnabled = true, petId = "pixel-cat"),
        ).toDesktopPetAppearance()
        val robot = WorkshopCatalog.resolve(
            WorkshopCatalog.defaultSelection().copy(petEnabled = true, petId = "tiny-robot"),
        ).toDesktopPetAppearance()

        assertNotEquals(cat.shape.earHeight, robot.shape.earHeight)
        assertNotEquals(cat.theme.accent, robot.theme.accent)
    }

    @Test
    fun `mapped graphite and aurora accents retain accessible text contrast`() {
        listOf("graphite-night", "aurora-night").forEach { themeId ->
            val colors = WorkshopCatalog.resolve(
                WorkshopCatalog.defaultSelection().copy(themeId = themeId),
            ).toUiColors()
            val ratio = contrastRatio(colors.accent, colors.accentText)

            assertTrue(ratio >= 3.0, "$themeId mapped accent contrast was $ratio")
        }
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) {
                normalized / 12.92
            } else {
                Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
