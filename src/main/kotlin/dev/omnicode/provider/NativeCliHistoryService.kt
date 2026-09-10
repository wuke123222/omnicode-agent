package dev.omnicode.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.model.ContentBlock
import dev.omnicode.model.ConversationMessage
import dev.omnicode.model.MessageRole
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
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

/**
 * Bounded visible history imported from a provider-owned session.
 *
 * Text, redacted tool calls/results and non-binary image references are retained so restored
 * history has the same shape as the live transcript. Hidden reasoning, credentials and image
 * bytes stay out of OmniCode persistence.
 */
internal data class NativeCliTranscript(
    val entry: NativeCliHistoryEntry,
    val messages: List<ConversationMessage>,
    /** True when the CLI exposes a stable resume id/list command but no safe transcript export. */
    val referenceOnly: Boolean = false,
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
            LocalHistoryDiscovery.GEMINI_SESSIONS -> listGemini(engine)
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
            LocalHistoryDiscovery.GEMINI_SESSIONS -> readGeminiReference(engine, sessionId)
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

    private suspend fun listGemini(engine: LocalAgentEngineContract): List<NativeCliHistoryEntry> =
        parseGeminiHistoryList(runBoundedCli(engine, listOf("--list-sessions")))

    private suspend fun readGeminiReference(
        engine: LocalAgentEngineContract,
        sessionId: String,
    ): NativeCliTranscript {
        val entry = listGemini(engine).firstOrNull { it.sessionId == sessionId }
            ?: throw ProviderException("Gemini 会话已不存在或不属于当前项目。", retryableOverride = false)
        // Gemini documents --list-sessions/--resume, but does not expose a bounded transcript
        // export command. Preserve only the opaque resume id; do not scan ~/.gemini JSON files.
        return NativeCliTranscript(entry = entry, messages = emptyList(), referenceOnly = true)
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

internal fun parseGeminiHistoryList(text: String): List<NativeCliHistoryEntry> = text.lineSequence()
    .mapNotNull { line ->
        val match = GEMINI_SESSION_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
        val id = match.groupValues[3].trim().takeIf(SAFE_NATIVE_HISTORY_ID::matches) ?: return@mapNotNull null
        NativeCliHistoryEntry(
            engineId = "gemini",
            sessionId = id,
            title = boundedTitle(match.groupValues[2].substringBeforeLast(" (").trim(), "Gemini 会话"),
            // The human-readable relative timestamp is intentionally not guessed.
            updatedAt = Instant.EPOCH,
        )
    }
    .distinctBy(NativeCliHistoryEntry::sessionId)
    .take(MAX_HISTORY_ITEMS)
    .toList()

internal fun parseOpenCodeTranscript(json: String, workingDirectory: Path, sessionId: String): NativeCliTranscript {
    val root = Json.parseObject(json)
    val info = root.jsonObjectOrNull("info") ?: throw ProviderException("OpenCode 导出缺少会话信息。", retryableOverride = false)
    if (info.stringOrNull("id") != sessionId || !sameWorkingDirectory(info.stringOrNull("directory"), workingDirectory)) {
        throw ProviderException("OpenCode 会话不属于当前项目，已拒绝导入。", retryableOverride = false)
    }
    val messages = mutableListOf<ConversationMessage>()
    root.jsonArrayOrNull("messages").orEmptySequence().forEach { element ->
        if (messages.size >= MAX_TRANSCRIPT_MESSAGES) return@forEach
        val message = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
        val role = when (message.jsonObjectOrNull("info")?.stringOrNull("role")) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> return@forEach
        }
        message.jsonArrayOrNull("parts").orEmptySequence().forEach partLoop@{ partElement ->
            val part = partElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@partLoop
            when (part.stringOrNull("type")) {
                "text" -> part.stringOrNull("text")?.let { appendImportedText(messages, role, it) }
                "file", "image" -> historyImageReference(part)?.let {
                    appendImportedText(messages, role, "[本地图片引用：$it]")
                }
                "tool" -> appendOpenCodeTool(messages, part)
                // Reasoning and provider-private bookkeeping never enter imported history.
            }
        }
    }
    val boundedMessages = boundImportedMessages(messages)
    if (boundedMessages.none { it.role == MessageRole.USER }) throw ProviderException("OpenCode 会话没有可导入的用户文本。", retryableOverride = false)
    val updated = info.jsonObjectOrNull("time")?.get("updated")?.runCatching { asLong }?.getOrNull()
        ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
    return NativeCliTranscript(
        NativeCliHistoryEntry("opencode", sessionId, boundedTitle(info.stringOrNull("title"), "OpenCode 会话"), updated),
        boundedMessages,
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
    thread.jsonArrayOrNull("turns").orEmptySequence().forEach { turnElement ->
        val turn = turnElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
        turn.jsonArrayOrNull("items").orEmptySequence().forEach itemLoop@{ itemElement ->
            if (messages.size >= MAX_TRANSCRIPT_MESSAGES) return@itemLoop
            val item = itemElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@itemLoop
            when (item.stringOrNull("type")) {
                "userMessage" -> {
                    item.jsonArrayOrNull("content").orEmptySequence().forEach contentLoop@{ contentElement ->
                        val content = contentElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@contentLoop
                        when (content.stringOrNull("type")) {
                            "text", "input_text" -> content.stringOrNull("text")?.let {
                                appendImportedText(messages, MessageRole.USER, it)
                            }
                            "image", "input_image", "localImage" -> historyImageReference(content)?.let {
                                appendImportedText(messages, MessageRole.USER, "[本地图片引用：$it]")
                            }
                        }
                    }
                }
                "agentMessage", "plan" -> {
                    appendImportedText(messages, MessageRole.ASSISTANT, item.stringOrNull("text").orEmpty())
                }
                "commandExecution" -> appendCodexCommand(messages, item)
                "mcpToolCall" -> appendCodexMcpTool(messages, item)
                "fileChange" -> appendCodexFileChange(messages, item)
                // reasoning, web-search internals and provider metadata intentionally stay out.
            }
        }
    }
    val boundedMessages = boundImportedMessages(messages)
    if (boundedMessages.none { it.role == MessageRole.USER }) throw ProviderException("Codex 会话没有可导入的用户文本。", retryableOverride = false)
    val updated = thread.get("updatedAt")?.runCatching { asLong }?.getOrNull()?.let(Instant::ofEpochSecond) ?: Instant.EPOCH
    return NativeCliTranscript(
        NativeCliHistoryEntry("codex", sessionId, boundedTitle(thread.stringOrNull("name") ?: thread.stringOrNull("preview"), "Codex 会话"), updated),
        boundedMessages,
    )
}

private fun appendImportedText(target: MutableList<ConversationMessage>, role: MessageRole, text: String) {
    val safe = HISTORY_REDACTOR.redact(text).trim().take(MAX_IMPORTED_BLOCK_CHARS)
    if (safe.isNotBlank()) appendImportedBlock(target, role, ContentBlock.Text(safe))
}

private fun appendImportedBlock(target: MutableList<ConversationMessage>, role: MessageRole, block: ContentBlock) {
    val previous = target.lastOrNull()
    if (previous?.role == role && previous.blocks.size < MAX_IMPORTED_BLOCKS_PER_MESSAGE) {
        target[target.lastIndex] = previous.copy(blocks = previous.blocks + block)
    } else if (target.size < MAX_TRANSCRIPT_MESSAGES) {
        target += ConversationMessage(role, listOf(block))
    }
}

private fun appendOpenCodeTool(target: MutableList<ConversationMessage>, part: JsonObject) {
    val state = part.jsonObjectOrNull("state") ?: JsonObject()
    val callId = (part.stringOrNull("callID") ?: part.stringOrNull("callId") ?: part.stringOrNull("id"))
        ?.takeIf(SAFE_NATIVE_HISTORY_ID::matches) ?: "opencode-tool-${target.size}"
    val name = (part.stringOrNull("tool") ?: part.stringOrNull("name") ?: "Tool").take(MAX_IMPORTED_TOOL_NAME_CHARS)
    val arguments = safeToolArguments(state.jsonObjectOrNull("input") ?: part.jsonObjectOrNull("input") ?: JsonObject())
    appendImportedBlock(target, MessageRole.ASSISTANT, ContentBlock.ToolCall(callId, name, arguments))
    val output = state.get("output") ?: state.get("result") ?: part.get("output")
    if (output != null && !output.isJsonNull) {
        appendImportedBlock(
            target,
            MessageRole.USER,
            ContentBlock.ToolResult(callId, safeToolOutput(output), state.stringOrNull("status") in IMPORTED_ERROR_STATUSES),
        )
    }
}

private fun appendCodexCommand(target: MutableList<ConversationMessage>, item: JsonObject) {
    val callId = importedCallId(item, "codex-command", target.size)
    val command = item.get("command")?.let(::safeToolOutput).orEmpty()
    val arguments = JsonObject().apply { addProperty("command", command.take(MAX_IMPORTED_TOOL_ARGUMENT_CHARS)) }
    appendImportedBlock(target, MessageRole.ASSISTANT, ContentBlock.ToolCall(callId, "Bash", arguments))
    val output = item.get("aggregatedOutput") ?: item.get("output")
    if (output != null && !output.isJsonNull) {
        appendImportedBlock(target, MessageRole.USER, ContentBlock.ToolResult(
            callId,
            safeToolOutput(output),
            item.stringOrNull("status") in IMPORTED_ERROR_STATUSES || item.get("exitCode")?.runCatching { asInt != 0 }?.getOrDefault(false) == true,
        ))
    }
}

private fun appendCodexMcpTool(target: MutableList<ConversationMessage>, item: JsonObject) {
    val callId = importedCallId(item, "codex-mcp", target.size)
    val server = item.stringOrNull("server").orEmpty().take(MAX_IMPORTED_TOOL_NAME_CHARS)
    val tool = (item.stringOrNull("tool") ?: item.stringOrNull("name") ?: "MCP").take(MAX_IMPORTED_TOOL_NAME_CHARS)
    val name = listOf(server, tool).filter(String::isNotBlank).joinToString("/").ifBlank { "MCP" }
    val arguments = safeToolArguments(item.jsonObjectOrNull("arguments") ?: item.jsonObjectOrNull("input") ?: JsonObject())
    appendImportedBlock(target, MessageRole.ASSISTANT, ContentBlock.ToolCall(callId, name, arguments))
    val output = item.get("result") ?: item.get("output") ?: item.get("error")
    if (output != null && !output.isJsonNull) {
        appendImportedBlock(target, MessageRole.USER, ContentBlock.ToolResult(
            callId,
            safeToolOutput(output),
            item.get("error") != null || item.stringOrNull("status") in IMPORTED_ERROR_STATUSES,
        ))
    }
}

private fun appendCodexFileChange(target: MutableList<ConversationMessage>, item: JsonObject) {
    val callId = importedCallId(item, "codex-edit", target.size)
    val changes = item.get("changes") ?: item.get("patch") ?: item.get("diff")
    val arguments = JsonObject().apply {
        addProperty("summary", changes?.let(::safeToolOutput).orEmpty().take(MAX_IMPORTED_TOOL_ARGUMENT_CHARS))
    }
    appendImportedBlock(target, MessageRole.ASSISTANT, ContentBlock.ToolCall(callId, "Edit", arguments))
    appendImportedBlock(target, MessageRole.USER, ContentBlock.ToolResult(
        callId,
        HISTORY_REDACTOR.redact(item.stringOrNull("status") ?: "文件变更已记录").take(MAX_IMPORTED_BLOCK_CHARS),
        item.stringOrNull("status") in IMPORTED_ERROR_STATUSES,
    ))
}

private fun importedCallId(item: JsonObject, prefix: String, ordinal: Int): String =
    (item.stringOrNull("id") ?: item.stringOrNull("callId") ?: item.stringOrNull("callID"))
        ?.takeIf(SAFE_NATIVE_HISTORY_ID::matches) ?: "$prefix-$ordinal"

private fun historyImageReference(item: JsonObject): String? {
    val value = item.stringOrNull("path") ?: item.stringOrNull("filePath") ?: item.stringOrNull("url")
        ?: item.stringOrNull("image_url")
    return value?.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
        ?.let(HISTORY_REDACTOR::redact)?.take(MAX_IMPORTED_IMAGE_REFERENCE_CHARS)
}

private fun safeToolArguments(arguments: JsonObject): JsonObject {
    val redacted = HISTORY_REDACTOR.redact(Json.stringify(arguments)).take(MAX_IMPORTED_TOOL_ARGUMENT_CHARS)
    return runCatching { Json.parseObject(redacted) }.getOrElse {
        JsonObject().apply { addProperty("summary", redacted) }
    }
}

private fun safeToolOutput(value: JsonElement): String {
    val raw = if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else Json.stringify(value)
    return HISTORY_REDACTOR.redact(raw).take(MAX_IMPORTED_BLOCK_CHARS)
}

private fun boundImportedMessages(source: List<ConversationMessage>): List<ConversationMessage> {
    var remaining = MAX_TRANSCRIPT_CHARS
    return buildList {
        source.forEach messageLoop@{ message ->
            if (size >= MAX_TRANSCRIPT_MESSAGES || remaining <= 0) return@messageLoop
            val blocks = buildList {
                message.blocks.take(MAX_IMPORTED_BLOCKS_PER_MESSAGE).forEach blockLoop@{ block ->
                    if (remaining <= 0) return@blockLoop
                    val bounded = when (block) {
                        is ContentBlock.Text -> block.copy(text = block.text.take(remaining))
                        is ContentBlock.ToolCall -> block.copy(arguments = safeToolArguments(block.arguments))
                        is ContentBlock.ToolResult -> block.copy(content = block.content.take(minOf(remaining, MAX_IMPORTED_BLOCK_CHARS)))
                        is ContentBlock.Image, is ContentBlock.TransientProjectContext -> return@blockLoop
                    }
                    val size = when (bounded) {
                        is ContentBlock.Text -> bounded.text.length
                        is ContentBlock.ToolCall -> Json.stringify(bounded.arguments).length
                        is ContentBlock.ToolResult -> bounded.content.length
                        else -> 0
                    }
                    remaining -= size
                    add(bounded)
                }
            }
            if (blocks.isNotEmpty()) add(ConversationMessage(message.role, blocks))
        }
    }
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
private val GEMINI_SESSION_LINE = Regex("""^\s*(\d+)\.\s+(.+?)\s+\[([A-Za-z0-9._:-]{1,256})]\s*$""")
private const val MAX_HISTORY_ITEMS = 50
private const val MAX_HISTORY_TITLE_CHARS = 120
private const val MAX_HISTORY_JSON_BYTES = 512 * 1024
private const val MAX_TRANSCRIPT_JSON_BYTES = 4 * 1024 * 1024
private const val MAX_HISTORY_ERROR_BYTES = 16 * 1024
private const val MAX_TRANSCRIPT_MESSAGES = 200
private const val MAX_TRANSCRIPT_CHARS = 500_000
private const val MAX_IMPORTED_BLOCK_CHARS = 32_000
private const val MAX_IMPORTED_BLOCKS_PER_MESSAGE = 64
private const val MAX_IMPORTED_TOOL_NAME_CHARS = 120
private const val MAX_IMPORTED_TOOL_ARGUMENT_CHARS = 16_000
private const val MAX_IMPORTED_IMAGE_REFERENCE_CHARS = 2_048
private const val HISTORY_TIMEOUT_SECONDS = 15L
private const val CODEX_EVENT_QUEUE_CAPACITY = 256
private const val MAX_CODEX_EVENT_CHARS = 4 * 1024 * 1024
private const val MAX_CODEX_CONFIG_LINES = 4_096
private val CODEX_MCP_SERVER_SECTION = Regex("\\[mcp_servers\\.([A-Za-z0-9_-]{1,80})\\]")
private val IMPORTED_ERROR_STATUSES = setOf("error", "failed", "cancelled", "canceled", "rejected")
private val HISTORY_REDACTOR = DefaultSensitiveDataRedactor()
