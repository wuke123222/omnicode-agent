package dev.omnicode.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.omnicode.service.CommitAiResult
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel

internal class CommitMessageDialog(
    project: Project,
    private val result: CommitAiResult,
) : DialogWrapper(project, true) {
    private val message = JBTextArea(result.text).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        caretPosition = 0
    }

    init {
        title = "Generate Commit Message"
        setOKButtonText("Copy to Clipboard")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        preferredSize = Dimension(JBUI.scale(620), JBUI.scale(330))
        add(JBLabel(
            "${result.provider} · ${result.model} · " +
                "${result.usage.inputTokens} input / ${result.usage.outputTokens} output tokens",
        ).apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
        }, BorderLayout.NORTH)
        add(JBScrollPane(message), BorderLayout.CENTER)
        add(JBLabel("Review and edit the message before copying it into the Commit tool window.").apply {
            foreground = OmniCodeUiPalette.secondary
            font = JBFont.small()
        }, BorderLayout.SOUTH)
    }

    override fun doOKAction() {
        val value = message.text.trim()
        if (value.isBlank()) {
            setErrorText("Commit message must not be blank.", message)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(value))
        super.doOKAction()
    }
}
