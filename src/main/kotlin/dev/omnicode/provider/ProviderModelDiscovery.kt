package dev.omnicode.provider

import com.google.gson.JsonObject
import dev.omnicode.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal data class ModelDiscoveryResult(
    val models: List<String>,
    val discoveredRemotely: Boolean,
    val status: String,
)

internal interface ModelDiscoveryHttpClient {
    /** Returns a successful response or throws [ProviderException] for HTTP/network failures. */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String>,
        proxyMode: ProviderProxyMode,
    ): HttpResult
}

private object HttpTransportModelDiscoveryClient : ModelDiscoveryHttpClient {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
        sensitiveValues: Collection<String>,
        proxyMode: ProviderProxyMode,
    ): HttpResult = HttpTransport.getJson(url, headers, timeoutSeconds, sensitiveValues, proxyMode)
}

internal object ProviderModelDiscovery {
    /** Includes explicit local discovery commands, which never receive a stored API key. */
    fun supportsModelDiscovery(protocol: ProviderProtocol): Boolean =
        supportsRemoteDiscovery(protocol) || protocol in setOf(
            ProviderProtocol.CLI_OPENCODE,
            ProviderProtocol.CLI_OMP,
            ProviderProtocol.CLI_DSH,
        )

    fun supportsRemoteDiscovery(protocol: ProviderProtocol): Boolean = when (protocol) {
        ProviderProtocol.CODEX_APP_SERVER,
        ProviderProtocol.OPENCODE_ZEN,
        ProviderProtocol.OPENAI_RESPONSES,
        ProviderProtocol.OPENAI_CHAT,
        ProviderProtocol.ANTHROPIC_MESSAGES,
        ProviderProtocol.GEMINI,
        -> true
        ProviderProtocol.AZURE_OPENAI,
        ProviderProtocol.BEDROCK_CONVERSE,
        ProviderProtocol.CLI_OPENCODE,
        ProviderProtocol.CLI_CLAUDE,
        ProviderProtocol.CLI_CODEX,
        ProviderProtocol.CLI_KIMI,
        ProviderProtocol.CLI_GROK,
        ProviderProtocol.CLI_PI,
        ProviderProtocol.CLI_OMP,
        ProviderProtocol.CLI_DSH,
        ProviderProtocol.CLI_QODER,
        -> false
    }

    suspend fun discover(connection: ProviderConnection): ModelDiscoveryResult =
        discover(connection, HttpTransportModelDiscoveryClient)

    internal suspend fun discover(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult = when (connection.preset.protocol) {
        ProviderProtocol.CODEX_APP_SERVER -> CodexNativeModelDiscovery.discover(connection)
        ProviderProtocol.OPENCODE_ZEN,
        ProviderProtocol.OPENAI_RESPONSES,
        ProviderProtocol.OPENAI_CHAT,
        -> discoverOpenAiCompatible(connection, client)

        ProviderProtocol.GEMINI -> discoverGemini(connection, client)

        ProviderProtocol.ANTHROPIC_MESSAGES -> discoverAnthropic(connection, client)

        ProviderProtocol.AZURE_OPENAI,
        ProviderProtocol.BEDROCK_CONVERSE,
        -> fallback(
            connection,
            "${connection.preset.displayName} does not expose a compatible model-list endpoint; using the configured/default model.",
        )

        ProviderProtocol.CLI_OPENCODE -> OpenCodeCliModelDiscovery.discover(connection)

        ProviderProtocol.CLI_OMP -> GenericCliModelDiscovery.discoverOmp(connection)

        ProviderProtocol.CLI_DSH -> DshHostModelDiscovery.discover(connection)

        ProviderProtocol.CLI_KIMI,
        ProviderProtocol.CLI_CLAUDE,
        ProviderProtocol.CLI_CODEX,
        ProviderProtocol.CLI_GROK,
        ProviderProtocol.CLI_PI,
        ProviderProtocol.CLI_QODER,
        -> fallback(
            connection,
            "${connection.preset.displayName} 使用本地 CLI 模型；保留当前模型设置。",
        )
    }

    private suspend fun discoverOpenAiCompatible(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult {
        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("Authorization", "Bearer ${connection.apiKey}")
            putAll(connection.extraHeaders)
        }
        val response = try {
            client.get(
                url = "${connection.baseUrl.trimEnd('/')}/models",
                headers = headers,
                timeoutSeconds = connection.requestTimeoutSeconds,
                sensitiveValues = connection.sensitiveValues(),
                proxyMode = connection.proxyMode,
            )
        } catch (error: ProviderException) {
            if (error.statusCode != null && error.statusCode in UNSUPPORTED_MODEL_LIST_STATUSES) {
                return fallback(
                    connection,
                    "This endpoint does not provide model listing (HTTP ${error.statusCode}); using the configured/default model.",
                )
            }
            throw error
        }
        val payload = parsePayload(connection, response.body)
        val data = when {
            payload.has("data") && payload.get("data").isJsonArray -> payload.getAsJsonArray("data")
            payload.has("models") && payload.get("models").isJsonArray -> payload.getAsJsonArray("models")
            else -> throw invalidPayload(connection)
        }
        val models = data.mapNotNull { element ->
            when {
                element.isJsonPrimitive -> runCatching { element.asString }.getOrNull()
                element.isJsonObject -> element.asJsonObject.stringOrNull("id")
                    ?: element.asJsonObject.stringOrNull("name")?.removePrefix("models/")
                else -> null
            }
        }.normalizedModelIds()
        return if (models.isEmpty()) {
            fallback(connection, "The provider returned no selectable models; using the configured/default model.")
        } else {
            ModelDiscoveryResult(
                models = models,
                discoveredRemotely = true,
                status = "Found ${models.size} available models.",
            )
        }
    }

    private suspend fun discoverAnthropic(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult {
        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("x-api-key", connection.apiKey)
            put(
                "anthropic-version",
                connection.apiVersion.takeIf { it.matches(ANTHROPIC_VERSION) }
                    ?: DEFAULT_ANTHROPIC_VERSION,
            )
            putAll(connection.extraHeaders)
        }
        val models = mutableListOf<String>()
        val seenLastIds = mutableSetOf<String>()
        var afterId: String? = null

        repeat(MAX_ANTHROPIC_PAGES) {
            val url = buildString {
                append(connection.baseUrl.trimEnd('/'))
                append("/models?limit=")
                append(ANTHROPIC_PAGE_SIZE)
                afterId?.let {
                    append("&after_id=")
                    append(URLEncoder.encode(it, StandardCharsets.UTF_8))
                }
            }
            val response = try {
                client.get(
                    url = url,
                    headers = headers,
                    timeoutSeconds = connection.requestTimeoutSeconds,
                    sensitiveValues = connection.sensitiveValues(),
                    proxyMode = connection.proxyMode,
                )
            } catch (error: ProviderException) {
                if (error.statusCode != null && error.statusCode in UNSUPPORTED_MODEL_LIST_STATUSES) {
                    return fallback(
                        connection,
                        "Anthropic model listing is unavailable (HTTP ${error.statusCode}); using the configured/default model.",
                    )
                }
                throw error
            }
            val payload = parsePayload(connection, response.body)
            val items = payload.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw invalidPayload(connection)
            items.forEach { element ->
                if (element.isJsonObject) {
                    element.asJsonObject.stringOrNull("id")
                        ?.takeIf(String::isNotBlank)
                        ?.let(models::add)
                }
            }

            val hasMore = payload.get("has_more")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean
                ?: throw invalidPayload(connection)
            if (!hasMore) {
                val normalized = models.normalizedModelIds()
                return if (normalized.isEmpty()) {
                    fallback(connection, "Anthropic returned no selectable models; using the configured/default model.")
                } else {
                    ModelDiscoveryResult(
                        models = normalized,
                        discoveredRemotely = true,
                        status = "Found ${normalized.size} Anthropic models.",
                    )
                }
            }

            val next = payload.stringOrNull("last_id")?.takeIf(String::isNotBlank)
                ?: throw ProviderException(
                    "${connection.preset.displayName} indicated more models without a usable last_id cursor.",
                )
            if (!seenLastIds.add(next)) {
                throw ProviderException(
                    "${connection.preset.displayName} returned a repeating model-list last_id cursor.",
                )
            }
            afterId = next
        }
        throw ProviderException(
            "${connection.preset.displayName} model listing exceeded $MAX_ANTHROPIC_PAGES pages.",
        )
    }

    private suspend fun discoverGemini(
        connection: ProviderConnection,
        client: ModelDiscoveryHttpClient,
    ): ModelDiscoveryResult {
        val headers = buildMap {
            if (connection.apiKey.isNotBlank()) put("x-goog-api-key", connection.apiKey)
            putAll(connection.extraHeaders)
        }
        val models = mutableListOf<String>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null

        repeat(MAX_GEMINI_PAGES) {
            val url = buildString {
                append(connection.baseUrl.trimEnd('/'))
                append("/models?pageSize=")
                append(GEMINI_PAGE_SIZE)
                pageToken?.let {
                    append("&pageToken=")
                    append(URLEncoder.encode(it, StandardCharsets.UTF_8))
                }
            }
            val response = client.get(
                url = url,
                headers = headers,
                timeoutSeconds = connection.requestTimeoutSeconds,
                sensitiveValues = connection.sensitiveValues(),
                proxyMode = connection.proxyMode,
            )
            val payload = parsePayload(connection, response.body)
            val items = payload.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw invalidPayload(connection)
            items.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val model = element.asJsonObject
                val supportsGenerateContent = model.get("supportedGenerationMethods")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.any { method ->
                        method.isJsonPrimitive && runCatching { method.asString }.getOrNull()
                            ?.equals("generateContent", ignoreCase = true) == true
                    } == true
                if (supportsGenerateContent) {
                    val id = model.stringOrNull("baseModelId")
                        ?: model.stringOrNull("name")?.removePrefix("models/")
                    if (!id.isNullOrBlank()) models += id
                }
            }

            val next = payload.stringOrNull("nextPageToken")?.takeIf { it.isNotBlank() }
            if (next == null) {
                val normalized = models.normalizedModelIds()
                return if (normalized.isEmpty()) {
                    fallback(connection, "Gemini returned no models that support generateContent; using the configured/default model.")
                } else {
                    ModelDiscoveryResult(
                        models = normalized,
                        discoveredRemotely = true,
                        status = "Found ${normalized.size} Gemini generation models.",
                    )
                }
            }
            if (!seenPageTokens.add(next)) {
                throw ProviderException("${connection.preset.displayName} returned a repeating model-list page token.")
            }
            pageToken = next
        }
        throw ProviderException("${connection.preset.displayName} model listing exceeded $MAX_GEMINI_PAGES pages.")
    }

    private fun parsePayload(connection: ProviderConnection, body: String): JsonObject =
        runCatching { Json.parseObject(body) }.getOrElse { cause ->
            throw invalidPayload(connection, cause)
        }

    private fun invalidPayload(connection: ProviderConnection, cause: Throwable? = null): ProviderException =
        ProviderException(
            "${connection.preset.displayName} returned an invalid model-list response.",
            cause = cause,
        )

    private fun fallback(connection: ProviderConnection, status: String): ModelDiscoveryResult =
        ModelDiscoveryResult(
            models = listOf(connection.model, connection.preset.defaultModel)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct(),
            discoveredRemotely = false,
            status = status,
        )

    private fun Iterable<String>.normalizedModelIds(): List<String> = asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toList()

    private const val GEMINI_PAGE_SIZE = 1_000
    private const val MAX_GEMINI_PAGES = 20
    private const val ANTHROPIC_PAGE_SIZE = 1_000
    private const val MAX_ANTHROPIC_PAGES = 20
    private const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"
    private val ANTHROPIC_VERSION = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val UNSUPPORTED_MODEL_LIST_STATUSES = setOf(404, 405, 501)
}

/**
 * Explicit, local-only model discovery for an authenticated OpenCode installation.
 *
 * This invokes a fixed argv (opencode models) without a shell, API key, prompt, project file,
 * or network credential from OmniCode. OpenCode itself may use the user's existing local login.
 */
internal object OpenCodeCliModelDiscovery {
    suspend fun discover(connection: ProviderConnection): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val executable = CliToolDiscovery.resolveExecutable(CliTool.OPENCODE, connection.baseUrl)
            ?: throw ProviderException(
                "找不到 OpenCode CLI。请先在终端确认 opencode --version，再重新加载模型。",
                retryableOverride = false,
            )
        val processBuilder = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + "models")
            .directory(File(System.getProperty("user.dir", ".")))
            .redirectErrorStream(true)
        CliToolDiscovery.applyRuntimePath(processBuilder.environment(), executable)
        val process = try {
            processBuilder.start()
        } catch (error: IOException) {
            throw ProviderException(
                "无法启动 OpenCode CLI 读取模型。请在 CLI 页面重新检测后重试。",
                networkFailure = true,
                cause = error,
            )
        }
        try {
            closeOneShotCliInput(process)
            val timeoutSeconds = connection.requestTimeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
            val output = try {
                withTimeout(timeoutSeconds * 1_000L) { readBoundedStdout(process) }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw ProviderException(
                    "OpenCode CLI 在 $timeoutSeconds 秒内未返回模型列表。请在终端运行 opencode models 检查登录状态后重试。",
                    networkFailure = true,
                    cause = timeout,
                )
            }
            if (process.exitValue() != 0) {
                throw ProviderException(
                    "OpenCode CLI 未能读取模型列表。请在终端运行 opencode models 检查登录/供应商配置后重试。",
                    retryableOverride = false,
                )
            }
            val models = parseModels(output)
            if (models.isEmpty()) {
                throw ProviderException(
                    "OpenCode CLI 没有返回可选模型。请在终端运行 opencode providers 完成登录或配置供应商。",
                    retryableOverride = false,
                )
            }
            ModelDiscoveryResult(
                models = models,
                discoveredRemotely = false,
                status = "已从本机 OpenCode CLI 读取 " + models.size + " 个可用模型。",
            )
        } finally {
            terminateProcessTree(process)
        }
    }

    /** OpenCode prints one provider/model identity per line; never retain non-model output. */
    internal fun parseModels(output: String): List<String> = output.lineSequence()
        .map(String::trim)
        .filter { it.contains('/') && MODEL_ID.matches(it) }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .take(MAX_MODELS)
        .toList()

    private suspend fun readBoundedStdout(process: Process): String {
        val output = StringBuilder()
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(OUTPUT_BUFFER_CHARS)
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                var readAny = false
                while (reader.ready()) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    readAny = true
                    appendBounded(output, buffer, count)
                }
                if (!readAny) delay(OUTPUT_POLL_MILLIS)
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count <= 0) break
                appendBounded(output, buffer, count)
            }
        }
        return output.toString()
    }

    private fun appendBounded(output: StringBuilder, buffer: CharArray, count: Int) {
        if (output.length + count > MAX_OUTPUT_CHARS) {
            throw ProviderException("OpenCode CLI 模型列表超过安全上限。", retryableOverride = false)
        }
        output.append(buffer, 0, count)
    }

    private fun terminateProcessTree(process: Process) {
        if (!process.isAlive) return
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        descendants.forEach { handle -> runCatching { handle.destroy() } }
        runCatching { process.destroy() }
        val stopped = runCatching { process.waitFor(PROCESS_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
            .getOrDefault(!process.isAlive)
        if (!stopped) {
            descendants.forEach { handle -> runCatching { handle.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
        }
    }

    private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
    private const val MIN_TIMEOUT_SECONDS = 3L
    private const val MAX_TIMEOUT_SECONDS = 20L
    private const val MAX_OUTPUT_CHARS = 256_000
    private const val MAX_MODELS = 1_000
    private const val OUTPUT_BUFFER_CHARS = 4_096
    private const val OUTPUT_POLL_MILLIS = 25L
    private const val PROCESS_EXIT_GRACE_MILLIS = 500L
}

/** Fixed-argv, local-only discovery for the OMP CLI. */
internal object GenericCliModelDiscovery {
    suspend fun discoverOmp(connection: ProviderConnection): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val executable = CliToolDiscovery.resolveExecutable(CliTool.OMP, connection.baseUrl)
            ?: throw ProviderException("找不到 OMP CLI。请先安装 omp 并在依赖页重新检测。", retryableOverride = false)
        val builder = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + listOf("models", "--json"))
            .directory(File(System.getProperty("user.dir", ".")))
            .redirectErrorStream(true)
        CliToolDiscovery.applyRuntimePath(builder.environment(), executable)
        val process = runCatching { builder.start() }.getOrElse { error ->
            throw ProviderException("无法启动 OMP CLI 读取模型。", networkFailure = true, cause = error)
        }
        try {
            closeOneShotCliInput(process)
            val output = withTimeout(connection.requestTimeoutSeconds.coerceIn(3, 30) * 1_000L) {
                readProcessOutput(process)
            }
            if (process.exitValue() != 0) throw ProviderException("OMP CLI 未能读取模型列表。", retryableOverride = false)
            val root = runCatching { Json.parseObject(output) }.getOrElse {
                throw ProviderException("OMP CLI 返回了无效的模型 JSON。", retryableOverride = false)
            }
            val modelArray = root.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: com.google.gson.JsonArray()
            val models = modelArray.asSequence().mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("selector")
            }.filter { it.matches(MODEL_ID) }.distinct().take(1_000).toList()
            ModelDiscoveryResult(
                models = models.ifEmpty { listOf(connection.model.ifBlank { "default" }) },
                discoveredRemotely = false,
                status = "已从本机 OMP CLI 读取 ${models.size} 个可用模型。",
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun readProcessOutput(process: Process): String {
        val output = StringBuilder()
        process.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(4_096)
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                var read = false
                while (reader.ready()) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    require(output.length + count <= 512_000) { "OMP 模型列表超过安全上限。" }
                    output.append(buffer, 0, count)
                    read = true
                }
                if (!read) delay(25)
            }
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                require(output.length + count <= 512_000) { "OMP 模型列表超过安全上限。" }
                output.append(buffer, 0, count)
            }
        }
        return output.toString()
    }

    private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
}
