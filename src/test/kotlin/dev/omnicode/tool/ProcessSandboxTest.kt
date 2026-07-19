package dev.omnicode.tool

import dev.omnicode.settings.SandboxMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessSandboxTest {
    @Test
    fun `workspace mode wraps direct argv without profile interpolation`() {
        withWorkspace { workspace ->
            val executable = javaExecutable()
            val sandbox = ProcessSandbox(
                osName = "Mac OS X",
                sandboxExecutable = executable,
                availabilityProbe = { true },
            )
            val argument = "value with spaces and \$(not-a-shell)"
            val plan = sandbox.prepare(
                ProcessSandboxRequest(
                    mode = SandboxMode.WORKSPACE_WRITE,
                    workspaceRoot = workspace,
                    cwd = workspace,
                    requestedExecutable = "java",
                    executable = executable,
                    arguments = listOf(argument),
                ),
            )

            assertEquals(SandboxEnforcement.MACOS_SANDBOX_EXEC, plan.capability.enforcement)
            assertTrue(plan.capability.enforced)
            assertEquals(executable.toRealPath().toString(), plan.commandArgv.first())
            assertEquals(argument, plan.commandArgv.last())
            assertTrue(plan.launchArgv.contains("-DOMNICODE_WORKSPACE=${workspace.toRealPath()}"))
            assertEquals(argument, plan.launchArgv.last())
            assertFalse(ProcessSandbox.MACOS_WORKSPACE_PROFILE.contains(workspace.toString()))
        }
    }

    @Test
    fun `workspace mode fails closed when no sandbox capability exists`() {
        withWorkspace { workspace ->
            val executable = javaExecutable()
            val sandbox = ProcessSandbox(
                osName = "Linux",
                sandboxExecutable = executable,
                availabilityProbe = { false },
            )

            val error = assertFailsWith<SandboxUnavailableException> {
                sandbox.prepare(request(workspace, executable, SandboxMode.WORKSPACE_WRITE))
            }
            assertTrue(error.message.orEmpty().contains("probe"))
            assertTrue(error.message.orEmpty().contains("no automatic downgrade"))
        }
    }

    @Test
    fun `linux workspace mode builds a direct bubblewrap plan with private home tmp and network`() {
        withWorkspace { workspace ->
            val executable = javaExecutable()
            val sandbox = ProcessSandbox(
                osName = "Linux",
                sandboxExecutable = executable,
                availabilityProbe = { true },
                userHome = workspace.parent,
            )
            val argument = "value with spaces and \$(not-a-shell)"
            val plan = sandbox.prepare(
                ProcessSandboxRequest(
                    mode = SandboxMode.WORKSPACE_WRITE,
                    workspaceRoot = workspace,
                    cwd = workspace,
                    requestedExecutable = "java",
                    executable = executable,
                    arguments = listOf(argument),
                ),
            )

            assertEquals(SandboxEnforcement.LINUX_BUBBLEWRAP, plan.capability.enforcement)
            assertTrue(plan.capability.enforced)
            assertTrue(plan.launchArgv.contains("--unshare-all"))
            assertTrue(plan.launchArgv.contains("--unshare-net"))
            assertTrue(plan.launchArgv.windowed(3).contains(listOf("--ro-bind", "/", "/")))
            assertTrue(
                plan.launchArgv.windowed(3).contains(
                    listOf("--bind", workspace.toRealPath().toString(), workspace.toRealPath().toString()),
                ),
            )
            assertTrue(plan.launchArgv.windowed(2).contains(listOf("--chdir", workspace.toRealPath().toString())))
            assertEquals("/tmp/omnicode-home", plan.environmentOverrides["HOME"])
            assertEquals("/tmp", plan.environmentOverrides["TMPDIR"])
            assertEquals(argument, plan.launchArgv.last())
            assertEquals(listOf(executable.toRealPath().toString(), argument), plan.commandArgv)
            sandbox.activate(plan)
            assertFalse(Files.exists(workspace.resolve(".omnicode-sandbox-home")))
        }
    }

    @Test
    fun `linux missing bubblewrap gives an actionable error without running a probe`() {
        val probeCalled = booleanArrayOf(false)
        val missing = Path.of("/definitely-missing-omnicode-bwrap")
        val sandbox = ProcessSandbox(
            osName = "Linux",
            sandboxExecutable = missing,
            availabilityProbe = {
                probeCalled[0] = true
                true
            },
        )

        val capability = sandbox.capability(SandboxMode.WORKSPACE_WRITE)
        assertFalse(capability.available)
        assertFalse(probeCalled[0])
        assertTrue(capability.summary.contains("Install the bubblewrap package"))
        assertTrue(capability.summary.contains("no automatic downgrade"))
    }

    @Test
    fun `sandbox backend changing during capability probe is rejected`() {
        val backend = Files.createTempFile("omnicode-sandbox-backend", ".bin")
        Files.writeString(backend, "original-backend")
        check(backend.toFile().setExecutable(true))
        try {
            val sandbox = ProcessSandbox(
                osName = "Linux",
                sandboxExecutable = backend,
                availabilityProbe = { probed ->
                    Files.writeString(probed, "replaced-backend-with-different-content-and-size")
                    true
                },
            )

            val capability = sandbox.capability(SandboxMode.WORKSPACE_WRITE)
            assertFalse(capability.available)
            assertFalse(capability.enforced)
            assertTrue(capability.summary.contains("changed during its enforcement probe"))
        } finally {
            Files.deleteIfExists(backend)
        }
    }

    @Test
    fun `windows WSL detection remains fail closed without a proven host path bridge`() {
        withWorkspace { workspace ->
            val executable = javaExecutable()
            val sandbox = ProcessSandbox(
                osName = "Windows 11",
                sandboxExecutable = executable,
                availabilityProbe = { true },
            )

            val capability = sandbox.capability(SandboxMode.WORKSPACE_WRITE)
            assertFalse(capability.available)
            assertFalse(capability.enforced)
            assertEquals(SandboxEnforcement.UNAVAILABLE, capability.enforcement)
            assertTrue(capability.summary.contains("WSL2 and bubblewrap were detected"))
            assertTrue(capability.summary.contains("cannot prove a safe host-path bridge"))
            assertFailsWith<SandboxUnavailableException> {
                sandbox.prepare(request(workspace, executable, SandboxMode.WORKSPACE_WRITE))
            }
        }
    }

    @Test
    fun `platform guidance gives actionable Linux and Windows recovery`() {
        val linux = ProcessSandbox.setupGuidance("Linux")
        val windows = ProcessSandbox.setupGuidance("Windows 11")

        assertTrue(linux.contains("bubblewrap"))
        assertTrue(linux.contains("不会降级"))
        assertTrue(windows.contains("WSL2"))
        assertTrue(windows.contains("fail closed"))
    }

    @Test
    fun `danger full access is explicit and remains direct argv`() {
        withWorkspace { workspace ->
            val executable = javaExecutable()
            val sandbox = ProcessSandbox(
                osName = "Linux",
                sandboxExecutable = executable,
                availabilityProbe = { false },
            )
            val plan = sandbox.prepare(request(workspace, executable, SandboxMode.DANGER_FULL_ACCESS))

            assertEquals(plan.commandArgv, plan.launchArgv)
            assertFalse(plan.capability.enforced)
            assertEquals(SandboxEnforcement.NONE, plan.capability.enforcement)
            assertTrue(plan.capability.summary.contains("no OS-level"))
        }
    }

    @Test
    fun `workspace mode blocks unapproved system commands and all modes block shells`() {
        withWorkspace { workspace ->
            val executable = javaExecutable()
            val blockedExecutable = Files.createTempFile("omnicode-shutdown-command", ".bin").also {
                check(it.toFile().setExecutable(true))
            }
            val sandbox = ProcessSandbox(
                osName = "Mac OS X",
                sandboxExecutable = executable,
                availabilityProbe = { true },
            )

            try {
                assertFailsWith<IllegalArgumentException> {
                    sandbox.prepare(
                        request(
                            workspace,
                            blockedExecutable,
                            SandboxMode.WORKSPACE_WRITE,
                            requestedExecutable = "shutdown",
                        ),
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    sandbox.prepare(
                        request(
                            workspace,
                            blockedExecutable,
                            SandboxMode.WORKSPACE_WRITE,
                            requestedExecutable = "git",
                        ),
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    sandbox.prepare(
                        request(
                            workspace,
                            executable,
                            SandboxMode.DANGER_FULL_ACCESS,
                            requestedExecutable = "bash",
                        ),
                    )
                }
            } finally {
                Files.deleteIfExists(blockedExecutable)
            }
        }
    }

    @Test
    fun `working directory must resolve inside workspace`() {
        withWorkspace { workspace ->
            val outside = createTempDirectory("omnicode-sandbox-outside")
            try {
                val executable = javaExecutable()
                val sandbox = ProcessSandbox(
                    osName = "Mac OS X",
                    sandboxExecutable = executable,
                    availabilityProbe = { true },
                )
                assertFailsWith<IllegalArgumentException> {
                    sandbox.prepare(
                        request(workspace, executable, SandboxMode.WORKSPACE_WRITE).copy(cwd = outside),
                    )
                }
            } finally {
                deleteRecursively(outside)
            }
        }
    }

    @Test
    fun `mac sandbox enforces workspace file boundary`() {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return

        val sandbox = ProcessSandbox()
        assertTrue(sandbox.capability(SandboxMode.WORKSPACE_WRITE).enforced)
        withWorkspace { workspace ->
            val inside = workspace.resolve("inside.txt")
            val outside = Files.createTempFile("omnicode-sandbox-outside-secret", ".txt")
            Files.writeString(inside, "inside-value")
            Files.writeString(outside, "outside-secret")
            try {
                val insideRead = runPlan(
                    sandbox.prepare(
                        requestForCommand(workspace, Path.of("/bin/cat"), "cat", listOf(inside.toString())),
                    ),
                )
                assertEquals(0, insideRead.exitCode)
                assertEquals("inside-value", insideRead.stdout)

                val outsideRead = runPlan(
                    sandbox.prepare(
                        requestForCommand(workspace, Path.of("/bin/cat"), "cat", listOf(outside.toString())),
                    ),
                )
                assertTrue(outsideRead.exitCode != 0)
                assertFalse(outsideRead.stdout.contains("outside-secret"))

                val insideCreated = workspace.resolve("created.txt")
                val insideWrite = runPlan(
                    sandbox.prepare(
                        requestForCommand(workspace, Path.of("/usr/bin/touch"), "touch", listOf(insideCreated.toString())),
                    ),
                )
                assertEquals(0, insideWrite.exitCode, insideWrite.stderr)
                assertTrue(Files.exists(insideCreated))

                val outsideCreated = outside.resolveSibling("${outside.fileName}.created")
                val outsideWrite = runPlan(
                    sandbox.prepare(
                        requestForCommand(workspace, Path.of("/usr/bin/touch"), "touch", listOf(outsideCreated.toString())),
                    ),
                )
                assertTrue(outsideWrite.exitCode != 0)
                assertFalse(Files.exists(outsideCreated))
            } finally {
                Files.deleteIfExists(outside)
                Files.deleteIfExists(outside.resolveSibling("${outside.fileName}.created"))
            }
        }
    }

    @Test
    fun `linux bubblewrap enforces workspace boundary when available`() {
        if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return

        val sandbox = ProcessSandbox()
        if (!sandbox.capability(SandboxMode.WORKSPACE_WRITE).enforced) return
        withWorkspace { workspace ->
            val inside = workspace.resolve("inside.txt")
            val outside = Files.createTempFile("omnicode-bwrap-outside-secret", ".txt")
            Files.writeString(inside, "inside-value")
            Files.writeString(outside, "outside-secret")
            try {
                val insideRead = runPlan(
                    sandbox.prepare(
                        requestForCommand(workspace, Path.of("/bin/cat"), "cat", listOf(inside.toString())),
                    ),
                )
                assertEquals(0, insideRead.exitCode)
                assertEquals("inside-value", insideRead.stdout)

                val outsideRead = runPlan(
                    sandbox.prepare(
                        requestForCommand(workspace, Path.of("/bin/cat"), "cat", listOf(outside.toString())),
                    ),
                )
                assertTrue(outsideRead.exitCode != 0)
                assertFalse(outsideRead.stdout.contains("outside-secret"))

                val insideCreated = workspace.resolve("created.txt")
                val insideWrite = runPlan(
                    sandbox.prepare(
                        requestForCommand(workspace, Path.of("/usr/bin/touch"), "touch", listOf(insideCreated.toString())),
                    ),
                )
                assertEquals(0, insideWrite.exitCode, insideWrite.stderr)
                assertTrue(Files.exists(insideCreated))
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    private fun request(
        workspace: Path,
        executable: Path,
        mode: SandboxMode,
        requestedExecutable: String = "java",
    ): ProcessSandboxRequest = ProcessSandboxRequest(
        mode = mode,
        workspaceRoot = workspace,
        cwd = workspace,
        requestedExecutable = requestedExecutable,
        executable = executable,
        arguments = listOf("-version"),
    )

    private fun requestForCommand(
        workspace: Path,
        executable: Path,
        requestedExecutable: String,
        arguments: List<String>,
    ): ProcessSandboxRequest = ProcessSandboxRequest(
        mode = SandboxMode.WORKSPACE_WRITE,
        workspaceRoot = workspace,
        cwd = workspace,
        requestedExecutable = requestedExecutable,
        executable = executable,
        arguments = arguments,
    )

    private fun runPlan(plan: ProcessSandboxPlan): ProcessResult {
        val builder = ProcessBuilder(plan.launchArgv).directory(plan.cwd.toFile())
        builder.environment().clear()
        builder.environment().putAll(plan.environmentOverrides)
        val process = builder.start()
        check(process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Sandbox integration command timed out"
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText(),
            stderr = process.errorStream.bufferedReader().readText(),
        )
    }

    private fun javaExecutable(): Path {
        val suffix = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", suffix).toRealPath()
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = createTempDirectory("omnicode-sandbox-workspace").toRealPath()
        try {
            block(workspace)
        } finally {
            deleteRecursively(workspace)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
