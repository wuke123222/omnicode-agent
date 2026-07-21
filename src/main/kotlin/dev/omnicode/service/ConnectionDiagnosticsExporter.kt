package dev.omnicode.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.persistence.DefaultSensitiveDataRedactor
import dev.omnicode.persistence.SensitiveDataRedactor

/** Generates bounded, secret-free content only; callers decide where or whether to save it. */
class ConnectionDiagnosticsExporter(
    private val redactor: SensitiveDataRedactor = DefaultSensitiveDataRedactor(),
    userHome: String? = System.getProperty("user.home"),
) {
    private val userHome = userHome?.trimEnd('/', '\\')?.takeIf(String::isNotBlank)

    fun export(report: ConnectionDiagnosticsReport): ConnectionDiagnosticsExport = ConnectionDiagnosticsExport(
        markdown = toMarkdown(report),
        json = toJson(report),
    )

    fun toMarkdown(report: ConnectionDiagnosticsReport): String {
        val content = buildString {
            appendLine("# OmniCode connection diagnostics")
            appendLine()
            appendLine("> This report contains configuration state only. Credential values, raw environment data and user paths are excluded.")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("| --- | --- |")
            appendLine("| Schema | ${report.schemaVersion} |")
            appendLine("| Generated (UTC) | ${markdownCell(report.generatedAt.toString())} |")
            appendLine("| Overall | ${report.overallStatus.name} |")
            appendLine("| Duration | ${report.durationMillis} ms |")
            appendLine("| PASS | ${report.count(ConnectionDiagnosticStatus.PASS)} |")
            appendLine("| WARN | ${report.count(ConnectionDiagnosticStatus.WARN)} |")
            appendLine("| FAIL | ${report.count(ConnectionDiagnosticStatus.FAIL)} |")
            appendLine("| SKIP | ${report.count(ConnectionDiagnosticStatus.SKIP)} |")
            ConnectionDiagnosticCategory.entries.forEach { category ->
                val categoryChecks = report.checks.filter { it.category == category }
                if (categoryChecks.isEmpty()) return@forEach
                appendLine()
                appendLine("## ${category.name.lowercase().replaceFirstChar(Char::uppercase)}")
                appendLine()
                appendLine("| Status | Check | Time | Result | Recovery |")
                appendLine("| --- | --- | ---: | --- | --- |")
                categoryChecks.forEach { check ->
                    append("| ${check.status.name} | ")
                    append(markdownCell(check.title)).append(" | ")
                    append(check.durationMillis).append(" ms | ")
                    append(markdownCell(check.summary)).append(" | ")
                    append(markdownCell(check.recoverySuggestion ?: "—")).appendLine(" |")
                }
            }
            appendLine()
            appendLine("_Network diagnostics use a credential-free, non-redirecting request to the exact configured provider Base URL. MCP authorization and stdio launch are never started by this report._")
        }
        return sanitize(content)
    }

    fun toJson(report: ConnectionDiagnosticsReport): String {
        val root = JsonObject().apply {
            addProperty("schemaVersion", report.schemaVersion)
            addProperty("generatedAt", report.generatedAt.toString())
            addProperty("durationMillis", report.durationMillis)
            addProperty("overallStatus", report.overallStatus.name)
            add("counts", JsonObject().apply {
                ConnectionDiagnosticStatus.entries.forEach { status ->
                    addProperty(status.name, report.count(status))
                }
            })
            add("checks", JsonArray().apply {
                report.checks.forEach { check ->
                    add(JsonObject().apply {
                        addProperty("id", safeField(check.id))
                        addProperty("category", check.category.name)
                        addProperty("title", safeField(check.title))
                        addProperty("status", check.status.name)
                        addProperty("summary", safeField(check.summary))
                        addProperty("durationMillis", check.durationMillis)
                        check.recoverySuggestion?.let { addProperty("recoverySuggestion", safeField(it)) }
                    })
                }
            })
        }
        return sanitize(PRETTY_GSON.toJson(root))
    }

    private fun markdownCell(value: String): String = safeField(value)
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", "<br>")

    private fun safeField(value: String): String = sanitize(value).take(MAX_FIELD_CHARS)

    private fun sanitize(value: String): String {
        var sanitized = redactor.redact(value)
        userHome?.let { home -> sanitized = sanitized.replace(home, USER_HOME_REDACTION, ignoreCase = true) }
        sanitized = AUTHORIZATION_LINE.replace(sanitized, "[REDACTED_HEADER]")
        sanitized = SECRET_QUERY.replace(sanitized, "[REDACTED_QUERY]")
        sanitized = UNIX_USER_PATH.replace(sanitized, USER_HOME_REDACTION)
        sanitized = WINDOWS_USER_PATH.replace(sanitized, USER_HOME_REDACTION)
        sanitized = ENVIRONMENT_ASSIGNMENT.replace(sanitized) { match ->
            "${match.groupValues[1]}=[ENVIRONMENT_VALUE_REDACTED]"
        }
        return sanitized
    }

    private companion object {
        const val USER_HOME_REDACTION = "[USER_HOME]"
        const val MAX_FIELD_CHARS = 4_000
        val PRETTY_GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        val AUTHORIZATION_LINE = Regex(
            pattern = "(?im)^\\s*authorization\\s*[:=].*$",
        )
        val SECRET_QUERY = Regex(
            pattern = "(?i)[?&](?:api[_-]?key|key|token|access[_-]?token|secret|password)=[^&#\\s]+",
        )
        val UNIX_USER_PATH = Regex(
            pattern = "(?i)(?:file://)?/(?:users|home)/[^/\\s\"'<>]+(?:/[^\\s\"'<>|]*)?",
        )
        val WINDOWS_USER_PATH = Regex(
            pattern = "(?i)[a-z]:\\\\users\\\\[^\\\\\\s\"'<>]+(?:\\\\[^\\s\"'<>|]*)?",
        )
        val ENVIRONMENT_ASSIGNMENT = Regex(
            pattern = "(?m)^\\s*([A-Za-z_][A-Za-z0-9_]{1,80})=.+$",
        )
    }
}
