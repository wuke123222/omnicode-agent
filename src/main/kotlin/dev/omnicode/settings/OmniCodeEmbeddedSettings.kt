package dev.omnicode.settings

import com.intellij.openapi.Disposable
import javax.swing.JComponent

internal interface OmniCodeEmbeddedSettings : Disposable {
    val component: JComponent
    val isModified: Boolean

    @Throws(OmniCodeSettingsSaveException::class)
    fun save()

    fun reset()

    fun selectSection(index: Int) = Unit
}

internal class OmniCodeSettingsSaveException(
    message: String,
    cause: Throwable? = null,
    val sectionIndex: Int? = null,
) : Exception(message, cause)
