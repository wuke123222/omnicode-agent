package dev.omnicode.ui

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.IOException
import java.io.Writer
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

internal sealed interface PdfResearchExtraction {
    data class Extracted(
        val text: String,
        val pages: Int,
        val truncated: Boolean,
        /** Stable offsets into [text], allowing the UI to cite a page without re-parsing PDF bytes. */
        val pageReferences: List<PdfPageReference> = emptyList(),
        /** True when the bounded text was produced by the optional local Tesseract adapter. */
        val ocr: Boolean = false,
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
                return extractScannedPdfWithLocalOcr(bytes) ?: PdfResearchExtraction.Rejected(
                    "PDF 未提取到可读文本；未发现本地 Tesseract。请安装 Tesseract 后重试，或上传关键页面截图并使用视觉辅助模型。",
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

/**
 * Optional local OCR fallback for image-only PDFs. It is intentionally conservative: only a
 * user-selected PDF is rendered, the executable must be a trusted Tesseract binary on PATH, the
 * first bounded pages are processed, and no network or provider call is made.
 */
private fun extractScannedPdfWithLocalOcr(bytes: ByteArray): PdfResearchExtraction.Extracted? {
    val tesseract = findTesseract() ?: return null
    val directory = runCatching { Files.createTempDirectory("omnicode-pdf-ocr") }.getOrNull() ?: return null
    return try {
        Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache()).use { document ->
            val pageCount = document.numberOfPages
            if (pageCount !in 1..MAX_PDF_PAGES) return null
            val pagesToProcess = minOf(pageCount, MAX_OCR_PAGES)
            val renderer = PDFRenderer(document)
            val output = StringBuilder()
            val language = tesseractLanguages(tesseract)
            for (pageIndex in 0 until pagesToProcess) {
                val mediaBox = document.getPage(pageIndex).mediaBox
                if (mediaBox.width > MAX_OCR_PAGE_POINTS || mediaBox.height > MAX_OCR_PAGE_POINTS) continue
                val imagePath = directory.resolve("page-${pageIndex + 1}.png")
                val image: BufferedImage = renderer.renderImageWithDPI(pageIndex, OCR_DPI)
                ImageIO.write(image, "png", imagePath.toFile())
                val text = runTesseract(tesseract, imagePath, language)
                if (text.isNotBlank()) {
                    output.append("\n\n[PDF page ${pageIndex + 1}] [local OCR]\n")
                    output.append(text.take(MAX_OCR_PAGE_CHARS))
                }
                Files.deleteIfExists(imagePath)
                if (output.length >= MAX_PDF_TEXT_CHARS) break
            }
            val text = output.toString().trim()
            if (text.none { it.isLetterOrDigit() }) return null
            val bounded = text.take(MAX_PDF_TEXT_CHARS)
            PdfResearchExtraction.Extracted(
                text = bounded,
                pages = pageCount,
                truncated = pageCount > pagesToProcess || text.length > MAX_PDF_TEXT_CHARS,
                ocr = true,
                pageReferences = pageReferences(bounded),
            )
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching {
            Files.list(directory).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}

private fun findTesseract(): Path? {
    val pathEntries = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
    val names = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        listOf("tesseract.exe")
    } else {
        listOf("tesseract")
    }
    return pathEntries.asSequence()
        .filter(String::isNotBlank)
        .flatMap { entry -> names.asSequence().map { Path.of(entry, it) } }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

private fun tesseractLanguages(executable: Path): String {
    val available = runTesseractCommand(executable, listOf("--list-langs"), null)
    return if (available.contains("chi_sim")) "eng+chi_sim" else "eng"
}

private fun runTesseract(executable: Path, image: Path, language: String): String =
    runTesseractCommand(executable, listOf(image.toString(), "stdout", "-l", language, "--psm", "3"), image)

private fun runTesseractCommand(executable: Path, arguments: List<String>, image: Path?): String {
    val process = runCatching {
        ProcessBuilder(listOf(executable.toString()) + arguments)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["PATH"] = System.getenv("PATH").orEmpty()
                System.getenv("TESSDATA_PREFIX")?.let { environment()["TESSDATA_PREFIX"] = it }
            }
            .start()
    }.getOrNull() ?: return ""
    return try {
        if (!process.waitFor(OCR_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return ""
        }
        process.inputStream.readNBytes(MAX_OCR_COMMAND_OUTPUT_BYTES).toString(Charsets.UTF_8)
    } finally {
        process.destroy()
        image?.let { runCatching { Files.deleteIfExists(it) } }
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
private const val MAX_OCR_PAGES = 4
private const val MAX_OCR_PAGE_CHARS = 4_000
private const val MAX_OCR_PAGE_POINTS = 1_440f
private const val MAX_OCR_COMMAND_OUTPUT_BYTES = 12 * 1_024
private const val OCR_DPI = 150f
private val OCR_TIMEOUT: Duration = Duration.ofSeconds(2)
private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
