package dev.omnicode.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import javax.swing.SwingConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerControlStyleTest {
    @Test
    fun `all semantic states share composer geometry and baseline`() {
        val styles = ComposerControlState.entries.map(::composerControlStyle)

        assertEquals(setOf(32), styles.map { it.logicalHeight }.toSet())
        assertEquals(setOf(9), styles.map { it.horizontalPadding }.toSet())
        assertEquals(setOf(10), styles.map { it.cornerArc }.toSet())

        val button = composerControlButton("Plan", state = ComposerControlState.SELECTED)
        assertEquals(JBUI.scale(32), button.preferredSize.height)
        assertEquals(JBUI.scale(9), button.insets.left)
        assertEquals(JBUI.scale(9), button.insets.right)
        assertEquals(JBFont.small().size, button.font.size)
        assertEquals(SwingConstants.CENTER, button.verticalAlignment)
        assertEquals(SwingConstants.CENTER, button.verticalTextPosition)
    }

    @Test
    fun `quiet selected and warning states expose distinct restrained semantics`() {
        val quiet = composerControlStyle(ComposerControlState.QUIET)
        val selected = composerControlStyle(ComposerControlState.SELECTED)
        val warning = composerControlStyle(ComposerControlState.WARNING)

        assertFalse(quiet.paintsBackgroundAtRest)
        assertFalse(quiet.paintsOutlineAtRest)
        assertTrue(selected.paintsBackgroundAtRest)
        assertFalse(selected.paintsOutlineAtRest)
        assertEquals(ComposerControlForeground.PRIMARY, selected.foreground)
        assertTrue(warning.paintsBackgroundAtRest)
        assertTrue(warning.paintsOutlineAtRest)
        assertEquals(ComposerControlForeground.WARNING, warning.foreground)
    }

    @Test
    fun `button can change semantic state without changing its geometry`() {
        val button = composerControlButton("danger-full-access", "沙箱模式")
        val initialSize = button.preferredSize

        button.controlState = ComposerControlState.WARNING

        assertEquals(ComposerControlState.WARNING, button.controlState)
        assertEquals(initialSize.height, button.preferredSize.height)
        assertEquals("沙箱模式", button.getAccessibleContext().accessibleName)
    }

    @Test
    fun `icon only composer controls stay square instead of inheriting platform minimum width`() {
        val button = composerControlButton("").apply { icon = AllIcons.General.GearPlain }

        assertEquals(JBUI.scale(32), button.preferredSize.width)
        assertEquals(JBUI.scale(32), button.preferredSize.height)
    }
}
