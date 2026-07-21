package dev.omnicode.ui.workshop

import java.awt.Color
import java.awt.image.BufferedImage
import dev.omnicode.workshop.WorkshopPetVisual
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopPetPanelTest {
    @Test
    fun `all workflow states have stable accessible labels`() {
        assertEquals(
            listOf("待命", "思考中", "正在使用工具", "任务完成", "任务出错"),
            DesktopPetState.entries.map(DesktopPetState::accessibleLabel),
        )
    }

    @Test
    fun `appearance data controls geometry and colors`() = onEdt {
        val shape = DesktopPetShape(preferredWidth = 148, preferredHeight = 126, earHeight = 0)
        val theme = DesktopPetTheme.defaults().copy(accent = Color.MAGENTA)
        val panel = DesktopPetPanel(initialAppearance = DesktopPetAppearance(theme, shape))

        assertEquals(148, panel.preferredSize.width)
        assertEquals(126, panel.preferredSize.height)
        assertEquals(Color.MAGENTA, panel.appearance.theme.accent)

        panel.appearance = panel.appearance.copy(shape = shape.copy(preferredWidth = 156))
        assertEquals(156, panel.preferredSize.width)
        panel.dispose()
    }

    @Test
    fun `invalid shape data fails before painting`() {
        assertFailsWith<IllegalArgumentException> { DesktopPetShape(preferredWidth = 60) }
        assertFailsWith<IllegalArgumentException> { DesktopPetShape(earHeight = 40) }
        assertFailsWith<IllegalArgumentException> { DesktopPetShape(eyeSize = 1) }
    }

    @Test
    fun `virtual idol and missing custom avatar paint safely`() = onEdt {
        listOf(
            WorkshopPetVisual.IDOL_VOCALIST,
            WorkshopPetVisual.IDOL_GUITARIST,
            WorkshopPetVisual.CUSTOM_AVATAR,
        ).forEach { visual ->
            val panel = DesktopPetPanel(
                initialAppearance = DesktopPetAppearance(visual = visual),
            )
            panel.setSize(panel.preferredSize)
            val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { graphics ->
                try {
                    panel.paint(graphics)
                } finally {
                    graphics.dispose()
                }
            }
            assertTrue(image.getRGB(panel.width / 2, panel.height / 2) != 0)
            panel.dispose()
        }
    }

    @Test
    fun `floating pet never captures transcript pointer events`() = onEdt {
        val panel = DesktopPetPanel()
        panel.setSize(panel.preferredSize)

        assertFalse(panel.contains(panel.width / 2, panel.height / 2))
        panel.dispose()
    }

    @Test
    fun `enable state updates accessibility and disabled pet paints safely`() = onEdt {
        val panel = DesktopPetPanel(initialState = DesktopPetState.TOOL)
        panel.setSize(panel.preferredSize)
        panel.isPetEnabled = false

        assertTrue(panel.toolTipText.contains("关闭"))
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        assertEquals(0, image.getRGB(panel.width / 2, panel.height / 2))
        panel.dispose()
    }

    @Test
    fun `timer follows hierarchy state enablement and disposal without restarting`() = onEdt {
        val root = JPanel()
        val panel = DesktopPetPanel(initialState = DesktopPetState.THINKING)
        root.add(panel)

        root.addNotify()
        try {
            assertTrue(panel.isShowing)
            assertTrue(panel.isAnimationRunning)

            panel.isPetEnabled = false
            assertFalse(panel.isAnimationRunning)

            panel.isPetEnabled = true
            assertTrue(panel.isAnimationRunning)

            panel.state = DesktopPetState.SUCCESS
            assertFalse(panel.isAnimationRunning)

            panel.state = DesktopPetState.TOOL
            assertTrue(panel.isAnimationRunning)

            panel.dispose()
            assertTrue(panel.isDisposed)
            assertFalse(panel.isAnimationRunning)

            panel.state = DesktopPetState.THINKING
            panel.isPetEnabled = false
            panel.isPetEnabled = true
            assertFalse(panel.isAnimationRunning)
        } finally {
            root.removeNotify()
            panel.dispose()
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }
}
