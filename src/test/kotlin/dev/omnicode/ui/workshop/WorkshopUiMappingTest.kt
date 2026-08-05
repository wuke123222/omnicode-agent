package dev.omnicode.ui.workshop

import dev.omnicode.workshop.WorkshopCatalog
import dev.omnicode.workshop.CustomPetAvatarStore
import dev.omnicode.workshop.WorkshopPetVisual
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
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
    fun `virtual idols map to explicit host-rendered silhouettes`() {
        val vocalist = WorkshopCatalog.resolve(
            WorkshopCatalog.defaultSelection().copy(petEnabled = true, petId = "lumi-vocalist"),
        ).toDesktopPetAppearance()
        val guitarist = WorkshopCatalog.resolve(
            WorkshopCatalog.defaultSelection().copy(petEnabled = true, petId = "aster-guitarist"),
        ).toDesktopPetAppearance()

        assertEquals(WorkshopPetVisual.IDOL_VOCALIST, vocalist.visual)
        assertEquals(WorkshopPetVisual.IDOL_GUITARIST, guitarist.visual)
        assertNotEquals(vocalist.theme.accent, guitarist.theme.accent)
        assertNotEquals(WorkshopPetVisual.CREATURE, vocalist.visual)
    }

    @Test
    fun `custom avatar is loaded only from the normalized avatar store`() {
        val directory = createTempDirectory("omnicode-avatar-map-")
        val source = directory.resolve("source.png")
        val image = BufferedImage(64, 96, BufferedImage.TYPE_INT_ARGB)
        assertTrue(ImageIO.write(image, "png", source.toFile()))
        val store = CustomPetAvatarStore(directory.resolve("stored/custom-idol.png"))
        store.importImage(source)

        val appearance = WorkshopCatalog.resolve(
            WorkshopCatalog.defaultSelection().copy(
                petEnabled = true,
                petId = WorkshopCatalog.CUSTOM_PET_ID,
            ),
        ).toDesktopPetAppearance(store)

        assertEquals(WorkshopPetVisual.CUSTOM_AVATAR, appearance.visual)
        assertEquals(64, appearance.customAvatar?.width)
        assertEquals(96, appearance.customAvatar?.height)
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

    @Test
    fun `workspace skin carries the complete theme palette instead of accent only`() {
        val resolved = WorkshopCatalog.resolve(
            WorkshopCatalog.defaultSelection().copy(themeId = "aurora-night"),
        )

        val colors = resolved.toWorkspaceColors()
        val palette = resolved.theme.palette

        assertEquals(workshopColor(palette.background), colors.background)
        assertEquals(workshopColor(palette.surface), colors.surface)
        assertEquals(workshopColor(palette.elevatedSurface), colors.elevatedSurface)
        assertEquals(workshopColor(palette.primaryText), colors.primaryText)
        assertEquals(workshopColor(palette.border), colors.border)
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
