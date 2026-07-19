package dev.omnicode.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

fun interface McpEnvironmentSecretReader {
    fun load(serverId: String, environmentKey: String): String
}

@Service(Service.Level.APP)
class McpEnvironmentCredentialStore : McpEnvironmentSecretReader {
    override fun load(serverId: String, environmentKey: String): String {
        validateMcpCredentialCoordinates(serverId, environmentKey)
        return PasswordSafe.instance.getPassword(attributes(serverId, environmentKey)).orEmpty()
    }

    fun save(serverId: String, environmentKey: String, value: String) {
        validateMcpCredentialCoordinates(serverId, environmentKey)
        PasswordSafe.instance.setPassword(
            attributes(serverId, environmentKey),
            value.takeIf(String::isNotBlank),
        )
    }

    fun clear(serverId: String, environmentKey: String) = save(serverId, environmentKey, "")

    fun hasSecret(serverId: String, environmentKey: String): Boolean = load(serverId, environmentKey).isNotBlank()

    private fun attributes(serverId: String, environmentKey: String): CredentialAttributes = CredentialAttributes(
        generateServiceName(
            SERVICE_NAME,
            "${serverId.trim()}/${environmentKey.trim().uppercase()}",
        ),
    )

    companion object {
        private const val SERVICE_NAME = "dev.omnicode.agent.mcp.credentials"

        fun getInstance(): McpEnvironmentCredentialStore =
            ApplicationManager.getApplication().getService(McpEnvironmentCredentialStore::class.java)
    }
}

internal fun isValidMcpEnvironmentKey(value: String): Boolean =
    value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))

private fun validateMcpCredentialCoordinates(serverId: String, environmentKey: String) {
    require(serverId.isNotBlank()) { "MCP server id must not be blank" }
    require(isValidMcpEnvironmentKey(environmentKey)) { "Invalid MCP environment key: $environmentKey" }
}
