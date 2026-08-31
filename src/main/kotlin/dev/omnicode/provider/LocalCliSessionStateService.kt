package dev.omnicode.provider

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Persists only opaque native CLI session identities in the user's workspace file. Prompts,
 * output, credentials and process state never enter this component.
 */
@Service(Service.Level.PROJECT)
@State(name = "OmniCodeLocalCliSessions", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class LocalCliSessionStateService : PersistentStateComponent<LocalCliSessionStateService.StoredState> {
    data class StoredState(var sessions: MutableMap<String, String> = linkedMapOf())

    private var stored = StoredState()

    override fun getState(): StoredState = synchronized(this) {
        StoredState(LinkedHashMap(stored.sessions))
    }

    override fun loadState(state: StoredState) {
        synchronized(this) {
            stored = StoredState(
                state.sessions.entries.asSequence()
                    .filter { (key, value) -> SAFE_SESSION_KEY.matches(key) && SAFE_NATIVE_SESSION_ID.matches(value) }
                    .take(MAX_STORED_SESSIONS)
                    .associateTo(linkedMapOf()) { it.key to it.value },
            )
        }
    }

    fun context(conversationId: String, engineId: String): LocalCliSessionContext {
        require(SAFE_CONVERSATION_ID.matches(conversationId)) { "Invalid conversation id" }
        require(SAFE_ENGINE_ID.matches(engineId)) { "Invalid local engine id" }
        val key = "$conversationId:$engineId"
        return LocalCliSessionContext(
            resumeSessionId = synchronized(this) { stored.sessions[key] },
            onSessionStarted = { sessionId -> remember(key, sessionId) },
            onSessionInvalid = { synchronized(this) { stored.sessions.remove(key) } },
        )
    }

    fun clearConversation(conversationId: String) {
        if (!SAFE_CONVERSATION_ID.matches(conversationId)) return
        synchronized(this) { stored.sessions.keys.removeIf { it.startsWith("$conversationId:") } }
    }

    private fun remember(key: String, sessionId: String) {
        if (!SAFE_SESSION_KEY.matches(key) || !SAFE_NATIVE_SESSION_ID.matches(sessionId)) return
        synchronized(this) {
            if (key !in stored.sessions && stored.sessions.size >= MAX_STORED_SESSIONS) {
                stored.sessions.keys.firstOrNull()?.let(stored.sessions::remove)
            }
            stored.sessions[key] = sessionId
        }
    }

    companion object {
        fun getInstance(project: Project): LocalCliSessionStateService = project.service()
    }
}

data class LocalCliSessionContext(
    val resumeSessionId: String?,
    val onSessionStarted: (String) -> Unit,
    val onSessionInvalid: () -> Unit,
)

private const val MAX_STORED_SESSIONS = 100
private val SAFE_CONVERSATION_ID = Regex("[A-Za-z0-9._:-]{1,256}")
private val SAFE_ENGINE_ID = Regex("[a-z0-9_-]{1,64}")
private val SAFE_SESSION_KEY = Regex("[A-Za-z0-9._:-]{3,321}")
private val SAFE_NATIVE_SESSION_ID = Regex("[A-Za-z0-9._:-]{1,256}")
