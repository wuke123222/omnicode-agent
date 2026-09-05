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
            ProviderProtocol.CLI_KIMI,
            ProviderProtocol.CLI_PI,
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

        ProviderProtocol.CLI_KIMI -> GenericCliModelDiscovery.discoverKimi(connection)

        ProviderProtocol.CLI_PI -> GenericCliModelDiscovery.discoverPi(connection)

        ProviderProtocol.CLI_OMP -> GenericCliModelDiscovery.discoverOmp(connection)

        ProviderProtocol.CLI_DSH -> DshHostModelDiscovery.discover(connection)

        ProviderProtocol.CLI_CLAUDE,
        ProviderProtocol.CLI_CODEX,
        ProviderProtocol.CLI_GROK,
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
        // Do not perform an unbounded blocking read after the CLI parent exits. npm/uvx
        // launchers can leave a worker holding the inherited stdout pipe; waiting for EOF here
        // made model discovery appear stuck until the request timeout. The shared reader polls
        // while the process is alive and drains only a short, cancellation-aware post-exit
        // window, while retaining the same output bound.
        return readBoundedProcessOutput(process, MAX_OUTPUT_CHARS)
    }

    private fun terminateProcessTree(process: Process) {
        // A launcher may have exited while a worker still owns the stdout pipe. Reap descendants
        // even when the parent is already dead, otherwise model discovery can keep the UI busy.
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
        descendants.forEach { handle -> runCatching { handle.destroy() } }
        if (process.isAlive) runCatching { process.destroy() }
        runCatching { process.waitFor(PROCESS_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
        descendants.filter { it.isAlive }.forEach { handle -> runCatching { handle.destroyForcibly() } }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(PROCESS_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
    private const val MIN_TIMEOUT_SECONDS = 3L
    private const val MAX_TIMEOUT_SECONDS = 20L
    private const val MAX_OUTPUT_CHARS = 256_000
    private const val MAX_MODELS = 1_000
    private const val PROCESS_EXIT_GRACE_MILLIS = 500L
}

/** Fixed-argv, local-only discovery for the OMP CLI. */
internal object GenericCliModelDiscovery {
    suspend fun discoverKimi(connection: ProviderConnection): ModelDiscoveryResult = discoverFixedCli(
        connection = connection,
        tool = CliTool.KIMI,
        arguments = listOf("provider", "list", "--json"),
        missingMessage = "找不到 Kimi CLI。请先安装并在依赖页重新检测。",
        failureMessage = "Kimi CLI 未能读取模型。请在终端运行 kimi provider list --json 检查配置。",
        emptyMessage = "Kimi 尚未配置可用模型。请先在终端完成 Kimi 登录或 provider 配置。",
        parser = ::parseKimiModels,
    )

    suspend fun discoverPi(connection: ProviderConnection): ModelDiscoveryResult = discoverFixedCli(
        connection = connection,
        tool = CliTool.PI,
        arguments = listOf("--list-models"),
        missingMessage = "找不到 Pi CLI。请先安装并在依赖页重新检测。",
        failureMessage = "Pi CLI 未能读取模型。请在终端运行 pi --list-models 检查配置。",
        emptyMessage = "Pi 尚未登录任何模型供应商。请先在终端运行 pi，再使用 /login 完成登录。",
        parser = ::parsePiModels,
    )

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
        // Keep model discovery subject to the same bounded post-exit drain as every other CLI
        // read. A descendant must never be able to hold the UI in “加载模型配置…” forever.
        return readBoundedProcessOutput(process, 512_000)
    }

    private suspend fun discoverFixedCli(
        connection: ProviderConnection,
        tool: CliTool,
        arguments: List<String>,
        missingMessage: String,
        failureMessage: String,
        emptyMessage: String,
        parser: (String) -> List<String>,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val executable = CliToolDiscovery.resolveExecutable(tool, connection.baseUrl)
            ?: throw ProviderException(missingMessage, retryableOverride = false)
        val builder = ProcessBuilder(CliToolDiscovery.launchCommand(executable) + arguments)
            .directory(File(System.getProperty("user.dir", ".")))
            .redirectErrorStream(true)
        CliToolDiscovery.applyRuntimePath(builder.environment(), executable)
        val process = runCatching { builder.start() }.getOrElse { error ->
            throw ProviderException("无法启动 ${connection.preset.displayName} 读取模型。", networkFailure = true, cause = error)
        }
        try {
            closeOneShotCliInput(process)
            val timeoutSeconds = connection.requestTimeoutSeconds.coerceIn(3, 30)
            val output = try {
                withTimeout(timeoutSeconds * 1_000L) { readBoundedProcessOutput(process, 512_000) }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw ProviderException(
                    "${connection.preset.displayName} 在 $timeoutSeconds 秒内未返回模型列表。",
                    networkFailure = true,
                    retryableOverride = false,
                    cause = timeout,
                )
            }
            if (process.exitValue() != 0) throw ProviderException(failureMessage, retryableOverride = false)
            val models = parser(output)
            if (models.isEmpty()) throw ProviderException(emptyMessage, retryableOverride = false)
            ModelDiscoveryResult(
                models = models,
                discoveredRemotely = false,
                status = "已从本机 ${connection.preset.displayName} 读取 ${models.size} 个可用模型。",
            )
        } finally {
            terminateDiscoveryProcess(process)
        }
    }

    internal fun parseKimiModels(output: String): List<String> {
        val root = runCatching { Json.parseObject(output) }.getOrNull() ?: return emptyList()
        val models = root.get("models")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        return models.entrySet().asSequence()
            .map { it.key.trim() }
            .filter(MODEL_ID::matches)
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .take(1_000)
            .toList()
    }

    internal fun parsePiModels(output: String): List<String> = output.lineSequence()
        .map { ANSI_ESCAPE.replace(it, "").trim() }
        .filter(String::isNotBlank)
        .mapNotNull { line ->
            val columns = line.split(MODEL_COLUMNS, limit = 3)
            if (columns.size < 2) null else {
                val provider = columns[0].trim()
                val model = columns[1].trim()
                "$provider/$model".takeIf {
                    !(provider.equals("provider", ignoreCase = true) && model.equals("model", ignoreCase = true)) &&
                        provider.matches(PROVIDER_ID) && model.matches(MODEL_ID)
                }
            }
        }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .take(1_000)
        .toList()

    private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
    private val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}")
    private val MODEL_COLUMNS = Regex("\\s{2,}")
    private val ANSI_ESCAPE = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
}

private fun terminateDiscoveryProcess(process: Process) {
    val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
    descendants.forEach { handle -> runCatching { handle.destroy() } }
    if (process.isAlive) runCatching { process.destroy() }
    runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
    descendants.filter { it.isAlive }.forEach { handle -> runCatching { handle.destroyForcibly() } }
    if (process.isAlive) runCatching { process.destroyForcibly() }
    runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
}
