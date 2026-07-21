package dev.omnicode.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
                addProperty("version", "0.9.0")
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
            result.getAsJsonArray("tools")?.forEach { item ->
                val tool = item.asJsonObject
                val name = tool.get("name")?.asString?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                tools += McpToolDescriptor(
                    name = name,
                    description = tool.get("description")?.asString.orEmpty().take(MAX_DESCRIPTION_CHARS),
                    inputSchema = tool.getAsJsonObject("inputSchema") ?: emptyObjectSchema(),
                )
            }
            cursor = result.get("nextCursor")?.takeUnless(JsonElement::isJsonNull)?.asString
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
        result.getAsJsonArray("content")?.forEach { item ->
            val block = item.asJsonObject
            when (block.get("type")?.asString) {
                "text" -> block.get("text")?.asString?.let(textParts::add)
                else -> textParts += Json.stringify(block)
            }
        }
        result.get("structuredContent")?.takeUnless(JsonElement::isJsonNull)?.let {
            textParts += Json.stringify(it)
        }
        return McpToolCallResult(
            text = textParts.joinToString("\n").take(MAX_RESULT_CHARS).ifBlank { "MCP tool completed." },
            isError = result.get("isError")?.asBoolean == true,
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
                    message.getAsJsonObject("error")?.let { error ->
                        val code = error.get("code")?.let { runCatching { it.asInt }.getOrNull() }
                        val description = error.get("message")?.let { runCatching { it.asString }.getOrNull() }
                            .orEmpty()
                            .take(300)
                        throw McpProtocolException("MCP $method failed${code?.let { " ($it)" }.orEmpty()}: $description")
                    }
                    return@withTimeout message.getAsJsonObject("result") ?: JsonObject()
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
                val message = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
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
