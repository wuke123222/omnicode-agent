package dev.omnicode.ui

import dev.omnicode.model.AttachmentKind
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AttachmentIntakeTest {
    @Test
    fun `supported attachment names cover images markdown and bounded text`() {
        listOf(
            "a.png", "a.JPG", "a.jpeg", "a.webp", "a.gif", "a.md", "a.markdown", "a.mdown", "a.mkdn",
            "paper.tex", "references.bib", "analysis.ipynb", "stats.R", "model.jl", "matrix.m", "page.html",
            "style.css", "theme.scss", "App.vue", "main.dart", "tool.rb", "index.php", "script.lua", "api.proto",
            "paper.pdf", "Dockerfile", "Makefile", "CMakeLists.txt", ".env.example", ".env.sample", ".env.template", ".env.dist",
        )
            .forEach { assertTrue(AttachmentIntake.supportsFileName(it), it) }
        assertTrue(AttachmentIntake.supportsFileName("build.log"))
        assertTrue(AttachmentIntake.supportsFileName("request.json"))
        assertTrue(AttachmentIntake.supportsFileName("Example.kt"))
        assertFalse(AttachmentIntake.supportsFileName("no-extension"))
        listOf(".env", ".env.local", ".env.production", "production.env")
            .forEach { assertFalse(AttachmentIntake.supportsFileName(it), it) }
    }

    @Test
    fun `text logs remain bounded readable attachments`() {
        val log = Files.createTempFile("omnicode-build", ".log")
        try {
            Files.writeString(log, "ERROR failed to compile\n")
            val accepted = assertIs<AttachmentIntakeResult.Accepted>(AttachmentIntake.read(log)).attachment

            assertEquals(AttachmentKind.TEXT, accepted.kind)
            assertEquals("text/plain", accepted.mediaType)
            assertTrue(accepted.content.contains("failed to compile"))
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @Test
    fun `image input is bounded and encoded without changing its metadata`() {
        val file = Files.createTempFile("omnicode-image", ".png")
        try {
            ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
            val result = assertIs<AttachmentIntakeResult.Accepted>(AttachmentIntake.read(file))

            assertEquals(AttachmentKind.IMAGE, result.attachment.kind)
            assertEquals("image/png", result.attachment.mediaType)
            assertTrue(result.attachment.byteSize > 0)
            assertTrue(result.attachment.content.isNotBlank())
            val preview = AttachmentPreviewCache.find(result.attachment)
            assertEquals(ImageDimensions(3, 2), preview?.dimensions)
            assertTrue(preview?.thumbnail != null)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `markdown remains local UTF8 text and malformed PDF is rejected`() {
        val markdown = Files.createTempFile("omnicode-notes", ".md")
        val binary = Files.createTempFile("omnicode-notes", ".pdf")
        try {
            Files.writeString(markdown, "# Notes\n\nInspect this change.")
            val accepted = assertIs<AttachmentIntakeResult.Accepted>(AttachmentIntake.read(markdown))
            assertEquals(AttachmentKind.MARKDOWN, accepted.attachment.kind)
            assertTrue(accepted.attachment.content.contains("Inspect this change."))

            val rejected = assertIs<AttachmentIntakeResult.Rejected>(AttachmentIntake.read(binary))
            assertTrue(rejected.message.contains("有效的 PDF"))
        } finally {
            Files.deleteIfExists(markdown)
            Files.deleteIfExists(binary)
        }
    }

    @Test
    fun `markdown and text reject NUL and excessive control characters`() {
        val markdown = Files.createTempFile("omnicode-control", ".md")
        val text = Files.createTempFile("omnicode-control", ".txt")
        try {
            Files.writeString(markdown, "# heading\u0000hidden")
            Files.writeString(text, "visible\u0001\u0002\u0003")

            val markdownResult = assertIs<AttachmentIntakeResult.Rejected>(AttachmentIntake.read(markdown))
            val textResult = assertIs<AttachmentIntakeResult.Rejected>(AttachmentIntake.read(text))

            assertTrue(markdownResult.message.contains("控制字符"))
            assertTrue(textResult.message.contains("控制字符"))
        } finally {
            Files.deleteIfExists(markdown)
            Files.deleteIfExists(text)
        }
    }

    @Test
    fun `notebook intake stores only extracted source cells and preserves raw byte size`() {
        val notebook = Files.createTempFile("omnicode-research", ".ipynb")
        try {
            val raw = """
                {"cells":[
                  {"cell_type":"markdown","source":["# Result\n","Discussion"]},
                  {"cell_type":"code","source":"x = 42","outputs":[{"text":"do not include"}]}
                ],"metadata":{"private":"do not include"}}
            """.trimIndent()
            Files.writeString(notebook, raw)

            val attachment = assertIs<AttachmentIntakeResult.Accepted>(AttachmentIntake.read(notebook)).attachment

            assertEquals("application/x-ipynb+json", attachment.mediaType)
            assertEquals(raw.encodeToByteArray().size.toLong(), attachment.byteSize)
            assertTrue(attachment.content.contains("Notebook cell 1 · Markdown"))
            assertTrue(attachment.content.contains("Notebook cell 2 · Code"))
            assertFalse(attachment.content.contains("do not include"))
        } finally {
            Files.deleteIfExists(notebook)
        }
    }

    @Test
    fun `notebook raw size is capped before parsing`() {
        val notebook = Files.createTempFile("omnicode-large", ".ipynb")
        try {
            Files.write(notebook, ByteArray((MAX_NOTEBOOK_BYTES + 1).toInt()) { ' '.code.toByte() })

            val rejected = assertIs<AttachmentIntakeResult.Rejected>(AttachmentIntake.read(notebook))

            assertTrue(rejected.message.contains("Jupyter Notebook"))
            assertTrue(rejected.message.contains("2.0 MB"))
        } finally {
            Files.deleteIfExists(notebook)
        }
    }

    @Test
    fun `batch intake keeps drop order fills slots after rejection and reports overflow`() {
        val directory = Files.createTempDirectory("omnicode-drop")
        val malformedPdf = directory.resolve("first.pdf")
        val markdown = directory.resolve("second.md")
        val image = directory.resolve("third.png")
        val overflow = directory.resolve("fourth.md")
        try {
            Files.write(malformedPdf, byteArrayOf(1))
            Files.writeString(markdown, "# Second")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
            Files.writeString(overflow, "# Fourth")

            val result = AttachmentBatchIntake.read(
                listOf(malformedPdf, markdown, image, overflow),
                availableSlots = 2,
            )

            assertEquals(listOf("second.md", "third.png"), result.accepted.map { it.attachment.fileName })
            assertEquals(listOf("first.pdf"), result.rejected.map { it.fileName })
            assertEquals(1, result.omittedByLimit)
            assertEquals("已添加 2 个附件；2 个未添加", attachmentBatchStatus(
                result.accepted.map { it.attachment.fileName },
                result.rejected.size + result.omittedByLimit,
            ))
        } finally {
            Files.deleteIfExists(overflow)
            Files.deleteIfExists(image)
            Files.deleteIfExists(markdown)
            Files.deleteIfExists(malformedPdf)
            Files.deleteIfExists(directory)
        }
    }
}
