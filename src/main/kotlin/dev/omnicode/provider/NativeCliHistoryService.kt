package dev.omnicode.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A bounded, read-only reference to one session owned by a local coding CLI. */
internal data class NativeCliHistoryEntry(
    val engineId: String,
    val sessionId: String,
    val title: String,
    val updatedAt: Instant,
)

/** Only visible user/assistant text is imported; reasoning, tools, images and credentials stay out. */
internal data class NativeCliTranscript(
    val entry: NativeCliHistoryEntry,
    val messages: List<ConversationMessage>,
)

/**
 * Reads native history only after an explicit UI action. Project scans and dependency probes never
 * call this service. Every provider command is an argv list with a hard timeout/output bound.
 */
internal class NativeCliHistoryService(private val workingDirectory: Path) {
    suspend fun list(engineId: String): List<NativeCliHistoryEntry> {
        val engine = historyEngine(engineId)
        return when (engine.historyDiscovery) {
            LocalHistoryDiscovery.CODEX_APP_SERVER -> CodexHistoryClient(workingDirectory).use { it.list() }
            LocalHistoryDiscovery.OPENCODE_JSON -> listOpenCode(engine)
            LocalHistoryDiscovery.NONE -> throw ProviderException(
                "${engine.displayName} 当前版本没有稳定的只读历史协议，OmniCode 不会扫描或猜测其私有文件。",
                retryableOverride = false,
            )
        }
    }

    suspend fun read(engineId: String, sessionId: String): NativeCliTranscript {
        require(SAFE_NATIVE_HISTORY_ID.matches(sessionId)) { "Invalid native history id" }
        val engine = historyEngine(engineId)
        return when (engine.historyDiscovery) {
            LocalHistoryDiscovery.CODEX_APP_SERVER -> CodexHistoryClient(workingDirectory).use { it.read(sessionId) }
            LocalHistoryDiscovery.OPENCODE_JSON -> readOpenCode(engine, sessionId)
            LocalHistoryDiscovery.NONE -> throw ProviderException(
                "${engine.displayName} 当前版本不支持安全导入历史。",
                retryableOverride = false,
            )
        }
    }

    private fun historyEngine(engineId: String): LocalAgentEngineContract =
        LocalAgentEngineRegistry.all.firstOrNull { it.id == engineId && it.supportsNativeHistory }
            ?: throw ProviderException("该本地引擎不支持原生历史导入。", retryableOverride = false)

    private suspend fun listOpenCode(engine: LocalAgentEngineContract): List<NativeCliHistoryEntry> {
        val json = runBoundedCli(engine, listOf("session", "list", "--format", "json", "--max-count", MAX_HISTORY_ITEMS.toString()))
        return parseOpenCodeHistoryList(json, workingDirectory)
    }

    private suspend fun readOpenCode(engine: LocalAgentEngineContract, sessionId: String): NativeCliTranscript {
        val json = runBoundedCli(engine, listOf("export", sessionId), maxStdoutBytes = MAX_TRANSCRIPT_JSON_BYTES)
        return parseOpenCodeTranscript(json, workingDirectory, sessionId)
    }

    private suspend fun runBoundedCli(
        engine: LocalAgentEngineContract,
        arguments: List<String>,
        maxStdoutBytes: Int = MAX_HISTORY_JSON_BYTES,
    ): String = withContext(Dispatchers.IO) {
        val executable = CliToolDiscovery.resolveExecutable(engine.tool, null)
            ?: throw ProviderException("找不到 ${engine.displayName} CLI，请先在依赖页重新检测。", retryableOverride = false)
        val process = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + arguments)
            .directory(workingDirectory.toAbsolutePath().normalize().toFile())
            .let { builder ->
                CliToolDiscovery.applyRuntimePath(builder.environment(), executable)
                builder.start()
            }
        runCatching { process.outputStream.close() }
        val stdout = BoundedProcessStream(process, process.inputStream, maxStdoutBytes, "stdout")
        val stderr = BoundedProcessStream(process, process.errorStream, MAX_HISTORY_ERROR_BYTES, "stderr")
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) terminateProcessTree(process)
        }
        try {
            val finished = process.waitFor(HISTORY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                terminateProcessTree(process)
                throw ProviderException("${engine.displayName} 读取历史超过 ${HISTORY_TIMEOUT_SECONDS} 秒，已停止。", retryableOverride = false)
            }
            stdout.join()
            stderr.join()
            if (stdout.overflowed) throw ProviderException("${engine.displayName} 历史输出超过安全上限。", retryableOverride = false)
            val errorText = stderr.text().trim().take(MAX_HISTORY_ERROR_BYTES)
            if (process.exitValue() != 0) {
                throw ProviderException(
                    "${engine.displayName} 无法读取历史${errorText.lineSequence().firstOrNull()?.let { "：$it" }.orEmpty()}",
                    retryableOverride = false,
                )
            }
            stdout.text()
        } finally {
            cancellation?.dispose()
            terminateProcessTree(process)
            stdout.join()
            stderr.join()
        }
    }
}

/** Minimal JSON-RPC client dedicated to Codex's read-only thread/list and thread/read methods. */
private class CodexHistoryClient(private val workingDirectory: Path) : AutoCloseable {
    private val process: Process
    private val lines = Channel<String>(CODEX_EVENT_QUEUE_CAPACITY)
    private val writer: java.io.BufferedWriter
    private val requestIds = AtomicLong(0)
    private val readerThread: Thread

    init {
        val executable = CliToolDiscovery.resolveExecutable(CliTool.CODEX, System.getenv("OMNICODE_CODEX_PATH"))
            ?: throw ProviderException("找不到 Codex CLI，请先在依赖页重新检测。", retryableOverride = false)
        // History is read-only and must not be delayed by unrelated user-global MCP startup.
        // CLI overrides are process-local: the user's config and credentials are never changed.
        val builder = ProcessBuilder(
            CliToolDiscovery.launchCommand(executable) + codexMcpDisableOverrides() + listOf("app-server", "--stdio"),
        )
            .directory(workingDirectory.toAbsolutePath().normalize().toFile())
        CliToolDiscovery.applyRuntimePath(builder.environment(), executable)
        process = runCatching { builder.start() }.getOrElse { error ->
            throw ProviderException("无法启动 Codex App Server 读取历史。", retryableOverride = false, cause = error)
        }
        writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        readerThread = Thread {
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.length > MAX_CODEX_EVENT_CHARS || lines.trySend(line).isFailure) break
                    }
                }
            } finally {
                lines.close()
            }
        }.apply { name = "omnicode-codex-history"; isDaemon = true; start() }
        Thread {
            runCatching { process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { it.forEach { _ -> } } }
        }.apply { name = "omnicode-codex-history-stderr"; isDaemon = true; start() }
    }

    suspend fun list(): List<NativeCliHistoryEntry> = guarded {
        initialize()
        val result = call("thread/list", JsonObject().apply {
            addProperty("cwd", workingDirectory.toAbsolutePath().normalize().toString())
            addProperty("limit", MAX_HISTORY_ITEMS)
            addProperty("sortKey", "updated_at")
            addProperty("sortDirection", "desc")
            addProperty("useStateDbOnly", true)
            add("sourceKinds", JsonArray().apply {
                listOf("cli", "vscode", "exec", "appServer", "unknown").forEach(::add)
            })
        })
        parseCodexHistoryList(result, workingDirectory)
    }

    suspend fun read(sessionId: String): NativeCliTranscript = guarded {
        initialize()
        val result = call("thread/read", JsonObject().apply {
            addProperty("threadId", sessionId)
            addProperty("includeTurns", true)
        })
        parseCodexTranscript(result, workingDirectory, sessionId)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        withTimeout(HISTORY_TIMEOUT_SECONDS * 1_000L) { block() }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ProviderException("Codex App Server 读取历史超时，已安全停止。", retryableOverride = false, cause = timeout)
    }

    private suspend fun initialize() {
        call("initialize", JsonObject().apply {
            add("clientInfo", JsonObject().apply {
                addProperty("name", "omnicode-history")
                addProperty("title", "OmniCode history import")
                addProperty("version", "3.0.0")
            })
        })
        notify("initialized", JsonObject())
    }

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val id = requestIds.incrementAndGet().toString()
        write(JsonObject().apply {
            addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params)
        })
        while (true) {
            val received = lines.receiveCatching()
            val line = received.getOrNull() ?: throw ProviderException(
                "Codex App Server 在返回历史前已退出。",
                retryableOverride = false,
                cause = received.exceptionOrNull(),
            )
            val payload = runCatching { Json.parseObject(line) }.getOrNull() ?: continue
            if (payload.get("id")?.takeIf { it.isJsonPrimitive }?.asString != id) continue
            payload.jsonObjectOrNull("error")?.let { error ->
                throw ProviderException(
                    "Codex 无法读取历史${error.stringOrNull("message")?.take(240)?.let { "：$it" }.orEmpty()}",
                    retryableOverride = false,
                )
            }
            return payload.jsonObjectOrNull("result")
                ?: throw ProviderException("Codex App Server 返回了无效的历史响应。", retryableOverride = false)
        }
    }

    private fun notify(method: String, params: JsonObject) = write(JsonObject().apply {
        addProperty("jsonrpc", "2.0"); addProperty("method", method); add("params", params)
    })

    private fun write(payload: JsonObject) {
        writer.write(Json.stringify(payload)); writer.newLine(); writer.flush()
    }

    override fun close() {
        lines.close()
        runCatching { writer.close() }
        terminateProcessTree(process)
        readerThread.interrupt()
        runCatching { readerThread.join(500) }
    }
}

/**
 * Reads only bounded MCP section names from Codex config and disables those servers for this
 * short-lived read-only process. Values, environment variables and credentials are never parsed.
 */
private fun codexMcpDisableOverrides(): List<String> {
    val codexHome = System.getenv("CODEX_HOME")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".codex")
    val config = codexHome.resolve("config.toml")
    if (!Files.isRegularFile(config)) return emptyList()
    val names = runCatching {
        Files.newBufferedReader(config, StandardCharsets.UTF_8).useLines { lines ->
            lines.take(MAX_CODEX_CONFIG_LINES)
                .mapNotNull { line -> CODEX_MCP_SERVER_SECTION.matchEntire(line.trim())?.groupValues?.getOrNull(1) }
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        }
    }.getOrDefault(emptyList())
    return names.flatMap { name -> listOf("-c", "mcp_servers.$name.enabled=false") }
}

internal fun parseOpenCodeHistoryList(json: String, workingDirectory: Path): List<NativeCliHistoryEntry> {
    val root = JsonParser.parseString(json).takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
    return root.asSequence().mapNotNull { element ->
        val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val id = item.stringOrNull("id")?.takeIf(SAFE_NATIVE_HISTORY_ID::matches) ?: return@mapNotNull null
        if (!sameWorkingDirectory(item.stringOrNull("directory"), workingDirectory)) return@mapNotNull null
        NativeCliHistoryEntry(
            engineId = "opencode",
            sessionId = id,
            title = boundedTitle(item.stringOrNull("title"), "OpenCode 会话"),
            updatedAt = item.get("updated")?.runCatching { asLong }?.getOrNull()?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
        )
    }.take(MAX_HISTORY_ITEMS).toList()
}

internal fun parseOpenCodeTranscript(json: String, workingDirectory: Path, sessionId: String): NativeCliTranscript {
    val root = Json.parseObject(json)
    val info = root.jsonObjectOrNull("info") ?: throw ProviderException("OpenCode 导出缺少会话信息。", retryableOverride = false)
    if (info.stringOrNull("id") != sessionId || !sameWorkingDirectory(info.stringOrNull("directory"), workingDirectory)) {
        throw ProviderException("OpenCode 会话不属于当前项目，已拒绝导入。", retryableOverride = false)
    }
    val messages = mutableListOf<ConversationMessage>()
    var characters = 0
    root.jsonArrayOrNull("messages").orEmptySequence().forEach { element ->
        if (messages.size >= MAX_TRANSCRIPT_MESSAGES || characters >= MAX_TRANSCRIPT_CHARS) return@forEach
        val message = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
        val role = when (message.jsonObjectOrNull("info")?.stringOrNull("role")) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> return@forEach
        }
        val text = message.jsonArrayOrNull("parts").orEmptySequence()
            .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .filter { it.stringOrNull("type") == "text" }
            .mapNotNull { it.stringOrNull("text") }
            .joinToString("\n")
            .take((MAX_TRANSCRIPT_CHARS - characters).coerceAtLeast(0))
            .trim()
        if (text.isBlank()) return@forEach
        characters += text.length
        appendVisibleMessage(messages, role, text)
    }
    if (messages.none { it.role == MessageRole.USER }) throw ProviderException("OpenCode 会话没有可导入的用户文本。", retryableOverride = false)
    val updated = info.jsonObjectOrNull("time")?.get("updated")?.runCatching { asLong }?.getOrNull()
        ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
    return NativeCliTranscript(
        NativeCliHistoryEntry("opencode", sessionId, boundedTitle(info.stringOrNull("title"), "OpenCode 会话"), updated),
        messages,
    )
}

internal fun parseCodexHistoryList(result: JsonObject, workingDirectory: Path): List<NativeCliHistoryEntry> =
    result.jsonArrayOrNull("data").orEmptySequence().mapNotNull { element ->
        val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val id = item.stringOrNull("id")?.takeIf(SAFE_NATIVE_HISTORY_ID::matches) ?: return@mapNotNull null
        if (!sameWorkingDirectory(item.stringOrNull("cwd"), workingDirectory)) return@mapNotNull null
        NativeCliHistoryEntry(
            engineId = "codex",
            sessionId = id,
            title = boundedTitle(item.stringOrNull("name") ?: item.stringOrNull("preview"), "Codex 会话"),
            updatedAt = item.get("updatedAt")?.runCatching { asLong }?.getOrNull()?.let(Instant::ofEpochSecond) ?: Instant.EPOCH,
        )
    }.take(MAX_HISTORY_ITEMS).toList()

internal fun parseCodexTranscript(result: JsonObject, workingDirectory: Path, sessionId: String): NativeCliTranscript {
    val thread = result.jsonObjectOrNull("thread") ?: throw ProviderException("Codex 历史响应缺少 thread。", retryableOverride = false)
    if (thread.stringOrNull("id") != sessionId || !sameWorkingDirectory(thread.stringOrNull("cwd"), workingDirectory)) {
        throw ProviderException("Codex 会话不属于当前项目，已拒绝导入。", retryableOverride = false)
    }
    val messages = mutableListOf<ConversationMessage>()
    var characters = 0
    thread.jsonArrayOrNull("turns").orEmptySequence().forEach { turnElement ->
        val turn = turnElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
        turn.jsonArrayOrNull("items").orEmptySequence().forEach itemLoop@{ itemElement ->
            if (messages.size >= MAX_TRANSCRIPT_MESSAGES || characters >= MAX_TRANSCRIPT_CHARS) return@itemLoop
            val item = itemElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@itemLoop
            val type = item.stringOrNull("type")
            val role: MessageRole
            val text = when (type) {
                "userMessage" -> {
                    role = MessageRole.USER
                    item.jsonArrayOrNull("content").orEmptySequence()
                        .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                        .filter { it.stringOrNull("type") == "text" }
                        .mapNotNull { it.stringOrNull("text") }
                        .joinToString("\n")
                }
                "agentMessage", "plan" -> {
                    role = MessageRole.ASSISTANT
                    item.stringOrNull("text").orEmpty()
                }
                else -> return@itemLoop
            }.take((MAX_TRANSCRIPT_CHARS - characters).coerceAtLeast(0)).trim()
            if (text.isBlank()) return@itemLoop
            characters += text.length
            appendVisibleMessage(messages, role, text)
        }
    }
    if (messages.none { it.role == MessageRole.USER }) throw ProviderException("Codex 会话没有可导入的用户文本。", retryableOverride = false)
    val updated = thread.get("updatedAt")?.runCatching { asLong }?.getOrNull()?.let(Instant::ofEpochSecond) ?: Instant.EPOCH
    return NativeCliTranscript(
        NativeCliHistoryEntry("codex", sessionId, boundedTitle(thread.stringOrNull("name") ?: thread.stringOrNull("preview"), "Codex 会话"), updated),
        messages,
    )
}

private fun appendVisibleMessage(target: MutableList<ConversationMessage>, role: MessageRole, text: String) {
    val previous = target.lastOrNull()
    if (previous?.role == role) {
        val priorText = previous.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        target[target.lastIndex] = ConversationMessage(role, "$priorText\n$text".take(MAX_TRANSCRIPT_CHARS))
    } else target += ConversationMessage(role, text)
}

private fun JsonArray?.orEmptySequence(): Sequence<JsonElement> = this?.asSequence() ?: emptySequence()

private fun sameWorkingDirectory(candidate: String?, expected: Path): Boolean = runCatching {
    candidate?.takeIf(String::isNotBlank)?.let(Path::of)?.toAbsolutePath()?.normalize() == expected.toAbsolutePath().normalize()
}.getOrDefault(false)

private fun boundedTitle(value: String?, fallback: String): String = value.orEmpty()
    .replace(Regex("[\\r\\n\\t]+"), " ")
    .trim()
    .take(MAX_HISTORY_TITLE_CHARS)
    .ifBlank { fallback }

private class BoundedProcessStream(
    private val process: Process,
    stream: java.io.InputStream,
    private val limit: Int,
    label: String,
) {
    private val bytes = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    private val overflow = AtomicBoolean(false)
    private val thread = Thread {
        val buffer = ByteArray(8192)
        stream.use { input ->
            while (true) {
                val count = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (count <= 0) break
                synchronized(bytes) {
                    if (bytes.size() + count > limit) {
                        overflow.set(true)
                        terminateProcessTree(process)
                        return@Thread
                    }
                    bytes.write(buffer, 0, count)
                }
            }
        }
    }.apply { name = "omnicode-cli-history-$label"; isDaemon = true; start() }

    val overflowed: Boolean get() = overflow.get()
    fun join() = runCatching { thread.join(500) }
    fun text(): String = synchronized(bytes) { bytes.toByteArray().toString(StandardCharsets.UTF_8) }
}

private fun terminateProcessTree(process: Process) {
    val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
    descendants.forEach { runCatching { it.destroy() } }
    if (process.isAlive) runCatching { process.destroy() }
    runCatching { process.waitFor(300, TimeUnit.MILLISECONDS) }
    descendants.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
    if (process.isAlive) runCatching { process.destroyForcibly() }
}

private val SAFE_NATIVE_HISTORY_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private const val MAX_HISTORY_ITEMS = 50
private const val MAX_HISTORY_TITLE_CHARS = 120
private const val MAX_HISTORY_JSON_BYTES = 512 * 1024
private const val MAX_TRANSCRIPT_JSON_BYTES = 4 * 1024 * 1024
private const val MAX_HISTORY_ERROR_BYTES = 16 * 1024
private const val MAX_TRANSCRIPT_MESSAGES = 200
private const val MAX_TRANSCRIPT_CHARS = 500_000
private const val HISTORY_TIMEOUT_SECONDS = 15L
private const val CODEX_EVENT_QUEUE_CAPACITY = 256
private const val MAX_CODEX_EVENT_CHARS = 4 * 1024 * 1024
private const val MAX_CODEX_CONFIG_LINES = 4_096
private val CODEX_MCP_SERVER_SECTION = Regex("\\[mcp_servers\\.([A-Za-z0-9_-]{1,80})\\]")
