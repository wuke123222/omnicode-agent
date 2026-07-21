package dev.omnicode.ui

import com.intellij.openapi.ui.DialogWrapper
import dev.omnicode.persistence.SensitiveDataRedactor
import dev.omnicode.tool.ApprovalDiff
import dev.omnicode.tool.ApprovalRequest
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractAction
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModalApprovalGateTest {
    @Test
    fun `enter defaults to rejection and never applies a diff`() {
        val applyClicks = AtomicInteger()
        val rejectClicks = AtomicInteger()
        val applyAction = countingAction(DIFF_APPLY_ACTION_LABEL, applyClicks).apply {
            putValue(DialogWrapper.DEFAULT_ACTION, true)
            putValue(DialogWrapper.FOCUSED_ACTION, true)
        }
        val rejectAction = countingAction(DIFF_REJECT_ACTION_LABEL, rejectClicks)

        configureExplicitDiffApprovalActions(applyAction, rejectAction)

        assertNull(applyAction.getValue(DialogWrapper.DEFAULT_ACTION))
        assertNull(applyAction.getValue(DialogWrapper.FOCUSED_ACTION))
        assertEquals(true, rejectAction.getValue(DialogWrapper.DEFAULT_ACTION))
        assertEquals(true, rejectAction.getValue(DialogWrapper.FOCUSED_ACTION))

        val rootPane = JRootPane()
        DialogWrapper.createJButtonForAction(applyAction, rootPane)
        val rejectButton = DialogWrapper.createJButtonForAction(rejectAction, rootPane)
        assertSame(rejectButton, rootPane.defaultButton)

        // Swing routes Enter to the root pane's default button.
        rootPane.defaultButton.doClick()
        assertEquals(0, applyClicks.get())
        assertEquals(1, rejectClicks.get())
    }

    @Test
    fun `only the explicit apply exit code grants diff approval`() {
        assertTrue(isExplicitDiffApproval(DialogWrapper.OK_EXIT_CODE))
        assertFalse(isExplicitDiffApproval(DialogWrapper.CANCEL_EXIT_CODE))
        assertFalse(isExplicitDiffApproval(DialogWrapper.CLOSE_EXIT_CODE))
        assertFalse(isExplicitDiffApproval(DialogWrapper.NEXT_USER_EXIT_CODE))
    }

    @Test
    fun `diff approval summary exposes tool target risk and details after redaction`() {
        val summary = diffApprovalSummary(
            ApprovalRequest(
                toolName = "apply_change secret-value",
                title = "Modify secret-value",
                details = "File summary contains secret-value",
                risk = "Credential secret-value would be written",
                diff = ApprovalDiff(
                    path = "src/secret-value/App.kt",
                    before = "secret-value remains visible in the local diff",
                    after = "updated secret-value remains visible in the local diff",
                ),
            ),
            SensitiveDataRedactor { it.replace("secret-value", "[REDACTED]") },
        )

        assertEquals("Modify [REDACTED]", summary.title)
        assertEquals("apply_change [REDACTED]", summary.tool)
        assertEquals("src/[REDACTED]/App.kt", summary.target)
        assertEquals("Credential [REDACTED] would be written", summary.risk)
        assertEquals("File summary contains [REDACTED]", summary.details)
    }

    @Test
    fun `approval actions use explicit non-ambiguous labels`() {
        assertEquals("仅本次应用", DIFF_APPLY_ACTION_LABEL)
        assertEquals("拒绝", DIFF_REJECT_ACTION_LABEL)
    }

    private fun countingAction(label: String, counter: AtomicInteger) = object : AbstractAction(label) {
        override fun actionPerformed(event: ActionEvent?) {
            counter.incrementAndGet()
        }
    }
}
