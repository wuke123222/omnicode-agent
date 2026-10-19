package dev.omnicode.agent

import java.time.Instant
import dev.omnicode.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatEventEnvelopeTest {
    @Test
    fun `tool availability is a bounded structured turn event without credentials`() {
        val mapper = ChatEventEnvelopeMapper(1, "session-1", "turn-1")
        val envelope = mapper.map(AgentEvent.ToolAvailability(
            entries = listOf(ToolAvailabilityEntry("query_database", "configuration", "凭据尚未配置")),
            note = "本轮工具目录，连接尚未验证。",
        ))

        assertEquals("tools.available", envelope.kind)
        assertEquals("completed", envelope.phase)
        assertEquals("query_database", envelope.payload.getAsJsonArray("entries")[0].asJsonObject.get("name").asString)
        assertTrue(envelope.payload.toString().length < ChatEventEnvelopeV1.MAX_EVENT_PAYLOAD_CHARS)
    }

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

    @Test
    fun `routine status updates replace one progress block`() {
        val mapper = ChatEventEnvelopeMapper(1, "session-1", "turn-1")
        val connecting = mapper.map(AgentEvent.Status("正在连接模型…", Instant.EPOCH))
        val processing = mapper.map(AgentEvent.Status("正在处理模型响应…", Instant.EPOCH.plusSeconds(1)))

        assertEquals(connecting.blockId, processing.blockId)
        assertEquals("running", processing.phase)
    }

    @Test
    fun `provider request completion closes the request block`() {
        val mapper = ChatEventEnvelopeMapper(1, "session-1", "turn-1")
        val started = mapper.map(
            AgentEvent.ProviderRequestStarted(1, 1, "turn-1-provider-1", 100, 200, Instant.EPOCH),
        )
        val completed = mapper.map(AgentEvent.ProviderRequestCompleted(1, 1, 420, Instant.EPOCH.plusSeconds(1)))

        assertEquals(started.blockId, completed.blockId)
        assertEquals("provider.completed", completed.kind)
        assertEquals("completed", completed.phase)
    }

    @Test
    fun `delegated agents keep separate blocks while their own lifecycle converges`() {
        val mapper = ChatEventEnvelopeMapper(1, "session-1", "turn-1")
        val firstStarted = mapper.map(
            AgentEvent.DelegatedAgentStarted(
                workflowId = "workflow-1",
                delegationId = "delegation-1",
                agentId = "agent-1",
                parentAgentId = "lead",
                role = AgentRole.EXPLORER,
                displayName = "Explorer",
                objective = "Inspect the project",
            ),
        )
        val secondStarted = mapper.map(
            AgentEvent.DelegatedAgentStarted(
                workflowId = "workflow-1",
                delegationId = "delegation-1",
                agentId = "agent-2",
                parentAgentId = "lead",
                role = AgentRole.REVIEWER,
                displayName = "Reviewer",
                objective = "Review the project",
            ),
        )
        val firstCompleted = mapper.map(
            AgentEvent.DelegatedAgentCompleted(
                workflowId = "workflow-1",
                delegationId = "delegation-1",
                agentId = "agent-1",
                parentAgentId = "lead",
                role = AgentRole.EXPLORER,
                displayName = "Explorer",
                status = AgentRunStatus.COMPLETED,
                usable = true,
                summary = "done",
                usage = TokenUsage(),
            ),
        )

        assertTrue(firstStarted.blockId != secondStarted.blockId)
        assertEquals(firstStarted.blockId, firstCompleted.blockId)
    }
}
