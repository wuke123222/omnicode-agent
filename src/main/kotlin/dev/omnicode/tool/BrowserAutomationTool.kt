package dev.omnicode.tool

import com.google.gson.JsonObject
import dev.omnicode.agent.AgentMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Small, explicit Playwright CLI bridge. It intentionally does not accept arbitrary JavaScript:
 * navigation and screenshots are the first safe primitives, while every network/browser action
 * is shown in the existing approval dialog and remains subject to the selected sandbox.
 */
internal class BrowserAutomationTool(
    private val commandTool: AgentTool,
) : AgentTool {
    override val name: String = "browser_automation"
    override val description: String =
        "Use an installed Playwright browser for an approved URL check, navigation, or screenshot. " +
            "No arbitrary JavaScript is accepted; network access requires danger-full-access and the user approval."
    override val inputSchema: JsonObject = objectSchema(required = listOf("action")) {
        stringProperty("action", "check, open, or screenshot")
        stringProperty("url", "An HTTPS or HTTP URL without embedded credentials.")
        stringProperty("path", "Project-relative output path under .omnicode-browser for screenshots.")
    }
    override val dangerous: Boolean = true
    override val effect: ToolEffect = ToolEffect.EXTERNAL
    override val executionTimeout: Duration = Duration.ofMinutes(3)

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
        require(context.mode == AgentMode.AGENT) {
            "BROWSER_AUTOMATION_BLOCKED: Browser automation is available only in Agent mode."
        }
        val action = arguments.stringValue("action")
        require(action in ACTIONS) { "Unsupported browser_automation action: $action" }
        val url = arguments.stringValue("url")
        val output = arguments.stringValue("path")
        val root = ProjectPathGuard.root(context.project).toRealPath()
        val command = when (action) {
            "check" -> BrowserCommand(
                argv = listOf("npx", "playwright", "--version"),
                title = "Check local Playwright installation",
                risk = "This starts the local Playwright CLI; no page is opened.",
            )
            "open" -> {
                validateUrl(url)
                BrowserCommand(
                    argv = listOf("npx", "playwright", "open", url),
                    title = "Open approved browser URL",
                    risk = "This opens an external page and may send the URL to the network. Review the URL.",
                )
            }
            "screenshot" -> {
                validateUrl(url)
                val target = browserOutputPath(root, output)
                BrowserCommand(
                    argv = listOf("npx", "playwright", "screenshot", url, target.toString()),
                    title = "Capture approved browser screenshot",
                    risk = "This loads an external page and writes a screenshot inside .omnicode-browser. Review the URL and path.",
                )
            }
            else -> error("unreachable")
        }
        if (!context.approvalGate.approve(
                ApprovalRequest(
                    toolName = name,
                    title = command.title,
                    details = command.argv.joinToString(" ") { quote(it) },
                    risk = command.risk,
                ),
        )) {
            return ToolExecutionResult("REJECTED_BY_USER: Browser operation was not run.", true)
        }
        if (action == "screenshot") {
            val outputRoot = root.resolve(OUTPUT_DIRECTORY).normalize()
            Files.createDirectories(outputRoot)
            require(!Files.isSymbolicLink(outputRoot)) { "Browser output directory must not be a symbolic link" }
        }
        return commandTool.execute(
            command.toArguments(),
            context.copy(approvalGate = ApprovalGate { true }),
        ).let { result -> result.copy(content = "Browser automation $action:\n${result.content}") }
    }

    private fun browserOutputPath(root: Path, relative: String): Path {
        require(relative.isNotBlank()) { "Screenshot path is required" }
        val outputRoot = root.resolve(OUTPUT_DIRECTORY).normalize()
        val target = root.resolve(relative).normalize()
        require(target.startsWith(outputRoot) && target != outputRoot) {
            "Browser output must stay under $OUTPUT_DIRECTORY"
        }
        require(!Files.isSymbolicLink(outputRoot) && !Files.isSymbolicLink(target)) {
            "Browser output paths must not be symbolic links"
        }
        require(target.fileName.toString().length <= 128) { "Screenshot filename is too long" }
        return target
    }

    private fun validateUrl(value: String) {
        require(value.length in 8..MAX_URL_CHARS) { "Browser URL is missing or too long" }
        require(value.startsWith("https://") || value.startsWith("http://")) {
            "Only http:// and https:// browser URLs are supported"
        }
        require(!value.contains('@') && !value.contains('\n') && !value.contains('\r') && !value.contains('\u0000')) {
            "Browser URL must not contain credentials or control characters"
        }
    }

    private data class BrowserCommand(
        val argv: List<String>,
        val title: String,
        val risk: String,
    ) {
        fun toArguments(): JsonObject = JsonObject().apply {
            add("argv", com.google.gson.JsonArray().apply { argv.forEach { add(it) } })
            addProperty("cwd", ".")
            addProperty("timeout_seconds", 180)
        }
    }

    private fun JsonObject.stringValue(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()

    private fun quote(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_./:@=-]+"))) value else "'${value.replace("'", "'\\''")}'"

    private companion object {
        val ACTIONS = setOf("check", "open", "screenshot")
        const val OUTPUT_DIRECTORY = ".omnicode-browser"
        const val MAX_URL_CHARS = 2_048
    }
}
