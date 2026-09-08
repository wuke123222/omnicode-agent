package dev.omnicode.provider

import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.tool.QueryDatabaseTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliToolBridgeTest {
    private val databaseDefinition = QueryDatabaseTool().definition()

    @Test
    fun `database tool is described to a headless cli without credentials`() {
        val instructions = cliBridgeInstructions(request())

        assertTrue(instructions.contains("query_database"))
        assertTrue(instructions.contains("SELECT 1"))
        assertTrue(instructions.contains("不要声称这些工具不可用"))
        assertTrue(!instructions.contains("password", ignoreCase = true))
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

    private fun request() = ModelRequest(
        messages = listOf(ConversationMessage(MessageRole.USER, "检查数据库连接")),
        tools = listOf(databaseDefinition),
        maxOutputTokens = 1_024,
    )
}
