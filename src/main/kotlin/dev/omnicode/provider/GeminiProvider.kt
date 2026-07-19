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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class GeminiProvider(
    private val connection: ProviderConnection,
) : ModelProvider {
    override val id: String = connection.preset.id

    /*
     * Gemini Interactions is the preferred GA API. OmniCode's current shared
     * conversation type cannot persist Interactions thought steps and opaque
     * continuation state across provider recreation, while generateContent can
     * reliably replay the complete visible history and function responses.
     * Use stable streamGenerateContent until the core model gains that state.
     */
    private val replayPartsByCallId = ConcurrentHashMap<String, List<JsonObject>>()
    private val providerIssuedCallIds = ConcurrentHashMap.newKeySet<String>()
    private val fallbackCallSequence = AtomicLong()

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val text = StringBuilder()
        val calls = linkedMapOf<String, ToolCallAccumulator>()
        val replayParts = mutableListOf<JsonObject>()
        var usage = TokenUsage()
        var stopReason = StopReason.UNKNOWN
        var terminalReceived = false

        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("x-goog-api-key", connection.apiKey)
            putAll(connection.extraHeaders)
        }
        val responseHeaders = HttpTransport.postSse(
            endpoint(),
            headers,
            Json.stringify(buildBody(request)),
            connection.requestTimeoutSeconds,
            connection.sensitiveValues(),
        ) { _, data ->
            if (data == "[DONE]") {
                terminalReceived = true
                return@postSse
            }
            val chunk = runCatching { Json.parseObject(data) }.getOrNull() ?: return@postSse
            if (chunk.has("error")) throw providerStreamException("Google Gemini", chunk, connection)

            chunk.jsonObjectOrNull("usageMetadata")?.let { metadata ->
                usage = TokenUsage(
                    inputTokens = metadata.longOrZero("promptTokenCount"),
                    outputTokens = metadata.longOrZero("candidatesTokenCount"),
                )
            }
            chunk.jsonObjectOrNull("promptFeedback")?.stringOrNull("blockReason")?.let {
                if (it.isNotBlank() && !it.equals("BLOCK_REASON_UNSPECIFIED", true)) {
                    terminalReceived = true
                    stopReason = StopReason.CONTENT_FILTER
                }
            }

            chunk.getAsJsonArray("candidates")?.forEachIndexed candidateLoop@ { candidateIndex, candidateElement ->
                if (!candidateElement.isJsonObject) return@candidateLoop
                val candidate = candidateElement.asJsonObject
                candidate.stringOrNull("finishReason")?.let {
                    terminalReceived = true
                    stopReason = mapStopReason(it)
                }
                val parts = candidate.jsonObjectOrNull("content")?.getAsJsonArray("parts") ?: return@candidateLoop
                parts.forEachIndexed partLoop@ { partIndex, partElement ->
                    if (!partElement.isJsonObject) return@partLoop
                    val part = partElement.asJsonObject
                    val isThought = part.get("thought")?.runCatching { asBoolean }?.getOrDefault(false) == true
                    if (isThought || part.has("thoughtSignature")) {
                        addReplayPart(replayParts, part)
                    }
                    if (!isThought) {
                        part.stringOrNull("text")?.let { delta ->
                            text.append(delta)
                            onTextDelta(delta)
                        }
                    }

                    val functionCall = part.jsonObjectOrNull("functionCall") ?: return@partLoop
                    val providerId = functionCall.stringOrNull("id")
                    val key = providerId ?: "$candidateIndex:$partIndex:${functionCall.stringOrNull("name").orEmpty()}"
                    val call = calls.getOrPut(key) { ToolCallAccumulator() }
                    if (providerId != null) {
                        call.id = providerId
                        call.providerIssuedId = true
                    }
                    functionCall.stringOrNull("name")?.let { call.name = it }
                    functionCall.get("args")?.takeUnless { it.isJsonNull }?.let { args ->
                        call.arguments = if (args.isJsonObject) args.asJsonObject.deepCopy() else JsonObject()
                    }
                    addReplayPart(replayParts, part)
                }
            }
        }
        if (!terminalReceived) {
            throw ProviderException("Google Gemini stream ended before a terminal event")
        }

        val blocks = buildList {
            if (text.isNotEmpty()) add(ContentBlock.Text(text.toString()))
            calls.values.forEach { call ->
                if (call.name.isBlank()) return@forEach
                val callId = call.id.ifBlank { "gemini_call_${fallbackCallSequence.incrementAndGet()}" }
                if (call.providerIssuedId) providerIssuedCallIds += callId
                add(ContentBlock.ToolCall(callId, call.name, call.arguments))
                if (replayParts.isNotEmpty()) {
                    replayPartsByCallId[callId] = replayParts.map { it.deepCopy() }
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
        if (stopReason == StopReason.UNKNOWN && blocks.isNotEmpty()) stopReason = StopReason.COMPLETE
        if (blocks.none { it is ContentBlock.ToolCall }) {
            replayPartsByCallId.clear()
            providerIssuedCallIds.clear()
        }

        return ModelResponse(
            blocks = blocks,
            usage = usage,
            stopReason = stopReason,
            providerRequestId = responseHeaders.headerValue("x-goog-request-id")
                ?: responseHeaders.headerValue("x-request-id"),
        )
    }

    private fun buildBody(request: ModelRequest): JsonObject = JsonObject().apply {
        val systemText = request.messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.blocks.filterIsInstance<ContentBlock.Text>() }
            .joinToString("\n") { it.text }
        if (systemText.isNotBlank()) {
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply { add(textPart(systemText)) })
            })
        }
        add("contents", buildContents(request.messages))
        add("generationConfig", JsonObject().apply {
            addProperty("maxOutputTokens", request.maxOutputTokens)
            addProperty("temperature", request.temperature)
        })
        if (request.tools.isNotEmpty()) {
            add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    add("functionDeclarations", JsonArray().apply {
                        request.tools.forEach { tool ->
                            add(JsonObject().apply {
                                addProperty("name", tool.name)
                                addProperty("description", tool.description)
                                add("parameters", tool.inputSchema.deepCopy())
                            })
                        }
                    })
                })
            })
            add("toolConfig", JsonObject().apply {
                add("functionCallingConfig", JsonObject().apply { addProperty("mode", "AUTO") })
            })
        }
    }

    private fun buildContents(messages: List<ConversationMessage>): JsonArray = JsonArray().apply {
        val toolNames = buildMap {
            messages.flatMap { it.blocks.filterIsInstance<ContentBlock.ToolCall>() }
                .forEach { put(it.id, it.name) }
        }
        messages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            val parts = JsonArray()
            val replay = message.blocks.filterIsInstance<ContentBlock.ToolCall>()
                .asSequence()
                .mapNotNull { replayPartsByCallId[it.id] }
                .flatten()
                .distinctBy { Json.stringify(it) }
                .toList()
            val replayedCallIds = replay.mapNotNull { part ->
                part.jsonObjectOrNull("functionCall")?.stringOrNull("id")
            }.toSet()
            val replayedCallNames = replay.mapNotNull { part ->
                part.jsonObjectOrNull("functionCall")?.stringOrNull("name")
            }.toSet()

            message.blocks.filterIsInstance<ContentBlock.Text>().forEach { block ->
                if (block.text.isNotBlank()) parts.add(textPart(block.text))
            }
            message.blocks.filterIsInstance<ContentBlock.Image>().forEach { image ->
                parts.add(JsonObject().apply {
                    add("inlineData", JsonObject().apply {
                        addProperty("mimeType", image.mediaType)
                        addProperty("data", image.base64Data)
                    })
                })
            }
            replay.forEach { parts.add(it.deepCopy()) }
            message.blocks.filterIsInstance<ContentBlock.ToolCall>().forEach { call ->
                if (call.id !in replayedCallIds && call.name !in replayedCallNames) {
                    parts.add(JsonObject().apply {
                        add("functionCall", JsonObject().apply {
                            if (call.id in providerIssuedCallIds) addProperty("id", call.id)
                            addProperty("name", call.name)
                            add("args", call.arguments.deepCopy())
                        })
                    })
                }
            }
            message.blocks.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                val functionName = toolNames[result.toolCallId]
                    ?: findToolName(messages, result.toolCallId)
                    ?: "tool"
                parts.add(JsonObject().apply {
                    add("functionResponse", JsonObject().apply {
                        if (result.toolCallId in providerIssuedCallIds) addProperty("id", result.toolCallId)
                        addProperty("name", functionName)
                        add("response", JsonObject().apply {
                            addProperty(if (result.isError) "error" else "result", result.content)
                        })
                    })
                })
            }

            if (parts.size() > 0) {
                add(JsonObject().apply {
                    addProperty("role", if (message.role == MessageRole.ASSISTANT) "model" else "user")
                    add("parts", parts)
                })
            }
        }
    }

    private fun endpoint(): String {
        val model = connection.model.removePrefix("models/")
        return "${connection.baseUrl.trimEnd('/')}/models/${encodePath(model)}:streamGenerateContent?alt=sse"
    }

    private fun mapStopReason(reason: String): StopReason = when (reason.uppercase()) {
        "STOP", "FINISH_REASON_UNSPECIFIED" -> StopReason.COMPLETE
        "MAX_TOKENS" -> StopReason.LENGTH
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
            StopReason.CONTENT_FILTER
        else -> StopReason.UNKNOWN
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var providerIssuedId: Boolean = false,
        var name: String = "",
        var arguments: JsonObject = JsonObject(),
    )
}

private fun textPart(text: String): JsonObject = JsonObject().apply { addProperty("text", text) }

private fun addReplayPart(parts: MutableList<JsonObject>, part: JsonObject) {
    val serialized = Json.stringify(part)
    if (parts.none { Json.stringify(it) == serialized }) parts += part.deepCopy()
}

private fun findToolName(messages: List<ConversationMessage>, callId: String): String? =
    messages.asReversed()
        .asSequence()
        .flatMap { it.blocks.asSequence() }
        .filterIsInstance<ContentBlock.ToolCall>()
        .firstOrNull { it.id == callId }
        ?.name
