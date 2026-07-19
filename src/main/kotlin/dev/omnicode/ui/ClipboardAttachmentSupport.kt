package dev.omnicode.ui

import dev.omnicode.model.AttachmentKind
import dev.omnicode.model.UserAttachment
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.ImageIcon

internal fun clipboardImageAttachment(
    image: Image,
    fileName: String,
): AttachmentIntakeResult {
    val icon = ImageIcon(image)
    val width = icon.iconWidth
    val height = icon.iconHeight
    if (width <= 0 || height <= 0) {
        return AttachmentIntakeResult.Rejected("无法读取剪贴板图片尺寸。")
    }
    if (!clipboardImageDimensionsAllowed(width, height)) {
        return AttachmentIntakeResult.Rejected(
            "剪贴板图片尺寸过大，最大 ${MAX_CLIPBOARD_IMAGE_EDGE}×${MAX_CLIPBOARD_IMAGE_EDGE} 且不超过 800 万像素。",
        )
    }
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    buffered.createGraphics().use { graphics -> graphics.drawImage(image, 0, 0, width, height, null) }
    val bytes = try {
        BoundedAttachmentOutputStream(AttachmentIntake.MAX_IMAGE_BYTES.toInt()).use { output ->
            if (!ImageIO.write(buffered, "png", output)) {
                return AttachmentIntakeResult.Rejected("无法将剪贴板图片转换为 PNG。")
            }
            output.toByteArray()
        }
    } catch (_: AttachmentOutputLimitExceededException) {
        return AttachmentIntakeResult.Rejected(
            "剪贴板图片压缩后仍过大，PNG 最大 ${attachmentDisplaySize(AttachmentIntake.MAX_IMAGE_BYTES)}。",
        )
    } catch (_: Exception) {
        return AttachmentIntakeResult.Rejected("无法将剪贴板图片转换为 PNG。")
    }
    val attachment = UserAttachment(
            fileName = fileName,
            kind = AttachmentKind.IMAGE,
            mediaType = "image/png",
            byteSize = bytes.size.toLong(),
            content = Base64.getEncoder().encodeToString(bytes),
        )
    val preview = attachmentPreviewFromBufferedImage(buffered)
    AttachmentPreviewCache.remember(attachment, preview)
    return AttachmentIntakeResult.Accepted(attachment)
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

internal class BoundedAttachmentOutputStream(
    private val limit: Int,
) : OutputStream() {
    private val output = ByteArrayOutputStream(minOf(limit, 8 * 1_024))

    init {
        require(limit > 0)
    }

    override fun write(value: Int) {
        ensureCapacity(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
        ensureCapacity(length)
        output.write(bytes, offset, length)
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun ensureCapacity(additional: Int) {
        if (additional > limit - output.size()) throw AttachmentOutputLimitExceededException()
    }
}

internal class AttachmentOutputLimitExceededException : IOException("Attachment output exceeds its byte limit")

internal fun clipboardImageDimensionsAllowed(width: Int, height: Int): Boolean =
    width > 0 && height > 0 &&
        width <= MAX_CLIPBOARD_IMAGE_EDGE && height <= MAX_CLIPBOARD_IMAGE_EDGE &&
        width.toLong() * height <= MAX_CLIPBOARD_IMAGE_PIXELS

internal const val MAX_CLIPBOARD_IMAGE_EDGE = 4_096
internal const val MAX_CLIPBOARD_IMAGE_PIXELS = 8_000_000L
