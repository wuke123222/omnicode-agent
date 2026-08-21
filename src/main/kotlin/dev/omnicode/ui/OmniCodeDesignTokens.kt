package dev.omnicode.ui

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Shared, non-color design tokens for every OmniCode surface.
 *
 * [OmniCodeUiPalette] already centralises semantic colors; this object adds the missing
 * spacing / radius / control scales plus small perception helpers so panels stop inventing
 * per-file inset and corner values. All values are logical (unscaled) pixels — callers pass
 * them through `JBUI.scale` exactly like the previous literals.
 */
internal object OmniCodeUiTokens {
    // Spacing scale. Prefer these steps over ad-hoc 1..20 literals.
    const val SPACE_XXS = 2
    const val SPACE_XS = 4
    const val SPACE_SM = 6
    const val SPACE_MD = 8
    const val SPACE_LG = 12
    const val SPACE_XL = 16
    const val SPACE_XXL = 20

    // Corner radius scale: SM for chips/inline cards, MD for controls and elevated
    // timeline cards, LG for the composer, message bubbles and page sections.
    const val RADIUS_SM = 8
    const val RADIUS_MD = 10
    const val RADIUS_LG = 12

    // Control heights: regular composer controls and compact transcript actions.
    const val CONTROL_HEIGHT = 32
    const val COMPACT_CONTROL_HEIGHT = 28
    const val PRIMARY_CONTROL_HEIGHT = 34

    /**
     * Elevation cue under rounded cards. Dark themes need a stronger shade for the cue to be
     * perceptible against an already-dark canvas; light themes only want a whisper of depth.
     */
    val cardShadow: Color = JBColor(Color(0, 0, 0, 22), Color(0, 0, 0, 70))
}

/** Relative luminance in 0..1 (sRGB approximation, good enough for text-contrast picks). */
internal fun colorLuminance(color: Color): Double =
    (0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue) / 255.0

/**
 * Picks a readable text/icon color for an arbitrary solid fill. Used by filled primary
 * actions whose background follows the (possibly user-customised) accent color, where a
 * hardcoded white label loses contrast on light accents.
 */
internal fun readableTextOn(background: Color): Color =
    if (colorLuminance(background) > 0.56) Color(0x1E, 0x20, 0x24) else Color.WHITE

/**
 * Hover fill for a filled control: lighten dark fills, darken light fills. Blending a light
 * accent further toward white (the previous behaviour) made hover invisible.
 */
internal fun hoverFillFor(base: Color): Color =
    if (colorLuminance(base) > 0.56) blendColorChannels(base, Color.BLACK, 0.08)
    else blendColorChannels(base, Color.WHITE, 0.12)

/** Pressed fill for a filled control; one step further than hover in the same direction. */
internal fun pressedFillFor(base: Color): Color =
    if (colorLuminance(base) > 0.56) blendColorChannels(base, Color.BLACK, 0.16)
    else blendColorChannels(base, Color.WHITE, 0.22)

internal fun blendColorChannels(base: Color, overlay: Color, ratio: Double): Color {
    val weight = ratio.coerceIn(0.0, 1.0)
    fun channel(left: Int, right: Int): Int = (left + ((right - left) * weight)).toInt().coerceIn(0, 255)
    return Color(
        channel(base.red, overlay.red),
        channel(base.green, overlay.green),
        channel(base.blue, overlay.blue),
        base.alpha,
    )
}

/** Platform-correct composer send hint; the previous label hardcoded the macOS glyph. */
internal fun composerSendShortcutLabel(isMac: Boolean = SystemInfo.isMac): String =
    if (isMac) "⌘↵ 发送" else "Ctrl↵ 发送"

internal fun composerSendShortcutTooltip(isMac: Boolean = SystemInfo.isMac): String =
    if (isMac) "发送 · Cmd+Enter" else "发送 · Ctrl+Enter"
