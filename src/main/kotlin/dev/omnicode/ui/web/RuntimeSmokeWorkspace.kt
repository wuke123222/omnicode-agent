package dev.omnicode.ui.web

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Comparator

/** An empty cwd for an explicitly approved local CLI conversation test. */
internal object RuntimeSmokeWorkspace {
    private const val PREFIX = "omnicode-cli-smoke-"

    fun create(): Path = Files.createTempDirectory(PREFIX).toAbsolutePath().normalize()

    fun cleanup(workspace: Path) {
        require(workspace.fileName?.toString()?.startsWith(PREFIX) == true) {
            "Refusing to clean a directory outside the CLI smoke-test namespace."
        }
        if (!Files.exists(workspace, NOFOLLOW_LINKS)) return
        // Files.walk does not follow symbolic links unless explicitly requested. A CLI-created
        // link to a project file is removed as a link, not traversed or used as a deletion path.
        Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
