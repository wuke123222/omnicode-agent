package dev.omnicode.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import dev.omnicode.service.ProjectContextPathPolicy
import dev.omnicode.service.ProjectAiExclusionPolicy
import dev.omnicode.service.isPathExcluded

class ProjectContextPersistentState {
    var pinnedPaths: MutableList<String> = mutableListOf()
    var excludedPaths: MutableList<String> = mutableListOf()
}

data class ProjectContextSettings(
    val pinnedPaths: List<String> = emptyList(),
    val excludedPaths: List<String> = emptyList(),
)

/** Project-local context choices. Paths are canonical project-relative values only. */
@Service(Service.Level.PROJECT)
@State(
    name = "OmniCodeProjectContext",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ProjectContextSettingsService(private val project: Project) :
    PersistentStateComponent<ProjectContextPersistentState> {
    private var current = ProjectContextSettings()

    @Synchronized
    override fun getState(): ProjectContextPersistentState = current.toPersistentState()

    @Synchronized
    override fun loadState(state: ProjectContextPersistentState) {
        val root = runCatching { ProjectContextPathPolicy.projectRoot(project) }.getOrNull()
        if (root == null) {
            current = ProjectContextSettings()
            return
        }
        val exclusions = normalizePaths(
            root = root,
            paths = state.excludedPaths,
            maximum = MAX_EXCLUDED_PROJECT_PATHS,
            tolerateInvalid = true,
        ).collapseExclusions()
        val policy = ProjectAiExclusionPolicy.load(root, exclusions)
        val pinned = normalizePaths(
            root = root,
            paths = state.pinnedPaths,
            maximum = MAX_PINNED_PROJECT_PATHS,
            tolerateInvalid = true,
        ).filterNot(policy::isExcluded)
        current = ProjectContextSettings(pinned, exclusions)
    }

    @Synchronized
    fun snapshot(): ProjectContextSettings = current.copy(
        pinnedPaths = current.pinnedPaths.toList(),
        excludedPaths = current.excludedPaths.toList(),
    )

    @Synchronized
    fun setPinnedPaths(paths: Collection<String>): ProjectContextSettings {
        require(paths.size <= MAX_PINNED_PROJECT_PATHS) {
            "At most $MAX_PINNED_PROJECT_PATHS project paths can be pinned"
        }
        val normalized = normalizePaths(projectRoot(), paths, MAX_PINNED_PROJECT_PATHS, tolerateInvalid = false)
        val policy = exclusionPolicy()
        require(normalized.none(policy::isExcluded)) {
            "An ignored, sensitive, or explicitly excluded path cannot be pinned"
        }
        current = current.copy(pinnedPaths = normalized)
        return snapshot()
    }

    @Synchronized
    fun setExcludedPaths(paths: Collection<String>): ProjectContextSettings {
        require(paths.size <= MAX_EXCLUDED_PROJECT_PATHS) {
            "At most $MAX_EXCLUDED_PROJECT_PATHS project paths can be excluded"
        }
        val normalized = normalizePaths(projectRoot(), paths, MAX_EXCLUDED_PROJECT_PATHS, tolerateInvalid = false)
            .collapseExclusions()
        current = ProjectContextSettings(
            pinnedPaths = current.pinnedPaths.filterNot(ProjectAiExclusionPolicy.load(projectRoot(), normalized)::isExcluded),
            excludedPaths = normalized,
        )
        return snapshot()
    }

    @Synchronized
    fun pin(relativePath: String): ProjectContextSettings {
        val normalized = ProjectContextPathPolicy.normalizeRelative(projectRoot(), relativePath)
        require(!exclusionPolicy().isExcluded(normalized)) {
            "An ignored, sensitive, or explicitly excluded path cannot be pinned"
        }
        if (normalized in current.pinnedPaths) return snapshot()
        require(current.pinnedPaths.size < MAX_PINNED_PROJECT_PATHS) {
            "At most $MAX_PINNED_PROJECT_PATHS project paths can be pinned"
        }
        current = current.copy(pinnedPaths = current.pinnedPaths + normalized)
        return snapshot()
    }

    @Synchronized
    fun unpin(relativePath: String): ProjectContextSettings {
        val normalized = ProjectContextPathPolicy.normalizeRelative(projectRoot(), relativePath)
        current = current.copy(pinnedPaths = current.pinnedPaths.filterNot(normalized::equals))
        return snapshot()
    }

    @Synchronized
    fun exclude(relativePath: String): ProjectContextSettings {
        val normalized = ProjectContextPathPolicy.normalizeRelative(projectRoot(), relativePath)
        if (isPathExcluded(normalized, current.excludedPaths)) return snapshot()
        require(current.excludedPaths.size < MAX_EXCLUDED_PROJECT_PATHS) {
            "At most $MAX_EXCLUDED_PROJECT_PATHS project paths can be excluded"
        }
        val exclusions = (current.excludedPaths + normalized).collapseExclusions()
        current = ProjectContextSettings(
            pinnedPaths = current.pinnedPaths.filterNot { isPathExcluded(it, exclusions) },
            excludedPaths = exclusions,
        )
        return snapshot()
    }

    @Synchronized
    fun include(relativePath: String): ProjectContextSettings {
        val normalized = ProjectContextPathPolicy.normalizeRelative(projectRoot(), relativePath)
        current = current.copy(excludedPaths = current.excludedPaths.filterNot(normalized::equals))
        return snapshot()
    }

    @Synchronized
    fun isExcluded(relativePath: String): Boolean {
        val normalized = ProjectContextPathPolicy.normalizeRelative(projectRoot(), relativePath)
        return exclusionPolicy().isExcluded(normalized)
    }

    @Synchronized
    fun clear(): ProjectContextSettings {
        current = ProjectContextSettings()
        return snapshot()
    }

    private fun projectRoot() = ProjectContextPathPolicy.projectRoot(project)

    private fun exclusionPolicy(): ProjectAiExclusionPolicy =
        ProjectAiExclusionPolicy.load(projectRoot(), current.excludedPaths)

    private fun ProjectContextSettings.toPersistentState(): ProjectContextPersistentState =
        ProjectContextPersistentState().also { state ->
            state.pinnedPaths = pinnedPaths.toMutableList()
            state.excludedPaths = excludedPaths.toMutableList()
        }

    companion object {
        fun getInstance(project: Project): ProjectContextSettingsService =
            project.getService(ProjectContextSettingsService::class.java)
    }
}

private fun normalizePaths(
    root: java.nio.file.Path,
    paths: Collection<String>,
    maximum: Int,
    tolerateInvalid: Boolean,
): List<String> {
    val result = linkedSetOf<String>()
    var examined = 0
    for (path in paths) {
        if (examined >= maximum) break
        examined++
        val normalized = if (tolerateInvalid) {
            runCatching { ProjectContextPathPolicy.normalizeRelative(root, path) }.getOrNull() ?: continue
        } else {
            ProjectContextPathPolicy.normalizeRelative(root, path)
        }
        result += normalized
    }
    return result.toList()
}

private fun List<String>.collapseExclusions(): List<String> {
    val collapsed = mutableListOf<String>()
    for (path in this) {
        if (isPathExcluded(path, collapsed)) continue
        collapsed.removeAll { existing -> isPathExcluded(existing, listOf(path)) }
        collapsed += path
    }
    return collapsed
}

const val MAX_PINNED_PROJECT_PATHS = 64
const val MAX_EXCLUDED_PROJECT_PATHS = 128
