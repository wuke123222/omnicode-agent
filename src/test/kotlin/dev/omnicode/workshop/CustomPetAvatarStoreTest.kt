package dev.omnicode.workshop

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CustomPetAvatarStoreTest {
    @Test
    fun `import decodes and re-encodes a bounded local image`() {
        val directory = createTempDirectory("omnicode-avatar-")
        val source = directory.resolve("source.png")
        val target = directory.resolve("store/custom-idol.png")
        writeImage(source, 96, 128, "png", Color(0x7C8CFF))
        val store = CustomPetAvatarStore(target)

        val info = store.importImage(source)
        val loaded = assertNotNull(store.loadImage())

        assertEquals(96, info.width)
        assertEquals(128, info.height)
        assertEquals(96, loaded.width)
        assertEquals(128, loaded.height)
        assertTrue(store.exists())
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47),
            Files.readAllBytes(target).take(4).map(Byte::toUByte).map(UByte::toInt),
        )
    }

    @Test
    fun `invalid replacement preserves the last valid avatar`() {
        val directory = createTempDirectory("omnicode-avatar-")
        val source = directory.resolve("valid.jpg")
        val invalid = directory.resolve("invalid.png")
        val target = directory.resolve("custom-idol.png")
        writeImage(source, 80, 80, "jpg", Color(0xFF7CAD))
        Files.writeString(invalid, "not an image")
        val store = CustomPetAvatarStore(target)
        store.importImage(source)
        val before = Files.readAllBytes(target)

        assertFailsWith<IllegalArgumentException> { store.importImage(invalid) }

        assertTrue(before.contentEquals(Files.readAllBytes(target)))
        assertNotNull(store.loadImage())
    }

    @Test
    fun `unsupported dimensions and formats fail closed`() {
        val directory = createTempDirectory("omnicode-avatar-")
        val tiny = directory.resolve("tiny.png")
        val gif = directory.resolve("animated.gif")
        val target = directory.resolve("custom-idol.png")
        writeImage(tiny, 16, 16, "png", Color.BLUE)
        writeImage(gif, 64, 64, "gif", Color.GREEN)
        val store = CustomPetAvatarStore(target)

        assertFailsWith<IllegalArgumentException> { store.importImage(tiny) }
        assertFailsWith<IllegalArgumentException> { store.importImage(gif) }
        assertFalse(store.exists())
    }

    @Test
    fun `remove deletes only the normalized local copy`() {
        val directory = createTempDirectory("omnicode-avatar-")
        val source = directory.resolve("source.png")
        val target = directory.resolve("store/custom-idol.png")
        writeImage(source, 64, 64, "png", Color.ORANGE)
        val store = CustomPetAvatarStore(target)
        store.importImage(source)

        assertTrue(store.remove())
        assertFalse(store.exists())
        assertTrue(Files.exists(source))
    }

    private fun writeImage(path: java.nio.file.Path, width: Int, height: Int, format: String, color: Color) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { graphics ->
            try {
                graphics.color = color
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
        }
        assertTrue(ImageIO.write(image, format, path.toFile()))
    }
}
