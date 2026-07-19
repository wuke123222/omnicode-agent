package dev.omnicode.tool

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object ProjectPathGuard {
    fun root(project: Project): Path = Path.of(
        requireNotNull(project.basePath) { "The project has no filesystem root" },
    ).toAbsolutePath().normalize()

    fun resolve(project: Project, relativePath: String): Path {
        require(relativePath.isNotBlank()) { "path must not be blank" }
        val root = root(project)
        val resolved = root.resolve(relativePath).normalize().toAbsolutePath()
        return validate(project, resolved)
    }

    fun validate(project: Project, candidate: Path): Path {
        val root = root(project)
        val resolved = candidate.normalize().toAbsolutePath()
        require(resolved.startsWith(root)) { "Path escapes the project root" }
        rejectSensitivePath(root, resolved)

        var cursor = root
        root.relativize(resolved).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Symbolic links are not allowed in agent paths" }
            }
        }

        val realRoot = root.toRealPath()
        val existingAncestor = generateSequence(resolved) { it.parent }
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: root
        val realAncestor = existingAncestor.toRealPath()
        require(realAncestor.startsWith(realRoot)) { "Path escapes the project through a symbolic link" }
        return resolved
    }

    private fun rejectSensitivePath(root: Path, path: Path) {
        val segments = root.relativize(path).map { it.toString().lowercase() }
        require(segments.none(::isSensitiveSegment)) {
            "Access to credential or private-key files is blocked"
        }
    }
}

private fun isSensitiveSegment(value: String): Boolean =
    value in SENSITIVE_SEGMENTS ||
        (value.startsWith(".env.") && value !in SAFE_ENV_TEMPLATES) ||
        PRIVATE_KEY_SUFFIXES.any(value::endsWith)

private val SENSITIVE_SEGMENTS = setOf(
    ".env", ".env.local", ".env.production", ".aws", ".ssh", ".gnupg", ".git-credentials",
    ".npmrc", ".pypirc", "id_rsa", "id_ed25519", "credentials.json", "service-account.json",
)
private val SAFE_ENV_TEMPLATES = setOf(".env.example", ".env.sample", ".env.template")
private val PRIVATE_KEY_SUFFIXES = setOf(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")
