package dev.omnicode.service

import com.intellij.openapi.components.Service

/**
 * Renderer-reload safety net for prompts that have not been accepted by a run.
 * Deliberately project-memory only: no attachment bytes, paths, or credentials are persisted.
 */
data class PendingPromptRecord(
    val clientMessageId: String,
    val text: String,
    val mode: String,
    val strategy: String,
    val requiresReattach: Boolean,
)

internal class PendingPromptQueueStore {
    private val queues = linkedMapOf<String, MutableList<PendingPromptRecord>>()
    private val consumed = linkedSetOf<String>()

    @Synchronized fun upsert(sessionId: String, prompt: PendingPromptRecord): Boolean {
        if (prompt.clientMessageId in consumed) return false
        if (sessionId !in queues && queues.size >= 16) return false
        val queue = queues.getOrPut(sessionId) { mutableListOf() }
        val index = queue.indexOfFirst { it.clientMessageId == prompt.clientMessageId }
        if (index >= 0) queue[index] = prompt
        else {
            if (queue.size >= 20) return false
            queue.add(prompt)
        }
        return true
    }

    @Synchronized fun remove(sessionId: String, id: String) {
        val queue = queues[sessionId] ?: return
        queue.removeAll { it.clientMessageId == id }
        if (queue.isEmpty()) queues.remove(sessionId)
    }

    @Synchronized fun consume(sessionId: String, id: String) {
        remove(sessionId, id)
        consumed.add(id)
        if (consumed.size > 256) consumed.remove(consumed.first())
    }

    @Synchronized fun move(sessionId: String, id: String, direction: Int) {
        val queue = queues[sessionId] ?: return
        val index = queue.indexOfFirst { it.clientMessageId == id }
        val destination = index + direction
        if (index >= 0 && destination in queue.indices) {
            val item = queue.removeAt(index)
            queue.add(destination, item)
        }
    }

    @Synchronized fun snapshot(sessionId: String): List<PendingPromptRecord> = queues[sessionId]?.toList().orEmpty()

    @Synchronized fun clear(sessionId: String) { queues.remove(sessionId) }
}

@Service(Service.Level.PROJECT)
class PendingPromptQueueService {
    internal val store = PendingPromptQueueStore()
}
