package dev.omnicode.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.util.Json

class AnthropicMessagesProvider(
    private val connection: ProviderConnection,
) : ModelProvider {
    override val id: String = connection.preset.id

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val text = StringBuilder()
        val calls = linkedMapOf<Int, ToolCallAccumulator>()
        var inputTokens = 0L
        var outputTokens = 0L
        var stopReason = StopReason.UNKNOWN
        var messageId: String? = null
        var terminalReceived = false

        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("x-api-key", connection.apiKey)
            put("anthropic-version", connection.apiVersion.takeIf { it.matches(ANTHROPIC_VERSION) } ?: "2023-06-01")
            putAll(connection.extraHeaders)
        }
        val responseHeaders = HttpTransport.postSse(
            "${connection.baseUrl.trimEnd('/')}/messages",
            headers,
            Json.stringify(buildBody(request)),
            connection.requestTimeoutSeconds,
            connection.sensitiveValues(),
        ) { eventName, data ->
            if (data == "[DONE]") {
                terminalReceived = true
                return@postSse
            }
            val event = runCatching { Json.parseObject(data) }.getOrNull() ?: return@postSse
            when (event.stringOrNull("type") ?: eventName.orEmpty()) {
                "message_start" -> {
                    event.jsonObjectOrNull("message")?.let { message ->
                        messageId = message.stringOrNull("id") ?: messageId
                        message.jsonObjectOrNull("usage")?.let { usage ->
                            inputTokens = usage.longOrZero("input_tokens")
                            outputTokens = usage.longOrZero("output_tokens")
                        }
                    }
                }

                "content_block_start" -> {
                    val index = event.intOrNull("index") ?: calls.size
                    val block = event.jsonObjectOrNull("content_block") ?: return@postSse
                    if (block.stringOrNull("type") == "tool_use") {
                        val call = calls.getOrPut(index) { ToolCallAccumulator() }
                        block.stringOrNull("id")?.let { call.id = it }
                        block.stringOrNull("name")?.let { call.name = it }
                        block.get("input")?.takeUnless { it.isJsonNull }?.let {
                            if (it.isJsonObject && it.asJsonObject.size() > 0) {
                                call.arguments.append(Json.stringify(it))
                            }
                        }
                    }
                }

                "content_block_delta" -> {
                    val index = event.intOrNull("index") ?: calls.size
                    val delta = event.jsonObjectOrNull("delta") ?: return@postSse
                    when (delta.stringOrNull("type")) {
                        "text_delta" -> delta.stringOrNull("text")?.let { value ->
                            text.append(value)
                            onTextDelta(value)
                        }
                        "input_json_delta" -> delta.stringOrNull("partial_json")?.let { value ->
                            calls.getOrPut(index) { ToolCallAccumulator() }.arguments.append(value)
                        }
                        // Thinking/signature and future content deltas are intentionally ignored.
                        else -> Unit
                    }
                }

                "message_delta" -> {
                    event.jsonObjectOrNull("delta")?.stringOrNull("stop_reason")?.let {
                        terminalReceived = true
                        stopReason = mapStopReason(it)
                    }
                    event.jsonObjectOrNull("usage")?.let { usage ->
                        outputTokens = usage.longOrZero("output_tokens").takeIf { it > 0 } ?: outputTokens
                    }
                }

                "message_stop" -> terminalReceived = true

                "error" -> throw providerStreamException("Anthropic Messages", event, connection)

                // ping, block stop, message stop, and future event types are safe to ignore.
                else -> Unit
            }
        }
        if (!terminalReceived) {
            throw ProviderException("Anthropic Messages stream ended before a terminal event")
        }

        val blocks = buildList {
            if (text.isNotEmpty()) add(ContentBlock.Text(text.toString()))
            calls.toSortedMap().values.forEachIndexed { index, call ->
                if (call.name.isBlank()) return@forEachIndexed
                val arguments = runCatching { Json.parseObject(call.arguments.toString()) }.getOrElse { JsonObject() }
                add(ContentBlock.ToolCall(call.id.ifBlank { "toolu_$index" }, call.name, arguments))
            }
        }
        if (
            blocks.any { it is ContentBlock.ToolCall } &&
            stopReason != StopReason.LENGTH &&
            stopReason != StopReason.CONTENT_FILTER
        ) {
            stopReason = StopReason.TOOL_USE
        }
        if (stopReason == StopReason.UNKNOWN && blocks.isNotEmpty()) stopReason = StopReason.COMPLETE

        return ModelResponse(
            blocks = blocks,
            usage = TokenUsage(inputTokens, outputTokens),
            stopReason = stopReason,
            providerRequestId = messageId ?: responseHeaders.headerValue("request-id"),
        )
    }

    private fun buildBody(request: ModelRequest): JsonObject = JsonObject().apply {
        addProperty("model", connection.model)
        addProperty("stream", true)
        addProperty("max_tokens", request.maxOutputTokens)
        addProperty("temperature", request.temperature)

        val systemText = request.messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.blocks.filterIsInstance<ContentBlock.Text>() }
            .joinToString("\n") { it.text }
        if (systemText.isNotBlank()) addProperty("system", systemText)
        add("messages", buildMessages(request.messages))

        if (request.tools.isNotEmpty()) {
            add("tools", JsonArray().apply {
                request.tools.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)
                        add("input_schema", tool.inputSchema.deepCopy())
                    })
                }
            })
            add("tool_choice", JsonObject().apply {
                addProperty("type", "auto")
                addProperty("disable_parallel_tool_use", true)
            })
        }
    }

    private fun buildMessages(messages: List<ConversationMessage>): JsonArray = JsonArray().apply {
        messages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            val content = JsonArray()

            // Anthropic requires tool_result blocks before any text in a user message.
            message.blocks.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                content.add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", result.toolCallId)
                    addProperty("content", result.content)
                    if (result.isError) addProperty("is_error", true)
                })
            }
            message.blocks.filterIsInstance<ContentBlock.Text>().forEach { block ->
                if (block.text.isNotBlank()) {
                    content.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", block.text)
                    })
                }
            }
            message.blocks.filterIsInstance<ContentBlock.Image>().forEach { image ->
                content.add(JsonObject().apply {
                    addProperty("type", "image")
                    add("source", JsonObject().apply {
                        addProperty("type", "base64")
                        addProperty("media_type", image.mediaType)
                        addProperty("data", image.base64Data)
                    })
                })
            }
            message.blocks.filterIsInstance<ContentBlock.ToolCall>().forEach { call ->
                content.add(JsonObject().apply {
                    addProperty("type", "tool_use")
                    addProperty("id", call.id)
                    addProperty("name", call.name)
                    add("input", call.arguments.deepCopy())
                })
            }

            if (content.size() > 0) {
                add(JsonObject().apply {
                    addProperty("role", if (message.role == MessageRole.ASSISTANT) "assistant" else "user")
                    add("content", content)
                })
            }
        }
    }

    private fun mapStopReason(value: String): StopReason = when (value.lowercase()) {
        "end_turn", "stop_sequence", "pause_turn" -> StopReason.COMPLETE
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens", "model_context_window_exceeded" -> StopReason.LENGTH
        "refusal" -> StopReason.CONTENT_FILTER
        else -> StopReason.UNKNOWN
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private companion object {
        val ANTHROPIC_VERSION = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
