package dev.omnicode.workshop

import java.util.Collections

/** Whether a theme is intended for a light or dark surface. */
enum class WorkshopThemeAppearance {
    DARK,
    LIGHT,
}

/**
 * A closed set of presentation hints understood by the host UI.
 *
 * Pets cannot provide callbacks, scripts, commands, class names, URLs, or file-system paths. New
 * behaviour must be implemented and reviewed in the plugin before it can become an enum value.
 */
enum class WorkshopPetMotion {
    BOB,
    HOP,
    GLIDE,
    DOZE,
}

/** A strictly declarative set of theme colours. */
data class WorkshopThemePalette(
    val background: String,
    val surface: String,
    val elevatedSurface: String,
    val primaryText: String,
    val secondaryText: String,
    val accent: String,
    val accentText: String,
    val border: String,
    val success: String,
    val warning: String,
    val error: String,
) {
    init {
        allColors().forEach(WorkshopCatalogPolicy::requireColor)
    }

    fun allColors(): List<String> = listOf(
        background,
        surface,
        elevatedSurface,
        primaryText,
        secondaryText,
        accent,
        accentText,
        border,
        success,
        warning,
        error,
    )
}

/** A built-in theme descriptor. It contains values only and has no executable extension point. */
data class WorkshopTheme(
    val id: String,
    val displayName: String,
    val description: String,
    val appearance: WorkshopThemeAppearance,
    val palette: WorkshopThemePalette,
) {
    init {
        WorkshopCatalogPolicy.requireId(id)
        WorkshopCatalogPolicy.requireDisplayText(displayName, "Theme name", MAX_NAME_CHARS)
        WorkshopCatalogPolicy.requireDisplayText(description, "Theme description", MAX_DESCRIPTION_CHARS)
    }

    private companion object {
        const val MAX_NAME_CHARS = 48
        const val MAX_DESCRIPTION_CHARS = 180
    }
}

/**
 * Declarative pet timing and copy. Motion is selected from [WorkshopPetMotion]; arbitrary behaviour
 * supplied by a catalog item is intentionally impossible.
 */
class WorkshopPetBehavior(
    val motion: WorkshopPetMotion,
    val idleIntervalSeconds: Int,
    idleMessages: List<String>,
) {
    val idleMessages: List<String> = Collections.unmodifiableList(ArrayList(idleMessages))

    init {
        require(idleIntervalSeconds in MIN_IDLE_SECONDS..MAX_IDLE_SECONDS) {
            "Pet idle interval must be between $MIN_IDLE_SECONDS and $MAX_IDLE_SECONDS seconds"
        }
        require(this.idleMessages.size in 1..MAX_IDLE_MESSAGES) {
            "A pet must have between 1 and $MAX_IDLE_MESSAGES idle messages"
        }
        this.idleMessages.forEach { message ->
            WorkshopCatalogPolicy.requireDisplayText(message, "Pet message", MAX_MESSAGE_CHARS)
        }
    }

    override fun equals(other: Any?): Boolean = other is WorkshopPetBehavior &&
        motion == other.motion &&
        idleIntervalSeconds == other.idleIntervalSeconds &&
        idleMessages == other.idleMessages

    override fun hashCode(): Int {
        var result = motion.hashCode()
        result = 31 * result + idleIntervalSeconds
        result = 31 * result + idleMessages.hashCode()
        return result
    }

    override fun toString(): String =
        "WorkshopPetBehavior(motion=$motion, idleIntervalSeconds=$idleIntervalSeconds, idleMessages=$idleMessages)"

    private companion object {
        const val MIN_IDLE_SECONDS = 5
        const val MAX_IDLE_SECONDS = 3_600
        const val MAX_IDLE_MESSAGES = 12
        const val MAX_MESSAGE_CHARS = 120
    }
}

/** A built-in desktop-pet descriptor containing presentation data only. */
data class WorkshopPet(
    val id: String,
    val displayName: String,
    val description: String,
    val glyph: String,
    val accentColor: String,
    val behavior: WorkshopPetBehavior,
) {
    init {
        WorkshopCatalogPolicy.requireId(id)
        WorkshopCatalogPolicy.requireDisplayText(displayName, "Pet name", MAX_NAME_CHARS)
        WorkshopCatalogPolicy.requireDisplayText(description, "Pet description", MAX_DESCRIPTION_CHARS)
        WorkshopCatalogPolicy.requireGlyph(glyph)
        WorkshopCatalogPolicy.requireColor(accentColor)
    }

    private companion object {
        const val MAX_NAME_CHARS = 48
        const val MAX_DESCRIPTION_CHARS = 180
    }
}

/** The persisted/user-visible selection. Catalog definitions themselves are never persisted. */
data class WorkshopSelection(
    val themeId: String,
    val petId: String,
    val petEnabled: Boolean,
)

/** A selection resolved exclusively against the trusted built-in catalog. */
data class ResolvedWorkshopSelection(
    val selection: WorkshopSelection,
    val theme: WorkshopTheme,
    val pet: WorkshopPet?,
)

/**
 * Trusted local catalog for the creative workshop.
 *
 * This object deliberately has no registration, discovery, deserialization, reflection, process,
 * or class-loading API. Catalog entries are compiled data and consumers can only resolve their IDs.
 */
object WorkshopCatalog {
    const val DEFAULT_THEME_ID: String = "jetbrains-native"
    const val DEFAULT_PET_ID: String = "pixel-cat"

    val themes: List<WorkshopTheme> = Collections.unmodifiableList(arrayListOf(
        WorkshopTheme(
            id = DEFAULT_THEME_ID,
            displayName = "JetBrains Native",
            description = "Follows the active JetBrains look and feel for maximum compatibility.",
            appearance = WorkshopThemeAppearance.DARK,
            palette = WorkshopThemePalette(
                background = "#1E1F22",
                surface = "#2B2D30",
                elevatedSurface = "#393B40",
                primaryText = "#DFE1E5",
                secondaryText = "#A8ADBD",
                accent = "#3574F0",
                accentText = "#FFFFFF",
                border = "#43454A",
                success = "#59A869",
                warning = "#E2B86B",
                error = "#DB5C5C",
            ),
        ),
        WorkshopTheme(
            id = "graphite-night",
            displayName = "Graphite Night",
            description = "A restrained charcoal workspace with a calm blue focus colour.",
            appearance = WorkshopThemeAppearance.DARK,
            palette = WorkshopThemePalette(
                background = "#17181A",
                surface = "#202226",
                elevatedSurface = "#292C31",
                primaryText = "#F2F3F5",
                secondaryText = "#A9ADB5",
                accent = "#5A8EEA",
                accentText = "#FFFFFF",
                border = "#3A3E45",
                success = "#5AC878",
                warning = "#E7B75C",
                error = "#F06060",
            ),
        ),
        WorkshopTheme(
            id = "aurora-night",
            displayName = "Aurora Night",
            description = "Deep indigo surfaces with teal and violet accents.",
            appearance = WorkshopThemeAppearance.DARK,
            palette = WorkshopThemePalette(
                background = "#10131D",
                surface = "#181D2A",
                elevatedSurface = "#22293A",
                primaryText = "#EEF2FF",
                secondaryText = "#A8B1C7",
                accent = "#7384F2",
                accentText = "#FFFFFF",
                border = "#343D54",
                success = "#54D6A1",
                warning = "#F2C66D",
                error = "#FF727D",
            ),
        ),
        WorkshopTheme(
            id = "forest-terminal",
            displayName = "Forest Terminal",
            description = "Evergreen surfaces and soft mint highlights for long coding sessions.",
            appearance = WorkshopThemeAppearance.DARK,
            palette = WorkshopThemePalette(
                background = "#111916",
                surface = "#19231F",
                elevatedSurface = "#23302A",
                primaryText = "#EBF5EF",
                secondaryText = "#A6B8AE",
                accent = "#63C793",
                accentText = "#0C1711",
                border = "#34483E",
                success = "#70D69E",
                warning = "#D9B96A",
                error = "#EE7373",
            ),
        ),
        WorkshopTheme(
            id = "paper-studio",
            displayName = "Paper Studio",
            description = "A warm light canvas with ink-like text and a cobalt accent.",
            appearance = WorkshopThemeAppearance.LIGHT,
            palette = WorkshopThemePalette(
                background = "#F5F2EB",
                surface = "#FCFAF5",
                elevatedSurface = "#FFFFFF",
                primaryText = "#24272C",
                secondaryText = "#666B73",
                accent = "#3267D6",
                accentText = "#FFFFFF",
                border = "#D8D3C8",
                success = "#32885A",
                warning = "#A76E16",
                error = "#C64747",
            ),
        ),
    ))

    val pets: List<WorkshopPet> = Collections.unmodifiableList(arrayListOf(
        WorkshopPet(
            id = DEFAULT_PET_ID,
            displayName = "Pixel Cat",
            description = "A quiet pair-programming cat that celebrates completed tasks.",
            glyph = "🐈",
            accentColor = "#8FAEFF",
            behavior = WorkshopPetBehavior(
                motion = WorkshopPetMotion.BOB,
                idleIntervalSeconds = 45,
                idleMessages = listOf("Ready when you are.", "Small steps still ship software."),
            ),
        ),
        WorkshopPet(
            id = "code-owl",
            displayName = "Code Owl",
            description = "A thoughtful night owl for reviews and research sessions.",
            glyph = "🦉",
            accentColor = "#C3A6FF",
            behavior = WorkshopPetBehavior(
                motion = WorkshopPetMotion.GLIDE,
                idleIntervalSeconds = 60,
                idleMessages = listOf("Check the edge cases.", "A clear invariant is a useful compass."),
            ),
        ),
        WorkshopPet(
            id = "rubber-duck",
            displayName = "Rubber Duck",
            description = "A classic debugging companion that encourages concise explanations.",
            glyph = "🦆",
            accentColor = "#F2C94C",
            behavior = WorkshopPetBehavior(
                motion = WorkshopPetMotion.HOP,
                idleIntervalSeconds = 50,
                idleMessages = listOf("Tell me what the code should do.", "What changed just before the failure?"),
            ),
        ),
        WorkshopPet(
            id = "tiny-robot",
            displayName = "Tiny Robot",
            description = "A compact build companion with a fondness for passing checks.",
            glyph = "🤖",
            accentColor = "#64D2C8",
            behavior = WorkshopPetBehavior(
                motion = WorkshopPetMotion.DOZE,
                idleIntervalSeconds = 40,
                idleMessages = listOf("Systems nominal.", "One more check before launch."),
            ),
        ),
    ))

    private val themesById = index(themes, WorkshopTheme::id, "theme")
    private val petsById = index(pets, WorkshopPet::id, "pet")

    init {
        check(DEFAULT_THEME_ID in themesById) { "Default workshop theme is missing" }
        check(DEFAULT_PET_ID in petsById) { "Default workshop pet is missing" }
    }

    fun theme(id: String): WorkshopTheme? = themesById[id]

    fun pet(id: String): WorkshopPet? = petsById[id]

    fun defaultSelection(): WorkshopSelection = WorkshopSelection(
        themeId = DEFAULT_THEME_ID,
        petId = DEFAULT_PET_ID,
        petEnabled = false,
    )

    /** Normalizes untrusted persisted IDs to known built-in entries. */
    fun normalize(selection: WorkshopSelection): WorkshopSelection {
        val knownPetId = selection.petId.trim().takeIf(petsById::containsKey)
        return WorkshopSelection(
            themeId = selection.themeId.trim().takeIf(themesById::containsKey) ?: DEFAULT_THEME_ID,
            petId = knownPetId ?: DEFAULT_PET_ID,
            // Fail closed when a saved pet disappears after an upgrade or its state is damaged.
            petEnabled = selection.petEnabled && knownPetId != null,
        )
    }

    /** Rejects unknown IDs supplied by live callers instead of retaining arbitrary strings. */
    fun requireKnown(selection: WorkshopSelection): WorkshopSelection {
        require(selection.themeId in themesById) { "Unknown workshop theme: ${selection.themeId}" }
        require(selection.petId in petsById) { "Unknown workshop pet: ${selection.petId}" }
        return selection
    }

    fun resolve(selection: WorkshopSelection): ResolvedWorkshopSelection {
        val normalized = normalize(selection)
        return ResolvedWorkshopSelection(
            selection = normalized,
            theme = themesById.getValue(normalized.themeId),
            pet = normalized.petId.takeIf { normalized.petEnabled }?.let(petsById::getValue),
        )
    }

    private fun <T> index(values: List<T>, id: (T) -> String, kind: String): Map<String, T> {
        val indexed = values.associateBy(id)
        check(indexed.size == values.size) { "Duplicate workshop $kind ID" }
        return indexed
    }
}

/** Central validation for every string that can reach a future workshop renderer. */
internal object WorkshopCatalogPolicy {
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")

    fun requireId(value: String) {
        require(value.length <= 64 && idPattern.matches(value)) {
            "Workshop IDs may contain lowercase letters, numbers, and single hyphens only"
        }
    }

    fun requireColor(value: String) {
        require(colorPattern.matches(value)) { "Workshop colours must use #RRGGBB or #RRGGBBAA" }
    }

    fun requireDisplayText(value: String, label: String, maxChars: Int) {
        require(value.isNotBlank() && value.length <= maxChars) { "$label must contain 1 to $maxChars characters" }
        require(value.none(Char::isISOControl)) { "$label cannot contain control characters" }
        // Swing labels interpret an <html> prefix; rejecting markup keeps catalog copy plain data.
        require('<' !in value && '>' !in value) { "$label cannot contain markup" }
    }

    fun requireGlyph(value: String) {
        requireDisplayText(value, "Pet glyph", 16)
        require(value.codePointCount(0, value.length) in 1..4) { "Pet glyph must contain at most four code points" }
    }
}
