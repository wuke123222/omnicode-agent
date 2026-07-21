package dev.omnicode.workshop

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/** XML-serializable state containing selection IDs only; no catalog code or content is persisted. */
class WorkshopPersistentState {
    var selectedThemeId: String = WorkshopCatalog.DEFAULT_THEME_ID
    var selectedPetId: String = WorkshopCatalog.DEFAULT_PET_ID
    var petEnabled: Boolean = false
}

@Service(Service.Level.APP)
@State(
    name = "OmniCodeCreativeWorkshop",
    storages = [Storage("omnicode-workshop.xml")],
)
class WorkshopSettingsService : PersistentStateComponent<WorkshopPersistentState> {
    @Volatile
    private var current = WorkshopPersistentState()
    private val listeners = CopyOnWriteArrayList<(WorkshopSelection) -> Unit>()

    /** Returns a defensive bean so callers cannot mutate the live selection around validation. */
    @Synchronized
    override fun getState(): WorkshopPersistentState = stateFrom(current.toSelection())

    @Synchronized
    override fun loadState(state: WorkshopPersistentState) {
        current = stateFrom(
            WorkshopCatalog.normalize(
                WorkshopSelection(
                    themeId = state.selectedThemeId,
                    petId = state.selectedPetId,
                    petEnabled = state.petEnabled,
                ),
            ),
        )
    }

    @Synchronized
    fun snapshot(): WorkshopSelection = current.toSelection()

    @Synchronized
    fun resolvedSelection(): ResolvedWorkshopSelection = WorkshopCatalog.resolve(current.toSelection())

    fun update(selection: WorkshopSelection): WorkshopSelection {
        val checked = WorkshopCatalog.requireKnown(selection)
        val changed = synchronized(this) {
            if (current.toSelection() == checked) {
                false
            } else {
                current = stateFrom(checked)
                true
            }
        }
        if (changed) listeners.forEach { listener ->
            runCatching { listener(checked) }.onFailure { error -> LOG.warn("Workshop listener failed", error) }
        }
        return checked
    }

    fun selectTheme(themeId: String): WorkshopSelection = update(snapshot().copy(themeId = themeId))

    fun selectPet(petId: String): WorkshopSelection = update(snapshot().copy(petId = petId))

    fun setPetEnabled(enabled: Boolean): WorkshopSelection = update(snapshot().copy(petEnabled = enabled))

    fun addListener(parent: Disposable, listener: (WorkshopSelection) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun WorkshopPersistentState.toSelection(): WorkshopSelection = WorkshopSelection(
        themeId = selectedThemeId,
        petId = selectedPetId,
        petEnabled = petEnabled,
    )

    private fun stateFrom(selection: WorkshopSelection): WorkshopPersistentState = WorkshopPersistentState().also {
        it.selectedThemeId = selection.themeId
        it.selectedPetId = selection.petId
        it.petEnabled = selection.petEnabled
    }

    companion object {
        private val LOG = Logger.getInstance(WorkshopSettingsService::class.java)

        fun getInstance(): WorkshopSettingsService =
            ApplicationManager.getApplication().getService(WorkshopSettingsService::class.java)
    }
}
