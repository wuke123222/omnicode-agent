package dev.omnicode.ui

import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.Scrollable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponsiveUiLayoutsTest {
    @Test
    fun `viewport content always tracks width but remains vertically scrollable`() {
        val panel = ViewportWidthPanel()
        val scrollable = panel as Scrollable

        assertTrue(scrollable.scrollableTracksViewportWidth)
        assertFalse(scrollable.scrollableTracksViewportHeight)
    }

    @Test
    fun `wrapping action panel reports the second row at narrow widths`() {
        val parent = JPanel(BorderLayout())
        val actions = WrappingActionPanel()
        repeat(3) {
            actions.add(JPanel().apply {
                preferredSize = Dimension(120, 24)
                minimumSize = preferredSize
            })
        }
        parent.add(actions, BorderLayout.SOUTH)

        parent.setSize(450, 200)
        val wideHeight = actions.preferredSize.height
        parent.setSize(300, 200)
        val narrowHeight = actions.preferredSize.height

        assertTrue(narrowHeight >= 24 * 2, "narrow actions must reserve at least two rows")
        assertTrue(narrowHeight > wideHeight, "300 px must report more height than 450 px")
    }

    @Test
    fun `wrapping height follows parent width instead of stale child bounds`() {
        val parent = JPanel(BorderLayout())
        val actions = WrappingActionPanel()
        repeat(4) {
            actions.add(JPanel().apply { preferredSize = Dimension(90, 22) })
        }
        parent.add(actions, BorderLayout.SOUTH)
        actions.setSize(600, 22)

        parent.setSize(320, 160)

        assertTrue(actions.preferredSize.height > 22)
    }

    @Test
    fun `bounded tooltip escapes markup and caps model supplied text`() {
        val tooltip = boundedTooltipHtml("<unsafe>\n" + "x".repeat(100), maxCharacters = 20).orEmpty()

        assertTrue("&lt;unsafe&gt;" in tooltip)
        assertTrue("工具提示已截断" in tooltip)
        assertFalse("<unsafe>" in tooltip)
    }

    @Test
    fun `diagnostics labels distinguish presence checks from local capability guesses`() {
        assertTrue("凭据是否存在" in DIAGNOSTICS_SCOPE_DESCRIPTION)
        assertTrue("不验证 API Key 有效性" in DIAGNOSTICS_SCOPE_DESCRIPTION)
        assertTrue("本地推测" in DIAGNOSTICS_SCOPE_DESCRIPTION)
        assertTrue("存在性" in diagnosticsDisplayTitle("provider.credentials", "fallback"))
        assertTrue("本地推测" in diagnosticsDisplayTitle("model.tools", "fallback"))
        assertTrue("本地推测" in diagnosticsDisplayTitle("model.vision", "fallback"))
    }

    @Test
    fun `external diagnostics refresh is suppressed while a run owns the presentation`() {
        assertFalse(shouldRenderDiagnosticsRefresh(running = true))
        assertTrue(shouldRenderDiagnosticsRefresh(running = false))
    }

    @Test
    fun `task center polls only while visible and explains blocked actions`() {
        assertTrue(taskCenterShouldPoll(isShowing = true, disposed = false))
        assertFalse(taskCenterShouldPoll(isShowing = false, disposed = false))
        assertFalse(taskCenterShouldPoll(isShowing = true, disposed = true))
        assertTrue(TASK_CENTER_REFRESH_MILLIS == 2_000)
        assertTrue("任务正在运行" in TASK_ACTIONS_RUNNING_TOOLTIP)
    }
}
