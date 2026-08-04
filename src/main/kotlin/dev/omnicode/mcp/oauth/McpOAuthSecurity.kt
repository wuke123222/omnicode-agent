package dev.omnicode.mcp.oauth

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

object McpOAuthPkce {
    private val random = SecureRandom()

    fun generate(): McpPkcePair {
        val verifier = randomUrlToken(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        return McpPkcePair(verifier = verifier, challenge = challenge)
    }

    internal fun isValidVerifier(value: String): Boolean =
        value.length in 43..128 && value.all { it.isLetterOrDigit() || it in "-._~" }

    private fun randomUrlToken(byteCount: Int): String = ByteArray(byteCount).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

object McpOAuthState {
    private val random = SecureRandom()

    fun generate(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun matches(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        if (expected.length !in 1..1_024 || actual.length !in 1..1_024) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }
}

internal fun canonicalMcpResource(value: URI): URI {
    requireAbsoluteHttpUri(value, "MCP resource", allowLoopbackHttp = true)
    val scheme = value.scheme.lowercase(Locale.ROOT)
    val host = value.host.lowercase(Locale.ROOT)
    val port = value.port.takeUnless { it == 443 && scheme == "https" || it == 80 && scheme == "http" } ?: -1
    val path = value.rawPath.orEmpty().let { if (it == "/") "" else it }
    return rawHttpUri(scheme, host, port, path, value.rawQuery)
}

internal fun requireAuthorizationServerUri(value: URI, label: String): URI {
    requireAbsoluteHttpUri(value, label, allowLoopbackHttp = false)
    return value
}

internal fun requireRedirectUri(value: URI): URI {
    require(value.isAbsolute && value.host != null) { "OAuth redirect URI must be absolute" }
    require(value.rawUserInfo == null && value.rawFragment == null) {
        "OAuth redirect URI must not contain credentials or a fragment"
    }
    val scheme = value.scheme.lowercase(Locale.ROOT)
    require(scheme == "https" || scheme == "http" && isLoopbackHost(value.host)) {
        "OAuth redirect URI must use HTTPS or literal loopback HTTP"
    }
    return value
}

internal fun requireMetadataUri(value: URI, label: String): URI {
    requireAbsoluteHttpUri(value, label, allowLoopbackHttp = true)
    return value
}

private fun requireAbsoluteHttpUri(value: URI, label: String, allowLoopbackHttp: Boolean) {
    require(value.isAbsolute && value.host != null) { "$label must be an absolute URL" }
    require(value.rawUserInfo == null && value.rawFragment == null) {
        "$label must not contain credentials or a fragment"
    }
    val scheme = value.scheme.lowercase(Locale.ROOT)
    require(scheme == "https" || allowLoopbackHttp && scheme == "http" && isLoopbackHost(value.host)) {
        "$label must use HTTPS"
    }
}

internal fun isLoopbackHost(value: String): Boolean {
    val host = value.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
    if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
    val octets = host.split('.')
    return octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 } && octets.first() == "127"
}

/** Exact scheme/host/effective-port match used before following challenge discovery URLs. */
internal fun isSameHttpOrigin(left: URI, right: URI): Boolean = runCatching {
    val leftScheme = left.scheme.lowercase(Locale.ROOT)
    val rightScheme = right.scheme.lowercase(Locale.ROOT)
    leftScheme == rightScheme &&
        left.host.lowercase(Locale.ROOT) == right.host.lowercase(Locale.ROOT) &&
        effectiveHttpPort(leftScheme, left.port) == effectiveHttpPort(rightScheme, right.port)
}.getOrDefault(false)

private fun effectiveHttpPort(scheme: String, port: Int): Int = when {
    port >= 0 -> port
    scheme == "https" -> 443
    scheme == "http" -> 80
    else -> -1
}

internal fun rawHttpUri(scheme: String, host: String, port: Int, rawPath: String, rawQuery: String? = null): URI {
    val authorityHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
    return URI(
        buildString {
            append(scheme).append("://").append(authorityHost)
            if (port >= 0) append(':').append(port)
            append(rawPath)
            rawQuery?.let { append('?').append(it) }
        },
    )
}

internal fun requireBoundedSecret(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_SECRET_CHARS && !value.contains('\u0000')) {
        "$label is missing or exceeds the supported length"
    }
}

internal fun safeProtocolValue(value: String?, maxChars: Int = 256): String? = value
    ?.takeIf { it.length <= maxChars && it.all { char -> char.code >= 0x20 && char != '\u007f' } }

internal const val MAX_SECRET_CHARS = 65_536
