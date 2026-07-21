package dev.omnicode.service

import com.intellij.openapi.project.Project
import dev.omnicode.settings.ProjectContextSettingsService
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class ProjectAiExclusionPolicyIssueReason {
    UNSAFE_IGNORE_FILE,
    TRUNCATED_IGNORE_FILE,
    PATTERN_LIMIT,
}

internal data class ProjectAiExclusionPolicyIssue(
    val relativePath: String,
    val reason: ProjectAiExclusionPolicyIssueReason,
    val detail: String,
)

/**
 * One fail-closed boundary for every repository path that may be sent to a model automatically.
 * Explicit project exclusions cannot be undone by a negated ignore pattern, and common credential
 * files are always excluded even when a repository attempts to opt them back in.
 */
internal class ProjectAiExclusionPolicy private constructor(
    private val explicitExclusions: List<String>,
    private val ignoreMatcher: ProjectIgnoreMatcher,
    val issues: List<ProjectAiExclusionPolicyIssue>,
    val failClosed: Boolean,
) {
    fun isExcluded(relativePath: String): Boolean {
        val normalized = normalizePortableRelativePath(relativePath) ?: return true
        return failClosed ||
            isSensitiveAutomaticContextPath(normalized) ||
            isPathExcluded(normalized, explicitExclusions) ||
            ignoreMatcher.isIgnored(normalized)
    }

    fun exclusionDetail(relativePath: String): String = when {
        failClosed -> "Project ignore policy could not be loaded safely; automatic project context is disabled"
        normalizePortableRelativePath(relativePath) == null -> "Project path is invalid"
        isSensitiveAutomaticContextPath(requireNotNull(normalizePortableRelativePath(relativePath))) ->
            "Sensitive credential or private-key file is never included in automatic project context"
        isPathExcluded(requireNotNull(normalizePortableRelativePath(relativePath)), explicitExclusions) ->
            "Path is explicitly excluded from AI project context"
        else -> "Path is excluded by .gitignore, .aiignore, or .omnicodeignore"
    }

    companion object {
        fun load(project: Project): ProjectAiExclusionPolicy {
            val root = ProjectContextPathPolicy.projectRoot(project)
            val explicitExclusions = runCatching {
                ProjectContextSettingsService.getInstance(project).snapshot().excludedPaths
            }.getOrDefault(emptyList())
            return load(root, explicitExclusions)
        }

        fun load(
            projectRoot: Path,
            explicitExclusions: Collection<String> = emptyList(),
            maxIgnoreFileBytes: Int = DEFAULT_MAX_IGNORE_FILE_BYTES,
            maxIgnorePatterns: Int = DEFAULT_MAX_IGNORE_PATTERNS,
        ): ProjectAiExclusionPolicy {
            require(maxIgnoreFileBytes > 0)
            require(maxIgnorePatterns > 0)
            val root = ProjectContextPathPolicy.root(projectRoot)
            val normalizedExclusions = explicitExclusions.mapNotNull(::normalizePortableRelativePath)
            val patterns = mutableListOf<ProjectIgnorePattern>()
            val issues = mutableListOf<ProjectAiExclusionPolicyIssue>()
            var failedClosed = normalizedExclusions.size != explicitExclusions.size

            for (name in PROJECT_AI_IGNORE_FILES) {
                val file = root.resolve(name)
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue
                val read = try {
                    BoundedProjectFileReader.read(root, file, maxIgnoreFileBytes)
                } catch (error: UnsafeProjectContextFileException) {
                    failedClosed = true
                    issues += ProjectAiExclusionPolicyIssue(
                        name,
                        ProjectAiExclusionPolicyIssueReason.UNSAFE_IGNORE_FILE,
                        error.message ?: "Ignore file cannot be read safely",
                    )
                    continue
                }
                if (read.truncated) {
                    failedClosed = true
                    issues += ProjectAiExclusionPolicyIssue(
                        name,
                        ProjectAiExclusionPolicyIssueReason.TRUNCATED_IGNORE_FILE,
                        "Ignore file exceeds the bounded read limit",
                    )
                    continue
                }
                val remaining = maxIgnorePatterns - patterns.size
                if (remaining <= 0) {
                    if (read.text.lineSequence().any(::isPotentialIgnorePattern)) {
                        failedClosed = true
                        issues += ProjectAiExclusionPolicyIssue(
                            name,
                            ProjectAiExclusionPolicyIssueReason.PATTERN_LIMIT,
                            "Combined project ignore pattern limit reached",
                        )
                    }
                    continue
                }
                val parsed = ProjectIgnoreMatcher.parseDetailed(read.text, remaining)
                patterns += parsed.patterns
                if (parsed.truncated) {
                    failedClosed = true
                    issues += ProjectAiExclusionPolicyIssue(
                        name,
                        ProjectAiExclusionPolicyIssueReason.PATTERN_LIMIT,
                        "Combined project ignore pattern limit reached",
                    )
                }
            }
            return ProjectAiExclusionPolicy(
                explicitExclusions = normalizedExclusions,
                ignoreMatcher = ProjectIgnoreMatcher(patterns),
                issues = issues,
                failClosed = failedClosed,
            )
        }
    }
}

internal fun isSensitiveAutomaticContextPath(relativePath: String): Boolean {
    val normalized = normalizePortableRelativePath(relativePath) ?: return true
    val lower = normalized.lowercase()
    val segments = lower.split('/')
    val name = segments.last()
    if (segments.any(SENSITIVE_CONTEXT_DIRECTORY_NAMES::contains)) return true
    if (segments.any { it == ".env" || it.startsWith(".env.") }) return true
    if (name in SENSITIVE_CONTEXT_FILE_NAMES) return true
    if (SENSITIVE_CONTEXT_SUFFIXES.any(name::endsWith)) return true
    if (SENSITIVE_CONTEXT_PATH_SUFFIXES.any { lower == it || lower.endsWith("/$it") }) return true
    if (name.endsWith(".json") && (
            name.startsWith("credentials") ||
                name.startsWith("client_secret") ||
                name.startsWith("service-account") ||
                name.contains("service_account") ||
                name.startsWith("secrets")
            )
    ) return true
    if ((name.startsWith("secrets.") || name.startsWith("credentials.")) &&
        name.substringAfterLast('.', "") in SENSITIVE_TEXT_EXTENSIONS
    ) return true
    return false
}

private fun normalizePortableRelativePath(value: String): String? {
    if (value.isBlank() || value.length > 1_024) return null
    if (value.any { it == '\u0000' || it == '\n' || it == '\r' || it.isISOControl() }) return null
    val portable = value.replace('\\', '/').removePrefix("./")
    if (portable.startsWith('/') || WINDOWS_PROJECT_PATH.matches(portable)) return null
    val segments = portable.split('/').filter(String::isNotEmpty)
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null
    return segments.joinToString("/")
}

private fun isPotentialIgnorePattern(rawLine: String): Boolean {
    val line = rawLine.trimEnd()
    return line.isNotEmpty() && !line.startsWith('#')
}

internal val PROJECT_AI_IGNORE_FILES = listOf(".gitignore", ".aiignore", ".omnicodeignore")

private val SENSITIVE_CONTEXT_FILE_NAMES = setOf(
    "credentials",
    "credentials.json",
    ".credentials",
    ".git-credentials",
    ".netrc",
    ".npmrc",
    ".pypirc",
    "id_rsa",
    "id_dsa",
    "id_ecdsa",
    "id_ed25519",
    "local.properties",
    "gradle.properties",
    "terraform.tfstate",
    "terraform.tfstate.backup",
)
private val SENSITIVE_CONTEXT_DIRECTORY_NAMES = setOf(".ssh", ".aws", ".gnupg", ".kube", ".docker")
private val SENSITIVE_CONTEXT_SUFFIXES = setOf(
    ".pem",
    ".key",
    ".p12",
    ".pfx",
    ".ppk",
    ".jks",
    ".keystore",
    ".kdbx",
    ".mobileprovision",
)
private val SENSITIVE_CONTEXT_PATH_SUFFIXES = setOf(
    ".aws/credentials",
    ".docker/config.json",
    ".kube/config",
    ".config/gcloud/application_default_credentials.json",
)
private val SENSITIVE_TEXT_EXTENSIONS = setOf(
    "json",
    "yaml",
    "yml",
    "toml",
    "ini",
    "conf",
    "properties",
    "xml",
)
private val WINDOWS_PROJECT_PATH = Regex("^[A-Za-z]:.*")
private const val DEFAULT_MAX_IGNORE_FILE_BYTES = 256 * 1024
private const val DEFAULT_MAX_IGNORE_PATTERNS = 10_000
