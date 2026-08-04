package dev.omnicode.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.ModelRequest
import dev.omnicode.model.ModelResponse
import dev.omnicode.model.StopReason
import dev.omnicode.model.TokenUsage
import dev.omnicode.model.providerTextOrNull
import dev.omnicode.util.Json
import java.util.concurrent.ConcurrentHashMap

class OpenAiResponsesProvider(
    private val connection: ProviderConnection,
) : ModelProvider {
    override val id: String = connection.preset.id

    /*
     * The public conversation model intentionally does not expose hidden reasoning.
     * Responses reasoning models still require their opaque reasoning items on the
     * next tool turn, so keep those items transiently and key them by call_id.
     */
    private val pendingReasoningByCallId = ConcurrentHashMap<String, List<JsonObject>>()

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val body = buildBody(request)
        val text = StringBuilder()
        val calls = linkedMapOf<Int, ToolCallAccumulator>()
        val reasoningItems = mutableListOf<JsonObject>()
        var usage = TokenUsage()
        var stopReason = StopReason.UNKNOWN
        var responseId: String? = null
        var terminalReceived = false

        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("Authorization", "Bearer ${connection.apiKey}")
            putAll(connection.extraHeaders)
            request.idempotencyKey?.let { put("Idempotency-Key", it) }
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
            val event = runCatching { Json.parseObject(data) }.getOrNull() ?: return@postSse
            val type = event.stringOrNull("type") ?: eventName.orEmpty()
            responseId = responseId
                ?: event.stringOrNull("response_id")
                ?: event.jsonObjectOrNull("response")?.stringOrNull("id")

            when (type) {
                "response.created", "response.in_progress" -> {
                    responseId = event.jsonObjectOrNull("response")?.stringOrNull("id") ?: responseId
                }

                "response.output_text.delta" -> {
                    event.stringOrNull("delta")?.let { delta ->
                        text.append(delta)
                        onTextDelta(delta)
                    }
                }

                "response.output_item.added", "response.output_item.done" -> {
                    val item = event.jsonObjectOrNull("item") ?: return@postSse
                    when (item.stringOrNull("type")) {
                        "function_call" -> mergeFunctionCall(
                            calls = calls,
                            index = event.intOrNull("output_index") ?: calls.size,
                            item = item,
                            replaceArguments = type == "response.output_item.done",
                        )
                        "reasoning" -> if (type == "response.output_item.done") {
                            reasoningItems += item.deepCopy()
                        }
                    }
                }

                "response.function_call_arguments.delta" -> {
                    val itemId = event.stringOrNull("item_id")
                    val delta = event.stringOrNull("delta")
                    if (itemId == null && delta == null) return@postSse
                    val index = event.intOrNull("output_index") ?: calls.size
                    val call = calls.getOrPut(index) { ToolCallAccumulator() }
                    itemId?.let { call.itemId = it }
                    delta?.let { call.arguments.append(it) }
                }

                "response.function_call_arguments.done" -> {
                    val itemId = event.stringOrNull("item_id")
                    val arguments = event.stringOrNull("arguments")
                    if (itemId == null && arguments == null) return@postSse
                    val index = event.intOrNull("output_index") ?: calls.size
                    val call = calls.getOrPut(index) { ToolCallAccumulator() }
                    itemId?.let { call.itemId = it }
                    arguments?.let {
                        call.arguments.setLength(0)
                        call.arguments.append(it)
                    }
                }

                "response.completed", "response.incomplete" -> {
                    terminalReceived = true
                    val response = event.jsonObjectOrNull("response") ?: event
                    responseId = response.stringOrNull("id") ?: responseId
                    usage = response.jsonObjectOrNull("usage")?.toTokenUsage() ?: usage
                    response.jsonArrayOrNull("output")?.forEachIndexed { index, element ->
                        if (!element.isJsonObject) return@forEachIndexed
                        val item = element.asJsonObject
                        when (item.stringOrNull("type")) {
                            "function_call" -> mergeFunctionCall(calls, index, item, true)
                            "reasoning" -> if (reasoningItems.none { it.stringOrNull("id") == item.stringOrNull("id") }) {
                                reasoningItems += item.deepCopy()
                            }
                        }
                    }
                    stopReason = when {
                        type == "response.incomplete" -> StopReason.LENGTH
                        calls.isNotEmpty() -> StopReason.TOOL_USE
                        else -> StopReason.COMPLETE
                    }
                }

                "response.failed", "response.error", "error" -> {
                    throw providerStreamException(
                        "OpenAI Responses",
                        event.jsonObjectOrNull("response") ?: event,
                        connection,
                    )
                }

                // Forward compatibility: unknown lifecycle and content events are ignored.
                else -> Unit
            }
        }
        if (!terminalReceived) {
            throw ProviderException(
                "OpenAI Responses stream ended before a terminal event",
                billingUncertain = true,
            )
        }

        val blocks = buildList {
            if (text.isNotEmpty()) add(ContentBlock.Text(text.toString()))
            calls.toSortedMap().values.forEachIndexed { index, call ->
                if (call.name.isBlank()) return@forEachIndexed
                val callId = call.callId.ifBlank { call.itemId.ifBlank { "call_$index" } }
                val arguments = runCatching { Json.parseObject(call.arguments.toString()) }.getOrElse { JsonObject() }
                add(ContentBlock.ToolCall(callId, call.name, arguments))
                if (reasoningItems.isNotEmpty()) {
                    pendingReasoningByCallId[callId] = reasoningItems.map { it.deepCopy() }
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
        if (blocks.none { it is ContentBlock.ToolCall }) pendingReasoningByCallId.clear()

        val transportRequestId = responseHeaders.headerValue("x-request-id")
            ?: responseHeaders.headerValue("request-id")
        return ModelResponse(blocks, usage, stopReason, responseId ?: transportRequestId)
    }

    private fun buildBody(request: ModelRequest): JsonObject = JsonObject().apply {
        val reasoning = connection.requireReasoningResolution()
        addProperty("model", connection.model)
        addProperty("stream", true)
        addProperty("max_output_tokens", request.maxOutputTokens)
        addProperty("parallel_tool_calls", false)
        add("input", buildInput(request.messages))
        when (reasoning.wireFormat) {
            ReasoningWireFormat.OMIT -> Unit
            ReasoningWireFormat.OPENAI_RESPONSES -> add("reasoning", JsonObject().apply {
                addProperty("effort", requireNotNull(reasoning.wireValue))
                if (reasoning.openAiProMode) addProperty("mode", "pro")
            })
            else -> throw ProviderException(
                "${connection.preset.displayName} resolved an incompatible reasoning request for the Responses API.",
            )
        }
        if (request.tools.isNotEmpty()) {
            add("tools", JsonArray().apply {
                request.tools.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("type", "function")
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)
                        add("parameters", tool.inputSchema)
                    })
                }
            })
            addProperty("tool_choice", "auto")
        }
    }

    private fun buildInput(messages: List<ConversationMessage>): JsonArray = JsonArray().apply {
        val emittedReasoningIds = mutableSetOf<String>()
        messages.forEach { message ->
            val text = message.blocks.mapNotNull(ContentBlock::providerTextOrNull).joinToString("\n")
            val images = message.blocks.filterIsInstance<ContentBlock.Image>()
            if (text.isNotBlank() || images.isNotEmpty()) {
                add(JsonObject().apply {
                    addProperty("role", message.role.openAiRole())
                    if (images.isEmpty()) {
                        addProperty("content", text)
                    } else {
                        add("content", JsonArray().apply {
                            if (text.isNotBlank()) add(JsonObject().apply {
                                addProperty("type", "input_text")
                                addProperty("text", text)
                            })
                            images.forEach { image -> add(JsonObject().apply {
                                addProperty("type", "input_image")
                                addProperty("image_url", image.dataUrl())
                            }) }
                        })
                    }
                })
            }

            message.blocks.filterIsInstance<ContentBlock.ToolCall>().forEach { call ->
                pendingReasoningByCallId[call.id].orEmpty().forEach { reasoning ->
                    val reasoningId = reasoning.stringOrNull("id") ?: Json.stringify(reasoning)
                    if (emittedReasoningIds.add(reasoningId)) add(reasoning.deepCopy())
                }
                add(JsonObject().apply {
                    addProperty("type", "function_call")
                    addProperty("call_id", call.id)
                    addProperty("name", call.name)
                    addProperty("arguments", Json.stringify(call.arguments))
                })
            }

            message.blocks.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                add(JsonObject().apply {
                    addProperty("type", "function_call_output")
                    addProperty("call_id", result.toolCallId)
                    addProperty("output", result.content)
                })
            }
        }
    }

    private fun mergeFunctionCall(
        calls: MutableMap<Int, ToolCallAccumulator>,
        index: Int,
        item: JsonObject,
        replaceArguments: Boolean,
    ) {
        val call = calls.getOrPut(index) { ToolCallAccumulator() }
        item.stringOrNull("id")?.let { call.itemId = it }
        item.stringOrNull("call_id")?.let { call.callId = it }
        item.stringOrNull("name")?.let { call.name = mergeStreamedValue(call.name, it) }
        item.get("arguments")?.takeUnless { it.isJsonNull }?.let { arguments ->
            val value = if (arguments.isJsonPrimitive) arguments.asString else Json.stringify(arguments)
            if (replaceArguments || call.arguments.isEmpty()) {
                call.arguments.setLength(0)
                call.arguments.append(value)
            } else {
                call.arguments.append(value)
            }
        }
    }

    private fun endpoint(): String = "${connection.baseUrl.trimEnd('/')}/responses"

    private data class ToolCallAccumulator(
        var itemId: String = "",
        var callId: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )
}

private fun JsonObject.toTokenUsage(): TokenUsage = TokenUsage(
    inputTokens = longOrZero("input_tokens"),
    outputTokens = longOrZero("output_tokens"),
)

internal fun Map<String, List<String>>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

internal fun mergeStreamedValue(current: String, incoming: String): String = when {
    current.isBlank() -> incoming
    incoming == current || current.endsWith(incoming) -> current
    incoming.startsWith(current) -> incoming
    else -> current + incoming
}
