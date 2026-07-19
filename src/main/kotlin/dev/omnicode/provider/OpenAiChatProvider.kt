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

class OpenAiChatProvider(
    private val connection: ProviderConnection,
) : ModelProvider {
    override val id: String = connection.preset.id

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val body = buildBody(request)
        val text = StringBuilder()
        val calls = linkedMapOf<Int, ToolCallAccumulator>()
        var stopReason = StopReason.UNKNOWN
        var usage = TokenUsage()
        var terminalReceived = false

        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) {
                if (connection.preset.protocol == ProviderProtocol.AZURE_OPENAI) {
                    put("api-key", connection.apiKey)
                } else {
                    put("Authorization", "Bearer ${connection.apiKey}")
                }
            }
            putAll(connection.extraHeaders)
        }
        val responseHeaders = HttpTransport.postSse(
            endpoint(),
            headers,
            Json.stringify(body),
            connection.requestTimeoutSeconds,
            connection.sensitiveValues(),
        ) { eventName, data ->
            if (data == "[DONE]") {
                terminalReceived = true
                return@postSse
            }
            val chunk = runCatching { Json.parseObject(data) }.getOrNull() ?: return@postSse
            if (eventName == "error" || chunk.get("error")?.takeUnless { it.isJsonNull } != null) {
                throw providerStreamException(connection.preset.displayName, chunk, connection)
            }
            chunk.getAsJsonObject("usage")?.let {
                usage = TokenUsage(
                    it.longOrZero("prompt_tokens"),
                    it.longOrZero("completion_tokens"),
                )
            }
            val choice = chunk.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: return@postSse
            val delta = choice.getAsJsonObject("delta")
            delta?.get("content")?.takeUnless { it.isJsonNull }?.asString?.let { value ->
                text.append(value)
                onTextDelta(value)
            }
            delta?.getAsJsonArray("tool_calls")?.forEach { element ->
                val tool = element.asJsonObject
                val index = tool.get("index")?.asInt ?: calls.size
                val accumulator = calls.getOrPut(index) { ToolCallAccumulator() }
                tool.get("id")?.takeUnless { it.isJsonNull }?.asString?.let { accumulator.id = it }
                tool.getAsJsonObject("function")?.let { function ->
                    function.get("name")?.takeUnless { it.isJsonNull }?.asString?.let {
                        accumulator.name = mergeStreamedValue(accumulator.name, it)
                    }
                    function.get("arguments")?.takeUnless { it.isJsonNull }?.let { arguments ->
                        val value = if (arguments.isJsonPrimitive) arguments.asString else Json.stringify(arguments)
                        accumulator.arguments.append(value)
                    }
                }
            }
            choice.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString?.let {
                terminalReceived = true
                stopReason = mapStopReason(it)
            }
        }
        if (!terminalReceived) {
            throw ProviderException("${connection.preset.displayName} stream ended before a terminal event")
        }
        val requestId = responseHeaders.headerValue("x-request-id")
            ?: responseHeaders.headerValue("request-id")

        val blocks = buildList {
            if (text.isNotEmpty()) add(ContentBlock.Text(text.toString()))
            calls.values.forEachIndexed { index, call ->
                if (call.name.isNotBlank()) {
                    val args = runCatching { Json.parseObject(call.arguments.toString()) }.getOrElse { JsonObject() }
                    add(ContentBlock.ToolCall(call.id.ifBlank { "call_$index" }, call.name, args))
                }
            }
        }
        if (
            blocks.any { it is ContentBlock.ToolCall } &&
            stopReason != StopReason.LENGTH &&
            stopReason != StopReason.CONTENT_FILTER
        ) {
            stopReason = StopReason.TOOL_USE
        }
        return ModelResponse(blocks, usage, stopReason, requestId)
    }

    private fun buildBody(request: ModelRequest): JsonObject = JsonObject().apply {
        addProperty("model", connection.model)
        addProperty("stream", true)
        addProperty("max_tokens", request.maxOutputTokens)
        addProperty("temperature", request.temperature)
        add("messages", buildMessages(request.messages))
        if (request.tools.isNotEmpty()) {
            add("tools", JsonArray().apply {
                request.tools.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            add("parameters", tool.inputSchema.deepCopy())
                        })
                    })
                }
            })
            addProperty("tool_choice", "auto")
            addProperty("parallel_tool_calls", false)
        }
    }

    private fun buildMessages(messages: List<ConversationMessage>): JsonArray = JsonArray().apply {
        messages.forEach { message ->
            val text = message.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
            val images = message.blocks.filterIsInstance<ContentBlock.Image>()
            if (text.isNotBlank() || message.blocks.none { it is ContentBlock.ToolResult }) {
                add(JsonObject().apply {
                    addProperty("role", message.role.openAiRole())
                    if (images.isEmpty()) {
                        addProperty("content", text.takeIf { it.isNotBlank() })
                    } else {
                        add("content", JsonArray().apply {
                            if (text.isNotBlank()) add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", text)
                            })
                            images.forEach { image -> add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply { addProperty("url", image.dataUrl()) })
                            }) }
                        })
                    }
                    val toolCalls = message.blocks.filterIsInstance<ContentBlock.ToolCall>()
                    if (toolCalls.isNotEmpty()) {
                        add("tool_calls", JsonArray().apply {
                            toolCalls.forEach { call ->
                                add(JsonObject().apply {
                                    addProperty("id", call.id)
                                    addProperty("type", "function")
                                    add("function", JsonObject().apply {
                                        addProperty("name", call.name)
                                        addProperty("arguments", Json.stringify(call.arguments))
                                    })
                                })
                            }
                        })
                    }
                })
            }
            message.blocks.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", result.toolCallId)
                    addProperty("content", result.content)
                })
            }
        }
    }

    private fun endpoint(): String = when (connection.preset.protocol) {
        ProviderProtocol.AZURE_OPENAI -> {
            val base = connection.baseUrl.trimEnd('/')
            when {
                base.endsWith("/openai/v1") -> "$base/chat/completions"
                connection.apiVersion.equals("v1", true) -> "$base/openai/v1/chat/completions"
                else -> "$base/openai/deployments/${encodePath(connection.model)}/chat/completions?api-version=${connection.apiVersion}"
            }
        }
        else -> "${connection.baseUrl.trimEnd('/')}/chat/completions"
    }

    private fun mapStopReason(reason: String): StopReason = when (reason.lowercase()) {
        "stop" -> StopReason.COMPLETE
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "length" -> StopReason.LENGTH
        "content_filter" -> StopReason.CONTENT_FILTER
        else -> StopReason.UNKNOWN
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )
}

internal fun MessageRole.openAiRole(): String = when (this) {
    MessageRole.SYSTEM -> "system"
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
}

internal fun encodePath(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
