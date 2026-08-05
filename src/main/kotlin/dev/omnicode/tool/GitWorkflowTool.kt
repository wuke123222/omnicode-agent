package dev.omnicode.tool

import com.google.gson.JsonObject
import dev.omnicode.agent.AgentMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Approved Git worktree and pull-request operations.
 *
 * The tool never invokes a shell and never owns a second process policy. It presents one explicit
 * approval to the user, then delegates the tokenized argv to the existing RunCommandTool with an
 * already-approved gate. The command still goes through path revalidation, sandbox selection,
 * timeout, output limits, audit, and recovery checkpoint handling.
 */
internal class GitWorkflowTool(
    private val commandTool: AgentTool,
) : AgentTool {
    override val name: String = "git_workflow"
    override val description: String =
        "Manage project-scoped Git worktrees and create a GitHub pull request through explicit approval. " +
            "Worktrees are restricted to .omnicode-worktrees; PR creation requires an installed gh CLI, " +
            "a configured account, and a network-capable sandbox."
    override val inputSchema: JsonObject = objectSchema(required = listOf("action")) {
        stringProperty("action", "Operation: worktree_list, worktree_create, worktree_remove, status, or pr_create.")
        stringProperty("path", "Project-relative worktree path under .omnicode-worktrees.")
        stringProperty("branch", "Git branch name for a worktree or PR head.")
        stringProperty("base", "Target branch for a pull request; defaults to the repository default.")
        stringProperty("title", "Pull-request title.")
        stringProperty("body", "Pull-request body; keep it concise and free of credentials.")
        booleanProperty("new_branch", "Create the branch when adding a worktree.", true)
    }
    override val dangerous: Boolean = true
    override val effect: ToolEffect = ToolEffect.MUTATING
    override val executionTimeout: Duration = Duration.ofMinutes(5)

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
        require(context.mode == AgentMode.AGENT) {
            "GIT_WORKFLOW_BLOCKED: Worktree and PR operations are available only in Agent mode."
        }
        val action = arguments.get("action")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        require(action in ACTIONS) { "Unsupported git_workflow action: $action" }
        val root = ProjectPathGuard.root(context.project).toRealPath()
        val command = buildCommand(action, arguments, root)
        val approval = context.approvalGate.approve(
            ApprovalRequest(
                toolName = name,
                title = command.title,
                details = command.argv.joinToString(" ") { quoteForApproval(it) },
                risk = command.risk,
            ),
        )
        if (!approval) return ToolExecutionResult("REJECTED_BY_USER: Git operation was not run.", true)

        if (action == "worktree_create") {
            val worktreeRoot = root.resolve(WORKTREE_DIRECTORY).normalize()
            Files.createDirectories(worktreeRoot)
            require(!Files.isSymbolicLink(worktreeRoot)) { "Worktree directory must not be a symbolic link" }
        }

        val delegatedContext = context.copy(approvalGate = ApprovalGate { true })
        val result = commandTool.execute(command.toArguments(), delegatedContext)
        return result.copy(content = "Git workflow ${action}:\n${result.content}")
    }

    private fun buildCommand(action: String, arguments: JsonObject, root: Path): GitCommand {
        val path = arguments.stringValue("path")
        val branch = arguments.stringValue("branch")
        return when (action) {
            "worktree_list" -> GitCommand(
                argv = listOf("git", "-C", root.toString(), "worktree", "list", "--porcelain"),
                title = "List project Git worktrees",
                risk = "Read-only Git metadata; output is bounded and no files are changed.",
            )
            "status" -> GitCommand(
                argv = listOf("git", "-C", root.toString(), "status", "--short", "--branch"),
                title = "Inspect project Git status",
                risk = "Read-only Git status.",
            )
            "worktree_create" -> {
                val worktree = worktreePath(root, path)
                validateBranch(branch)
                val createBranch = arguments.booleanValue("new_branch", default = true)
                GitCommand(
                    argv = buildList {
                        addAll(listOf("git", "-C", root.toString(), "worktree", "add"))
                        if (createBranch) addAll(listOf("-b", branch))
                        add(worktree.toString())
                        if (!createBranch) add(branch)
                    },
                    title = "Create Git worktree $path",
                    risk = "This creates a new worktree and may create or attach a branch. Review the path and branch before approving.",
                )
            }
            "worktree_remove" -> {
                val worktree = worktreePath(root, path)
                GitCommand(
                    argv = listOf("git", "-C", root.toString(), "worktree", "remove", worktree.toString()),
                    title = "Remove Git worktree $path",
                    risk = "Git may remove the selected worktree directory. The operation is not forced, so uncommitted changes cause a refusal.",
                )
            }
            "pr_create" -> {
                require(path.isBlank() || path == ".") { "pr_create does not accept a worktree path" }
                require(branch.isNotBlank()) { "pr_create requires branch" }
                validateBranch(branch)
                val title = arguments.stringValue("title")
                val body = arguments.stringValue("body")
                require(title.isNotBlank() && title.length <= MAX_PR_TITLE_CHARS) { "PR title is required and bounded" }
                require(body.length <= MAX_PR_BODY_CHARS) { "PR body exceeds the safe limit" }
                val base = arguments.stringValue("base").ifBlank { "main" }
                validateBranch(base)
                GitCommand(
                    argv = buildList {
                        addAll(listOf("gh", "pr", "create", "--head", branch, "--base", base, "--title", title, "--body", body))
                    },
                    title = "Create GitHub pull request $branch → $base",
                    risk = "This sends repository metadata and the proposed PR body to GitHub through the installed gh CLI. Network access and gh authentication are required.",
                )
            }
            else -> error("unreachable")
        }
    }

    private fun worktreePath(root: Path, relative: String): Path {
        require(relative.isNotBlank()) { "worktree path is required" }
        val worktreeRoot = root.resolve(WORKTREE_DIRECTORY).normalize()
        val candidate = root.resolve(relative).normalize()
        require(candidate.startsWith(worktreeRoot) && candidate != worktreeRoot) {
            "Worktrees must stay under $WORKTREE_DIRECTORY"
        }
        require(!Files.isSymbolicLink(worktreeRoot) && !Files.isSymbolicLink(candidate)) {
            "Worktree paths must not be symbolic links"
        }
        require(!Files.exists(candidate) || Files.isDirectory(candidate)) {
            "Worktree path exists but is not a directory"
        }
        return candidate
    }

    private fun validateBranch(value: String) {
        require(value.isNotBlank() && value.length <= MAX_BRANCH_CHARS) { "Invalid or missing branch name" }
        require(SAFE_BRANCH.matches(value) && ".." !in value && "@{" !in value) {
            "Branch name contains unsupported Git syntax"
        }
    }

    private data class GitCommand(
        val argv: List<String>,
        val title: String,
        val risk: String,
    ) {
        fun toArguments(): JsonObject = JsonObject().apply {
            add("argv", com.google.gson.JsonArray().apply { argv.forEach { add(it) } })
            addProperty("cwd", ".")
            addProperty("timeout_seconds", 300)
        }
    }

    private fun quoteForApproval(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_./:@=-]+"))) value else "'${value.replace("'", "'\\''")}'"

    private fun JsonObject.stringValue(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()

    private fun JsonObject.booleanValue(name: String, default: Boolean): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

    private companion object {
        val ACTIONS = setOf("worktree_list", "worktree_create", "worktree_remove", "status", "pr_create")
        val SAFE_BRANCH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
        const val WORKTREE_DIRECTORY = ".omnicode-worktrees"
        const val MAX_BRANCH_CHARS = 128
        const val MAX_PR_TITLE_CHARS = 240
        const val MAX_PR_BODY_CHARS = 12_000
    }
}
