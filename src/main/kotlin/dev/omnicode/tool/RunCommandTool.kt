package dev.omnicode.tool

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType
import com.intellij.execution.process.OSProcessUtil
import dev.omnicode.settings.SandboxMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

class RunCommandTool(
    private val sandboxMode: SandboxMode = SandboxMode.DEFAULT,
    private val processSandbox: ProcessSandbox = ProcessSandbox(),
    private val processStarter: (GeneralCommandLine) -> Process = { commandLine -> commandLine.createProcess() },
) : AgentTool {
    override val name = "run_command"
    override val description = "Run one non-interactive executable with an argument array. Agent and Research commands require approval. Claude Plan accepts only structurally validated read-only exploration commands and runs them without approval in an OS-enforced read-only workspace sandbox."
    override val dangerous = true
    override val effect = ToolEffect.COMMAND
    override val inputSchema: JsonObject = objectSchema(required = listOf("argv")) {
        stringArrayProperty(
            "argv",
            "Executable followed by its individual arguments, for example [\"npm\", \"test\"]. " +
                "In Claude Plan use a bare supported read-only name such as rg, git, ls, find, or cat.",
        )
        stringProperty("cwd", "Project-relative working directory. Defaults to '.'.")
        integerProperty("timeout_seconds", "Timeout before the process is terminated.", 120, 1, 300)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(
            context.mode == dev.omnicode.agent.AgentMode.AGENT ||
                context.mode == dev.omnicode.agent.AgentMode.RESEARCH ||
                context.mode == dev.omnicode.agent.AgentMode.CLAUDE_PLAN,
        ) { "PLAN_MODE_BLOCKED: Command execution is disabled in Plan mode." }
        val argvValues = arguments.get("argv")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: throw IllegalArgumentException("argv must be an array")
        val argv = argvValues.mapIndexed { index, element ->
            element.takeIf { it.isJsonPrimitive }
                ?.runCatching { asString }
                ?.getOrNull()
                ?: throw IllegalArgumentException("argv[$index] must be a string")
        }
        require(argv.isNotEmpty()) { "argv must contain an executable" }
        val claudePlan = context.mode == dev.omnicode.agent.AgentMode.CLAUDE_PLAN
        if (claudePlan) ClaudePlanReadOnlyCommandPolicy.requireAllowed(argv)
        val cwdRelative = arguments.string("cwd", ".")
        val cwd = ProjectPathGuard.resolve(context.project, cwdRelative)
        require(Files.isDirectory(cwd)) { "Working directory does not exist: $cwdRelative" }
        val executable = resolveExecutable(argv.first(), cwd)
            ?: error("Executable was not found: ${argv.first()}")
        require(argv.none { it.contains('\u0000') || it.contains('\n') || it.contains('\r') }) { "Arguments must not contain control lines" }
        val timeout = arguments.int("timeout_seconds", 120).coerceIn(1, 300)
        val sandboxRequest = ProcessSandboxRequest(
            mode = if (claudePlan) SandboxMode.WORKSPACE_WRITE else sandboxMode,
            workspaceRoot = ProjectPathGuard.root(context.project),
            cwd = cwd,
            requestedExecutable = argv.first(),
            executable = executable,
            arguments = argv.drop(1),
            readOnlyWorkspace = claudePlan,
        )
        val proposedPlan = processSandbox.prepare(sandboxRequest)

        val approved = claudePlan || context.approvalGate.approve(
            ApprovalRequest(
                toolName = name,
                title = "Run ${executable.fileName}",
                details = "Executable: $executable\nWorking directory: $cwdRelative\nArguments:\n${proposedPlan.commandArgv.joinToString("\n") { "  $it" }}\nTimeout: ${timeout}s\nSandbox: ${proposedPlan.capability.summary}",
                risk = if (proposedPlan.capability.enforced) {
                    "The detected OS sandbox restricts host writes to the workspace, hides user data, and denies network access. Sensitive environment variables are removed."
                } else {
                    "DANGER_FULL_ACCESS is not OS-sandboxed. The process runs with your user permissions; only explicit approval, timeout, output bounds, and environment cleanup remain."
                },
            ),
        )
        if (!approved) return@withContext ToolExecutionResult("REJECTED_BY_USER: Command was not run.", true)

        // Re-resolve the cwd and executable after approval so the approved plan cannot be
        // silently redirected by a symlink swap while the dialog is open.
        val revalidatedCwd = ProjectPathGuard.validate(context.project, cwd).toRealPath()
        val revalidatedExecutable = executable.toRealPath()
        val executionPlan = processSandbox.prepare(
            sandboxRequest.copy(cwd = revalidatedCwd, executable = revalidatedExecutable),
        )
        require(
            executionPlan.commandArgv == proposedPlan.commandArgv &&
                executionPlan.cwd == proposedPlan.cwd &&
                executionPlan.executableIdentity == proposedPlan.executableIdentity &&
                executionPlan.sandboxExecutableIdentity == proposedPlan.sandboxExecutableIdentity
        ) {
            "COMMAND_CONFLICT: Command or sandbox target changed while awaiting approval"
        }
        processSandbox.activate(executionPlan)

        val commandLine = GeneralCommandLine(executionPlan.launchArgv)
            .withWorkDirectory(executionPlan.cwd.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(ParentEnvironmentType.NONE)
        SAFE_ENVIRONMENT_KEYS.forEach { key -> System.getenv(key)?.let { commandLine.withEnvironment(key, it) } }
        executionPlan.environmentOverrides.forEach { (key, value) -> commandLine.withEnvironment(key, value) }
        if (claudePlan) {
            CLAUDE_PLAN_ENVIRONMENT.forEach { (key, value) -> commandLine.withEnvironment(key, value) }
        }
        commandLine.withEnvironment("OMNICODE_SANDBOX_MODE", executionPlan.mode.name)

        val output = runBounded(commandLine, timeout)
        val summary = buildString {
            appendLine("Sandbox: ${executionPlan.capability.summary}")
            appendLine("Exit code: ${output.exitCode}")
            if (output.isTimeout) appendLine("Timed out after ${timeout}s; process tree terminated.")
            if (output.stdout.text.isNotBlank()) appendLine("STDOUT:\n${output.stdout.text}")
            if (output.stderr.text.isNotBlank()) appendLine("STDERR:\n${output.stderr.text}")
            if (output.stdout.truncated || output.stderr.truncated) appendLine("[output truncated]")
        }.trim()
        ToolExecutionResult(summary, output.exitCode != 0 || output.isTimeout)
    }

    private suspend fun runBounded(commandLine: GeneralCommandLine, timeoutSeconds: Int): CommandOutput = coroutineScope {
        val process = processStarter(commandLine)
        // Commands are non-interactive; signal EOF immediately instead of leaving stdin open.
        runCatching { process.outputStream.close() }
        val stdout = async(Dispatchers.IO) { readBounded(process.inputStream) }
        val stderr = async(Dispatchers.IO) { readBounded(process.errorStream) }
        var timedOut = false
        try {
            val exited = withTimeoutOrNull(timeoutSeconds * 1_000L) {
                while (process.isAlive) delay(50)
                true
            } == true
            if (!exited) {
                timedOut = true
                terminateProcess(process)
            }
        } catch (cancelled: CancellationException) {
            terminateProcess(process)
            withContext(NonCancellable) {
                waitForExit(process)
                drainOutput(process, stdout, stderr)
            }
            throw cancelled
        }
        if (process.isAlive) terminateProcess(process)
        val exitCode = waitForExit(process)
        val (stdoutText, stderrText) = drainOutput(process, stdout, stderr)
        CommandOutput(exitCode, timedOut, stdoutText, stderrText)
    }

    private fun terminateProcess(process: Process) {
        closeProcessStreams(process)
        runCatching { OSProcessUtil.killProcessTree(process) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    private suspend fun waitForExit(process: Process): Int = withContext(Dispatchers.IO) {
        if (process.isAlive && !runCatching {
                process.waitFor(PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        ) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS) }
        }
        if (process.isAlive) -1 else runCatching(process::exitValue).getOrDefault(-1)
    }

    private suspend fun drainOutput(
        process: Process,
        stdout: Deferred<BoundedText>,
        stderr: Deferred<BoundedText>,
    ): Pair<BoundedText, BoundedText> {
        runCatching {
            withTimeoutOrNull(OUTPUT_DRAIN_TIMEOUT_MS) { stdout.await() to stderr.await() }
        }.getOrNull()?.let { return it }

        // A descendant may outlive its parent while retaining inherited pipe handles.
        closeProcessStreams(process)
        runCatching {
            withTimeoutOrNull(STREAM_CLOSE_GRACE_MS) { stdout.await() to stderr.await() }
        }.getOrNull()?.let { (out, err) ->
            return out.copy(truncated = true) to err.copy(truncated = true)
        }

        stdout.cancel()
        stderr.cancel()
        return BoundedText("", true) to BoundedText("", true)
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun readBounded(input: InputStream): BoundedText {
        val output = StringBuilder()
        var truncated = false
        try {
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(4_096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    val remaining = MAX_OUTPUT_CHARS - output.length
                    if (remaining > 0) output.append(buffer, 0, minOf(read, remaining))
                    if (read > remaining) truncated = true
                }
            }
        } catch (_: IOException) {
            // Forced closure is expected during timeout and cancellation cleanup.
            truncated = true
        }
        return BoundedText(output.toString(), truncated)
    }

    private fun resolveExecutable(value: String, cwd: Path): Path? {
        val requested = Path.of(value)
        if (requested.isAbsolute || value.contains('/') || value.contains('\\')) {
            val candidate = if (requested.isAbsolute) requested else cwd.resolve(requested)
            return candidate.normalize().takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }?.toRealPath()
        }
        val pathValue = System.getenv("PATH").orEmpty()
        return pathValue.split(java.io.File.pathSeparatorChar)
            .asSequence()
            .filter(String::isNotBlank)
            .flatMap { directory ->
                windowsExecutableCandidates(value).asSequence().map { candidate ->
                    Path.of(directory).resolve(candidate)
                }
            }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.toRealPath()
    }

    /** Java's NIO PATH lookup does not apply PATHEXT; direct Windows tools commonly need .exe. */
    private fun windowsExecutableCandidates(value: String): List<String> {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return listOf(value)
        if (value.contains('.')) return listOf(value)
        return listOf(value, "$value.exe")
    }
}

/**
 * Claude Plan commands are selected from a small argv-level capability surface. This is not a
 * shell-text blacklist: the executable and command grammar must be known before the OS sandbox is
 * asked to launch anything. The read-only sandbox remains the second, independent enforcement
 * boundary in case a supported executable changes behavior in a future release.
 */
internal object ClaudePlanReadOnlyCommandPolicy {
    fun requireAllowed(argv: List<String>) {
        require(argv.isNotEmpty()) { "CLAUDE_PLAN_COMMAND_BLOCKED: argv must contain an executable" }
        val requested = argv.first()
        require(requested.isNotBlank() && '/' !in requested && '\\' !in requested) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: use a supported executable name, not an executable path"
        }
        require(argv.drop(1).none(::isShellCompositionToken)) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: pipelines, redirections, and compound commands are not accepted"
        }

        val executable = requested.lowercase(Locale.ROOT).removeSuffix(".exe")
        val args = argv.drop(1)
        when (executable) {
            "cat", "grep", "head", "ls", "tail", "wc" -> Unit
            "diff" -> requireReadOnlyDiff(args)
            "find" -> requireReadOnlyFind(args)
            "git" -> requireReadOnlyGit(args)
            "rg" -> requireReadOnlyRipgrep(args)
            else -> throw IllegalArgumentException(
                "CLAUDE_PLAN_COMMAND_BLOCKED: '$requested' is not a supported read-only exploration executable",
            )
        }
    }

    private fun requireReadOnlyRipgrep(args: List<String>) {
        val unsafeOption = args.optionsBeforeTerminator().firstOrNull { argument ->
            argument == "--pre" || argument.startsWith("--pre=") ||
                argument == "--pre-glob" || argument.startsWith("--pre-glob=") ||
                argument == "--hostname-bin" || argument.startsWith("--hostname-bin=") ||
                argument == "--search-zip" || argument == "-z" ||
                (argument.startsWith("-z") && !argument.startsWith("--"))
        }
        require(unsafeOption == null) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: rg option '$unsafeOption' may execute another program"
        }
    }

    private fun requireReadOnlyDiff(args: List<String>) {
        val unsafeOption = args.optionsBeforeTerminator().firstOrNull { argument ->
            argument == "-l" ||
                argument.matchesLongOption("--paginate") ||
                argument.matchesLongOption("--output")
        }
        require(unsafeOption == null) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: diff option '$unsafeOption' may write output or execute a helper"
        }
    }

    private fun requireReadOnlyGit(args: List<String>) {
        require(args.isNotEmpty()) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: git requires an explicitly supported read-only subcommand"
        }
        val subcommand = args.first().lowercase(Locale.ROOT)
        require(subcommand in READ_ONLY_GIT_SUBCOMMANDS) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: git subcommand '$subcommand' is not read-only"
        }
        val subcommandArgs = args.drop(1)
        val unsafeOption = subcommandArgs.optionsBeforeTerminator().firstOrNull { argument ->
            argument.matchesLongOption("--output") ||
                argument.matchesLongOption("--ext-diff") ||
                argument.matchesLongOption("--textconv") ||
                argument.matchesLongOption("--filters") ||
                argument.matchesLongOption("--open-files-in-pager") ||
                (subcommand == "grep" && (argument == "-O" || argument.startsWith("-O")))
        }
        require(unsafeOption == null) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: git option '$unsafeOption' may write output or execute a helper"
        }
        if (subcommand == "branch") requireReadOnlyGitBranch(subcommandArgs)
    }

    private fun requireReadOnlyGitBranch(args: List<String>) {
        var listMode = false
        var expectValue = false
        var terminatedOptions = false
        args.forEach { argument ->
            if (expectValue) {
                expectValue = false
                return@forEach
            }
            if (!terminatedOptions && argument == "--") {
                terminatedOptions = true
                return@forEach
            }
            if (!terminatedOptions && argument.startsWith("-")) {
                when {
                    argument == "--list" || argument.startsWith("--list=") -> listMode = true
                    argument in GIT_BRANCH_FLAG_OPTIONS -> Unit
                    argument.substringBefore('=') in GIT_BRANCH_VALUE_OPTIONS -> {
                        if ('=' !in argument) expectValue = true
                        if (argument.substringBefore('=') in GIT_BRANCH_LIST_VALUE_OPTIONS) listMode = true
                    }
                    else -> throw IllegalArgumentException(
                        "CLAUDE_PLAN_COMMAND_BLOCKED: git branch option '$argument' is not in the read-only grammar",
                    )
                }
            } else {
                require(listMode) {
                    "CLAUDE_PLAN_COMMAND_BLOCKED: git branch names can create or change refs; use --list"
                }
            }
        }
        require(!expectValue) {
            "CLAUDE_PLAN_COMMAND_BLOCKED: git branch option is missing its value"
        }
    }

    private fun requireReadOnlyFind(args: List<String>) {
        var expressionStarted = false
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            if (!expressionStarted && isFindLeadingOption(argument)) {
                if (argument == "-D") {
                    require(index + 1 < args.size) {
                        "CLAUDE_PLAN_COMMAND_BLOCKED: find -D is missing its value"
                    }
                    index += 2
                } else {
                    index++
                }
                continue
            }
            if (!expressionStarted && !looksLikeFindExpression(argument)) {
                index++
                continue
            }
            expressionStarted = true
            when {
                argument in FIND_BOOLEAN_OPERATORS || argument in FIND_ZERO_ARGUMENT_PREDICATES -> index++
                argument in FIND_ONE_ARGUMENT_PREDICATES || argument.matches(FIND_NEWER_PREDICATE) -> {
                    require(index + 1 < args.size) {
                        "CLAUDE_PLAN_COMMAND_BLOCKED: find predicate '$argument' is missing its value"
                    }
                    index += 2
                }
                else -> throw IllegalArgumentException(
                    "CLAUDE_PLAN_COMMAND_BLOCKED: find predicate '$argument' is not in the read-only grammar",
                )
            }
        }
    }

    private fun isFindLeadingOption(argument: String): Boolean =
        argument == "-H" || argument == "-L" || argument == "-P" || argument == "-D" ||
            argument.matches(Regex("-O[0-3]"))

    private fun looksLikeFindExpression(argument: String): Boolean =
        argument.startsWith("-") || argument == "!" || argument == "(" || argument == ")"

    private fun List<String>.optionsBeforeTerminator(): Sequence<String> = sequence {
        for (argument in this@optionsBeforeTerminator) {
            if (argument == "--") break
            if (argument.startsWith("-")) yield(argument)
        }
    }

    private fun isShellCompositionToken(argument: String): Boolean =
        argument in SHELL_COMPOSITION_TOKENS || SHELL_REDIRECTION_TOKEN.matches(argument)

    /** GNU-style parsers may accept long-option abbreviations, so dangerous names are matched by prefix. */
    private fun String.matchesLongOption(canonical: String): Boolean {
        val suppliedName = substringBefore('=')
        return suppliedName.startsWith("--") && canonical.startsWith(suppliedName)
    }

    private val READ_ONLY_GIT_SUBCOMMANDS = setOf(
        "blame", "branch", "cat-file", "describe", "diff", "diff-files", "diff-index", "diff-tree",
        "for-each-ref", "grep", "log", "ls-files", "ls-tree", "merge-base", "name-rev", "rev-list",
        "rev-parse", "shortlog", "show", "show-ref", "status",
    )
    private val GIT_BRANCH_FLAG_OPTIONS = setOf(
        "-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "--show-current",
        "--ignore-case", "--no-column", "--no-color",
    )
    private val GIT_BRANCH_VALUE_OPTIONS = setOf(
        "--contains", "--no-contains", "--merged", "--no-merged", "--points-at", "--sort", "--format",
        "--color", "--column", "--abbrev",
    )
    private val GIT_BRANCH_LIST_VALUE_OPTIONS = setOf(
        "--contains", "--no-contains", "--merged", "--no-merged", "--points-at",
    )
    private val FIND_BOOLEAN_OPERATORS = setOf("!", "(", ")", "-not", "-a", "-and", "-o", "-or", ",")
    private val FIND_ZERO_ARGUMENT_PREDICATES = setOf(
        "-print", "-print0", "-empty", "-readable", "-writable", "-executable", "-true", "-false",
        "-prune", "-ls", "-xdev", "-mount", "-depth", "-ignore_readdir_race", "-noignore_readdir_race",
        "-noleaf", "-nouser", "-nogroup",
    )
    private val FIND_ONE_ARGUMENT_PREDICATES = setOf(
        "-name", "-iname", "-path", "-ipath", "-wholename", "-iwholename", "-regex", "-iregex",
        "-type", "-xtype", "-uid", "-gid", "-user", "-group", "-size", "-atime", "-amin", "-ctime",
        "-cmin", "-mtime", "-mmin", "-newer", "-perm", "-links", "-inum", "-samefile", "-fstype",
        "-maxdepth", "-mindepth", "-printf", "-files0-from",
    )
    private val FIND_NEWER_PREDICATE = Regex("-newer(?:[acmB][acmtB])?")
    private val SHELL_COMPOSITION_TOKENS = setOf("|", "||", "&&", ";", "&", "<", ">", "<<", ">>")
    private val SHELL_REDIRECTION_TOKEN = Regex("(?:\\d*)(?:>>?|<<?|<>|>&|<&).+")
}

private data class BoundedText(val text: String, val truncated: Boolean)
private data class CommandOutput(
    val exitCode: Int,
    val isTimeout: Boolean,
    val stdout: BoundedText,
    val stderr: BoundedText,
)

private const val MAX_OUTPUT_CHARS = 30_000
private const val OUTPUT_DRAIN_TIMEOUT_MS = 2_000L
private const val STREAM_CLOSE_GRACE_MS = 1_000L
private const val PROCESS_EXIT_GRACE_MS = 1_000L
private val SAFE_ENVIRONMENT_KEYS = setOf(
    "PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL", "TERM", "SystemRoot", "ComSpec",
)
private val CLAUDE_PLAN_ENVIRONMENT = mapOf(
    "GIT_PAGER" to "cat",
    "PAGER" to "cat",
    "GIT_TERMINAL_PROMPT" to "0",
    "GIT_OPTIONAL_LOCKS" to "0",
    "GIT_CONFIG_NOSYSTEM" to "1",
)
