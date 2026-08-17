package dev.omnicode.mcp

/** A deterministic pre-install review; it never downloads or executes an MCP package. */
enum class McpSecurityFindingSeverity { INFO, WARNING, BLOCKING }

data class McpSecurityFinding(
    val code: String,
    val severity: McpSecurityFindingSeverity,
    val message: String,
) {
    init {
        require(code.matches(Regex("[A-Z0-9._-]{2,64}")))
        require(message.isNotBlank() && message.length <= 480)
    }
}

data class McpSecurityReport(
    val entryId: String,
    val optionId: String,
    val compatible: Boolean,
    val signedProvenance: Boolean,
    val findings: List<McpSecurityFinding>,
) {
    val hasBlockingFinding: Boolean
        get() = findings.any { it.severity == McpSecurityFindingSeverity.BLOCKING }

    val installAllowed: Boolean
        get() = compatible && !hasBlockingFinding
}

/**
 * Review metadata is intentionally conservative.  Registry publication is not treated as a
 * signature, and mutable package tags are never silently promoted to a trusted install.
 */
internal fun scanMcpInstall(entry: McpCatalogEntry, option: McpCatalogInstallOption): McpSecurityReport {
    val findings = ArrayList<McpSecurityFinding>()
    var compatible = true
    // The plugin's own signature protects the catalog binary, but that is not a publisher
    // signature for the server package itself. Keep this false until a registry signature can be
    // verified against a pinned publisher key.
    val signed = false

    if (entry.source == McpCatalogSource.MCP_REGISTRY) {
        findings += McpSecurityFinding(
            "REGISTRY_UNVERIFIED",
            McpSecurityFindingSeverity.WARNING,
            "公开 Registry 只证明元数据来源；发布者、签名和运行代码仍未由 OmniCode 审核。",
        )
    }
    if (!signed) {
        findings += McpSecurityFinding(
            "NO_SIGNED_PROVENANCE",
            McpSecurityFindingSeverity.WARNING,
            "未提供可在本地验证的发布签名；安装前请核对仓库、版本和供应商。",
        )
    }
    when (option.kind) {
        McpCatalogInstallKind.NPX_PACKAGE,
        McpCatalogInstallKind.UVX_PACKAGE,
        -> {
            val packageToken = option.arguments.firstOrNull().orEmpty()
            if (packageToken.endsWith("@latest", ignoreCase = true) || packageToken == "latest") {
                findings += McpSecurityFinding(
                    "MUTABLE_VERSION",
                    McpSecurityFindingSeverity.WARNING,
                    "安装声明使用可变 latest 标签；建议改为经过审阅的固定版本后再启用。",
                )
            }
            if (packageToken.startsWith("git+", ignoreCase = true) || packageToken.contains("://")) {
                compatible = false
                findings += McpSecurityFinding(
                    "UNSUPPORTED_PACKAGE_SOURCE",
                    McpSecurityFindingSeverity.BLOCKING,
                    "不允许从 Git 或任意 URL 动态安装 MCP 包；请使用固定的包注册表版本。",
                )
            }
        }
        McpCatalogInstallKind.LOCAL_EXECUTABLE -> findings += McpSecurityFinding(
            "AMBIENT_EXECUTABLE",
            McpSecurityFindingSeverity.WARNING,
            "本地可执行文件会使用设备上的 PATH/凭据；首次运行必须重新审批并检查其绝对来源。",
        )
        McpCatalogInstallKind.STREAMABLE_HTTP -> if (option.httpAuthMode != dev.omnicode.settings.McpHttpAuthMode.NONE) {
            findings += McpSecurityFinding(
                "REMOTE_CREDENTIALS",
                McpSecurityFindingSeverity.WARNING,
                "远程 MCP 需要 OAuth/Bearer 凭据；只会保存到 Password Safe，首次连接仍需确认。",
            )
        }
    }
    if (entry.riskLevel == McpCatalogRiskLevel.HIGH) {
        findings += McpSecurityFinding(
            "HIGH_CAPABILITY",
            McpSecurityFindingSeverity.WARNING,
            "该服务器被标为高风险能力，请在启用前审阅工具、网络和文件范围。",
        )
    }
    return McpSecurityReport(entry.id, option.id, compatible, signed, findings)
}

internal fun McpSecurityReport.warningTexts(): List<String> = findings
    .map { "[${it.severity.name}] ${it.message}" }
    .take(8)
