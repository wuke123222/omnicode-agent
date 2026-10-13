package dev.omnicode.mcp

import com.intellij.openapi.application.PathManager
import dev.omnicode.util.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Secret-free persistence for the last known-good MCP Registry directory.
 *
 * The Registry payload is untrusted project-independent data. It is revalidated through the same
 * catalog constructors when read, and the cache never stores headers, tokens, command output, or
 * environment values. A malformed or oversized cache is simply discarded.
 */
internal interface McpRegistryCatalogPersistentCache {
    fun load(): McpRegistryLoadResult?
    fun save(result: McpRegistryLoadResult)
}

internal class IdeMcpRegistryCatalogPersistentCache(
    private val cacheFile: Path = Path.of(PathManager.getConfigPath(), CACHE_FILE_NAME),
) : McpRegistryCatalogPersistentCache {
    override fun load(): McpRegistryLoadResult? = runCatching {
        if (!Files.isRegularFile(cacheFile) || Files.size(cacheFile) > MAX_CACHE_BYTES) return null
        val envelope = Json.gson.fromJson(
            Files.readString(cacheFile, StandardCharsets.UTF_8),
            CachedRegistryEnvelope::class.java,
        ) ?: return null
        if (envelope.schemaVersion != CACHE_SCHEMA_VERSION) return null
        val cachedEntries = envelope.entries.orEmpty().asSequence()
            .take(MAX_CACHED_ENTRIES)
            .mapNotNull(::toCatalogEntry)
            .distinctBy { it.id }
            .toList()
        if (cachedEntries.isEmpty()) return null
        val savedAtEpochMillis = envelope.savedAtEpochMillis.takeIf { it > 0L } ?: return null
        McpRegistryLoadResult(
            entries = cachedEntries,
            pagesLoaded = envelope.pagesLoaded.coerceIn(0, MAX_REGISTRY_PAGES),
            rejectedEntries = envelope.rejectedEntries.coerceAtLeast(0),
            totalResponseBytes = envelope.totalResponseBytes.coerceAtLeast(0),
            truncated = envelope.truncated,
            notices = envelope.notices.orEmpty().take(MAX_NOTICES),
            fromCache = true,
            loadedAtEpochMillis = savedAtEpochMillis,
        )
    }.getOrNull()

    override fun save(result: McpRegistryLoadResult) {
        val registryEntries = result.entries.asSequence()
            .filter { it.source == McpCatalogSource.MCP_REGISTRY && it.registryMetadata != null }
            .take(MAX_CACHED_ENTRIES)
            .map(::fromCatalogEntry)
            .toList()
        if (registryEntries.isEmpty()) return
        runCatching {
            val payload = Json.gson.toJson(
                CachedRegistryEnvelope(
                    schemaVersion = CACHE_SCHEMA_VERSION,
                    savedAtEpochMillis = result.loadedAtEpochMillis,
                    entries = registryEntries,
                    pagesLoaded = result.pagesLoaded,
                    rejectedEntries = result.rejectedEntries,
                    totalResponseBytes = result.totalResponseBytes,
                    truncated = result.truncated,
                    notices = result.notices.take(MAX_NOTICES),
                ),
            )
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_CACHE_BYTES) return
            Files.createDirectories(cacheFile.parent)
            val temp = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    cacheFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    private fun toCatalogEntry(value: CachedRegistryEntry): McpCatalogEntry? = runCatching {
        val metadata = value.registryMetadata ?: return null
        val registryName = metadata.registryName ?: return null
        val category = McpCatalogCategory.entries.firstOrNull { it.name == value.category } ?: return null
        val risk = McpCatalogRiskLevel.entries.firstOrNull { it.name == value.riskLevel } ?: return null
        val links = value.links.orEmpty().mapNotNull { link ->
            val kind = McpCatalogLinkKind.entries.firstOrNull { it.name == link.kind } ?: return@mapNotNull null
            runCatching { McpCatalogLink(kind, link.url.orEmpty()) }.getOrNull()
        }
        val options = value.installOptions.orEmpty().mapNotNull(::toInstallOption)
        val declarations = metadata.installDeclarations.orEmpty().mapNotNull(::toDeclaration)
        McpCatalogEntry(
            id = stableRegistryId(registryName),
            name = value.name.orEmpty(),
            publisher = value.publisher.orEmpty(),
            description = value.description.orEmpty(),
            source = McpCatalogSource.MCP_REGISTRY,
            category = category,
            riskLevel = risk,
            riskSummary = value.riskSummary.orEmpty(),
            tags = value.tags.orEmpty(),
            links = links,
            installOptions = options,
            registryMetadata = McpRegistryEntryMetadata(
                registryName = registryName,
                version = metadata.version.orEmpty(),
                status = McpRegistryServerStatus.entries.firstOrNull { it.name == metadata.status }
                    ?: McpRegistryServerStatus.ACTIVE,
                publishedAt = metadata.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                updatedAt = metadata.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                installDeclarations = declarations,
            ),
        )
    }.getOrNull()

    private fun toDeclaration(value: CachedDeclaration): McpRegistryInstallDeclaration? = runCatching {
        McpRegistryInstallDeclaration(
            type = value.type.orEmpty(),
            identifier = value.identifier.orEmpty(),
            transport = value.transport.orEmpty(),
            version = value.version.orEmpty(),
            installable = value.installable,
            unavailableReason = value.unavailableReason.orEmpty(),
        )
    }.getOrNull()

    private fun toInstallOption(value: CachedInstallOption): McpCatalogInstallOption? = runCatching {
        val kind = McpCatalogInstallKind.entries.firstOrNull { it.name == value.kind } ?: return null
        val transport = dev.omnicode.settings.McpTransport.entries.firstOrNull { it.name == value.transport }
            ?: return null
        val authMode = dev.omnicode.settings.McpHttpAuthMode.entries.firstOrNull { it.name == value.httpAuthMode }
            ?: dev.omnicode.settings.McpHttpAuthMode.NONE
        McpCatalogInstallOption(
            id = value.id.orEmpty(),
            displayName = value.displayName.orEmpty(),
            kind = kind,
            transport = transport,
            command = value.command.orEmpty(),
            arguments = value.arguments.orEmpty(),
            environmentKeys = value.environmentKeys.orEmpty(),
            workingDirectory = value.workingDirectory ?: ".",
            url = value.url.orEmpty(),
            httpAuthMode = authMode,
            oauthClientId = value.oauthClientId.orEmpty(),
            oauthScopes = value.oauthScopes.orEmpty(),
        )
    }.getOrNull()

    private fun fromCatalogEntry(entry: McpCatalogEntry): CachedRegistryEntry {
        val metadata = requireNotNull(entry.registryMetadata)
        return CachedRegistryEntry(
            name = entry.name,
            publisher = entry.publisher,
            description = entry.description,
            category = entry.category.name,
            riskLevel = entry.riskLevel.name,
            riskSummary = entry.riskSummary,
            tags = entry.tags.toList(),
            links = entry.links.map { CachedLink(it.kind.name, it.url) },
            installOptions = entry.installOptions.map { option ->
                CachedInstallOption(
                    id = option.id,
                    displayName = option.displayName,
                    kind = option.kind.name,
                    transport = option.transport.name,
                    command = option.command,
                    arguments = option.arguments,
                    environmentKeys = option.environmentKeys.toList(),
                    workingDirectory = option.workingDirectory,
                    url = option.url,
                    httpAuthMode = option.httpAuthMode.name,
                    oauthClientId = option.oauthClientId,
                    oauthScopes = option.oauthScopes,
                )
            },
            registryMetadata = CachedMetadata(
                registryName = metadata.registryName,
                version = metadata.version,
                status = metadata.status.name,
                publishedAt = metadata.publishedAt?.toString(),
                updatedAt = metadata.updatedAt?.toString(),
                installDeclarations = metadata.installDeclarations.map { declaration ->
                    CachedDeclaration(
                        type = declaration.type,
                        identifier = declaration.identifier,
                        transport = declaration.transport,
                        version = declaration.version,
                        installable = declaration.installable,
                        unavailableReason = declaration.unavailableReason,
                    )
                },
            ),
        )
    }

    private data class CachedRegistryEnvelope(
        val schemaVersion: Int = 0,
        val savedAtEpochMillis: Long = 0L,
        val entries: List<CachedRegistryEntry>? = null,
        val pagesLoaded: Int = 0,
        val rejectedEntries: Int = 0,
        val totalResponseBytes: Int = 0,
        val truncated: Boolean = false,
        val notices: List<String>? = null,
    )

    private data class CachedRegistryEntry(
        val name: String? = null,
        val publisher: String? = null,
        val description: String? = null,
        val category: String? = null,
        val riskLevel: String? = null,
        val riskSummary: String? = null,
        val tags: List<String>? = null,
        val links: List<CachedLink>? = null,
        val installOptions: List<CachedInstallOption>? = null,
        val registryMetadata: CachedMetadata? = null,
    )

    private data class CachedLink(val kind: String? = null, val url: String? = null)

    private data class CachedInstallOption(
        val id: String? = null,
        val displayName: String? = null,
        val kind: String? = null,
        val transport: String? = null,
        val command: String? = null,
        val arguments: List<String>? = null,
        val environmentKeys: List<String>? = null,
        val workingDirectory: String? = null,
        val url: String? = null,
        val httpAuthMode: String? = null,
        val oauthClientId: String? = null,
        val oauthScopes: List<String>? = null,
    )

    private data class CachedMetadata(
        val registryName: String? = null,
        val version: String? = null,
        val status: String? = null,
        val publishedAt: String? = null,
        val updatedAt: String? = null,
        val installDeclarations: List<CachedDeclaration>? = null,
    )

    private data class CachedDeclaration(
        val type: String? = null,
        val identifier: String? = null,
        val transport: String? = null,
        val version: String? = null,
        val installable: Boolean = false,
        val unavailableReason: String? = null,
    )

    private companion object {
        const val CACHE_FILE_NAME = "omnicode-mcp-registry-cache.json"
        const val CACHE_SCHEMA_VERSION = 1
        const val MAX_CACHE_BYTES = 8 * 1_024 * 1_024L
        const val MAX_CACHED_ENTRIES = 1_000
        const val MAX_REGISTRY_PAGES = 25
        const val MAX_NOTICES = 8
    }
}
