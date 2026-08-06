package dev.omnicode.mcp

/**
 * Curated research source templates. These are discovery metadata, not claims that a source
 * exposes an official MCP server. Users must provide an authorized endpoint or install a
 * separately reviewed connector; OmniCode never scrapes paywalled pages or stores credentials.
 */
enum class ResearchAccess(val label: String) {
    OPEN_API("公开 API"),
    USER_AUTHORIZED("用户授权"),
    INSTITUTIONAL("机构订阅"),
}

data class ResearchConnectorTemplate(
    val id: String,
    val name: String,
    val provider: String,
    val access: ResearchAccess,
    val capabilities: Set<String>,
    val documentationUrl: String,
    val notes: String,
) {
    init {
        require(Regex("[a-z0-9-]{2,40}").matches(id))
        require(name.length in 2..100 && provider.length in 2..100)
        require(capabilities.isNotEmpty() && capabilities.size <= 12)
        McpCatalogPolicy.requireHttpsUrl(documentationUrl, "Research documentation")
        require(notes.length <= 400)
    }
}

object ResearchConnectorCatalog {
    val templates: List<ResearchConnectorTemplate> = listOf(
        ResearchConnectorTemplate("crossref", "Crossref", "Crossref", ResearchAccess.OPEN_API, setOf("doi", "metadata", "citation"), "https://api.crossref.org/", "开放元数据 API；适合 DOI、作者和期刊元数据检索。"),
        ResearchConnectorTemplate("openalex", "OpenAlex", "OurResearch", ResearchAccess.OPEN_API, setOf("works", "authors", "institutions", "citations"), "https://docs.openalex.org/", "开放学术图谱 API；请求应遵守其速率限制。"),
        ResearchConnectorTemplate("pubmed", "PubMed / NCBI", "National Library of Medicine", ResearchAccess.OPEN_API, setOf("literature", "abstracts", "mesh"), "https://www.ncbi.nlm.nih.gov/books/NBK25501/", "E-utilities 可用于公开文献元数据；不要把受限全文当作公开内容。"),
        ResearchConnectorTemplate("arxiv", "arXiv", "Cornell University", ResearchAccess.OPEN_API, setOf("preprints", "metadata", "pdf-links"), "https://info.arxiv.org/help/api/", "公开预印本元数据与链接；PDF 下载仍受来源条款约束。"),
        ResearchConnectorTemplate("semantic-scholar", "Semantic Scholar", "Allen Institute", ResearchAccess.OPEN_API, setOf("papers", "authors", "citations"), "https://api.semanticscholar.org/api-docs/", "公开 API 需要遵守配额；API Key 由用户自行配置。"),
        ResearchConnectorTemplate("science", "Science", "AAAS", ResearchAccess.INSTITUTIONAL, setOf("journal-search", "metadata", "citations"), "https://www.science.org/content/page/submission-guidelines", "通常需要机构订阅或用户授权；只连接用户提供的合规 API/MCP。"),
        ResearchConnectorTemplate("nature", "Nature", "Springer Nature", ResearchAccess.INSTITUTIONAL, setOf("journal-search", "metadata", "citations"), "https://www.springernature.com/gp/researchers", "通常需要机构订阅或 API 授权；不提供绕过付费墙的抓取。"),
        ResearchConnectorTemplate("cnki", "中国知网（CNKI）", "同方知网", ResearchAccess.USER_AUTHORIZED, setOf("中文文献", "主题检索", "引用信息"), "https://www.cnki.net/", "必须使用用户或机构授权的官方接口/自建 MCP；插件不代存账号密码、不绕过验证码或访问控制。"),
    )

    fun search(text: String = ""): List<ResearchConnectorTemplate> {
        val query = text.trim().lowercase()
        if (query.isBlank()) return templates
        return templates.filter { source ->
            listOf(source.id, source.name, source.provider, source.capabilities.joinToString(" ")).any { it.lowercase().contains(query) }
        }
    }
}
