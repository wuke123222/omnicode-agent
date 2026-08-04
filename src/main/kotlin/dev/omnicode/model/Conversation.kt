package dev.omnicode.model

import com.google.gson.JsonObject

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    /**
     * Repository-authored rules and pinned-file excerpts used for one provider request only.
     * This distinct type prevents user text from spoofing the cleanup marker and makes every
     * persistence/export path fail closed unless it handles transient context explicitly.
     */
    data class TransientProjectContext(val text: String) : ContentBlock

    /**
     * A user-selected image. [base64Data] lives only in the active in-memory
     * conversation; persistence deliberately stores metadata/derived text only.
     */
    data class Image(
        val fileName: String,
        val mediaType: String,
        val base64Data: String,
        val byteSize: Long,
    ) : ContentBlock

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
    ) : ContentBlock

    data class ToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock
}

/** Text serialized to a model provider; transient context remains typed inside OmniCode. */
fun ContentBlock.providerTextOrNull(): String? = when (this) {
    is ContentBlock.Text -> text
    is ContentBlock.TransientProjectContext -> text
    is ContentBlock.Image,
    is ContentBlock.ToolCall,
    is ContentBlock.ToolResult,
    -> null
}

data class ConversationMessage(
    val role: MessageRole,
    val blocks: List<ContentBlock>,
) {
    constructor(role: MessageRole, text: String) : this(role, listOf(ContentBlock.Text(text)))
}

enum class AttachmentKind {
    IMAGE,
    MARKDOWN,
    TEXT,
}

/** A bounded, local attachment supplied alongside one user task. */
data class UserAttachment(
    val fileName: String,
    val kind: AttachmentKind,
    val mediaType: String,
    val byteSize: Long,
    val content: String,
)

data class UserSubmission(
    val prompt: String,
    val attachments: List<UserAttachment> = emptyList(),
) {
    fun toMessage(): ConversationMessage = ConversationMessage(
        MessageRole.USER,
        buildList {
            if (prompt.isNotBlank()) add(ContentBlock.Text(prompt))
            attachments.forEach { attachment ->
                when (attachment.kind) {
                    AttachmentKind.IMAGE -> add(
                        ContentBlock.Image(
                            fileName = attachment.fileName,
                            mediaType = attachment.mediaType,
                            base64Data = attachment.content,
                            byteSize = attachment.byteSize,
                        ),
                    )
                    AttachmentKind.MARKDOWN -> add(
                        ContentBlock.Text(
                            """
                            [Markdown attachment: ${attachment.fileName}]
                            ${attachment.content}
                            [End Markdown attachment: ${attachment.fileName}]
                            """.trimIndent(),
                        ),
                    )
                    AttachmentKind.TEXT -> add(
                        ContentBlock.Text(
                            """
                            [Text attachment: ${attachment.fileName}; ${attachment.mediaType}]
                            ${attachment.content}
                            [End text attachment: ${attachment.fileName}]
                            """.trimIndent(),
                        ),
                    )
                }
            }
        },
    )

    val estimatedCharacterCount: Int
        get() = prompt.length + attachments.sumOf { attachment ->
            if (attachment.kind == AttachmentKind.IMAGE) 0 else attachment.content.length
        }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    /** Providers treat this schema as immutable while constructing a request body. */
    val inputSchema: JsonObject,
)

data class ModelRequest(
    val messages: List<ConversationMessage>,
    val tools: List<ToolDefinition>,
    val maxOutputTokens: Int,
    val temperature: Double = 0.2,
    /** Stable across retries of one logical provider request. */
    val idempotencyKey: String? = null,
)

data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

enum class StopReason {
    COMPLETE,
    TOOL_USE,
    LENGTH,
    CONTENT_FILTER,
    UNKNOWN,
}

data class ModelResponse(
    val blocks: List<ContentBlock>,
    val usage: TokenUsage = TokenUsage(),
    val stopReason: StopReason = StopReason.UNKNOWN,
    val providerRequestId: String? = null,
) {
    val text: String
        get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    val toolCalls: List<ContentBlock.ToolCall>
        get() = blocks.filterIsInstance<ContentBlock.ToolCall>()
}
