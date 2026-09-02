package dev.omnicode.tool

import com.intellij.openapi.project.Project
import dev.omnicode.mcp.McpLaunchApprovalDecision
import dev.omnicode.mcp.McpLaunchApprovalGate
import dev.omnicode.mcp.McpLaunchApprovalRequest
import dev.omnicode.mcp.McpLaunchAuditEvent
import dev.omnicode.mcp.McpLaunchAuditOutcome
import dev.omnicode.mcp.McpLaunchAuditSink
import dev.omnicode.mcp.McpLaunchRejectedException
import dev.omnicode.mcp.McpLaunchTrustStore
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpEnvironmentSecretReader
import dev.omnicode.settings.SandboxMode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxedMcpProcessLauncherTest {
    @Test
    fun `MCP runtime path includes GUI-launched runtime directories`() {
        val home = createTempDirectory("omnicode-mcp-runtime").toRealPath()
        val nvmBin = home.resolve(".nvm/versions/node/v22.14.0/bin")
        Files.createDirectories(nvmBin)

        try {
            val path = mcpRuntimePath(
                existingPath = "/shell/bin",
                executable = Path.of("/opt/node/bin/npx"),
                home = home.toString(),
                osName = "Linux",
            )
            val directories = path.split(File.pathSeparator)

            assertEquals("/shell/bin", directories.first())
            assertTrue(directories.contains("/opt/node/bin"))
            assertTrue(directories.contains(home.resolve(".local/bin").toString()))
            assertTrue(directories.contains(home.resolve(".bun/bin").toString()))
            assertTrue(directories.contains(nvmBin.toString()))
            assertTrue(directories.contains("/usr/bin"))
        } finally {
            deleteRecursively(home)
        }
    }

    @Test
    fun `workspace sandbox is activated before MCP process start`() = runBlocking {
        val workspace = createTempDirectory("omnicode-mcp-launcher").toRealPath()
        val java = javaExecutable()
        val sandbox = ProcessSandbox(
            osName = "Mac OS X",
            sandboxExecutable = java,
            availabilityProbe = { true },
        )
        val starterCalled = AtomicBoolean(false)
        var capturedBuilder: ProcessBuilder? = null
        var storedSecret = "password-safe-value"
        val audits = mutableListOf<McpLaunchAuditEvent>()
        val launcher = SandboxedMcpProcessLauncher(
            project = projectAt(workspace),
            sandboxMode = SandboxMode.WORKSPACE_WRITE,
            sandbox = sandbox,
            processStarter = { builder ->
                val sandboxHome = workspace.resolve(".omnicode-sandbox-home")
                assertTrue(Files.isDirectory(sandboxHome), "Sandbox HOME must exist before process start")
                starterCalled.set(true)
                capturedBuilder = builder
                StubProcess()
            },
            approvalGate = McpLaunchApprovalGate { McpLaunchApprovalDecision.ALLOW_ONCE },
            trustStore = InMemoryTrustStore(),
            auditSink = McpLaunchAuditSink(audits::add),
            projectId = "project",
            secretReader = McpEnvironmentSecretReader { _, key ->
                when (key) {
                    "MCP_TOKEN" -> storedSecret
                    "SHORT_TOKEN" -> "xy"
                    else -> ""
                }
            },
        )

        try {
            val launched = launcher.launchWithDiagnostics(
                McpServerConfig(
                    id = "sandbox-test",
                    name = "Sandbox test",
                    enabled = true,
                    command = java.toString(),
                    arguments = listOf("-version"),
                    environmentKeys = setOf("MCP_TOKEN", "SHORT_TOKEN"),
                    workingDirectory = ".",
                ),
            )
            val process = launched.process
            storedSecret = "rotated-after-launch"

            assertTrue(starterCalled.get())
            val builder = requireNotNull(capturedBuilder)
            assertEquals(workspace.resolve(".omnicode-sandbox-home").toString(), builder.environment()["HOME"])
            assertEquals(SandboxMode.WORKSPACE_WRITE.name, builder.environment()["OMNICODE_SANDBOX_MODE"])
            assertEquals("password-safe-value", builder.environment()["MCP_TOKEN"])
            assertEquals("xy", builder.environment()["SHORT_TOKEN"])
            val diagnostic = launched.diagnosticRedactor.redact(
                "old=password-safe-value short=xy new=rotated-after-launch",
            )
            assertFalse(diagnostic.contains("password-safe-value"))
            assertFalse(diagnostic.contains("short=xy"))
            assertTrue(diagnostic.contains("rotated-after-launch"))
            assertEquals(
                listOf(
                    McpLaunchAuditOutcome.APPROVAL_REQUESTED,
                    McpLaunchAuditOutcome.APPROVED_ONCE,
                    McpLaunchAuditOutcome.STARTED,
                ),
                audits.map(McpLaunchAuditEvent::outcome),
            )
            process.destroy()
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `rejected danger-full-access MCP launch never starts the process`() = runBlocking {
        val workspace = createTempDirectory("omnicode-mcp-rejected").toRealPath()
        val executable = executableAt(workspace, "mcp-server")
        val starterCalled = AtomicBoolean(false)
        val audits = mutableListOf<McpLaunchAuditEvent>()
        var request: McpLaunchApprovalRequest? = null
        val launcher = SandboxedMcpProcessLauncher(
            project = projectAt(workspace),
            sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
            sandbox = ProcessSandbox(
                osName = "Mac OS X",
                sandboxExecutable = executable,
                availabilityProbe = { true },
            ),
            processStarter = {
                starterCalled.set(true)
                StubProcess()
            },
            approvalGate = McpLaunchApprovalGate { approval ->
                request = approval
                McpLaunchApprovalDecision.REJECT
            },
            trustStore = InMemoryTrustStore(),
            auditSink = McpLaunchAuditSink(audits::add),
            projectId = "project",
        )

        try {
            val error = assertFailsWith<McpLaunchRejectedException> {
                launcher.launch(config(executable, environmentKeys = setOf("MCP_TOKEN", "HOME")))
            }

            assertTrue(error.message.orEmpty().contains("approval was rejected"))
            assertFalse(starterCalled.get())
            val approval = requireNotNull(request)
            assertEquals("Test server", approval.serverName)
            assertEquals(executable.toString(), approval.command)
            assertEquals(workspace.toString(), approval.workingDirectory)
            assertEquals(SandboxMode.DANGER_FULL_ACCESS, approval.sandboxMode)
            assertEquals(setOf("MCP_TOKEN", "HOME"), approval.environmentKeys)
            assertEquals(
                listOf(McpLaunchAuditOutcome.APPROVAL_REQUESTED, McpLaunchAuditOutcome.REJECTED),
                audits.map(McpLaunchAuditEvent::outcome),
            )
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `trusted launch is reused only until config or executable fingerprint changes`() = runBlocking {
        val workspace = createTempDirectory("omnicode-mcp-trust").toRealPath()
        val executable = executableAt(workspace, "mcp-server")
        val trustStore = InMemoryTrustStore()
        val approvals = mutableListOf<McpLaunchApprovalRequest>()
        val audits = mutableListOf<McpLaunchAuditEvent>()
        var starts = 0
        val launcher = SandboxedMcpProcessLauncher(
            project = projectAt(workspace),
            sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
            sandbox = ProcessSandbox(
                osName = "Mac OS X",
                sandboxExecutable = executable,
                availabilityProbe = { true },
            ),
            processStarter = {
                starts++
                StubProcess()
            },
            approvalGate = McpLaunchApprovalGate { approval ->
                approvals += approval
                McpLaunchApprovalDecision.TRUST_CONFIGURATION
            },
            trustStore = trustStore,
            auditSink = McpLaunchAuditSink(audits::add),
            projectId = "project",
        )

        try {
            launcher.launch(config(executable)).destroy()
            launcher.launch(config(executable)).destroy()
            assertEquals(1, approvals.size, "same project and fingerprint should reuse persistent trust")

            launcher.launch(config(executable, arguments = listOf("--changed"))).destroy()
            assertEquals(2, approvals.size, "configuration changes must invalidate trust")

            Files.writeString(executable, "#!/bin/sh\necho changed\n")
            launcher.launch(config(executable, arguments = listOf("--changed"))).destroy()
            assertEquals(3, approvals.size, "executable content changes must invalidate trust")
            assertEquals(4, starts)
            assertTrue(audits.any { it.outcome == McpLaunchAuditOutcome.PERSISTENT_TRUST_USED })
        } finally {
            deleteRecursively(workspace)
        }
    }

    @Test
    fun `executable changed while approval is open is not started`() = runBlocking {
        val workspace = createTempDirectory("omnicode-mcp-race").toRealPath()
        val executable = executableAt(workspace, "mcp-server")
        val starterCalled = AtomicBoolean(false)
        val launcher = SandboxedMcpProcessLauncher(
            project = projectAt(workspace),
            sandboxMode = SandboxMode.DANGER_FULL_ACCESS,
            sandbox = ProcessSandbox("Mac OS X", executable) { true },
            processStarter = {
                starterCalled.set(true)
                StubProcess()
            },
            approvalGate = McpLaunchApprovalGate {
                Files.writeString(executable, "#!/bin/sh\necho replaced executable\n")
                McpLaunchApprovalDecision.ALLOW_ONCE
            },
            trustStore = InMemoryTrustStore(),
            auditSink = McpLaunchAuditSink { },
            projectId = "project",
        )

        try {
            val error = assertFailsWith<IllegalArgumentException> { launcher.launch(config(executable)) }
            assertTrue(error.message.orEmpty().contains("changed after approval"))
            assertFalse(starterCalled.get())
        } finally {
            deleteRecursively(workspace)
        }
    }

    private fun config(
        executable: Path,
        arguments: List<String> = emptyList(),
        environmentKeys: Set<String> = emptySet(),
    ): McpServerConfig = McpServerConfig(
        id = "test-server",
        name = "Test server",
        enabled = true,
        command = executable.toString(),
        arguments = arguments,
        environmentKeys = environmentKeys,
        workingDirectory = ".",
    )

    private fun executableAt(workspace: Path, name: String): Path = workspace.resolve(name).also { executable ->
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        check(executable.toFile().setExecutable(true))
    }.toRealPath()

    private fun projectAt(root: Path): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getBasePath" -> root.toString()
            "isDisposed" -> false
            "getName" -> "MCP sandbox test"
            "toString" -> "McpSandboxProject(${root.fileName})"
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

    private fun javaExecutable(): Path {
        val suffix = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", suffix).toRealPath()
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private class StubProcess : Process() {
        private val alive = AtomicBoolean(true)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            alive.set(false)
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            alive.set(false)
            return true
        }
        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("Stub process is alive")
            return 0
        }
        override fun destroy() {
            alive.set(false)
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = alive.get()
    }

    private class InMemoryTrustStore : McpLaunchTrustStore {
        private val fingerprints = mutableMapOf<Pair<String, String>, String>()

        override fun isTrusted(serverId: String, projectId: String, fingerprint: String): Boolean =
            fingerprints[serverId to projectId] == fingerprint

        override fun trust(serverId: String, projectId: String, fingerprint: String) {
            fingerprints[serverId to projectId] = fingerprint
        }
    }
}
