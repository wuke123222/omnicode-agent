package dev.omnicode.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface DatabasePasswordReader {
    fun load(connectionId: String, jdbcUrl: String): String
}

/** Passwords are bound to the exact JDBC URL digest so editing an endpoint cannot reuse them. */
@Service(Service.Level.APP)
class DatabaseCredentialStore : DatabasePasswordReader {
    override fun load(connectionId: String, jdbcUrl: String): String {
        validateCoordinates(connectionId, jdbcUrl)
        return PasswordSafe.instance.getPassword(attributes(connectionId, jdbcUrl)).orEmpty()
    }

    fun save(connectionId: String, jdbcUrl: String, password: String) {
        validateCoordinates(connectionId, jdbcUrl)
        PasswordSafe.instance.setPassword(attributes(connectionId, jdbcUrl), password.takeIf(String::isNotBlank))
    }

    fun clear(connectionId: String, jdbcUrl: String) = save(connectionId, jdbcUrl, "")

    fun hasPassword(connectionId: String, jdbcUrl: String): Boolean = load(connectionId, jdbcUrl).isNotBlank()

    private fun attributes(connectionId: String, jdbcUrl: String): CredentialAttributes = CredentialAttributes(
        generateServiceName(SERVICE_NAME, "${connectionId.trim()}/${jdbcUrl.sha256()}"),
    )

    private fun validateCoordinates(connectionId: String, jdbcUrl: String) {
        require(connectionId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid database connection id" }
        require(jdbcUrl.isNotBlank() && jdbcUrl.length <= 4_096) { "Invalid database JDBC URL" }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val SERVICE_NAME = "dev.omnicode.agent.database.credentials"

        fun getInstance(): DatabaseCredentialStore =
            ApplicationManager.getApplication().getService(DatabaseCredentialStore::class.java)
    }
}
