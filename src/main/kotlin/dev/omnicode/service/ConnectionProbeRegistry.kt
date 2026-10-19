package dev.omnicode.service

import com.intellij.openapi.components.Service
import dev.omnicode.settings.DatabaseConnectionConfig
import dev.omnicode.settings.McpServerConfig
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Ephemeral, secret-free evidence from user-requested connection tests; never a live-tool claim. */
@Service(Service.Level.PROJECT)
class ConnectionProbeRegistry {
    private data class Probe(val fingerprint: String, val succeeded: Boolean, val atMillis: Long)

    private val databases = ConcurrentHashMap<String, Probe>()
    private val mcpServers = ConcurrentHashMap<String, Probe>()

    fun recordDatabase(config: DatabaseConnectionConfig, succeeded: Boolean) {
        databases[config.id] = Probe(fingerprint(config.toString()), succeeded, System.currentTimeMillis())
    }

    fun recordMcp(config: McpServerConfig, succeeded: Boolean) {
        mcpServers[config.id] = Probe(mcpFingerprint(config), succeeded, System.currentTimeMillis())
    }

    fun clearDatabase(id: String) { databases.remove(id) }
    fun clearMcp(id: String) { mcpServers.remove(id) }

    fun databasePassedRecently(config: DatabaseConnectionConfig): Boolean =
        databases[config.id]?.isRecentMatch(fingerprint(config.toString())) == true

    fun mcpPassedRecently(config: McpServerConfig): Boolean =
        mcpServers[config.id]?.isRecentMatch(mcpFingerprint(config)) == true

    private fun mcpFingerprint(config: McpServerConfig): String =
        fingerprint(config.copy(environmentKeys = config.environmentKeys.toSortedSet()).toString())

    private fun Probe.isRecentMatch(current: String): Boolean = succeeded && fingerprint == current &&
        System.currentTimeMillis() - atMillis in 0..RECENT_MILLIS

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object { const val RECENT_MILLIS = 10 * 60 * 1000L }
}
