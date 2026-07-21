package dev.omnicode.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.tool.ApprovalGate
import dev.omnicode.tool.ToolExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpStdioClientTest {
    @Test
    fun `stdio skips null and non-object tool and content entries`() = runBlocking {
        val process = FakeMcpProcess { request ->
            when (request.method()) {
                "initialize" -> emptyMcpResult(request)
                "notifications/initialized" -> null
                "tools/list" -> mcpSuccess(request, JsonObject().apply {
                    add("tools", JsonArray().apply {
                        add(JsonNull.INSTANCE)
                        add("not-an-object")
                        add(toolDescriptor("echo", "Echo", withSchema = true))
                    })
                })
                "tools/call" -> mcpSuccess(request, JsonObject().apply {
                    add("content", JsonArray().apply {
                        add(JsonNull.INSTANCE)
                        add(42)
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "valid content")
                        })
                    })
                })
                else -> mcpError(request, -32601, "unknown method")
            }
        }
        val client = McpStdioClient.connect(
            config(),
            McpProcessLauncher { process },
            McpTimeouts(requestMs = 2_000, toolCallMs = 2_000),
        )

        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            assertEquals("valid content", client.callTool("echo", JsonObject()).text)
            assertTrue(process.isAlive)
        } finally {
            client.close()
        }
        process.assertServerHealthy()
    }

    @Test
    fun `stdio client initializes pages tools and calls a tool with one JSON object per line`() = runBlocking {
        val process = FakeMcpProcess { request ->
            when (request.method()) {
                "initialize" -> mcpSuccess(request, JsonObject().apply {
                    addProperty("protocolVersion", "2025-11-25")
                    add("capabilities", JsonObject())
                })
                "notifications/initialized" -> null
                "tools/list" -> {
                    if (request.getAsJsonObject("params").get("cursor") == null) {
                        mcpSuccess(request, JsonObject().apply {
                            add("tools", JsonArray().apply {
                                add(toolDescriptor("echo", "Echo a value", withSchema = true))
                            })
                            addProperty("nextCursor", "page-2")
                        })
                    } else {
                        mcpSuccess(request, JsonObject().apply {
                            add("tools", JsonArray().apply {
                                add(toolDescriptor("status", "Read status", withSchema = false))
                            })
                        })
                    }
                }
                "tools/call" -> {
                    val value = request.getAsJsonObject("params")
                        .getAsJsonObject("arguments")
                        .get("value")
                        .asString
                    mcpSuccess(request, JsonObject().apply {
                        add("content", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", "echo:$value")
                            })
                        })
                        add("structuredContent", JsonObject().apply { addProperty("echoed", value) })
                        addProperty("isError", false)
                    })
                }
                else -> mcpError(request, -32601, "unknown method")
            }
        }
        val client = McpStdioClient.connect(
            config(),
            McpProcessLauncher { process },
            McpTimeouts(requestMs = 2_000, toolCallMs = 2_000),
        )

        try {
            val tools = client.listTools()
            val result = client.callTool("echo", JsonObject().apply { addProperty("value", "hello") })

            assertEquals(listOf("echo", "status"), tools.map { it.name })
            assertEquals("object", tools.last().inputSchema.get("type").asString)
            assertTrue(result.text.contains("echo:hello"))
            assertTrue(result.text.contains("\"echoed\":\"hello\""))
            assertFalse(result.isError)

            assertEquals(
                listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call"),
                process.requests.map { it.method() },
            )
            assertEquals(listOf(1L, 2L, 3L, 4L), process.requests.filter { it.has("id") }.map { it.get("id").asLong })
            assertFalse(process.requests[1].has("id"))
            assertEquals("page-2", process.requests[3].getAsJsonObject("params").get("cursor").asString)
            assertTrue(process.rawRequestLines.all { line ->
                !line.contains('\n') && runCatching { JsonParser.parseString(line).isJsonObject }.getOrDefault(false)
            })
        } finally {
            client.close()
        }

        assertFalse(process.isAlive)
        process.assertServerHealthy()
    }

    @Test
    fun `JSON RPC errors surface and do not corrupt a still usable connection`() = runBlocking {
        val process = FakeMcpProcess { request ->
            when (request.method()) {
                "initialize" -> emptyMcpResult(request)
                "notifications/initialized" -> null
                "tools/list" -> mcpError(request, -32601, "listing disabled")
                "tools/call" -> mcpSuccess(request, JsonObject().apply {
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "tool failed safely")
                        })
                    })
                    addProperty("isError", true)
                })
                else -> mcpError(request, -32601, "unknown method")
            }
        }
        val client = McpStdioClient.connect(
            config(),
            McpProcessLauncher { process },
            McpTimeouts(requestMs = 2_000, toolCallMs = 2_000),
        )

        try {
            val error = expectProtocolFailure { client.listTools() }
            assertTrue(error.message.orEmpty().contains("tools/list failed (-32601): listing disabled"))

            val call = client.callTool("still-available", JsonObject())
            assertTrue(call.isError)
            assertEquals("tool failed safely", call.text)
            assertTrue(process.isAlive)
        } finally {
            client.close()
        }

        process.assertServerHealthy()
    }

    @Test
    fun `initialize failure closes the launched process`() = runBlocking {
        val process = FakeMcpProcess { request ->
            mcpError(request, -32000, "not ready")
        }

        val error = expectProtocolFailure {
            McpStdioClient.connect(
                config(),
                McpProcessLauncher { process },
                McpTimeouts(requestMs = 2_000, toolCallMs = 2_000),
            )
        }

        assertTrue(error.message.orEmpty().contains("initialize failed (-32000): not ready"))
        assertFalse(process.isAlive)
        process.assertServerHealthy()
    }

    @Test
    fun `request timeout returns promptly and closes the transport`() = runBlocking {
        val process = FakeMcpProcess { request ->
            when (request.method()) {
                "initialize" -> emptyMcpResult(request)
                "notifications/initialized" -> null
                "tools/list" -> null
                else -> mcpError(request, -32601, "unknown method")
            }
        }
        val client = McpStdioClient.connect(
            config(),
            McpProcessLauncher { process },
            McpTimeouts(requestMs = 100, toolCallMs = 100),
        )
        val started = System.nanoTime()

        val error = expectProtocolFailure { client.listTools() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(error.message.orEmpty().contains("tools/list timed out after 100 ms"))
        assertTrue(elapsedMillis < 2_000, "Timeout took ${elapsedMillis}ms")
        assertFalse(process.isAlive)
        process.assertServerHealthy()
    }

    @Test
    fun `unsolicited response flood fails closed at the bounded queue`() = runBlocking {
        lateinit var process: FakeMcpProcess
        process = FakeMcpProcess { request ->
            when (request.method()) {
                "initialize" -> emptyMcpResult(request)
                "notifications/initialized" -> {
                    repeat(80) { index ->
                        process.sendServerMessage(JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            addProperty("method", "notifications/progress")
                            add("params", JsonObject().apply { addProperty("index", index) })
                        })
                    }
                    null
                }
                else -> mcpError(request, -32601, "unknown method")
            }
        }
        val client = McpStdioClient.connect(
            config(),
            McpProcessLauncher { process },
            McpTimeouts(requestMs = 2_000, toolCallMs = 2_000),
        )

        awaitStopped(process)
        val error = expectProtocolFailure { client.listTools() }

        assertTrue(error.message.orEmpty().contains("exceeded the 64-message response queue"))
        process.assertServerHealthy()
    }

    @Test
    fun `oversized stdout protocol line fails closed without unbounded readLine`() = runBlocking {
        lateinit var process: FakeMcpProcess
        process = FakeMcpProcess { request ->
            when (request.method()) {
                "initialize" -> emptyMcpResult(request)
                "notifications/initialized" -> {
                    process.sendServerRawLine("x".repeat(1_048_577))
                    null
                }
                else -> mcpError(request, -32601, "unknown method")
            }
        }
        val client = McpStdioClient.connect(
            config(),
            McpProcessLauncher { process },
            McpTimeouts(requestMs = 2_000, toolCallMs = 2_000),
        )

        awaitStopped(process)
        val error = expectProtocolFailure { client.listTools() }

        assertTrue(error.message.orEmpty().contains("1048576-character protocol line limit"))
        process.assertServerHealthy()
    }

    @Test
    fun `connector executes approved tools and bundle close terminates the server`() = runBlocking {
        val process = FakeMcpProcess(listingResponder(listOf("echo")))
        val bundle = McpToolConnector(McpProcessLauncher { process }).connect(listOf(config()))

        try {
            assertTrue(bundle.errors.isEmpty())
            val tool = bundle.tools.single()
            assertEquals("mcp__test_server__echo", tool.name)
            assertTrue(tool.dangerous)

            val result = tool.execute(
                JsonObject().apply { addProperty("value", "from connector") },
                ToolExecutionContext(fakeProject(), ApprovalGate { true }, AgentMode.AGENT),
            )

            assertFalse(result.isError)
            assertEquals("called:echo", result.content)
        } finally {
            bundle.close()
        }

        assertFalse(process.isAlive)
        process.assertServerHealthy()
    }

    @Test
    fun `connector reports normalized tool name collisions within and across servers`() = runBlocking {
        val firstConfig = config(id = "first", name = "Server A")
        val secondConfig = config(id = "second", name = "server_a")
        val first = FakeMcpProcess(listingResponder(listOf("read file", "read_file")))
        val second = FakeMcpProcess(listingResponder(listOf("read_file")))
        val processes = mapOf("first" to first, "second" to second)
        val bundle = McpToolConnector(McpProcessLauncher { server -> processes.getValue(server.id) })
            .connect(listOf(firstConfig, secondConfig))

        try {
            assertEquals(listOf("mcp__server_a__read_file"), bundle.tools.map { it.name })
            assertEquals(2, bundle.errors.size)
            assertTrue(bundle.errors.all { it.message.contains("name collision after normalization") })
            assertEquals(listOf("Server A", "server_a"), bundle.errors.map { it.serverName })
        } finally {
            bundle.close()
        }

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        first.assertServerHealthy()
        second.assertServerHealthy()
    }

    @Test
    fun `connector exposes launch rejection as a readable server error`() = runBlocking {
        val bundle = McpToolConnector(McpProcessLauncher { server ->
            throw McpLaunchRejectedException(server.name)
        }).connect(listOf(config()))

        assertTrue(bundle.tools.isEmpty())
        assertEquals(1, bundle.errors.size)
        assertEquals("Test Server", bundle.errors.single().serverName)
        assertTrue(bundle.errors.single().message.contains("launch approval was rejected"))
    }

    @Test
    fun `connector does not swallow cancellation while awaiting launch approval`() = runBlocking {
        var propagated = false
        try {
            McpToolConnector(McpProcessLauncher { throw CancellationException("cancel approval") })
                .connect(listOf(config()))
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
    }

    private fun listingResponder(names: List<String>): (JsonObject) -> JsonObject? = { request ->
        when (request.method()) {
            "initialize" -> emptyMcpResult(request)
            "notifications/initialized" -> null
            "tools/list" -> mcpSuccess(request, JsonObject().apply {
                add("tools", JsonArray().apply {
                    names.forEach { name -> add(toolDescriptor(name, "Test tool", withSchema = true)) }
                })
            })
            "tools/call" -> mcpSuccess(request, JsonObject().apply {
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "called:${request.getAsJsonObject("params").get("name").asString}")
                    })
                })
            })
            else -> mcpError(request, -32601, "unknown method")
        }
    }

    private fun toolDescriptor(name: String, description: String, withSchema: Boolean): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        if (withSchema) {
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            })
        }
    }

    private suspend fun expectProtocolFailure(block: suspend () -> Unit): McpProtocolException {
        try {
            block()
        } catch (error: McpProtocolException) {
            return error
        }
        throw AssertionError("Expected McpProtocolException")
    }

    private suspend fun awaitStopped(process: FakeMcpProcess) {
        repeat(200) {
            if (!process.isAlive) return
            delay(10)
        }
        assertFalse(process.isAlive, "MCP transport did not fail closed")
    }

    private fun config(
        id: String = "test-server",
        name: String = "Test Server",
    ): McpServerConfig = McpServerConfig(
        id = id,
        name = name,
        enabled = true,
        command = "fake-mcp-server",
        arguments = emptyList(),
        environmentKeys = emptySet(),
        workingDirectory = ".",
    )

    private fun JsonObject.method(): String = get("method")?.asString.orEmpty()

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "isDisposed" -> false
            "getName" -> "MCP test project"
            "toString" -> "McpTestProject"
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
}
