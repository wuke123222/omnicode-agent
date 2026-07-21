package dev.omnicode.workshop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkshopCatalogTest {
    @Test
    fun `built-in catalog is stable unique and fully declarative`() {
        assertTrue(WorkshopCatalog.themes.size >= 4)
        assertTrue(WorkshopCatalog.pets.size >= 4)
        assertEquals(
            WorkshopCatalog.themes.size,
            WorkshopCatalog.themes.map(WorkshopTheme::id).toSet().size,
        )
        assertEquals(
            WorkshopCatalog.pets.size,
            WorkshopCatalog.pets.map(WorkshopPet::id).toSet().size,
        )
        assertNotNull(WorkshopCatalog.theme(WorkshopCatalog.DEFAULT_THEME_ID))
        assertNotNull(WorkshopCatalog.pet(WorkshopCatalog.DEFAULT_PET_ID))
        WorkshopCatalog.themes.forEach { theme ->
            assertTrue(theme.palette.allColors().all { it.matches(Regex("#[0-9A-Fa-f]{6,8}")) })
        }
        WorkshopCatalog.pets.forEach { pet ->
            assertTrue(pet.behavior.idleMessages.isNotEmpty())
            assertFalse(pet.behavior.idleMessages.any { '<' in it || '>' in it })
        }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> {
            (WorkshopCatalog.themes as MutableList<WorkshopTheme>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> {
            (WorkshopCatalog.pets.first().behavior.idleMessages as MutableList<String>).clear()
        }
    }

    @Test
    fun `catalog data rejects paths markup and non-colour payloads`() {
        val palette = validPalette()

        assertFailsWith<IllegalArgumentException> {
            WorkshopTheme(
                id = "../launch",
                displayName = "Unsafe",
                description = "Not accepted",
                appearance = WorkshopThemeAppearance.DARK,
                palette = palette,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkshopTheme(
                id = "safe-id",
                displayName = "<html>Unsafe",
                description = "Not accepted",
                appearance = WorkshopThemeAppearance.DARK,
                palette = palette,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkshopThemePalette(
                background = "javascript:alert(1)",
                surface = "#000000",
                elevatedSurface = "#000000",
                primaryText = "#FFFFFF",
                secondaryText = "#FFFFFF",
                accent = "#FFFFFF",
                accentText = "#000000",
                border = "#FFFFFF",
                success = "#00FF00",
                warning = "#FFFF00",
                error = "#FF0000",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkshopPetBehavior(
                motion = WorkshopPetMotion.BOB,
                idleIntervalSeconds = 30,
                idleMessages = listOf("<html><script>run</script>"),
            )
        }
    }

    @Test
    fun `selection resolves only against built-in entries`() {
        val untrusted = WorkshopSelection(
            themeId = "../../theme",
            petId = "command-shell",
            petEnabled = true,
        )

        val resolved = WorkshopCatalog.resolve(untrusted)

        assertEquals(WorkshopCatalog.DEFAULT_THEME_ID, resolved.selection.themeId)
        assertEquals(WorkshopCatalog.DEFAULT_PET_ID, resolved.selection.petId)
        assertEquals(WorkshopCatalog.DEFAULT_THEME_ID, resolved.theme.id)
        assertFalse(resolved.selection.petEnabled)
        assertNull(resolved.pet)
        assertFailsWith<IllegalArgumentException> { WorkshopCatalog.requireKnown(untrusted) }
    }

    @Test
    fun `disabled pet retains choice without resolving a live pet`() {
        val selection = WorkshopCatalog.defaultSelection().copy(petEnabled = false)

        val resolved = WorkshopCatalog.resolve(selection)

        assertNull(resolved.pet)
        assertEquals(WorkshopCatalog.DEFAULT_PET_ID, resolved.selection.petId)
    }

    @Test
    fun `graphite and aurora accent text pairs meet three to one contrast`() {
        listOf("graphite-night", "aurora-night").forEach { themeId ->
            val palette = requireNotNull(WorkshopCatalog.theme(themeId)).palette
            val ratio = contrastRatio(palette.accent, palette.accentText)

            assertTrue(ratio >= 3.0, "$themeId accent contrast was $ratio")
        }
    }

    private fun validPalette(): WorkshopThemePalette = WorkshopThemePalette(
        background = "#000000",
        surface = "#111111",
        elevatedSurface = "#222222",
        primaryText = "#FFFFFF",
        secondaryText = "#CCCCCC",
        accent = "#3366FF",
        accentText = "#FFFFFF",
        border = "#333333",
        success = "#00AA66",
        warning = "#DDAA00",
        error = "#DD3344",
    )

    private fun contrastRatio(first: String, second: String): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(hex: String): Double {
        val rgb = hex.removePrefix("#").take(6).chunked(2).map { channel ->
            val value = channel.toInt(16) / 255.0
            if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    }
}
