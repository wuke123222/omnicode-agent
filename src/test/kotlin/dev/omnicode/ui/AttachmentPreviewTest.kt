package dev.omnicode.ui

import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.UserAttachment
import dev.omnicode.service.ResearchCitationValidator
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentPreviewTest {
    @Test
    fun `image header inspection reads dimensions without decoding full pixels`() {
        assertEquals(ImageDimensions(640, 480), imageDimensions(pngHeader(640, 480), "image/png"))
        assertEquals(ImageDimensions(320, 200), imageDimensions(gifHeader(320, 200), "image/gif"))
        assertEquals(ImageDimensions(1024, 768), imageDimensions(webpExtendedHeader(1024, 768), "image/webp"))
        assertNull(imageDimensions(byteArrayOf(1, 2, 3), "image/png"))
    }

    @Test
    fun `WebP requires complete RIFF and chunk bounds and reports unavailable preview`() {
        val completeHeader = webpExtendedHeader(1024, 768)
        val truncated = completeHeader.copyOf().apply { putLittleEndianInt(this, 4, 10_000) }

        assertNull(imageDimensions(truncated, "image/webp"))
        val invalid = assertIs<ImageAttachmentInspection.Invalid>(
            inspectImageAttachment(completeHeader, "image/webp"),
        )
        assertTrue(invalid.message.contains("WebP"))
        assertTrue(invalid.message.contains("预览"))
    }

    @Test
    fun `compressed image dimensions are rejected before thumbnail decode`() {
        val inspection = inspectImageAttachment(
            pngHeader(MAX_ATTACHMENT_IMAGE_EDGE, MAX_ATTACHMENT_IMAGE_EDGE),
            "image/png",
        )

        val invalid = assertIs<ImageAttachmentInspection.Invalid>(inspection)
        assertTrue(invalid.message.contains("2500 万像素"))
    }

    @Test
    fun `plausible header without image payload is rejected as corrupt`() {
        val inspection = inspectImageAttachment(pngHeader(10, 10), "image/png")

        val invalid = assertIs<ImageAttachmentInspection.Invalid>(inspection)
        assertTrue(invalid.message.contains("无法安全解码"))
    }

    @Test
    fun `valid image thumbnail is safely subsampled to UI bounds`() {
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }

        val preview = assertIs<ImageAttachmentInspection.Valid>(
            inspectImageAttachment(bytes, "image/png"),
        ).preview

        assertEquals(ImageDimensions(800, 600), preview.dimensions)
        assertTrue(requireNotNull(preview.thumbnail).width <= 72)
        assertTrue(requireNotNull(preview.thumbnail).height <= 52)
    }

    @Test
    fun `text preview is bounded by both characters and lines`() {
        val preview = boundedAttachmentPreview(
            (1..200).joinToString("\n") { "line-$it-" + "x".repeat(30) },
            maxChars = 120,
            maxLines = 3,
        )

        assertTrue(preview.truncated)
        assertTrue(preview.text.length <= 120)
        assertTrue(preview.displayedLines <= 3)
        assertTrue(preview.text.lineSequence().count() <= 3)
    }

    @Test
    fun `text preview never scans beyond its bounded prefix`() {
        val source = object : CharSequence {
            override val length: Int = 1_000_000
            override fun get(index: Int): Char {
                check(index <= 120) { "preview scanned beyond its bound at $index" }
                return 'x'
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                error("preview must not materialize the source")
        }

        val preview = boundedAttachmentPreview(source, maxChars = 120, maxLines = 3)

        assertEquals(120, preview.text.length)
        assertTrue(preview.truncated)
        assertEquals(1, preview.displayedLines)
    }

    @Test
    fun `presentation labels preserve type size and readable bounded names`() {
        val attachment = UserAttachment(
            fileName = "this-is-a-very-long-source-file-name-that-needs-truncation.kt",
            kind = AttachmentKind.TEXT,
            mediaType = "text/plain",
            byteSize = 2_048,
            content = "fun main() = Unit",
        )

        assertEquals("Kotlin", attachmentTypeLabel(attachment))
        assertTrue(attachmentDetailText(attachment).contains("2 KB"))
        val displayName = attachmentDisplayName(attachment.fileName, 24)
        assertTrue(displayName.length <= 24)
        assertTrue(displayName.endsWith(".kt"))
        assertTrue(displayName.contains('…'))

        fun label(name: String, mediaType: String = "text/plain"): String = attachmentTypeLabel(
            UserAttachment(name, AttachmentKind.TEXT, mediaType, 1, "x"),
        )
        assertEquals("LaTeX", label("paper.tex"))
        assertEquals("BibTeX", label("references.bib"))
        assertEquals("Jupyter Notebook", label("analysis.ipynb", "application/x-ipynb+json"))
        assertEquals("PDF 论文", label("paper.pdf", "application/pdf"))
        assertEquals("Julia", label("analysis.jl"))
        assertEquals("Protocol Buffers", label("schema.proto"))
        assertEquals("Dockerfile", label("Dockerfile"))
        assertEquals("环境变量示例", label(".env.example"))
    }

    @Test
    fun `bibtex attachment detail exposes offline citation validation state`() {
        val attachment = UserAttachment(
            fileName = "references.bib",
            kind = AttachmentKind.TEXT,
            mediaType = "application/x-bibtex",
            byteSize = 64,
            content = "@article{demo, doi={not-a-doi}}",
        )

        val detail = attachmentDetailText(attachment)
        assertTrue("引用 1 条" in detail)
        assertTrue("需检查" in detail)
    }

    @Test
    fun `large bibtex detail stays bounded for edt rendering`() {
        val attachment = UserAttachment(
            fileName = "large.bib",
            kind = AttachmentKind.TEXT,
            mediaType = "application/x-bibtex",
            byteSize = 65_536,
            content = "@article{demo, title={bounded}}" +
                "x".repeat(ResearchCitationValidator.MAX_UI_SOURCE_CHARS),
        )

        val detail = attachmentDetailText(attachment)
        assertTrue("引用 至少 1 条" in detail)
        assertTrue("需检查" in detail)
    }

    @Test
    fun `preview cache uses identity and does not confuse equal attachments`() {
        val first = UserAttachment("same.png", AttachmentKind.IMAGE, "image/png", 1, "x")
        val equalButDistinct = first.copy()
        val preview = AttachmentImagePreview(ImageDimensions(1, 1), null)

        AttachmentPreviewCache.remember(first, preview)

        assertEquals(preview, AttachmentPreviewCache.find(first))
        assertNull(AttachmentPreviewCache.find(equalButDistinct))
        assertFalse(first === equalButDistinct)
    }

    private fun pngHeader(width: Int, height: Int): ByteArray = ByteArray(24).apply {
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(this)
        "IHDR".encodeToByteArray().copyInto(this, 12)
        putBigEndianInt(this, 16, width)
        putBigEndianInt(this, 20, height)
    }

    private fun gifHeader(width: Int, height: Int): ByteArray = ByteArray(10).apply {
        "GIF89a".encodeToByteArray().copyInto(this)
        this[6] = width.toByte()
        this[7] = (width ushr 8).toByte()
        this[8] = height.toByte()
        this[9] = (height ushr 8).toByte()
    }

    private fun webpExtendedHeader(width: Int, height: Int): ByteArray = ByteArray(30).apply {
        "RIFF".encodeToByteArray().copyInto(this)
        putLittleEndianInt(this, 4, size - 8)
        "WEBP".encodeToByteArray().copyInto(this, 8)
        "VP8X".encodeToByteArray().copyInto(this, 12)
        putLittleEndianInt(this, 16, 10)
        putLittleEndian24(this, 24, width - 1)
        putLittleEndian24(this, 27, height - 1)
    }

    private fun putBigEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putLittleEndian24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
    }

    private fun putLittleEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }
}
