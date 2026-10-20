package dev.omnicode.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.mcp.McpStreamableHttpClient
import dev.omnicode.model.ToolDefinition
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import dev.omnicode.tool.QueryDatabaseTool
import dev.omnicode.tool.RunCommandTool
import dev.omnicode.util.Json
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeMcpToolBridgeTest {
    private val databaseDefinition = QueryDatabaseTool().definition()

    @Test
    fun `native OpenCode bridge lists all registered Kubernetes tools`() = runBlocking {
        val definitions = (1..14).map { index ->
            ToolDefinition("mcp__kubernetes__tool_$index", "Kubernetes tool $index", JsonObject().apply {
                addProperty("type", "object")
            })
        }
        OpenCodeMcpToolBridge.start(definitions).use { bridge ->
            val client = McpStreamableHttpClient.connect(McpServerConfig(
                id = "kubernetes-bridge-test", name = "Kubernetes bridge test", enabled = true,
                command = "", arguments = emptyList(), environmentKeys = emptySet(),
                workingDirectory = ".", transport = McpTransport.HTTP,
                url = bridge.endpoint.toASCIIString(), httpAuthMode = McpHttpAuthMode.NONE,
            ))
            try {
                assertEquals(definitions.map { it.name }, client.listTools().map { it.name })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `native MCP lists and captures an approved host tool request`() = runBlocking {
        OpenCodeMcpToolBridge.start(listOf(databaseDefinition)).use { bridge ->
            val client = McpStreamableHttpClient.connect(
                McpServerConfig(
                    id = "opencode-test",
                    name = "OpenCode bridge test",
                    enabled = true,
                    command = "",
                    arguments = emptyList(),
                    environmentKeys = emptySet(),
                    workingDirectory = ".",
                    transport = McpTransport.HTTP,
                    url = bridge.endpoint.toASCIIString(),
                    httpAuthMode = McpHttpAuthMode.NONE,
                ),
            )
            try {
                val tools = client.listTools()
                assertEquals(listOf("query_database"), tools.map { it.name })

                val result = client.callTool(
                    "query_database",
                    JsonObject().apply { addProperty("sql", "SELECT 1") },
                )

                assertTrue(!result.isError)
                assertTrue(result.text.contains("IDE 审批"))
                val call = assertNotNull(bridge.capturedToolCall())
                assertEquals("query_database", call.name)
                assertEquals("SELECT 1", call.arguments.get("sql").asString)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `inline config registers only the random loopback bridge and preserves existing config`() {
        OpenCodeMcpToolBridge.start(listOf(databaseDefinition)).use { bridge ->
            val environment = mutableMapOf(
                "OPENCODE_CONFIG_CONTENT" to "{\"autoupdate\":false,\"mcp\":{\"existing\":{\"enabled\":false}}}",
                "NO_PROXY" to "localhost",
            )

            bridge.applyToEnvironment(environment)

            val root = Json.parseObject(environment.getValue("OPENCODE_CONFIG_CONTENT"))
            assertTrue(!root.get("autoupdate").asBoolean)
            val mcp = root.getAsJsonObject("mcp")
            assertTrue(mcp.has("existing"))
            val injected = mcp.entrySet().single { it.key.startsWith("omnicode_host_") }
            assertEquals("remote", injected.value.asJsonObject.get("type").asString)
            assertTrue(injected.value.asJsonObject.get("url").asString.startsWith("http://127.0.0.1:"))
            assertTrue(environment.getValue("NO_PROXY").contains("127.0.0.1"))
        }
    }

    @Test
    fun `invalid native MCP arguments never become a host tool call`() = runBlocking {
        OpenCodeMcpToolBridge.start(listOf(databaseDefinition)).use { bridge ->
            val client = McpStreamableHttpClient.connect(
                McpServerConfig(
                    id = "opencode-invalid-test",
                    name = "OpenCode invalid bridge test",
                    enabled = true,
                    command = "",
                    arguments = emptyList(),
                    environmentKeys = emptySet(),
                    workingDirectory = ".",
                    transport = McpTransport.HTTP,
                    url = bridge.endpoint.toASCIIString(),
                    httpAuthMode = McpHttpAuthMode.NONE,
                ),
            )
            try {
                val result = client.callTool(
                    "query_database",
                    JsonObject().apply { addProperty("sql", 42) },
                )

                assertTrue(result.isError)
                assertTrue(!bridge.hasCapturedToolCall())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `native MCP provides the event stream OpenCode requires before listing tools`() {
        OpenCodeMcpToolBridge.start(listOf(databaseDefinition)).use { bridge ->
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(bridge.endpoint)
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            response.body().bufferedReader().use { reader ->
                assertEquals(200, response.statusCode())
                assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
                assertEquals(": omnicode connected", reader.readLine())
            }
        }
    }

    @Test
    fun `native MCP captures a command for IDE approval without running it in the bridge`() = runBlocking {
        val commandDefinition = RunCommandTool().definition()
        OpenCodeMcpToolBridge.start(listOf(commandDefinition)).use { bridge ->
            val client = McpStreamableHttpClient.connect(
                McpServerConfig(
                    id = "opencode-command-test",
                    name = "OpenCode command bridge test",
                    enabled = true,
                    command = "",
                    arguments = emptyList(),
                    environmentKeys = emptySet(),
                    workingDirectory = ".",
                    transport = McpTransport.HTTP,
                    url = bridge.endpoint.toASCIIString(),
                    httpAuthMode = McpHttpAuthMode.NONE,
                ),
            )
            try {
                val result = client.callTool(
                    "run_command",
                    JsonObject().apply {
                        add("argv", JsonArray().apply {
                            add("git")
                            add("status")
                        })
                    },
                )

                assertTrue(!result.isError)
                val call = assertNotNull(bridge.capturedToolCall())
                assertEquals("run_command", call.name)
                assertEquals("git", call.arguments.getAsJsonArray("argv")[0].asString)
                assertEquals("status", call.arguments.getAsJsonArray("argv")[1].asString)
            } finally {
                client.close()
            }
        }
    }
}
