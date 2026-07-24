package dev.omnicode.mcp

import java.time.Instant
import java.util.Collections

enum class McpRegistryServerStatus {
    ACTIVE,
    DEPRECATED,
}

/**
 * A bounded, display-safe summary of a Registry install declaration.
 *
 * Raw argument values, environment values, headers, and publisher extensions are deliberately not
 * retained. A declaration marked unavailable is metadata only and cannot produce a settings draft.
 */
data class McpRegistryInstallDeclaration(
    val type: String,
    val identifier: String,
    val transport: String,
    val version: String = "",
    val installable: Boolean,
    val unavailableReason: String = "",
) {
    init {
        requireSafeRegistryText(type, "Registry declaration type", MAX_TYPE_CHARS)
        requireSafeRegistryText(identifier, "Registry declaration identifier", MAX_IDENTIFIER_CHARS)
        requireSafeRegistryText(transport, "Registry declaration transport", MAX_TRANSPORT_CHARS)
        require(version.length <= MAX_VERSION_CHARS && version.none(Char::isISOControl)) {
            "Registry declaration version is invalid"
        }
        if (installable) {
            require(unavailableReason.isBlank()) { "An installable declaration cannot have an unavailable reason" }
        } else {
            requireSafeRegistryText(
                unavailableReason,
                "Registry declaration unavailable reason",
                MAX_REASON_CHARS,
            )
        }
    }

    private companion object {
        const val MAX_TYPE_CHARS = 40
        const val MAX_IDENTIFIER_CHARS = 320
        const val MAX_TRANSPORT_CHARS = 40
        const val MAX_VERSION_CHARS = 128
        const val MAX_REASON_CHARS = 200
    }
}

/** Provenance and lifecycle data preserved from the official Registry response. */
class McpRegistryEntryMetadata(
    val registryName: String,
    val version: String,
    val status: McpRegistryServerStatus,
    val publishedAt: Instant?,
    val updatedAt: Instant?,
    installDeclarations: Collection<McpRegistryInstallDeclaration>,
) {
    val installDeclarations: List<McpRegistryInstallDeclaration> =
        Collections.unmodifiableList(ArrayList(installDeclarations))

    /** Registry publication is namespace verification, not an OmniCode security review. */
    val reviewed: Boolean = false

    init {
        require(registryName.length <= MAX_REGISTRY_NAME_CHARS && REGISTRY_NAME.matches(registryName)) {
            "Registry server name is invalid"
        }
        require(version.isNotBlank() && version.length <= MAX_VERSION_CHARS && version.none(Char::isISOControl)) {
            "Registry server version is invalid"
        }
        require(this.installDeclarations.size <= MAX_DECLARATIONS) {
            "Registry metadata may contain at most $MAX_DECLARATIONS install declarations"
        }
    }

    private companion object {
        val REGISTRY_NAME = Regex("[A-Za-z0-9.-]+/[A-Za-z0-9._-]+")
        const val MAX_REGISTRY_NAME_CHARS = 200
        const val MAX_VERSION_CHARS = 128
        const val MAX_DECLARATIONS = 16
    }
}

private fun requireSafeRegistryText(value: String, label: String, maxChars: Int) {
    require(value.isNotBlank() && value.length <= maxChars && value.none(Char::isISOControl)) {
        "$label is invalid"
    }
    require('<' !in value && '>' !in value) { "$label cannot contain markup" }
}
