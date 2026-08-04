package dev.omnicode.workshop

import com.intellij.openapi.util.Disposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WorkshopSettingsServiceTest {
    @Test
    fun `selection survives persistent state round trip`() {
        val service = WorkshopSettingsService()
        service.selectTheme("paper-studio")
        service.selectPet("code-owl")
        service.setPetEnabled(true)
        service.setPetDisplayMode(PetDisplayMode.FLOATING)
        service.saveEmbeddedPetPosition(2_500, 7_500)
        service.saveFloatingPetPosition(-420, 180)

        val restored = WorkshopSettingsService()
        restored.loadState(service.state)

        assertEquals(
            WorkshopSelection(themeId = "paper-studio", petId = "code-owl", petEnabled = true),
            restored.snapshot(),
        )
        assertEquals("paper-studio", restored.resolvedSelection().theme.id)
        assertEquals("code-owl", restored.resolvedSelection().pet?.id)
        assertEquals(
            PetPlacementSettings(
                displayMode = PetDisplayMode.FLOATING,
                embeddedX = 2_500,
                embeddedY = 7_500,
                floatingX = -420,
                floatingY = 180,
            ),
            restored.placementSnapshot(),
        )
    }

    @Test
    fun `untrusted persisted IDs are replaced by built-in defaults`() {
        val state = WorkshopPersistentState().also {
            it.selectedThemeId = "javascript-alert"
            it.selectedPetId = "../../bin/sh"
            it.petEnabled = true
        }
        val service = WorkshopSettingsService()

        service.loadState(state)

        assertEquals(WorkshopCatalog.DEFAULT_THEME_ID, service.state.selectedThemeId)
        assertEquals(WorkshopCatalog.DEFAULT_PET_ID, service.state.selectedPetId)
        assertFalse(service.state.petEnabled)
        assertEquals(null, service.resolvedSelection().pet)
    }

    @Test
    fun `live updates reject unknown catalog IDs`() {
        val service = WorkshopSettingsService()

        assertFailsWith<IllegalArgumentException> { service.selectTheme("file-path") }
        assertFailsWith<IllegalArgumentException> { service.selectPet("shell-command") }
        assertEquals(WorkshopCatalog.defaultSelection(), service.snapshot())
    }

    @Test
    fun `loading state copies values and does not retain caller state`() {
        val source = WorkshopPersistentState().also {
            it.selectedThemeId = "aurora-night"
            it.selectedPetId = "tiny-robot"
            it.petEnabled = false
        }
        val service = WorkshopSettingsService()
        service.loadState(source)

        source.selectedThemeId = "paper-studio"
        source.petEnabled = true

        assertEquals("aurora-night", service.snapshot().themeId)
        assertFalse(service.snapshot().petEnabled)
    }

    @Test
    fun `damaged placement data fails closed to embedded unset position`() {
        val state = WorkshopPersistentState().also {
            it.petDisplayMode = "REMOTE_SCRIPT"
            it.embeddedPetX = 4_000
            it.embeddedPetY = -50
            it.floatingPetX = 2_000_000
            it.floatingPetY = 200
        }
        val service = WorkshopSettingsService()

        service.loadState(state)

        val placement = service.placementSnapshot()
        assertEquals(PetDisplayMode.EMBEDDED, placement.displayMode)
        assertNull(placement.embeddedX)
        assertNull(placement.embeddedY)
        assertNull(placement.floatingX)
        assertNull(placement.floatingY)
    }

    @Test
    fun `placement updates are bounded resettable and observable`() {
        val service = WorkshopSettingsService()
        var observed: PetPlacementSettings? = null
        val listener: (PetPlacementSettings) -> Unit = { observed = it }
        service.addPlacementListener(listener)

        service.saveEmbeddedPetPosition(-50, 50_000)
        assertEquals(0, observed?.embeddedX)
        assertEquals(PetPlacementSettings.NORMALIZED_POSITION_MAX, observed?.embeddedY)
        service.setPetDisplayMode(PetDisplayMode.FLOATING)
        assertEquals(PetDisplayMode.FLOATING, observed?.displayMode)
        service.resetPetPosition()
        assertNull(observed?.embeddedX)

        service.removePlacementListener(listener)
        observed = null
        service.setPetDisplayMode(PetDisplayMode.EMBEDDED)
        assertNull(observed)
    }

    @Test
    fun `persistent state getter cannot mutate the live selection`() {
        val service = WorkshopSettingsService()
        val exported = service.state

        exported.selectedThemeId = "paper-studio"
        exported.petEnabled = true

        assertEquals(WorkshopCatalog.defaultSelection(), service.snapshot())
    }

    @Test
    fun `listeners receive validated changes and are removed with their parent`() {
        val service = WorkshopSettingsService()
        val parent = Disposer.newDisposable()
        var observed: WorkshopSelection? = null
        service.addListener(parent) { observed = it }

        service.selectTheme("paper-studio")
        assertEquals("paper-studio", observed?.themeId)

        Disposer.dispose(parent)
        service.selectTheme("aurora-night")
        assertEquals("paper-studio", observed?.themeId)
    }
}
