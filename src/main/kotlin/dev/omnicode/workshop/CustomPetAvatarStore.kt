package dev.omnicode.workshop

import com.intellij.openapi.application.PathManager
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

data class CustomPetAvatarInfo(
    val width: Int,
    val height: Int,
    val storedBytes: Long,
)

/**
 * Imports a user-owned local avatar without retaining the source file or its metadata.
 *
 * Only PNG/JPEG pixels are decoded. The image is bounded, rendered into a fresh ARGB buffer, and
 * re-encoded as PNG in the IDE config directory, so EXIF, appended payloads, animation, paths, and
 * executable content never become part of the workshop pack.
 */
class CustomPetAvatarStore internal constructor(
    private val avatarFile: Path,
) {
    @Volatile
    private var cachedImage: BufferedImage? = null

    fun importImage(source: Path): CustomPetAvatarInfo {
        val input = source.toAbsolutePath().normalize()
        require(Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            "请选择普通的 PNG 或 JPG 图片文件（不支持符号链接）"
        }
        val sourceBytes = Files.size(input)
        require(sourceBytes in 1..MAX_SOURCE_BYTES) { "图片大小必须小于 8 MB" }

        val decoded = decodeBounded(input)
        val renderScale = minOf(1.0, MAX_STORED_DIMENSION.toDouble() / maxOf(decoded.width, decoded.height))
        val storedWidth = (decoded.width * renderScale).toInt().coerceAtLeast(1)
        val storedHeight = (decoded.height * renderScale).toInt().coerceAtLeast(1)
        val normalized = BufferedImage(storedWidth, storedHeight, BufferedImage.TYPE_INT_ARGB)
        normalized.createGraphics().use { graphics ->
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(decoded, 0, 0, storedWidth, storedHeight, null)
        }

        val directory = requireNotNull(avatarFile.parent) { "自定义桌宠存储路径无效" }
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(avatarFile)) { "自定义桌宠目标不能是符号链接" }
        val temporary = Files.createTempFile(directory, ".custom-idol-", ".png")
        try {
            require(ImageIO.write(normalized, "png", temporary.toFile())) { "无法编码 PNG 图片" }
            require(Files.size(temporary) in 1..MAX_STORED_BYTES) { "重新编码后的图片超出安全限制" }
            moveAtomically(temporary, avatarFile)
        } finally {
            Files.deleteIfExists(temporary)
        }
        cachedImage = normalized
        return CustomPetAvatarInfo(normalized.width, normalized.height, Files.size(avatarFile))
    }

    fun loadImage(): BufferedImage? {
        cachedImage?.let { return it }
        if (!Files.isRegularFile(avatarFile, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(avatarFile) !in 1..MAX_STORED_BYTES) return null
        return runCatching { decodeBounded(avatarFile) }
            .getOrNull()
            ?.also { cachedImage = it }
    }

    fun exists(): Boolean = Files.isRegularFile(avatarFile, LinkOption.NOFOLLOW_LINKS)

    fun remove(): Boolean {
        require(!Files.isSymbolicLink(avatarFile)) { "自定义桌宠目标不能是符号链接" }
        return Files.deleteIfExists(avatarFile).also { cachedImage = null }
    }

    private fun decodeBounded(path: Path): BufferedImage {
        Files.newInputStream(path).use { stream ->
            ImageIO.createImageInputStream(stream).use { imageInput ->
                requireNotNull(imageInput) { "无法读取图片" }
                val readers = ImageIO.getImageReaders(imageInput)
                require(readers.hasNext()) { "仅支持 PNG 或 JPG 图片" }
                val reader = readers.next()
                try {
                    reader.input = imageInput
                    val format = reader.formatName.lowercase()
                    require(format == "png" || format == "jpeg" || format == "jpg") {
                        "仅支持 PNG 或 JPG 图片"
                    }
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    require(width in MIN_DIMENSION..MAX_DIMENSION && height in MIN_DIMENSION..MAX_DIMENSION) {
                        "图片宽高必须在 32 到 2048 像素之间"
                    }
                    require(width.toLong() * height.toLong() <= MAX_PIXELS) { "图片像素数量过大" }
                    return requireNotNull(reader.read(0)) { "无法解码图片" }
                } finally {
                    reader.dispose()
                }
            }
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val MIN_DIMENSION = 32
        private const val MAX_DIMENSION = 2_048
        private const val MAX_STORED_DIMENSION = 512
        private const val MAX_PIXELS = 4_194_304L
        private const val MAX_SOURCE_BYTES = 8L * 1024L * 1024L
        private const val MAX_STORED_BYTES = 16L * 1024L * 1024L

        val shared: CustomPetAvatarStore by lazy {
            CustomPetAvatarStore(
                PathManager.getConfigDir()
                    .resolve("omnicode")
                    .resolve("workshop")
                    .resolve("custom-idol.png"),
            )
        }
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
