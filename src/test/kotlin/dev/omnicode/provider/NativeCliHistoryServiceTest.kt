package dev.omnicode.provider

import dev.omnicode.model.ContentBlock
import dev.omnicode.model.MessageRole
import dev.omnicode.util.Json
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCliHistoryServiceTest {
    private val project = Path.of("/workspace/project")

    @Test
    fun `OpenCode list keeps only current project and bounded metadata`() {
        val entries = parseOpenCodeHistoryList(
            """[
              {"id":"ses_ok","title":"Current","updated":1700000000000,"directory":"/workspace/project"},
              {"id":"ses_other","title":"Other","updated":1700000001000,"directory":"/workspace/other"},
              {"id":"../unsafe","title":"Unsafe","updated":1,"directory":"/workspace/project"}
            ]""",
            project,
        )
        assertEquals(listOf("ses_ok"), entries.map(NativeCliHistoryEntry::sessionId))
        assertEquals("Current", entries.single().title)
    }

    @Test
    fun `Gemini list keeps documented opaque resume ids without guessing timestamps`() {
        val entries = parseGeminiHistoryList(
            """
            Available sessions for this project (2):
              1. Fix parser regression (2 hours ago) [session-abc_123]
              2. Research transport [session:def.456]
              3. Unsafe [../../escape]
              4. Duplicate [session-abc_123]
            """.trimIndent(),
        )

        assertEquals(listOf("session-abc_123", "session:def.456"), entries.map(NativeCliHistoryEntry::sessionId))
        assertEquals(listOf("Fix parser regression", "Research transport"), entries.map(NativeCliHistoryEntry::title))
    }

    @Test
    fun `OpenCode import keeps visible text and redacted tool history`() {
        val transcript = parseOpenCodeTranscript(
            """{
              "info":{"id":"ses_ok","title":"Imported","directory":"/workspace/project","time":{"updated":1700000000000}},
              "messages":[
                {"info":{"role":"user"},"parts":[{"type":"text","text":"question"}]},
                {"info":{"role":"assistant"},"parts":[{"type":"reasoning","text":"hidden"},{"type":"text","text":"answer"},{"type":"tool","callID":"tool-1","tool":"read_file","state":{"input":{"path":"README.md","api_key":"sk-test-secret-value"},"output":"done","status":"completed"}}]}
              ]
            }""",
            project,
            "ses_ok",
        )
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), transcript.messages.map { it.role })
        assertEquals("question", transcript.messages[0].blocks.filterIsInstance<ContentBlock.Text>().single().text)
        assertEquals("answer", transcript.messages[1].blocks.filterIsInstance<ContentBlock.Text>().single().text)
        val call = transcript.messages[1].blocks.filterIsInstance<ContentBlock.ToolCall>().single()
        assertEquals("read_file", call.name)
        assertEquals("[REDACTED]", call.arguments.get("api_key").asString)
        assertEquals("done", transcript.messages[2].blocks.filterIsInstance<ContentBlock.ToolResult>().single().content)
    }

    @Test
    fun `Codex import keeps user agent and visible plan items`() {
        val result = Json.parseObject(
            """{"thread":{
              "id":"019f-native","cwd":"/workspace/project","preview":"Task","updatedAt":1700000000,
              "turns":[{"items":[
                {"id":"u1","type":"userMessage","content":[{"type":"text","text":"question"},{"type":"image","url":"data:secret"}]},
                {"id":"r1","type":"reasoning","summary":["hidden"]},
                {"id":"a1","type":"agentMessage","text":"answer"},
                {"id":"p1","type":"plan","text":"visible plan"},
                {"id":"c1","type":"commandExecution","command":"git status","aggregatedOutput":"clean","exitCode":0}
              ]}]
            }}""",
        )
        val transcript = parseCodexTranscript(result, project, "019f-native")
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), transcript.messages.map { it.role })
        assertEquals("question", transcript.messages[0].blocks.filterIsInstance<ContentBlock.Text>().single().text)
        assertEquals("answer\nvisible plan", transcript.messages[1].blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text })
        assertEquals("Bash", transcript.messages[1].blocks.filterIsInstance<ContentBlock.ToolCall>().single().name)
        assertEquals("clean", transcript.messages[2].blocks.filterIsInstance<ContentBlock.ToolResult>().single().content)
    }

    @Test
    fun `native transcript from another project is rejected`() {
        assertFailsWith<ProviderException> {
            parseOpenCodeTranscript(
                """{"info":{"id":"ses_ok","directory":"/workspace/other"},"messages":[]}""",
                project,
                "ses_ok",
            )
        }
    }
}
