package dev.omnicode.mcp

import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpTransport
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpMarketplaceCatalogTest {
    @Test
    fun `built-in presets are bounded unique and do not claim official verification`() {
        val entries = McpMarketplaceCatalog.entries

        assertEquals(
            setOf(
                "memory",
                "sequential-thinking",
                "time",
                "filesystem",
                "fetch",
                "git",
                "github",
                "playwright",
                "chrome-devtools",
                "context7",
                "mongodb",
                "redis",
                "neon",
                "supabase",
                "qdrant",
                "sqlite",
                "arxiv",
                "zotero",
                "aws-documentation",
                "brave-search",
                "notion",
                "linear",
                "sentry",
                "azure",
                "cloudflare-observability",
                "docker-mcp-gateway",
                "kubernetes",
            ),
            entries.map(McpCatalogEntry::id).toSet(),
        )
        assertEquals(27, entries.size)
        assertEquals(entries.size, entries.map(McpCatalogEntry::id).distinct().size)
        assertTrue(entries.all { it.source == McpCatalogSource.BUILT_IN_PRESET })
        assertTrue(entries.all { it.tags.size in 1..12 })
        assertTrue(entries.all { it.installOptions.size in 1..6 })
        assertTrue(entries.none { entry ->
            listOf(entry.publisher, entry.source.displayName).any { value ->
                value.contains("official", ignoreCase = true) || value.contains("官方")
            }
        })
        assertNull(McpMarketplaceCatalog.find("../memory"))
        assertNotNull(McpMarketplaceCatalog.find("memory"))

        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> {
            (entries as MutableList<McpCatalogEntry>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> {
            (entries.first().installOptions.first().arguments as MutableList<String>).clear()
        }
    }

    @Test
    fun `catalog categories are stable represented and useful for filtering`() {
        val entries = McpMarketplaceCatalog.entries
        val counts = entries.groupingBy(McpCatalogEntry::category).eachCount()

        assertEquals(McpCatalogCategory.entries.toSet(), counts.keys)
        assertTrue(counts.values.all { it >= 4 })
        assertTrue(entries.all { it.category.id in it.tags })
        assertEquals(
            setOf("mongodb", "redis", "neon", "supabase", "qdrant", "sqlite"),
            McpMarketplaceCatalog.search(
                McpCatalogQuery(categories = setOf(McpCatalogCategory.DATA)),
            ).map(McpCatalogEntry::id).toSet(),
        )
    }

    @Test
    fun `primary source links are unique absolute https URLs`() {
        val links = McpMarketplaceCatalog.entries.flatMap(McpCatalogEntry::links)

        assertTrue(links.isNotEmpty())
        assertEquals(links.size, links.map { it.url }.distinct().size)
        links.forEach { link ->
            val uri = URI(link.url)
            assertEquals("https", uri.scheme)
            assertTrue(!uri.host.isNullOrBlank())
            assertNull(uri.userInfo)
            assertNull(uri.fragment)
        }
    }

    @Test
    fun `templates contain argv and credential names only`() {
        val forbiddenArgumentFragments = listOf(";", "&&", "||", "`", "\$(", "sk-", "password=")

        McpMarketplaceCatalog.entries.forEach { entry ->
            entry.installOptions.forEach { option ->
                assertFalse(option.command.any(Char::isWhitespace))
                assertTrue(option.arguments.all { argument ->
                    argument.none(Char::isWhitespace) &&
                        forbiddenArgumentFragments.none { fragment -> argument.contains(fragment, ignoreCase = true) }
                })
                assertTrue(option.environmentKeys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) })
                assertTrue(option.environmentKeys.none { it.contains('=') })

                val draft = McpMarketplaceCatalog.createDraft(entry.id, option.id)
                assertFalse(draft.config.enabled)
                assertEquals(option.environmentKeys, draft.requiredCredentialKeys)
            }
        }
    }

    @Test
    fun `search combines bounded text source tag and install method filters`() {
        assertEquals(
            listOf("memory"),
            McpMarketplaceCatalog.search(McpCatalogQuery(text = "knowledge graph")).map(McpCatalogEntry::id),
        )
        assertEquals(
            setOf("filesystem", "notion"),
            McpMarketplaceCatalog.search(McpCatalogQuery(tags = setOf("WORKSPACE"))).map(McpCatalogEntry::id).toSet(),
        )
        assertEquals(
            setOf("time", "fetch", "git", "redis", "qdrant", "sqlite", "arxiv", "aws-documentation"),
            McpMarketplaceCatalog.search(
                McpCatalogQuery(
                    sources = setOf(McpCatalogSource.BUILT_IN_PRESET),
                    installKinds = setOf(McpCatalogInstallKind.UVX_PACKAGE),
                ),
            ).map(McpCatalogEntry::id).toSet(),
        )
        assertEquals(1, McpMarketplaceCatalog.search(McpCatalogQuery(maxResults = 1)).size)
        assertTrue(McpMarketplaceCatalog.search(McpCatalogQuery(text = "no-such-server")).isEmpty())
    }

    @Test
    fun `install draft reuses existing config and remains disabled until review`() {
        val draft = McpMarketplaceCatalog.createDraft("filesystem", "npx", "Project files")

        assertEquals("filesystem", draft.entryId)
        assertEquals("npx", draft.optionId)
        assertEquals("Project files", draft.config.name)
        assertFalse(draft.config.enabled)
        assertEquals(McpTransport.STDIO, draft.config.transport)
        assertEquals("npx", draft.config.command)
        assertEquals(listOf("--yes", "@modelcontextprotocol/server-filesystem", "."), draft.config.arguments)
        assertEquals(".", draft.config.workingDirectory)
        assertTrue(draft.warnings.any { it.contains("默认禁用") })
        assertTrue(draft.warnings.any { it.contains("第三方包代码") })
    }

    @Test
    fun `credentialed preset contains key names only and never a credential value`() {
        val draft = McpMarketplaceCatalog.createDraft("github", "local-executable")

        assertFalse(draft.config.enabled)
        assertEquals(setOf("GITHUB_PERSONAL_ACCESS_TOKEN"), draft.requiredCredentialKeys)
        assertEquals(draft.requiredCredentialKeys, draft.config.environmentKeys)
        assertEquals("github-mcp-server", draft.config.command)
        assertEquals(listOf("stdio"), draft.config.arguments)
        assertTrue(draft.config.environmentKeys.all { it.matches(Regex("[A-Z_]+")) })
        assertTrue(draft.warnings.any { it.contains("PasswordSafe") })
        assertTrue(draft.config.id.startsWith("market-github-"))
    }

    @Test
    fun `catalog inputs and executable templates fail closed at their hard bounds`() {
        assertFailsWith<IllegalArgumentException> { McpCatalogQuery(text = "x".repeat(161)) }
        assertFailsWith<IllegalArgumentException> { McpCatalogQuery(maxResults = 0) }
        assertFailsWith<IllegalArgumentException> { McpCatalogQuery(maxResults = 1_001) }
        assertFailsWith<IllegalArgumentException> { McpCatalogQuery(tags = setOf("../unsafe")) }
        assertFailsWith<IllegalArgumentException> {
            McpMarketplaceCatalog.createDraft("memory", "npx", "<html>unsafe")
        }
        assertFailsWith<IllegalArgumentException> {
            McpCatalogLink(McpCatalogLinkKind.HOMEPAGE, "http://example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            McpCatalogInstallOption(
                id = "bad-env",
                displayName = "Bad environment",
                kind = McpCatalogInstallKind.LOCAL_EXECUTABLE,
                transport = McpTransport.STDIO,
                command = "server",
                environmentKeys = setOf("TOKEN=value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpCatalogInstallOption(
                id = "large-argument",
                displayName = "Large argument",
                kind = McpCatalogInstallKind.LOCAL_EXECUTABLE,
                transport = McpTransport.STDIO,
                command = "server",
                arguments = listOf("x".repeat(2_049)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            McpCatalogInstallOption(
                id = "remote-http",
                displayName = "Unsafe HTTP",
                kind = McpCatalogInstallKind.STREAMABLE_HTTP,
                transport = McpTransport.HTTP,
                url = "http://example.com/mcp",
                httpAuthMode = McpHttpAuthMode.NONE,
            )
        }
    }

    @Test
    fun `streamable HTTP template is bounded and separates local command fields`() {
        val option = McpCatalogInstallOption(
            id = "remote-http",
            displayName = "Remote HTTP",
            kind = McpCatalogInstallKind.STREAMABLE_HTTP,
            transport = McpTransport.HTTP,
            url = "https://mcp.example.com/api",
            httpAuthMode = McpHttpAuthMode.OAUTH,
            oauthClientId = "public-client",
            oauthScopes = listOf("tools:read"),
        )

        assertEquals("", option.command)
        assertTrue(option.arguments.isEmpty())
        assertEquals(McpHttpAuthMode.OAUTH, option.httpAuthMode)
        assertEquals(listOf("tools:read"), option.oauthScopes)
    }
}
