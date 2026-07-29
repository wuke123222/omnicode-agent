package dev.omnicode.tool

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.omnicode.agent.AgentMode
import dev.omnicode.model.ToolDefinition
import java.time.Duration

data class ToolExecutionContext(
    val project: Project,
    val approvalGate: ApprovalGate,
    val mode: AgentMode,
    val changeRecorder: TaskChangeRecorder? = null,
)

fun interface TaskChangeRecorder {
    fun record(relativePath: String, before: String?, after: String)
}

data class ToolExecutionResult(
    val content: String,
    val isError: Boolean = false,
)

data class ApprovalRequest(
    val toolName: String,
    val title: String,
    val details: String,
    val risk: String,
    val diff: ApprovalDiff? = null,
)

data class ApprovalDiff(
    val path: String,
    val before: String,
    val after: String,
)

fun interface ApprovalGate {
    suspend fun approve(request: ApprovalRequest): Boolean
}

/**
 * Security classification used to build the mode-specific tool surface.
 * New and third-party tools default to EXTERNAL so restricted modes fail closed.
 */
enum class ToolEffect {
    READ_ONLY,
    MUTATING,
    COMMAND,
    EXTERNAL,
}

interface AgentTool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val dangerous: Boolean
    val effect: ToolEffect
        get() = ToolEffect.EXTERNAL
    /**
     * Trusted orchestration tools may need longer than the generic single-tool timeout. AgentEngine
     * always caps this override at the run wall-clock limit, so it cannot outlive the task.
     */
    val executionTimeout: Duration?
        get() = null

    suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult

    fun definition(): ToolDefinition = ToolDefinition(name, description, inputSchema)
}
