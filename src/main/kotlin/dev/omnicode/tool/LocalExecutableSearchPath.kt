package dev.omnicode.tool

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bounded search path for locating user-installed executables such as `npx`, `uvx` or `docker`.
 *
 * IDEs launched from Finder/Dock/desktop shortcuts inherit a minimal PATH without the user's
 * shell configuration, so Node installed through nvm/Homebrew/Volta (and uv tools in
 * `~/.local/bin`) are invisible to `System.getenv("PATH")`. This object combines, in order:
 *
 * 1. the IDE process PATH,
 * 2. the PATH the IntelliJ Platform captured from the user's login shell,
 * 3. well-known per-user and system package-manager directories, including versioned
 *    nvm/fnm installations (newest first, bounded).
 *
 * The result is used both to resolve the executable before approval and as the PATH of the
 * launched child process, so `#!/usr/bin/env node` wrapper scripts can find their runtime.
 */
internal object LocalExecutableSearchPath {
    private const val MAX_VERSIONED_INSTALL_DIRS = 8

    fun directories(): List<String> = directories(
        processPath = System.getenv("PATH"),
        shellPath = shellCapturedPath(),
        home = System.getProperty("user.home").orEmpty(),
        windows = isWindows(),
        env = System.getenv(),
    )

    internal fun directories(
        processPath: String?,
        shellPath: String?,
        home: String,
        windows: Boolean,
        env: Map<String, String>,
    ): List<String> = linkedSetOf<String>().apply {
        processPath?.split(File.pathSeparator)?.forEach { add(it) }
        shellPath?.split(File.pathSeparator)?.forEach { add(it) }
        if (home.isNotBlank()) {
            add("$home/.local/bin")
            add("$home/.npm-global/bin")
            add("$home/.npm/bin")
            add("$home/.volta/bin")
            add("$home/.bun/bin")
            add("$home/.deno/bin")
            add("$home/.cargo/bin")
            add("$home/.asdf/shims")
            add("$home/bin")
            addAll(versionedInstallBins(Path.of(home, ".nvm", "versions", "node"), "bin"))
            addAll(versionedInstallBins(Path.of(home, ".local", "share", "fnm", "node-versions"), "installation/bin"))
            addAll(
                versionedInstallBins(
                    Path.of(home, "Library", "Application Support", "fnm", "node-versions"),
                    "installation/bin",
                ),
            )
        }
        if (windows) {
            env["APPDATA"]?.takeIf(String::isNotBlank)?.let { add("$it\\npm") }
            env["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let { add("$it\\Programs\\nodejs") }
            env["ProgramFiles"]?.takeIf(String::isNotBlank)?.let { add("$it\\nodejs") }
        } else {
            add("/opt/homebrew/bin")
            add("/usr/local/bin")
            add("/opt/local/bin")
            add("/usr/bin")
            add("/bin")
        }
    }.filter(String::isNotBlank)

    /** Candidate file names for a bare command, honouring Windows launcher extensions. */
    fun candidateNames(command: String, windows: Boolean = isWindows()): List<String> =
        if (windows && '.' !in command) listOf(command, "$command.cmd", "$command.exe", "$command.bat")
        else listOf(command)

    /** PATH value for a launched child, leading with the resolved executable's own directory. */
    fun launchPathValue(executableDirectory: Path?): String = linkedSetOf<String>().apply {
        executableDirectory?.toString()?.let(::add)
        addAll(directories())
    }.joinToString(File.pathSeparator)

    /**
     * Newest-first bins of versioned tool installations such as `~/.nvm/versions/node/v22.1.0/bin`.
     * Bounded so a pathological directory cannot bloat the search path or the audit trail.
     */
    private fun versionedInstallBins(root: Path, binSuffix: String): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        val versions = runCatching {
            Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .toList()
            }
        }.getOrDefault(emptyList())
        return versions
            .sortedWith(compareByDescending(::numericVersionKey))
            .take(MAX_VERSIONED_INSTALL_DIRS)
            .mapNotNull { version ->
                val bin = root.resolve(version).resolve(binSuffix)
                bin.toString().takeIf { Files.isDirectory(bin) }
            }
    }

    /** Orders `v10.1.0` above `v9.9.9` where plain lexicographic sorting would not. */
    private fun numericVersionKey(version: String): Long {
        val numbers = Regex("[0-9]+").findAll(version).take(3)
            .map { it.value.take(6).toLong() }
            .toList()
        return numbers.getOrElse(0) { 0 } * 1_000_000_000_000L +
            numbers.getOrElse(1) { 0 } * 1_000_000L +
            numbers.getOrElse(2) { 0 }
    }

    /**
     * PATH from the login-shell environment the IntelliJ Platform captures at startup. Guarded
     * because headless test environments may not have the capture available.
     */
    private fun shellCapturedPath(): String? = runCatching {
        com.intellij.util.EnvironmentUtil.getEnvironmentMap()["PATH"]
    }.getOrNull()

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")
}
