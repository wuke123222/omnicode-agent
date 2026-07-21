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
import java.util.concurrent.TimeUnit

class RunCommandTool(
    private val sandboxMode: SandboxMode = SandboxMode.DEFAULT,
    private val processSandbox: ProcessSandbox = ProcessSandbox(),
    private val processStarter: (GeneralCommandLine) -> Process = { commandLine -> commandLine.createProcess() },
) : AgentTool {
    override val name = "run_command"
    override val description = "Run one non-interactive executable with an argument array after explicit approval. Commands use direct argv execution and default to an OS-enforced workspace sandbox."
    override val dangerous = true
    override val effect = ToolEffect.COMMAND
    override val inputSchema: JsonObject = objectSchema(required = listOf("argv")) {
        stringArrayProperty("argv", "Executable followed by its individual arguments, for example [\"npm\", \"test\"].")
        stringProperty("cwd", "Project-relative working directory. Defaults to '.'.")
        integerProperty("timeout_seconds", "Timeout before the process is terminated.", 120, 1, 300)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(
            context.mode == dev.omnicode.agent.AgentMode.AGENT ||
                context.mode == dev.omnicode.agent.AgentMode.RESEARCH,
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
        val cwdRelative = arguments.string("cwd", ".")
        val cwd = ProjectPathGuard.resolve(context.project, cwdRelative)
        require(Files.isDirectory(cwd)) { "Working directory does not exist: $cwdRelative" }
        val executable = resolveExecutable(argv.first(), cwd)
            ?: error("Executable was not found: ${argv.first()}")
        require(argv.none { it.contains('\u0000') || it.contains('\n') || it.contains('\r') }) { "Arguments must not contain control lines" }
        val timeout = arguments.int("timeout_seconds", 120).coerceIn(1, 300)
        val sandboxRequest = ProcessSandboxRequest(
            mode = sandboxMode,
            workspaceRoot = ProjectPathGuard.root(context.project),
            cwd = cwd,
            requestedExecutable = argv.first(),
            executable = executable,
            arguments = argv.drop(1),
        )
        val proposedPlan = processSandbox.prepare(sandboxRequest)

        val approved = context.approvalGate.approve(
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
            .map { Path.of(it).resolve(value) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.toRealPath()
    }
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
