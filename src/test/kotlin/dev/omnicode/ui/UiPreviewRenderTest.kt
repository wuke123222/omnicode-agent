package dev.omnicode.ui

import dev.omnicode.review.TaskChangeDecision
import dev.omnicode.review.TaskChangeHunk
import dev.omnicode.review.TaskChangedFile
import dev.omnicode.agent.AgentMode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders representative conversation surfaces off-screen into PNG files under build/ui-preview
 * so visual
 * changes can be reviewed as real pixels instead of guessed from code. The assertions only check
 * that rendering produced non-trivial images; the PNGs are the actual review artifact.
 */
class UiPreviewRenderTest {
    @Test
    fun `renders a representative conversation preview`() {
        var producedSize = 0L
        javax.swing.SwingUtilities.invokeAndWait { producedSize = renderConversationPreview() }
        assertTrue(producedSize > 10_000, "conversation preview should not be blank")
    }

    /** Runs on the EDT: Swing text layout is not safe off the event thread. */
    private fun renderConversationPreview(): Long {
        val canvas = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = OmniCodeUiPalette.canvas
            border = javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16)
        }

        canvas.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(UserMessageCard("为啥 CLI 一直卡在“正在请求模型”？帮我修一下"), BorderLayout.EAST)
        })
        canvas.add(javax.swing.Box.createVerticalStrut(12))

        val turn = AssistantTurnPanel(mode = AgentMode.AGENT)
        turn.alignmentX = Component.LEFT_ALIGNMENT
        turn.appendText(
            "先确认根因：CLI 子进程的 stdin 没有关闭，导致 opencode 一直等待输入。\n\n" +
                "## 修复方案\n" +
                "- 启动后立即关闭子进程 stdin\n" +
                "- 超时与取消销毁整棵进程树\n\n" +
                "```kotlin\n" +
                "val process = builder.start()\n" +
                "runCatching { process.outputStream.close() }\n" +
                "```\n" +
                "以上改动已经覆盖回归测试。",
        )
        turn.startTool("run_command", "./gradlew test", callId = "tool-1")
        turn.completeTool("run_command", "BUILD SUCCESSFUL in 14s", isError = false, callId = "tool-1")
        turn.showChangeSummary(
            files = listOf(sampleChangedFile()),
            onReview = {},
            onCompare = {},
        )
        turn.finish("✓  已完成", isError = false)
        canvas.add(turn)

        val image = renderComponent(canvas, width = 860)
        val output = writePng(image, "conversation")
        return Files.size(output)
    }

    private fun sampleChangedFile(): TaskChangedFile {
        val hunk = TaskChangeHunk(
            id = "h1",
            beforeStartLine = 12,
            beforeLineCount = 2,
            afterStartLine = 12,
            afterLineCount = 3,
            beforeText = "val timeout = connection.requestTimeoutSeconds\nwithTimeout(timeout) { execute() }",
            afterText = "val timeout = connection.requestTimeoutSeconds\nval watchdog = launch { delay(timeout) }\nrunInterruptible { execute() }",
            decision = TaskChangeDecision.PENDING,
        )
        return TaskChangedFile(
            relativePath = "src/main/kotlin/dev/omnicode/provider/CliToolProvider.kt",
            beforeContent = hunk.beforeText,
            afterContent = hunk.afterText,
            expectedCurrentContent = hunk.afterText,
            beforeSha256 = "a",
            afterSha256 = "b",
            expectedCurrentSha256 = "b",
            decision = TaskChangeDecision.PENDING,
            hunks = listOf(hunk),
        )
    }

    companion object {
        fun renderComponent(component: JComponent, width: Int): BufferedImage {
            component.setSize(width, 4_000)
            layoutTree(component)
            layoutTree(component)
            val height = component.preferredSize.height.coerceIn(80, 4_000)
            component.setSize(width, height)
            layoutTree(component)
            layoutTree(component)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(
                    java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )
                component.paint(graphics)
            } finally {
                graphics.dispose()
            }
            return image
        }

        fun writePng(image: BufferedImage, name: String): Path {
            val directory = Path.of("build", "ui-preview")
            Files.createDirectories(directory)
            val output = directory.resolve("$name.png")
            ImageIO.write(image, "png", output.toFile())
            return output
        }

        private fun layoutTree(component: Component) {
            component.doLayout()
            if (component is Container) {
                component.components.forEach(::layoutTree)
            }
        }
    }
}
