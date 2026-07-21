package dev.omnicode.ui.workshop

import dev.omnicode.workshop.WorkshopCatalog
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertNotEquals

class WorkshopLafResolutionTest {
    @Test
    fun `native pet appearance is re-resolved from current laf colors`() = onEdt {
        val defaults = UIManager.getDefaults()
        val original = defaults[PANEL_BACKGROUND_KEY]
        val selection = WorkshopCatalog.defaultSelection().copy(petEnabled = true)
        try {
            defaults[PANEL_BACKGROUND_KEY] = Color(0x18, 0x19, 0x1C)
            val darkBody = WorkshopCatalog.resolve(selection).toDesktopPetAppearance().theme.body

            defaults[PANEL_BACKGROUND_KEY] = Color(0xF7, 0xF8, 0xFA)
            val lightBody = WorkshopCatalog.resolve(selection).toDesktopPetAppearance().theme.body

            assertNotEquals(darkBody, lightBody)
        } finally {
            if (original == null) defaults.remove(PANEL_BACKGROUND_KEY) else defaults[PANEL_BACKGROUND_KEY] = original
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    private companion object {
        const val PANEL_BACKGROUND_KEY = "Panel.background"
    }
}
