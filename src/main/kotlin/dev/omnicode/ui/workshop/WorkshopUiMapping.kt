package dev.omnicode.ui.workshop

import dev.omnicode.ui.OmniCodeUiPalette
import dev.omnicode.workshop.CustomPetAvatarStore
import dev.omnicode.workshop.ResolvedWorkshopSelection
import dev.omnicode.workshop.WorkshopCatalog
import dev.omnicode.workshop.WorkshopPetVisual
import dev.omnicode.workshop.WorkshopThemePalette
import java.awt.Color

internal data class WorkshopUiColors(
    val background: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val accentText: Color,
    val border: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

internal fun WorkshopThemePalette.toUiColors(): WorkshopUiColors = WorkshopUiColors(
    background = workshopColor(background),
    surface = workshopColor(surface),
    elevatedSurface = workshopColor(elevatedSurface),
    primaryText = workshopColor(primaryText),
    secondaryText = workshopColor(secondaryText),
    accent = workshopColor(accent),
    accentText = workshopColor(accentText),
    border = workshopColor(border),
    success = workshopColor(success),
    warning = workshopColor(warning),
    error = workshopColor(error),
)

internal fun ResolvedWorkshopSelection.toUiColors(): WorkshopUiColors =
    if (theme.id == WorkshopCatalog.DEFAULT_THEME_ID) {
        WorkshopUiColors(
            background = OmniCodeUiPalette.canvas,
            surface = OmniCodeUiPalette.surface,
            elevatedSurface = OmniCodeUiPalette.controlSelected,
            primaryText = OmniCodeUiPalette.primary,
            secondaryText = OmniCodeUiPalette.secondary,
            accent = OmniCodeUiPalette.accent,
            accentText = Color.WHITE,
            border = OmniCodeUiPalette.border,
            success = OmniCodeUiPalette.success,
            warning = OmniCodeUiPalette.warning,
            error = OmniCodeUiPalette.error,
        )
    } else {
        theme.palette.toUiColors()
    }

/**
 * Applies a workshop pack to existing chat chrome without recolouring third-party/IDE controls.
 * Native surfaces keep their current LAF contrast; the selected pack supplies accent identity.
 */
internal fun ResolvedWorkshopSelection.toWorkspaceColors(): WorkshopUiColors {
    val native = WorkshopUiColors(
        background = OmniCodeUiPalette.canvas,
        surface = OmniCodeUiPalette.surface,
        elevatedSurface = OmniCodeUiPalette.controlSelected,
        primaryText = OmniCodeUiPalette.primary,
        secondaryText = OmniCodeUiPalette.secondary,
        accent = OmniCodeUiPalette.accent,
        accentText = Color.WHITE,
        border = OmniCodeUiPalette.border,
        success = OmniCodeUiPalette.success,
        warning = OmniCodeUiPalette.warning,
        error = OmniCodeUiPalette.error,
    )
    if (theme.id == WorkshopCatalog.DEFAULT_THEME_ID) return native
    val themed = theme.palette.toUiColors()
    return native.copy(
        elevatedSurface = mixWorkshopColors(native.surface, themed.accent, 0.14),
        accent = themed.accent,
        accentText = themed.accentText,
    )
}

internal fun ResolvedWorkshopSelection.toDesktopPetAppearance(): DesktopPetAppearance =
    toDesktopPetAppearance(CustomPetAvatarStore.shared)

internal fun ResolvedWorkshopSelection.toDesktopPetAppearance(
    avatarStore: CustomPetAvatarStore,
): DesktopPetAppearance {
    val colors = toUiColors()
    val petAccent = pet?.accentColor?.let(::workshopColor) ?: colors.accent
    val darkSurface = colors.background.red + colors.background.green + colors.background.blue < 3 * 128
    val visual = pet?.visual ?: WorkshopPetVisual.CREATURE
    val shape = when (visual) {
        WorkshopPetVisual.OWL -> DesktopPetShape(earHeight = 10, cornerRadius = 18, eyeSize = 8)
        WorkshopPetVisual.DUCK -> DesktopPetShape(earHeight = 0, cornerRadius = 28, bodyWidth = 72, eyeSize = 6)
        WorkshopPetVisual.ROBOT -> DesktopPetShape(earHeight = 0, cornerRadius = 10, bodyWidth = 72, eyeSize = 6)
        WorkshopPetVisual.IDOL_VOCALIST,
        WorkshopPetVisual.IDOL_GUITARIST,
        WorkshopPetVisual.CUSTOM_AVATAR,
        -> DesktopPetShape(
            preferredWidth = 124,
            preferredHeight = 118,
            bodyWidth = 70,
            bodyHeight = 60,
            cornerRadius = 18,
            earHeight = 0,
            eyeSize = 6,
        )
        else -> DesktopPetShape()
    }
    val isIdol = visual == WorkshopPetVisual.IDOL_VOCALIST || visual == WorkshopPetVisual.IDOL_GUITARIST
    return DesktopPetAppearance(
        theme = DesktopPetTheme(
            body = mixWorkshopColors(colors.surface, petAccent, if (isIdol) 0.58 else 0.22),
            face = if (isIdol) Color(0xF6D5C2) else colors.elevatedSurface,
            outline = mixWorkshopColors(colors.border, petAccent, 0.18),
            foreground = colors.primaryText,
            muted = colors.secondaryText,
            accent = petAccent,
            success = colors.success,
            error = colors.error,
            shadow = Color(0, 0, 0, if (darkSurface) 76 else 34),
        ),
        shape = shape,
        visual = visual,
        customAvatar = if (visual == WorkshopPetVisual.CUSTOM_AVATAR) avatarStore.loadImage() else null,
    )
}

internal fun workshopColor(value: String): Color {
    val raw = value.removePrefix("#")
    require(raw.length == 6 || raw.length == 8) { "Unsupported workshop colour" }
    val parsed = raw.toLong(16)
    return if (raw.length == 8) {
        Color(
            ((parsed shr 24) and 0xFF).toInt(),
            ((parsed shr 16) and 0xFF).toInt(),
            ((parsed shr 8) and 0xFF).toInt(),
            (parsed and 0xFF).toInt(),
        )
    } else {
        Color(parsed.toInt())
    }
}

private fun mixWorkshopColors(base: Color, overlay: Color, ratio: Double): Color {
    val weight = ratio.coerceIn(0.0, 1.0)
    fun channel(left: Int, right: Int): Int = (left + (right - left) * weight).toInt().coerceIn(0, 255)
    return Color(
        channel(base.red, overlay.red),
        channel(base.green, overlay.green),
        channel(base.blue, overlay.blue),
        channel(base.alpha, overlay.alpha),
    )
}
