package dev.omnicode.mcp.oauth

import java.net.URI
import java.util.Locale

object McpOAuthChallengeParser {
    fun parse(wwwAuthenticate: List<String>): McpOAuthChallenge? {
        var firstBearer: McpOAuthChallenge? = null
        wwwAuthenticate.take(MAX_HEADER_COUNT).forEach { rawHeader ->
            val header = rawHeader.take(MAX_HEADER_CHARS)
            splitChallenges(header).forEach { challenge ->
                val firstSpace = challenge.indexOfFirst(Char::isWhitespace)
                val scheme = if (firstSpace < 0) challenge else challenge.substring(0, firstSpace)
                if (!scheme.equals("Bearer", ignoreCase = true)) return@forEach
                val parameters = if (firstSpace < 0) emptyMap() else parseParameters(challenge.substring(firstSpace + 1))
                val resourceMetadata = parameters["resource_metadata"]?.let { raw ->
                    val uri = runCatching { URI(raw) }.getOrElse {
                        throw McpOAuthException("MCP Bearer challenge contains an invalid resource_metadata URL")
                    }
                    runCatching { requireMetadataUri(uri, "MCP resource metadata URL") }.getOrElse {
                        throw McpOAuthException("MCP Bearer challenge resource_metadata URL is not secure")
                    }
                }
                val parsed = McpOAuthChallenge(
                    resourceMetadata = resourceMetadata,
                    scopes = parseScope(parameters["scope"]),
                    error = safeProtocolValue(parameters["error"]),
                    errorDescription = safeProtocolValue(parameters["error_description"], 512),
                )
                if (parsed.resourceMetadata != null) return parsed
                if (firstBearer == null) firstBearer = parsed
            }
        }
        return firstBearer
    }

    private fun splitChallenges(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var quoted = false
        var escaped = false
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                !quoted && char == ',' && startsNewChallenge(value, index + 1) -> {
                    value.substring(start, index).trim().takeIf(String::isNotEmpty)?.let(result::add)
                    start = index + 1
                }
            }
            index++
        }
        value.substring(start).trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private fun startsNewChallenge(value: String, offset: Int): Boolean {
        var index = offset
        while (index < value.length && value[index].isWhitespace()) index++
        val tokenStart = index
        while (index < value.length && isTokenChar(value[index])) index++
        if (index == tokenStart) return false
        while (index < value.length && value[index].isWhitespace()) index++
        return index >= value.length || value[index] != '='
    }

    private fun parseParameters(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        splitCommaSeparated(value).forEach { item ->
            val equals = item.indexOf('=')
            if (equals <= 0) return@forEach
            val name = item.substring(0, equals).trim().lowercase(Locale.ROOT)
            if (name.isEmpty() || !name.all(::isTokenChar) || result.containsKey(name)) return@forEach
            decodeParameterValue(item.substring(equals + 1).trim())?.let { result[name] = it }
        }
        return result
    }

    private fun splitCommaSeparated(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var quoted = false
        var escaped = false
        value.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += value.substring(start).trim()
        return result
    }

    private fun decodeParameterValue(value: String): String? {
        if (!value.startsWith('"')) return value.takeIf { it.all(::isTokenChar) }
        if (value.length < 2 || !value.endsWith('"')) return null
        val decoded = StringBuilder()
        var escaped = false
        value.substring(1, value.length - 1).forEach { char ->
            when {
                escaped -> {
                    decoded.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '\r' || char == '\n' || char == '\u0000' -> return null
                else -> decoded.append(char)
            }
        }
        return decoded.takeUnless { escaped }?.toString()
    }

    internal fun parseScope(value: String?): Set<String> = value.orEmpty()
        .split(Regex("\\s+"))
        .asSequence()
        .filter(String::isNotBlank)
        .take(MAX_SCOPES + 1)
        .onEach { scope ->
            if (scope.length > MAX_SCOPE_CHARS || !scope.all { it.code in 0x21..0x7e }) {
                throw McpOAuthException("OAuth scope is invalid or exceeds the supported length")
            }
        }
        .toCollection(linkedSetOf())
        .also { if (it.size > MAX_SCOPES) throw McpOAuthException("OAuth scope count exceeds the supported limit") }

    private fun isTokenChar(value: Char): Boolean = value.isLetterOrDigit() || value in "!#$%&'*+-.^_`|~"

    private const val MAX_HEADER_COUNT = 16
    private const val MAX_HEADER_CHARS = 8_192
    private const val MAX_SCOPES = 128
    private const val MAX_SCOPE_CHARS = 256
}
