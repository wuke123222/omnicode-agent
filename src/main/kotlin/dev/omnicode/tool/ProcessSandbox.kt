package dev.omnicode.tool

import dev.omnicode.settings.SandboxMode
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SandboxEnforcement {
    MACOS_SANDBOX_EXEC,
    LINUX_BUBBLEWRAP,
    WINDOWS_APPCONTAINER,
    NONE,
    UNAVAILABLE,
}

data class SandboxCapability(
    val mode: SandboxMode,
    val enforcement: SandboxEnforcement,
    val available: Boolean,
    val enforced: Boolean,
    val summary: String,
)

class SandboxUnavailableException(message: String) : IllegalStateException(message)

internal data class ProcessSandboxRequest(
    val mode: SandboxMode,
    val workspaceRoot: Path,
    val cwd: Path,
    val requestedExecutable: String,
    val executable: Path,
    val arguments: List<String>,
    val readOnlyWorkspace: Boolean = false,
    /** Read-only runtime directories required by an interpreter behind npx/uvx. */
    val runtimeReadPaths: List<Path> = emptyList(),
)

internal data class ProcessSandboxPlan(
    val mode: SandboxMode,
    val workspaceRoot: Path,
    val cwd: Path,
    val executable: Path,
    val executableIdentity: ExecutableIdentity,
    val sandboxExecutableIdentity: ExecutableIdentity?,
    val commandArgv: List<String>,
    val launchArgv: List<String>,
    val environmentOverrides: Map<String, String>,
    val capability: SandboxCapability,
    val readOnlyWorkspace: Boolean = false,
    val runtimeReadPaths: List<Path> = emptyList(),
)

internal data class ExecutableIdentity(
    val realPath: Path,
    val fileKey: String?,
    val size: Long,
    val lastModifiedMillis: Long,
)

private enum class SandboxPlatform {
    MACOS,
    LINUX,
    WINDOWS,
    OTHER,
}

private data class DefaultSandboxConfiguration(
    val osName: String,
    val executable: Path,
    val probe: (Path) -> Boolean,
)

/**
 * Converts an already tokenized command into an OS-enforced launch plan.
 * No shell is involved and no command value is interpolated into the profile.
 */
class ProcessSandbox internal constructor(
    private val osName: String,
    private val sandboxExecutable: Path,
    private val availabilityProbe: (Path) -> Boolean,
    private val userHome: Path?,
) {
    internal constructor(
        osName: String,
        sandboxExecutable: Path,
        availabilityProbe: (Path) -> Boolean,
    ) : this(
        osName = osName,
        sandboxExecutable = sandboxExecutable,
        availabilityProbe = availabilityProbe,
        userHome = System.getProperty("user.home")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of),
    )

    constructor() : this(defaultSandboxConfiguration())

    private constructor(configuration: DefaultSandboxConfiguration) : this(
        osName = configuration.osName,
        sandboxExecutable = configuration.executable,
        availabilityProbe = configuration.probe,
    )

    @Volatile
    private var verifiedSandboxBackend: Pair<Path, ExecutableIdentity>? = null
    private val workspaceCapability: SandboxCapability by lazy(::detectWorkspaceCapability)

    fun capability(mode: SandboxMode): SandboxCapability = when (mode) {
        SandboxMode.WORKSPACE_WRITE -> workspaceCapability
        SandboxMode.DANGER_FULL_ACCESS -> SandboxCapability(
            mode = mode,
            enforcement = SandboxEnforcement.NONE,
            available = true,
            enforced = false,
            summary = "DANGER_FULL_ACCESS: no OS-level filesystem or network sandbox",
        )
    }

    /**
     * Materializes host state only after the command is approved and revalidated. macOS needs
     * a reserved HOME below the workspace and rejects symlinks. Linux creates HOME/tmp entirely
     * inside bubblewrap's private mount namespace, so activation intentionally writes nothing.
     */
    internal fun activate(plan: ProcessSandboxPlan) {
        if (plan.mode != SandboxMode.WORKSPACE_WRITE) return
        if (plan.readOnlyWorkspace) return
        require(plan.capability.available && plan.capability.enforced) {
            "WORKSPACE_WRITE launch requires an enforced sandbox capability"
        }

        // bubblewrap creates HOME and tmp inside its private mount namespace. Creating host
        // directories here would either leak state across commands or pollute the project.
        if (plan.capability.enforcement in setOf(
                SandboxEnforcement.LINUX_BUBBLEWRAP,
                SandboxEnforcement.WINDOWS_APPCONTAINER,
            )
        ) return

        val configuredHome = plan.environmentOverrides["HOME"]
            ?.let { Path.of(it) }
            ?.toAbsolutePath()
            ?.normalize()
            ?: error("WORKSPACE_WRITE plan is missing its sandbox HOME")
        val expectedHome = plan.workspaceRoot.resolve(SANDBOX_HOME_DIRECTORY).normalize()
        require(configuredHome == expectedHome && configuredHome.startsWith(plan.workspaceRoot)) {
            "Sandbox HOME must be the reserved directory inside the real workspace"
        }

        Files.createDirectories(configuredHome)
        require(Files.isDirectory(configuredHome, LinkOption.NOFOLLOW_LINKS)) {
            "Sandbox HOME must not be a symlink: $configuredHome"
        }
        val realHome = configuredHome.toRealPath()
        require(realHome.startsWith(plan.workspaceRoot)) {
            "Sandbox HOME escaped the real workspace"
        }
        runCatching {
            Files.setPosixFilePermissions(
                realHome,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    internal fun prepare(request: ProcessSandboxRequest): ProcessSandboxPlan {
        require(request.arguments.none(::containsForbiddenControlCharacter)) {
            "Arguments must not contain NUL or line-break characters"
        }
        require(!request.readOnlyWorkspace || request.mode == SandboxMode.WORKSPACE_WRITE) {
            "A read-only workspace command requires the enforced WORKSPACE_WRITE sandbox backend"
        }

        val workspaceRoot = request.workspaceRoot.toRealPath()
        require(Files.isDirectory(workspaceRoot)) { "Workspace root is not a directory" }
        val cwd = request.cwd.toRealPath()
        require(cwd.startsWith(workspaceRoot)) { "Sandbox working directory must be inside the real workspace" }

        val executable = request.executable.toRealPath()
        require(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
            "Executable is not a runnable regular file: $executable"
        }
        validateDirectExecution(request.requestedExecutable, executable)
        val executableIdentity = executableIdentity(executable)
        val runtimeReadPaths = normalizeRuntimeReadPaths(request.runtimeReadPaths, workspaceRoot)

        val commandArgv = listOf(executable.toString()) + request.arguments
        return when (request.mode) {
            SandboxMode.WORKSPACE_WRITE -> prepareWorkspacePlan(
                request = request,
                workspaceRoot = workspaceRoot,
                cwd = cwd,
                executable = executable,
                executableIdentity = executableIdentity,
                commandArgv = commandArgv,
                runtimeReadPaths = runtimeReadPaths,
            )
            SandboxMode.DANGER_FULL_ACCESS -> ProcessSandboxPlan(
                mode = request.mode,
                workspaceRoot = workspaceRoot,
                cwd = cwd,
                executable = executable,
                executableIdentity = executableIdentity,
                sandboxExecutableIdentity = null,
                commandArgv = commandArgv,
                launchArgv = commandArgv,
                environmentOverrides = emptyMap(),
                capability = capability(request.mode),
                readOnlyWorkspace = false,
                runtimeReadPaths = runtimeReadPaths,
            )
        }
    }

    private fun prepareWorkspacePlan(
        request: ProcessSandboxRequest,
        workspaceRoot: Path,
        cwd: Path,
        executable: Path,
        executableIdentity: ExecutableIdentity,
        commandArgv: List<String>,
        runtimeReadPaths: List<Path>,
    ): ProcessSandboxPlan {
        val capability = capability(SandboxMode.WORKSPACE_WRITE)
        if (!capability.available || !capability.enforced) {
            throw SandboxUnavailableException(capability.summary)
        }

        val requestedName = executableName(request.requestedExecutable)
        val resolvedName = executableName(executable.fileName.toString())
        val projectExecutable = executable.startsWith(workspaceRoot)
        require(projectExecutable || isApprovedSystemExecutable(requestedName, resolvedName)) {
            "WORKSPACE_WRITE blocks system executable '$requestedName'; use an approved developer command or explicitly select DANGER_FULL_ACCESS"
        }

        val (sandboxBackend, verifiedIdentity) = checkNotNull(verifiedSandboxBackend) {
            "WORKSPACE_WRITE capability did not retain a verified sandbox backend"
        }
        val currentBackend = sandboxExecutable.toRealPath()
        require(!currentBackend.startsWith(workspaceRoot)) {
            "Sandbox backend must be installed outside the project workspace"
        }
        val sandboxExecutableIdentity = executableIdentity(currentBackend)
        require(currentBackend == sandboxBackend && sandboxExecutableIdentity == verifiedIdentity) {
            "Sandbox backend changed after its enforcement probe; execution was refused"
        }
        val launchArgv = when (capability.enforcement) {
            SandboxEnforcement.MACOS_SANDBOX_EXEC -> buildMacLaunchArgv(
                sandboxBackend,
                workspaceRoot,
                commandArgv,
                request.readOnlyWorkspace,
                runtimeReadPaths,
            )
            SandboxEnforcement.LINUX_BUBBLEWRAP -> buildLinuxLaunchArgv(
                bubblewrap = sandboxBackend,
                workspaceRoot = workspaceRoot,
                cwd = cwd,
                commandArgv = commandArgv,
                userHome = userHome,
                readOnlyWorkspace = request.readOnlyWorkspace,
                runtimeReadPaths = runtimeReadPaths,
            )
            SandboxEnforcement.WINDOWS_APPCONTAINER -> buildWindowsAppContainerLaunchArgv(
                helper = sandboxBackend,
                workspaceRoot = workspaceRoot,
                cwd = cwd,
                commandArgv = commandArgv,
                readOnlyWorkspace = request.readOnlyWorkspace,
            )
            else -> throw SandboxUnavailableException(capability.summary)
        }
        val environmentOverrides = when (capability.enforcement) {
            SandboxEnforcement.LINUX_BUBBLEWRAP -> mapOf(
                "HOME" to LINUX_SANDBOX_HOME,
                "TMPDIR" to LINUX_SANDBOX_TMP,
                "TEMP" to LINUX_SANDBOX_TMP,
                "TMP" to LINUX_SANDBOX_TMP,
            )
            // The native host replaces HOME/USERPROFILE/LOCALAPPDATA/TEMP inside the child
            // with the per-AppContainer profile. Do not pass the IDE user's profile through.
            SandboxEnforcement.WINDOWS_APPCONTAINER -> emptyMap()
            else -> if (request.readOnlyWorkspace) {
                mapOf(
                    "HOME" to MACOS_READ_ONLY_HOME,
                    "TMPDIR" to MACOS_READ_ONLY_HOME,
                    "TEMP" to MACOS_READ_ONLY_HOME,
                    "TMP" to MACOS_READ_ONLY_HOME,
                )
            } else {
                mapOf(
                    "HOME" to workspaceRoot.resolve(SANDBOX_HOME_DIRECTORY).toString(),
                    "TMPDIR" to workspaceRoot.toString(),
                    "TEMP" to workspaceRoot.toString(),
                    "TMP" to workspaceRoot.toString(),
                )
            }
        }
        val effectiveCapability = if (request.readOnlyWorkspace) {
            capability.copy(summary = capability.summary.replace("workspace read/write", "workspace read-only"))
        } else capability
        return ProcessSandboxPlan(
            mode = request.mode,
            workspaceRoot = workspaceRoot,
            cwd = cwd,
            executable = executable,
            executableIdentity = executableIdentity,
            sandboxExecutableIdentity = sandboxExecutableIdentity,
            commandArgv = commandArgv,
            launchArgv = launchArgv,
            environmentOverrides = environmentOverrides,
            capability = effectiveCapability,
            readOnlyWorkspace = request.readOnlyWorkspace,
            runtimeReadPaths = runtimeReadPaths,
        )
    }

    private fun buildMacLaunchArgv(
        sandboxBackend: Path,
        workspaceRoot: Path,
        commandArgv: List<String>,
        readOnlyWorkspace: Boolean = false,
        runtimeReadPaths: List<Path> = emptyList(),
    ): List<String> = buildList {
        add(sandboxBackend.toString())
        add("-p")
        add(macProfile(readOnlyWorkspace, runtimeReadPaths))
        add("-DOMNICODE_WORKSPACE=$workspaceRoot")
        runtimeReadPaths.forEachIndexed { index, path ->
            add("-DOMNICODE_RUNTIME_$index=$path")
        }
        add("--")
        addAll(commandArgv)
    }

    private fun buildWindowsAppContainerLaunchArgv(
        helper: Path,
        workspaceRoot: Path,
        cwd: Path,
        commandArgv: List<String>,
        readOnlyWorkspace: Boolean,
    ): List<String> = buildList {
        add(helper.toString())
        add("--workspace")
        add(workspaceRoot.toString())
        add("--cwd")
        add(cwd.toString())
        add(if (readOnlyWorkspace) "--read-only" else "--read-write")
        add("--")
        addAll(commandArgv)
    }

    private fun validateDirectExecution(requestedExecutable: String, executable: Path) {
        val requestedName = executableName(requestedExecutable)
        val resolvedName = executableName(executable.fileName.toString())
        require(requestedName !in ALWAYS_BLOCKED_EXECUTABLES && resolvedName !in ALWAYS_BLOCKED_EXECUTABLES) {
            "Shells, command brokers, and privilege-escalation executables are blocked; provide a direct argv command"
        }
    }

    private fun isApprovedSystemExecutable(requestedName: String, resolvedName: String): Boolean {
        if (requestedName !in WORKSPACE_SYSTEM_EXECUTABLES) return false
        if (resolvedName in WORKSPACE_SYSTEM_EXECUTABLES) return true
        if (resolvedName in TRUSTED_RESOLVED_ENTRYPOINTS[requestedName].orEmpty()) return true
        return requestedName == "python3" && resolvedName.startsWith("python3.")
    }

    private fun executableIdentity(executable: Path): ExecutableIdentity {
        val attributes = Files.readAttributes(
            executable,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return ExecutableIdentity(
            realPath = executable,
            fileKey = attributes.fileKey()?.toString(),
            size = attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun detectWorkspaceCapability(): SandboxCapability {
        val platform = sandboxPlatform(osName)
        if (!Files.isRegularFile(sandboxExecutable) || !Files.isExecutable(sandboxExecutable)) {
            return unavailable(missingBackendMessage(platform, sandboxExecutable))
        }
        val realBackend = runCatching { sandboxExecutable.toRealPath() }.getOrNull()
            ?: return unavailable(missingBackendMessage(platform, sandboxExecutable))
        val identityBeforeProbe = runCatching { executableIdentity(realBackend) }.getOrNull()
            ?: return unavailable("WORKSPACE_WRITE unavailable: sandbox backend identity could not be verified")
        val probePassed = runCatching { availabilityProbe(realBackend) }.getOrDefault(false)
        val identityAfterProbe = runCatching { executableIdentity(realBackend) }.getOrNull()
        val stableBackend = identityAfterProbe == identityBeforeProbe &&
            runCatching { sandboxExecutable.toRealPath() == realBackend }.getOrDefault(false)
        if (platform == SandboxPlatform.WINDOWS) {
            if (!isWindowsAppContainerHelper(realBackend)) {
                return unavailable(
                    "Windows workspace-write unavailable: only the signed OmniCode AppContainer host is accepted; WSL executables and project-local helpers are not sandbox backends. Install the Windows package or use JetBrains WSL/Remote Development; no automatic downgrade occurred.",
                )
            }
            if (!probePassed || !stableBackend) {
                return unavailable(
                    if (stableBackend) {
                        "Windows workspace-write unavailable: the native AppContainer host failed its API, profile, or stdio probe. Install/repair the signed helper and retry; no automatic downgrade occurred."
                    } else {
                        "Windows workspace-write unavailable: the native AppContainer host changed during its probe; execution was refused. No automatic downgrade occurred."
                    },
                )
            }
            verifiedSandboxBackend = realBackend to identityBeforeProbe
            return SandboxCapability(
                mode = SandboxMode.WORKSPACE_WRITE,
                enforcement = SandboxEnforcement.WINDOWS_APPCONTAINER,
                available = true,
                enforced = true,
                summary = "Windows AppContainer enforced: network capability absent, user profile hidden, workspace ACL transaction scoped to the project and restored after exit",
            )
        }
        if (platform == SandboxPlatform.OTHER) {
            return unavailable("WORKSPACE_WRITE unavailable: this platform has no configured OS process sandbox; no automatic downgrade occurred")
        }
        if (!probePassed || !stableBackend) {
            return unavailable(
                when (platform) {
                    SandboxPlatform.MACOS -> if (stableBackend) {
                        "WORKSPACE_WRITE unavailable: sandbox-exec failed its read/write and network enforcement probe"
                    } else {
                        "WORKSPACE_WRITE unavailable: sandbox-exec changed during its enforcement probe"
                    }
                    SandboxPlatform.LINUX -> if (stableBackend) {
                        "WORKSPACE_WRITE unavailable: bubblewrap failed its namespace, file-boundary, or network-isolation probe. Ensure unprivileged user namespaces are enabled; no automatic downgrade occurred."
                    } else {
                        "WORKSPACE_WRITE unavailable: bubblewrap changed during its enforcement probe; no automatic downgrade occurred."
                    }
                    else -> "WORKSPACE_WRITE unavailable: sandbox enforcement probe failed"
                },
            )
        }
        verifiedSandboxBackend = realBackend to identityBeforeProbe
        return when (platform) {
            SandboxPlatform.MACOS -> SandboxCapability(
                mode = SandboxMode.WORKSPACE_WRITE,
                enforcement = SandboxEnforcement.MACOS_SANDBOX_EXEC,
                available = true,
                enforced = true,
                summary = "macOS sandbox-exec enforced: workspace read/write, platform runtime read-only, user data and network denied",
            )
            SandboxPlatform.LINUX -> SandboxCapability(
                mode = SandboxMode.WORKSPACE_WRITE,
                enforcement = SandboxEnforcement.LINUX_BUBBLEWRAP,
                available = true,
                enforced = true,
                summary = "Linux bubblewrap enforced: workspace read/write, host runtime read-only, private HOME/tmp, user data hidden, network namespace isolated",
            )
            SandboxPlatform.WINDOWS -> error("Windows capability is returned above")
            else -> unavailable("WORKSPACE_WRITE unavailable: unsupported platform")
        }
    }

    private fun unavailable(message: String): SandboxCapability = SandboxCapability(
        mode = SandboxMode.WORKSPACE_WRITE,
        enforcement = SandboxEnforcement.UNAVAILABLE,
        available = false,
        enforced = false,
        summary = message,
    )

    private fun executableName(value: String): String = value
        .replace('\\', '/')
        .substringAfterLast('/')
        .lowercase(Locale.ROOT)
        .removeSuffix(".exe")
        .removeSuffix(".cmd")
        .removeSuffix(".bat")

    private fun containsForbiddenControlCharacter(value: String): Boolean =
        value.any { it == '\u0000' || it == '\n' || it == '\r' }

    companion object {
        private val MACOS_SANDBOX_EXEC: Path = Path.of("/usr/bin/sandbox-exec")
        private val LINUX_BUBBLEWRAP_CANDIDATES = listOf(
            Path.of("/usr/bin/bwrap"),
            Path.of("/bin/bwrap"),
            Path.of("/usr/local/bin/bwrap"),
            Path.of("/run/current-system/sw/bin/bwrap"),
        )
        private const val SANDBOX_HOME_DIRECTORY = ".omnicode-sandbox-home"
        private const val LINUX_SANDBOX_HOME = "/tmp/omnicode-home"
        private const val LINUX_SANDBOX_TMP = "/tmp"
        private const val MACOS_READ_ONLY_HOME = "/var/empty"
        private const val MAX_RUNTIME_READ_PATHS = 64

        /**
         * Human-readable installation and migration guidance. This method performs no process
         * probe, so settings UIs can call it on the EDT without blocking.
         */
        fun setupGuidance(osName: String = System.getProperty("os.name").orEmpty()): String =
            when (sandboxPlatform(osName)) {
                SandboxPlatform.MACOS ->
                    "macOS：使用系统 sandbox-exec；插件会先验证工作区读写边界与网络阻断，验证失败即拒绝执行。"
                SandboxPlatform.LINUX ->
                    "Linux：需要 bubblewrap（bwrap）和可用的用户/网络命名空间。Ubuntu/Debian：sudo apt install bubblewrap；Fedora：sudo dnf install bubblewrap。能力探测失败时不会降级。"
                SandboxPlatform.WINDOWS ->
                    "Windows：优先使用随 OmniCode 发布、经过哈希/签名校验的原生 AppContainer host；它会在受控 ACL 事务中授予项目访问并在进程退出后恢复。helper 缺失、签名/哈希不匹配或清理失败都会拒绝执行；也可在 WSL2 安装 bubblewrap 后使用 JetBrains WSL/Remote Development。"
                SandboxPlatform.OTHER ->
                    "当前平台没有受支持的系统级 workspace-write 后端；命令会被拒绝且不会静默切换到完全访问。"
            }

        /**
         * Display-only argv examples for Windows users. OmniCode never executes these commands
         * automatically; reopen the project in the WSL/Remote Development backend first.
         */
        fun windowsRemoteDevelopmentSteps(): List<List<String>> = listOf(
            listOf("wsl.exe", "--install"),
            listOf("wsl.exe", "--install", "-d", "Ubuntu"),
            listOf("sudo", "apt-get", "update"),
            listOf("sudo", "apt-get", "install", "bubblewrap"),
        )

        /**
         * system.sb supplies the minimal platform runtime rules needed to start normal macOS
         * command-line programs. Explicit denies then remove access to user-controlled roots;
         * the more-specific workspace grant restores read/write access only to the real project.
         * Network is denied by default and again explicitly for auditability.
         */
        internal val MACOS_WORKSPACE_PROFILE: String = """
            (version 1)
            (deny default)
            (import "system.sb")
            (allow process-exec)
            (allow process-fork)
            (allow signal (target same-sandbox))
            (deny network*)
            (deny file-write*)
            (deny file-write* (subpath "/cores"))
            (deny file-read*
                (subpath "/Users")
                (subpath "/Volumes")
                (subpath "/Network")
                (subpath "/home")
                (subpath "/private/tmp")
                (subpath "/private/var/folders")
                (subpath "/private/var/root")
                (subpath "/var/root"))
            (allow file-read* file-write* (subpath (param "OMNICODE_WORKSPACE")))
        """.trimIndent()

        /** Plan exploration can read the real workspace but cannot write anywhere on the host. */
        internal val MACOS_READ_ONLY_PROFILE: String = """
            (version 1)
            (deny default)
            (import "system.sb")
            (allow process-exec)
            (allow process-fork)
            (allow signal (target same-sandbox))
            (deny network*)
            (deny file-write*)
            (deny file-read*
                (subpath "/Users")
                (subpath "/Volumes")
                (subpath "/Network")
                (subpath "/home")
                (subpath "/private/tmp")
                (subpath "/private/var/folders")
                (subpath "/private/var/root")
                (subpath "/var/root"))
            (allow file-read* (subpath (param "OMNICODE_WORKSPACE")))
        """.trimIndent()

        private val ALWAYS_BLOCKED_EXECUTABLES = setOf(
            "sh", "bash", "zsh", "fish", "csh", "dash", "ksh", "tcsh",
            "cmd", "powershell", "pwsh", "sudo", "su", "doas", "env", "xargs",
            "osascript", "script", "expect",
        )

        private val WORKSPACE_SYSTEM_EXECUTABLES = setOf(
            "awk", "bun", "cargo", "cat", "clang", "clang++", "cmake", "deno", "diff", "gh",
            "dotnet", "eslint", "find", "git", "go", "gradle", "grep", "head", "java",
            "javac", "kotlin", "kotlinc", "ls", "make", "mvn", "ninja", "node", "npm",
            "npx", "patch", "php", "pip", "pip3", "pnpm", "printf", "pytest", "python",
            "python3", "rg", "ruby", "ruff", "rustc", "sed", "sort", "swift", "swiftc",
            "tail", "tar", "touch", "tsc", "uniq", "uv", "wc", "xcodebuild", "yarn",
        )

        private val TRUSTED_RESOLVED_ENTRYPOINTS = mapOf(
            "npm" to setOf("npm-cli.js"),
            "npx" to setOf("npx-cli.js"),
            "pnpm" to setOf("pnpm.cjs"),
            "yarn" to setOf("yarn.js", "yarn.cjs"),
        )

        private fun defaultSandboxConfiguration(): DefaultSandboxConfiguration {
            val osName = System.getProperty("os.name").orEmpty()
            val platform = sandboxPlatform(osName)
            val executable = when (platform) {
                SandboxPlatform.MACOS -> MACOS_SANDBOX_EXEC
                // Do not trust a project-influenced PATH entry as the sandbox itself. Only
                // conventional system installation locations are eligible for the real probe.
                SandboxPlatform.LINUX -> LINUX_BUBBLEWRAP_CANDIDATES.firstOrNull {
                    Files.isRegularFile(it) && Files.isExecutable(it)
                } ?: LINUX_BUBBLEWRAP_CANDIDATES.first()
                SandboxPlatform.WINDOWS -> windowsAppContainerExecutable()
                SandboxPlatform.OTHER -> Path.of(".omnicode-unsupported-sandbox")
            }
            val probe: (Path) -> Boolean = when (platform) {
                SandboxPlatform.MACOS -> ::probeMacSandbox
                SandboxPlatform.LINUX -> ::probeLinuxBubblewrap
                SandboxPlatform.WINDOWS -> ::probeWindowsAppContainer
                SandboxPlatform.OTHER -> { _ -> false }
            }
            return DefaultSandboxConfiguration(osName, executable, probe)
        }

        private fun sandboxPlatform(osName: String): SandboxPlatform {
            val normalized = osName.lowercase(Locale.ROOT)
            return when {
                normalized.contains("mac") || normalized.contains("darwin") -> SandboxPlatform.MACOS
                normalized.contains("linux") -> SandboxPlatform.LINUX
                normalized.contains("windows") -> SandboxPlatform.WINDOWS
                else -> SandboxPlatform.OTHER
            }
        }

        private fun missingBackendMessage(platform: SandboxPlatform, executable: Path): String = when (platform) {
            SandboxPlatform.MACOS ->
                "WORKSPACE_WRITE unavailable: /usr/bin/sandbox-exec is missing or not executable"
            SandboxPlatform.LINUX ->
                "WORKSPACE_WRITE unavailable: bubblewrap (bwrap) is missing or not executable at $executable. Install the bubblewrap package and ensure user namespaces are enabled; no automatic downgrade occurred."
            SandboxPlatform.WINDOWS ->
                "Windows workspace-write unavailable: the signed OmniCode AppContainer host was not found. Install the Windows helper package or open the project through JetBrains WSL/Remote Development; no automatic downgrade occurred."
            SandboxPlatform.OTHER ->
                "WORKSPACE_WRITE unavailable: this platform has no configured OS process sandbox; no automatic downgrade occurred"
        }

        private fun windowsAppContainerExecutable(): Path {
            val configured = System.getenv("OMNICODE_APPCONTAINER_HOST")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf(Path::isAbsolute)
            if (configured != null) return configured.toAbsolutePath().normalize()

            val pluginCandidates = buildList {
                System.getProperty("idea.plugins.path")?.takeIf(String::isNotBlank)?.let { root ->
                    add(Path.of(root, "omnicode-agent", "bin", "windows-x64", APPCONTAINER_HELPER_NAME))
                    add(Path.of(root, "dev.omnicode.agent", "bin", "windows-x64", APPCONTAINER_HELPER_NAME))
                }
                System.getProperty("idea.home.path")?.takeIf(String::isNotBlank)?.let { home ->
                    add(Path.of(home, "plugins", "omnicode-agent", "bin", "windows-x64", APPCONTAINER_HELPER_NAME))
                    add(Path.of(home, "plugins", "dev.omnicode.agent", "bin", "windows-x64", APPCONTAINER_HELPER_NAME))
                }
            }
            pluginCandidates.firstOrNull { Files.isRegularFile(it) }?.let { return it.toAbsolutePath().normalize() }

            val systemRoot = System.getenv("SystemRoot").orEmpty()
            if (systemRoot.isNotBlank()) {
                val installed = Path.of(systemRoot, "OmniCode", APPCONTAINER_HELPER_NAME)
                if (Files.isRegularFile(installed)) return installed.toAbsolutePath().normalize()
            }
            return Path.of("C:\\Program Files\\OmniCode", APPCONTAINER_HELPER_NAME)
        }

        private fun isWindowsAppContainerHelper(path: Path): Boolean =
            path.fileName?.toString()?.equals(APPCONTAINER_HELPER_NAME, ignoreCase = true) == true

        private fun probeWindowsAppContainer(executable: Path): Boolean {
            if (!isWindowsAppContainerHelper(executable) || !verifyWindowsHelperHash(executable)) return false
            val process = runCatching {
                ProcessBuilder(executable.toString(), "--probe")
                    .apply { environment().clear() }
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return false
            val completed = runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!completed) {
                process.destroyForcibly()
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                return false
            }
            val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            return process.exitValue() == 0 && output.contains("OMNICODE_APPCONTAINER_PROBE_OK")
        }

        private fun verifyWindowsHelperHash(executable: Path): Boolean {
            val expected = System.getenv("OMNICODE_APPCONTAINER_HOST_SHA256")
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: runCatching {
                    val sidecar = Path.of("${executable}.sha256")
                    if (!Files.isRegularFile(sidecar)) return false
                    Files.readString(sidecar)
                        .trim()
                        .substringBefore(' ')
                        .lowercase(Locale.ROOT)
                        .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                }.getOrNull()
                ?: return false
            val digest = runCatching {
                require(Files.size(executable) in 1..(64L * 1024L * 1024L))
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable))
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull() ?: return false
            return digest == expected
        }

        private const val APPCONTAINER_HELPER_NAME = "omnicode-appcontainer-host.exe"

        private fun buildLinuxLaunchArgv(
            bubblewrap: Path,
            workspaceRoot: Path,
            cwd: Path,
            commandArgv: List<String>,
            userHome: Path?,
            readOnlyWorkspace: Boolean = false,
            runtimeReadPaths: List<Path> = emptyList(),
        ): List<String> {
            val hiddenRoots = linuxHiddenRoots(userHome)
            return buildList {
                add(bubblewrap.toString())
                add("--die-with-parent")
                add("--new-session")
                add("--unshare-all")
                // Keep this explicit even though --unshare-all includes it: network denial is a
                // security invariant and must remain visible in plan/audit inspection.
                add("--unshare-net")
                add("--ro-bind")
                add("/")
                add("/")
                add("--proc")
                add("/proc")
                add("--dev")
                add("/dev")
                hiddenRoots.forEach { root ->
                    add("--tmpfs")
                    add(root.toString())
                }
                // /tmp is a private tmpfs, so HOME and temporary files never materialize on the
                // host. Re-create any hidden parents before mounting the real workspace over it.
                add("--dir")
                add(LINUX_SANDBOX_HOME)
                linuxWorkspaceDestinations(workspaceRoot, hiddenRoots).forEach { destination ->
                    add("--dir")
                    add(destination.toString())
                }
                linuxRuntimeDestinations(runtimeReadPaths, hiddenRoots).forEach { destination ->
                    add("--dir")
                    add(destination.toString())
                }
                runtimeReadPaths.forEach { path ->
                    add("--ro-bind")
                    add(path.toString())
                    add(path.toString())
                }
                add(if (readOnlyWorkspace) "--ro-bind" else "--bind")
                add(workspaceRoot.toString())
                add(workspaceRoot.toString())
                add("--chdir")
                add(cwd.toString())
                add("--setenv")
                add("HOME")
                add(LINUX_SANDBOX_HOME)
                add("--setenv")
                add("TMPDIR")
                add(LINUX_SANDBOX_TMP)
                add("--setenv")
                add("TEMP")
                add(LINUX_SANDBOX_TMP)
                add("--setenv")
                add("TMP")
                add(LINUX_SANDBOX_TMP)
                add("--")
                addAll(commandArgv)
            }
        }

        private fun linuxHiddenRoots(userHome: Path?): List<Path> {
            val candidates = buildList {
                add(Path.of("/tmp"))
                add(Path.of("/var/tmp"))
                add(Path.of("/home"))
                add(Path.of("/root"))
                add(Path.of("/Users"))
                // Hide host service sockets (Docker, D-Bus, keyrings) in addition to files.
                add(Path.of("/run"))
                add(Path.of("/media"))
                add(Path.of("/mnt"))
                add(Path.of("/Volumes"))
                userHome?.takeIf(Path::isAbsolute)?.normalize()?.let(::add)
            }
                .distinct()
                .filter { Files.isDirectory(it) }
                .sortedBy { it.nameCount }
            return candidates.filter { candidate ->
                candidates.none { other -> other != candidate && candidate.startsWith(other) }
            }
        }

        private fun linuxWorkspaceDestinations(workspaceRoot: Path, hiddenRoots: List<Path>): List<Path> {
            val hiddenRoot = hiddenRoots
                .filter(workspaceRoot::startsWith)
                .maxByOrNull { it.nameCount }
                ?: return emptyList()
            val relative = hiddenRoot.relativize(workspaceRoot)
            val destinations = mutableListOf<Path>()
            var cursor = hiddenRoot
            relative.forEach { segment ->
                cursor = cursor.resolve(segment)
                destinations.add(cursor)
            }
            return destinations
        }

        /** Recreates hidden user-runtime parents before mounting their read-only directories. */
        private fun linuxRuntimeDestinations(runtimeReadPaths: List<Path>, hiddenRoots: List<Path>): List<Path> =
            runtimeReadPaths
                .flatMap { path ->
                    val hiddenRoot = hiddenRoots
                        .filter(path::startsWith)
                        .maxByOrNull(Path::getNameCount)
                        ?: return@flatMap emptyList()
                    val destinations = mutableListOf<Path>()
                    var cursor = hiddenRoot
                    hiddenRoot.relativize(path).forEach { segment ->
                        cursor = cursor.resolve(segment)
                        destinations.add(cursor)
                    }
                    destinations
                }
                .distinct()

        private fun normalizeRuntimeReadPaths(paths: List<Path>, workspaceRoot: Path): List<Path> =
            paths.asSequence()
                .map { it.toAbsolutePath().normalize() }
                // Never mount the workspace (it is already mounted read/write or read-only), and
                // never allow a caller to broaden the sandbox to a filesystem root.
                .filter { it.nameCount > 1 && !it.startsWith(workspaceRoot) }
                .mapNotNull { candidate ->
                    runCatching { candidate.toRealPath() }
                        .getOrNull()
                        ?.takeIf { Files.isDirectory(it) && it.nameCount > 1 && !it.startsWith(workspaceRoot) }
                }
                .distinct()
                .take(MAX_RUNTIME_READ_PATHS)
                .toList()

        private fun macProfile(readOnlyWorkspace: Boolean, runtimeReadPaths: List<Path>): String {
            val base = if (readOnlyWorkspace) MACOS_READ_ONLY_PROFILE else MACOS_WORKSPACE_PROFILE
            if (runtimeReadPaths.isEmpty()) return base
            val rules = runtimeReadPaths.indices.joinToString("\n") { index ->
                "            (allow file-read* (subpath (param \"OMNICODE_RUNTIME_$index\")))"
            }
            val workspaceRule = if (readOnlyWorkspace) {
                "(allow file-read* (subpath (param \"OMNICODE_WORKSPACE\")))"
            } else {
                "(allow file-read* file-write* (subpath (param \"OMNICODE_WORKSPACE\")))"
            }
            return base.replace(workspaceRule, "$rules\n$workspaceRule")
        }

        private fun probeMacSandbox(executable: Path): Boolean {
            val catExecutable = Path.of("/bin/cat")
            val touchExecutable = Path.of("/usr/bin/touch")
            if (!Files.isExecutable(catExecutable) || !Files.isExecutable(touchExecutable)) return false

            var probeRoot: Path? = null
            return try {
                val root = Files.createTempDirectory("omnicode-sandbox-probe").toRealPath()
                probeRoot = root
                val workspace = Files.createDirectory(root.resolve("workspace")).toRealPath()
                val insideReadable = workspace.resolve("inside-readable.txt")
                val outsideReadable = root.resolve("outside-readable.txt")
                val insideWritable = workspace.resolve("inside-writable.txt")
                val insideReadOnlyWritable = workspace.resolve("inside-read-only-writable.txt")
                val outsideWritable = root.resolve("outside-writable.txt")
                Files.writeString(insideReadable, "inside")
                Files.writeString(outsideReadable, "outside")

                runProfileProbe(
                    executable = executable,
                    workspace = workspace,
                    command = listOf(catExecutable.toString(), insideReadable.toString()),
                    expectSuccess = true,
                ) && runProfileProbe(
                    executable = executable,
                    workspace = workspace,
                    command = listOf(catExecutable.toString(), outsideReadable.toString()),
                    expectSuccess = false,
                ) && runProfileProbe(
                    executable = executable,
                    workspace = workspace,
                    command = listOf(touchExecutable.toString(), insideWritable.toString()),
                    expectSuccess = true,
                ) && Files.isRegularFile(insideWritable) && runProfileProbe(
                    executable = executable,
                    workspace = workspace,
                    command = listOf(touchExecutable.toString(), outsideWritable.toString()),
                    expectSuccess = false,
                ) && !Files.exists(outsideWritable) && runProfileProbe(
                    executable = executable,
                    workspace = workspace,
                    command = listOf(catExecutable.toString(), insideReadable.toString()),
                    expectSuccess = true,
                    profile = MACOS_READ_ONLY_PROFILE,
                ) && runProfileProbe(
                    executable = executable,
                    workspace = workspace,
                    command = listOf(touchExecutable.toString(), insideReadOnlyWritable.toString()),
                    expectSuccess = false,
                    profile = MACOS_READ_ONLY_PROFILE,
                ) && !Files.exists(insideReadOnlyWritable)
            } catch (_: Exception) {
                false
            } finally {
                probeRoot?.let(::deleteProbeTree)
            }
        }

        private fun probeLinuxBubblewrap(executable: Path): Boolean {
            val catExecutable = firstExecutable("/bin/cat", "/usr/bin/cat") ?: return false
            val touchExecutable = firstExecutable("/usr/bin/touch", "/bin/touch") ?: return false

            var probeRoot: Path? = null
            return try {
                val root = Files.createTempDirectory("omnicode-bwrap-probe").toRealPath()
                probeRoot = root
                val workspace = Files.createDirectory(root.resolve("workspace")).toRealPath()
                val insideReadable = workspace.resolve("inside-readable.txt")
                val outsideReadable = root.resolve("outside-readable.txt")
                val insideWritable = workspace.resolve("inside-writable.txt")
                val insideReadOnlyWritable = workspace.resolve("inside-read-only-writable.txt")
                val outsideWritable = root.resolve("outside-writable.txt")
                Files.writeString(insideReadable, "inside")
                Files.writeString(outsideReadable, "outside-secret")

                val insideRead = runLinuxProbe(
                    buildLinuxLaunchArgv(
                        executable,
                        workspace,
                        workspace,
                        listOf(catExecutable.toString(), insideReadable.toString()),
                        System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
                    ),
                )
                val outsideRead = runLinuxProbe(
                    buildLinuxLaunchArgv(
                        executable,
                        workspace,
                        workspace,
                        listOf(catExecutable.toString(), outsideReadable.toString()),
                        System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
                    ),
                )
                val insideWrite = runLinuxProbe(
                    buildLinuxLaunchArgv(
                        executable,
                        workspace,
                        workspace,
                        listOf(touchExecutable.toString(), insideWritable.toString()),
                        System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
                    ),
                )
                val insideReadOnlyRead = runLinuxProbe(
                    buildLinuxLaunchArgv(
                        executable,
                        workspace,
                        workspace,
                        listOf(catExecutable.toString(), insideReadable.toString()),
                        System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
                        readOnlyWorkspace = true,
                    ),
                )
                val insideReadOnlyWrite = runLinuxProbe(
                    buildLinuxLaunchArgv(
                        executable,
                        workspace,
                        workspace,
                        listOf(touchExecutable.toString(), insideReadOnlyWritable.toString()),
                        System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
                        readOnlyWorkspace = true,
                    ),
                )
                // Writes outside the workspace may succeed in private tmpfs, but must never
                // materialize on the host.
                runLinuxProbe(
                    buildLinuxLaunchArgv(
                        executable,
                        workspace,
                        workspace,
                        listOf(touchExecutable.toString(), outsideWritable.toString()),
                        System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
                    ),
                )
                val networkView = runLinuxProbe(
                    buildLinuxLaunchArgv(
                        executable,
                        workspace,
                        workspace,
                        listOf(catExecutable.toString(), "/proc/net/dev"),
                        System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
                    ),
                )

                insideRead.exitCode == 0 && insideRead.stdout == "inside" &&
                    outsideRead.exitCode != 0 && !outsideRead.stdout.contains("outside-secret") &&
                    insideWrite.exitCode == 0 && Files.isRegularFile(insideWritable) &&
                    insideReadOnlyRead.exitCode == 0 && insideReadOnlyRead.stdout == "inside" &&
                    insideReadOnlyWrite.exitCode != 0 && !Files.exists(insideReadOnlyWritable) &&
                    !Files.exists(outsideWritable) &&
                    networkView.exitCode == 0 && onlyLoopbackNetworkInterface(networkView.stdout)
            } catch (_: Exception) {
                false
            } finally {
                probeRoot?.let(::deleteProbeTree)
            }
        }

        private fun probeWindowsWslBubblewrap(executable: Path): Boolean {
            val process = runCatching {
                ProcessBuilder(executable.toString(), "--exec", "bwrap", "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull() ?: return false
            val completed = runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!completed) {
                process.destroyForcibly()
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                return false
            }
            return process.exitValue() == 0
        }

        private fun firstExecutable(vararg candidates: String): Path? = candidates
            .asSequence()
            .map(Path::of)
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        private fun runLinuxProbe(argv: List<String>): LinuxProbeResult {
            val process = runCatching {
                ProcessBuilder(argv).apply { environment().clear() }.start()
            }.getOrNull() ?: return LinuxProbeResult(-1, "")
            val completed = runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!completed) {
                process.destroyForcibly()
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                return LinuxProbeResult(-1, "")
            }
            val stdout = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            return LinuxProbeResult(process.exitValue(), stdout)
        }

        private fun onlyLoopbackNetworkInterface(procNetDev: String): Boolean {
            val interfaces = procNetDev.lineSequence()
                .mapNotNull { line ->
                    if (':' !in line) null else line.substringBefore(':').trim().takeIf(String::isNotBlank)
                }
                .toList()
            return interfaces.isNotEmpty() && interfaces.all { it == "lo" }
        }

        private fun runProfileProbe(
            executable: Path,
            workspace: Path,
            command: List<String>,
            expectSuccess: Boolean,
            profile: String = MACOS_WORKSPACE_PROFILE,
        ): Boolean {
            val process = runCatching {
                ProcessBuilder(
                    buildList {
                        add(executable.toString())
                        add("-p")
                        add(profile)
                        add("-DOMNICODE_WORKSPACE=$workspace")
                        add("--")
                        addAll(command)
                    },
                ).apply {
                    environment().clear()
                }
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull() ?: return false
            val completed = runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!completed) {
                process.destroyForcibly()
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                return false
            }
            return (process.exitValue() == 0) == expectSuccess
        }

        private fun deleteProbeTree(root: Path) {
            runCatching {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
        }

        private data class LinuxProbeResult(val exitCode: Int, val stdout: String)
    }
}
