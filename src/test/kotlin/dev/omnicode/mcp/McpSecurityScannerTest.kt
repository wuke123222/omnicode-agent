package dev.omnicode.mcp

import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpTransport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSecurityScannerTest {
    @Test
    fun `mutable package versions are visible before install`() {
        val option = McpCatalogInstallOption(
            id = "npx",
            displayName = "NPX",
            kind = McpCatalogInstallKind.NPX_PACKAGE,
            transport = McpTransport.STDIO,
            command = "npx",
            arguments = listOf("@example/server@latest"),
        )
        val entry = McpCatalogEntry(
            id = "example",
            name = "Example",
            publisher = "Example",
            description = "Example MCP server",
            source = McpCatalogSource.BUILT_IN_PRESET,
            riskLevel = McpCatalogRiskLevel.MEDIUM,
            riskSummary = "Review the package.",
            tags = listOf("development"),
            links = emptyList(),
            installOptions = listOf(option),
        )

        val report = scanMcpInstall(entry, option)
        assertTrue(report.installAllowed)
        assertTrue(report.findings.any { it.code == "MUTABLE_VERSION" })
    }

    @Test
    fun `url package sources are blocked`() {
        val option = McpCatalogInstallOption(
            id = "uvx",
            displayName = "UVX",
            kind = McpCatalogInstallKind.UVX_PACKAGE,
            transport = McpTransport.STDIO,
            command = "uvx",
            arguments = listOf("git+https://example.invalid/server"),
        )
        val entry = McpCatalogEntry(
            id = "example",
            name = "Example",
            publisher = "Example",
            description = "Example MCP server",
            source = McpCatalogSource.BUILT_IN_PRESET,
            riskLevel = McpCatalogRiskLevel.LOW,
            riskSummary = "Review the package.",
            tags = listOf("development"),
            links = emptyList(),
            installOptions = listOf(option),
        )

        val report = scanMcpInstall(entry, option)
        assertFalse(report.installAllowed)
        assertTrue(report.hasBlockingFinding)
    }

    @Test
    fun `remote credential warning does not expose secret values`() {
        val option = McpCatalogInstallOption(
            id = "http",
            displayName = "HTTP",
            kind = McpCatalogInstallKind.STREAMABLE_HTTP,
            transport = McpTransport.HTTP,
            url = "https://example.invalid/mcp",
            httpAuthMode = McpHttpAuthMode.OAUTH,
        )
        val entry = McpCatalogEntry(
            id = "remote",
            name = "Remote",
            publisher = "Example",
            description = "Remote MCP server",
            source = McpCatalogSource.BUILT_IN_PRESET,
            riskLevel = McpCatalogRiskLevel.MEDIUM,
            riskSummary = "Review the remote endpoint.",
            tags = listOf("research"),
            links = emptyList(),
            installOptions = listOf(option),
        )

        val report = scanMcpInstall(entry, option)
        assertTrue(report.findings.any { it.code == "REMOTE_CREDENTIALS" })
        assertTrue(report.warningTexts().none { it.contains("token", ignoreCase = true) })
    }
}
