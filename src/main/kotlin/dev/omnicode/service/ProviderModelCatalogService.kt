package dev.omnicode.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import dev.omnicode.provider.ProviderModelDiscovery
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.settings.OmniCodeSettingsSnapshot
import dev.omnicode.settings.OmniCodeSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class ProviderModelCatalog(
    val providerId: String,
    val providerName: String,
    val models: List<String>,
    val discoveredRemotely: Boolean,
    val status: String,
    val error: String? = null,
)

internal data class ProviderModelCatalogCacheKey(
    val providerId: String,
    val baseUrl: String,
    val region: String,
    val apiVersion: String,
    val proxyMode: String,
    val requestTimeoutSeconds: Long,
)

internal fun providerModelCatalogCacheKey(settings: OmniCodeSettingsSnapshot): ProviderModelCatalogCacheKey =
    ProviderModelCatalogCacheKey(
        providerId = settings.providerId,
        baseUrl = settings.baseUrl,
        region = settings.region,
        apiVersion = settings.apiVersion,
        proxyMode = settings.proxyMode.persistedValue,
        requestTimeoutSeconds = settings.requestTimeoutSeconds,
    )

internal fun shouldCacheProviderModelCatalog(catalog: ProviderModelCatalog): Boolean = catalog.error == null

/**
 * Tracks independent catalog loads until their EDT delivery is claimed. Keeping a request active
 * through the queued UI callback prevents an invalidated result from arriving late.
 */
internal class ProviderModelCatalogRequestQueue {
    private val lock = Any()
    private val active = linkedMapOf<Long, Ticket>()
    private var nextId = 0L

    internal class Ticket(
        internal val id: Long,
        internal val onFinished: () -> Unit,
    ) {
        @Volatile
        internal var job: Job? = null
    }

    fun register(onFinished: () -> Unit): Ticket = synchronized(lock) {
        Ticket(++nextId, onFinished).also { active[it.id] = it }
    }

    /** Runs [action] only while [ticket] can still be delivered. */
    fun ifActive(ticket: Ticket, action: () -> Unit): Boolean = synchronized(lock) {
        if (active[ticket.id] !== ticket) return@synchronized false
        action()
        true
    }

    /** Claims the sole right to deliver this request. */
    fun claim(ticket: Ticket): Boolean = synchronized(lock) {
        active.remove(ticket.id) === ticket
    }

    /** Cancels every pending request atomically with related state invalidation. */
    fun cancelAll(onLocked: () -> Unit = {}): List<Ticket> = synchronized(lock) {
        onLocked()
        active.values.toList().also { active.clear() }
    }

    internal fun activeCount(): Int = synchronized(lock) { active.size }
}

@Service(Service.Level.APP)
class ProviderModelCatalogService(
    private val coroutineScope: CoroutineScope,
) {
    // Keep the IntelliJ service constructor limited to the platform-provided CoroutineScope.
    // The cache is deliberately lazy so headless/test construction does not touch PropertiesComponent
    // until a model catalog is actually requested.
    private val persistentCache: ProviderModelCatalogPersistentCache by lazy {
        IdeProviderModelCatalogPersistentCache()
    }
    private val cache = mutableMapOf<ProviderModelCatalogCacheKey, CacheEntry>()
    private val requests = ProviderModelCatalogRequestQueue()

    /** Returns the last verified catalog without starting network or CLI work. */
    fun cachedCurrent(): ProviderModelCatalog? {
        val settings = OmniCodeSettingsService.getInstance().snapshot()
        val key = providerModelCatalogCacheKey(settings)
        return synchronized(cache) { cache[key]?.catalog } ?: persistentCache.load(key)
    }

    fun loadCurrent(
        forceRefresh: Boolean = false,
        onFinished: () -> Unit = {},
        callback: (ProviderModelCatalog) -> Unit,
    ) {
        val settingsService = OmniCodeSettingsService.getInstance()
        val settings = settingsService.snapshot()
        val key = providerModelCatalogCacheKey(settings)
        val request = requests.register(onFinished)
        // A prior plugin version persisted the CLI fallback ("default") as a catalog. Do not
        // surface that stale placeholder after upgrading: an explicit model-menu open should
        // query the locally authenticated OpenCode CLI again.
        val localOpenCodeCli = ProviderPresets.byId(settings.providerId).protocol ==
            dev.omnicode.provider.ProviderProtocol.CLI_OPENCODE
        var cached: CacheEntry? = null
        requests.ifActive(request) {
            cached = synchronized(cache) { cache[key] }
                ?: persistentCache.load(key)?.let {
                    CacheEntry(
                        Instant.now(),
                        it.copy(status = "缓存：${it.status}"),
                    )
                }
        }
        val cachedEntry = cached
        if (!forceRefresh && !localOpenCodeCli && cachedEntry != null &&
            Duration.between(cachedEntry.at, Instant.now()) < CACHE_TTL
        ) {
            deliver(request) { callback(cachedEntry.catalog) }
            return
        }

        val job = coroutineScope.launch {
            val catalog = runCatching {
                val connection = settingsService.providerConnectionAsync(settings)
                if (
                    ProviderModelDiscovery.supportsRemoteDiscovery(connection.preset.protocol) &&
                    !connection.preset.apiKeyOptional &&
                    connection.apiKey.isBlank()
                ) {
                    ProviderModelCatalog(
                        providerId = connection.preset.id,
                        providerName = connection.preset.displayName,
                        models = emptyList(),
                        discoveredRemotely = false,
                        status = "Save an API key before loading models.",
                        error = "Save the provider API key, apply settings, then open the model list again.",
                    )
                } else {
                    val result = ProviderModelDiscovery.discover(connection)
                    ProviderModelCatalog(
                        providerId = connection.preset.id,
                        providerName = connection.preset.displayName,
                        models = result.models,
                        discoveredRemotely = result.discoveredRemotely,
                        status = result.status,
                    )
                }
            }.getOrElse { error ->
                // Keep the last known-good catalog visible when the provider is temporarily
                // unreachable. The error remains attached so the UI can offer diagnostics,
                // while users can still select a previously verified model.
                val stale = synchronized(cache) { cache[key]?.catalog }
                    ?: persistentCache.load(key)
                ProviderModelCatalog(
                    providerId = settings.providerId,
                    providerName = ProviderPresets.byId(settings.providerId).displayName,
                    models = (stale?.models.orEmpty() + settings.model)
                        .filter(String::isNotBlank)
                        .distinct(),
                    discoveredRemotely = false,
                    status = if (stale != null) {
                        "Unable to refresh models; showing the last known-good list."
                    } else {
                        "Unable to load models"
                    },
                    error = safeMessage(error),
                )
            }
            val accepted = requests.ifActive(request) {
                if (shouldCacheProviderModelCatalog(catalog)) {
                    synchronized(cache) { cache[key] = CacheEntry(Instant.now(), catalog) }
                    persistentCache.save(key, catalog)
                }
            }
            if (accepted) deliver(request) { callback(catalog) }
        }
        if (!requests.ifActive(request) { request.job = job }) job.cancel()
    }

    fun invalidate() {
        val canceled = requests.cancelAll { synchronized(cache) { cache.clear() } }
        canceled.forEach { request ->
            request.job?.cancel()
            dispatchEdt(request.onFinished)
        }
    }

    private fun deliver(request: ProviderModelCatalogRequestQueue.Ticket, callback: () -> Unit) {
        dispatchEdt {
            if (!requests.claim(request)) return@dispatchEdt
            try {
                callback()
            } finally {
                request.onFinished()
            }
        }
    }

    private fun dispatchEdt(callback: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) callback()
        else application.invokeLater(callback, ModalityState.nonModal())
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.lineSequence()?.firstOrNull()?.take(240) ?: error::class.java.simpleName

    private data class CacheEntry(
        val at: Instant,
        val catalog: ProviderModelCatalog,
    )

    companion object {
        private val CACHE_TTL: Duration = Duration.ofMinutes(5)

        fun getInstance(): ProviderModelCatalogService =
            ApplicationManager.getApplication().getService(ProviderModelCatalogService::class.java)
    }
}
