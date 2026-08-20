package dev.omnicode.service

import java.nio.file.Files
import java.nio.file.Path

/** One detected game engine with an optional short detail such as the engine version. */
internal data class DetectedGameEngine(
    val name: String,
    val detail: String?,
)

/**
 * Bounded, read-only detection of common game engines from fixed marker paths. Every probe is a
 * constant number of existence checks plus at most one small read; there is no recursive scan,
 * and marker files are treated as untrusted project data (only short extracted fragments are
 * ever placed into model context).
 */
internal object GameEngineDetection {
    private const val MAX_MARKER_READ_BYTES = 4_096
    private const val MAX_DETAIL_CHARS = 48

    fun detect(basePath: Path): DetectedGameEngine? {
        detectUnity(basePath)?.let { return it }
        detectUnreal(basePath)?.let { return it }
        detectGodot(basePath)?.let { return it }
        detectCocos(basePath)?.let { return it }
        return null
    }

    /** A single bounded context line, or null when the project is not a recognized game project. */
    fun contextLine(basePath: Path): String? = detect(basePath)?.let { engine ->
        buildString {
            append("## 项目类型\n游戏项目 · ")
            append(engine.name)
            engine.detail?.takeIf(String::isNotBlank)?.let { append("（").append(it).append("）") }
            append("。请遵循该引擎的项目结构与 API 约定；")
            append("生成的目录、缓存与资产库（如 Unity 的 Library/Temp/*.meta 噪声、Unreal 的 Binaries/DerivedDataCache、Godot 的 .godot/）不属于源码上下文。")
        }
    }

    private fun detectUnity(base: Path): DetectedGameEngine? {
        val versionFile = base.resolve("ProjectSettings").resolve("ProjectVersion.txt")
        if (Files.isRegularFile(versionFile)) {
            val version = readSmall(versionFile)
                ?.lineSequence()
                ?.firstOrNull { it.trimStart().startsWith("m_EditorVersion:") }
                ?.substringAfter(':')
                ?.trim()
                ?.take(MAX_DETAIL_CHARS)
            return DetectedGameEngine("Unity", version)
        }
        if (Files.isDirectory(base.resolve("Assets")) && Files.isDirectory(base.resolve("ProjectSettings"))) {
            return DetectedGameEngine("Unity", null)
        }
        return null
    }

    private fun detectUnreal(base: Path): DetectedGameEngine? {
        val uproject = runCatching {
            Files.list(base).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".uproject") }.findFirst().orElse(null)
            }
        }.getOrNull() ?: return null
        return DetectedGameEngine("Unreal Engine", uproject.fileName.toString().take(MAX_DETAIL_CHARS))
    }

    private fun detectGodot(base: Path): DetectedGameEngine? {
        if (!Files.isRegularFile(base.resolve("project.godot"))) return null
        return DetectedGameEngine("Godot", null)
    }

    private fun detectCocos(base: Path): DetectedGameEngine? {
        val creatorSettings = base.resolve("settings").resolve("v2")
        val looksLikeCreator = Files.isDirectory(base.resolve("assets")) &&
            (Files.isDirectory(creatorSettings) || Files.isRegularFile(base.resolve("tsconfig.cocos.json")))
        if (looksLikeCreator) return DetectedGameEngine("Cocos Creator", null)
        return null
    }

    private fun readSmall(path: Path): String? = runCatching {
        Files.newInputStream(path).use { String(it.readNBytes(MAX_MARKER_READ_BYTES), Charsets.UTF_8) }
    }.getOrNull()
}
