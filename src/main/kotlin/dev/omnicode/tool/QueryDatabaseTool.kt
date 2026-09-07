package dev.omnicode.tool

import com.google.gson.JsonObject
import dev.omnicode.database.RemoteDatabaseAccess
import dev.omnicode.database.databaseErrorMessage
import dev.omnicode.settings.DatabaseCredentialStore
import dev.omnicode.settings.OmniCodePlatformSettingsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.time.Duration

/** Approved, bounded, read-only access to explicitly configured network databases. */
class QueryDatabaseTool : AgentTool {
    override val name = "query_database"
    override val description =
        "Run one approved read-only SELECT/SHOW/DESCRIBE query against a user-configured remote PostgreSQL or MySQL/MariaDB connection. Returns bounded tabular text."
    override val dangerous = true
    override val effect = ToolEffect.EXTERNAL
    override val executionTimeout: Duration = Duration.ofMinutes(6)
    override val inputSchema: JsonObject = objectSchema(required = listOf("sql")) {
        stringProperty("connection_id", "Optional configured connection id or name. Omit when exactly one database is enabled.")
        stringProperty("sql", "One read-only SELECT, SHOW, DESCRIBE or DESC statement. No comments or SELECT INTO.")
        integerProperty("max_rows", "Maximum rows to return, capped by the saved connection policy.", 100, 1, 1_000)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
        val requestedConnection = arguments.string("connection_id").trim()
        require(requestedConnection.length <= 128 && requestedConnection.none(Char::isISOControl)) {
            "Invalid database connection id or name"
        }
        val sql = RemoteDatabaseAccess.validateReadOnlySql(arguments.string("sql"))
        val enabledConnections = OmniCodePlatformSettingsService.getInstance().snapshot().databaseConnections
            .filter { it.enabled }
        val config = when {
            requestedConnection.isNotBlank() -> enabledConnections.firstOrNull {
                it.id == requestedConnection || it.name.equals(requestedConnection, ignoreCase = true)
            }
            enabledConnections.size == 1 -> enabledConnections.single()
            else -> null
        } ?: return ToolExecutionResult(
            if (enabledConnections.isEmpty()) {
                "没有已启用的数据库连接。请让用户在设置 → 数据库中保存 URL、凭据并启用连接。"
            } else {
                "当前启用了多个数据库连接。为避免向模型暴露其他项目的连接目录，请让用户明确提供设置页中的连接名称或 ID。"
            },
            isError = true,
        )
        val endpoint = runCatching { RemoteDatabaseAccess.validate(config) }
            .getOrElse { return ToolExecutionResult(it.message.orEmpty(), isError = true) }
        val approved = context.approvalGate.approve(
            ApprovalRequest(
                toolName = name,
                title = "查询 ${config.name}",
                details = "Endpoint: ${endpoint.displayName}\nRows: ${arguments.int("max_rows", 100).coerceIn(1, config.maxRows)}\n\n$sql",
                risk = "该操作会把只读 SQL 发送到外部数据库。连接强制只读事务，但数据库函数仍可能由服务端执行；请核对 SQL 和目标环境。",
            ),
        )
        if (!approved) return ToolExecutionResult("Database query was not approved.", isError = true)
        return runInterruptible(Dispatchers.IO) {
            runCatching {
                val password = DatabaseCredentialStore.getInstance().load(config.id, config.jdbcUrl)
                require(password.isNotBlank()) { "数据库密码未保存。" }
                val result = RemoteDatabaseAccess.query(
                    config,
                    password,
                    sql,
                    arguments.int("max_rows", 100),
                )
                ToolExecutionResult(render(result.columns, result.rows, result.truncated, endpoint.displayName))
            }.getOrElse { ToolExecutionResult(databaseErrorMessage(it), isError = true) }
        }
    }

    private fun render(columns: List<String>, rows: List<List<String?>>, truncated: Boolean, endpoint: String): String {
        if (columns.isEmpty()) return "Connected to $endpoint. Query returned no columns."
        fun cell(value: String?): String = (value ?: "NULL")
            .replace("|", "\\|")
            .replace("\r", " ")
            .replace("\n", " ↵ ")
        return buildString {
            append("Database: ").append(endpoint).append('\n')
            append(columns.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) }).append('\n')
            append(columns.joinToString(" | ", prefix = "| ", postfix = " |") { "---" }).append('\n')
            rows.forEach { row ->
                append(columns.indices.joinToString(" | ", prefix = "| ", postfix = " |") { index -> cell(row.getOrNull(index)) })
                    .append('\n')
            }
            append("Rows: ").append(rows.size)
            if (truncated) append(" (truncated by configured safety limit)")
        }.take(64_000)
    }
}
