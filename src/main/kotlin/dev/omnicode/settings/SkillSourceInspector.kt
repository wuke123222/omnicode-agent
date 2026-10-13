package dev.omnicode.settings

import java.nio.file.Files
import java.nio.file.Path

internal data class SkillSourceInspection(
    val isValid: Boolean,
    val discoveredSkills: Int,
    val message: String,
)

/** Bounded, read-only inspection shared by the WebView settings surface and tests. */
internal fun inspectSkillSource(value: String, projectBasePath: String?): SkillSourceInspection {
    if (value.isBlank()) return SkillSourceInspection(false, 0, "尚未填写 Skill 路径。")
    val source = runCatching { resolveSkillSourcePath(value, projectBasePath) }
        .getOrElse { return SkillSourceInspection(false, 0, "Skill 路径格式无效。") }
    if (!Files.exists(source)) return SkillSourceInspection(false, 0, "路径不存在：$source")
    if (!Files.isReadable(source)) return SkillSourceInspection(false, 0, "路径不可读取：$source")
    if (Files.isRegularFile(source)) {
        return if (source.fileName.toString().equals("SKILL.md", ignoreCase = true)) {
            SkillSourceInspection(true, 1, "扫描成功 · 发现 1 个 Skill")
        } else {
            SkillSourceInspection(false, 0, "请选择名为 SKILL.md 的文件。")
        }
    }
    if (!Files.isDirectory(source)) return SkillSourceInspection(false, 0, "该路径不是文件或目录。")
    val discovered = runCatching {
        var count = if (Files.isRegularFile(source.resolve("SKILL.md"))) 1 else 0
        Files.newDirectoryStream(source).use { children ->
            children.forEach { child ->
                if (Files.isDirectory(child) && Files.isRegularFile(child.resolve("SKILL.md"))) count++
            }
        }
        count
    }.getOrElse { error ->
        return SkillSourceInspection(false, 0, "扫描失败：${error.message ?: error::class.java.simpleName}")
    }
    return if (discovered > 0) {
        SkillSourceInspection(true, discovered, "扫描成功 · 发现 $discovered 个 Skill")
    } else {
        SkillSourceInspection(false, 0, "未发现 SKILL.md；支持目录本身或一级子目录。")
    }
}

internal fun resolveSkillSourcePath(value: String, projectBasePath: String?): Path {
    val trimmed = value.trim()
    val expanded = if (trimmed == "~" || trimmed.startsWith("~/")) {
        Path.of(System.getProperty("user.home")).resolve(trimmed.removePrefix("~/").removePrefix("~"))
    } else {
        Path.of(trimmed)
    }
    if (expanded.isAbsolute) return expanded.normalize()
    val base = projectBasePath?.let(Path::of) ?: Path.of(System.getProperty("user.dir"))
    return base.resolve(expanded).normalize()
}

internal fun renderCommandLine(arguments: List<String>): String = arguments.joinToString(" ") { argument ->
    if (argument.isNotEmpty() && SAFE_COMMAND_ARGUMENT.matches(argument)) argument
    else "\"${argument.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

private val SAFE_COMMAND_ARGUMENT = Regex("^[A-Za-z0-9_@%+=:,./-]+$")
