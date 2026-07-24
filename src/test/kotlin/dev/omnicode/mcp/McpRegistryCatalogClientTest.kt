package dev.omnicode.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.omnicode.OMNICODE_MCP_USER_AGENT
import dev.omnicode.settings.McpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpRegistryCatalogClientTest {
    @Test
    fun `loads five hundred unique active entries from bounded pages and caches metadata only`() = runBlocking {
        val transport = RecordingRegistryTransport { _, requestIndex ->
            val pageIndex = requestIndex % 5
            registryResponse(
                entries = (pageIndex * 100 until (pageIndex + 1) * 100).map(::activeRegistryItem),
                nextCursor = if (pageIndex < 4) "cursor-${pageIndex + 1}" else null,
            )
        }
        val client = McpRegistryCatalogClient(transport, McpRegistryLoadLimits())

        val first = client.load()

        assertEquals(500, first.entries.size)
        assertEquals(500, first.entries.mapNotNull { it.registryMetadata?.registryName }.distinct().size)
        assertEquals(5, first.pagesLoaded)
        assertEquals(0, first.rejectedEntries)
        assertFalse(first.fromCache)
        assertFalse(first.truncated)
        assertTrue(first.entries.all { it.source == McpCatalogSource.MCP_REGISTRY })
        assertTrue(first.entries.all { it.riskLevel == McpCatalogRiskLevel.HIGH })
        assertTrue(first.entries.all { it.registryMetadata?.status == McpRegistryServerStatus.ACTIVE })
        assertTrue(first.entries.none { it.registryMetadata?.reviewed == true })
        assertTrue(first.entries.all { it.installOptions.isEmpty() })

        assertEquals(
            listOf(
                "version=latest&limit=100",
                "version=latest&limit=100&cursor=cursor-1",
                "version=latest&limit=100&cursor=cursor-2",
                "version=latest&limit=100&cursor=cursor-3",
                "version=latest&limit=100&cursor=cursor-4",
            ),
            transport.requests.map { it.uri.rawQuery },
        )
        transport.requests.forEach { request ->
            assertEquals("https", request.uri.scheme)
            assertEquals("registry.modelcontextprotocol.io", request.uri.host)
            assertEquals("/v0.1/servers", request.uri.path)
            assertEquals(-1, request.uri.port)
            assertNull(request.uri.userInfo)
            assertNull(request.uri.fragment)
            assertEquals(Duration.ofSeconds(8), request.connectTimeout)
            assertEquals(Duration.ofSeconds(12), request.requestTimeout)
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(OMNICODE_MCP_USER_AGENT, request.headers["User-Agent"])
        }

        val cached = client.load()
        assertTrue(cached.fromCache)
        assertEquals(first.entries.map(McpCatalogEntry::id), cached.entries.map(McpCatalogEntry::id))
        assertEquals(5, transport.requests.size)

        val refreshed = client.load(forceRefresh = true)
        assertFalse(refreshed.fromCache)
        assertEquals(500, refreshed.entries.size)
        assertEquals(10, transport.requests.size)
    }

    @Test
    fun `skips malformed deprecated and duplicate rows while retaining unsupported declarations`() = runBlocking {
        val npm = packageDeclaration(
            registryType = "npm",
            identifier = "@safe/example-mcp",
            transport = "stdio",
            version = "1.2.3",
            environmentNames = listOf("API_TOKEN"),
            secretValue = "top-secret-value",
            registryBaseUrl = "https://registry.npmjs.org/",
        )
        val safeRemote = remoteDeclaration("streamable-http", "https://mcp.example.com/api")
        val unsupportedOci = packageDeclaration(
            registryType = "oci",
            identifier = "ghcr.io/example/server",
            transport = "stdio",
            version = "2.0.0",
        )
        val unsupportedSse = remoteDeclaration(
            type = "sse",
            url = "https://legacy.example.com/sse",
            includeHeaders = true,
        )
        val alpha = activeRegistryItem(
            index = 1,
            registryName = "io.example/alpha",
            title = "<b>Alpha</b>\u0000 Server",
            description = "Useful <script>alert</script> database server\nfor projects",
            packages = listOf(npm),
            remotes = listOf(safeRemote),
        )
        val metadataOnly = activeRegistryItem(
            index = 2,
            registryName = "io.example/metadata-only",
            packages = listOf(unsupportedOci),
            remotes = listOf(unsupportedSse),
        )
        val missingServer = JsonObject().apply {
            add("server", JsonNull.INSTANCE)
            add("_meta", officialMetadata())
        }
        val deprecated = activeRegistryItem(index = 3, registryName = "io.example/deprecated").also {
            officialObject(it).addProperty("status", "deprecated")
        }
        val duplicate = activeRegistryItem(index = 4, registryName = "io.example/alpha")
        val transport = RecordingRegistryTransport {
                _, _ -> registryResponse(listOf(alpha, metadataOnly, JsonNull.INSTANCE, missingServer, deprecated, duplicate))
        }
        val client = McpRegistryCatalogClient(transport, McpRegistryLoadLimits())

        val result = client.load()

        assertEquals(2, result.entries.size)
        assertEquals(4, result.rejectedEntries)
        assertTrue(result.notices.any { it.contains("4") && it.contains("跳过") })

        val loadedAlpha = result.entries.first { it.registryMetadata?.registryName == "io.example/alpha" }
        assertFalse(loadedAlpha.name.contains('<'))
        assertFalse(loadedAlpha.name.contains('>'))
        assertFalse(loadedAlpha.name.any(Char::isISOControl))
        assertFalse(loadedAlpha.description.contains('<'))
        assertFalse(loadedAlpha.description.contains('>'))
        assertEquals(
            setOf(McpCatalogInstallKind.NPX_PACKAGE, McpCatalogInstallKind.STREAMABLE_HTTP),
            loadedAlpha.installOptions.map(McpCatalogInstallOption::kind).toSet(),
        )
        val npx = loadedAlpha.installOptions.first { it.kind == McpCatalogInstallKind.NPX_PACKAGE }
        assertEquals("npx", npx.command)
        assertEquals(listOf("--yes", "@safe/example-mcp@1.2.3"), npx.arguments)
        assertEquals(setOf("API_TOKEN"), npx.environmentKeys)
        assertTrue(loadedAlpha.installOptions.flatMap { it.arguments }.none { it.contains("top-secret-value") })
        assertTrue(loadedAlpha.registryMetadata?.installDeclarations.orEmpty().none {
            it.identifier.contains("top-secret-value") || it.unavailableReason.contains("top-secret-value")
        })

        val draft = McpMarketplaceCatalog.createDraft(loadedAlpha, npx.id, "Alpha registry draft")
        assertFalse(draft.config.enabled)
        assertEquals(McpTransport.STDIO, draft.config.transport)
        assertEquals(setOf("API_TOKEN"), draft.requiredCredentialKeys)
        assertTrue(draft.warnings.any { it.contains("未审阅") })
        assertTrue(draft.warnings.any { it.contains("默认禁用") })

        val unsupported = result.entries.first { it.registryMetadata?.registryName == "io.example/metadata-only" }
        assertTrue(unsupported.installOptions.isEmpty())
        assertEquals(2, unsupported.registryMetadata?.installDeclarations?.size)
        assertTrue(unsupported.registryMetadata?.installDeclarations.orEmpty().all { !it.installable })
        assertFailsWith<IllegalArgumentException> {
            McpMarketplaceCatalog.createDraft(unsupported, "missing-option")
        }
    }

    @Test
    fun `directory returns the exact offline catalog when the first Registry request fails`() = runBlocking {
        val transport = RecordingRegistryTransport { _, _ ->
            throw McpRegistryException(McpRegistryFailureKind.NETWORK, "safe fixed failure")
        }
        val directory = McpMarketplaceDirectory(
            McpRegistryCatalogClient(transport, McpRegistryLoadLimits()),
        )

        val snapshot = directory.load()

        assertTrue(snapshot.usingOfflineFallback)
        assertFalse(snapshot.registryAvailable)
        assertEquals(McpRegistryFailureKind.NETWORK, snapshot.registryFailure)
        assertEquals(McpMarketplaceCatalog.entries.map(McpCatalogEntry::id), snapshot.entries.map(McpCatalogEntry::id))
        assertEquals(27, snapshot.entries.size)
        assertTrue(snapshot.entries.all { it.source == McpCatalogSource.BUILT_IN_PRESET })
        assertTrue(snapshot.notice.contains("27"))
        assertFalse(snapshot.notice.contains("safe fixed failure"))
    }

    @Test
    fun `response size media type UTF8 JSON shape and cursor failures are typed and bounded`() = runBlocking {
        val limits = McpRegistryLoadLimits(
            maxEntries = 500,
            maxPages = 20,
            maxPageBytes = 64 * 1_024,
            maxTotalBytes = 64 * 1_024,
        )
        assertEquals(
            McpRegistryFailureKind.RESPONSE_TOO_LARGE,
            registryFailure(
                McpRegistryHttpResponse(200, "application/json", ByteArray(64 * 1_024 + 1)),
                limits,
            ).kind,
        )
        assertEquals(
            McpRegistryFailureKind.CONTENT_TYPE,
            registryFailure(McpRegistryHttpResponse(200, "text/html", "{}".toByteArray()), limits).kind,
        )
        assertEquals(
            McpRegistryFailureKind.INVALID_JSON,
            registryFailure(McpRegistryHttpResponse(200, "application/json", byteArrayOf(0xC3.toByte())), limits).kind,
        )
        assertEquals(
            McpRegistryFailureKind.INVALID_RESPONSE,
            registryFailure(McpRegistryHttpResponse(200, "application/json", "null".toByteArray()), limits).kind,
        )
        val deeplyNested = "[".repeat(65) + "0" + "]".repeat(65)
        assertEquals(
            McpRegistryFailureKind.INVALID_RESPONSE,
            registryFailure(McpRegistryHttpResponse(200, "application/json", deeplyNested.toByteArray()), limits).kind,
        )
        assertEquals(
            McpRegistryFailureKind.INVALID_RESPONSE,
            registryFailure(registryResponse(listOf(activeRegistryItem(1)), nextCursor = "bad\u0000cursor"), limits).kind,
        )
        val countMismatch = registryResponse(listOf(activeRegistryItem(1))).let { response ->
            response.copy(body = response.body.toString(Charsets.UTF_8).replace("\"count\":1", "\"count\":2").toByteArray())
        }
        assertEquals(McpRegistryFailureKind.INVALID_RESPONSE, registryFailure(countMismatch, limits).kind)
    }

    @Test
    fun `entry and page caps stop pagination without exceeding their bounds`() = runBlocking {
        val entryTransport = pagedTransport()
        val entryClient = McpRegistryCatalogClient(
            entryTransport,
            McpRegistryLoadLimits(maxEntries = 250, maxPages = 10, maxPageBytes = 1_024 * 1_024, maxTotalBytes = 8 * 1_024 * 1_024),
        )

        val entryLimited = entryClient.load()

        assertEquals(250, entryLimited.entries.size)
        assertEquals(3, entryLimited.pagesLoaded)
        assertTrue(entryLimited.truncated)
        assertEquals("version=latest&limit=50&cursor=page-2", entryTransport.requests.last().uri.rawQuery)

        val pageTransport = pagedTransport()
        val pageClient = McpRegistryCatalogClient(
            pageTransport,
            McpRegistryLoadLimits(maxEntries = 500, maxPages = 2, maxPageBytes = 1_024 * 1_024, maxTotalBytes = 8 * 1_024 * 1_024),
        )
        val pageLimited = pageClient.load()

        assertEquals(200, pageLimited.entries.size)
        assertEquals(2, pageLimited.pagesLoaded)
        assertEquals(2, pageTransport.requests.size)
        assertTrue(pageLimited.truncated)
    }

    @Test
    fun `dynamic snapshots support source search up to one thousand results and reject oversized input`() = runBlocking {
        val transport = RecordingRegistryTransport { _, requestIndex ->
            val pageIndex = requestIndex
            registryResponse(
                entries = (pageIndex * 100 until (pageIndex + 1) * 100).map(::activeRegistryItem),
                nextCursor = if (pageIndex < 4) "search-$pageIndex" else null,
            )
        }
        val registryEntries = McpRegistryCatalogClient(transport, McpRegistryLoadLimits()).load().entries
        val candidates = McpMarketplaceCatalog.entries + registryEntries

        val registryMatches = McpMarketplaceCatalog.search(
            candidates,
            McpCatalogQuery(
                text = "registry server",
                sources = setOf(McpCatalogSource.MCP_REGISTRY),
                maxResults = 600,
            ),
        )

        assertEquals(500, registryMatches.size)
        assertTrue(registryMatches.all { it.source == McpCatalogSource.MCP_REGISTRY })
        assertFailsWith<IllegalArgumentException> {
            McpMarketplaceCatalog.search(Collections.nCopies(2_001, registryEntries.first()))
        }
        assertFailsWith<IllegalArgumentException> { McpCatalogQuery(maxResults = 1_001) }
    }

    @Test
    fun `cancellation is never converted into a Registry network failure or offline snapshot`() = runBlocking {
        val transport = RecordingRegistryTransport { _, _ -> throw CancellationException("cancel test") }
        val client = McpRegistryCatalogClient(transport, McpRegistryLoadLimits())
        val directory = McpMarketplaceDirectory(client)

        assertFailsWith<CancellationException> { client.load() }
        assertFailsWith<CancellationException> { directory.load(forceRefresh = true) }
    }

    @Test
    fun `real coroutine cancellation interrupts a blocking Registry transport`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val client = McpRegistryCatalogClient(
            RecordingRegistryTransport { _, _ ->
                entered.complete(Unit)
                Thread.sleep(60_000)
                registryResponse(emptyList())
            },
            McpRegistryLoadLimits(),
        )
        val task = async { client.load() }

        withTimeout(2_000) { entered.await() }
        withTimeout(2_000) { task.cancelAndJoin() }

        assertTrue(task.isCancelled)
    }

    @Test
    fun `integrity hash packages stay metadata only and developer research entries rank first`() = runBlocking {
        val hashedPackage = packageDeclaration(
            registryType = "npm",
            identifier = "@safe/hashed-mcp",
            transport = "stdio",
            version = "1.0.0",
            fileSha256 = "a".repeat(64),
        )
        val transport = RecordingRegistryTransport { _, _ ->
            registryResponse(
                listOf(
                    activeRegistryItem(1, registryName = "io.test/tasks", description = "Notion task productivity server"),
                    activeRegistryItem(2, registryName = "io.test/papers", description = "Scientific research paper and citation tools"),
                    activeRegistryItem(3, registryName = "io.test/code", description = "Compiler and developer code tools"),
                    activeRegistryItem(4, registryName = "io.test/hashed", packages = listOf(hashedPackage)),
                ),
            )
        }

        val entries = McpRegistryCatalogClient(transport, McpRegistryLoadLimits()).load().entries

        assertEquals(McpCatalogCategory.DEVELOPMENT, entries[0].category)
        assertEquals(McpCatalogCategory.DEVELOPMENT, entries[1].category)
        assertEquals(McpCatalogCategory.RESEARCH, entries[2].category)
        val hashed = entries.first { it.registryMetadata?.registryName == "io.test/hashed" }
        assertTrue(hashed.installOptions.isEmpty())
        assertTrue(hashed.registryMetadata?.installDeclarations.orEmpty().single().unavailableReason.contains("integrity"))
    }

    private suspend fun registryFailure(
        response: McpRegistryHttpResponse,
        limits: McpRegistryLoadLimits,
    ): McpRegistryException {
        val client = McpRegistryCatalogClient(RecordingRegistryTransport { _, _ -> response }, limits)
        return try {
            client.load()
            throw AssertionError("Registry load unexpectedly succeeded")
        } catch (failure: McpRegistryException) {
            failure
        }
    }

    private fun pagedTransport(): RecordingRegistryTransport = RecordingRegistryTransport { request, requestIndex ->
        val limit = request.uri.rawQuery.substringAfter("limit=").substringBefore('&').toInt()
        registryResponse(
            entries = (requestIndex * 100 until requestIndex * 100 + limit).map(::activeRegistryItem),
            nextCursor = "page-${requestIndex + 1}",
        )
    }
}

private class RecordingRegistryTransport(
    private val responder: (McpRegistryHttpRequest, Int) -> McpRegistryHttpResponse,
) : McpRegistryHttpTransport {
    private val mutableRequests = ArrayList<McpRegistryHttpRequest>()
    val requests: List<McpRegistryHttpRequest> get() = Collections.unmodifiableList(mutableRequests)

    override fun execute(request: McpRegistryHttpRequest): McpRegistryHttpResponse {
        val index = mutableRequests.size
        mutableRequests += request
        return responder(request, index)
    }
}

private fun registryResponse(
    entries: List<JsonElement>,
    nextCursor: String? = null,
    contentType: String = "application/json; charset=utf-8",
): McpRegistryHttpResponse {
    val servers = JsonArray().apply { entries.forEach(::add) }
    val metadata = JsonObject().apply {
        addProperty("count", entries.size)
        if (nextCursor == null) add("nextCursor", JsonNull.INSTANCE) else addProperty("nextCursor", nextCursor)
    }
    val root = JsonObject().apply {
        add("servers", servers)
        add("metadata", metadata)
    }
    return McpRegistryHttpResponse(200, contentType, root.toString().toByteArray(Charsets.UTF_8))
}

private fun activeRegistryItem(
    index: Int,
    registryName: String = "io.test/server-$index",
    title: String = "Registry Server $index",
    description: String = "Registry server metadata entry $index for developer projects.",
    packages: List<JsonElement> = emptyList(),
    remotes: List<JsonElement> = emptyList(),
): JsonObject {
    val server = JsonObject().apply {
        addProperty("name", registryName)
        addProperty("title", title)
        addProperty("description", description)
        addProperty("version", "1.0.$index")
        if (packages.isNotEmpty()) add("packages", JsonArray().apply { packages.forEach(::add) })
        if (remotes.isNotEmpty()) add("remotes", JsonArray().apply { remotes.forEach(::add) })
        add("repository", JsonObject().apply {
            addProperty("url", "https://github.com/example/server-$index")
            addProperty("source", "github")
        })
    }
    return JsonObject().apply {
        add("server", server)
        add("_meta", officialMetadata())
    }
}

private fun officialMetadata(): JsonObject = JsonObject().apply {
    add("io.modelcontextprotocol.registry/official", JsonObject().apply {
        addProperty("status", "active")
        addProperty("isLatest", true)
        addProperty("publishedAt", "2026-07-01T00:00:00Z")
        addProperty("updatedAt", "2026-07-02T00:00:00Z")
    })
}

private fun officialObject(wrapper: JsonObject): JsonObject = wrapper
    .getAsJsonObject("_meta")
    .getAsJsonObject("io.modelcontextprotocol.registry/official")

private fun packageDeclaration(
    registryType: String,
    identifier: String,
    transport: String,
    version: String,
    environmentNames: List<String> = emptyList(),
    secretValue: String? = null,
    registryBaseUrl: String? = null,
    fileSha256: String? = null,
): JsonObject = JsonObject().apply {
    addProperty("registryType", registryType)
    addProperty("identifier", identifier)
    addProperty("version", version)
    registryBaseUrl?.let { addProperty("registryBaseUrl", it) }
    fileSha256?.let { addProperty("fileSha256", it) }
    add("transport", JsonObject().apply { addProperty("type", transport) })
    if (environmentNames.isNotEmpty()) {
        add("environmentVariables", JsonArray().apply {
            environmentNames.forEach { name ->
                add(JsonObject().apply {
                    addProperty("name", name)
                    secretValue?.let { addProperty("value", it) }
                })
            }
        })
    }
}

private fun remoteDeclaration(
    type: String,
    url: String,
    includeHeaders: Boolean = false,
): JsonObject = JsonObject().apply {
    addProperty("type", type)
    addProperty("url", url)
    if (includeHeaders) {
        add("headers", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("name", "Authorization")
                addProperty("value", "secret-must-not-survive")
            })
        })
    }
}
