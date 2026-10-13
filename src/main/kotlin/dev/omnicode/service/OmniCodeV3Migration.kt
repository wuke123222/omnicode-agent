package dev.omnicode.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
            // provider and MCP credentials use separate services and remain untouched. Password
            // Safe may initialize an OS-native store (for example libsecret on Linux), so never
            // touch it synchronously while constructing the Tool Window on the EDT. Cleanup is
            // deliberately best-effort: an unavailable legacy store must not block the product.
            deferLegacyCredentialCleanup(
                enqueue = { cleanup ->
                    ApplicationManager.getApplication().executeOnPooledThread { cleanup() }
                },
                cleanup = {
                    PasswordSafe.instance.setPassword(
                        CredentialAttributes(generateServiceName(LEGACY_LICENSE_SERVICE, "signed-license")),
                        null,
                    )
                },
                onFailure = { error -> LOG.info("Skipping unavailable legacy license credential cleanup", error) },
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

    internal fun deferLegacyCredentialCleanup(
        enqueue: (() -> Unit) -> Unit,
        cleanup: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        runCatching {
            enqueue {
                runCatching(cleanup).onFailure(onFailure)
            }
        }.onFailure(onFailure)
    }

    private const val LEGACY_LICENSE_SERVICE = "dev.omnicode.agent.commercial"
    private val LOG = Logger.getInstance(OmniCodeV3Migration::class.java)
}
