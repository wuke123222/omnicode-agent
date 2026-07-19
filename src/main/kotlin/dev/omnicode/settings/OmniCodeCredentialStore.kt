package dev.omnicode.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.canonicalModelApiOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Runtime-only secret values. The custom toString prevents accidental disclosure in diagnostics.
 */
data class ProviderSecrets(
    val apiKey: String = "",
    val secondarySecret: String = "",
    val sessionToken: String = "",
) {
    override fun toString(): String =
        "ProviderSecrets(apiKey=<redacted>, secondarySecret=<redacted>, sessionToken=<redacted>)"
}

@Service(Service.Level.APP)
class OmniCodeCredentialStore {
    /** Loads secrets only when they are bound to [baseUrl]'s canonical origin. */
    fun load(providerId: String, baseUrl: String): ProviderSecrets {
        val requestedOrigin = canonicalModelApiOrigin(baseUrl)
        val boundOrigin = readBoundOrigin(providerId)
        if (boundOrigin.isNotBlank()) {
            requireMatchingCredentialOrigin(boundOrigin, requestedOrigin)
            return readScoped(providerId, requestedOrigin)
        }

        val legacy = readLegacy(providerId)
        if (legacy.isEmpty()) return ProviderSecrets()

        // Credentials saved by versions before origin binding may be migrated only at the
        // provider's built-in endpoint. A custom endpoint must ask the user to re-enter/confirm.
        val defaultOrigin = canonicalModelApiOrigin(ProviderPresets.byId(providerId).defaultBaseUrl)
        requireMatchingCredentialOrigin(defaultOrigin, requestedOrigin)
        save(providerId, baseUrl, legacy)
        return legacy
    }

    /** Saves secrets under an origin-scoped key and records the active binding. */
    fun save(providerId: String, baseUrl: String, secrets: ProviderSecrets) {
        val origin = canonicalModelApiOrigin(baseUrl)
        val previousOrigin = readBoundOrigin(providerId)
        if (secrets.isEmpty()) {
            clearScoped(providerId, origin)
            if (previousOrigin.isNotBlank() && previousOrigin != origin) clearScoped(providerId, previousOrigin)
            clearLegacy(providerId)
            writeBoundOrigin(providerId, "")
            return
        }

        // Write the new scoped values before moving the binding marker. If PasswordSafe fails,
        // readers remain fail-closed on the previous origin instead of leaking a partial secret.
        writeScoped(providerId, origin, secrets)
        writeBoundOrigin(providerId, origin)
        if (previousOrigin.isNotBlank() && previousOrigin != origin) clearScoped(providerId, previousOrigin)
        clearLegacy(providerId)
    }

    suspend fun loadAsync(providerId: String, baseUrl: String): ProviderSecrets = withContext(Dispatchers.IO) {
        load(providerId, baseUrl)
    }

    fun clear(providerId: String) {
        readBoundOrigin(providerId).takeIf(String::isNotBlank)?.let { clearScoped(providerId, it) }
        clearLegacy(providerId)
        writeBoundOrigin(providerId, "")
    }

    private fun readScoped(providerId: String, origin: String): ProviderSecrets = ProviderSecrets(
        apiKey = read(attributes(providerId, origin, SecretKind.API_KEY)),
        secondarySecret = read(attributes(providerId, origin, SecretKind.SECONDARY_SECRET)),
        sessionToken = read(attributes(providerId, origin, SecretKind.SESSION_TOKEN)),
    )

    private fun readLegacy(providerId: String): ProviderSecrets = ProviderSecrets(
        apiKey = read(legacyAttributes(providerId, SecretKind.API_KEY)),
        secondarySecret = read(legacyAttributes(providerId, SecretKind.SECONDARY_SECRET)),
        sessionToken = read(legacyAttributes(providerId, SecretKind.SESSION_TOKEN)),
    )

    private fun writeScoped(providerId: String, origin: String, secrets: ProviderSecrets) {
        write(attributes(providerId, origin, SecretKind.API_KEY), secrets.apiKey)
        write(attributes(providerId, origin, SecretKind.SECONDARY_SECRET), secrets.secondarySecret)
        write(attributes(providerId, origin, SecretKind.SESSION_TOKEN), secrets.sessionToken)
    }

    private fun clearScoped(providerId: String, origin: String) {
        SecretKind.entries.forEach { kind -> write(attributes(providerId, origin, kind), "") }
    }

    private fun clearLegacy(providerId: String) {
        SecretKind.entries.forEach { kind -> write(legacyAttributes(providerId, kind), "") }
    }

    private fun readBoundOrigin(providerId: String): String = read(originAttributes(providerId))

    private fun writeBoundOrigin(providerId: String, origin: String) = write(originAttributes(providerId), origin)

    private fun read(attributes: CredentialAttributes): String =
        PasswordSafe.instance.getPassword(attributes).orEmpty()

    private fun write(attributes: CredentialAttributes, value: String) {
        PasswordSafe.instance.setPassword(attributes, value.takeIf { it.isNotEmpty() })
    }

    private fun attributes(providerId: String, origin: String, kind: SecretKind): CredentialAttributes =
        CredentialAttributes(
            generateServiceName(
                SERVICE_NAME,
                "${normalizedProviderId(providerId)}/origin/${origin.storageKey()}/${kind.storageKey}",
            ),
        )

    private fun legacyAttributes(providerId: String, kind: SecretKind): CredentialAttributes = CredentialAttributes(
        generateServiceName(SERVICE_NAME, "${normalizedProviderId(providerId)}/${kind.storageKey}"),
    )

    private fun originAttributes(providerId: String): CredentialAttributes = CredentialAttributes(
        generateServiceName(SERVICE_NAME, "${normalizedProviderId(providerId)}/bound-origin"),
    )

    private fun normalizedProviderId(providerId: String): String = providerId.trim().also {
        require(it.isNotEmpty()) { "Provider id must not be blank" }
    }

    private fun String.storageKey(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(toByteArray(StandardCharsets.UTF_8))

    private enum class SecretKind(val storageKey: String) {
        API_KEY("api-key"),
        SECONDARY_SECRET("secondary-secret"),
        SESSION_TOKEN("session-token"),
    }

    companion object {
        private const val SERVICE_NAME = "dev.omnicode.agent.credentials"

        fun getInstance(): OmniCodeCredentialStore =
            ApplicationManager.getApplication().getService(OmniCodeCredentialStore::class.java)
    }
}

internal class CredentialOriginMismatchException(
    val boundOrigin: String,
    val requestedOrigin: String,
) : IllegalStateException(
    "Base URL 的 Origin 已从 $boundOrigin 变为 $requestedOrigin。为防止 API Key 被发送到新地址，" +
        "已阻止复用旧凭据；请重新输入 Key，或点击“保存并加载模型”确认绑定新地址。",
)

internal fun requireMatchingCredentialOrigin(boundOrigin: String, requestedOrigin: String) {
    if (boundOrigin != requestedOrigin) {
        throw CredentialOriginMismatchException(boundOrigin, requestedOrigin)
    }
}

internal fun ProviderSecrets.isEmpty(): Boolean =
    apiKey.isBlank() && secondarySecret.isBlank() && sessionToken.isBlank()
