package dev.omnicode.tool

import com.intellij.openapi.project.Project
import dev.omnicode.service.ProjectAiExclusionPolicy
import java.nio.file.Path

/** Applies the same model-visible project boundary before any general filesystem tool reads data. */
internal class ProjectContextToolAccess private constructor(
    private val root: Path,
    private val policy: ProjectAiExclusionPolicy,
) {
    fun rejectionForRequestedPath(relativePath: String): ToolExecutionResult? {
        if (policy.failClosed) return excludedToolResult()
        if (relativePath == "." || relativePath == "./") return null
        if (policy.isExcluded(relativePath)) return excludedToolResult()
        return null
    }

    fun isExcluded(path: Path): Boolean {
        if (policy.failClosed) return true
        val normalized = path.toAbsolutePath().normalize()
        if (normalized == root) return false
        if (!normalized.startsWith(root)) return true
        val relative = root.relativize(normalized).joinToString("/") { it.toString() }
        return policy.isExcluded(relative)
    }

    companion object {
        fun load(project: Project): ProjectContextToolAccess = ProjectContextToolAccess(
            root = ProjectPathGuard.root(project),
            policy = ProjectAiExclusionPolicy.load(project),
        )
    }
}

internal fun excludedToolResult(): ToolExecutionResult = ToolExecutionResult(
    content = "$PROJECT_CONTEXT_EXCLUDED_CODE: path is not available to model-visible project tools.",
    isError = true,
)

internal const val PROJECT_CONTEXT_EXCLUDED_CODE = "PROJECT_CONTEXT_EXCLUDED"
