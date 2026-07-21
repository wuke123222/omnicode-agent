package dev.omnicode.provider

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import dev.omnicode.util.Json

internal fun ProviderConnection.sensitiveValues(): List<String> = buildList {
    add(apiKey)
    add(secondarySecret)
    add(sessionToken)
    extraHeaders.forEach { (name, value) ->
        if (name.contains("authorization", true) ||
            name.contains("api-key", true) ||
            name.contains("token", true) ||
            name.contains("secret", true)
        ) {
            add(value)
            add(if (value.startsWith("Bearer ", ignoreCase = true)) value.substring(7) else value)
        }
    }
}.filter { it.isNotBlank() }

internal fun sanitizeProviderText(value: String?, sensitiveValues: Collection<String>): String? {
    if (value == null) return null
    var sanitized: String = value
    sensitiveValues
        .asSequence()
        .filter { it.length >= 4 }
        .distinct()
        .sortedByDescending(String::length)
        .forEach { secret -> sanitized = sanitized.replace(secret, "[REDACTED]") }
    return sanitized
}

internal fun providerStreamException(
    providerName: String,
    payload: JsonObject,
    connection: ProviderConnection,
): ProviderException {
    val error = payload.jsonObjectOrNull("error")
    val message = error?.stringOrNull("message")
        ?: payload.stringOrNull("message")
        ?: "$providerName stream failed"
    val code = error?.stringOrNull("code") ?: error?.stringOrNull("type")
    val safeMessage = sanitizeProviderText(message, connection.sensitiveValues())
        ?.take(2_000)
        .orEmpty()
    val description = if (code.isNullOrBlank()) safeMessage else "$code: $safeMessage"
    return ProviderException(
        "$providerName stream failed${description.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
        responseBody = sanitizeProviderText(Json.stringify(payload), connection.sensitiveValues())?.take(20_000),
    )
}

internal fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.runCatching { asString }?.getOrNull()

internal fun JsonObject.intOrNull(name: String): Int? =
    get(name)?.takeUnless { it.isJsonNull }?.runCatching { asInt }?.getOrNull()

internal fun JsonObject.longOrZero(name: String): Long =
    get(name)?.takeUnless { it.isJsonNull }?.runCatching { asLong }?.getOrDefault(0L) ?: 0L

internal fun JsonObject.jsonObjectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.jsonArrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray
