package dev.omnicode.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.omnicode.settings.OmniCodePlatformSettingsService

/** Idempotent deletion/migration boundary for UI schema 3. */
object OmniCodeV3Migration {
    private const val APP_KEY = "dev.omnicode.ui.schema"
    private const val PROJECT_KEY = "dev.omnicode.project.ui.schema"
    private const val VERSION = 3

    fun migrate(project: Project) {
        val appProperties = PropertiesComponent.getInstance()
        if (appProperties.getInt(APP_KEY, 0) < VERSION) {
            // Marketplace/offline license gates were removed. Delete the old PasswordSafe value;
            // provider and MCP credentials use separate services and remain untouched.
            PasswordSafe.instance.setPassword(
                CredentialAttributes(generateServiceName(LEGACY_LICENSE_SERVICE, "signed-license")),
                null,
            )
            OmniCodePlatformSettingsService.getInstance().update { state ->
                state.uiSchemaVersion = VERSION
                state.promptTemplates.removeIf { prompt ->
                    prompt.shortcut.trim().removePrefix("!").equals("semi-design", ignoreCase = true)
                }
            }
            appProperties.setValue(APP_KEY, VERSION, 0)
        }

        val projectProperties = PropertiesComponent.getInstance(project)
        if (projectProperties.getInt(PROJECT_KEY, 0) < VERSION) {
            project.service<LegacyExperimentStateCleaner>().clear()
            projectProperties.setValue(PROJECT_KEY, VERSION, 0)
        }
    }

    private const val LEGACY_LICENSE_SERVICE = "dev.omnicode.agent.commercial"
}
