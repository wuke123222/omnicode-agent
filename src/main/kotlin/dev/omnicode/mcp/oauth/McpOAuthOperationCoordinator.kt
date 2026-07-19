package dev.omnicode.mcp.oauth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM-wide coordination for one OAuth server configuration.
 *
 * Managers are intentionally cheap and are created by each connector, so coordination cannot
 * live on a manager instance. A ticket captures the server generation at operation submission.
 * Logout invalidates that generation without waiting for a browser flow or network request, and
 * commit uses the same short monitor as invalidation so a stale result can never win the race.
 */
internal object McpOAuthOperationCoordinator {
    private class Entry {
        val operation = Mutex()
        var generation: Long = 0L
        var sessionVersion: Long = 0L
    }

    internal data class Ticket(
        val serverId: String,
        val generation: Long,
        val sessionVersion: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun ticket(serverId: String): Ticket {
        require(serverId.isNotBlank()) { "MCP server id must not be blank" }
        val entry = entries.computeIfAbsent(serverId) { Entry() }
        return synchronized(entry) { Ticket(serverId, entry.generation, entry.sessionVersion) }
    }

    suspend fun <T> withOperation(ticket: Ticket, block: suspend () -> T): T {
        val entry = entries.getValue(ticket.serverId)
        return entry.operation.withLock {
            ensureCurrent(entry, ticket)
            block()
        }
    }

    fun <T> commitSessionIfCurrent(ticket: Ticket, block: () -> T): T {
        val entry = entries.getValue(ticket.serverId)
        return synchronized(entry) {
            ensureCurrent(entry, ticket)
            block().also { entry.sessionVersion++ }
        }
    }

    /** True when a concurrent operation submitted after [ticket] has installed a newer session. */
    fun sessionChangedSince(ticket: Ticket): Boolean {
        val entry = entries.getValue(ticket.serverId)
        return synchronized(entry) {
            ensureCurrent(entry, ticket)
            entry.sessionVersion != ticket.sessionVersion
        }
    }

    /** Invalidates all submitted operations only when [ticket] is still current. */
    fun invalidateIfCurrent(ticket: Ticket, block: () -> Unit): Boolean {
        val entry = entries.getValue(ticket.serverId)
        return synchronized(entry) {
            if (entry.generation != ticket.generation) return@synchronized false
            block()
            entry.generation++
            entry.sessionVersion++
            true
        }
    }

    /** Clears durable state and then invalidates every operation submitted before this call. */
    fun invalidate(serverId: String, block: () -> Unit) {
        val entry = entries.computeIfAbsent(serverId) { Entry() }
        synchronized(entry) {
            block()
            entry.generation++
            entry.sessionVersion++
        }
    }

    private fun ensureCurrent(entry: Entry, ticket: Ticket) {
        if (entry.generation != ticket.generation) throw McpOAuthOperationSupersededException()
    }
}

internal class McpOAuthOperationSupersededException : IllegalStateException(
    "MCP OAuth operation was cancelled because its credentials changed.",
)
