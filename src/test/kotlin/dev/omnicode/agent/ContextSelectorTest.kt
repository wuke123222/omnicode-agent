package dev.omnicode.agent

import com.google.gson.JsonObject
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContextSelectorTest {
    @Test
    fun `tool call and result stay atomic when middle history is trimmed`() {
        val call = ContentBlock.ToolCall("call-1", "read_file", JsonObject())
        val result = ContentBlock.ToolResult("call-1", "ok")
        val selected = ContextSelector.select(
            listOf(
                ConversationMessage(MessageRole.SYSTEM, "system"),
                ConversationMessage(MessageRole.USER, "original goal"),
                ConversationMessage(MessageRole.ASSISTANT, "x".repeat(20_000)),
                ConversationMessage(MessageRole.ASSISTANT, listOf(call)),
                ConversationMessage(MessageRole.USER, listOf(result)),
            ),
            maxChars = 2_000,
        )

        val selectedCalls = selected.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolCall>()
        val selectedResults = selected.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(listOf("call-1"), selectedCalls.map { it.id })
        assertEquals(listOf("call-1"), selectedResults.map { it.toolCallId })
        assertTrue(selected.none { message ->
            message.blocks.filterIsInstance<ContentBlock.Text>().any { it.text.length == 20_000 }
        })
    }

    @Test
    fun `latest user message is retained when middle history is trimmed`() {
        val latest = ConversationMessage(MessageRole.USER, "latest request")
        val selected = ContextSelector.select(
            listOf(
                ConversationMessage(MessageRole.SYSTEM, "system"),
                ConversationMessage(MessageRole.USER, "original goal"),
                ConversationMessage(MessageRole.ASSISTANT, "x".repeat(20_000)),
                latest,
            ),
            maxChars = 1_000,
        )

        assertTrue(selected.any { it === latest })
        assertTrue(ContextSelector.estimatedInputTokens(selected) <= 250)
    }

    @Test
    fun `required context fails closed when it cannot fit`() {
        assertFailsWith<ContextBudgetExceededException> {
            ContextSelector.select(
                listOf(
                    ConversationMessage(MessageRole.SYSTEM, "system"),
                    ConversationMessage(MessageRole.USER, "x".repeat(700)),
                    ConversationMessage(MessageRole.USER, "y".repeat(700)),
                ),
                maxChars = 1_000,
            )
        }
    }

    @Test
    fun `trimmed context retains structural execution memory without raw tool output`() {
        val patchArguments = JsonObject().apply { addProperty("path", "src/Auth.kt") }
        val commandArguments = JsonObject().apply {
            add("argv", com.google.gson.JsonArray().apply {
                add("./gradlew")
                add("test")
            })
        }
        val memory = compressedExecutionMemory(
            listOf(
                ConversationMessage(
                    MessageRole.ASSISTANT,
                    listOf(
                        ContentBlock.ToolCall("patch-1", "apply_patch", patchArguments),
                        ContentBlock.ToolCall("cmd-1", "run_command", commandArguments),
                    ),
                ),
                ConversationMessage(
                    MessageRole.USER,
                    listOf(
                        ContentBlock.ToolResult("patch-1", "secret raw output", false),
                        ContentBlock.ToolResult("cmd-1", "another raw output", true),
                    ),
                ),
            ),
        )

        assertTrue(memory.contains("apply_patch completed for src/Auth.kt"))
        assertTrue(memory.contains("run_command failed (./gradlew)"))
        assertTrue(!memory.contains("secret raw output"))
        assertTrue(!memory.contains("another raw output"))
    }
}
