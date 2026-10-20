package dev.omnicode.provider

import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.StopReason
import dev.omnicode.model.ToolDefinition
import dev.omnicode.settings.SandboxMode
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.util.Json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexDynamicToolProtocolTest {
    @Test
    fun `fake app server registers a tool and hands off a callback without running it`() = runBlocking {
        val source = requireNotNull(javaClass.getResource("/codex/FakeCodexDynamicToolServer.java"))
        val java = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val workspace = Files.createTempDirectory("omnicode-codex-protocol-")
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) {
            _, method, _ -> error("Unexpected Project access in protocol fixture: ${method.name}")
        } as Project
        try {
            val preset = ProviderPresets.byId("codex-native")
            val response = withTimeout(20_000) {
                CodexNativeSession(
                    ProviderConnection(preset, preset.defaultBaseUrl, preset.defaultModel, ""),
                    CodexNativeExecutionContext(project, workspace, ApprovalGate { false },
                        AgentMode.AGENT, SandboxMode.WORKSPACE_WRITE),
                    launchCandidates = listOf(listOf(java.toString(), Path.of(source.toURI()).toString())),
                ).run(
                    ModelRequest(
                        messages = listOf(ConversationMessage(MessageRole.USER, "Request the safe probe.")),
                        tools = listOf(ToolDefinition("mcp__probe__ping", "Read-only protocol probe",
                            Json.parseObject("""{"type":"object","properties":{"nonce":{"type":"string"}}}"""))),
                        maxOutputTokens = 64,
                    ),
                    onTextDelta = {},
                )
            }
            assertEquals(StopReason.TOOL_USE, response.stopReason)
            assertEquals(1, response.toolCalls.size)
            assertEquals("call-1", response.toolCalls.single().id)
            assertEquals("mcp__probe__ping", response.toolCalls.single().name)
            assertEquals("safe", response.toolCalls.single().arguments.get("nonce").asString)
            assertTrue(response.text.isEmpty())
        } finally {
            Files.deleteIfExists(workspace)
        }
    }
}
