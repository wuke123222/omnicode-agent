package dev.omnicode.mcp

import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Provenance shown by the marketplace. It deliberately does not imply vendor certification. */
enum class McpCatalogSource(val displayName: String) {
    BUILT_IN_PRESET("Built-in Presets"),
    MCP_REGISTRY("MCP Registry · 未审阅"),
}

/** Stable marketplace grouping. [id] is also injected as an entry tag for simple UI filters. */
enum class McpCatalogCategory(val id: String, val displayName: String) {
    DEVELOPMENT("development", "开发"),
    DATA("data", "数据"),
    RESEARCH("research", "研究"),
    PRODUCTIVITY("productivity", "效率"),
    BROWSER("browser", "浏览器"),
    CLOUD("cloud", "云与基础设施"),
}

enum class McpCatalogRiskLevel(val displayName: String) {
    LOW("低风险"),
    MEDIUM("中风险"),
    HIGH("高风险"),
}

enum class McpCatalogInstallKind(val displayName: String) {
    NPX_PACKAGE("NPX package"),
    UVX_PACKAGE("UVX package"),
    LOCAL_EXECUTABLE("本地可执行文件"),
    STREAMABLE_HTTP("Streamable HTTP"),
}

/** A presentation-level filter that makes the difference between metadata and a safe draft explicit. */
enum class McpCatalogAvailability {
    ALL,
    INSTALLABLE,
    BROWSE_ONLY,
}

enum class McpCatalogLinkKind(val displayName: String) {
    REPOSITORY("代码仓库"),
    DOCUMENTATION("文档"),
    HOMEPAGE("主页"),
}

data class McpCatalogLink(
    val kind: McpCatalogLinkKind,
    val url: String,
) {
    init {
        McpCatalogPolicy.requireHttpsUrl(url, "Catalog link")
    }
}

/**
 * One reviewed way to configure a catalog entry.
 *
 * The command and argv are declarative data. This type has no launch, download, persistence, or
 * credential API; execution remains behind the existing MCP approval and sandbox boundary.
 */
class McpCatalogInstallOption(
    val id: String,
    val displayName: String,
    val kind: McpCatalogInstallKind,
    val transport: McpTransport,
    val command: String = "",
    arguments: Collection<String> = emptyList(),
    environmentKeys: Collection<String> = emptySet(),
    val workingDirectory: String = ".",
    val url: String = "",
    val httpAuthMode: McpHttpAuthMode = McpHttpAuthMode.NONE,
    val oauthClientId: String = "",
    oauthScopes: Collection<String> = emptyList(),
) {
    val arguments: List<String> = Collections.unmodifiableList(ArrayList(arguments))
    val environmentKeys: Set<String> = Collections.unmodifiableSet(LinkedHashSet(environmentKeys))
    val oauthScopes: List<String> = Collections.unmodifiableList(ArrayList(oauthScopes))

    init {
        McpCatalogPolicy.requireId(id, "Install option ID")
        McpCatalogPolicy.requireText(displayName, "Install option name", MAX_OPTION_NAME_CHARS)
        require(this.arguments.size <= MAX_ARGUMENTS) { "An install option may contain at most $MAX_ARGUMENTS arguments" }
        require(this.arguments.sumOf(String::length) <= MAX_ARGUMENT_CHARS_TOTAL) {
            "Install option arguments exceed the total character limit"
        }
        this.arguments.forEach { McpCatalogPolicy.requirePlainValue(it, "Argument", MAX_ARGUMENT_CHARS) }
        require(this.environmentKeys.size <= MAX_ENVIRONMENT_KEYS) {
            "An install option may request at most $MAX_ENVIRONMENT_KEYS credential keys"
        }
        this.environmentKeys.forEach { key ->
            require(McpCatalogPolicy.environmentKey.matches(key)) { "Invalid credential environment key: $key" }
        }
        McpCatalogPolicy.requirePlainValue(workingDirectory, "Working directory", MAX_PATH_CHARS, allowBlank = false)
        McpCatalogPolicy.requirePlainValue(oauthClientId, "OAuth Client ID", MAX_OAUTH_CLIENT_ID_CHARS)
        require(this.oauthScopes.size <= MAX_OAUTH_SCOPES) {
            "An install option may contain at most $MAX_OAUTH_SCOPES OAuth scopes"
        }
        this.oauthScopes.forEach { scope ->
            require(McpCatalogPolicy.oauthScope.matches(scope)) { "Invalid OAuth scope" }
        }

        when (transport) {
            McpTransport.STDIO -> {
                require(kind != McpCatalogInstallKind.STREAMABLE_HTTP) {
                    "A Streamable HTTP option must use HTTP transport"
                }
                McpCatalogPolicy.requireExecutable(command)
                require(url.isBlank()) { "A stdio install option cannot contain an HTTP URL" }
            }
            McpTransport.HTTP -> {
                require(kind == McpCatalogInstallKind.STREAMABLE_HTTP) {
                    "HTTP transport must use the Streamable HTTP install kind"
                }
                require(command.isBlank() && this.arguments.isEmpty()) {
                    "An HTTP install option cannot contain a local command"
                }
                require(this.environmentKeys.isEmpty()) {
                    "HTTP credentials must use the existing PasswordSafe-backed HTTP auth fields"
                }
                McpCatalogPolicy.requireSecureMcpUrl(url)
            }
        }
    }

    private companion object {
        const val MAX_OPTION_NAME_CHARS = 80
        const val MAX_ARGUMENTS = 32
        const val MAX_ARGUMENT_CHARS = 2_048
        const val MAX_ARGUMENT_CHARS_TOTAL = 16_384
        const val MAX_ENVIRONMENT_KEYS = 16
        const val MAX_PATH_CHARS = 4_096
        const val MAX_OAUTH_CLIENT_ID_CHARS = 2_048
        const val MAX_OAUTH_SCOPES = 64
    }
}

/** Declarative, compile-time catalog entry. No executable status is inferred from its publisher. */
class McpCatalogEntry(
    val id: String,
    val name: String,
    val publisher: String,
    val description: String,
    val source: McpCatalogSource,
    val riskLevel: McpCatalogRiskLevel,
    val riskSummary: String,
    tags: Collection<String>,
    links: Collection<McpCatalogLink>,
    installOptions: Collection<McpCatalogInstallOption>,
    val category: McpCatalogCategory = McpCatalogCategory.DEVELOPMENT,
    val registryMetadata: McpRegistryEntryMetadata? = null,
) {
    val tags: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(tags.map(McpCatalogPolicy::normalizeTag)).apply { add(category.id) },
    )
    val links: List<McpCatalogLink> = Collections.unmodifiableList(ArrayList(links))
    val installOptions: List<McpCatalogInstallOption> = Collections.unmodifiableList(ArrayList(installOptions))

    init {
        McpCatalogPolicy.requireId(id, "Catalog entry ID")
        McpCatalogPolicy.requireText(name, "Catalog entry name", MAX_NAME_CHARS)
        McpCatalogPolicy.requireText(publisher, "Catalog publisher", MAX_PUBLISHER_CHARS)
        McpCatalogPolicy.requireText(description, "Catalog description", MAX_DESCRIPTION_CHARS)
        McpCatalogPolicy.requireText(riskSummary, "Catalog risk summary", MAX_RISK_SUMMARY_CHARS)
        require(this.tags.size in 1..MAX_TAGS) { "A catalog entry must have between 1 and $MAX_TAGS tags" }
        require(this.links.size <= MAX_LINKS) { "A catalog entry may contain at most $MAX_LINKS links" }
        require(this.links.distinctBy { it.kind to it.url }.size == this.links.size) {
            "Catalog links must be unique"
        }
        require(this.installOptions.size <= MAX_INSTALL_OPTIONS) {
            "A catalog entry may contain at most $MAX_INSTALL_OPTIONS install options"
        }
        require(source != McpCatalogSource.BUILT_IN_PRESET || this.installOptions.isNotEmpty()) {
            "A built-in catalog entry must contain an install option"
        }
        require((source == McpCatalogSource.MCP_REGISTRY) == (registryMetadata != null)) {
            "Registry metadata must be present only on MCP Registry entries"
        }
        require(this.installOptions.map(McpCatalogInstallOption::id).distinct().size == this.installOptions.size) {
            "Install option IDs must be unique within a catalog entry"
        }
    }

    private companion object {
        const val MAX_NAME_CHARS = 120
        const val MAX_PUBLISHER_CHARS = 120
        const val MAX_DESCRIPTION_CHARS = 480
        const val MAX_RISK_SUMMARY_CHARS = 320
        const val MAX_TAGS = 12
        const val MAX_LINKS = 6
        const val MAX_INSTALL_OPTIONS = 6
    }
}

data class McpCatalogQuery(
    val text: String = "",
    val sources: Set<McpCatalogSource> = emptySet(),
    val tags: Set<String> = emptySet(),
    val installKinds: Set<McpCatalogInstallKind> = emptySet(),
    val maxResults: Int = 40,
    val categories: Set<McpCatalogCategory> = emptySet(),
    val availability: McpCatalogAvailability = McpCatalogAvailability.ALL,
) {
    init {
        require(text.length <= MAX_QUERY_CHARS && text.none(Char::isISOControl)) {
            "Catalog search text must contain at most $MAX_QUERY_CHARS printable characters"
        }
        require(tags.size <= MAX_QUERY_TAGS) { "A catalog query may contain at most $MAX_QUERY_TAGS tags" }
        tags.forEach(McpCatalogPolicy::normalizeTag)
        require(maxResults in 1..MAX_RESULTS) { "Catalog maxResults must be between 1 and $MAX_RESULTS" }
    }

    internal fun normalizedTags(): Set<String> = tags.mapTo(linkedSetOf(), McpCatalogPolicy::normalizeTag)

    private companion object {
        const val MAX_QUERY_CHARS = 160
        const val MAX_QUERY_TAGS = 12
        const val MAX_RESULTS = 1_000
    }
}

data class McpInstallDraft(
    val entryId: String,
    val optionId: String,
    val config: McpServerConfig,
    val warnings: List<String>,
    val requiredCredentialKeys: Set<String>,
) {
    init {
        require(!config.enabled) { "Marketplace drafts must remain disabled until explicit user review" }
        require(warnings.size in 1..8) { "An MCP install draft must have between 1 and 8 warnings" }
        warnings.forEach { McpCatalogPolicy.requireText(it, "Install warning", 480) }
        require(requiredCredentialKeys.size <= 16) { "Too many required credential keys" }
        requiredCredentialKeys.forEach { key ->
            require(McpCatalogPolicy.environmentKey.matches(key)) { "Invalid credential environment key" }
        }
    }
}

/**
 * Bounded local MCP marketplace catalog.
 *
 * Entries are compiled into the plugin. There is intentionally no registry fetch, dynamic plugin
 * loading, package installation, command execution, persistence, or secret-value input here.
 */
object McpMarketplaceCatalog {
    val entries: List<McpCatalogEntry> = Collections.unmodifiableList(arrayListOf(
        mcpPreset(
            id = "memory",
            name = "Memory",
            publisher = "Model Context Protocol examples",
            description = "Store and query a local knowledge graph. Review where persistent data is written before enabling it.",
            category = McpCatalogCategory.PRODUCTIVITY,
            riskLevel = McpCatalogRiskLevel.MEDIUM,
            riskSummary = "May write knowledge-graph data in the server working directory; NPX may download package code.",
            tags = listOf("memory", "knowledge-graph", "stdio"),
            repositoryPath = "memory",
            option = npxOption("@modelcontextprotocol/server-memory"),
        ),
        mcpPreset(
            id = "sequential-thinking",
            name = "Sequential Thinking",
            publisher = "Model Context Protocol examples",
            description = "Expose a structured reasoning tool for decomposing and revising plans.",
            category = McpCatalogCategory.RESEARCH,
            riskLevel = McpCatalogRiskLevel.MEDIUM,
            riskSummary = "The tool is locally scoped, but NPX may download and execute package code on first launch.",
            tags = listOf("planning", "reasoning", "stdio"),
            repositoryPath = "sequentialthinking",
            option = npxOption("@modelcontextprotocol/server-sequential-thinking"),
        ),
        mcpPreset(
            id = "time",
            name = "Time",
            publisher = "Model Context Protocol examples",
            description = "Read current time and convert times between named time zones.",
            category = McpCatalogCategory.PRODUCTIVITY,
            riskLevel = McpCatalogRiskLevel.MEDIUM,
            riskSummary = "Runtime capability is read-only, but UVX may download and execute package code on first launch.",
            tags = listOf("time", "timezone", "stdio"),
            repositoryPath = "time",
            option = uvxOption("mcp-server-time"),
        ),
        mcpPreset(
            id = "filesystem",
            name = "Filesystem",
            publisher = "Model Context Protocol examples",
            description = "Read and modify files below explicitly configured roots. The built-in draft starts at the project root.",
            category = McpCatalogCategory.DEVELOPMENT,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can read and modify project files; NPX may also download package code. Verify every allowed root.",
            tags = listOf("filesystem", "files", "workspace", "stdio"),
            repositoryPath = "filesystem",
            option = npxOption("@modelcontextprotocol/server-filesystem", extraArguments = listOf(".")),
        ),
        mcpPreset(
            id = "fetch",
            name = "Fetch",
            publisher = "Model Context Protocol examples",
            description = "Retrieve and transform web content for model consumption.",
            category = McpCatalogCategory.BROWSER,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can send requests to external hosts and disclose requested URLs; UVX may download package code.",
            tags = listOf("web", "fetch", "network", "stdio"),
            repositoryPath = "fetch",
            option = uvxOption("mcp-server-fetch"),
        ),
        mcpPreset(
            id = "git",
            name = "Git",
            publisher = "Model Context Protocol examples",
            description = "Inspect repository status, diffs, history, branches, and commits through Git-aware tools.",
            category = McpCatalogCategory.DEVELOPMENT,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can change repository state and create commits; UVX may download package code. Review every Git action.",
            tags = listOf("git", "repository", "version-control", "stdio"),
            repositoryPath = "git",
            option = uvxOption("mcp-server-git", extraArguments = listOf("--repository", ".")),
        ),
        catalogPreset(
            id = "github",
            name = "GitHub",
            publisher = "GitHub",
            description = "Connect a preinstalled GitHub MCP server to repositories, issues, pull requests, and other account data.",
            category = McpCatalogCategory.DEVELOPMENT,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Uses an account credential and may perform external side effects allowed by its scopes and tools.",
            tags = listOf("github", "git", "repository", "network", "stdio"),
            primaryUrl = "https://github.com/github/github-mcp-server",
            option = localExecutableOption(
                command = "github-mcp-server",
                arguments = listOf("stdio"),
                environmentKeys = setOf("GITHUB_PERSONAL_ACCESS_TOKEN"),
            ),
        ),
        catalogPreset(
            id = "playwright",
            name = "Playwright",
            publisher = "Microsoft",
            description = "Automate Chromium, Firefox, and WebKit with accessibility snapshots and browser interaction tools.",
            category = McpCatalogCategory.BROWSER,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can browse, submit forms, download files, and expose page content; NPX may download package code.",
            tags = listOf("browser", "automation", "testing", "playwright", "stdio"),
            primaryUrl = "https://github.com/microsoft/playwright-mcp",
            option = npxOption("@playwright/mcp@latest", extraArguments = listOf("--isolated", "--headless")),
        ),
        catalogPreset(
            id = "chrome-devtools",
            name = "Chrome DevTools",
            publisher = "Chrome DevTools",
            description = "Inspect and automate Chrome pages, console output, network traffic, and performance traces.",
            category = McpCatalogCategory.BROWSER,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Controls a browser and can expose authenticated page data; NPX may download package code and the server checks for updates.",
            tags = listOf("browser", "chrome", "debugging", "performance", "stdio"),
            primaryUrl = "https://github.com/ChromeDevTools/chrome-devtools-mcp",
            option = npxOption("chrome-devtools-mcp@latest", extraArguments = listOf("--slim", "--headless")),
        ),
        catalogPreset(
            id = "context7",
            name = "Context7",
            publisher = "Upstash",
            description = "Search version-aware library documentation and retrieve focused code examples for development tasks.",
            category = McpCatalogCategory.RESEARCH,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Sends documentation queries to an external service and returns untrusted content; NPX may download package code.",
            tags = listOf("documentation", "libraries", "code", "search", "stdio"),
            primaryUrl = "https://github.com/upstash/context7",
            option = npxOption("@upstash/context7-mcp"),
        ),
        catalogPreset(
            id = "mongodb",
            name = "MongoDB",
            publisher = "MongoDB",
            description = "Inspect MongoDB databases and Atlas metadata through the vendor server's read-only mode.",
            category = McpCatalogCategory.DATA,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can disclose database records and metadata. The draft requests read-only mode, but credentials still grant external access.",
            tags = listOf("database", "mongodb", "atlas", "read-only", "stdio"),
            primaryUrl = "https://github.com/mongodb-js/mongodb-mcp-server",
            option = npxOption(
                "mongodb-mcp-server@latest",
                extraArguments = listOf("--readOnly"),
                environmentKeys = setOf("MDB_MCP_CONNECTION_STRING"),
            ),
        ),
        catalogPreset(
            id = "redis",
            name = "Redis",
            publisher = "Redis",
            description = "Query and manage local or remote Redis data using Redis-native data structure tools.",
            category = McpCatalogCategory.DATA,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can read or mutate Redis data according to the connected account; UVX may download package code.",
            tags = listOf("database", "redis", "cache", "key-value", "stdio"),
            primaryUrl = "https://github.com/redis/mcp-redis",
            option = uvxFromOption(
                distribution = "redis-mcp-server@latest",
                executable = "redis-mcp-server",
                environmentKeys = setOf("REDIS_HOST", "REDIS_PORT", "REDIS_DB", "REDIS_USERNAME", "REDIS_PWD"),
            ),
        ),
        catalogPreset(
            id = "neon",
            name = "Neon",
            publisher = "Neon",
            description = "Inspect Neon projects, schemas, queries, and performance through the hosted read-only endpoint.",
            category = McpCatalogCategory.DATA,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "OAuth grants access to Neon account and database metadata. The endpoint is constrained to read-only mode.",
            tags = listOf("database", "postgres", "neon", "oauth", "remote"),
            primaryUrl = "https://github.com/neondatabase-labs/mcp-server-neon",
            option = remoteOption("https://mcp.neon.tech/mcp?readonly=true", oauthScopes = listOf("read")),
        ),
        catalogPreset(
            id = "supabase",
            name = "Supabase",
            publisher = "Supabase Community",
            description = "Inspect Supabase projects, database schemas, documentation, and diagnostics through the hosted server.",
            category = McpCatalogCategory.DATA,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "OAuth grants external account access. The draft requests read-only tools but returned project content remains sensitive.",
            tags = listOf("database", "postgres", "supabase", "oauth", "remote"),
            primaryUrl = "https://github.com/supabase-community/supabase-mcp",
            option = remoteOption("https://mcp.supabase.com/mcp?read_only=true"),
        ),
        catalogPreset(
            id = "qdrant",
            name = "Qdrant",
            publisher = "Qdrant",
            description = "Store and retrieve semantic memories in a local or hosted Qdrant collection.",
            category = McpCatalogCategory.DATA,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can read and write vector data and may download an embedding model; UVX may download package code.",
            tags = listOf("database", "vector", "semantic-search", "memory", "stdio"),
            primaryUrl = "https://github.com/qdrant/mcp-server-qdrant",
            option = uvxOption(
                "mcp-server-qdrant",
                environmentKeys = setOf("QDRANT_URL", "QDRANT_API_KEY", "COLLECTION_NAME"),
            ),
        ),
        catalogPreset(
            id = "sqlite",
            name = "SQLite",
            publisher = "Model Context Protocol archived examples",
            description = "Explore a workspace-local SQLite database and run SQL through the archived reference server.",
            category = McpCatalogCategory.DATA,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can read and modify the configured database. This archived reference is no longer actively maintained; UVX may download code.",
            tags = listOf("database", "sqlite", "sql", "archived", "stdio"),
            primaryUrl = "https://github.com/modelcontextprotocol/servers-archived/tree/main/src/sqlite",
            option = uvxOption("mcp-server-sqlite", extraArguments = listOf("--db-path", "./omnicode-mcp.sqlite")),
        ),
        catalogPreset(
            id = "arxiv",
            name = "arXiv",
            publisher = "arxiv-mcp-server contributors",
            description = "Search arXiv, download papers, and read bounded paper text from a local research collection.",
            category = McpCatalogCategory.RESEARCH,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Downloads and stores papers; paper text is untrusted and may contain prompt injection. UVX may download package code.",
            tags = listOf("papers", "arxiv", "academic", "literature", "stdio"),
            primaryUrl = "https://github.com/blazickjp/arxiv-mcp-server",
            option = uvxOption("arxiv-mcp-server"),
        ),
        catalogPreset(
            id = "zotero",
            name = "Zotero",
            publisher = "zotero-mcp contributors",
            description = "Search and manage a Zotero library through a preinstalled Zotero MCP executable.",
            category = McpCatalogCategory.RESEARCH,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "May read or modify library metadata and attachments. Install and run Zotero setup separately before enabling.",
            tags = listOf("papers", "citations", "zotero", "library", "stdio"),
            primaryUrl = "https://github.com/54yyyu/zotero-mcp",
            option = localExecutableOption(command = "zotero-mcp"),
        ),
        catalogPreset(
            id = "aws-documentation",
            name = "AWS Documentation",
            publisher = "AWS Labs",
            description = "Search AWS documentation and recommendations without exposing account-management tools.",
            category = McpCatalogCategory.RESEARCH,
            riskLevel = McpCatalogRiskLevel.MEDIUM,
            riskSummary = "Sends documentation queries externally and returns untrusted content; UVX may download package code.",
            tags = listOf("aws", "documentation", "cloud", "search", "stdio"),
            primaryUrl = "https://github.com/awslabs/mcp",
            option = uvxOption("awslabs.aws-documentation-mcp-server@latest"),
        ),
        catalogPreset(
            id = "brave-search",
            name = "Brave Search",
            publisher = "Model Context Protocol archived examples",
            description = "Search the public web with a Brave Search API account through the archived reference server.",
            category = McpCatalogCategory.BROWSER,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Sends search queries externally and returns untrusted web content. The archived package is no longer actively maintained.",
            tags = listOf("search", "web", "brave", "archived", "stdio"),
            primaryUrl = "https://github.com/modelcontextprotocol/servers-archived/tree/main/src/brave-search",
            option = npxOption(
                "@modelcontextprotocol/server-brave-search",
                environmentKeys = setOf("BRAVE_API_KEY"),
            ),
        ),
        catalogPreset(
            id = "notion",
            name = "Notion",
            publisher = "Notion",
            description = "Read and write authorized Notion pages, databases, comments, and workspace content through OAuth.",
            category = McpCatalogCategory.PRODUCTIVITY,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "OAuth may grant access to private workspace data and write-capable tools. Verify the pages and permissions selected during consent.",
            tags = listOf("notes", "workspace", "notion", "oauth", "remote"),
            primaryUrl = "https://developers.notion.com/guides/mcp/get-started-with-mcp",
            primaryLinkKind = McpCatalogLinkKind.DOCUMENTATION,
            option = remoteOption("https://mcp.notion.com/mcp"),
        ),
        catalogPreset(
            id = "linear",
            name = "Linear",
            publisher = "Linear",
            description = "Read issues, projects, milestones, and comments using Linear's hosted read-only endpoint.",
            category = McpCatalogCategory.PRODUCTIVITY,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "OAuth grants access to private planning data. The selected endpoint exposes read tools only.",
            tags = listOf("issues", "projects", "planning", "oauth", "remote"),
            primaryUrl = "https://linear.app/docs/mcp",
            primaryLinkKind = McpCatalogLinkKind.DOCUMENTATION,
            option = remoteOption("https://mcp.linear.app/mcp/readonly", oauthScopes = listOf("read")),
        ),
        catalogPreset(
            id = "sentry",
            name = "Sentry",
            publisher = "Sentry",
            description = "Investigate Sentry issues, events, traces, releases, and project health through the hosted service.",
            category = McpCatalogCategory.DEVELOPMENT,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "OAuth may expose production telemetry and write-capable tools within granted projects and skills.",
            tags = listOf("observability", "errors", "debugging", "oauth", "remote"),
            primaryUrl = "https://github.com/getsentry/sentry-mcp",
            option = remoteOption("https://mcp.sentry.dev/mcp"),
        ),
        catalogPreset(
            id = "azure",
            name = "Azure",
            publisher = "Microsoft",
            description = "Inspect and manage Azure resources using a local server that reuses an existing Azure CLI sign-in.",
            category = McpCatalogCategory.CLOUD,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Uses ambient Azure CLI identity and can mutate cloud resources; NPX may download package code.",
            tags = listOf("azure", "cloud", "infrastructure", "resources", "stdio"),
            primaryUrl = "https://github.com/microsoft/mcp/tree/main/servers/Azure.Mcp.Server",
            option = npxOption("@azure/mcp@latest", extraArguments = listOf("server", "start")),
        ),
        catalogPreset(
            id = "cloudflare-observability",
            name = "Cloudflare Observability",
            publisher = "Cloudflare",
            description = "Inspect Cloudflare application logs and analytics through the managed observability server.",
            category = McpCatalogCategory.CLOUD,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "OAuth grants access to account telemetry that may contain production or customer data.",
            tags = listOf("cloudflare", "observability", "logs", "oauth", "remote"),
            primaryUrl = "https://developers.cloudflare.com/agents/model-context-protocol/cloudflare/servers-for-cloudflare/",
            primaryLinkKind = McpCatalogLinkKind.DOCUMENTATION,
            option = remoteOption("https://observability.mcp.cloudflare.com/mcp"),
        ),
        catalogPreset(
            id = "docker-mcp-gateway",
            name = "Docker MCP Gateway",
            publisher = "Docker",
            description = "Expose the Docker MCP Toolkit's configured default profile through its local stdio gateway.",
            category = McpCatalogCategory.CLOUD,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Can launch configured containerized MCP servers and inherit their permissions, secrets, network, and host mounts.",
            tags = listOf("docker", "containers", "gateway", "toolkit", "stdio"),
            primaryUrl = "https://github.com/docker/mcp-gateway",
            option = localExecutableOption(command = "docker", arguments = listOf("mcp", "gateway", "run")),
        ),
        catalogPreset(
            id = "kubernetes",
            name = "Kubernetes",
            publisher = "containers project contributors",
            description = "Inspect Kubernetes and OpenShift resources through the server's explicit read-only mode.",
            category = McpCatalogCategory.CLOUD,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Reads cluster resources using ambient kubeconfig credentials; NPX may download package code. The draft disables writes.",
            tags = listOf("kubernetes", "openshift", "cluster", "read-only", "stdio"),
            primaryUrl = "https://github.com/containers/kubernetes-mcp-server",
            option = npxOption("kubernetes-mcp-server@latest", extraArguments = listOf("--read-only")),
        ),
    )).also(::validateCatalog)

    fun find(id: String): McpCatalogEntry? {
        if (!McpCatalogPolicy.isLookupId(id)) return null
        return entries.firstOrNull { it.id == id }
    }

    fun search(query: McpCatalogQuery = McpCatalogQuery()): List<McpCatalogEntry> = search(entries, query)

    /** Searches a bounded caller-provided snapshot, including unreviewed Registry entries. */
    fun search(
        candidates: Collection<McpCatalogEntry>,
        query: McpCatalogQuery = McpCatalogQuery(),
    ): List<McpCatalogEntry> {
        require(candidates.size <= MAX_SEARCH_CANDIDATES) {
            "Catalog search may inspect at most $MAX_SEARCH_CANDIDATES entries"
        }
        val terms = query.text.trim().lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .take(MAX_QUERY_TERMS)
        val requiredTags = query.normalizedTags()
        val matches = candidates.asSequence()
            .filter { entry -> query.sources.isEmpty() || entry.source in query.sources }
            .filter { entry -> query.categories.isEmpty() || entry.category in query.categories }
            .filter { entry -> requiredTags.isEmpty() || entry.tags.containsAll(requiredTags) }
            .filter { entry ->
                query.installKinds.isEmpty() || entry.installOptions.any { option -> option.kind in query.installKinds }
            }
            .filter { entry ->
                when (query.availability) {
                    McpCatalogAvailability.ALL -> true
                    McpCatalogAvailability.INSTALLABLE -> entry.installOptions.isNotEmpty()
                    McpCatalogAvailability.BROWSE_ONLY -> entry.installOptions.isEmpty()
                }
            }
            .filter { entry ->
                if (terms.isEmpty()) return@filter true
                val searchable = buildString {
                    append(entry.name).append(' ')
                    append(entry.publisher).append(' ')
                    append(entry.description).append(' ')
                    append(entry.category.id).append(' ')
                    append(entry.category.displayName).append(' ')
                    append(entry.tags.joinToString(" "))
                }.lowercase(Locale.ROOT)
                terms.all(searchable::contains)
            }
            .mapIndexed { index, entry ->
                SearchMatch(
                    entry = entry,
                    originalIndex = index,
                    score = catalogSearchScore(entry, terms),
                )
            }
            .sortedWith(compareByDescending<SearchMatch> { it.score }.thenBy { it.originalIndex })
            .take(query.maxResults)
            .map(SearchMatch::entry)
            .toCollection(ArrayList())
        return Collections.unmodifiableList(matches)
    }

    private data class SearchMatch(
        val entry: McpCatalogEntry,
        val originalIndex: Int,
        val score: Int,
    )

    /** Creates a disabled existing-settings draft. No command is run and no value is persisted. */
    fun createDraft(
        entryId: String,
        optionId: String,
        displayName: String? = null,
    ): McpInstallDraft = createDraft(
        entry = find(entryId) ?: throw IllegalArgumentException("Unknown MCP catalog entry: $entryId"),
        optionId = optionId,
        displayName = displayName,
    )

    /** Converts reviewed local or unreviewed Registry metadata into a disabled settings draft. */
    fun createDraft(
        entry: McpCatalogEntry,
        optionId: String,
        displayName: String? = null,
    ): McpInstallDraft {
        val option = entry.installOptions.firstOrNull { it.id == optionId }
            ?: throw IllegalArgumentException("Unknown install option for ${entry.id}: $optionId")
        val security = scanMcpInstall(entry, option)
        require(!security.hasBlockingFinding) {
            security.findings.filter { it.severity == McpSecurityFindingSeverity.BLOCKING }
                .joinToString("；") { it.message }
        }
        val name = displayName?.trim()?.also {
            McpCatalogPolicy.requireText(it, "MCP server name", MAX_SERVER_NAME_CHARS)
        } ?: entry.name
        val credentialKeys = Collections.unmodifiableSet(LinkedHashSet(option.environmentKeys))
        val warnings = ArrayList<String>()
        warnings += "这里只创建默认禁用的配置草案；保存、启用和首次连接仍需要用户明确操作。"
        if (entry.source == McpCatalogSource.MCP_REGISTRY) {
            warnings += "此配置来自公开 MCP Registry 元数据，OmniCode 未审阅其代码、发布者或运行行为。"
        }
        warnings += security.warningTexts()
        warnings += entry.riskSummary
        if (option.kind == McpCatalogInstallKind.NPX_PACKAGE || option.kind == McpCatalogInstallKind.UVX_PACKAGE) {
            warnings += "首次启动可能联网下载并执行第三方包代码，请先核对包名、来源和版本策略。"
        }
        if (credentialKeys.isNotEmpty()) {
            warnings += "草案只保存凭据变量名；凭据值必须由用户另行写入 IDE PasswordSafe。"
        }
        return McpInstallDraft(
            entryId = entry.id,
            optionId = option.id,
            config = McpServerConfig(
                id = "market-${entry.id}-${UUID.randomUUID()}",
                name = name,
                enabled = false,
                command = option.command,
                arguments = option.arguments,
                environmentKeys = credentialKeys,
                workingDirectory = option.workingDirectory,
                transport = option.transport,
                url = option.url,
                httpAuthMode = option.httpAuthMode,
                oauthClientId = option.oauthClientId,
                oauthScopes = option.oauthScopes,
            ),
            warnings = Collections.unmodifiableList(warnings.take(8)),
            requiredCredentialKeys = credentialKeys,
        )
    }

    private const val MAX_QUERY_TERMS = 12
    private const val MAX_SERVER_NAME_CHARS = 120
    private const val MAX_SEARCH_CANDIDATES = 2_000
}

private fun mcpPreset(
    id: String,
    name: String,
    publisher: String,
    description: String,
    category: McpCatalogCategory,
    riskLevel: McpCatalogRiskLevel,
    riskSummary: String,
    tags: List<String>,
    repositoryPath: String,
    option: McpCatalogInstallOption,
): McpCatalogEntry = McpCatalogEntry(
    id = id,
    name = name,
    publisher = publisher,
    description = description,
    source = McpCatalogSource.BUILT_IN_PRESET,
    category = category,
    riskLevel = riskLevel,
    riskSummary = riskSummary,
    tags = tags,
    links = listOf(
        McpCatalogLink(
            McpCatalogLinkKind.REPOSITORY,
            "https://github.com/modelcontextprotocol/servers/tree/main/src/$repositoryPath",
        ),
    ),
    installOptions = listOf(option),
)

private fun catalogPreset(
    id: String,
    name: String,
    publisher: String,
    description: String,
    category: McpCatalogCategory,
    riskLevel: McpCatalogRiskLevel,
    riskSummary: String,
    tags: List<String>,
    primaryUrl: String,
    primaryLinkKind: McpCatalogLinkKind = McpCatalogLinkKind.REPOSITORY,
    option: McpCatalogInstallOption,
): McpCatalogEntry = McpCatalogEntry(
    id = id,
    name = name,
    publisher = publisher,
    description = description,
    source = McpCatalogSource.BUILT_IN_PRESET,
    category = category,
    riskLevel = riskLevel,
    riskSummary = riskSummary,
    tags = tags,
    links = listOf(McpCatalogLink(primaryLinkKind, primaryUrl)),
    installOptions = listOf(option),
)

private fun npxOption(
    packageName: String,
    extraArguments: List<String> = emptyList(),
    environmentKeys: Set<String> = emptySet(),
): McpCatalogInstallOption =
    McpCatalogInstallOption(
        id = "npx",
        displayName = McpCatalogInstallKind.NPX_PACKAGE.displayName,
        kind = McpCatalogInstallKind.NPX_PACKAGE,
        transport = McpTransport.STDIO,
        command = "npx",
        arguments = listOf("--yes", packageName) + extraArguments,
        environmentKeys = environmentKeys,
    )

private fun uvxOption(
    packageName: String,
    extraArguments: List<String> = emptyList(),
    environmentKeys: Set<String> = emptySet(),
): McpCatalogInstallOption = McpCatalogInstallOption(
    id = "uvx",
    displayName = McpCatalogInstallKind.UVX_PACKAGE.displayName,
    kind = McpCatalogInstallKind.UVX_PACKAGE,
    transport = McpTransport.STDIO,
    command = "uvx",
    arguments = listOf(packageName) + extraArguments,
    environmentKeys = environmentKeys,
)

private fun uvxFromOption(
    distribution: String,
    executable: String,
    environmentKeys: Set<String> = emptySet(),
): McpCatalogInstallOption = McpCatalogInstallOption(
    id = "uvx",
    displayName = McpCatalogInstallKind.UVX_PACKAGE.displayName,
    kind = McpCatalogInstallKind.UVX_PACKAGE,
    transport = McpTransport.STDIO,
    command = "uvx",
    arguments = listOf("--from", distribution, executable),
    environmentKeys = environmentKeys,
)

private fun localExecutableOption(
    command: String,
    arguments: List<String> = emptyList(),
    environmentKeys: Set<String> = emptySet(),
): McpCatalogInstallOption = McpCatalogInstallOption(
    id = "local-executable",
    displayName = "Preinstalled executable",
    kind = McpCatalogInstallKind.LOCAL_EXECUTABLE,
    transport = McpTransport.STDIO,
    command = command,
    arguments = arguments,
    environmentKeys = environmentKeys,
)

private fun remoteOption(
    url: String,
    oauthScopes: List<String> = emptyList(),
): McpCatalogInstallOption = McpCatalogInstallOption(
    id = "streamable-http",
    displayName = McpCatalogInstallKind.STREAMABLE_HTTP.displayName,
    kind = McpCatalogInstallKind.STREAMABLE_HTTP,
    transport = McpTransport.HTTP,
    url = url,
    httpAuthMode = McpHttpAuthMode.OAUTH,
    oauthScopes = oauthScopes,
)

private fun validateCatalog(entries: List<McpCatalogEntry>) {
    require(entries.size <= 128) { "The built-in MCP catalog may contain at most 128 entries" }
    require(entries.map(McpCatalogEntry::id).distinct().size == entries.size) {
        "Built-in MCP catalog IDs must be unique"
    }
}

private fun catalogSearchScore(entry: McpCatalogEntry, terms: List<String>): Int {
    if (terms.isEmpty()) return 0
    val name = entry.name.lowercase(Locale.ROOT)
    val publisher = entry.publisher.lowercase(Locale.ROOT)
    val tags = entry.tags.joinToString(" ").lowercase(Locale.ROOT)
    val description = entry.description.lowercase(Locale.ROOT)
    return terms.sumOf { term ->
        when {
            name == term -> 1_000
            name.startsWith(term) -> 800
            name.contains(term) -> 650
            publisher.contains(term) -> 450
            tags.contains(term) -> 350
            description.contains(term) -> 100
            else -> 0
        }
    }
}

internal object McpCatalogPolicy {
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    val environmentKey: Regex = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    val oauthScope: Regex = Regex("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,256}")
    private val executablePattern = Regex("[A-Za-z0-9._/\\\\:-]{1,256}")
    private val tagPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    fun requireId(value: String, label: String) {
        require(value.length <= 64 && idPattern.matches(value)) {
            "$label may contain lowercase letters, numbers, and single hyphens only"
        }
    }

    fun isLookupId(value: String): Boolean = value.length <= 64 && idPattern.matches(value)

    fun requireText(value: String, label: String, maxChars: Int) {
        require(value.isNotBlank() && value.length <= maxChars) { "$label must contain 1 to $maxChars characters" }
        require(value.none(Char::isISOControl)) { "$label cannot contain control characters" }
        require('<' !in value && '>' !in value) { "$label cannot contain markup" }
    }

    fun requirePlainValue(value: String, label: String, maxChars: Int, allowBlank: Boolean = true) {
        require(value.length <= maxChars && (allowBlank || value.isNotBlank())) {
            "$label exceeds its character limit or is blank"
        }
        require(value.none(Char::isISOControl)) { "$label cannot contain control characters" }
    }

    fun normalizeTag(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        require(normalized.length <= 32 && tagPattern.matches(normalized)) {
            "Catalog tags may contain lowercase letters, numbers, and single hyphens only"
        }
        return normalized
    }

    fun requireExecutable(value: String) {
        require(executablePattern.matches(value)) {
            "Catalog commands must be a single bounded executable name or path"
        }
    }

    fun requireHttpsUrl(value: String, label: String) {
        require(value.length <= 2_048 && value.none(Char::isISOControl)) { "$label is too long or invalid" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("$label is invalid", it) }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "$label must use an absolute HTTPS URL"
        }
        require(uri.userInfo == null && uri.fragment == null) { "$label cannot contain user info or a fragment" }
    }

    fun requireSecureMcpUrl(value: String) {
        require(value.length <= 2_048 && value.none(Char::isISOControl)) { "MCP URL is too long or invalid" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("MCP URL is invalid", it) }
        val loopback = uri.host.equals("localhost", ignoreCase = true) || uri.host == "127.0.0.1" || uri.host == "::1"
        require((uri.scheme.equals("https", ignoreCase = true) || (loopback && uri.scheme.equals("http", true))) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Remote MCP URLs must use HTTPS; HTTP is accepted only for loopback"
        }
    }
}
