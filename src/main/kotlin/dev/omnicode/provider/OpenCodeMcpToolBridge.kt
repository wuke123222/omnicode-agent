package dev.omnicode.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ToolDefinition
import dev.omnicode.util.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A one-turn, loopback-only MCP adapter that makes selected OmniCode host tools real OpenCode tools.
 *
 * It never executes a tool. The first validated tools/call is captured and translated back into
 * AgentEngine's normal ToolCall path, where approval, audit, PasswordSafe and checkpoints apply.
 */
internal class OpenCodeMcpToolBridge private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val path: String,
    private val serverName: String,
    private val allowedTools: List<ToolDefinition>,
) : AutoCloseable {
    private val captured = AtomicReference<ContentBlock.ToolCall?>()
    private val closed = AtomicBoolean(false)
    private val eventStreamConnected = AtomicBoolean(false)
    private val diagnostics = StringBuilder()

    val endpoint: URI = URI("http", null, LOOPBACK_HOST, server.address.port, path, null, null)

    init {
        server.createContext(path, ::handle)
        server.executor = executor
        server.start()
    }

    fun nativeToolName(toolName: String): String = "${serverName}_$toolName"

    fun hasCapturedToolCall(): Boolean = captured.get() != null

    fun capturedToolCall(): ContentBlock.ToolCall? = captured.get()

    fun diagnosticSummary(): String = synchronized(diagnostics) { diagnostics.toString().takeLast(4_000) }

    fun applyToEnvironment(environment: MutableMap<String, String>) {
        val existing = environment[OPENCODE_CONFIG_CONTENT].orEmpty()
        require(existing.length <= MAX_INLINE_CONFIG_CHARS) { "OpenCode inline config exceeds the safe merge limit" }
        val root = if (existing.isBlank()) {
            JsonObject()
        } else {
            runCatching { Json.parseObject(existing) }.getOrElse {
                throw IllegalArgumentException("OpenCode inline config is not valid JSON", it)
            }.deepCopy()
        }
        val mcp = root.get("mcp")?.takeUnless(JsonElement::isJsonNull)?.let { value ->
            require(value.isJsonObject) { "OpenCode inline mcp config must be an object" }
            value.asJsonObject.deepCopy()
        } ?: JsonObject()
        mcp.add(serverName, JsonObject().apply {
            addProperty("type", "remote")
            addProperty("url", endpoint.toASCIIString())
            addProperty("enabled", true)
            addProperty("oauth", false)
            addProperty("timeout", MCP_DISCOVERY_TIMEOUT_MILLIS)
        })
        root.add("mcp", mcp)
        val tools = root.get("tools")?.takeUnless(JsonElement::isJsonNull)?.let { value ->
            require(value.isJsonObject) { "OpenCode inline tools config must be an object" }
            value.asJsonObject.deepCopy()
        } ?: JsonObject()
        tools.addProperty("${serverName}_*", true)
        root.add("tools", tools)
        environment[OPENCODE_CONFIG_CONTENT] = Json.stringify(root)
        appendNoProxy(environment, LOOPBACK_HOST)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            recordDiagnostic(exchange.requestMethod)
            val expectedHost = "$LOOPBACK_HOST:${server.address.port}"
            if (!exchange.requestHeaders.getFirst("Host").orEmpty().equals(expectedHost, ignoreCase = true)) {
                exchange.respond(400, rpcError(null, -32600, "Invalid loopback host"))
                return
            }
            if (exchange.requestMethod == "GET") {
                serveEventStream(exchange)
                return
            }
            if (exchange.requestMethod != "POST") {
                exchange.responseHeaders.set("Allow", "GET, POST")
                exchange.respond(405)
                return
            }
            if (!exchange.requestHeaders.getFirst("Content-Type").orEmpty().lowercase().startsWith("application/json")) {
                exchange.respond(415, rpcError(null, -32600, "Content-Type must be application/json"))
                return
            }
            val bytes = exchange.requestBody.use { it.readNBytes(MAX_REQUEST_BYTES + 1) }
            if (bytes.size > MAX_REQUEST_BYTES) {
                exchange.respond(413, rpcError(null, -32600, "Request too large"))
                return
            }
            val message = runCatching { Json.parseObject(bytes.toString(StandardCharsets.UTF_8)) }.getOrNull()
            if (message == null || message.get("jsonrpc")?.asString != "2.0") {
                exchange.respond(400, rpcError(message?.get("id"), -32700, "Invalid JSON-RPC request"))
                return
            }
            val id = message.get("id")
            val method = message.get("method")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            recordDiagnostic("jsonrpc ${method.orEmpty()}")
            if (id == null || id.isJsonNull) {
                exchange.respond(202)
                return
            }
            when (method) {
                "initialize" -> {
                    val requested = message.getAsJsonObject("params")?.get("protocolVersion")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        ?.takeIf(PROTOCOL_VERSION::matches)
                        ?: DEFAULT_PROTOCOL_VERSION
                    exchange.respond(200, rpcResult(id, JsonObject().apply {
                        addProperty("protocolVersion", requested)
                        add("capabilities", JsonObject().apply {
                            add("tools", JsonObject().apply { addProperty("listChanged", false) })
                        })
                        add("serverInfo", JsonObject().apply {
                            addProperty("name", "OmniCode Host Tools")
                            addProperty("version", "1")
                        })
                    }))
                }
                "tools/list" -> exchange.respond(200, rpcResult(id, JsonObject().apply {
                    add("tools", JsonArray().apply {
                        allowedTools.forEach { definition ->
                            add(JsonObject().apply {
                                addProperty("name", definition.name)
                                addProperty("description", definition.description.take(MAX_DESCRIPTION_CHARS))
                                add("inputSchema", definition.inputSchema.deepCopy())
                            })
                        }
                    })
                }))
                "tools/call" -> handleToolCall(exchange, id, message.getAsJsonObject("params"))
                "ping" -> exchange.respond(200, rpcResult(id, JsonObject()))
                else -> exchange.respond(200, rpcError(id, -32601, "Method not found"))
            }
        } catch (_: Throwable) {
            recordDiagnostic("bridge handler failed")
            runCatching { exchange.respond(500, rpcError(null, -32603, "Internal bridge error")) }
        } finally {
            exchange.close()
        }
    }

    /** OpenCode keeps this optional Streamable HTTP notification channel open before tools/list. */
    private fun serveEventStream(exchange: HttpExchange) {
        if (!eventStreamConnected.compareAndSet(false, true)) {
            exchange.respond(409)
            return
        }
        exchange.responseHeaders.set("Content-Type", "text/event-stream")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)
        try {
            exchange.responseBody.use { output ->
                output.write(": omnicode connected\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
                while (!closed.get()) {
                    Thread.sleep(SSE_HEARTBEAT_MILLIS)
                    output.write(": keep-alive\n\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: java.io.IOException) {
            // The OpenCode process or bridge owner closed the one-turn event stream.
        } finally {
            eventStreamConnected.set(false)
        }
    }

    private fun recordDiagnostic(message: String) {
        synchronized(diagnostics) {
            if (diagnostics.length < 8_000) diagnostics.appendLine(message.take(500))
        }
    }

    private fun handleToolCall(exchange: HttpExchange, id: JsonElement, params: JsonObject?) {
        val name = params?.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
        val arguments = params?.get("arguments")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val call = arguments?.let { validatedCliBridgeToolCall(name, it, allowedTools) }
        if (call == null) {
            exchange.respond(200, rpcResult(id, toolResult("OmniCode 拒绝了无效或未授权的工具请求。", true)))
            return
        }
        if (!captured.compareAndSet(null, call)) {
            exchange.respond(200, rpcResult(id, toolResult("本轮只允许一个 OmniCode 工具请求。", true)))
            return
        }
        exchange.respond(
            200,
            rpcResult(
                id,
                toolResult("请求已交给 OmniCode IDE 审批；请结束当前模型步并等待下一轮结果。", false),
            ),
        )
    }

    override fun close() {
        closed.set(true)
        runCatching { server.stop(0) }
        executor.shutdownNow()
    }

    companion object {
        fun start(allowedTools: List<ToolDefinition>): OpenCodeMcpToolBridge {
            require(allowedTools.isNotEmpty()) { "OpenCode bridge requires at least one tool" }
            require(allowedTools.size <= 64 && allowedTools.all { cliBridgeToolName(it.name) } &&
                allowedTools.map { it.name }.distinct().size == allowedTools.size) {
                "OpenCode bridge tool is not allowlisted"
            }
            val address = InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0)
            val server = HttpServer.create(address, 0)
            val executor = Executors.newFixedThreadPool(MAX_BRIDGE_THREADS) { runnable ->
                Thread(runnable, "OmniCode OpenCode MCP bridge").apply { isDaemon = true }
            }
            val token = randomToken(24)
            return try {
                OpenCodeMcpToolBridge(
                    server = server,
                    executor = executor,
                    path = "/omnicode/tools/$token",
                    serverName = "omnicode_host_${token.take(12)}",
                    allowedTools = allowedTools,
                )
            } catch (error: Throwable) {
                executor.shutdownNow()
                runCatching { server.stop(0) }
                throw error
            }
        }

        private fun randomToken(bytes: Int): String = ByteArray(bytes)
            .also(SECURE_RANDOM::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        private val SECURE_RANDOM = SecureRandom()
        private val PROTOCOL_VERSION = Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2}")
        private const val DEFAULT_PROTOCOL_VERSION = "2025-11-25"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val OPENCODE_CONFIG_CONTENT = "OPENCODE_CONFIG_CONTENT"
        private const val MAX_INLINE_CONFIG_CHARS = 256_000
        private const val MAX_REQUEST_BYTES = 256 * 1_024
        private const val MAX_DESCRIPTION_CHARS = 1_000
        private const val MCP_DISCOVERY_TIMEOUT_MILLIS = 5_000
        private const val SSE_HEARTBEAT_MILLIS = 5_000L
        private const val MAX_BRIDGE_THREADS = 4
    }
}

private fun rpcResult(id: JsonElement, result: JsonObject): JsonObject = JsonObject().apply {
    addProperty("jsonrpc", "2.0")
    add("id", id.deepCopy())
    add("result", result)
}

private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = JsonObject().apply {
    addProperty("jsonrpc", "2.0")
    if (id == null) add("id", com.google.gson.JsonNull.INSTANCE) else add("id", id.deepCopy())
    add("error", JsonObject().apply {
        addProperty("code", code)
        addProperty("message", message.take(200))
    })
}

private fun toolResult(text: String, isError: Boolean): JsonObject = JsonObject().apply {
    add("content", JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        })
    })
    addProperty("isError", isError)
}

private fun HttpExchange.respond(status: Int, body: JsonObject? = null) {
    responseHeaders.set("Cache-Control", "no-store")
    responseHeaders.set("Pragma", "no-cache")
    if (body == null) {
        sendResponseHeaders(status, -1)
        return
    }
    val bytes = Json.stringify(body).toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun appendNoProxy(environment: MutableMap<String, String>, host: String) {
    listOf("NO_PROXY", "no_proxy").forEach { key ->
        val existing = environment[key].orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        environment[key] = (existing + host).distinct().joinToString(",")
    }
}
