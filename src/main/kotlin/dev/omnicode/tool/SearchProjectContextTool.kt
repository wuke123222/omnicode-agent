package dev.omnicode.tool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.omnicode.service.LargeRepositoryContextService
import dev.omnicode.service.RepositoryContextHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses IntelliJ's language indexes instead of walking a large repository into model context. */
class SearchProjectContextTool : AgentTool {
    override val name: String = "search_project_context"
    override val description: String =
        "Search project symbols or keyword occurrences using IntelliJ indexes. Respects OmniCode context exclusions " +
            "and degrades to bounded pinned-file search while indexes are updating."
    override val dangerous: Boolean = false
    override val effect: ToolEffect = ToolEffect.READ_ONLY
    override val inputSchema: JsonObject = objectSchema(required = listOf("query", "kind")) {
        stringProperty("query", "Symbol name or keyword to find (1-200 characters).")
        add("kind", JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", "Use symbol for PSI/navigation symbols or keyword for indexed text occurrences.")
            add("enum", JsonArray().apply {
                add("symbol")
                add("keyword")
            })
        })
        integerProperty("max_results", "Maximum bounded matches.", 30, 1, 100)
    }

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionResult = withContext(Dispatchers.Default) {
        val query = arguments.string("query").trim()
        val kind = arguments.string("kind").trim().lowercase()
        val limit = arguments.int("max_results", 30).coerceIn(1, 100)
        val service = LargeRepositoryContextService.getInstance(context.project)
        val result = when (kind) {
            "symbol" -> service.searchSymbols(query, limit)
            "keyword" -> service.searchKeywords(query, limit)
            else -> throw IllegalArgumentException("kind must be symbol or keyword")
        }
        ToolExecutionResult(buildString {
            append("Search mode: ").append(result.mode.name)
            if (result.degraded) append(" (degraded)")
            append(" · ").append(result.hits.size).append(" hit(s)")
            if (result.truncated) append(" · truncated")
            appendLine()
            result.message?.let { appendLine(it) }
            result.hits.forEach { hit -> appendHit(hit) }
            if (result.hits.isEmpty()) append("No indexed project match.")
        }.take(MAX_TOOL_RESULT_CHARS))
    }

    private fun StringBuilder.appendHit(hit: RepositoryContextHit) {
        append(hit.relativePath)
        if (hit.line > 0) append(':').append(hit.line)
        if (hit.column > 0) append(':').append(hit.column)
        append(" · ").append(hit.kind.name)
        hit.symbolName?.let { append(" · ").append(it) }
        if (hit.preview.isNotBlank()) append(" · ").append(hit.preview.replace('\n', ' ').take(400))
        appendLine()
    }

    private companion object {
        const val MAX_TOOL_RESULT_CHARS = 32_000
    }
}
