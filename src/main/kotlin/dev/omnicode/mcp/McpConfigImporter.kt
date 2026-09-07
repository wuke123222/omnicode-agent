package dev.omnicode.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import dev.omnicode.settings.parseCommandLine
import java.util.UUID

/** Bounded, side-effect-free importer for Copilot/VS Code and Claude Desktop MCP JSON. */
object McpConfigImporter {
    fun parse(raw: String): List<McpServerConfig> {
        require(raw.length <= MAX_JSON_CHARS) { "MCP 配置超过安全长度限制。" }
        val root = runCatching { JsonParser.parseString(raw).objectOrNull() }.getOrNull()
            ?: throw IllegalArgumentException("MCP 配置不是有效的 JSON 对象。")
        val servers = root.get("servers")?.objectOrNull()
            ?: root.get("mcpServers")?.objectOrNull()
            ?: throw IllegalArgumentException("未找到 servers 或 mcpServers。")
        require(servers.size() in 1..MAX_SERVERS) { "一次只能导入 1-$MAX_SERVERS 个 MCP 服务器。" }
        return servers.entrySet().map { (rawName, element) -> parseServer(rawName, element) }
    }

    private fun parseServer(rawName: String, element: JsonElement): McpServerConfig {
        val server = element.objectOrNull()
            ?: throw IllegalArgumentException("$rawName 的配置必须是对象。")
        val name = rawName.trim().take(120).ifBlank { "MCP Server" }
        require(name.none(Char::isISOControl)) { "MCP 名称包含控制字符。" }
        val url = server.string("url").trim().take(4_096)
        val type = server.string("type").lowercase()
        return if (url.isNotBlank() || type in HTTP_TYPES) parseHttp(name, url, server) else parseStdio(name, server)
    }

    private fun parseHttp(name: String, url: String, server: JsonObject): McpServerConfig {
        validateMcpHttpEndpoint(url)
        val authMode = when {
            server.has("oauth") || server.has("oauthClientId") -> McpHttpAuthMode.OAUTH
            server.has("headers") -> McpHttpAuthMode.BEARER
            else -> McpHttpAuthMode.NONE
        }
        val scopes = server.get("oauthScopes")?.let { value ->
            when {
                value.isJsonArray -> value.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                value.isJsonPrimitive -> value.asString.split(Regex("[\\s,]+"))
                else -> emptyList()
            }
        }.orEmpty().map(String::trim).filter(String::isNotBlank).distinct().take(64)
        return McpServerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            enabled = false,
            command = "",
            arguments = emptyList(),
            environmentKeys = emptySet(),
            workingDirectory = ".",
            transport = McpTransport.HTTP,
            url = url,
            httpAuthMode = authMode,
            oauthClientId = server.string("oauthClientId").take(2_048),
            oauthScopes = scopes,
        )
    }

    private fun parseStdio(name: String, server: JsonObject): McpServerConfig {
        val command = server.string("command").trim().take(1_024)
        require(command.isNotBlank() && command.none(Char::isISOControl)) { "$name 缺少安全的 command。" }
        val arguments = server.get("args")?.let { value ->
            when {
                value.isJsonArray -> value.asJsonArray.mapIndexed { index, item ->
                    item.takeIf(JsonElement::isJsonPrimitive)?.asString?.take(MAX_ARGUMENT_CHARS)
                        ?: throw IllegalArgumentException("$name 的 args[$index] 必须是字符串。")
                }
                value.isJsonPrimitive -> parseCommandLine(value.asString)
                else -> emptyList()
            }
        }.orEmpty().take(MAX_ARGUMENTS)
        require(arguments.sumOf(String::length) <= MAX_ARGUMENT_TOTAL_CHARS) {
            "$name 的参数超过安全长度限制。"
        }
        val environmentKeys = server.get("env")?.objectOrNull()?.entrySet().orEmpty()
            .map { it.key }
            .filter(ENVIRONMENT_KEY::matches)
            .distinct()
            .take(16)
            .toSet()
        return McpServerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            enabled = false,
            command = command,
            arguments = arguments,
            environmentKeys = environmentKeys,
            workingDirectory = server.string("cwd").trim().ifBlank { "." }.take(4_096),
        )
    }

    private fun JsonObject.string(name: String): String = get(name)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asString
        .orEmpty()

    private fun JsonElement.objectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject

    private const val MAX_JSON_CHARS = 256 * 1_024
    private const val MAX_SERVERS = 32
    private const val MAX_ARGUMENTS = 64
    private const val MAX_ARGUMENT_CHARS = 2_048
    private const val MAX_ARGUMENT_TOTAL_CHARS = 16_384
    private val HTTP_TYPES = setOf("http", "sse", "streamable-http")
    private val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
}
