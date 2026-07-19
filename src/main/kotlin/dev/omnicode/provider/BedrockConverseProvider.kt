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

class BedrockConverseProvider(
    private val connection: ProviderConnection,
) : ModelProvider {
    override val id: String = connection.preset.id

    override suspend fun complete(
        request: ModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): ModelResponse {
        val region = resolvedRegion()
        val url = endpoint(region)
        val body = Json.stringify(buildBody(request))
        val baseHeaders = buildMap {
            put("Content-Type", "application/json")
            connection.extraHeaders.forEach { (name, value) ->
                if (!name.equals("Authorization", true) && !name.equals("Host", true)) put(name, value)
            }
        }
        val credentials = resolveAwsCredentials()
        val headers = when {
            credentials != null -> AwsSigV4.signPost(
                url = url,
                body = body,
                headers = baseHeaders,
                region = region,
                service = "bedrock",
                credentials = credentials,
            )
            connection.apiKey.isNotBlank() -> baseHeaders + ("Authorization" to "Bearer ${connection.apiKey}")
            else -> throw ProviderException(
                "AWS Bedrock credentials are missing. Configure access key/secret key, a Bedrock API key, or AWS_* environment variables.",
            )
        }

        val result = HttpTransport.postJson(
            url = url,
            headers = headers,
            body = body,
            timeoutSeconds = connection.requestTimeoutSeconds,
            sensitiveValues = connection.sensitiveValues() + listOfNotNull(
                credentials?.accessKeyId,
                credentials?.secretAccessKey,
                credentials?.sessionToken,
            ),
        )
        val response = runCatching { Json.parseObject(result.body) }.getOrElse { error ->
            throw ProviderException("AWS Bedrock returned an invalid JSON response", result.statusCode, cause = error)
        }
        if (response.has("error")) throw providerStreamException("AWS Bedrock", response, connection)

        val blocks = mutableListOf<ContentBlock>()
        response.jsonObjectOrNull("output")
            ?.jsonObjectOrNull("message")
            ?.getAsJsonArray("content")
            ?.forEachIndexed { index, element ->
                if (!element.isJsonObject) return@forEachIndexed
                val content = element.asJsonObject
                content.stringOrNull("text")?.let { value ->
                    blocks += ContentBlock.Text(value)
                    onTextDelta(value)
                }
                content.jsonObjectOrNull("toolUse")?.let { toolUse ->
                    val name = toolUse.stringOrNull("name") ?: return@let
                    val callId = toolUse.stringOrNull("toolUseId") ?: "bedrock_call_$index"
                    val input = toolUse.get("input")?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy()
                        ?: JsonObject()
                    blocks += ContentBlock.ToolCall(callId, name, input)
                }
            }

        val usage = response.jsonObjectOrNull("usage")?.let {
            TokenUsage(it.longOrZero("inputTokens"), it.longOrZero("outputTokens"))
        } ?: TokenUsage()
        var stopReason = mapStopReason(response.stringOrNull("stopReason"))
        if (
            blocks.any { it is ContentBlock.ToolCall } &&
            stopReason != StopReason.LENGTH &&
            stopReason != StopReason.CONTENT_FILTER
        ) {
            stopReason = StopReason.TOOL_USE
        }
        return ModelResponse(
            blocks = blocks,
            usage = usage,
            stopReason = stopReason,
            providerRequestId = result.headers.headerValue("x-amzn-requestid")
                ?: result.headers.headerValue("x-amz-request-id"),
        )
    }

    private fun buildBody(request: ModelRequest): JsonObject = JsonObject().apply {
        val systemText = request.messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.blocks.filterIsInstance<ContentBlock.Text>() }
            .joinToString("\n") { it.text }
        if (systemText.isNotBlank()) {
            add("system", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", systemText) })
            })
        }
        add("messages", buildMessages(request.messages))
        add("inferenceConfig", JsonObject().apply {
            addProperty("maxTokens", request.maxOutputTokens)
            addProperty("temperature", request.temperature)
        })
        if (request.tools.isNotEmpty()) {
            add("toolConfig", JsonObject().apply {
                add("tools", JsonArray().apply {
                    request.tools.forEach { tool ->
                        add(JsonObject().apply {
                            add("toolSpec", JsonObject().apply {
                                addProperty("name", tool.name)
                                addProperty("description", tool.description)
                                add("inputSchema", JsonObject().apply {
                                    add("json", tool.inputSchema.deepCopy())
                                })
                            })
                        })
                    }
                })
                add("toolChoice", JsonObject().apply { add("auto", JsonObject()) })
            })
        }
    }

    private fun buildMessages(messages: List<ConversationMessage>): JsonArray = JsonArray().apply {
        messages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            val content = JsonArray()
            message.blocks.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> if (block.text.isNotBlank()) {
                        content.add(JsonObject().apply { addProperty("text", block.text) })
                    }
                    is ContentBlock.Image -> content.add(JsonObject().apply {
                        add("image", JsonObject().apply {
                            addProperty("format", block.mediaType.substringAfter('/', "png"))
                            add("source", JsonObject().apply { addProperty("bytes", block.base64Data) })
                        })
                    })
                    is ContentBlock.ToolCall -> content.add(JsonObject().apply {
                        add("toolUse", JsonObject().apply {
                            addProperty("toolUseId", block.id)
                            addProperty("name", block.name)
                            add("input", block.arguments.deepCopy())
                        })
                    })
                    is ContentBlock.ToolResult -> content.add(JsonObject().apply {
                        add("toolResult", JsonObject().apply {
                            addProperty("toolUseId", block.toolCallId)
                            add("content", JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", block.content) })
                            })
                            addProperty("status", if (block.isError) "error" else "success")
                        })
                    })
                }
            }
            if (content.size() > 0) {
                add(JsonObject().apply {
                    addProperty("role", if (message.role == MessageRole.ASSISTANT) "assistant" else "user")
                    add("content", content)
                })
            }
        }
    }

    private fun resolvedRegion(): String = connection.region.takeIf { it.isNotBlank() }
        ?: System.getenv("AWS_REGION")?.takeIf { it.isNotBlank() }
        ?: System.getenv("AWS_DEFAULT_REGION")?.takeIf { it.isNotBlank() }
        ?: "us-east-1"

    private fun resolveAwsCredentials(): AwsCredentials? {
        if (connection.apiKey.isNotBlank() && connection.secondarySecret.isNotBlank()) {
            return AwsCredentials(
                connection.apiKey,
                connection.secondarySecret,
                connection.sessionToken.takeIf { it.isNotBlank() },
            )
        }
        val accessKey = System.getenv("AWS_ACCESS_KEY_ID").orEmpty()
        val secretKey = System.getenv("AWS_SECRET_ACCESS_KEY").orEmpty()
        if (accessKey.isBlank() || secretKey.isBlank()) return null
        return AwsCredentials(
            accessKey,
            secretKey,
            System.getenv("AWS_SESSION_TOKEN")?.takeIf { it.isNotBlank() },
        )
    }

    private fun endpoint(region: String): String {
        val base = connection.baseUrl.replace("{region}", region).trimEnd('/')
        return "$base/model/${encodePath(connection.model)}/converse"
    }

    private fun mapStopReason(reason: String?): StopReason = when (reason?.lowercase()) {
        "end_turn", "stop_sequence" -> StopReason.COMPLETE
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens", "model_context_window_exceeded" -> StopReason.LENGTH
        "content_filtered", "guardrail_intervened" -> StopReason.CONTENT_FILTER
        else -> StopReason.UNKNOWN
    }
}
