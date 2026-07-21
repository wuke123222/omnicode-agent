package dev.omnicode.settings

import com.google.gson.JsonObject
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import dev.omnicode.util.Json
import java.net.URI

internal data class McpOAuthStoredSession(
    val configurationBinding: String,
    val resource: String,
    val issuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String,
    val clientSecretExpiresAtEpochSeconds: Long,
    val tokenEndpointAuthMethod: String,
    val redirectUri: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val scopes: List<String>,
    val expiresAtEpochMillis: Long,
) {
    override fun toString(): String =
        "McpOAuthStoredSession(resource=$resource, issuer=$issuer, clientId=$clientId, credentials=[REDACTED])"
}

@Service(Service.Level.APP)
internal class McpOAuthCredentialStore : McpOAuthSessionStore {
    override fun load(serverId: String): McpOAuthStoredSession? {
        validateMcpHttpServerId(serverId)
        val encoded = PasswordSafe.instance.getPassword(attributes(serverId)).orEmpty()
        return decodeMcpOAuthSession(encoded)
    }

    override fun save(serverId: String, session: McpOAuthStoredSession) {
        validateMcpHttpServerId(serverId)
        PasswordSafe.instance.setPassword(attributes(serverId), encodeMcpOAuthSession(session))
    }

    override fun clear(serverId: String) {
        validateMcpHttpServerId(serverId)
        PasswordSafe.instance.setPassword(attributes(serverId), null)
    }

    private fun attributes(serverId: String): CredentialAttributes = CredentialAttributes(
        generateServiceName(SERVICE_NAME, serverId.trim()),
    )

    companion object {
        private const val SERVICE_NAME = "dev.omnicode.agent.mcp.oauth.credentials"

        fun getInstance(): McpOAuthCredentialStore =
            ApplicationManager.getApplication().getService(McpOAuthCredentialStore::class.java)
    }
}

internal interface McpOAuthSessionStore {
    fun load(serverId: String): McpOAuthStoredSession?
    fun save(serverId: String, session: McpOAuthStoredSession)
    fun clear(serverId: String)
    fun hasSession(serverId: String): Boolean = load(serverId) != null
}

internal fun encodeMcpOAuthSession(session: McpOAuthStoredSession): String {
    requireValidOAuthSession(session)
    return Json.stringify(JsonObject().apply {
        addProperty("version", 2)
        addProperty("configurationBinding", session.configurationBinding)
        addProperty("resource", session.resource)
        addProperty("issuer", session.issuer)
        addProperty("tokenEndpoint", session.tokenEndpoint)
        addProperty("clientId", session.clientId)
        addProperty("clientSecret", session.clientSecret)
        addProperty("clientSecretExpiresAtEpochSeconds", session.clientSecretExpiresAtEpochSeconds)
        addProperty("tokenEndpointAuthMethod", session.tokenEndpointAuthMethod)
        addProperty("redirectUri", session.redirectUri)
        addProperty("accessToken", session.accessToken)
        addProperty("refreshToken", session.refreshToken)
        addProperty("tokenType", session.tokenType)
        add("scopes", Json.gson.toJsonTree(session.scopes))
        addProperty("expiresAtEpochMillis", session.expiresAtEpochMillis)
    })
}

internal fun decodeMcpOAuthSession(value: String): McpOAuthStoredSession? {
    if (value.isBlank() || value.length > MAX_STORED_OAUTH_CHARS) return null
    return runCatching {
        val json = Json.parseObject(value)
        val version = json.get("version")?.asInt
        require(version == 1 || version == 2)
        val session = McpOAuthStoredSession(
            configurationBinding = if (version == 2) json.requiredString("configurationBinding") else "",
            resource = json.requiredString("resource"),
            issuer = json.requiredString("issuer"),
            tokenEndpoint = json.requiredString("tokenEndpoint"),
            clientId = json.requiredString("clientId"),
            clientSecret = json.optionalString("clientSecret"),
            clientSecretExpiresAtEpochSeconds = json.get("clientSecretExpiresAtEpochSeconds")?.asLong ?: 0L,
            tokenEndpointAuthMethod = json.optionalString("tokenEndpointAuthMethod").ifBlank { "none" },
            redirectUri = json.requiredString("redirectUri"),
            accessToken = json.requiredString("accessToken"),
            refreshToken = json.optionalString("refreshToken"),
            tokenType = json.optionalString("tokenType").ifBlank { "Bearer" },
            scopes = json.get("scopes")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.runCatching { asString }?.getOrNull() }
                .orEmpty(),
            expiresAtEpochMillis = json.get("expiresAtEpochMillis")?.asLong ?: 0L,
        )
        requireValidOAuthSession(session, allowLegacyBinding = version == 1)
        session
    }.getOrNull()
}

private fun requireValidOAuthSession(session: McpOAuthStoredSession, allowLegacyBinding: Boolean = false) {
    require(
        (allowLegacyBinding && session.configurationBinding.isEmpty()) ||
            OAUTH_CONFIGURATION_BINDING.matches(session.configurationBinding),
    )
    requireHttpsUri(session.resource, allowLoopbackHttp = true)
    requireHttpsUri(session.issuer)
    requireHttpsUri(session.tokenEndpoint)
    val redirect = URI(session.redirectUri)
    require(redirect.scheme.equals("http", ignoreCase = true) && redirect.host == "127.0.0.1" && redirect.port > 0)
    require(session.clientId.isNotBlank() && session.clientId.length <= 2_048)
    requireSafeCredential(session.clientSecret)
    require(session.clientSecretExpiresAtEpochSeconds >= 0L)
    requireSafeCredential(session.accessToken, required = true)
    requireSafeCredential(session.refreshToken)
    require(session.tokenType.equals("Bearer", ignoreCase = true))
    require(session.tokenEndpointAuthMethod in setOf("none", "client_secret_post"))
    require(session.scopes.size <= 128 && session.scopes.all(OAUTH_SCOPE_TOKEN::matches))
    require(session.expiresAtEpochMillis >= 0L)
}

private fun requireHttpsUri(value: String, allowLoopbackHttp: Boolean = false) {
    val uri = URI(value)
    require(uri.isAbsolute && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null)
    val secure = uri.scheme.equals("https", ignoreCase = true)
    val loopback = allowLoopbackHttp && uri.scheme.equals("http", ignoreCase = true) &&
        (uri.host == "127.0.0.1" || uri.host == "::1" || uri.host.equals("localhost", ignoreCase = true))
    require(secure || loopback)
}

private fun requireSafeCredential(value: String, required: Boolean = false) {
    require(!required || value.isNotBlank())
    require(value.length <= MAX_OAUTH_CREDENTIAL_CHARS)
    require(value.none { it == '\u0000' || it == '\r' || it == '\n' })
}

private fun JsonObject.requiredString(name: String): String = get(name)?.asString?.takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("Missing OAuth session field")

private fun JsonObject.optionalString(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

private const val MAX_STORED_OAUTH_CHARS = 256 * 1_024
private const val MAX_OAUTH_CREDENTIAL_CHARS = 64 * 1_024
private val OAUTH_CONFIGURATION_BINDING = Regex("[0-9a-f]{64}")
