package dev.omnicode.tool

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.problems.WolfTheProblemSolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Exposes the IDE's already-computed problem-file index without running a compiler command. */
class ListIdeProblemsTool : AgentTool {
    override val name: String = "list_ide_problems"
    override val description: String =
        "List project files currently marked by the JetBrains IDE as having syntax or analysis problems. " +
            "Use this before inspecting likely broken files; read the returned files for details."
    override val dangerous: Boolean = false
    override val effect: ToolEffect = ToolEffect.READ_ONLY
    override val inputSchema: JsonObject = objectSchema { }

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolExecutionResult = withContext(Dispatchers.Default) {
        val root = ProjectPathGuard.root(context.project)
        val access = ProjectContextToolAccess.load(context.project)
        val application = ApplicationManager.getApplication()
        val read = Computable {
            val wolf = WolfTheProblemSolver.getInstance(context.project)
            val problems = mutableListOf<IdeProblemFile>()
            ProjectFileIndex.getInstance(context.project).iterateContent(ContentIterator { file ->
                if (!file.isDirectory && problems.size < MAX_IDE_PROBLEM_FILES && wolf.isProblemFile(file)) {
                    val relative = runCatching {
                        root.relativize(Path.of(file.path).toAbsolutePath().normalize())
                            .joinToString("/") { it.toString() }
                    }.getOrNull()
                    if (!relative.isNullOrBlank() && !relative.startsWith("..") && !access.isExcluded(Path.of(file.path))) {
                        problems += IdeProblemFile(relative, wolf.hasSyntaxErrors(file))
                    }
                }
                problems.size < MAX_IDE_PROBLEM_FILES
            })
            problems.sortedWith(
                compareByDescending<IdeProblemFile>(IdeProblemFile::syntaxErrors).thenBy(IdeProblemFile::path),
            )
        }
        val problems = if (application.isReadAccessAllowed) read.compute() else application.runReadAction(read)
        ToolExecutionResult(renderIdeProblemFiles(problems))
    }
}

internal data class IdeProblemFile(
    val path: String,
    val syntaxErrors: Boolean,
)

internal fun renderIdeProblemFiles(problems: List<IdeProblemFile>): String = when {
    problems.isEmpty() -> "The IDE has not reported any problem files in the current project."
    else -> buildString {
        appendLine("IDE problem files (${problems.size}):")
        problems.forEach { problem ->
            append("- ").append(problem.path)
            append(if (problem.syntaxErrors) " [syntax errors]" else " [analysis problems]")
            appendLine()
        }
        append("Read the relevant files to inspect exact code and current document contents.")
    }
}

private const val MAX_IDE_PROBLEM_FILES = 100
