package dev.omnicode.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

fun interface McpBearerTokenReader {
    fun load(serverId: String): String
}

@Service(Service.Level.APP)
class McpHttpCredentialStore : McpBearerTokenReader {
    override fun load(serverId: String): String {
        validateMcpHttpServerId(serverId)
        return PasswordSafe.instance.getPassword(attributes(serverId)).orEmpty()
    }

    fun save(serverId: String, token: String) {
        validateMcpHttpServerId(serverId)
        PasswordSafe.instance.setPassword(attributes(serverId), token.takeIf(String::isNotBlank))
    }

    fun clear(serverId: String) = save(serverId, "")

    fun hasToken(serverId: String): Boolean = load(serverId).isNotBlank()

    private fun attributes(serverId: String): CredentialAttributes = CredentialAttributes(
        generateServiceName(SERVICE_NAME, serverId.trim()),
    )

    companion object {
        private const val SERVICE_NAME = "dev.omnicode.agent.mcp.http.credentials"

        fun getInstance(): McpHttpCredentialStore =
            ApplicationManager.getApplication().getService(McpHttpCredentialStore::class.java)
    }
}

internal fun validateMcpHttpServerId(serverId: String) {
    require(serverId.isNotBlank()) { "MCP server id must not be blank" }
    require(serverId.length <= 200) { "MCP server id is too long" }
}
