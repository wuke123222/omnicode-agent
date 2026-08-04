package dev.omnicode.ui

import dev.omnicode.model.AttachmentKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PdfResearchExtractorTest {
    @Test
    fun `attachment intake accepts PDF and stores only extracted bounded text`() {
        val path = Files.createTempFile("omnicode-paper", ".pdf")
        try {
            Files.write(path, pdf("Research finding"))

            val attachment = assertIs<AttachmentIntakeResult.Accepted>(AttachmentIntake.read(path)).attachment

            assertEquals(AttachmentKind.TEXT, attachment.kind)
            assertEquals("application/pdf", attachment.mediaType)
            assertTrue(attachment.content.contains("[PDF document · 1 pages · text extracted locally]"))
            assertTrue(attachment.content.contains("Research finding"))
            assertTrue(!attachment.content.startsWith("%PDF-"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `extracts bounded text with stable page markers`() {
        val result = assertIs<PdfResearchExtraction.Extracted>(
            extractPdfResearchText(pdf("Hypothesis alpha", "Observed result beta")),
        )

        assertEquals(2, result.pages)
        assertTrue(result.text.contains("[PDF page 1]"))
        assertTrue(result.text.contains("Hypothesis alpha"))
        assertTrue(result.text.contains("[PDF page 2]"))
        assertTrue(result.text.contains("Observed result beta"))
        assertEquals(2, result.pageReferences.size)
        assertEquals(1, result.pageReferences[0].page)
        assertTrue(result.pageReferences[0].endOffset <= result.pageReferences[1].startOffset)
    }

    @Test
    fun `rejects malformed and oversized PDF input`() {
        assertIs<PdfResearchExtraction.Rejected>(extractPdfResearchText("not-pdf".toByteArray()))
        val oversized = ByteArray(MAX_PDF_BYTES + 1).also { bytes ->
            "%PDF-".toByteArray().copyInto(bytes)
        }
        assertIs<PdfResearchExtraction.Rejected>(extractPdfResearchText(oversized))
    }

    private fun pdf(vararg pages: String): ByteArray = PDDocument().use { document ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        pages.forEach { text ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText(text)
                content.endText()
            }
        }
        ByteArrayOutputStream().use { output ->
            document.save(output)
            output.toByteArray()
        }
    }
}
