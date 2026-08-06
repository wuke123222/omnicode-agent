package dev.omnicode.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchConnectorCatalogTest {
    @Test
    fun `catalog includes open and authorized research sources`() {
        assertTrue(ResearchConnectorCatalog.templates.size >= 8)
        assertTrue(ResearchConnectorCatalog.templates.any { it.id == "science" && it.access == ResearchAccess.INSTITUTIONAL })
        assertTrue(ResearchConnectorCatalog.templates.any { it.id == "cnki" && it.access == ResearchAccess.USER_AUTHORIZED })
        assertEquals("crossref", ResearchConnectorCatalog.search("doi").first().id)
    }
}
