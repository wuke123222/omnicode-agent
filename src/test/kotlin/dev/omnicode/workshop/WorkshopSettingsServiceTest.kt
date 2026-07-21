package dev.omnicode.workshop

import com.intellij.openapi.util.Disposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkshopSettingsServiceTest {
    @Test
    fun `selection survives persistent state round trip`() {
        val service = WorkshopSettingsService()
        service.selectTheme("paper-studio")
        service.selectPet("code-owl")
        service.setPetEnabled(true)

        val restored = WorkshopSettingsService()
        restored.loadState(service.state)

        assertEquals(
            WorkshopSelection(themeId = "paper-studio", petId = "code-owl", petEnabled = true),
            restored.snapshot(),
        )
        assertEquals("paper-studio", restored.resolvedSelection().theme.id)
        assertEquals("code-owl", restored.resolvedSelection().pet?.id)
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
