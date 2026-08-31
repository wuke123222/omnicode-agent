package dev.omnicode.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import org.jdom.Element

/**
 * Migration-only owner of the removed experiment-lab component name.
 *
 * Returning no state tells the IntelliJ persistence layer to discard the old
 * plugin-owned workspace payload on the next save. The legacy payload is never
 * interpreted and no project source or exported experiment file is touched.
 */
@Service(Service.Level.PROJECT)
@State(name = "OmniCodeExperimentLab", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LegacyExperimentStateCleaner : PersistentStateComponent<Element> {
    override fun getState(): Element? = null

    override fun loadState(state: Element) = Unit

    fun clear() = Unit
}
