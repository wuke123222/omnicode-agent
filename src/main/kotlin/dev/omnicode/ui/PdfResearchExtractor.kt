package dev.omnicode.ui

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.io.Writer

internal sealed interface PdfResearchExtraction {
    data class Extracted(
        val text: String,
        val pages: Int,
        val truncated: Boolean,
        /** Stable offsets into [text], allowing the UI to cite a page without re-parsing PDF bytes. */
        val pageReferences: List<PdfPageReference> = emptyList(),
    ) : PdfResearchExtraction

    data class Rejected(val message: String) : PdfResearchExtraction
}

internal data class PdfPageReference(
    val page: Int,
    val startOffset: Int,
    val endOffset: Int,
) {
    init {
        require(page > 0) { "PDF page must be positive" }
        require(startOffset >= 0 && endOffset >= startOffset) { "Invalid PDF page range" }
    }
}

/** Extracts a bounded, page-addressable text view from one user-selected research paper. */
internal fun extractPdfResearchText(bytes: ByteArray): PdfResearchExtraction {
    if (bytes.size > MAX_PDF_BYTES) {
        return PdfResearchExtraction.Rejected("PDF 过大，最大 ${attachmentDisplaySize(MAX_PDF_BYTES.toLong())}。")
    }
    if (bytes.size < PDF_SIGNATURE.size || !bytes.copyOfRange(0, PDF_SIGNATURE.size).contentEquals(PDF_SIGNATURE)) {
        return PdfResearchExtraction.Rejected("文件不是有效的 PDF。")
    }
    return try {
        Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache()).use { document ->
            if (document.isEncrypted) {
                return PdfResearchExtraction.Rejected("暂不读取加密 PDF，请先在可信工具中解密副本。")
            }
            val pages = document.numberOfPages
            if (pages !in 1..MAX_PDF_PAGES) {
                return PdfResearchExtraction.Rejected("PDF 页数无效或超过 $MAX_PDF_PAGES 页。")
            }
            val output = BoundedPdfWriter(MAX_PDF_TEXT_CHARS)
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }
            var truncated = false
            for (page in 1..pages) {
                try {
                    output.append("\n\n[PDF page $page]\n")
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.writeText(document, output)
                } catch (_: PdfTextLimitExceededException) {
                    truncated = true
                    break
                }
            }
            val text = output.value().trim()
            if (text.none { it.isLetterOrDigit() }) {
                return PdfResearchExtraction.Rejected(
                    "PDF 未提取到可读文本；若是扫描论文，请上传关键页面截图并使用视觉辅助模型。",
                )
            }
            val finalText = if (truncated) "$text\n\n[PDF text truncated at $MAX_PDF_TEXT_CHARS characters]" else text
            PdfResearchExtraction.Extracted(
                text = finalText,
                pages = pages,
                truncated = truncated,
                pageReferences = pageReferences(finalText),
            )
        }
    } catch (_: IOException) {
        PdfResearchExtraction.Rejected("PDF 无法安全解析，文件可能损坏、加密或使用了不支持的结构。")
    } catch (_: RuntimeException) {
        PdfResearchExtraction.Rejected("PDF 解析失败，请转换为文本或上传关键页面截图。")
    }
}

private fun pageReferences(text: String): List<PdfPageReference> {
    val marker = Regex("\\[PDF page (\\d+)]")
    val matches = marker.findAll(text).toList()
    return matches.mapIndexed { index, match ->
        val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
        PdfPageReference(
            page = match.groupValues[1].toIntOrNull() ?: (index + 1),
            startOffset = match.range.first,
            endOffset = end,
        )
    }
}

private class BoundedPdfWriter(private val limit: Int) : Writer() {
    private val output = StringBuilder(minOf(limit, 16 * 1_024))

    override fun write(chars: CharArray, offset: Int, length: Int) {
        java.util.Objects.checkFromIndexSize(offset, length, chars.size)
        if (length > limit - output.length) {
            val remaining = (limit - output.length).coerceAtLeast(0)
            if (remaining > 0) output.append(chars, offset, remaining)
            throw PdfTextLimitExceededException()
        }
        output.append(chars, offset, length)
    }

    override fun flush() = Unit
    override fun close() = Unit
    fun value(): String = output.toString()
}

private class PdfTextLimitExceededException : IOException("PDF text exceeds its bounded extraction limit")

internal const val MAX_PDF_BYTES: Int = 10 * 1_024 * 1_024
internal const val MAX_PDF_PAGES: Int = 300
internal const val MAX_PDF_TEXT_CHARS: Int = 48_000
private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
