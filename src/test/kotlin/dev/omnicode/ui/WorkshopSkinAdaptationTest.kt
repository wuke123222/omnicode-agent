package dev.omnicode.ui

import dev.omnicode.ui.workshop.WorkshopUiColors
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkshopSkinAdaptationTest {
    private fun skin(background: Color = Color(0xF5, 0xEF, 0xE2)): WorkshopUiColors = WorkshopUiColors(
        background = background,
        surface = Color(0xFC, 0xF8, 0xF0),
        elevatedSurface = Color(0xEF, 0xE6, 0xD2),
        primaryText = Color(0x2A, 0x26, 0x1E),
        secondaryText = Color(0x6E, 0x66, 0x58),
        accent = Color(0x3A, 0x6E, 0xA5),
        accentText = Color.WHITE,
        border = Color(0xD8, 0xCD, 0xB8),
        success = Color(0x2F, 0x7D, 0x4A),
        warning = Color(0x9A, 0x62, 0x00),
        error = Color(0xB6, 0x2E, 0x3A),
    )

    @Test
    fun `default palette selection clears the active skin`() {
        ActiveWorkshopSkin.update(skin())
        assertNotNull(ActiveWorkshopSkin.current)

        // The default workshop selection passes the palette JBColor instances through
        // unchanged, which is how paint-time consumers know to follow the IDE LAF.
        ActiveWorkshopSkin.update(
            skin().copy(background = OmniCodeUiPalette.canvas),
        )
        assertNull(ActiveWorkshopSkin.current)
    }

    @Test
    fun `derived skin fills stay between their source colors`() {
        val colors = skin()
        val selected = ActiveWorkshopSkin.selectedFill(colors)
        val hover = ActiveWorkshopSkin.hoverFill(colors)

        // Selected leans from the elevated surface toward the accent; hover sits between the
        // canvas and the elevated surface. Both must stay light for a light skin so text
        // retargeted to the skin's dark primary color remains readable.
        assertTrue(colorLuminance(selected) > 0.5, "selected fill should stay light on a light skin")
        assertTrue(colorLuminance(hover) > 0.5, "hover fill should stay light on a light skin")
        assertTrue(colorLuminance(selected) < colorLuminance(colors.elevatedSurface) + 0.01)

        ActiveWorkshopSkin.update(skin().copy(background = OmniCodeUiPalette.canvas))
        assertNull(ActiveWorkshopSkin.current)
    }

    @Test
    fun `readable text on skin fills flips with the fill`() {
        val colors = skin()
        assertEquals(Color.WHITE, readableTextOn(colors.accent))
        assertTrue(readableTextOn(ActiveWorkshopSkin.selectedFill(colors)) != Color.WHITE)
    }
}
