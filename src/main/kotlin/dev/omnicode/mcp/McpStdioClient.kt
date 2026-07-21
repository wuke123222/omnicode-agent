package dev.omnicode.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.OMNICODE_VERSION
import com.intellij.execution.process.OSProcessUtil
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.util.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpToolCallResult(
    val text: String,
    val isError: Boolean,
)

internal data class McpTimeouts(
    val requestMs: Long = 15_000L,
    val toolCallMs: Long = 120_000L,
) {
    init {
        require(requestMs > 0)
        require(toolCallMs > 0)
    }
}

fun interface McpProcessLauncher {
    suspend fun launch(config: McpServerConfig): Process
}

interface McpClient : Closeable {
    val config: McpServerConfig
    suspend fun listTools(): List<McpToolDescriptor>
    suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult
}

open class McpProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class McpStdioClient private constructor(
    override val config: McpServerConfig,
    private val process: Process,
    private val timeouts: McpTimeouts,
) : McpClient {
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
    private val requestMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<McpProtocolException?>()
    private val responses = Channel<JsonObject>(RESPONSE_QUEUE_CAPACITY)
    private val stderrTail = BoundedTail(MAX_STDERR_CHARS)
    private val stdoutThread: Thread
    private val stderrThread: Thread

    init {
        stdoutThread = Thread(::readResponses, "OmniCode MCP stdout ${config.name}").apply {
            isDaemon = true
        }
        stderrThread = Thread({
            runCatching {
                InputStreamReader(process.errorStream, StandardCharsets.UTF_8).use { source ->
                    val buffer = CharArray(1_024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        stderrTail.append(buffer, read)
                    }
                }
            }
        }, "OmniCode MCP stderr ${config.name}").apply {
            isDaemon = true
        }
        stderrThread.start()
        stdoutThread.start()
    }

    suspend fun initialize() {
        val params = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "OmniCode")
                addProperty("version", OMNICODE_VERSION)
            })
        }
        request("initialize", params, timeouts.requestMs)
        notify("notifications/initialized", JsonObject())
    }

    override suspend fun listTools(): List<McpToolDescriptor> {
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val params = JsonObject().apply { cursor?.let { addProperty("cursor", it) } }
            val result = request("tools/list", params, timeouts.requestMs)
            val toolItems = result.get("tools")
            if (toolItems != null && !toolItems.isJsonNull && !toolItems.isJsonArray) {
                throw McpProtocolException("MCP tools/list returned an invalid tools field; expected an array")
            }
            toolItems?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { item ->
                val tool = item.asObjectOrNull() ?: return@forEach
                val name = tool.get("name").asStringOrNull()?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                tools += McpToolDescriptor(
                    name = name,
                    description = tool.get("description").asStringOrNull().orEmpty().take(MAX_DESCRIPTION_CHARS),
                    inputSchema = tool.get("inputSchema").asObjectOrNull() ?: emptyObjectSchema(),
                )
            }
            val nextCursor = result.get("nextCursor")
            cursor = when {
                nextCursor == null || nextCursor.isJsonNull -> null
                else -> nextCursor.asStringOrNull()
                    ?: throw McpProtocolException("MCP tools/list returned an invalid nextCursor; expected a string")
            }
            if (cursor.isNullOrBlank()) return tools.distinctBy(McpToolDescriptor::name)
        }
        return tools.distinctBy(McpToolDescriptor::name)
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult {
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments.deepCopy())
        }
        val result = request("tools/call", params, timeouts.toolCallMs)
        val textParts = mutableListOf<String>()
        val contentItems = result.get("content")
        if (contentItems != null && !contentItems.isJsonNull && !contentItems.isJsonArray) {
            throw McpProtocolException("MCP tools/call returned an invalid content field; expected an array")
        }
        contentItems?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { item ->
            val block = item.asObjectOrNull() ?: return@forEach
            when (block.get("type").asStringOrNull()) {
                "text" -> block.get("text").asStringOrNull()?.let(textParts::add)
                else -> textParts += Json.stringify(block)
            }
        }
        result.get("structuredContent")?.takeUnless(JsonElement::isJsonNull)?.let {
            textParts += Json.stringify(it)
        }
        val isError = result.get("isError").let { value ->
            when {
                value == null || value.isJsonNull -> false
                else -> value.asBooleanOrNull()
                    ?: throw McpProtocolException("MCP tools/call returned an invalid isError; expected a boolean")
            }
        }
        return McpToolCallResult(
            text = textParts.joinToString("\n").take(MAX_RESULT_CHARS).ifBlank { "MCP tool completed." },
            isError = isError,
        )
    }

    private suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMs: Long,
    ): JsonObject = requestMutex.withLock {
        try {
            withTimeout(timeoutMs) {
                val id = withContext(Dispatchers.IO) {
                    ensureAlive()
                    nextId.getAndIncrement().also { requestId ->
                        send(JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            addProperty("id", requestId)
                            addProperty("method", method)
                            add("params", params)
                        })
                    }
                }
                while (true) {
                    val message = responses.receive()
                    val responseId = message.get("id")
                        ?.takeUnless(JsonElement::isJsonNull)
                        ?.let { runCatching { it.asLong }.getOrNull() }
                    if (responseId != id) continue
                    val errorValue = message.get("error")
                    if (errorValue != null && !errorValue.isJsonNull) {
                        val error = errorValue.asObjectOrNull()
                            ?: throw McpProtocolException("MCP $method returned an invalid JSON-RPC error object")
                        val code = error.get("code")?.let { runCatching { it.asInt }.getOrNull() }
                        val description = error.get("message").asStringOrNull()
                            .orEmpty()
                            .take(300)
                        throw McpProtocolException("MCP $method failed${code?.let { " ($it)" }.orEmpty()}: $description")
                    }
                    val result = message.get("result")
                    return@withTimeout result.asObjectOrNull()
                        ?: throw McpProtocolException("MCP $method returned a missing or non-object result")
                }
                @Suppress("UNREACHABLE_CODE")
                JsonObject()
            }
        } catch (timeout: TimeoutCancellationException) {
            val error = McpProtocolException("MCP $method timed out after $timeoutMs ms", timeout)
            closeTransport(error)
            throw error
        } catch (cancelled: CancellationException) {
            closeTransport(McpProtocolException("MCP $method was cancelled", cancelled))
            throw cancelled
        } catch (error: McpProtocolException) {
            throw error
        } catch (error: Throwable) {
            val protocolError = McpProtocolException("MCP $method transport failed", error)
            closeTransport(protocolError)
            throw protocolError
        }
    }

    private fun readResponses() {
        var invalidLines = 0
        try {
            while (!closed.get()) {
                val line = readProtocolLine() ?: throw McpProtocolException(
                    "MCP server '${config.name}' closed its output${stderrSuffix()}",
                )
                val message = runCatching { JsonParser.parseString(line) }.getOrNull().asObjectOrNull()
                if (message == null) {
                    invalidLines++
                    if (invalidLines > MAX_INVALID_LINES) {
                        throw McpProtocolException("MCP server '${config.name}' produced invalid protocol output")
                    }
                    continue
                }
                if (responses.trySend(message).isFailure && !closed.get()) {
                    throw McpProtocolException(
                        "MCP server '${config.name}' exceeded the $RESPONSE_QUEUE_CAPACITY-message response queue",
                    )
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                val protocolError = error as? McpProtocolException
                    ?: McpProtocolException("MCP server '${config.name}' output failed", error)
                closeTransport(protocolError)
            }
        }
    }

    private fun readProtocolLine(): String? {
        val line = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            val char = next.toChar()
            if (char == '\n') {
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            if (line.length >= MAX_PROTOCOL_LINE_CHARS) {
                throw McpProtocolException(
                    "MCP server '${config.name}' exceeded the $MAX_PROTOCOL_LINE_CHARS-character protocol line limit",
                )
            }
            line.append(char)
        }
    }

    private suspend fun notify(method: String, params: JsonObject) = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            try {
                ensureAlive()
                send(JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("method", method)
                    add("params", params)
                })
            } catch (error: Throwable) {
                val protocolError = McpProtocolException("MCP $method notification failed", error)
                closeTransport(protocolError)
                throw protocolError
            }
        }
    }

    private fun send(message: JsonObject) {
        writer.write(Json.stringify(message))
        writer.newLine()
        writer.flush()
    }

    private fun ensureAlive() {
        if (closed.get() || !process.isAlive) {
            terminalFailure.get()?.let { throw it }
            throw McpProtocolException("MCP server '${config.name}' is not running${stderrSuffix()}")
        }
    }

    private fun stderrSuffix(): String = stderrTail.text().trim().takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()

    override fun close() = closeTransport(McpProtocolException("MCP client '${config.name}' was closed"))

    private fun closeTransport(cause: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        val protocolCause = cause as? McpProtocolException
            ?: McpProtocolException("MCP client '${config.name}' transport closed", cause)
        terminalFailure.compareAndSet(null, protocolCause)
        responses.close(protocolCause)
        if (process.isAlive) runCatching { process.destroy() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        if (process.isAlive) runCatching { OSProcessUtil.killProcessTree(process) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        runCatching { writer.close() }
        runCatching { reader.close() }
        stdoutThread.interrupt()
        stderrThread.interrupt()
    }

    companion object {
        suspend fun connect(config: McpServerConfig, launcher: McpProcessLauncher): McpStdioClient {
            return connect(config, launcher, McpTimeouts())
        }

        internal suspend fun connect(
            config: McpServerConfig,
            launcher: McpProcessLauncher,
            timeouts: McpTimeouts,
        ): McpStdioClient {
            val process = launcher.launch(config)
            return McpStdioClient(config, process, timeouts).also { client ->
                try {
                    client.initialize()
                } catch (error: Throwable) {
                    client.close()
                    throw error
                }
            }
        }

        private fun emptyObjectSchema(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject())
        }

        private const val PROTOCOL_VERSION = "2025-11-25"
        private const val MAX_PAGES = 10
        private const val MAX_INVALID_LINES = 8
        private const val RESPONSE_QUEUE_CAPACITY = 64
        private const val MAX_PROTOCOL_LINE_CHARS = 1_048_576
        private const val MAX_STDERR_CHARS = 4_000
        private const val MAX_DESCRIPTION_CHARS = 1_000
        private const val MAX_RESULT_CHARS = 24_000
    }
}

private fun JsonElement?.asObjectOrNull(): JsonObject? =
    this?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject

private fun JsonElement?.asStringOrNull(): String? {
    val primitive = this?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return primitive.takeIf { it.isString }?.asString
}

private fun JsonElement?.asBooleanOrNull(): Boolean? {
    val primitive = this?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return primitive.takeIf { it.isBoolean }?.asBoolean
}

private class BoundedTail(private val limit: Int) {
    private val value = StringBuilder()

    @Synchronized
    fun append(chars: CharArray, length: Int) {
        value.append(chars, 0, length)
        if (value.length > limit) value.delete(0, value.length - limit)
    }

    @Synchronized
    fun text(): String = value.toString()
}
