package dev.omnicode.service

import com.intellij.openapi.project.Project
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.tool.AgentTool
import dev.omnicode.tool.ToolExecutionContext
import dev.omnicode.tool.ToolExecutionResult
import dev.omnicode.tool.ToolEffect
import dev.omnicode.tool.objectSchema
import dev.omnicode.tool.stringProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

data class SkillDefinition(
    val name: String,
    val description: String,
    val instructions: String,
    val source: Path,
)

class SkillLibrary(
    private val project: Project,
) {
    suspend fun list(): List<SkillDefinition> = withContext(Dispatchers.IO) {
        val configured = OmniCodePlatformSettingsService.getInstance().snapshot().skillSources
            .filter { it.enabled && it.path.isNotBlank() }
        configured.flatMap { source -> discover(resolve(source.path)) }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
            .take(MAX_SKILLS)
    }

    private fun discover(path: Path): List<SkillDefinition> {
        if (path.isRegularFile() && path.fileName.toString().equals("SKILL.md", ignoreCase = true)) {
            return listOfNotNull(readSkill(path))
        }
        if (!path.isDirectory()) return emptyList()
        val candidates = mutableListOf<Path>()
        path.resolve("SKILL.md").takeIf(Path::isRegularFile)?.let(candidates::add)
        Files.newDirectoryStream(path).use { children ->
            children.asSequence()
                .filter(Path::isDirectory)
                .map { it.resolve("SKILL.md") }
                .filter(Path::isRegularFile)
                .take(MAX_SKILLS)
                .forEach(candidates::add)
        }
        return candidates.mapNotNull(::readSkill)
    }

    private fun readSkill(path: Path): SkillDefinition? {
        val text = runCatching { Files.readString(path).take(MAX_SKILL_CHARS) }.getOrNull() ?: return null
        if (text.isBlank()) return null
        val frontmatter = if (text.startsWith("---")) {
            text.substringAfter("---").substringBefore("---")
        } else ""
        fun field(name: String): String = frontmatter.lineSequence()
            .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"', '\'')
            .orEmpty()
        val fallbackName = path.parent?.fileName?.toString().orEmpty().ifBlank { "skill" }
        return SkillDefinition(
            name = field("name").ifBlank { fallbackName },
            description = field("description").ifBlank { "Instructions from $fallbackName" }.take(500),
            instructions = text,
            source = path,
        )
    }

    private fun resolve(value: String): Path {
        val expanded = if (value == "~" || value.startsWith("~/")) {
            Path.of(System.getProperty("user.home")).resolve(value.removePrefix("~/").removePrefix("~"))
        } else Path.of(value)
        if (expanded.isAbsolute) return expanded.normalize()
        val root = project.basePath?.let(Path::of) ?: Path.of(System.getProperty("user.dir"))
        return root.resolve(expanded).normalize()
    }

    companion object {
        private const val MAX_SKILLS = 100
        private const val MAX_SKILL_CHARS = 60_000
    }
}

class ListSkillsTool(
    private val library: SkillLibrary,
) : AgentTool {
    override val name: String = "list_skills"
    override val description: String = "List user-configured OmniCode skills that can provide specialized instructions."
    override val dangerous: Boolean = false
    override val effect: ToolEffect = ToolEffect.READ_ONLY
    override val inputSchema = objectSchema { }

    override suspend fun execute(arguments: com.google.gson.JsonObject, context: ToolExecutionContext): ToolExecutionResult {
        val skills = library.list()
        val output = if (skills.isEmpty()) {
            "No enabled skills are configured."
        } else skills.joinToString("\n") { "${it.name}: ${it.description}" }
        return ToolExecutionResult(output)
    }
}

class LoadSkillTool(
    private val library: SkillLibrary,
) : AgentTool {
    override val name: String = "load_skill"
    override val description: String = "Load one skill's full instructions by its exact name after using list_skills."
    override val dangerous: Boolean = false
    override val effect: ToolEffect = ToolEffect.READ_ONLY
    override val inputSchema = objectSchema(required = listOf("name")) {
        stringProperty("name", "Exact skill name returned by list_skills.")
    }

    override suspend fun execute(arguments: com.google.gson.JsonObject, context: ToolExecutionContext): ToolExecutionResult {
        val requested = arguments.get("name")?.asString?.trim().orEmpty()
        val skill = library.list().firstOrNull { it.name.equals(requested, ignoreCase = true) }
            ?: return ToolExecutionResult("UNKNOWN_SKILL: $requested", true)
        return ToolExecutionResult(
            "Skill: ${skill.name}\nSource: ${skill.source}\n\n${skill.instructions}",
        )
    }
}
