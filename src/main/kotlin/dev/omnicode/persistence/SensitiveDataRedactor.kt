package dev.omnicode.persistence

fun interface SensitiveDataRedactor {
    fun redact(value: String): String
}

/**
 * Defense-in-depth redaction for free-form snapshots. Callers may also supply the
 * active provider secrets so opaque tokens without a recognizable prefix are removed.
 */
class DefaultSensitiveDataRedactor(
    knownSecrets: Collection<String> = emptyList(),
) : SensitiveDataRedactor {
    private val knownSecrets = knownSecrets
        .asSequence()
        .filter { it.length >= MIN_SECRET_LENGTH }
        .distinct()
        .sortedByDescending(String::length)
        .toList()

    override fun redact(value: String): String {
        var redacted = value
        knownSecrets.forEach { secret ->
            redacted = redacted.replace(secret, REDACTED)
        }
        redacted = PRIVATE_KEY.replace(redacted, REDACTED)
        redacted = AUTHORIZATION_HEADER.replace(redacted) { "${it.groupValues[1]}$REDACTED" }
        redacted = BEARER_TOKEN.replace(redacted) { "${it.groupValues[1]} $REDACTED" }
        redacted = JWT.replace(redacted, REDACTED)
        redacted = KNOWN_TOKEN_PREFIX.replace(redacted, REDACTED)
        redacted = NAMED_SECRET.replace(redacted) { "${it.groupValues[1]}$REDACTED" }
        return redacted
    }

    private companion object {
        const val MIN_SECRET_LENGTH = 4
        const val REDACTED = "[REDACTED]"

        val PRIVATE_KEY = Regex(
            pattern = """-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val BEARER_TOKEN = Regex(
            pattern = """(?i)\b(Bearer)\s+[A-Za-z0-9._~+/=-]{8,}""",
        )
        val AUTHORIZATION_HEADER = Regex(
            pattern = """(?im)^(\s*Authorization\s*:\s*)([^\r\n]+)""",
        )
        val JWT = Regex(
            pattern = """\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""",
        )
        val KNOWN_TOKEN_PREFIX = Regex(
            pattern = """(?i)\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{8,}|sk-ant-[A-Za-z0-9_-]{8,}|xox[baprs]-[A-Za-z0-9-]{8,}|gh[pousr]_[A-Za-z0-9]{12,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16})\b""",
        )
        val NAMED_SECRET = Regex(
            pattern = """(?i)(["']?(?:api[_-]?key|authorization|access[_-]?token|session[_-]?token|secret(?:[_-]?key)?|password|aws[_-]?secret[_-]?access[_-]?key)["']?\s*[:=]\s*["']?)([^\s"',}\]]{4,})""",
        )
    }
}
