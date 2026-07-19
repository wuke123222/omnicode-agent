package dev.omnicode.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.omnicode.provider.ProviderConnection
import dev.omnicode.provider.ProviderException
import dev.omnicode.provider.ProviderPresets
import dev.omnicode.provider.ProviderProtocol

object OmniCodeSettingsDefaults {
    val providerId: String = ProviderPresets.all.first().id
    const val REGION: String = "us-east-1"
    const val API_VERSION: String = "2025-04-01-preview"
    const val MAX_OUTPUT_TOKENS: Int = 8_192
    const val MIN_OUTPUT_TOKENS: Int = 1
    const val MAX_ALLOWED_OUTPUT_TOKENS: Int = 1_048_576
}

/**
 * Non-sensitive application settings. Secrets deliberately do not belong to this state object;
 * they are stored by [OmniCodeCredentialStore] in the IDE password safe.
 */
class OmniCodeSettingsState {
    var providerId: String = OmniCodeSettingsDefaults.providerId
    var baseUrl: String = ProviderPresets.byId(providerId).defaultBaseUrl
    var model: String = ProviderPresets.byId(providerId).defaultModel
    var region: String = OmniCodeSettingsDefaults.REGION
    var apiVersion: String = OmniCodeSettingsDefaults.API_VERSION
    var maxOutputTokens: Int = OmniCodeSettingsDefaults.MAX_OUTPUT_TOKENS
    var providerProfiles: MutableList<ProviderProfileState> = mutableListOf()
}

/**
 * Non-secret settings are retained per provider so switching providers never destroys a custom
 * endpoint or the user's last selected model. Credentials remain in PasswordSafe.
 */
class ProviderProfileState {
    var providerId: String = ""
    var baseUrl: String = ""
    var model: String = ""
    var region: String = OmniCodeSettingsDefaults.REGION
    var apiVersion: String = OmniCodeSettingsDefaults.API_VERSION
    var maxOutputTokens: Int = OmniCodeSettingsDefaults.MAX_OUTPUT_TOKENS
    var visionModel: String = ""
}

data class OmniCodeSettingsSnapshot(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val region: String,
    val apiVersion: String,
    val maxOutputTokens: Int,
)

@Service(Service.Level.APP)
@State(
    name = "OmniCodeSettings",
    storages = [Storage("omnicode.xml")],
)
class OmniCodeSettingsService : PersistentStateComponent<OmniCodeSettingsState> {
    @Volatile
    private var currentState = defaultState()

    override fun getState(): OmniCodeSettingsState = currentState

    override fun loadState(state: OmniCodeSettingsState) {
        currentState = normalize(state)
    }

    @Synchronized
    fun snapshot(): OmniCodeSettingsSnapshot {
        val state = currentState
        return state.toSnapshot()
    }

    /** Returns the last saved settings for [providerId], or that provider's defaults. */
    @Synchronized
    fun snapshotFor(providerId: String): OmniCodeSettingsSnapshot {
        val preset = ProviderPresets.byId(providerId)
        return currentState.providerProfiles
            .firstOrNull { it.providerId == preset.id }
            ?.toSnapshot(preset)
            ?: defaultSnapshot(preset)
    }

    /** Returns every saved profile, always including the active provider. */
    @Synchronized
    fun profileSnapshots(): Map<String, OmniCodeSettingsSnapshot> = buildMap {
        currentState.providerProfiles.forEach { profile ->
            val preset = ProviderPresets.all.firstOrNull { it.id == profile.providerId } ?: return@forEach
            put(preset.id, profile.toSnapshot(preset))
        }
        put(currentState.providerId, currentState.toSnapshot())
    }

    @Synchronized
    fun visionModelFor(providerId: String): String = currentState.providerProfiles
        .firstOrNull { it.providerId == ProviderPresets.byId(providerId).id }
        ?.visionModel
        ?.trim()
        .orEmpty()

    @Synchronized
    fun visionModels(): Map<String, String> = currentState.providerProfiles
        .associate { it.providerId to it.visionModel.trim() }

    @Synchronized
    fun updateVisionModels(values: Map<String, String>) {
        val state = copyState(currentState)
        values.forEach { (providerId, model) ->
            val preset = ProviderPresets.all.firstOrNull { it.id == providerId } ?: return@forEach
            val existing = state.providerProfiles.firstOrNull { it.providerId == preset.id }
            val snapshot = existing?.toSnapshot(preset) ?: defaultSnapshot(preset)
            state.upsertProfile(snapshot, visionModel = model.trim())
        }
        currentState = normalize(state)
    }

    @Synchronized
    fun update(snapshot: OmniCodeSettingsSnapshot) {
        updateProfiles(snapshot.providerId, listOf(snapshot))
    }

    /** Atomically saves edited profiles and activates [activeProviderId]. */
    @Synchronized
    fun updateProfiles(activeProviderId: String, snapshots: Collection<OmniCodeSettingsSnapshot>) {
        val state = copyState(currentState)
        snapshots.forEach { snapshot ->
            if (ProviderPresets.all.any { it.id == snapshot.providerId }) {
                state.upsertProfile(normalizeSnapshot(snapshot))
            }
        }
        val activePreset = ProviderPresets.all.firstOrNull { it.id == activeProviderId }
            ?: ProviderPresets.byId(OmniCodeSettingsDefaults.providerId)
        val active = state.providerProfiles
            .firstOrNull { it.providerId == activePreset.id }
            ?.toSnapshot(activePreset)
            ?: defaultSnapshot(activePreset)
        state.applyActive(active)
        currentState = normalize(state)
    }

    /**
     * Fast provider switch used by the chat footer. The current profile is preserved and the
     * target provider resumes exactly where the user left it.
     */
    @Synchronized
    fun activateProvider(providerId: String): OmniCodeSettingsSnapshot {
        val state = copyState(currentState)
        state.upsertProfile(state.toSnapshot())
        val preset = ProviderPresets.byId(providerId)
        val target = state.providerProfiles.firstOrNull { it.providerId == preset.id }
            ?.toSnapshot(preset)
            ?: defaultSnapshot(preset)
        state.upsertProfile(target)
        state.applyActive(target)
        currentState = normalize(state)
        return currentState.toSnapshot()
    }

    /** Builds the active provider connection without ever copying credentials into persisted XML state. */
    fun providerConnection(): ProviderConnection {
        val settings = snapshot()
        val preset = ProviderPresets.byId(settings.providerId)
        val secrets = OmniCodeCredentialStore.getInstance().load(preset.id, settings.baseUrl)
        return connection(settings, preset, secrets)
    }

    suspend fun providerConnectionAsync(): ProviderConnection {
        return providerConnectionAsync(snapshot())
    }

    /** Uses the active provider profile and saved credential, but targets its configured vision model. */
    suspend fun visionProviderConnectionAsync(): ProviderConnection? {
        val settings = snapshot()
        val visionModel = visionModelFor(settings.providerId)
        return visionModel.takeIf(String::isNotBlank)?.let { providerConnectionAsync(settings).copy(model = it) }
    }

    internal suspend fun providerConnectionAsync(settings: OmniCodeSettingsSnapshot): ProviderConnection {
        val preset = ProviderPresets.byId(settings.providerId)
        val secrets = OmniCodeCredentialStore.getInstance().loadAsync(preset.id, settings.baseUrl)
        return connection(settings, preset, secrets)
    }

    private fun connection(
        settings: OmniCodeSettingsSnapshot,
        preset: dev.omnicode.provider.ProviderPreset,
        secrets: ProviderSecrets,
    ): ProviderConnection {
        val resolved = resolveProviderSecrets(preset.id, secrets, baseUrl = settings.baseUrl)
        resolved.blockedEnvironmentVariable?.let { variable ->
            throw ProviderException(blockedEnvironmentCredentialMessage(variable, settings.baseUrl))
        }
        val resolvedSecrets = resolved.secrets
        return ProviderConnection(
            preset = preset,
            baseUrl = settings.baseUrl,
            model = settings.model,
            apiKey = resolvedSecrets.apiKey,
            secondarySecret = resolvedSecrets.secondarySecret,
            sessionToken = resolvedSecrets.sessionToken,
            region = settings.region,
            apiVersion = if (
                preset.protocol == ProviderProtocol.ANTHROPIC_MESSAGES &&
                settings.apiVersion == OmniCodeSettingsDefaults.API_VERSION
            ) {
                "2023-06-01"
            } else {
                settings.apiVersion
            },
        )
    }

    private fun normalize(source: OmniCodeSettingsState): OmniCodeSettingsState {
        val requestedPreset = ProviderPresets.all.firstOrNull { it.id == source.providerId }
        val preset = requestedPreset ?: ProviderPresets.byId(OmniCodeSettingsDefaults.providerId)
        val knownProfiles = source.providerProfiles.mapNotNull { profile ->
            val profilePreset = ProviderPresets.all.firstOrNull { it.id == profile.providerId }
                ?: return@mapNotNull null
            profile.toSnapshot(profilePreset) to profile.visionModel
        }
        val active = if (requestedPreset != null) {
            normalizeSnapshot(
                OmniCodeSettingsSnapshot(
                    providerId = preset.id,
                    baseUrl = source.baseUrl,
                    model = source.model,
                    region = source.region,
                    apiVersion = source.apiVersion,
                    maxOutputTokens = source.maxOutputTokens,
                ),
            )
        } else {
            knownProfiles.lastOrNull { it.first.providerId == preset.id }?.first ?: defaultSnapshot(preset)
        }
        return OmniCodeSettingsState().also { normalized ->
            knownProfiles.forEach { (profile, visionModel) -> normalized.upsertProfile(profile, visionModel) }
            // Known legacy active fields remain authoritative; orphaned provider IDs never
            // repurpose their endpoint/model as another provider's profile.
            normalized.upsertProfile(active)
            normalized.applyActive(active)
        }
    }

    private fun normalizeSnapshot(source: OmniCodeSettingsSnapshot): OmniCodeSettingsSnapshot {
        val preset = ProviderPresets.byId(source.providerId)
        return source.copy(
            providerId = preset.id,
            baseUrl = source.baseUrl.trim().ifBlank { preset.defaultBaseUrl },
            model = source.model.trim().ifBlank { preset.defaultModel },
            region = source.region.trim().ifBlank { OmniCodeSettingsDefaults.REGION },
            apiVersion = source.apiVersion.trim().ifBlank { OmniCodeSettingsDefaults.API_VERSION },
            maxOutputTokens = source.maxOutputTokens.coerceIn(
                OmniCodeSettingsDefaults.MIN_OUTPUT_TOKENS,
                OmniCodeSettingsDefaults.MAX_ALLOWED_OUTPUT_TOKENS,
            ),
        )
    }

    private fun defaultSnapshot(preset: dev.omnicode.provider.ProviderPreset): OmniCodeSettingsSnapshot =
        OmniCodeSettingsSnapshot(
            providerId = preset.id,
            baseUrl = preset.defaultBaseUrl,
            model = preset.defaultModel,
            region = OmniCodeSettingsDefaults.REGION,
            apiVersion = if (preset.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
                "2023-06-01"
            } else {
                OmniCodeSettingsDefaults.API_VERSION
            },
            maxOutputTokens = OmniCodeSettingsDefaults.MAX_OUTPUT_TOKENS,
        )

    private fun copyState(source: OmniCodeSettingsState): OmniCodeSettingsState = OmniCodeSettingsState().apply {
        providerId = source.providerId
        baseUrl = source.baseUrl
        model = source.model
        region = source.region
        apiVersion = source.apiVersion
        maxOutputTokens = source.maxOutputTokens
        providerProfiles = source.providerProfiles.map { profile ->
            ProviderProfileState().also { copy ->
                copy.providerId = profile.providerId
                copy.baseUrl = profile.baseUrl
                copy.model = profile.model
                copy.region = profile.region
                copy.apiVersion = profile.apiVersion
                copy.maxOutputTokens = profile.maxOutputTokens
                copy.visionModel = profile.visionModel
            }
        }.toMutableList()
    }

    private fun OmniCodeSettingsState.toSnapshot(): OmniCodeSettingsSnapshot = OmniCodeSettingsSnapshot(
        providerId = providerId,
        baseUrl = baseUrl,
        model = model,
        region = region,
        apiVersion = apiVersion,
        maxOutputTokens = maxOutputTokens,
    )

    private fun ProviderProfileState.toSnapshot(
        preset: dev.omnicode.provider.ProviderPreset,
    ): OmniCodeSettingsSnapshot = normalizeSnapshot(
        OmniCodeSettingsSnapshot(
            providerId = preset.id,
            baseUrl = baseUrl,
            model = model,
            region = region,
            apiVersion = apiVersion,
            maxOutputTokens = maxOutputTokens,
        ),
    )

    private fun OmniCodeSettingsState.upsertProfile(
        snapshot: OmniCodeSettingsSnapshot,
        visionModel: String? = null,
    ) {
        val existingVisionModel = providerProfiles.firstOrNull { it.providerId == snapshot.providerId }?.visionModel.orEmpty()
        providerProfiles.removeAll { it.providerId == snapshot.providerId }
        providerProfiles += ProviderProfileState().also { profile ->
            profile.providerId = snapshot.providerId
            profile.baseUrl = snapshot.baseUrl
            profile.model = snapshot.model
            profile.region = snapshot.region
            profile.apiVersion = snapshot.apiVersion
            profile.maxOutputTokens = snapshot.maxOutputTokens
            profile.visionModel = visionModel ?: existingVisionModel
        }
    }

    private fun OmniCodeSettingsState.applyActive(snapshot: OmniCodeSettingsSnapshot) {
        providerId = snapshot.providerId
        baseUrl = snapshot.baseUrl
        model = snapshot.model
        region = snapshot.region
        apiVersion = snapshot.apiVersion
        maxOutputTokens = snapshot.maxOutputTokens
    }

    companion object {
        fun getInstance(): OmniCodeSettingsService =
            ApplicationManager.getApplication().getService(OmniCodeSettingsService::class.java)

        private fun defaultState(): OmniCodeSettingsState = OmniCodeSettingsState()
    }
}
