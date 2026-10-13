package dev.omnicode.ui.web

import java.util.ArrayDeque

/**
 * Serializes every native -> JCEF message through one bounded consumer.
 *
 * JCEF's executeJavaScript call is asynchronous. Calling it from unrelated invokeLater blocks
 * allowed a late status/snapshot to overtake a terminal run event, leaving the React surface in
 * a permanent running state. This queue preserves enqueue order, batches renderer work and keeps
 * terminal/command acknowledgement messages when ordinary refresh traffic must be shed.
 */
internal class WebViewEventQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxBatchMessages: Int = DEFAULT_BATCH_MESSAGES,
    private val maxBatchChars: Int = DEFAULT_BATCH_CHARS,
    private val scheduleOnUiThread: ((() -> Unit) -> Unit),
    private val deliverBatch: (List<String>) -> Unit,
    private val onDeliveryFailure: (Throwable) -> Unit = {},
) {
    private data class Entry(
        val message: String,
        val coalesceKey: String?,
        val critical: Boolean,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Entry>()
    private var ready = false
    private var disposed = false
    private var drainScheduled = false

    /** Returns false only when a non-critical refresh was dropped under backpressure. */
    fun offer(message: String, coalesceKey: String? = null, critical: Boolean = false): Boolean {
        require(message.isNotBlank())
        var accepted = true
        synchronized(lock) {
            if (disposed) return false
            if (coalesceKey != null && !critical) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (!entry.critical && entry.coalesceKey == coalesceKey) iterator.remove()
                }
            }
            if (pending.size >= capacity) {
                val removable = pending.iterator()
                var removed = false
                // Ordinary live traffic never evicts another useful message merely because it
                // arrived later. Coalescible refreshes may replace another refresh; critical
                // lifecycle events may evict any non-critical item so terminal state survives.
                if (critical || coalesceKey != null) {
                    while (removable.hasNext()) {
                        if (!removable.next().critical) {
                            removable.remove()
                            removed = true
                            break
                        }
                    }
                }
                if (!removed && !critical) accepted = false
                // A terminal result or command acknowledgement is authoritative. In the very
                // unlikely all-critical case allow a small bounded reserve before evicting the
                // oldest acknowledgement; never grow without limit while the page is unready.
                if (!removed && critical && pending.size >= capacity + CRITICAL_RESERVE) {
                    pending.removeFirst()
                }
            }
            if (accepted) pending.addLast(Entry(message, coalesceKey, critical))
        }
        requestDrain()
        return accepted
    }

    fun markReady() {
        synchronized(lock) {
            if (disposed) return
            ready = true
        }
        requestDrain()
    }

    fun markNotReady() {
        synchronized(lock) { if (!disposed) ready = false }
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            ready = false
            pending.clear()
        }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun requestDrain() {
        val shouldSchedule = synchronized(lock) {
            if (disposed || !ready || drainScheduled || pending.isEmpty()) false
            else {
                drainScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            try {
                scheduleOnUiThread(::drainOnUiThread)
            } catch (error: Throwable) {
                synchronized(lock) { drainScheduled = false }
                onDeliveryFailure(error)
            }
        }
    }

    private fun drainOnUiThread() {
        val batch = synchronized(lock) {
            if (disposed || !ready) {
                drainScheduled = false
                return
            }
            val messages = ArrayList<String>(maxBatchMessages)
            var chars = 0
            while (pending.isNotEmpty() && messages.size < maxBatchMessages) {
                val next = pending.first()
                if (messages.isNotEmpty() && chars + next.message.length > maxBatchChars) break
                pending.removeFirst()
                messages += next.message
                chars += next.message.length
            }
            drainScheduled = false
            messages
        }
        if (batch.isNotEmpty()) {
            try {
                deliverBatch(batch)
            } catch (error: Throwable) {
                onDeliveryFailure(error)
            }
        }
        requestDrain()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 512
        const val CRITICAL_RESERVE = 32
        const val DEFAULT_BATCH_MESSAGES = 64
        const val DEFAULT_BATCH_CHARS = 512 * 1_024
    }
}
