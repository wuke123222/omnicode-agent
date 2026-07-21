package dev.omnicode.ui.workshop

import dev.omnicode.workshop.ResolvedWorkshopSelection
import dev.omnicode.workshop.WorkshopCatalog
import dev.omnicode.workshop.WorkshopSettingsService
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreativeWorkshopPanelTest {
    @Test
    fun `workshop renders its catalog and emits an initial safe selection`() = onEdt {
        var observed: ResolvedWorkshopSelection? = null
        val panel = CreativeWorkshopPanel(
            onSelectionChanged = { observed = it },
            settings = WorkshopSettingsService(),
        )
        panel.setSize(760, 900)
        panel.doLayout()

        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.printAll(graphics)
        } finally {
            graphics.dispose()
            panel.dispose()
        }

        assertNotNull(observed)
        assertTrue(image.getRGB(panel.width / 2, panel.height / 2) != 0)
    }

    @Test
    fun `disabled selected pet keeps its own appearance in preview`() = onEdt {
        val settings = WorkshopSettingsService().apply {
            selectPet("tiny-robot")
            setPetEnabled(false)
        }
        val panel = CreativeWorkshopPanel(onSelectionChanged = {}, settings = settings)
        try {
            val preview = descendants(panel).filterIsInstance<DesktopPetPanel>().single()
            val expectedRobot = WorkshopCatalog.resolve(
                settings.snapshot().copy(petEnabled = true),
            ).toDesktopPetAppearance()
            val defaultCat = WorkshopCatalog.resolve(
                WorkshopCatalog.defaultSelection().copy(petEnabled = true),
            ).toDesktopPetAppearance()

            assertEquals(expectedRobot, preview.appearance)
            assertNotEquals(defaultCat.shape, preview.appearance.shape)
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `theme grid maximum height follows all catalog rows`() = onEdt {
        val panel = CreativeWorkshopPanel(onSelectionChanged = {}, settings = WorkshopSettingsService())
        try {
            val themeGrid = descendants(panel)
                .filterIsInstance<JPanel>()
                .single { it.layout is GridLayout && it.componentCount == WorkshopCatalog.themes.size }

            assertEquals(themeGrid.preferredSize.height, themeGrid.maximumSize.height)
            assertTrue(themeGrid.maximumSize.height > 226)
        } finally {
            panel.dispose()
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    private fun descendants(root: Container): List<Component> = buildList {
        root.components.forEach { child ->
            add(child)
            if (child is Container) addAll(descendants(child))
        }
    }
}
