package dev.omnicode.provider

import com.google.gson.JsonObject
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.ToolDefinition
import dev.omnicode.tool.ApplyChangeTool
import dev.omnicode.tool.ApplyPatchTool
import dev.omnicode.tool.QueryDatabaseTool
import dev.omnicode.tool.RunCommandTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliToolBridgeTest {
    private val databaseDefinition = QueryDatabaseTool().definition()

    @Test
    fun `registered remote inspection is bridged to native and CLI engines during recovery`() {
        val definition = dev.omnicode.tool.ToolRegistry()
            .definitionsFor(dev.omnicode.agent.AgentMode.AGENT, safeOnly = true)
            .single { it.name == "inspect_remote_kubernetes" }
        assertTrue(cliBridgeToolName(definition.name))
        assertTrue(nativeHostBridgeToolName(definition.name))
        assertEquals(listOf(definition), cliBridgeTools(request(definition)))
        assertEquals(listOf(definition), nativeHostBridgeTools(request(definition)))
    }

    @Test
    fun `database tool is described to a headless cli without credentials`() {
        val instructions = cliBridgeInstructions(request())

        assertTrue(instructions.contains("query_database"))
        assertTrue(instructions.contains("SELECT 1"))
        assertTrue(instructions.contains("不要声称这些工具不可用"))
        assertTrue(!instructions.contains("password", ignoreCase = true))
    }

    @Test
    fun `agent cli bridge includes host-owned changes and commands`() {
        val tools = cliBridgeTools(
            request(
                databaseDefinition,
                ApplyPatchTool().definition(),
                ApplyChangeTool().definition(),
                RunCommandTool().definition(),
            ),
        )

        assertEquals(
            listOf("query_database", "apply_patch", "apply_change", "run_command"),
            tools.map { it.name },
        )
        val instructions = cliBridgeInstructions(request(*tools.toTypedArray()))
        assertTrue(instructions.contains("apply_patch"))
        assertTrue(instructions.contains("apply_change"))
        assertTrue(instructions.contains("run_command"))
        assertTrue(instructions.contains("审批、沙箱和审计"))
    }

    @Test
    fun `OpenCode bridge includes all fourteen registered Kubernetes tools`() {
        val kubernetes = (1..14).map { index ->
            ToolDefinition("mcp__kubernetes__tool_$index", "Kubernetes tool $index", JsonObject().apply {
                addProperty("type", "object")
            })
        }
        val tools = cliBridgeTools(request(databaseDefinition, *kubernetes.toTypedArray()))

        assertEquals(15, tools.size)
        assertEquals("mcp__kubernetes__tool_14", tools.last().name)
        assertTrue(cliBridgeToolName("mcp__kubernetes__pods_list"))
        assertTrue(!cliBridgeToolName("mcp__bad.name__pods_list"))
        assertTrue(!cliBridgeToolName("arbitrary_tool"))
    }

    @Test
    fun `native host bridge exposes only registered database and MCP tools`() {
        val mcpDefinition = ToolDefinition(
            "mcp__demo__list_tables", "remote supplied description",
            JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("schema", JsonObject().apply { addProperty("type", "string") })
                })
            },
        )
        val allowed = nativeHostBridgeTools(request(
            RunCommandTool().definition(), databaseDefinition, mcpDefinition,
        ))
        assertEquals(listOf("query_database", "mcp__demo__list_tables"), allowed.map { it.name })
        val prompt = nativeHostBridgePrompt("用户问题", allowed, 2_000)
        assertTrue(prompt.contains("<<<OMNICODE_TOOL_CALL>>>"))
        assertTrue(prompt.contains("mcp__demo__list_tables"))
        assertTrue(prompt.contains("query_database"))
        assertTrue(!prompt.contains("run_command"))
        assertTrue(!prompt.contains("remote supplied description"))

        val response = ModelResponse(blocks = listOf(ContentBlock.Text(
            "<<<OMNICODE_TOOL_CALL>>>\n" +
                "{\"name\":\"mcp__demo__list_tables\",\"arguments\":{\"schema\":\"public\"}}\n" +
                "<<<END_OMNICODE_TOOL_CALL>>>")),
        )
        assertEquals("mcp__demo__list_tables", assertIs<ContentBlock.ToolCall>(
            parseCliBridgeResponse(response, allowed).blocks.single(),
        ).name)
        assertTrue(parseCliBridgeResponse(response, listOf(databaseDefinition)).blocks.none { it is ContentBlock.ToolCall })

        val noArgsTool = ToolDefinition("mcp__demo__ping", "ping", JsonObject().apply {
            addProperty("type", "object")
        })
        val noArgsResponse = ModelResponse(blocks = listOf(ContentBlock.Text(
            "<<<OMNICODE_TOOL_CALL>>>{\"name\":\"mcp__demo__ping\",\"arguments\":{}}<<<END_OMNICODE_TOOL_CALL>>>",
        )))
        assertIs<ContentBlock.ToolCall>(parseCliBridgeResponse(noArgsResponse, listOf(noArgsTool)).blocks.single())
    }

    @Test
    fun `native host bridge retains full Kubernetes tool catalog`() {
        val kubernetes = (1..14).map { index ->
            ToolDefinition("mcp__kubernetes__tool_$index", "Kubernetes tool $index", JsonObject().apply {
                addProperty("type", "object")
            })
        }
        val allowed = nativeHostBridgeTools(request(databaseDefinition, *kubernetes.toTypedArray()))
        assertEquals(15, allowed.size)
        assertEquals(kubernetes.map { it.name }, allowed.drop(1).map { it.name })
    }

    @Test
    fun `named Kubernetes server is not hidden behind earlier MCP catalogs`() {
        val unrelated = (1..60).map { index ->
            ToolDefinition("mcp__github__tool_$index", "Unrelated $index", JsonObject())
        }
        val kubernetes = (1..14).map { index ->
            ToolDefinition("mcp__kubernetes__tool_$index", "Kubernetes $index", JsonObject())
        }
        val request = ModelRequest(
            messages = listOf(ConversationMessage(MessageRole.USER, "请查看 Kubernetes 集群的 pods")),
            tools = listOf(databaseDefinition) + unrelated + kubernetes,
            maxOutputTokens = 1_024,
        )

        val native = nativeHostBridgeTools(request)
        val cli = cliBridgeTools(request)
        assertEquals(MAX_NATIVE_HOST_BRIDGE_TOOLS, native.size)
        assertEquals(64, cli.size)
        assertEquals(kubernetes.map { it.name }, native.filter { it.name.startsWith("mcp__kubernetes__") }.map { it.name })
        assertEquals(kubernetes.map { it.name }, cli.filter { it.name.startsWith("mcp__kubernetes__") }.map { it.name })
        assertTrue(native.all { it in request.tools })
    }

    @Test
    fun `native tool call takes precedence over legacy text marker`() {
        val nativeCall = ContentBlock.ToolCall("native-1", "mcp__demo__ping", JsonObject())
        val response = ModelResponse(
            blocks = listOf(
                ContentBlock.Text(
                    "<<<OMNICODE_TOOL_CALL>>>{\"name\":\"query_database\",\"arguments\":{}}<<<END_OMNICODE_TOOL_CALL>>>",
                ),
                nativeCall,
            ),
            stopReason = StopReason.TOOL_USE,
        )

        val parsed = parseCliBridgeResponse(response, listOf(databaseDefinition))
        assertEquals(response, parsed)
        assertEquals(listOf(nativeCall), parsed.toolCalls)
    }

    @Test
    fun `DSH follow up receives approved tool result and bridge instructions`() {
        val nextRequest = ModelRequest(
            messages = listOf(ConversationMessage(
                MessageRole.USER,
                listOf(ContentBlock.ToolResult("call-1", "| count |\n| 3 |", false)),
            )),
            tools = listOf(databaseDefinition),
            maxOutputTokens = 512,
        )
        val prompt = dshConversationText(nextRequest)

        assertTrue(prompt.contains("[OmniCode 已审批工具结果 · call-1]"))
        assertTrue(prompt.contains("| 3 |"))
        assertTrue(prompt.contains("<<<OMNICODE_TOOL_CALL>>>"))
    }

    @Test
    fun `Codex resumed turn replays approved host result before next bridge request`() {
        val visible = codexNativeConversationText(listOf(ConversationMessage(
            MessageRole.USER,
            listOf(ContentBlock.ToolResult("db-1", "Connected. Rows: 1", false)),
        )))
        val prompt = nativeHostBridgePrompt(visible, listOf(databaseDefinition), 2_000)

        assertTrue(prompt.contains("[OmniCode 已审批工具结果 · db-1]"))
        assertTrue(prompt.contains("Connected. Rows: 1"))
        assertTrue(prompt.contains("<<<OMNICODE_TOOL_CALL>>>"))
    }

    @Test
    fun `valid marker becomes a provider neutral tool call`() {
        val response = ModelResponse(
            blocks = listOf(
                ContentBlock.Text(
                    "准备验证连接。\n<<<OMNICODE_TOOL_CALL>>>\n" +
                        "{\"name\":\"query_database\",\"arguments\":{\"sql\":\"SELECT 1\"}}\n" +
                        "<<<END_OMNICODE_TOOL_CALL>>>",
                ),
            ),
            stopReason = StopReason.COMPLETE,
        )

        val parsed = parseCliBridgeResponse(response, listOf(databaseDefinition))

        assertEquals(StopReason.TOOL_USE, parsed.stopReason)
        assertEquals("准备验证连接。", assertIs<ContentBlock.Text>(parsed.blocks[0]).text)
        val call = assertIs<ContentBlock.ToolCall>(parsed.blocks[1])
        assertEquals("query_database", call.name)
        assertEquals("SELECT 1", call.arguments.get("sql").asString)
        assertTrue(call.id.startsWith("cli-bridge-"))
    }

    @Test
    fun `unknown or malformed tool request fails closed`() {
        val response = ModelResponse(
            blocks = listOf(
                ContentBlock.Text(
                    "<<<OMNICODE_TOOL_CALL>>>" +
                        "{\"name\":\"run_command\",\"arguments\":{\"cmd\":\"env\"}}" +
                        "<<<END_OMNICODE_TOOL_CALL>>>",
                ),
            ),
        )

        val parsed = parseCliBridgeResponse(response, listOf(databaseDefinition))

        assertTrue(parsed.blocks.none { it is ContentBlock.ToolCall })
        assertTrue(parsed.text.contains("格式无效"))
    }

    @Test
    fun `wrong database argument type fails closed`() {
        val response = ModelResponse(
            blocks = listOf(
                ContentBlock.Text(
                    "<<<OMNICODE_TOOL_CALL>>>" +
                        "{\"name\":\"query_database\",\"arguments\":{\"sql\":42}}" +
                        "<<<END_OMNICODE_TOOL_CALL>>>",
                ),
            ),
        )

        val parsed = parseCliBridgeResponse(response, listOf(databaseDefinition))

        assertTrue(parsed.blocks.none { it is ContentBlock.ToolCall })
        assertTrue(parsed.text.contains("格式无效"))
    }

    @Test
    fun `approved database result is replayed without credentials`() {
        val rendered = cliConversationBlockText(
            ContentBlock.ToolResult(
                toolCallId = "db-1",
                content = "Database: db.example:5432/demo\n| count |\n| 3 |",
                isError = false,
            ),
        ).orEmpty()

        assertTrue(rendered.contains("OmniCode 已审批工具结果"))
        assertTrue(rendered.contains("db.example:5432/demo"))
        assertTrue(!rendered.contains("password", ignoreCase = true))
    }

    @Test
    fun `stream filter hides a marker split across chunks`() = runBlocking {
        val visible = StringBuilder()
        val filter = CliBridgeDeltaFilter { visible.append(it) }

        filter.accept("正在准备。<<<OMNICODE_")
        filter.accept("TOOL_CALL>>>{\"name\":\"query_database\"}")
        filter.finish()

        assertEquals("正在准备。", visible.toString())
    }

    @Test
    fun `stream filter preserves ordinary output`() = runBlocking {
        val visible = StringBuilder()
        val filter = CliBridgeDeltaFilter { visible.append(it) }

        filter.accept("普通")
        filter.accept("回答")
        filter.finish()

        assertEquals("普通回答", visible.toString())
    }

    private fun request(vararg tools: dev.omnicode.model.ToolDefinition) = ModelRequest(
        messages = listOf(ConversationMessage(MessageRole.USER, "检查数据库连接")),
        tools = if (tools.isEmpty()) listOf(databaseDefinition) else tools.toList(),
        maxOutputTokens = 1_024,
    )
}
