package dev.omnicode.ui

import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.UserAttachment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal sealed interface AttachmentIntakeResult {
    data class Accepted(val attachment: UserAttachment) : AttachmentIntakeResult
    data class Rejected(val message: String) : AttachmentIntakeResult
}

/** Reads bounded image and UTF-8 text attachments that the chat can safely represent. */
internal object AttachmentIntake {
    const val MAX_IMAGE_BYTES: Long = 5L * 1_024 * 1_024
    const val MAX_MARKDOWN_BYTES: Long = 512L * 1_024
    const val MAX_TEXT_BYTES: Long = 1L * 1_024 * 1_024
    const val MAX_ATTACHMENTS: Int = 4
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val markdownExtensions = setOf("md", "markdown", "mdown", "mkdn")
    private val pdfExtensions = setOf("pdf")
    private val textExtensions = setOf(
        "txt", "log", "json", "jsonl", "yaml", "yml", "xml", "csv", "tsv", "toml", "ini", "properties",
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "cc", "cpp", "h", "hpp",
        "cs", "swift", "sh", "sql", "gradle", "tex", "bib", "ipynb", "r", "jl", "m", "html", "htm",
        "css", "scss", "vue", "dart", "rb", "php", "lua", "proto",
    )
    private val exactTextFileNames = setOf("dockerfile", "makefile", "cmakelists.txt")
    private val safeEnvironmentExamples = setOf(".env.example", ".env.sample", ".env.template", ".env.dist")

    fun supports(path: Path): Boolean = supportsFileName(path.fileName?.toString().orEmpty())

    fun supportsFileName(fileName: String): Boolean {
        val normalizedName = fileName.trim().lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in imageExtensions || extension in markdownExtensions || extension in pdfExtensions ||
            extension in textExtensions ||
            normalizedName in exactTextFileNames || normalizedName in safeEnvironmentExamples
    }

    fun read(path: Path): AttachmentIntakeResult {
        val fileName = path.fileName?.toString()?.trim().orEmpty()
        if (fileName.isBlank()) return AttachmentIntakeResult.Rejected("无法读取没有文件名的附件。")
        if (!Files.isRegularFile(path)) return AttachmentIntakeResult.Rejected("只能添加本地文件。")

        val normalizedName = fileName.lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val kind = when (extension) {
            in imageExtensions -> AttachmentKind.IMAGE
            in markdownExtensions -> AttachmentKind.MARKDOWN
            in pdfExtensions -> AttachmentKind.TEXT
            in textExtensions -> AttachmentKind.TEXT
            else -> if (normalizedName in exactTextFileNames || normalizedName in safeEnvironmentExamples) {
                AttachmentKind.TEXT
            } else {
                return AttachmentIntakeResult.Rejected(
                    "仅支持图片、Markdown、Notebook、科研资料、代码、日志和常见安全文本文件；不支持真实 .env。",
                )
            }
        }
        val size = runCatching { Files.size(path) }.getOrElse {
            return AttachmentIntakeResult.Rejected("无法读取附件大小。")
        }
        val limit = when (kind) {
            AttachmentKind.IMAGE -> MAX_IMAGE_BYTES
            AttachmentKind.MARKDOWN -> MAX_MARKDOWN_BYTES
            AttachmentKind.TEXT -> when (extension) {
                "ipynb" -> MAX_NOTEBOOK_BYTES
                "pdf" -> MAX_PDF_BYTES.toLong()
                else -> MAX_TEXT_BYTES
            }
        }
        val kindLabel = when (extension) {
            "ipynb" -> "Jupyter Notebook"
            "pdf" -> "PDF"
            else -> attachmentKindLabel(kind)
        }
        if (size > limit) {
            return AttachmentIntakeResult.Rejected(
                "$kindLabel 过大，最大 ${attachmentDisplaySize(limit)}。",
            )
        }
        val bytes = runCatching {
            Files.newInputStream(path).use { it.readNBytes((limit + 1).toInt()) }
        }.getOrElse {
            return AttachmentIntakeResult.Rejected("读取附件失败。")
        }
        if (bytes.size.toLong() > limit) {
            return AttachmentIntakeResult.Rejected(
                "$kindLabel 过大，最大 ${attachmentDisplaySize(limit)}。",
            )
        }
        val mediaType = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            in markdownExtensions -> "text/markdown"
            "json", "jsonl" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "tsv" -> "text/tab-separated-values"
            "tex" -> "application/x-tex"
            "bib" -> "application/x-bibtex"
            "ipynb" -> "application/x-ipynb+json"
            "pdf" -> "application/pdf"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "scss" -> "text/x-scss"
            "vue" -> "text/x-vue"
            "proto" -> "text/x-protobuf"
            else -> "text/plain"
        }
        val imagePreview = if (kind == AttachmentKind.IMAGE) {
            when (val inspection = inspectImageAttachment(bytes, mediaType)) {
                is ImageAttachmentInspection.Valid -> inspection.preview
                is ImageAttachmentInspection.Invalid -> return AttachmentIntakeResult.Rejected(inspection.message)
            }
        } else {
            null
        }
        val content = when (kind) {
            AttachmentKind.IMAGE -> Base64.getEncoder().encodeToString(bytes)
            AttachmentKind.MARKDOWN ->
                decodeUtf8(bytes) ?: return AttachmentIntakeResult.Rejected("文件不是有效的 UTF-8 文本。")
            AttachmentKind.TEXT -> if (extension == "ipynb") {
                when (val extraction = extractJupyterNotebook(bytes)) {
                    is NotebookExtractionResult.Accepted -> extraction.text
                    is NotebookExtractionResult.Rejected -> return AttachmentIntakeResult.Rejected(extraction.message)
                }
            } else if (extension == "pdf") {
                when (val extraction = extractPdfResearchText(bytes)) {
                    is PdfResearchExtraction.Extracted -> buildString {
                        appendLine("[PDF document · ${extraction.pages} pages · text extracted locally]")
                        append(extraction.text)
                    }
                    is PdfResearchExtraction.Rejected -> return AttachmentIntakeResult.Rejected(extraction.message)
                }
            } else {
                decodeUtf8(bytes) ?: return AttachmentIntakeResult.Rejected("文件不是有效的 UTF-8 文本。")
            }
        }
        if (kind != AttachmentKind.IMAGE && content.isBlank()) {
            return AttachmentIntakeResult.Rejected("${attachmentKindLabel(kind)}为空。")
        }
        if (kind != AttachmentKind.IMAGE && !isSafeTextAttachment(content)) {
            return AttachmentIntakeResult.Rejected("文件包含 NUL 或过多控制字符，无法作为安全文本附件读取。")
        }
        val attachment = UserAttachment(fileName, kind, mediaType, bytes.size.toLong(), content)
        imagePreview?.let { AttachmentPreviewCache.remember(attachment, it) }
        return AttachmentIntakeResult.Accepted(attachment)
    }
}

private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

private fun attachmentKindLabel(kind: AttachmentKind): String = when (kind) {
    AttachmentKind.IMAGE -> "图片"
    AttachmentKind.MARKDOWN -> "Markdown"
    AttachmentKind.TEXT -> "文本文件"
}

internal fun isSafeTextAttachment(value: String): Boolean {
    if ('\u0000' in value) return false
    val controls = value.count { it.code in 0..8 || it.code in 14..31 }
    return controls <= (value.length / 100).coerceAtLeast(2)
}

internal fun attachmentDisplaySize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${(bytes / 1_024.0).toInt()} KB"
    else -> "${"%.1f".format(bytes / 1_024.0 / 1_024.0)} MB"
}
