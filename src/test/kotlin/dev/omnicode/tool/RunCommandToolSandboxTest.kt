package dev.omnicode.tool

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.settings.SandboxMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunCommandToolSandboxTest {
    @Test
    fun `claude plan runs validated exploration without approval and forces read only sandbox`() = runBlocking {
        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        val approvals = AtomicInteger()
        val launches = AtomicInteger()
        try {
            val tool = RunCommandTool(
                sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
                processSandbox = ProcessSandbox(
                    osName = "Linux",
                    sandboxExecutable = javaExecutable(),
                    availabilityProbe = { true },
                ),
                processStarter = {
                    launches.incrementAndGet()
                    CompletedProcess()
                },
            )
            val result = tool.execute(
                // Git is available on all GitHub-hosted runners (including Windows),
                // while the Unix-only `ls` name is not guaranteed on the Windows PATH.
                commandArguments("git", "status", "--short"),
                ToolExecutionContext(
                    project = projectAt(workspace),
                    approvalGate = ApprovalGate {
                        approvals.incrementAndGet()
                        false
                    },
                    mode = AgentMode.CLAUDE_PLAN,
                ),
            )

            assertFalse(result.isError, result.content)
            assertEquals(0, approvals.get())
            assertEquals(1, launches.get())
            assertTrue(result.content.contains("read-only", ignoreCase = true), result.content)
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `claude plan rejects an unproven command before approval or process launch`() = runBlocking {
        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        val approvals = AtomicInteger()
        val launches = AtomicInteger()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                RunCommandTool(processStarter = {
                    launches.incrementAndGet()
                    CompletedProcess()
                }).execute(
                    commandArguments("touch", "created.txt"),
                    ToolExecutionContext(
                        project = projectAt(workspace),
                        approvalGate = ApprovalGate {
                            approvals.incrementAndGet()
                            true
                        },
                        mode = AgentMode.CLAUDE_PLAN,
                    ),
                )
            }

            assertTrue(error.message.orEmpty().startsWith("CLAUDE_PLAN_COMMAND_BLOCKED:"))
            assertEquals(0, approvals.get())
            assertEquals(0, launches.get())
            assertFalse(Files.exists(workspace.resolve("created.txt")))
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `null argv is rejected as a validation error instead of a json cast failure`() = runBlocking {
        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                RunCommandTool().execute(
                    JsonObject().apply { add("argv", JsonNull.INSTANCE) },
                    ToolExecutionContext(projectAt(workspace), ApprovalGate { true }, AgentMode.AGENT),
                )
            }

            assertEquals("argv must be an array", error.message)
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `research command reaches approval with the configured sandbox plan`() = runBlocking {
        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        try {
            var approvals = 0
            var approvalDetails = ""
            val tool = RunCommandTool(sandboxMode = SandboxMode.DANGER_FULL_ACCESS)
            val result = tool.execute(
                fakeCommandArguments(timeoutSeconds = 15),
                ToolExecutionContext(
                    project = projectAt(workspace),
                    approvalGate = ApprovalGate { request ->
                        approvals++
                        approvalDetails = request.details
                        false
                    },
                    mode = AgentMode.RESEARCH,
                ),
            )

            assertTrue(result.isError)
            assertTrue(result.content.startsWith("REJECTED_BY_USER"))
            assertEquals(1, approvals)
            assertTrue(approvalDetails.contains("Sandbox: DANGER_FULL_ACCESS"))
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `default mode really denies reading outside workspace on mac`() = runBlocking {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return@runBlocking

        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        val inside = workspace.resolve("inside.txt")
        val outside = Files.createTempFile("omnicode-command-outside", ".txt")
        Files.writeString(inside, "inside-value")
        Files.writeString(outside, "outside-secret")
        try {
            val tool = RunCommandTool()
            val insideResult = tool.execute(
                commandArguments(Path.of("/bin/cat"), inside),
                ToolExecutionContext(projectAt(workspace), ApprovalGate { true }, AgentMode.AGENT),
            )
            assertFalse(insideResult.isError, insideResult.content)
            assertTrue(insideResult.content.contains("inside-value"))
            assertTrue(insideResult.content.contains("macOS sandbox-exec enforced"))
            assertTrue(Files.isDirectory(workspace.resolve(".omnicode-sandbox-home")))

            val outsideResult = tool.execute(
                commandArguments(Path.of("/bin/cat"), outside),
                ToolExecutionContext(projectAt(workspace), ApprovalGate { true }, AgentMode.AGENT),
            )
            assertTrue(outsideResult.isError)
            assertFalse(outsideResult.content.contains("outside-secret"))
        } finally {
            deleteRecursively(workspace)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `danger mode stays approved bounded and explicitly unsandboxed`() = runBlocking {
        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        try {
            var approvalDetails = ""
            val tool = RunCommandTool(sandboxMode = SandboxMode.DANGER_FULL_ACCESS)
            val result = tool.execute(
                JsonObject().apply {
                    add("argv", JsonArray().apply {
                        add(javaExecutable().toString())
                        add("-version")
                    })
                    addProperty("cwd", ".")
                    addProperty("timeout_seconds", 15)
                },
                ToolExecutionContext(
                    project = projectAt(workspace),
                    approvalGate = ApprovalGate { request ->
                        approvalDetails = request.details + "\n" + request.risk
                        true
                    },
                    mode = AgentMode.AGENT,
                ),
            )

            assertFalse(result.isError, result.content)
            assertTrue(approvalDetails.contains("DANGER_FULL_ACCESS"))
            assertTrue(approvalDetails.contains("not OS-sandboxed"))
            assertTrue(result.content.contains("Sandbox: DANGER_FULL_ACCESS"))
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `timeout closes held output pipes and returns promptly`() = runBlocking {
        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        val process = PipeHoldingProcess()
        try {
            val tool = RunCommandTool(
                sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
                processStarter = { process },
            )
            val started = System.nanoTime()
            val result = tool.execute(
                fakeCommandArguments(timeoutSeconds = 1),
                ToolExecutionContext(projectAt(workspace), ApprovalGate { true }, AgentMode.AGENT),
            )
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(result.isError)
            assertTrue(result.content.contains("Timed out after 1s"))
            assertTrue(process.stdout.closed.get())
            assertTrue(process.stderr.closed.get())
            assertTrue(elapsedMillis < 5_000, "Timeout cleanup took ${elapsedMillis}ms")
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `cancellation closes held output pipes promptly`() = runBlocking {
        val workspace = createTempDirectory("omnicode-command-workspace").toRealPath()
        val process = PipeHoldingProcess()
        val launched = CompletableDeferred<Unit>()
        try {
            val tool = RunCommandTool(
                sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
                processStarter = {
                    launched.complete(Unit)
                    process
                },
            )
            val execution = async {
                tool.execute(
                    fakeCommandArguments(timeoutSeconds = 300),
                    ToolExecutionContext(projectAt(workspace), ApprovalGate { true }, AgentMode.AGENT),
                )
            }
            withTimeout(2_000) { launched.await() }
            withTimeout(5_000) { execution.cancelAndJoin() }

            assertTrue(process.stdout.closed.get())
            assertTrue(process.stderr.closed.get())
        } finally {
            deleteRecursively(workspace)
        }
    }

    private fun javaExecutable(): Path {
        val suffix = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", suffix).toRealPath()
    }

    private fun commandArguments(executable: Path, argument: Path): JsonObject = JsonObject().apply {
        add("argv", JsonArray().apply {
            add(executable.toString())
            add(argument.toString())
        })
        addProperty("cwd", ".")
        addProperty("timeout_seconds", 15)
    }

    private fun commandArguments(executable: String, vararg arguments: String): JsonObject = JsonObject().apply {
        add("argv", JsonArray().apply {
            add(executable)
            arguments.forEach(::add)
        })
        addProperty("cwd", ".")
        addProperty("timeout_seconds", 15)
    }

    private fun fakeCommandArguments(timeoutSeconds: Int): JsonObject = JsonObject().apply {
        add("argv", JsonArray().apply {
            add(javaExecutable().toString())
            add("-version")
        })
        addProperty("cwd", ".")
        addProperty("timeout_seconds", timeoutSeconds)
    }

    private fun projectAt(root: Path): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> root.toString()
            "isDisposed" -> false
            "getName" -> "test"
            "toString" -> "TestProject(${root.fileName})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as Project

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private class PipeHoldingProcess : Process() {
        val stdout = CloseAwareBlockingInputStream()
        val stderr = CloseAwareBlockingInputStream()
        private val stdin = ByteArrayOutputStream()
        private val alive = AtomicBoolean(true)

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int {
            while (alive.get()) Thread.sleep(10)
            return 137
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive.get()
        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("Process is still alive")
            return 137
        }
        override fun destroy() {
            alive.set(false)
        }
        override fun destroyForcibly(): Process {
            alive.set(false)
            return this
        }
        override fun isAlive(): Boolean = alive.get()
    }

    private class CompletedProcess : Process() {
        private val stdin = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = false
    }

    private class CloseAwareBlockingInputStream : InputStream() {
        val closed = AtomicBoolean(false)
        private val closeSignal = CountDownLatch(1)

        override fun read(): Int {
            closeSignal.await()
            return -1
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) closeSignal.countDown()
        }
    }
}
