package dev.omnicode.tool

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.*
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.provider.ModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteKubernetesRecoveryTest {
    private fun arguments(action: String = "connection") = JsonObject().apply {
        addProperty("host", "172.16.83.102")
        addProperty("user", "root")
        addProperty("action", action)
    }

    @Test fun `remote shell and SSH configuration injection never reach the executor`() {
        val command = commandTool { _, _ -> error("Must not execute") }
        val tool = InspectRemoteKubernetesTool(command)
        val attacks = listOf(
            "host" to "-oProxyCommand=touch /tmp/pwn", "host" to "host;id",
            "host" to "user@host", "host" to "host\nother", "user" to "root;id",
            "namespace" to "default;id", "selector" to "app=x$(id)",
            "selector" to "app=x --field-selector=metadata.name=y",
            "action" to "exec", "command" to "kubectl delete pod x", "identity_file" to "/tmp/key",
        )
        for ((key, value) in attacks) {
            val args = arguments("pods").apply { addProperty("namespace", "default"); addProperty(key, value) }
            assertFailsWith<IllegalArgumentException>("$key=$value") { tool.command(args) }
        }
        for (value in listOf(-1, 0, 65536)) {
            assertFailsWith<IllegalArgumentException> { tool.command(arguments().apply { addProperty("port", value) }) }
        }
        assertFailsWith<IllegalArgumentException> {
            tool.command(arguments("logs").apply {
                addProperty("namespace", "default"); addProperty("pod", "$(id)")
            })
        }
    }

    @Test fun `log request has fixed bounded arguments and disables SSH execution hooks`() {
        val tool = InspectRemoteKubernetesTool(commandTool { _, _ -> error("Must not execute") })
        val argv = tool.command(arguments("logs").apply {
            addProperty("namespace", "advertising-api")
            addProperty("pod", "advertising-api-service-abc")
            addProperty("container", "api")
        })
        assertEquals(listOf("ssh", "-F", "none", "-T", "-n"), argv.take(5))
        for (setting in listOf("BatchMode=yes", "StrictHostKeyChecking=yes", "UpdateHostKeys=no",
            "ForwardAgent=no", "ClearAllForwardings=yes", "PermitLocalCommand=no", "ProxyCommand=none")) {
            assertTrue(setting in argv, setting)
        }
        assertEquals("'kubectl' '--request-timeout=20s' '-n' 'advertising-api' 'logs' 'advertising-api-service-abc' " +
            "'--since=30m' '--tail=500' '--limit-bytes=24000' '--timestamps=true' '--prefix=true' '-c' 'api'", argv.last())
    }

    @Test fun `authorized remote read executes during each recovery scope and preserves prior mutation evidence`() = runBlocking {
        for (scope in listOf("resumed", "project", "harness")) {
            val checkpoints = mutableListOf<AgentExecutionCheckpoint>()
            val old = AgentPendingTool("old", "apply_patch", "{}", true, true, "old-execution")
            var executions = 0
            var approvals = 0
            val registry = ToolRegistry(runCommandTool = commandTool { args, context ->
                assertTrue(context.remoteReadInspection)
                assertEquals("ssh", args.getAsJsonArray("argv").first().asString)
                assertTrue(context.approvalGate.approve(ApprovalRequest("run_command", "SSH read", "fixed argv", "network read")))
                if (scope == "resumed") assertEquals(old, checkpoints.last().pendingTool)
                executions++
                ToolExecutionResult("OMNICODE_SSH_CONNECTED\nAuthorization: Bearer secret123456\nCookie: session=private")
            })
            val result = AgentEngine(
                project = project(), provider = provider(), tools = registry,
                approvalGate = ApprovalGate { approvals++; true }, limits = AgentLimits(maxIterations = 3),
                initialPendingTool = old.takeIf { scope == "resumed" },
                projectSideEffectGuard = old.takeIf { scope == "project" },
                checkpoints = AgentCheckpointSink { checkpoints += it },
            ).run("Connect and read logs", safeOnlyToolSurface = scope == "harness")
            assertEquals(1, executions, scope)
            assertEquals(1, approvals, scope)
            val output = result.messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>().single().content
            assertTrue(output.contains("OMNICODE_SSH_CONNECTED"), scope)
            assertFalse(output.contains("secret123456"), scope)
            assertFalse(output.contains("session=private"), scope)
            if (scope == "resumed") assertTrue(checkpoints.all { it.pendingTool == old }, scope)
        }
    }

    @Test fun `denial exception and timeout of a read never replace the unresolved write checkpoint`() = runBlocking {
        for (outcome in listOf("denied", "exception", "timeout")) {
            val old = AgentPendingTool("old", "apply_patch", "{}", true, true, "old-execution")
            val checkpoints = mutableListOf<AgentExecutionCheckpoint>()
            var executed = false
            val registry = ToolRegistry(runCommandTool = commandTool { _, context ->
                if (!context.approvalGate.approve(ApprovalRequest("run_command", "SSH read", "fixed argv", "network read"))) {
                    return@commandTool ToolExecutionResult("REJECTED_BY_USER", true)
                }
                executed = true
                if (outcome == "exception") error("SSH connection failed")
                // The fixed query reports its process deadline as a failed read result.
                ToolExecutionResult("Timed out", true, sideEffectStateUnknown = true)
            })
            AgentEngine(
                project = project(), provider = provider(), tools = registry,
                approvalGate = ApprovalGate { outcome != "denied" }, limits = AgentLimits(maxIterations = 3),
                initialPendingTool = old, checkpoints = AgentCheckpointSink { checkpoints += it },
            ).run("Read remote logs")
            assertEquals(outcome != "denied", executed)
            assertTrue(checkpoints.all { it.pendingTool == old }, outcome)
        }
    }

    @Test fun `remote tool cannot run directly in restricted modes`() = runBlocking {
        val tool = InspectRemoteKubernetesTool(commandTool { _, _ -> error("Must not execute") })
        for (mode in AgentMode.entries.filter { it != AgentMode.AGENT }) {
            assertFailsWith<IllegalArgumentException> {
                tool.execute(arguments(), ToolExecutionContext(project(), ApprovalGate { error("Must not approve") }, mode))
            }
        }
    }

    @Test fun `cancelling an in flight inspection preserves the historical write at every boundary`() = runBlocking {
        val old = AgentPendingTool("old", "apply_patch", "{}", true, true, "old-execution")
        val checkpoints = mutableListOf<AgentExecutionCheckpoint>()
        val entered = CompletableDeferred<Unit>()
        val registry = ToolRegistry(runCommandTool = commandTool { _, context ->
            check(context.approvalGate.approve(ApprovalRequest("run_command", "SSH read", "fixed argv", "network read")))
            entered.complete(Unit)
            delay(Long.MAX_VALUE)
            ToolExecutionResult("unreachable")
        })
        val job = launch {
            AgentEngine(project = project(), provider = provider(), tools = registry,
                approvalGate = ApprovalGate { true }, initialPendingTool = old,
                checkpoints = AgentCheckpointSink { checkpoints += it },
            ).run("Read remote logs")
        }
        withTimeout(2000) { entered.await() }
        job.cancelAndJoin()
        assertTrue(checkpoints.all { it.pendingTool == old })
    }

    private fun provider() = object : ModelProvider {
        override val id = "remote-recovery"
        private var calls = 0
        override suspend fun complete(request: ModelRequest, onTextDelta: suspend (String) -> Unit): ModelResponse {
            assertTrue(request.tools.any { it.name == "inspect_remote_kubernetes" })
            assertFalse(request.tools.any { it.name == "run_command" })
            return if (calls++ == 0) ModelResponse(
                listOf(ContentBlock.ToolCall("read", "inspect_remote_kubernetes", arguments())), stopReason = StopReason.TOOL_USE,
            ) else ModelResponse(listOf(ContentBlock.Text("Reported actual connection result")), stopReason = StopReason.COMPLETE)
        }
    }

    private fun commandTool(execute: suspend (JsonObject, ToolExecutionContext) -> ToolExecutionResult) = object : AgentTool {
        override val name = "run_command"
        override val description = "Command fixture"
        override val dangerous = true
        override val effect = ToolEffect.COMMAND
        override val inputSchema = JsonObject()
        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext) = execute.invoke(arguments, context)
    }

    private fun project(): Project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
        when (method.name) {
            "getName" -> "remote-test"
            "isDisposed" -> false
            else -> null
        }
    } as Project
}
