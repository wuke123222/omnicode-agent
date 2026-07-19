package dev.omnicode.ui

import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.UserAttachment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

internal data class ImageDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

internal data class AttachmentImagePreview(
    val dimensions: ImageDimensions,
    val thumbnail: BufferedImage?,
)

internal sealed interface ImageAttachmentInspection {
    data class Valid(val preview: AttachmentImagePreview) : ImageAttachmentInspection
    data class Invalid(val message: String) : ImageAttachmentInspection
}

internal data class BoundedAttachmentPreview(
    val text: String,
    val truncated: Boolean,
    val displayedLines: Int,
)

/**
 * Keeps decoded thumbnails away from the attachment model and releases both thumbnail and
 * source as soon as the attachment is no longer referenced. Identity semantics avoid hashing
 * multi-megabyte Base64/text content on the EDT.
 */
internal object AttachmentPreviewCache {
    private data class Entry(
        val attachment: WeakReference<UserAttachment>,
        val preview: AttachmentImagePreview,
    )

    private val entries = mutableMapOf<Int, MutableList<Entry>>()

    @Synchronized
    fun remember(attachment: UserAttachment, preview: AttachmentImagePreview) {
        cleanCollected()
        val key = System.identityHashCode(attachment)
        val bucket = entries.getOrPut(key) { mutableListOf() }
        bucket.removeIf { it.attachment.get() == null || it.attachment.get() === attachment }
        bucket += Entry(WeakReference(attachment), preview)
    }

    @Synchronized
    fun find(attachment: UserAttachment): AttachmentImagePreview? {
        cleanCollected()
        val key = System.identityHashCode(attachment)
        val bucket = entries[key] ?: return null
        val preview = bucket.firstOrNull { it.attachment.get() === attachment }?.preview
        bucket.removeIf { it.attachment.get() == null }
        if (bucket.isEmpty()) entries.remove(key)
        return preview
    }

    private fun cleanCollected() {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next().value
            bucket.removeIf { it.attachment.get() == null }
            if (bucket.isEmpty()) iterator.remove()
        }
    }
}

internal fun inspectImageAttachment(bytes: ByteArray, mediaType: String): ImageAttachmentInspection {
    val dimensions = imageDimensions(bytes, mediaType)
        ?: return ImageAttachmentInspection.Invalid("图片内容与文件格式不匹配，或无法读取尺寸。")
    if (
        dimensions.width > MAX_ATTACHMENT_IMAGE_EDGE ||
        dimensions.height > MAX_ATTACHMENT_IMAGE_EDGE ||
        dimensions.pixels > MAX_ATTACHMENT_IMAGE_PIXELS
    ) {
        return ImageAttachmentInspection.Invalid(
            "图片尺寸过大，最大 ${MAX_ATTACHMENT_IMAGE_EDGE}×${MAX_ATTACHMENT_IMAGE_EDGE} 且不超过 2500 万像素。",
        )
    }
    return when (val decoded = decodeBoundedThumbnail(bytes, dimensions)) {
        is ThumbnailDecodeResult.Decoded ->
            ImageAttachmentInspection.Valid(AttachmentImagePreview(dimensions, decoded.image))
        ThumbnailDecodeResult.Unsupported -> ImageAttachmentInspection.Invalid(
            if (mediaType.equals("image/webp", ignoreCase = true)) {
                "当前 IDE 无法安全预览 WebP，请转换为 PNG 或 JPEG 后重试。"
            } else {
                "当前 IDE 缺少该图片格式的安全预览支持。"
            },
        )
        ThumbnailDecodeResult.Invalid -> ImageAttachmentInspection.Invalid(
            if (mediaType.equals("image/webp", ignoreCase = true)) {
                "WebP 图片无法安全解码或预览，文件可能已损坏；请转换为 PNG 或 JPEG。"
            } else {
                "图片无法安全解码，文件可能已损坏。"
            },
        )
    }
}

internal fun imageDimensions(bytes: ByteArray, mediaType: String): ImageDimensions? = when (mediaType.lowercase()) {
    "image/png" -> pngDimensions(bytes)
    "image/jpeg" -> jpegDimensions(bytes)
    "image/gif" -> gifDimensions(bytes)
    "image/webp" -> webpDimensions(bytes)
    else -> null
}

internal fun boundedAttachmentPreview(
    value: CharSequence,
    maxChars: Int = MAX_TEXT_PREVIEW_CHARS,
    maxLines: Int = MAX_TEXT_PREVIEW_LINES,
): BoundedAttachmentPreview {
    require(maxChars > 0 && maxLines > 0)
    val output = StringBuilder(minOf(value.length, maxChars))
    var displayedLines = if (value.isEmpty()) 0 else 1
    var truncated = false
    var index = 0
    while (index < value.length && output.length < maxChars) {
        val character = value[index]
        if (character == '\n' || character == '\r') {
            if (displayedLines >= maxLines) {
                truncated = true
                break
            }
            output.append('\n')
            displayedLines++
            if (character == '\r' && index + 1 < value.length && value[index + 1] == '\n') index++
        } else {
            output.append(character)
        }
        index++
    }
    if (index < value.length) truncated = true
    if (truncated && output.isNotEmpty() && output.last().isHighSurrogate()) {
        output.setLength(output.length - 1)
    }
    return BoundedAttachmentPreview(output.toString(), truncated, displayedLines)
}

internal fun attachmentTypeLabel(attachment: UserAttachment): String {
    if (attachment.kind == AttachmentKind.IMAGE) return attachment.mediaType.removePrefix("image/").uppercase()
    if (attachment.kind == AttachmentKind.MARKDOWN) return "Markdown"
    when (attachment.fileName.lowercase()) {
        "dockerfile" -> return "Dockerfile"
        "makefile" -> return "Makefile"
        "cmakelists.txt" -> return "CMake"
        ".env.example", ".env.sample", ".env.template", ".env.dist" -> return "环境变量示例"
    }
    return when (attachment.fileName.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "js", "jsx" -> "JavaScript"
        "ts", "tsx" -> "TypeScript"
        "json", "jsonl" -> "JSON"
        "yaml", "yml" -> "YAML"
        "xml" -> "XML"
        "log" -> "日志"
        "sql" -> "SQL"
        "sh" -> "Shell"
        "tex" -> "LaTeX"
        "bib" -> "BibTeX"
        "ipynb" -> "Jupyter Notebook"
        "pdf" -> "PDF 论文"
        "r" -> "R"
        "jl" -> "Julia"
        "m" -> "MATLAB / Objective-C"
        "html", "htm" -> "HTML"
        "css" -> "CSS"
        "scss" -> "SCSS"
        "vue" -> "Vue"
        "dart" -> "Dart"
        "rb" -> "Ruby"
        "php" -> "PHP"
        "lua" -> "Lua"
        "proto" -> "Protocol Buffers"
        else -> attachment.mediaType.substringAfter('/').substringBefore(';').ifBlank { "文本" }
    }
}

internal fun attachmentDetailText(attachment: UserAttachment): String {
    val parts = mutableListOf(attachmentTypeLabel(attachment))
    AttachmentPreviewCache.find(attachment)?.dimensions?.let { parts += "${it.width}×${it.height}" }
    parts += attachmentDisplaySize(attachment.byteSize)
    return parts.joinToString(" · ")
}

internal fun attachmentPreviewFromBufferedImage(source: BufferedImage): AttachmentImagePreview =
    AttachmentImagePreview(ImageDimensions(source.width, source.height), scaleThumbnail(source))

private sealed interface ThumbnailDecodeResult {
    data class Decoded(val image: BufferedImage) : ThumbnailDecodeResult
    data object Unsupported : ThumbnailDecodeResult
    data object Invalid : ThumbnailDecodeResult
}

private fun decodeBoundedThumbnail(bytes: ByteArray, expected: ImageDimensions): ThumbnailDecodeResult = try {
    MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return ThumbnailDecodeResult.Unsupported
        val reader = readers.next()
        try {
            reader.setInput(input, true, true)
            if (reader.getWidth(0) != expected.width || reader.getHeight(0) != expected.height) {
                return ThumbnailDecodeResult.Invalid
            }
            val sample = maxOf(
                divideRoundUp(expected.width, THUMBNAIL_MAX_WIDTH),
                divideRoundUp(expected.height, THUMBNAIL_MAX_HEIGHT),
                1,
            )
            val parameters = reader.defaultReadParam.apply {
                setSourceSubsampling(sample, sample, 0, 0)
            }
            val sampled = reader.read(0, parameters) ?: return ThumbnailDecodeResult.Invalid
            ThumbnailDecodeResult.Decoded(scaleThumbnail(sampled))
        } finally {
            reader.dispose()
        }
    }
} catch (_: Exception) {
    ThumbnailDecodeResult.Invalid
}

private fun scaleThumbnail(source: BufferedImage): BufferedImage {
    val scale = minOf(
        THUMBNAIL_MAX_WIDTH.toDouble() / source.width,
        THUMBNAIL_MAX_HEIGHT.toDouble() / source.height,
        1.0,
    )
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    if (width == source.width && height == source.height) return source
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
        target.createGraphics().use { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        }
    }
}

private fun pngDimensions(bytes: ByteArray): ImageDimensions? {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
    if (ascii(bytes, 12, 4) != "IHDR") return null
    return dimensions(bigEndianInt(bytes, 16), bigEndianInt(bytes, 20))
}

private fun gifDimensions(bytes: ByteArray): ImageDimensions? {
    if (bytes.size < 10 || ascii(bytes, 0, 6) !in setOf("GIF87a", "GIF89a")) return null
    return dimensions(littleEndianShort(bytes, 6), littleEndianShort(bytes, 8))
}

private fun jpegDimensions(bytes: ByteArray): ImageDimensions? {
    if (bytes.size < 4 || unsigned(bytes[0]) != 0xff || unsigned(bytes[1]) != 0xd8) return null
    var offset = 2
    while (offset + 3 < bytes.size) {
        while (offset < bytes.size && unsigned(bytes[offset]) != 0xff) offset++
        while (offset < bytes.size && unsigned(bytes[offset]) == 0xff) offset++
        if (offset >= bytes.size) return null
        val marker = unsigned(bytes[offset++])
        if (marker == 0xd9 || marker == 0xda) return null
        if (marker == 0x01 || marker in 0xd0..0xd8) continue
        if (offset + 1 >= bytes.size) return null
        val length = bigEndianShort(bytes, offset)
        if (length < 2 || offset + length > bytes.size) return null
        if (marker in JPEG_SOF_MARKERS && length >= 7) {
            return dimensions(bigEndianShort(bytes, offset + 5), bigEndianShort(bytes, offset + 3))
        }
        offset += length
    }
    return null
}

private fun webpDimensions(bytes: ByteArray): ImageDimensions? {
    if (bytes.size < 30 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WEBP") return null
    val riffSize = littleEndianUnsignedInt(bytes, 4)
    if (riffSize < 22L || riffSize + 8L > bytes.size.toLong()) return null
    val chunkSize = littleEndianUnsignedInt(bytes, 16)
    if (chunkSize > Int.MAX_VALUE || 20L + chunkSize > bytes.size.toLong()) return null
    return when (ascii(bytes, 12, 4)) {
        "VP8X" -> if (chunkSize >= 10) {
            dimensions(littleEndian24(bytes, 24) + 1, littleEndian24(bytes, 27) + 1)
        } else {
            null
        }
        "VP8L" -> {
            if (chunkSize < 5 || unsigned(bytes[20]) != 0x2f || bytes.size < 25) return null
            val bits = littleEndianInt(bytes, 21)
            dimensions((bits and 0x3fff) + 1, ((bits ushr 14) and 0x3fff) + 1)
        }
        "VP8 " -> {
            if (chunkSize < 10 || bytes.size < 30 || unsigned(bytes[23]) != 0x9d || unsigned(bytes[24]) != 0x01 || unsigned(bytes[25]) != 0x2a) {
                return null
            }
            dimensions(littleEndianShort(bytes, 26) and 0x3fff, littleEndianShort(bytes, 28) and 0x3fff)
        }
        else -> null
    }
}

private fun dimensions(width: Int, height: Int): ImageDimensions? =
    if (width > 0 && height > 0) ImageDimensions(width, height) else null

private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
    if (offset >= 0 && length >= 0 && offset + length <= bytes.size) {
        String(bytes, offset, length, Charsets.US_ASCII)
    } else {
        ""
    }

private fun unsigned(value: Byte): Int = value.toInt() and 0xff

private fun bigEndianShort(bytes: ByteArray, offset: Int): Int =
    (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])

private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
    unsigned(bytes[offset]) or (unsigned(bytes[offset + 1]) shl 8)

private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
    (unsigned(bytes[offset]) shl 24) or
        (unsigned(bytes[offset + 1]) shl 16) or
        (unsigned(bytes[offset + 2]) shl 8) or
        unsigned(bytes[offset + 3])

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    unsigned(bytes[offset]) or
        (unsigned(bytes[offset + 1]) shl 8) or
        (unsigned(bytes[offset + 2]) shl 16) or
        (unsigned(bytes[offset + 3]) shl 24)

private fun littleEndianUnsignedInt(bytes: ByteArray, offset: Int): Long =
    littleEndianInt(bytes, offset).toLong() and 0xffff_ffffL

private fun littleEndian24(bytes: ByteArray, offset: Int): Int =
    unsigned(bytes[offset]) or (unsigned(bytes[offset + 1]) shl 8) or (unsigned(bytes[offset + 2]) shl 16)

private fun divideRoundUp(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

internal const val MAX_ATTACHMENT_IMAGE_EDGE = 8_192
internal const val MAX_ATTACHMENT_IMAGE_PIXELS = 25_000_000L
internal const val MAX_TEXT_PREVIEW_CHARS = 6_000
internal const val MAX_TEXT_PREVIEW_LINES = 80
private const val THUMBNAIL_MAX_WIDTH = 72
private const val THUMBNAIL_MAX_HEIGHT = 52
private val JPEG_SOF_MARKERS = setOf(
    0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
)
