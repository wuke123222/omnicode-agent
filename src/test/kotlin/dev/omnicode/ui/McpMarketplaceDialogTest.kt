package dev.omnicode.ui

import dev.omnicode.mcp.McpMarketplaceCatalog
import dev.omnicode.mcp.McpCatalogCategory
import dev.omnicode.mcp.McpCatalogEntry
import dev.omnicode.mcp.McpCatalogRiskLevel
import dev.omnicode.mcp.McpCatalogSource
import dev.omnicode.mcp.McpRegistryEntryMetadata
import dev.omnicode.mcp.McpRegistryServerStatus
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpMarketplaceDialogTest {
    @Test
    fun `marketplace uses a list detail flow below the wide threshold`() {
        assertEquals(McpMarketplaceLayoutMode.COMPACT, mcpMarketplaceLayoutMode(0))
        assertEquals(McpMarketplaceLayoutMode.COMPACT, mcpMarketplaceLayoutMode(1))
        assertEquals(McpMarketplaceLayoutMode.COMPACT, mcpMarketplaceLayoutMode(719))
        assertEquals(McpMarketplaceLayoutMode.WIDE, mcpMarketplaceLayoutMode(720))
    }

    @Test
    fun `install warnings state that browsing never executes package runners`() {
        val packageOption = McpMarketplaceCatalog.find("memory")!!.installOptions.single()
        val warning = optionDownloadWarning(packageOption).orEmpty()

        assertTrue("不会立即下载或运行" in warning)
    }

    @Test
    fun `local catalog wins registry id collisions while new entries remain searchable candidates`() {
        val memory = McpMarketplaceCatalog.find("memory")!!
        val filesystem = McpMarketplaceCatalog.find("filesystem")!!
        val github = McpMarketplaceCatalog.find("github")!!

        val merged = mergeMcpMarketplaceEntries(
            localEntries = listOf(memory, filesystem),
            registryEntries = listOf(memory, github),
        )

        assertEquals(listOf("memory", "filesystem", "github"), merged.map { it.id })
        assertSame(memory, merged.first())
        assertTrue(MCP_MARKETPLACE_MAX_RESULTS >= 600)
    }

    @Test
    fun `registry status never hides the usable local catalog`() {
        assertTrue("本地精选仍可浏览" in mcpRegistryStatusPresentation(McpRegistryUiState.LOADING).text)
        assertTrue("本地精选" in mcpRegistryStatusPresentation(McpRegistryUiState.EMPTY).text)
        assertEquals(
            "目录共 348 条 · Registry 321 条未经 OmniCode 审阅",
            mcpRegistryStatusPresentation(
                McpRegistryUiState.READY,
                registryCount = 321,
                totalCount = 348,
            ).text,
        )
        assertTrue("保留上次加载的 42 条" in mcpRegistryStatusPresentation(
            McpRegistryUiState.OFFLINE,
            retainedRegistryCount = 42,
        ).text)
        assertFalse(mcpRegistryStatusPresentation(McpRegistryUiState.OFFLINE).isError)
        assertTrue(mcpRegistryStatusPresentation(McpRegistryUiState.FAILED).isError)
        assertEquals(McpRegistryUiState.OFFLINE, mcpRegistryFailureState(IOException("offline")))
        assertEquals(McpRegistryUiState.FAILED, mcpRegistryFailureState(IllegalStateException("bad payload")))
    }

    @Test
    fun `registry browse only entries are explicitly unreviewed and cannot be added`() {
        val entry = McpCatalogEntry(
            id = "registry-browse-only",
            name = "Browse only",
            publisher = "Example publisher",
            description = "Registry metadata without a compatible OmniCode installation declaration.",
            source = McpCatalogSource.MCP_REGISTRY,
            riskLevel = McpCatalogRiskLevel.HIGH,
            riskSummary = "Registry metadata is unreviewed and cannot be installed from this declaration.",
            tags = setOf("registry", "browse-only"),
            links = emptyList(),
            installOptions = emptyList(),
            category = McpCatalogCategory.DEVELOPMENT,
            registryMetadata = McpRegistryEntryMetadata(
                registryName = "example/browse-only",
                version = "1.0.0",
                status = McpRegistryServerStatus.ACTIVE,
                publishedAt = null,
                updatedAt = null,
                installDeclarations = emptyList(),
            ),
        )

        assertEquals("Registry · 未审阅", mcpMarketplaceSourceBadge(entry.source))
        assertEquals("Registry（未审阅）", mcpMarketplaceSourceFilterLabel(entry.source))
        assertFalse(mcpMarketplaceCanAdd(entry))
    }
}
