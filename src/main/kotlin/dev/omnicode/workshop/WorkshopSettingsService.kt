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

private const val UNSET_PET_POSITION = Int.MIN_VALUE
private val SAFE_DESKTOP_POSITION_RANGE = -1_000_000..1_000_000

enum class PetDisplayMode {
    EMBEDDED,
    FLOATING,
    ;

    companion object {
        fun fromPersisted(value: String): PetDisplayMode = entries.firstOrNull { it.name == value }
            ?: EMBEDDED
    }
}

data class PetPlacementSettings(
    val displayMode: PetDisplayMode = PetDisplayMode.EMBEDDED,
    /** Normalized 0..10_000 coordinates inside the tool-window pet layer. */
    val embeddedX: Int? = null,
    val embeddedY: Int? = null,
    /** Absolute virtual-desktop coordinates. They are clamped to a usable screen before display. */
    val floatingX: Int? = null,
    val floatingY: Int? = null,
) {
    init {
        require(embeddedX == null || embeddedX in NORMALIZED_POSITION_RANGE)
        require(embeddedY == null || embeddedY in NORMALIZED_POSITION_RANGE)
        require((embeddedX == null) == (embeddedY == null)) {
            "Embedded pet coordinates must either both be set or both be absent"
        }
        require((floatingX == null) == (floatingY == null)) {
            "Floating pet coordinates must either both be set or both be absent"
        }
    }

    companion object {
        const val NORMALIZED_POSITION_MAX: Int = 10_000
        private val NORMALIZED_POSITION_RANGE = 0..NORMALIZED_POSITION_MAX
    }
}

/** XML-serializable bounded selection and position state; no catalog code or content is persisted. */
class WorkshopPersistentState {
    var selectedThemeId: String = WorkshopCatalog.DEFAULT_THEME_ID
    var selectedPetId: String = WorkshopCatalog.DEFAULT_PET_ID
    var petEnabled: Boolean = false
    var petDisplayMode: String = PetDisplayMode.EMBEDDED.name
    var embeddedPetX: Int = UNSET_PET_POSITION
    var embeddedPetY: Int = UNSET_PET_POSITION
    var floatingPetX: Int = UNSET_PET_POSITION
    var floatingPetY: Int = UNSET_PET_POSITION
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
    private val placementListeners = CopyOnWriteArrayList<(PetPlacementSettings) -> Unit>()

    /** Returns a defensive bean so callers cannot mutate the live selection around validation. */
    @Synchronized
    override fun getState(): WorkshopPersistentState = stateFrom(current.toSelection(), current.toPlacement())

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
            normalizePlacement(state),
        )
    }

    @Synchronized
    fun snapshot(): WorkshopSelection = current.toSelection()

    @Synchronized
    fun placementSnapshot(): PetPlacementSettings = current.toPlacement()

    @Synchronized
    fun resolvedSelection(): ResolvedWorkshopSelection = WorkshopCatalog.resolve(current.toSelection())

    fun update(selection: WorkshopSelection): WorkshopSelection {
        val checked = WorkshopCatalog.requireKnown(selection)
        val changed = synchronized(this) {
            if (current.toSelection() == checked) {
                false
            } else {
                current = stateFrom(checked, current.toPlacement())
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

    fun setPetDisplayMode(mode: PetDisplayMode): PetPlacementSettings = updatePlacement(
        placementSnapshot().copy(displayMode = mode),
    )

    fun saveEmbeddedPetPosition(x: Int, y: Int): PetPlacementSettings = updatePlacement(
        placementSnapshot().copy(
            embeddedX = x.coerceIn(0, PetPlacementSettings.NORMALIZED_POSITION_MAX),
            embeddedY = y.coerceIn(0, PetPlacementSettings.NORMALIZED_POSITION_MAX),
        ),
    )

    fun saveFloatingPetPosition(x: Int, y: Int): PetPlacementSettings = updatePlacement(
        placementSnapshot().copy(
            floatingX = x.coerceIn(SAFE_DESKTOP_POSITION_RANGE),
            floatingY = y.coerceIn(SAFE_DESKTOP_POSITION_RANGE),
        ),
    )

    fun resetPetPosition(): PetPlacementSettings = updatePlacement(
        placementSnapshot().copy(
            embeddedX = null,
            embeddedY = null,
            floatingX = null,
            floatingY = null,
        ),
    )

    fun addListener(parent: Disposable, listener: (WorkshopSelection) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun addPlacementListener(listener: (PetPlacementSettings) -> Unit) {
        placementListeners += listener
    }

    fun removePlacementListener(listener: (PetPlacementSettings) -> Unit) {
        placementListeners -= listener
    }

    private fun WorkshopPersistentState.toSelection(): WorkshopSelection = WorkshopSelection(
        themeId = selectedThemeId,
        petId = selectedPetId,
        petEnabled = petEnabled,
    )

    private fun WorkshopPersistentState.toPlacement(): PetPlacementSettings {
        val embeddedXValue = embeddedPetX.takeIf { it in 0..PetPlacementSettings.NORMALIZED_POSITION_MAX }
        val embeddedYValue = embeddedPetY.takeIf { it in 0..PetPlacementSettings.NORMALIZED_POSITION_MAX }
        val floatingXValue = floatingPetX.takeIf { it in SAFE_DESKTOP_POSITION_RANGE }
        val floatingYValue = floatingPetY.takeIf { it in SAFE_DESKTOP_POSITION_RANGE }
        return PetPlacementSettings(
            displayMode = PetDisplayMode.fromPersisted(petDisplayMode),
            embeddedX = embeddedXValue.takeIf { embeddedYValue != null },
            embeddedY = embeddedYValue.takeIf { embeddedXValue != null },
            floatingX = floatingXValue.takeIf { floatingYValue != null },
            floatingY = floatingYValue.takeIf { floatingXValue != null },
        )
    }

    private fun normalizePlacement(state: WorkshopPersistentState): PetPlacementSettings = state.toPlacement()

    private fun updatePlacement(candidate: PetPlacementSettings): PetPlacementSettings {
        val changed = synchronized(this) {
            if (current.toPlacement() == candidate) {
                false
            } else {
                current = stateFrom(current.toSelection(), candidate)
                true
            }
        }
        if (changed) placementListeners.forEach { listener ->
            runCatching { listener(candidate) }.onFailure { error -> LOG.warn("Pet placement listener failed", error) }
        }
        return candidate
    }

    private fun stateFrom(
        selection: WorkshopSelection,
        placement: PetPlacementSettings = PetPlacementSettings(),
    ): WorkshopPersistentState = WorkshopPersistentState().also {
        it.selectedThemeId = selection.themeId
        it.selectedPetId = selection.petId
        it.petEnabled = selection.petEnabled
        it.petDisplayMode = placement.displayMode.name
        it.embeddedPetX = placement.embeddedX ?: UNSET_PET_POSITION
        it.embeddedPetY = placement.embeddedY ?: UNSET_PET_POSITION
        it.floatingPetX = placement.floatingX ?: UNSET_PET_POSITION
        it.floatingPetY = placement.floatingY ?: UNSET_PET_POSITION
    }

    companion object {
        private val LOG = Logger.getInstance(WorkshopSettingsService::class.java)

        fun getInstance(): WorkshopSettingsService =
            ApplicationManager.getApplication().getService(WorkshopSettingsService::class.java)
    }
}
