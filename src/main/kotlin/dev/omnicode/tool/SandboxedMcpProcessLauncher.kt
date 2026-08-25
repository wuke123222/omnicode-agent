package dev.omnicode.tool

import com.intellij.openapi.project.Project
import dev.omnicode.mcp.LocalMcpLaunchAuditSink
import dev.omnicode.mcp.McpLaunchApprovalDecision
import dev.omnicode.mcp.McpLaunchApprovalGate
import dev.omnicode.mcp.McpLaunchApprovalRequest
import dev.omnicode.mcp.McpLaunchAuditEvent
import dev.omnicode.mcp.McpLaunchAuditOutcome
import dev.omnicode.mcp.McpLaunchAuditSink
import dev.omnicode.mcp.McpLaunchRejectedException
import dev.omnicode.mcp.McpLaunchTrustStore
import dev.omnicode.mcp.McpLaunchedProcess
import dev.omnicode.mcp.McpProcessLauncher
import dev.omnicode.mcp.SettingsMcpLaunchTrustStore
import dev.omnicode.mcp.asMcpLaunchApprovalGate
import dev.omnicode.mcp.mcpProjectIdentity
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import dev.omnicode.persistence.SensitiveDataRedactor
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpEnvironmentCredentialStore
import dev.omnicode.settings.McpEnvironmentSecretReader
import dev.omnicode.settings.SandboxMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

class SandboxedMcpProcessLauncher internal constructor(
    private val project: Project,
    private val sandboxMode: SandboxMode,
    private val sandbox: ProcessSandbox,
    private val processStarter: (ProcessBuilder) -> Process,
    private val approvalGate: McpLaunchApprovalGate,
    private val trustStore: McpLaunchTrustStore,
    private val auditSink: McpLaunchAuditSink,
    private val projectId: String,
    private val secretReader: McpEnvironmentSecretReader = McpEnvironmentSecretReader { _, _ -> "" },
) : McpProcessLauncher {
    constructor(project: Project, sandboxMode: SandboxMode, approvalGate: ApprovalGate) : this(
        project = project,
        sandboxMode = sandboxMode,
        sandbox = ProcessSandbox(),
        processStarter = { it.start() },
        approvalGate = approvalGate.asMcpLaunchApprovalGate(),
        trustStore = SettingsMcpLaunchTrustStore(),
        auditSink = LocalMcpLaunchAuditSink(),
        projectId = mcpProjectIdentity(project),
        secretReader = McpEnvironmentCredentialStore.getInstance(),
    )

    override suspend fun launch(config: McpServerConfig): Process = launchWithDiagnostics(config).process

    override suspend fun launchWithDiagnostics(config: McpServerConfig): McpLaunchedProcess =
        withContext(Dispatchers.IO) {
        require(config.command.isNotBlank()) { "MCP command must not be blank" }
        config.environmentKeys.forEach { key ->
            require(ENVIRONMENT_KEY.matches(key)) { "Invalid MCP environment key: $key" }
        }
        val workspace = ProjectPathGuard.root(project)
        val cwd = ProjectPathGuard.resolve(project, config.workingDirectory)
        require(Files.isDirectory(cwd)) { "MCP working directory does not exist: ${config.workingDirectory}" }
        val executable = resolveExecutable(config.command, cwd)
            ?: error("MCP executable was not found: ${config.command}")
        val plan = sandbox.prepare(
            ProcessSandboxRequest(
                mode = sandboxMode,
                workspaceRoot = workspace,
                cwd = cwd,
                requestedExecutable = config.command,
                executable = executable,
                arguments = config.arguments,
            ),
        )
        val fingerprint = mcpLaunchFingerprint(config, plan)
        val approvalRequest = McpLaunchApprovalRequest(
            serverName = config.name,
            command = config.command,
            arguments = config.arguments,
            workingDirectory = plan.cwd.toString(),
            sandboxMode = sandboxMode,
            environmentKeys = config.environmentKeys,
            executablePath = plan.executable.toString(),
            fingerprint = fingerprint,
        )
        val executionId = UUID.randomUUID().toString()
        val details = approvalRequest.details()
        val trusted = runCatching { trustStore.isTrusted(config.id, projectId, fingerprint) }
            .getOrDefault(false)
        if (trusted) {
            audit(executionId, config.name, details, McpLaunchAuditOutcome.PERSISTENT_TRUST_USED)
        } else {
            audit(executionId, config.name, details, McpLaunchAuditOutcome.APPROVAL_REQUESTED)
            val decision = try {
                approvalGate.approveMcpLaunch(approvalRequest)
            } catch (error: Throwable) {
                audit(
                    executionId,
                    config.name,
                    details,
                    McpLaunchAuditOutcome.FAILED,
                    error.message ?: error::class.java.simpleName,
                )
                throw error
            }
            when (decision) {
                McpLaunchApprovalDecision.ALLOW_ONCE -> {
                    audit(executionId, config.name, details, McpLaunchAuditOutcome.APPROVED_ONCE)
                }
                McpLaunchApprovalDecision.TRUST_CONFIGURATION -> {
                    try {
                        trustStore.trust(config.id, projectId, fingerprint)
                    } catch (error: Throwable) {
                        audit(
                            executionId,
                            config.name,
                            details,
                            McpLaunchAuditOutcome.FAILED,
                            "Could not persist MCP launch trust",
                        )
                        throw IllegalStateException("Could not persist MCP launch trust; the process was not started", error)
                    }
                    audit(executionId, config.name, details, McpLaunchAuditOutcome.TRUSTED_CONFIGURATION)
                }
                McpLaunchApprovalDecision.REJECT -> {
                    audit(executionId, config.name, details, McpLaunchAuditOutcome.REJECTED)
                    throw McpLaunchRejectedException(config.name)
                }
            }
        }
        val executionPlan = try {
            val revalidatedCwd = ProjectPathGuard.resolve(project, config.workingDirectory)
            val revalidatedExecutable = resolveExecutable(config.command, revalidatedCwd)
                ?: error("MCP executable was not found after approval: ${config.command}")
            sandbox.prepare(
                ProcessSandboxRequest(
                    mode = sandboxMode,
                    workspaceRoot = workspace,
                    cwd = revalidatedCwd,
                    requestedExecutable = config.command,
                    executable = revalidatedExecutable,
                    arguments = config.arguments,
                ),
            ).also { revalidated ->
                require(revalidated.executableIdentity == plan.executableIdentity) {
                    "MCP executable changed after approval; the process was not started"
                }
                require(mcpLaunchFingerprint(config, revalidated) == fingerprint) {
                    "MCP launch configuration changed after approval; the process was not started"
                }
            }
        } catch (error: Throwable) {
            audit(
                executionId,
                config.name,
                details,
                McpLaunchAuditOutcome.FAILED,
                error.message ?: error::class.java.simpleName,
            )
            throw error
        }
        val injectedSecrets = mutableListOf<String>()
        val builder = ProcessBuilder(executionPlan.launchArgv)
            .directory(executionPlan.cwd.toFile())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .also { builder ->
                val environment = builder.environment()
                environment.clear()
                SAFE_MCP_ENVIRONMENT_KEYS.forEach { key ->
                    System.getenv(key)?.let { environment[key] = it }
                }
                // A GUI-launched IDE may resolve npx/uvx from an allow-listed directory while
                // the child runtime (node/python) still cannot see that directory. Propagate the
                // bounded runtime path, including the resolved executable's parent.
                val runtimePath = linkedSetOf<String>().apply {
                    addAll(System.getenv("PATH").orEmpty().split(File.pathSeparatorChar))
                    executionPlan.launchArgv.firstOrNull()?.let { argv0 ->
                        runCatching { Path.of(argv0).toAbsolutePath().parent?.toString() }
                            .getOrNull()?.let(::add)
                    }
                    addAll(commonRuntimeDirectories())
                }.filter(String::isNotBlank).joinToString(File.pathSeparator)
                if (runtimePath.isNotBlank()) environment["PATH"] = runtimePath
                config.environmentKeys.forEach { key ->
                    val value = secretReader.load(config.id, key).ifBlank { System.getenv(key).orEmpty() }
                    value.takeIf(String::isNotBlank)?.let {
                        environment[key] = it
                        injectedSecrets += it
                    }
                }
                environment.putAll(executionPlan.environmentOverrides)
                environment["OMNICODE_SANDBOX_MODE"] = executionPlan.mode.name
            }
        try {
            sandbox.activate(executionPlan)
            val process = processStarter(builder)
            audit(executionId, config.name, details, McpLaunchAuditOutcome.STARTED)
            val redactionSecrets = injectedSecrets.flatMap { secret ->
                buildList {
                    add(secret)
                    secret.lineSequence().filter(String::isNotEmpty).forEach(::add)
                }
            }.distinct().sortedByDescending(String::length)
            val genericRedactor = DefaultSensitiveDataRedactor(redactionSecrets)
            McpLaunchedProcess(
                process = process,
                diagnosticRedactor = SensitiveDataRedactor { value ->
                    var safe = value
                    // Actual injected values are secrets regardless of length; short values are
                    // intentionally over-redacted instead of relying on the generic 4-char floor.
                    redactionSecrets.forEach { secret -> safe = safe.replace(secret, "[REDACTED]") }
                    genericRedactor.redact(safe)
                },
            )
        } catch (error: Throwable) {
            audit(
                executionId,
                config.name,
                details,
                McpLaunchAuditOutcome.FAILED,
                error.message ?: error::class.java.simpleName,
            )
            throw error
        }
    }

    private fun audit(
        executionId: String,
        serverName: String,
        details: String,
        outcome: McpLaunchAuditOutcome,
        errorMessage: String? = null,
    ) {
        runCatching {
            auditSink.record(
                McpLaunchAuditEvent(
                    executionId = executionId,
                    projectId = projectId,
                    serverName = serverName,
                    details = details,
                    outcome = outcome,
                    errorMessage = errorMessage,
                ),
            )
        }
    }

    private fun resolveExecutable(value: String, cwd: Path): Path? {
        val requested = Path.of(value)
        if (requested.isAbsolute || value.contains('/') || value.contains('\\')) {
            val candidate = if (requested.isAbsolute) requested else cwd.resolve(requested)
            return candidate.normalize().takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }?.toRealPath()
        }
        val searchDirectories = linkedSetOf<String>().apply {
            addAll(System.getenv("PATH").orEmpty().split(File.pathSeparatorChar))
            addAll(commonRuntimeDirectories())
        }
        return searchDirectories.asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it).resolve(value) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.toRealPath()
    }

    private fun commonRuntimeDirectories(): Set<String> {
        val directories = linkedSetOf<String>()
        val home = System.getProperty("user.home").orEmpty()
        if (home.isNotBlank()) directories.addAll(listOf(
            "$home/.local/bin", "$home/.npm-global/bin", "$home/.npm/bin", "$home/bin",
        ))
        directories.addAll(listOf("/usr/local/bin", "/opt/homebrew/bin", "/opt/local/bin"))
        System.getenv("APPDATA")?.takeIf(String::isNotBlank)?.let { directories.add("$it/npm") }
        System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let { directories.add("$it/npm") }
        return directories
    }

    private companion object {
        val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val SAFE_MCP_ENVIRONMENT_KEYS = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL", "TERM",
        )
    }
}

internal fun mcpLaunchFingerprint(config: McpServerConfig, plan: ProcessSandboxPlan): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digestField(digest, "version", "2")
    digestField(digest, "server-id", config.id)
    digestField(digest, "server-name", config.name)
    digestField(digest, "command", config.command)
    config.arguments.forEach { digestField(digest, "argument", it) }
    digestField(digest, "cwd", plan.cwd.toString())
    digestField(digest, "workspace", plan.workspaceRoot.toString())
    digestField(digest, "sandbox", plan.mode.name)
    digestField(digest, "sandbox-enforcement", plan.capability.enforcement.name)
    plan.sandboxExecutableIdentity?.let { identity ->
        digestField(digest, "sandbox-executable-path", identity.realPath.toString())
        digestField(digest, "sandbox-executable-file-key", identity.fileKey.orEmpty())
        digestField(digest, "sandbox-executable-size", identity.size.toString())
        digestField(digest, "sandbox-executable-modified", identity.lastModifiedMillis.toString())
    }
    config.environmentKeys.sorted().forEach { digestField(digest, "environment-key", it) }
    digestField(digest, "executable-path", plan.executable.toString())
    digestField(digest, "executable-sha256", executableSha256(plan.executable))
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun digestField(digest: MessageDigest, name: String, value: String) {
    val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
    val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(nameBytes.size).array())
    digest.update(nameBytes)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(valueBytes.size).array())
    digest.update(valueBytes)
}

private fun executableSha256(executable: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(executable).use { input ->
        val buffer = ByteArray(DEFAULT_DIGEST_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val DEFAULT_DIGEST_BUFFER_SIZE = 64 * 1024
