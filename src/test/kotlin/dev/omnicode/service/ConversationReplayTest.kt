package dev.omnicode.service

import dev.omnicode.agent.AgentEvent
import dev.omnicode.agent.ToolApprovalOutcome
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.persistence.ConversationRecord
import dev.omnicode.persistence.MessageSnapshot
import dev.omnicode.persistence.SnapshotRole
import dev.omnicode.persistence.ToolExecutionStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationReplayTest {
    @Test
    fun `terminal checkpoints retain failed and cancelled work with user or tool observations`() {
        val userOnly = listOf(ConversationMessage(MessageRole.USER, "Retry this task"))
        val toolObservation = listOf(
            ConversationMessage(
                MessageRole.ASSISTANT,
                listOf(ContentBlock.ToolResult("call-1", "file changed")),
            ),
        )
        val assistantOnly = listOf(ConversationMessage(MessageRole.ASSISTANT, "orphan response"))

        assertTrue(hasConversationCheckpoint(userOnly))
        assertTrue(hasConversationCheckpoint(toolObservation))
        assertTrue(!hasConversationCheckpoint(assistantOnly))
    }

    @Test
    fun `restore preserves paired tool call and observation blocks`() {
        val record = conversation(
            MessageSnapshot(SnapshotRole.USER, "Inspect the project"),
            MessageSnapshot(SnapshotRole.ASSISTANT, "I will inspect it."),
            MessageSnapshot(
                role = SnapshotRole.TOOL,
                text = "{\"path\":\"src/Main.kt\"}",
                toolName = "read_file",
                toolCallId = "call-1",
            ),
            MessageSnapshot(
                role = SnapshotRole.TOOL,
                text = "1\\tfun main() = Unit",
                toolCallId = "call-1",
            ),
            MessageSnapshot(SnapshotRole.ASSISTANT, "The file is valid."),
        )

        val messages = messagesFromConversationRecord(record)

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals(2, messages[1].blocks.size)
        val call = assertIs<ContentBlock.ToolCall>(messages[1].blocks[1])
        assertEquals("call-1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("src/Main.kt", call.arguments.get("path").asString)
        val result = assertIs<ContentBlock.ToolResult>(messages[2].blocks.single())
        assertEquals("call-1", result.toolCallId)
        assertTrue(!result.isError)
    }

    @Test
    fun `restore drops orphaned or malformed tool snapshots`() {
        val record = conversation(
            MessageSnapshot(SnapshotRole.USER, "Continue"),
            MessageSnapshot(SnapshotRole.TOOL, "orphan result", toolCallId = "missing-call"),
            MessageSnapshot(
                SnapshotRole.TOOL,
                "not-json",
                toolName = "read_file",
                toolCallId = "malformed-call",
            ),
            MessageSnapshot(SnapshotRole.TOOL, "result", toolCallId = "malformed-call"),
            MessageSnapshot(SnapshotRole.ASSISTANT, "Recovered"),
        )

        val messages = messagesFromConversationRecord(record)

        assertEquals(2, messages.size)
        assertTrue(messages.flatMap { it.blocks }.none { it is ContentBlock.ToolCall || it is ContentBlock.ToolResult })
    }

    @Test
    fun `cancelled completion maps to cancelled audit status before generic failure`() {
        val event = AgentEvent.ToolCompleted(
            name = "run_command",
            result = "cancelled",
            isError = true,
            approvalOutcome = ToolApprovalOutcome.APPROVED,
            callId = "call-2",
            cancelled = true,
        )

        assertEquals(ToolExecutionStatus.CANCELLED, toolExecutionStatus(event))
    }

    private fun conversation(vararg messages: MessageSnapshot): ConversationRecord = ConversationRecord(
        id = "conversation-1",
        projectId = "project-1",
        title = "Replay",
        createdAt = Instant.parse("2026-07-17T00:00:00Z"),
        updatedAt = Instant.parse("2026-07-17T00:01:00Z"),
        messages = messages.toList(),
    )
}
