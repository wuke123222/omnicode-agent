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
    fun `OpenCode import keeps only visible text`() {
        val transcript = parseOpenCodeTranscript(
            """{
              "info":{"id":"ses_ok","title":"Imported","directory":"/workspace/project","time":{"updated":1700000000000}},
              "messages":[
                {"info":{"role":"user"},"parts":[{"type":"text","text":"question"}]},
                {"info":{"role":"assistant"},"parts":[{"type":"reasoning","text":"hidden"},{"type":"text","text":"answer"},{"type":"tool","state":{"output":"secret"}}]}
              ]
            }""",
            project,
            "ses_ok",
        )
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), transcript.messages.map { it.role })
        assertEquals(listOf("question", "answer"), transcript.messages.map { it.blocks.filterIsInstance<ContentBlock.Text>().single().text })
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
                {"id":"c1","type":"commandExecution","aggregatedOutput":"private output"}
              ]}]
            }}""",
        )
        val transcript = parseCodexTranscript(result, project, "019f-native")
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), transcript.messages.map { it.role })
        assertEquals("question", transcript.messages[0].blocks.filterIsInstance<ContentBlock.Text>().single().text)
        assertEquals("answer\nvisible plan", transcript.messages[1].blocks.filterIsInstance<ContentBlock.Text>().single().text)
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
