package dev.omnicode.ui

import dev.omnicode.provider.ProviderConnection
import dev.omnicode.settings.McpEnvironmentCredentialStore
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpHttpCredentialStore
import dev.omnicode.settings.McpOAuthCredentialStore
import dev.omnicode.settings.McpTransport
import dev.omnicode.settings.OmniCodePlatformSettingsService
import dev.omnicode.settings.OmniCodeSettingsService

/**
 * Collects only credential values already configured by this plugin so an explicit research export
 * can redact secrets from earlier provider/MCP turns as well as the currently active provider.
 */
internal suspend fun collectResearchExportSecrets(
    settings: OmniCodeSettingsService,
    platform: OmniCodePlatformSettingsService,
    activeConnection: ProviderConnection,
): List<String> {
    val secrets = LinkedHashSet<String>()
    fun remember(value: String) {
        if (secrets.size < MAX_EXPORT_SECRETS && value.length in MIN_EXPORT_SECRET_CHARS..MAX_EXPORT_SECRET_CHARS) {
            secrets += value
        }
    }
    fun rememberConnection(connection: ProviderConnection) {
        remember(connection.apiKey)
        remember(connection.secondarySecret)
        remember(connection.sessionToken)
    }

    rememberConnection(activeConnection)
    settings.profileSnapshots().values.forEach { profile ->
        runCatching { settings.providerConnectionAsync(profile) }
            .getOrNull()
            ?.let(::rememberConnection)
    }

    val environmentStore = McpEnvironmentCredentialStore.getInstance()
    val bearerStore = McpHttpCredentialStore.getInstance()
    val oauthStore = McpOAuthCredentialStore.getInstance()
    platform.snapshot().mcpServers.take(MAX_EXPORT_MCP_SERVERS).forEach { server ->
        server.environmentKeys.take(MAX_EXPORT_ENV_KEYS_PER_SERVER).forEach { key ->
            runCatching { environmentStore.load(server.id, key) }.getOrNull()?.let(::remember)
        }
        if (server.transport == McpTransport.HTTP) {
            when (server.httpAuthMode) {
                McpHttpAuthMode.NONE -> Unit
                McpHttpAuthMode.BEARER -> runCatching { bearerStore.load(server.id) }.getOrNull()?.let(::remember)
                McpHttpAuthMode.OAUTH -> runCatching { oauthStore.load(server.id) }.getOrNull()?.let { session ->
                    remember(session.clientSecret)
                    remember(session.accessToken)
                    remember(session.refreshToken)
                }
            }
        }
    }
    return secrets.toList()
}

private const val MIN_EXPORT_SECRET_CHARS = 4
private const val MAX_EXPORT_SECRET_CHARS = 128 * 1024
private const val MAX_EXPORT_SECRETS = 512
private const val MAX_EXPORT_MCP_SERVERS = 256
private const val MAX_EXPORT_ENV_KEYS_PER_SERVER = 64
