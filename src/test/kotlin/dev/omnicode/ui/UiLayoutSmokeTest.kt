package dev.omnicode.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deterministic UI smoke coverage for the layouts most likely to regress in a narrow Tool Window.
 * This deliberately stays below Remote Robot: it catches geometry regressions without requiring a
 * display server, while the release matrix still calls out real click/screenshot coverage.
 */
class UiLayoutSmokeTest {
    @Test
    fun `composer card remains inside bounds at supported widths`() = onEdt {
        listOf(320, 480, 720).forEach { width ->
            val card = RoundedSurfacePanel(
                fillColor = OmniCodeUiPalette.surface,
                outlineColor = OmniCodeUiPalette.border,
            ).apply {
                layout = BorderLayout(JBUI.scale(8), JBUI.scale(6))
                border = JBUI.Borders.empty(JBUI.scale(8))
                add(JPanel().apply {
                    isOpaque = false
                    preferredSize = Dimension(1, JBUI.scale(56))
                }, BorderLayout.CENTER)
                add(WrappingActionPanel().apply {
                    add(composerControlButton("Agent", "切换 Agent 模式"))
                    add(composerControlButton("Team", "切换 Team 模式"))
                    add(composerControlButton("发送", "发送任务", ComposerControlState.SELECTED))
                }, BorderLayout.SOUTH)
            }
            card.setSize(width, 180)
            layoutTree(card)

            assertWithinParent(card)
            assertNoSiblingOverlap(card)
            val image = render(card)
            assertTrue(nonBackgroundPixels(image) > 0, "width=$width should render visible surface")
        }
    }

    @Test
    fun `attachment chip keeps remove action visible in narrow layout`() = onEdt {
        val attachment = dev.omnicode.model.UserAttachment(
            fileName = "research-notes.md",
            kind = dev.omnicode.model.AttachmentKind.MARKDOWN,
            mediaType = "text/markdown",
            byteSize = 128,
            content = "# notes",
        )
        val chip = AttachmentChip(attachment) {}
        chip.setSize(260, 72)
        layoutTree(chip)

        assertWithinParent(chip)
        val action = allDescendants(chip)
            .filterIsInstance<javax.swing.JButton>()
            .single { it.text == "×" }
        assertTrue(action.isVisible)
        assertTrue(action.bounds.width > 0 && action.bounds.height > 0)
        assertTrue(action.x + action.width <= chip.width)
        assertTrue(action.y + action.height <= chip.height)
    }

    private fun layoutTree(component: JComponent) {
        component.doLayout()
        component.components.filterIsInstance<JComponent>().forEach(::layoutTree)
    }

    private fun assertWithinParent(parent: JComponent) {
        parent.components.filterIsInstance<JComponent>().forEach { child ->
            assertTrue(child.x >= 0 && child.y >= 0, "${child.javaClass.simpleName} starts outside parent")
            assertTrue(child.x + child.width <= parent.width, "${child.javaClass.simpleName} exceeds parent width")
            assertTrue(child.y + child.height <= parent.height, "${child.javaClass.simpleName} exceeds parent height")
            assertWithinParent(child)
        }
    }

    private fun assertNoSiblingOverlap(parent: JComponent) {
        val children = parent.components.filter { it.isVisible && it.width > 0 && it.height > 0 }
        children.indices.forEach { index ->
            ((index + 1) until children.size).forEach { other ->
                assertTrue(
                    !children[index].bounds.intersects(children[other].bounds),
                    "${children[index].javaClass.simpleName} overlaps ${children[other].javaClass.simpleName}",
                )
            }
        }
        children.filterIsInstance<JComponent>().forEach(::assertNoSiblingOverlap)
    }

    private fun render(component: JComponent): BufferedImage {
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun allDescendants(component: JComponent): Sequence<java.awt.Component> = sequence {
        for (child in component.components) {
            yield(child)
            if (child is JComponent) yieldAll(allDescendants(child))
        }
    }

    private fun nonBackgroundPixels(image: BufferedImage): Int {
        val background = Color(image.getRGB(0, 0), true)
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (Color(image.getRGB(x, y), true) != background) count++
            }
        }
        return count
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
