package dev.omnicode.tool

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "Read a UTF-8 text file from the project with line numbers."
    override val dangerous = false
    override val effect = ToolEffect.READ_ONLY
    override val inputSchema: JsonObject = objectSchema(required = listOf("path")) {
        stringProperty("path", "Project-relative file path.")
        integerProperty("start_line", "First 1-based line to return.", 1, 1, 1_000_000)
        integerProperty("end_line", "Last 1-based line to return; at most 1000 lines are returned.", 400, 1, 1_000_000)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult = withContext(Dispatchers.IO) {
        val relative = arguments.string("path")
        val path = ProjectPathGuard.resolve(context.project, relative)
        val snapshot = readProjectFileSnapshot(context.project, path)
            ?: error("File does not exist: $relative")
        val start = arguments.int("start_line", 1).coerceAtLeast(1)
        val requestedEnd = arguments.int("end_line", start + 399).coerceAtLeast(start)
        val end = minOf(requestedEnd, start + 999)

        val lines = buildList {
            snapshot.text.lineSequence().forEachIndexed { index, line ->
                val lineNumber = index + 1
                if (lineNumber in start..end) add("$lineNumber\t$line")
            }
        }
        val content = if (lines.isEmpty()) "(no lines in requested range)" else lines.joinToString("\n")
        ToolExecutionResult("SHA-256: ${snapshot.sha256}\n$content")
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
