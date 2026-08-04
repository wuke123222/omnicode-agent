package dev.omnicode.service

import java.util.Locale

/** A bounded, offline-only validation result for a BibTeX attachment. */
data class CitationValidationReport(
    val entries: List<CitationEntry>,
    val duplicateKeys: List<String>,
    val duplicateDois: List<String>,
    val issues: List<CitationValidationIssue>,
    val networkChecked: Boolean = false,
    val truncated: Boolean = false,
) {
    val isValid: Boolean
        get() = issues.isEmpty() && duplicateKeys.isEmpty() && duplicateDois.isEmpty() && !truncated
}

data class CitationEntry(
    val type: String,
    val key: String,
    val doi: String? = null,
    val sourceOffset: Int,
)

data class CitationValidationIssue(
    val code: Code,
    val message: String,
    val key: String? = null,
) {
    enum class Code {
        EMPTY_SOURCE,
        SOURCE_TRUNCATED,
        MISSING_KEY,
        MALFORMED_DOI,
    }
}

/**
 * Validates only syntax and duplicates. It never contacts Crossref, DOI.org, or any provider and
 * therefore cannot claim that a DOI resolves. Callers should present [networkChecked] as false
 * until a separately approved network action completes.
 */
object ResearchCitationValidator {
    const val MAX_SOURCE_CHARS: Int = 256 * 1024
    /** Small enough for a tooltip/card update on the EDT; full validation remains bounded separately. */
    const val MAX_UI_SOURCE_CHARS: Int = 32 * 1024
    const val MAX_ENTRIES: Int = 2_000

    private val entryStart = Regex("""@([A-Za-z][A-Za-z0-9_-]*)\s*\{\s*([^,\s{}]+)\s*,""")
    private val doiField = Regex("""(?is)\bdoi\s*=\s*[\{\"']?\s*([^,\s\}\"']+)""")
    private val doiValue = Regex("""^10\.\d{4,9}/[-._;()/:A-Z0-9]+$""", RegexOption.IGNORE_CASE)
    private val ignoredTypes = setOf("comment", "string", "preamble")

    fun validate(source: String): CitationValidationReport {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) {
            return CitationValidationReport(
                entries = emptyList(),
                duplicateKeys = emptyList(),
                duplicateDois = emptyList(),
                issues = listOf(CitationValidationIssue(CitationValidationIssue.Code.EMPTY_SOURCE, "BibTeX 内容为空。")),
            )
        }
        val bounded = source.take(MAX_SOURCE_CHARS)
        val truncated = source.length > MAX_SOURCE_CHARS
        val issues = buildList {
            if (truncated) {
                add(
                    CitationValidationIssue(
                        CitationValidationIssue.Code.SOURCE_TRUNCATED,
                        "BibTeX 超过 ${MAX_SOURCE_CHARS} 个字符，校验结果不完整。",
                    ),
                )
            }
        }.toMutableList()
        val entries = mutableListOf<CitationEntry>()
        entryStart.findAll(bounded).take(MAX_ENTRIES).forEach { match ->
            val type = match.groupValues[1].lowercase(Locale.ROOT)
            if (type in ignoredTypes) return@forEach
            val key = match.groupValues[2].trim()
            val bodyStart = match.range.last + 1
            val bodyEnd = findEntryEnd(bounded, bodyStart).coerceAtMost(bounded.length)
            val body = bounded.substring(bodyStart, bodyEnd)
            val doiToken = doiField.find(body)?.groupValues?.get(1)?.trim()?.trimEnd('.', ';')
            if (doiToken != null && !doiValue.matches(doiToken)) {
                issues += CitationValidationIssue(
                    CitationValidationIssue.Code.MALFORMED_DOI,
                    "条目 $key 的 DOI 格式无效：$doiToken。",
                    key,
                )
            }
            entries += CitationEntry(type, key, doiToken?.lowercase(Locale.ROOT), match.range.first)
        }

        // A non-ignored entry opener without the key/comma shape is actionable but does not abort
        // the whole report; this is especially useful while a user is editing a .bib file.
        val opener = Regex("""@([A-Za-z][A-Za-z0-9_-]*)\s*\{""")
        opener.findAll(bounded).take(MAX_ENTRIES).forEach { match ->
            if (match.groupValues[1].lowercase(Locale.ROOT) !in ignoredTypes &&
                entries.none { it.sourceOffset == match.range.first }
            ) {
                issues += CitationValidationIssue(
                    CitationValidationIssue.Code.MISSING_KEY,
                    "条目 ${match.groupValues[1]} 缺少引用 key 或逗号。",
                    null,
                )
            }
        }

        val duplicateKeys = entries.groupingBy { it.key.lowercase(Locale.ROOT) }
            .eachCount().filterValues { it > 1 }.keys.sorted()
        val duplicateDois = entries.asSequence().mapNotNull { it.doi }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        return CitationValidationReport(
            entries = entries,
            duplicateKeys = duplicateKeys,
            duplicateDois = duplicateDois,
            issues = issues,
            truncated = truncated,
        )
    }

    private fun findEntryEnd(source: String, start: Int): Int {
        var depth = 1
        var quoted = false
        var escaped = false
        for (index in start until source.length) {
            val character = source[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (character == '\\' && quoted) {
                escaped = true
                continue
            }
            if (character == '"') {
                quoted = !quoted
                continue
            }
            if (quoted) continue
            when (character) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return source.length
    }
}
