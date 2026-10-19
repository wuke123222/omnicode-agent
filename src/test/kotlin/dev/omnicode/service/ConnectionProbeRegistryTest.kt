package dev.omnicode.service

import dev.omnicode.settings.DatabaseConnectionConfig
import dev.omnicode.settings.DatabaseDriver
import dev.omnicode.settings.McpServerConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionProbeRegistryTest {
    private val database = DatabaseConnectionConfig(
        id = "db", name = "Production", enabled = true,
        jdbcUrl = "jdbc:mariadb://localhost:3306/app", driver = DatabaseDriver.MARIADB,
        username = "reader", connectTimeoutSeconds = 10, queryTimeoutSeconds = 30, maxRows = 100,
    )
    private val mcp = McpServerConfig(
        id = "mcp", name = "Tools", enabled = true, command = "npx",
        arguments = listOf("-y", "server"), environmentKeys = emptySet(), workingDirectory = ".",
    )

    @Test fun `tests are not live tool claims and changed settings invalidate evidence`() {
        val registry = ConnectionProbeRegistry()
        assertFalse(registry.databasePassedRecently(database))
        assertFalse(registry.mcpPassedRecently(mcp))

        registry.recordDatabase(database, true)
        registry.recordMcp(mcp, true)
        assertTrue(registry.databasePassedRecently(database))
        assertTrue(registry.mcpPassedRecently(mcp))
        assertFalse(registry.databasePassedRecently(database.copy(jdbcUrl = "jdbc:mariadb://localhost:3306/other")))
        assertFalse(registry.mcpPassedRecently(mcp.copy(arguments = listOf("different"))))

        registry.recordDatabase(database, false)
        registry.clearMcp(mcp.id)
        assertFalse(registry.databasePassedRecently(database))
        assertFalse(registry.mcpPassedRecently(mcp))
    }
}
