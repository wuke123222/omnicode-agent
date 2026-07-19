package dev.omnicode.ui

import dev.omnicode.model.AttachmentKind
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClipboardAttachmentSupportTest {
    @Test
    fun `clipboard image is encoded as bounded png attachment`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, 0x00ff00)
        }
        val accepted = assertIs<AttachmentIntakeResult.Accepted>(
            clipboardImageAttachment(image, "clipboard.png"),
        ).attachment

        assertEquals(AttachmentKind.IMAGE, accepted.kind)
        assertEquals("image/png", accepted.mediaType)
        assertEquals("clipboard.png", accepted.fileName)
        assertTrue(accepted.byteSize > 0)
        assertTrue(accepted.content.isNotBlank())
        assertEquals(ImageDimensions(2, 2), AttachmentPreviewCache.find(accepted)?.dimensions)
        assertTrue(AttachmentPreviewCache.find(accepted)?.thumbnail != null)
    }

    @Test
    fun `clipboard dimensions cap decoded memory before allocating a raster`() {
        assertTrue(clipboardImageDimensionsAllowed(2_000, 4_000))
        assertFalse(clipboardImageDimensionsAllowed(4_001, 2_000))
        assertFalse(clipboardImageDimensionsAllowed(4_096, 4_096))
        assertFalse(clipboardImageDimensionsAllowed(0, 100))
    }

    @Test
    fun `bounded image output refuses growth before exceeding its byte limit`() {
        val output = BoundedAttachmentOutputStream(5)
        output.write(byteArrayOf(1, 2, 3), 0, 3)

        assertFailsWith<AttachmentOutputLimitExceededException> {
            output.write(byteArrayOf(4, 5, 6), 0, 3)
        }
        assertEquals(3, output.toByteArray().size)
    }
}
