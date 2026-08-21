package dev.omnicode.ui

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OmniCodeDesignTokensTest {
    @Test
    fun `radius scale stays ordered and matches the locked composer geometry`() {
        assertTrue(OmniCodeUiTokens.RADIUS_SM < OmniCodeUiTokens.RADIUS_MD)
        assertTrue(OmniCodeUiTokens.RADIUS_MD < OmniCodeUiTokens.RADIUS_LG)
        // ComposerControlStyleTest locks control geometry to 32/10; the tokens must agree.
        assertEquals(OmniCodeUiTokens.CONTROL_HEIGHT, composerControlStyle(ComposerControlState.QUIET).logicalHeight)
        assertEquals(OmniCodeUiTokens.RADIUS_MD, composerControlStyle(ComposerControlState.QUIET).cornerArc)
    }

    @Test
    fun `spacing scale is strictly increasing`() {
        val scale = listOf(
            OmniCodeUiTokens.SPACE_XXS,
            OmniCodeUiTokens.SPACE_XS,
            OmniCodeUiTokens.SPACE_SM,
            OmniCodeUiTokens.SPACE_MD,
            OmniCodeUiTokens.SPACE_LG,
            OmniCodeUiTokens.SPACE_XL,
            OmniCodeUiTokens.SPACE_XXL,
        )
        assertEquals(scale.sorted(), scale)
        assertEquals(scale.distinct(), scale)
    }

    @Test
    fun `readable text flips between dark and light fills`() {
        assertEquals(Color.WHITE, readableTextOn(Color(0x36, 0x65, 0xD8)))
        assertEquals(Color.WHITE, readableTextOn(Color.BLACK))
        assertTrue(readableTextOn(Color.WHITE) != Color.WHITE)
        assertTrue(readableTextOn(Color(0xF2, 0xD4, 0x66)) != Color.WHITE)
    }

    @Test
    fun `hover and pressed fills move away from the base fill in the readable direction`() {
        val darkAccent = Color(0x2B, 0x47, 0x8C)
        assertTrue(colorLuminance(hoverFillFor(darkAccent)) > colorLuminance(darkAccent))
        assertTrue(colorLuminance(pressedFillFor(darkAccent)) > colorLuminance(hoverFillFor(darkAccent)))

        val lightAccent = Color(0xE7, 0xEE, 0xFF)
        assertTrue(colorLuminance(hoverFillFor(lightAccent)) < colorLuminance(lightAccent))
        assertTrue(colorLuminance(pressedFillFor(lightAccent)) < colorLuminance(hoverFillFor(lightAccent)))
    }

    @Test
    fun `blend preserves alpha and clamps ratio`() {
        val base = Color(10, 20, 30, 128)
        assertEquals(base, blendColorChannels(base, Color.WHITE, 0.0))
        val blended = blendColorChannels(base, Color.WHITE, 2.0)
        assertEquals(Color(255, 255, 255, 128), blended)
    }

    @Test
    fun `composer shortcut copy follows the platform`() {
        assertEquals("⌘↵ 发送", composerSendShortcutLabel(isMac = true))
        assertEquals("Ctrl↵ 发送", composerSendShortcutLabel(isMac = false))
        assertEquals("发送 · Cmd+Enter", composerSendShortcutTooltip(isMac = true))
        assertEquals("发送 · Ctrl+Enter", composerSendShortcutTooltip(isMac = false))
    }
}
