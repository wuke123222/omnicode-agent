package dev.omnicode.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.OMNICODE_MCP_USER_AGENT
import dev.omnicode.provider.modelApiProxySelector
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

enum class McpRegistryFailureKind {
    NETWORK,
    TIMEOUT,
    HTTP_STATUS,
    CONTENT_TYPE,
    RESPONSE_TOO_LARGE,
    INVALID_JSON,
    INVALID_RESPONSE,
}

class McpRegistryException(
    val kind: McpRegistryFailureKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class McpRegistryLoadResult internal constructor(
    entries: Collection<McpCatalogEntry>,
    val pagesLoaded: Int,
    val rejectedEntries: Int,
    val totalResponseBytes: Int,
    val truncated: Boolean,
    notices: Collection<String>,
    val fromCache: Boolean = false,
    val loadedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val entries: List<McpCatalogEntry> = Collections.unmodifiableList(ArrayList(entries))
    val notices: List<String> = Collections.unmodifiableList(ArrayList(notices))

    init {
        require(this.entries.size <= MAX_REGISTRY_ENTRIES)
        require(pagesLoaded in 0..MAX_REGISTRY_PAGES)
        require(rejectedEntries >= 0)
        require(totalResponseBytes >= 0)
        require(this.notices.size <= MAX_NOTICES)
        this.notices.forEach { McpCatalogPolicy.requireText(it, "Registry notice", MAX_NOTICE_CHARS) }
    }

    internal fun cachedCopy(): McpRegistryLoadResult = McpRegistryLoadResult(
        entries = entries,
        pagesLoaded = pagesLoaded,
        rejectedEntries = rejectedEntries,
        totalResponseBytes = totalResponseBytes,
        truncated = truncated,
        notices = notices,
        fromCache = true,
        loadedAtEpochMillis = loadedAtEpochMillis,
    )

    private companion object {
        const val MAX_REGISTRY_ENTRIES = 1_000
        const val MAX_REGISTRY_PAGES = 25
        const val MAX_NOTICES = 8
        const val MAX_NOTICE_CHARS = 320
    }
}

class McpMarketplaceDirectorySnapshot internal constructor(
    entries: Collection<McpCatalogEntry>,
    val registryResult: McpRegistryLoadResult?,
    val registryFailure: McpRegistryFailureKind?,
    val notice: String,
) {
    val entries: List<McpCatalogEntry> = Collections.unmodifiableList(ArrayList(entries))
    val registryAvailable: Boolean get() = registryResult != null
    val usingOfflineFallback: Boolean get() = registryResult == null
}

/**
 * UI-ready marketplace directory. Registry failure is fail-soft and returns the 27 reviewed local
 * presets; coroutine cancellation is never converted into an offline result.
 */
class McpMarketplaceDirectory internal constructor(
    private val registryClient: McpRegistryCatalogClient,
) {
    constructor() : this(McpRegistryCatalogClient())

    suspend fun load(forceRefresh: Boolean = false): McpMarketplaceDirectorySnapshot {
        val builtIns = McpMarketplaceCatalog.entries
        return try {
            val registry = registryClient.load(forceRefresh)
            McpMarketplaceDirectorySnapshot(
                entries = ArrayList<McpCatalogEntry>(builtIns.size + registry.entries.size).apply {
                    addAll(builtIns)
                    addAll(registry.entries)
                },
                registryResult = registry,
                registryFailure = null,
                notice = registry.notices.firstOrNull().orEmpty(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: McpRegistryException) {
            McpMarketplaceDirectorySnapshot(
                entries = builtIns,
                registryResult = null,
                registryFailure = failure.kind,
                notice = registryFallbackNotice(failure.kind),
            )
        }
    }
}

internal data class McpRegistryLoadLimits(
    val maxEntries: Int = 500,
    val maxPages: Int = 20,
    val maxPageBytes: Int = 2 * 1_024 * 1_024,
    val maxTotalBytes: Int = 12 * 1_024 * 1_024,
) {
    init {
        require(maxEntries in 1..1_000)
        require(maxPages in 1..25)
        require(maxPageBytes in 64 * 1_024..4 * 1_024 * 1_024)
        require(maxTotalBytes in maxPageBytes..32 * 1_024 * 1_024)
    }
}

internal data class McpRegistryHttpRequest(
    val uri: URI,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxResponseBytes: Int,
    val headers: Map<String, String>,
)

internal data class McpRegistryHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

internal fun interface McpRegistryHttpTransport {
    fun execute(request: McpRegistryHttpRequest): McpRegistryHttpResponse
}

/**
 * Loads metadata from the official MCP Registry API. The endpoint is intentionally not
 * configurable: redirects and alternate hosts are rejected before any network request.
 */
class McpRegistryCatalogClient internal constructor(
    private val transport: McpRegistryHttpTransport,
    private val limits: McpRegistryLoadLimits,
    private val persistentCache: McpRegistryCatalogPersistentCache? = null,
) {
    constructor() : this(
        JavaMcpRegistryHttpTransport(),
        McpRegistryLoadLimits(),
        IdeMcpRegistryCatalogPersistentCache(),
    )

    private val loadMutex = Mutex()

    @Volatile
    private var cached: McpRegistryLoadResult? = null
    @Volatile
    private var cachedAtNanos: Long = 0L

    suspend fun load(forceRefresh: Boolean = false): McpRegistryLoadResult = loadMutex.withLock {
        if (!forceRefresh && System.nanoTime() - cachedAtNanos in 0 until CACHE_TTL_NANOS) {
            cached?.let { return@withLock it.cachedCopy() }
        }
        if (!forceRefresh) {
            persistentCache?.load()?.takeIf { persisted ->
                val age = System.currentTimeMillis() - persisted.loadedAtEpochMillis
                age in 0..PERSISTED_CACHE_TTL_MILLIS
            }?.let { persisted ->
                cached = persisted
                cachedAtNanos = System.nanoTime()
                return@withLock persisted.cachedCopy()
            }
        }
        val fresh = try {
            withContext(Dispatchers.IO) { loadFresh() }
        } catch (failure: McpRegistryException) {
            // A previous directory is more useful than an empty market after an IDE restart.
            // The caller can still force a fresh request with the explicit refresh action.
            val stale = cached ?: persistentCache?.load()
            if (stale != null) {
                cached = stale
                cachedAtNanos = System.nanoTime()
                return@withLock stale.cachedCopy()
            }
            throw failure
        }
        cached = fresh
        cachedAtNanos = System.nanoTime()
        persistentCache?.save(fresh)
        fresh
    }

    private suspend fun loadFresh(): McpRegistryLoadResult {
        val entriesByRegistryName = LinkedHashMap<String, McpCatalogEntry>()
        val seenCursors = LinkedHashSet<String>()
        val notices = ArrayList<String>()
        var cursor: String? = null
        var pagesLoaded = 0
        var rejectedEntries = 0
        var totalBytes = 0
        var truncated = false

        while (entriesByRegistryName.size < limits.maxEntries && pagesLoaded < limits.maxPages) {
            currentCoroutineContext().ensureActive()
            val remaining = limits.maxEntries - entriesByRegistryName.size
            val request = registryRequest(cursor, minOf(REGISTRY_PAGE_SIZE, remaining), limits.maxPageBytes)
            val response = try {
                runInterruptible(Dispatchers.IO) { transport.execute(request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: McpRegistryException) {
                throw failure
            } catch (error: Exception) {
                throw McpRegistryException(
                    McpRegistryFailureKind.NETWORK,
                    "MCP Registry network request failed",
                    error,
                )
            }

            if (response.body.size > limits.maxPageBytes || totalBytes + response.body.size > limits.maxTotalBytes) {
                throw McpRegistryException(
                    McpRegistryFailureKind.RESPONSE_TOO_LARGE,
                    "MCP Registry response exceeds the supported size limit",
                )
            }
            totalBytes += response.body.size

            val page = try {
                parseRegistryPage(response)
            } catch (failure: McpRegistryException) {
                throw failure
            }
            pagesLoaded++
            rejectedEntries += page.rejectedEntries
            page.entries.forEach { entry ->
                val registryName = entry.registryMetadata?.registryName ?: return@forEach
                if (entriesByRegistryName.size < limits.maxEntries) {
                    if (entriesByRegistryName.putIfAbsent(registryName, entry) != null) {
                        rejectedEntries++
                    }
                }
            }

            val next = page.nextCursor
            if (next == null) {
                cursor = null
                break
            }
            if (!seenCursors.add(next)) {
                throw McpRegistryException(
                    McpRegistryFailureKind.INVALID_RESPONSE,
                    "MCP Registry returned a repeating pagination cursor",
                )
            }
            cursor = next
        }

        if (cursor != null && (entriesByRegistryName.size >= limits.maxEntries || pagesLoaded >= limits.maxPages)) {
            truncated = true
        }
        if (rejectedEntries > 0 && notices.size < MAX_NOTICES) {
            notices += "已跳过 $rejectedEntries 条字段无效、非 active 或重复的 Registry 元数据。"
        }

        return McpRegistryLoadResult(
            entries = entriesByRegistryName.values.sortedWith(REGISTRY_RELEVANCE_ORDER),
            pagesLoaded = pagesLoaded,
            rejectedEntries = rejectedEntries,
            totalResponseBytes = totalBytes,
            truncated = truncated,
            notices = notices.take(MAX_NOTICES),
        )
    }

    private companion object {
        const val REGISTRY_PAGE_SIZE = 100
        const val MAX_NOTICES = 8
        const val CACHE_TTL_NANOS = 60L * 60L * 1_000_000_000L
        const val PERSISTED_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1_000L
    }
}

internal class JavaMcpRegistryHttpTransport(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(modelApiProxySelector())
        .build(),
) : McpRegistryHttpTransport {
    override fun execute(request: McpRegistryHttpRequest): McpRegistryHttpResponse {
        validateOfficialRegistryUri(request.uri)
        require(request.connectTimeout == CONNECT_TIMEOUT) { "Unexpected Registry connect timeout" }
        require(request.requestTimeout in MIN_REQUEST_TIMEOUT..MAX_REQUEST_TIMEOUT) {
            "Registry request timeout is outside the supported range"
        }
        require(request.maxResponseBytes in 1..4 * 1_024 * 1_024) {
            "Registry response limit is outside the supported range"
        }

        val httpRequest = HttpRequest.newBuilder(request.uri)
            .timeout(request.requestTimeout)
            .GET()
            .apply { request.headers.forEach(::header) }
            .build()
        val bodyRef = AtomicReference<InputStream?>()
        val completion = CompletableFuture<McpRegistryHttpResponse>()
        val worker = Thread.ofVirtual()
            .name("OmniCode MCP Registry")
            .unstarted {
                try {
                    val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    val input = response.body()
                    bodyRef.set(input)
                    input.use {
                        val declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
                        if (declaredLength > request.maxResponseBytes) {
                            throw McpRegistryException(
                                McpRegistryFailureKind.RESPONSE_TOO_LARGE,
                                "MCP Registry response exceeds the supported size limit",
                            )
                        }
                        val bytes = it.readNBytes(request.maxResponseBytes + 1)
                        if (bytes.size > request.maxResponseBytes) {
                            throw McpRegistryException(
                                McpRegistryFailureKind.RESPONSE_TOO_LARGE,
                                "MCP Registry response exceeds the supported size limit",
                            )
                        }
                        completion.complete(
                            McpRegistryHttpResponse(
                                statusCode = response.statusCode(),
                                contentType = response.headers().firstValue("Content-Type").orElse(null),
                                body = bytes,
                            ),
                        )
                    }
                } catch (error: Exception) {
                    completion.completeExceptionally(error)
                } finally {
                    bodyRef.set(null)
                }
            }
        worker.start()
        return try {
            completion.get(request.requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            bodyRef.getAndSet(null)?.runCatching(InputStream::close)
            worker.interrupt()
            throw McpRegistryException(
                McpRegistryFailureKind.TIMEOUT,
                "MCP Registry request timed out",
                timeout,
            )
        } catch (interrupted: InterruptedException) {
            bodyRef.getAndSet(null)?.runCatching(InputStream::close)
            worker.interrupt()
            Thread.currentThread().interrupt()
            throw McpRegistryException(
                McpRegistryFailureKind.NETWORK,
                "MCP Registry request was interrupted",
                interrupted,
            )
        } catch (execution: ExecutionException) {
            val cause = execution.cause
            when (cause) {
                is McpRegistryException -> throw cause
                is HttpTimeoutException -> throw McpRegistryException(
                    McpRegistryFailureKind.TIMEOUT,
                    "MCP Registry request timed out",
                    cause,
                )
                else -> throw McpRegistryException(
                    McpRegistryFailureKind.NETWORK,
                    "MCP Registry network request failed",
                    cause,
                )
            }
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(8)
        val MIN_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(1)
        val MAX_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

private data class ParsedRegistryPage(
    val entries: List<McpCatalogEntry>,
    val rejectedEntries: Int,
    val nextCursor: String?,
)

private data class ParsedInstallDeclarations(
    val options: List<McpCatalogInstallOption>,
    val metadata: List<McpRegistryInstallDeclaration>,
)

private fun registryRequest(cursor: String?, limit: Int, maxResponseBytes: Int): McpRegistryHttpRequest {
    require(limit in 1..100)
    val query = buildString {
        append("version=latest&limit=").append(limit)
        cursor?.let {
            requireValidCursor(it)
            append("&cursor=").append(URLEncoder.encode(it, StandardCharsets.UTF_8))
        }
    }
    val uri = URI("https://registry.modelcontextprotocol.io/v0.1/servers?$query")
    validateOfficialRegistryUri(uri)
    return McpRegistryHttpRequest(
        uri = uri,
        connectTimeout = Duration.ofSeconds(8),
        requestTimeout = Duration.ofSeconds(12),
        maxResponseBytes = maxResponseBytes,
        headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to OMNICODE_MCP_USER_AGENT,
        ),
    )
}

private fun validateOfficialRegistryUri(uri: URI) {
    require(
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("registry.modelcontextprotocol.io", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawPath == "/v0.1/servers" &&
            uri.userInfo == null &&
            uri.fragment == null,
    ) { "Registry requests must use the fixed official HTTPS endpoint" }
}

private fun parseRegistryPage(response: McpRegistryHttpResponse): ParsedRegistryPage {
    if (response.statusCode in 300..399) {
        throw McpRegistryException(
            McpRegistryFailureKind.HTTP_STATUS,
            "MCP Registry refused redirect status ${response.statusCode}",
        )
    }
    if (response.statusCode !in 200..299) {
        throw McpRegistryException(
            McpRegistryFailureKind.HTTP_STATUS,
            "MCP Registry returned HTTP status ${response.statusCode}",
        )
    }
    val contentType = response.contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    if (contentType != "application/json" && contentType?.endsWith("+json") != true) {
        throw McpRegistryException(
            McpRegistryFailureKind.CONTENT_TYPE,
            "MCP Registry returned an unsupported Content-Type",
        )
    }
    val json = decodeUtf8Strict(response.body)
    if (!hasBoundedJsonStructure(json)) {
        throw McpRegistryException(McpRegistryFailureKind.INVALID_RESPONSE, "MCP Registry JSON exceeds structural limits")
    }
    val parsed = try {
        JsonParser.parseString(json)
    } catch (_: Exception) {
        throw McpRegistryException(McpRegistryFailureKind.INVALID_JSON, "MCP Registry returned invalid JSON")
    }
    val root = parsed.objectOrNull()
        ?: throw McpRegistryException(McpRegistryFailureKind.INVALID_RESPONSE, "MCP Registry returned a non-object response")
    val servers = root.get("servers")?.takeUnless(JsonElement::isJsonNull)
        ?: throw invalidRegistryResponse()
    if (!servers.isJsonArray || servers.asJsonArray.size() > REGISTRY_API_PAGE_LIMIT) throw invalidRegistryResponse()
    val metadata = root.get("metadata").objectOrNull() ?: throw invalidRegistryResponse()
    val count = metadata.get("count").strictIntOrNull() ?: throw invalidRegistryResponse()
    if (count !in 0..REGISTRY_API_PAGE_LIMIT || count != servers.asJsonArray.size()) throw invalidRegistryResponse()
    val nextCursor = when (val cursor = metadata.get("nextCursor")) {
        null -> null
        else -> if (cursor.isJsonNull) null else cursor.stringOrNull()?.also(::requireValidCursor)
            ?: throw invalidRegistryResponse()
    }

    val parsedEntries = ArrayList<McpCatalogEntry>()
    var rejected = 0
    servers.asJsonArray.forEach { item ->
        val entry = try {
            parseRegistryEntry(item)
        } catch (_: Exception) {
            null
        }
        if (entry == null) rejected++ else parsedEntries += entry
    }
    return ParsedRegistryPage(
        entries = Collections.unmodifiableList(parsedEntries),
        rejectedEntries = rejected,
        nextCursor = nextCursor,
    )
}

private fun parseRegistryEntry(wrapperElement: JsonElement): McpCatalogEntry? {
    val wrapper = wrapperElement.objectOrNull() ?: return null
    val server = wrapper.get("server").objectOrNull() ?: return null
    val official = wrapper.get("_meta").objectOrNull()
        ?.get("io.modelcontextprotocol.registry/official").objectOrNull()
        ?: return null
    if (official.get("status").stringOrNull() != "active") return null
    if (official.get("isLatest").booleanOrNull() != true) return null

    val registryName = server.get("name").stringOrNull()?.trim()?.takeIf(::isValidRegistryName) ?: return null
    val version = server.get("version").stringOrNull()?.trim()?.takeIf(::isSafeVersion) ?: return null
    val rawDescription = server.get("description").stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val description = sanitizeRegistryText(rawDescription, 480).takeIf(String::isNotBlank) ?: return null
    val title = server.get("title").stringOrNull()?.let { sanitizeRegistryText(it, 120) }.orEmpty()
    val name = title.ifBlank { sanitizeRegistryText(registryName.substringAfter('/'), 120) }
        .ifBlank { "MCP Registry server" }
    val publisher = sanitizeRegistryText(registryName.substringBefore('/'), 120).ifBlank { "Registry publisher" }
    val category = inferCategory("$registryName $name $description")
    val installs = parseInstallDeclarations(server, version)
    val status = McpRegistryServerStatus.ACTIVE
    val publishedAt = official.get("publishedAt").instantOrNull()
    val updatedAt = official.get("updatedAt").instantOrNull()

    return McpCatalogEntry(
        id = stableRegistryId(registryName),
        name = name,
        publisher = publisher,
        description = description,
        source = McpCatalogSource.MCP_REGISTRY,
        category = category,
        riskLevel = McpCatalogRiskLevel.HIGH,
        riskSummary = "Public Registry metadata is namespace-verified but the package, code, permissions, and runtime behavior are not reviewed by OmniCode.",
        tags = registryTags(registryName, category, installs.options),
        links = registryLinks(server),
        installOptions = installs.options,
        registryMetadata = McpRegistryEntryMetadata(
            registryName = registryName,
            version = version,
            status = status,
            publishedAt = publishedAt,
            updatedAt = updatedAt,
            installDeclarations = installs.metadata,
        ),
    )
}

private fun parseInstallDeclarations(server: JsonObject, serverVersion: String): ParsedInstallDeclarations {
    val options = ArrayList<McpCatalogInstallOption>()
    val declarations = ArrayList<McpRegistryInstallDeclaration>()
    val packages = server.get("packages")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    val remotes = server.get("remotes")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    val declarationCount = (packages?.size() ?: 0) + (remotes?.size() ?: 0)
    val rawDeclarations = ArrayList<Pair<String, JsonElement>>(minOf(declarationCount, MAX_INSTALL_DECLARATIONS))
    packages?.take(MAX_INSTALL_DECLARATIONS)?.forEach { rawDeclarations += "package" to it }
    val remaining = MAX_INSTALL_DECLARATIONS - rawDeclarations.size
    if (remaining > 0) remotes?.take(remaining)?.forEach { rawDeclarations += "remote" to it }

    for (index in rawDeclarations.indices) {
        val (kind, element) = rawDeclarations[index]
        val parsed = if (kind == "package") {
            parsePackageDeclaration(element, serverVersion, options)
        } else {
            parseRemoteDeclaration(element, options)
        }
        parsed.option?.let { options += it }
        declarations += parsed.metadata
    }
    if (declarationCount > MAX_INSTALL_DECLARATIONS) {
        declarations += McpRegistryInstallDeclaration(
            type = "additional",
            identifier = "Additional Registry declarations",
            transport = "metadata",
            installable = false,
            unavailableReason = "Additional declarations exceeded the bounded display limit.",
        )
    }
    return ParsedInstallDeclarations(
        options = Collections.unmodifiableList(options),
        metadata = Collections.unmodifiableList(declarations),
    )
}

private data class ParsedDeclaration(
    val option: McpCatalogInstallOption?,
    val metadata: McpRegistryInstallDeclaration,
)

private fun parsePackageDeclaration(
    element: JsonElement,
    serverVersion: String,
    existingOptions: List<McpCatalogInstallOption>,
): ParsedDeclaration {
    val value = element.objectOrNull()
    val registryType = value?.get("registryType").stringOrNull()?.lowercase(Locale.ROOT).orEmpty()
    val identifier = sanitizeRegistryText(
        value?.get("identifier").stringOrNull().orEmpty(),
        MAX_DECLARATION_IDENTIFIER_CHARS,
    ).ifBlank { "Unspecified package" }
    val transport = value?.get("transport").objectOrNull()?.get("type").stringOrNull().orEmpty()
        .lowercase(Locale.ROOT)
    val versionElement = value?.get("version")
    val version = when {
        versionElement == null || versionElement.isJsonNull -> serverVersion
        else -> versionElement.stringOrNull()?.trim()?.takeIf(::isSafeVersion).orEmpty()
    }
    val typeLabel = sanitizeRegistryText(registryType, 40).ifBlank { "package" }
    val transportLabel = sanitizeRegistryText(transport, 40).ifBlank { "unspecified" }

    val unavailableReason = packageUnavailableReason(value, registryType, identifier, version, transport, existingOptions)
    if (unavailableReason != null) {
        return ParsedDeclaration(
            option = null,
            metadata = unavailableDeclaration(typeLabel, identifier, transportLabel, version, unavailableReason),
        )
    }

    val environmentKeys = parseEnvironmentKeys(value?.get("environmentVariables")) ?: return ParsedDeclaration(
        option = null,
        metadata = unavailableDeclaration(
            typeLabel,
            identifier,
            transportLabel,
            version,
            "Environment variable declarations cannot be represented safely.",
        ),
    )
    val ordinal = existingOptions.count {
        (registryType == "npm" && it.kind == McpCatalogInstallKind.NPX_PACKAGE) ||
            (registryType == "pypi" && it.kind == McpCatalogInstallKind.UVX_PACKAGE)
    } + 1
    val optionId = if (registryType == "npm") optionId("npx", ordinal) else optionId("uvx", ordinal)
    val option = if (registryType == "npm") {
        McpCatalogInstallOption(
            id = optionId,
            displayName = "NPX package $version",
            kind = McpCatalogInstallKind.NPX_PACKAGE,
            transport = McpTransport.STDIO,
            command = "npx",
            arguments = listOf("--yes", "$identifier@$version"),
            environmentKeys = environmentKeys,
        )
    } else {
        McpCatalogInstallOption(
            id = optionId,
            displayName = "UVX package $version",
            kind = McpCatalogInstallKind.UVX_PACKAGE,
            transport = McpTransport.STDIO,
            command = "uvx",
            arguments = listOf("$identifier==$version"),
            environmentKeys = environmentKeys,
        )
    }
    return ParsedDeclaration(
        option = option,
        metadata = McpRegistryInstallDeclaration(
            type = typeLabel,
            identifier = identifier,
            transport = transportLabel,
            version = version,
            installable = true,
        ),
    )
}

private fun packageUnavailableReason(
    value: JsonObject?,
    registryType: String,
    identifier: String,
    version: String,
    transport: String,
    existingOptions: List<McpCatalogInstallOption>,
): String? {
    if (value == null) return "Package declaration is not an object."
    if (existingOptions.size >= MAX_INSTALL_OPTIONS) return "One-click options exceeded the bounded safety limit."
    if (transport != "stdio") return "Only stdio package declarations can be converted safely."
    if (registryType !in setOf("npm", "pypi")) return "This package registry is metadata-only in OmniCode."
    if (!isSafeVersion(version)) return "Package version is missing or unsafe."
    if (registryType == "npm" && !NPM_IDENTIFIER.matches(identifier)) return "NPM package identifier is invalid."
    if (registryType == "pypi" && !PYPI_IDENTIFIER.matches(identifier)) return "PyPI package identifier is invalid."
    val fileSha256 = value.get("fileSha256")
    if (fileSha256 != null && !fileSha256.isJsonNull) {
        return "Package integrity hashes require a verified installer and are metadata-only for now."
    }
    val runtimeHintElement = value.get("runtimeHint")
    val runtimeHint = runtimeHintElement.stringOrNull()?.lowercase(Locale.ROOT).orEmpty()
    if (runtimeHintElement != null && !runtimeHintElement.isJsonNull && runtimeHintElement.stringOrNull() == null) {
        return "The declared runtime is malformed."
    }
    if (runtimeHint.isNotBlank() && runtimeHint != if (registryType == "npm") "npx" else "uvx") {
        return "The declared runtime is not supported for one-click configuration."
    }
    if (!usesDefaultPackageRegistry(value.get("registryBaseUrl"), registryType)) {
        return "Custom package registries require manual review."
    }
    if (!isEmptyArrayOrNull(value.get("packageArguments")) || !isEmptyArrayOrNull(value.get("runtimeArguments"))) {
        return "Runtime or package arguments require manual configuration."
    }
    return null
}

private fun parseRemoteDeclaration(
    element: JsonElement,
    existingOptions: List<McpCatalogInstallOption>,
): ParsedDeclaration {
    val value = element.objectOrNull()
    val transport = value?.get("type").stringOrNull()?.lowercase(Locale.ROOT).orEmpty()
    val rawUrl = value?.get("url").stringOrNull().orEmpty()
    val identifier = sanitizeRegistryText(rawUrl, MAX_DECLARATION_IDENTIFIER_CHARS).ifBlank { "Unspecified remote" }
    val transportLabel = sanitizeRegistryText(transport, 40).ifBlank { "unspecified" }
    val authMode = remoteAuthMode(value?.get("headers"))
    val reason = when {
        value == null -> "Remote declaration is not an object."
        existingOptions.size >= MAX_INSTALL_OPTIONS -> "One-click options exceeded the bounded safety limit."
        transport != "streamable-http" -> "Only Streamable HTTP remotes are supported."
        !isSafeRemoteUrl(rawUrl) -> "Remote URL must be a fixed absolute HTTPS endpoint."
        value.get("headers") != null && authMode == null -> "Only an Authorization header can be mapped to the secure credential field."
        !isEmptyObjectOrNull(value.get("variables")) -> "Templated remote URLs require manual configuration."
        else -> null
    }
    if (reason != null) {
        return ParsedDeclaration(
            option = null,
            metadata = unavailableDeclaration("remote", identifier, transportLabel, "", reason),
        )
    }
    val ordinal = existingOptions.count { it.kind == McpCatalogInstallKind.STREAMABLE_HTTP } + 1
    val option = McpCatalogInstallOption(
        id = optionId("streamable-http", ordinal),
        displayName = "Streamable HTTP",
        kind = McpCatalogInstallKind.STREAMABLE_HTTP,
        transport = McpTransport.HTTP,
        url = rawUrl,
        httpAuthMode = authMode ?: McpHttpAuthMode.NONE,
    )
    return ParsedDeclaration(
        option = option,
        metadata = McpRegistryInstallDeclaration(
            type = "remote",
            identifier = identifier,
            transport = transportLabel,
            installable = true,
        ),
    )
}

/** Maps the common Registry Authorization placeholder to the existing PasswordSafe-backed field. */
private fun remoteAuthMode(headers: JsonElement?): McpHttpAuthMode? {
    if (headers == null || headers.isJsonNull) return McpHttpAuthMode.NONE
    if (!headers.isJsonArray) return null
    val values = headers.asJsonArray
    if (values.size() == 0) return McpHttpAuthMode.NONE
    return if (values.all { it.objectOrNull()?.get("name").stringOrNull()?.equals("Authorization", ignoreCase = true) == true }) {
        McpHttpAuthMode.BEARER
    } else {
        null
    }
}

private fun parseEnvironmentKeys(element: JsonElement?): Set<String>? {
    if (element == null || element.isJsonNull) return emptySet()
    if (!element.isJsonArray || element.asJsonArray.size() > MAX_ENVIRONMENT_KEYS) return null
    val keys = LinkedHashSet<String>()
    for (item in element.asJsonArray) {
        val name = item.objectOrNull()?.get("name").stringOrNull() ?: return null
        if (!McpCatalogPolicy.environmentKey.matches(name)) return null
        keys += name
    }
    return Collections.unmodifiableSet(keys)
}

private fun unavailableDeclaration(
    type: String,
    identifier: String,
    transport: String,
    version: String,
    reason: String,
): McpRegistryInstallDeclaration = McpRegistryInstallDeclaration(
    type = type,
    identifier = identifier,
    transport = transport,
    version = version,
    installable = false,
    unavailableReason = reason,
)

private fun registryLinks(server: JsonObject): List<McpCatalogLink> {
    val links = ArrayList<McpCatalogLink>()
    server.get("repository").objectOrNull()?.get("url").stringOrNull()
        ?.takeIf(::isSafeHttpsLink)
        ?.let { links += McpCatalogLink(McpCatalogLinkKind.REPOSITORY, it) }
    server.get("websiteUrl").stringOrNull()
        ?.takeIf(::isSafeHttpsLink)
        ?.takeIf { website -> links.none { it.url == website } }
        ?.let { links += McpCatalogLink(McpCatalogLinkKind.HOMEPAGE, it) }
    return Collections.unmodifiableList(links)
}

private fun registryTags(
    registryName: String,
    category: McpCatalogCategory,
    options: List<McpCatalogInstallOption>,
): Set<String> {
    val tags = LinkedHashSet<String>()
    tags += "registry"
    REGISTRY_TAG_TOKEN.findAll(registryName.lowercase(Locale.ROOT))
        .map(MatchResult::value)
        .filter { it.length in 2..32 }
        .take(5)
        .forEach(tags::add)
    if (options.any { it.transport == McpTransport.HTTP }) tags += "remote"
    if (options.any { it.kind == McpCatalogInstallKind.NPX_PACKAGE }) tags += "npm"
    if (options.any { it.kind == McpCatalogInstallKind.UVX_PACKAGE }) tags += "pypi"
    tags += category.id
    return Collections.unmodifiableSet(tags.take(11).toCollection(LinkedHashSet()))
}

private fun inferCategory(searchable: String): McpCatalogCategory {
    val text = searchable.lowercase(Locale.ROOT)
    return when {
        RESEARCH_KEYWORDS.any(text::contains) -> McpCatalogCategory.RESEARCH
        DATA_KEYWORDS.any(text::contains) -> McpCatalogCategory.DATA
        BROWSER_KEYWORDS.any(text::contains) -> McpCatalogCategory.BROWSER
        CLOUD_KEYWORDS.any(text::contains) -> McpCatalogCategory.CLOUD
        PRODUCTIVITY_KEYWORDS.any(text::contains) -> McpCatalogCategory.PRODUCTIVITY
        else -> McpCatalogCategory.DEVELOPMENT
    }
}

private fun usesDefaultPackageRegistry(element: JsonElement?, registryType: String): Boolean {
    if (element == null || element.isJsonNull) return true
    val value = element.stringOrNull()?.trim()?.trimEnd('/') ?: return false
    return when (registryType) {
        "npm" -> value == "https://registry.npmjs.org"
        "pypi" -> value == "https://pypi.org"
        else -> false
    }
}

internal fun stableRegistryId(registryName: String): String {
    val slug = registryName.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(36)
        .ifBlank { "server" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(registryName.toByteArray(StandardCharsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it) }
    return "registry-$slug-$digest"
}

private fun optionId(base: String, ordinal: Int): String = if (ordinal == 1) base else "$base-$ordinal"

private fun isSafeRemoteUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.port in setOf(-1, 443) && uri.userInfo == null && uri.fragment == null
}.getOrDefault(false)

private fun isSafeHttpsLink(value: String): Boolean = runCatching {
    McpCatalogPolicy.requireHttpsUrl(value, "Registry link")
    true
}.getOrDefault(false)

private fun isValidRegistryName(value: String): Boolean =
    value.length <= 200 && REGISTRY_NAME.matches(value)

private fun isSafeVersion(value: String): Boolean =
    value.length in 1..128 && SAFE_VERSION.matches(value)

private fun sanitizeRegistryText(value: String, maxChars: Int): String = buildString(minOf(value.length, maxChars)) {
    var previousWhitespace = false
    value.forEach { char ->
        if (length >= maxChars) return@forEach
        when {
            char == '<' || char == '>' || char.isISOControl() -> {
                if (!previousWhitespace && isNotEmpty()) append(' ')
                previousWhitespace = true
            }
            char.isWhitespace() -> {
                if (!previousWhitespace && isNotEmpty()) append(' ')
                previousWhitespace = true
            }
            else -> {
                append(char)
                previousWhitespace = false
            }
        }
    }
}.trim()

private fun requireValidCursor(value: String) {
    if (value.isBlank() || value.length > MAX_CURSOR_CHARS || value.any(Char::isISOControl)) {
        throw McpRegistryException(
            McpRegistryFailureKind.INVALID_RESPONSE,
            "MCP Registry returned an invalid pagination cursor",
        )
    }
}

private fun decodeUtf8Strict(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    throw McpRegistryException(McpRegistryFailureKind.INVALID_JSON, "MCP Registry returned invalid UTF-8")
}

private fun hasBoundedJsonStructure(value: String): Boolean {
    var depth = 0
    var structuralNodes = 0
    var inString = false
    var escaped = false
    for (char in value) {
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{', '[' -> {
                depth++
                structuralNodes++
                if (depth > MAX_JSON_DEPTH || structuralNodes > MAX_JSON_STRUCTURAL_NODES) return false
            }
            '}', ']' -> {
                depth--
                if (depth < 0) return false
            }
            ',' -> {
                structuralNodes++
                if (structuralNodes > MAX_JSON_STRUCTURAL_NODES) return false
            }
        }
    }
    return depth == 0 && !inString && !escaped
}

private fun invalidRegistryResponse(): McpRegistryException = McpRegistryException(
    McpRegistryFailureKind.INVALID_RESPONSE,
    "MCP Registry returned an invalid response shape",
)

private fun registryFallbackNotice(kind: McpRegistryFailureKind): String = when (kind) {
    McpRegistryFailureKind.TIMEOUT -> "MCP Registry 请求超时，当前显示 27 个离线精选。"
    McpRegistryFailureKind.NETWORK -> "MCP Registry 暂时不可达，当前显示 27 个离线精选。"
    else -> "MCP Registry 响应未通过安全校验，当前显示 27 个离线精选。"
}

private fun isEmptyArrayOrNull(element: JsonElement?): Boolean =
    element == null || element.isJsonNull || (element.isJsonArray && element.asJsonArray.size() == 0)

private fun isEmptyObjectOrNull(element: JsonElement?): Boolean =
    element == null || element.isJsonNull || (element.isJsonObject && element.asJsonObject.size() == 0)

private fun JsonElement?.objectOrNull(): JsonObject? =
    this?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject

private fun JsonElement?.stringOrNull(): String? =
    this?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonElement?.booleanOrNull(): Boolean? =
    this?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun JsonElement?.strictIntOrNull(): Int? {
    val primitive = this?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isNumber) return null
    return runCatching {
        val decimal = primitive.asBigDecimal
        if (decimal.stripTrailingZeros().scale() > 0) return null
        decimal.intValueExact()
    }.getOrNull()
}

private fun JsonElement?.instantOrNull(): Instant? =
    stringOrNull()?.takeIf { it.length <= 64 }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private val REGISTRY_NAME = Regex("[A-Za-z0-9.-]+/[A-Za-z0-9._-]+")
private val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
private val NPM_IDENTIFIER = Regex("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]{0,213}")
private val PYPI_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val REGISTRY_TAG_TOKEN = Regex("[a-z0-9]+")
private val DATA_KEYWORDS = listOf("database", "postgres", "mysql", "mongo", "redis", "sqlite", "vector", "sql")
private val BROWSER_KEYWORDS = listOf("browser", "chrome", "playwright", "web search", "scrape", "crawl")
private val CLOUD_KEYWORDS = listOf("cloud", "kubernetes", "docker", "aws", "azure", "infrastructure")
private val RESEARCH_KEYWORDS = listOf(
    "research",
    "paper",
    "arxiv",
    "citation",
    "academic",
    "scientific",
    "science",
    "laboratory",
    "scholar",
    "pubmed",
    "doi",
    "zotero",
    "latex",
    "bibliography",
    "experiment",
    "documentation",
)
private val PRODUCTIVITY_KEYWORDS = listOf("notion", "linear", "calendar", "task", "notes", "slack", "productivity")
private val REGISTRY_RELEVANCE_ORDER = compareBy<McpCatalogEntry>(
    { entry ->
        when (entry.category) {
            McpCatalogCategory.DEVELOPMENT -> 0
            McpCatalogCategory.RESEARCH -> 1
            McpCatalogCategory.DATA -> 2
            McpCatalogCategory.BROWSER -> 3
            McpCatalogCategory.CLOUD -> 4
            McpCatalogCategory.PRODUCTIVITY -> 5
        }
    },
    { entry -> if (entry.installOptions.isEmpty()) 1 else 0 },
    { entry -> entry.name.lowercase(Locale.ROOT) },
    { entry -> entry.registryMetadata?.registryName.orEmpty() },
)
private const val REGISTRY_API_PAGE_LIMIT = 100
private const val MAX_CURSOR_CHARS = 512
private const val MAX_INSTALL_DECLARATIONS = 15
private const val MAX_INSTALL_OPTIONS = 6
private const val MAX_ENVIRONMENT_KEYS = 16
private const val MAX_DECLARATION_IDENTIFIER_CHARS = 320
private const val MAX_JSON_DEPTH = 64
private const val MAX_JSON_STRUCTURAL_NODES = 100_000
