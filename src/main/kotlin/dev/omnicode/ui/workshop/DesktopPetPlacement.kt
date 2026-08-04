package dev.omnicode.ui.workshop

import dev.omnicode.workshop.PetPlacementSettings
import java.awt.Dimension
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

internal fun isDesktopPetFloatingSupported(): Boolean {
    if (GraphicsEnvironment.isHeadless()) return false
    return runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
            device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }
    }.getOrDefault(false)
}

internal fun embeddedPetLocation(
    parentSize: Dimension,
    petSize: Dimension,
    normalizedX: Int,
    normalizedY: Int,
    margin: Int = 8,
): Point {
    val horizontalSpace = (parentSize.width - petSize.width - margin * 2).coerceAtLeast(0)
    val verticalSpace = (parentSize.height - petSize.height - margin * 2).coerceAtLeast(0)
    return Point(
        margin + (horizontalSpace * normalizedX.toDouble() / PetPlacementSettings.NORMALIZED_POSITION_MAX).roundToInt(),
        margin + (verticalSpace * normalizedY.toDouble() / PetPlacementSettings.NORMALIZED_POSITION_MAX).roundToInt(),
    )
}

internal fun normalizedEmbeddedPetLocation(
    location: Point,
    parentSize: Dimension,
    petSize: Dimension,
    margin: Int = 8,
): Point {
    fun normalize(value: Int, available: Int): Int {
        if (available <= 0) return 0
        return ((value - margin).coerceIn(0, available).toDouble() / available *
            PetPlacementSettings.NORMALIZED_POSITION_MAX).roundToInt()
    }
    val horizontalSpace = (parentSize.width - petSize.width - margin * 2).coerceAtLeast(0)
    val verticalSpace = (parentSize.height - petSize.height - margin * 2).coerceAtLeast(0)
    return Point(
        normalize(location.x, horizontalSpace),
        normalize(location.y, verticalSpace),
    )
}

internal fun clampEmbeddedPetLocation(
    location: Point,
    parentSize: Dimension,
    petSize: Dimension,
    margin: Int = 8,
): Point {
    fun clamp(value: Int, available: Int): Int {
        val space = available.coerceAtLeast(0)
        return if (space > margin * 2) value.coerceIn(margin, space - margin) else value.coerceIn(0, space)
    }
    return Point(
        clamp(location.x, parentSize.width - petSize.width),
        clamp(location.y, parentSize.height - petSize.height),
    )
}

internal fun clampFloatingPetLocation(
    requested: Point?,
    petSize: Dimension,
    usableScreens: List<Rectangle>,
    fallbackScreen: Rectangle,
    margin: Int = 12,
): Point {
    val screens = usableScreens.filter { it.width > 0 && it.height > 0 }.ifEmpty { listOf(fallbackScreen) }
    val fallback = screens.firstOrNull { it.intersects(fallbackScreen) } ?: screens.first()
    val initial = requested ?: Point(
        fallback.x + fallback.width - petSize.width - margin,
        fallback.y + fallback.height - petSize.height - margin,
    )
    val center = Point(initial.x + petSize.width / 2, initial.y + petSize.height / 2)
    val screen = screens.firstOrNull { it.contains(center) }
        ?: screens.minBy { distanceSquared(center, it) }
    val minX = screen.x
    val minY = screen.y
    val maxX = (screen.x + screen.width - petSize.width).coerceAtLeast(minX)
    val maxY = (screen.y + screen.height - petSize.height).coerceAtLeast(minY)
    return Point(initial.x.coerceIn(minX, maxX), initial.y.coerceIn(minY, maxY))
}

internal fun usableDesktopScreens(): List<Rectangle> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    val toolkit = Toolkit.getDefaultToolkit()
    return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.mapNotNull { device ->
        val configuration = device.defaultConfiguration ?: return@mapNotNull null
        val insets = runCatching { toolkit.getScreenInsets(configuration) }.getOrDefault(Insets(0, 0, 0, 0))
        configuration.bounds.let { bounds ->
            Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                (bounds.width - insets.left - insets.right).coerceAtLeast(1),
                (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
            )
        }
    }
}

private fun distanceSquared(point: Point, rectangle: Rectangle): Long {
    val nearestX = point.x.coerceIn(rectangle.x, rectangle.x + rectangle.width)
    val nearestY = point.y.coerceIn(rectangle.y, rectangle.y + rectangle.height)
    val deltaX = point.x.toLong() - nearestX
    val deltaY = point.y.toLong() - nearestY
    return deltaX * deltaX + deltaY * deltaY
}
