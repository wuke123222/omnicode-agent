package dev.omnicode.service

import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import dev.omnicode.util.Json
import java.security.MessageDigest

/**
 * Small, secret-free persistence boundary for the last known-good model directory.
 *
 * API keys, headers and provider responses are never written here.  Only the normalized model
 * ids and a bounded status string are retained, keyed by the non-secret discovery settings.
 * Keeping this separate from [ProviderModelCatalogService] makes the failure path testable and
 * prevents a transient network failure from blanking the selector after an IDE restart.
 */
internal interface ProviderModelCatalogPersistentCache {
    fun load(key: ProviderModelCatalogCacheKey): ProviderModelCatalog?
    fun save(key: ProviderModelCatalogCacheKey, catalog: ProviderModelCatalog)
}

internal class IdeProviderModelCatalogPersistentCache(
    private val properties: PropertiesComponent = PropertiesComponent.getInstance(),
) : ProviderModelCatalogPersistentCache {
    override fun load(key: ProviderModelCatalogCacheKey): ProviderModelCatalog? = runCatching {
        val raw = properties.getValue(storageKey(key)) ?: return null
        val objectValue = Json.parseObject(raw)
        val models = objectValue.get("models")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            ?.distinct()
            ?.take(MAX_MODELS)
            .orEmpty()
        if (models.isEmpty()) return null
        ProviderModelCatalog(
            providerId = objectValue.safeString("providerId", MAX_FIELD_CHARS),
            providerName = objectValue.safeString("providerName", MAX_FIELD_CHARS),
            models = models,
            discoveredRemotely = objectValue.get("discoveredRemotely")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean == true,
            status = objectValue.safeString("status", MAX_STATUS_CHARS)
                .ifBlank { "Showing the last known-good model list." },
        )
    }.getOrNull()

    override fun save(key: ProviderModelCatalogCacheKey, catalog: ProviderModelCatalog) {
        if (!shouldCacheProviderModelCatalog(catalog)) return
        runCatching {
            val boundedModels = catalog.models.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_MODELS)
                .toList()
            if (boundedModels.isEmpty()) return
            val value = Json.gson.toJson(
                mapOf(
                    "providerId" to catalog.providerId.take(MAX_FIELD_CHARS),
                    "providerName" to catalog.providerName.take(MAX_FIELD_CHARS),
                    "models" to boundedModels,
                    "discoveredRemotely" to catalog.discoveredRemotely,
                    "status" to catalog.status.take(MAX_STATUS_CHARS),
                ),
            )
            // PropertiesComponent is intended for small values.  Refuse rather than truncate a
            // JSON document, because a partial cache is worse than a visible provider fallback.
            if (value.length <= MAX_SERIALIZED_CHARS) properties.setValue(storageKey(key), value)
        }
    }

    private fun storageKey(key: ProviderModelCatalogCacheKey): String {
        val source = listOf(
            key.providerId,
            key.baseUrl,
            key.region,
            key.apiVersion,
            key.proxyMode,
            key.requestTimeoutSeconds.toString(),
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "omnicode.modelCatalog.$digest"
    }

    private fun JsonObject.safeString(name: String, maxChars: Int): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.take(maxChars)
        .orEmpty()

    private companion object {
        const val MAX_MODELS = 512
        const val MAX_FIELD_CHARS = 160
        const val MAX_STATUS_CHARS = 320
        const val MAX_SERIALIZED_CHARS = 120_000
    }
}
