package dev.omnicode.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatEventEnvelopeTest {
    @Test
    fun `streaming deltas share a stable block and increase sequence`() {
        val mapper = ChatEventEnvelopeMapper(3, "session-1", "turn-1")
        val first = mapper.map(AgentEvent.TextDelta("hello", Instant.EPOCH))
        val second = mapper.map(AgentEvent.TextDelta(" world", Instant.EPOCH.plusSeconds(1)))

        assertEquals(first.blockId, second.blockId)
        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals("message.assistant.delta", second.kind)
    }

    @Test
    fun `tool request and completion converge on the same block`() {
        val mapper = ChatEventEnvelopeMapper(1, "session-1", "turn-1")
        val request = mapper.map(AgentEvent.ToolRequested("read_file", "src/App.kt", callId = "call-7"))
        val result = mapper.map(AgentEvent.ToolCompleted("read_file", "ok", false, callId = "call-7"))

        assertEquals(request.blockId, result.blockId)
        assertEquals("completed", result.phase)
        assertEquals("tool.completed", result.kind)
    }

    @Test
    fun `payload is bounded and private event ids are normalized`() {
        val mapper = ChatEventEnvelopeMapper(1, "session-1", "turn-1")
        val event = mapper.map(AgentEvent.StageStarted("provider request", 2))
        assertTrue(event.blockId.matches(Regex("[A-Za-z0-9._:-]+")))
        assertEquals(1, event.schemaVersion)
    }
}
