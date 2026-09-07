package dev.omnicode.database

import dev.omnicode.settings.DatabaseConnectionConfig
import dev.omnicode.settings.DatabaseDriver
import java.net.URI
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.Executor
import javax.net.ssl.SSLException

data class ValidatedDatabaseEndpoint(
    val jdbcUrl: String,
    val host: String,
    val port: Int?,
    val database: String,
    val driver: DatabaseDriver,
) {
    val displayName: String = buildString {
        append(driver.displayName).append(" · ").append(host)
        port?.let { append(':').append(it) }
        if (database.isNotBlank()) append('/').append(database)
    }
}

data class DatabaseProbeResult(val product: String, val version: String, val endpoint: String)

data class DatabaseQueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean,
)

/**
 * In-process JDBC boundary used by both the explicit settings probe and the approved Agent tool.
 * It supports network JDBC URLs only; file-backed or arbitrary custom drivers stay outside the
 * plugin trust boundary.
 */
object RemoteDatabaseAccess {
    fun validate(config: DatabaseConnectionConfig): ValidatedDatabaseEndpoint =
        validate(config.jdbcUrl, config.driver)

    fun validate(jdbcUrl: String, driver: DatabaseDriver): ValidatedDatabaseEndpoint {
        val rawValue = jdbcUrl.trim()
        require(rawValue.length in 1..MAX_JDBC_URL_CHARS && rawValue.none(Char::isISOControl)) {
            "数据库 URL 无效。"
        }
        val value = normalizeNetworkUrl(rawValue, driver)
        val uri = runCatching { URI(value.substring("jdbc:".length)) }
            .getOrElse { throw IllegalArgumentException("数据库 URL 无法解析。") }
        require(uri.isAbsolute && uri.host?.isNotBlank() == true && uri.userInfo == null) {
            "数据库 URL 必须包含远程主机，且不能在 URL 中携带用户名或密码。"
        }
        require(uri.fragment == null) { "数据库 URL 不能包含 fragment。" }
        val queryKeys = uri.rawQuery.orEmpty()
            .split('&')
            .asSequence()
            .map { it.substringBefore('=').lowercase() }
            .filter(String::isNotBlank)
            .toSet()
        require(queryKeys.none { it in SECRET_QUERY_KEYS }) {
            "请通过 Password Safe 保存数据库凭据，不要把用户名、密码、Token 或 API Key 放入数据库 URL。"
        }
        require(queryKeys.none { it in UNSAFE_QUERY_KEYS }) {
            "数据库 URL 不能加载自定义认证、Socket、日志或属性转换类，也不能切换为本地 Socket。"
        }
        val database = uri.path.orEmpty().trim('/').substringBefore('/').take(MAX_DATABASE_NAME_CHARS)
        require(database.isNotBlank()) { "数据库 URL 必须包含数据库名称。" }
        return ValidatedDatabaseEndpoint(
            jdbcUrl = value,
            host = requireNotNull(uri.host).take(MAX_HOST_CHARS),
            port = uri.port.takeIf { it > 0 },
            database = database,
            driver = driver,
        )
    }

    private fun normalizeNetworkUrl(value: String, driver: DatabaseDriver): String {
        val prefixes = when (driver) {
            DatabaseDriver.POSTGRESQL -> listOf(
                "jdbc:postgresql://" to "jdbc:postgresql://",
                "postgresql://" to "jdbc:postgresql://",
                "postgres://" to "jdbc:postgresql://",
            )
            DatabaseDriver.MARIADB -> listOf(
                "jdbc:mariadb://" to "jdbc:mariadb://",
                "jdbc:mysql://" to "jdbc:mysql://",
                "mariadb://" to "jdbc:mariadb://",
                "mysql://" to "jdbc:mysql://",
            )
        }
        val match = prefixes.firstOrNull { (prefix) -> value.startsWith(prefix, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "${driver.displayName} URL 必须以 ${prefixes.joinToString("、") { it.first }} 之一开头。",
            )
        return match.second + value.substring(match.first.length)
    }

    fun probe(config: DatabaseConnectionConfig, password: String): DatabaseProbeResult =
        connect(config, password, requireEnabled = false).use { connection ->
            val metadata = connection.metaData
            DatabaseProbeResult(
                product = metadata.databaseProductName.orEmpty().take(120),
                version = metadata.databaseProductVersion.orEmpty().take(120),
                endpoint = validate(config).displayName,
            )
        }

    /** MariaDB Connector/J only accepts the MySQL compatibility scheme when the URL opts in. */
    internal fun driverJdbcUrl(config: DatabaseConnectionConfig): String {
        val endpoint = validate(config)
        if (endpoint.driver != DatabaseDriver.MARIADB ||
            !endpoint.jdbcUrl.startsWith("jdbc:mysql://", ignoreCase = true)
        ) {
            return endpoint.jdbcUrl
        }
        val compatibilityFlags = endpoint.jdbcUrl.substringAfter('?', "")
            .split('&')
            .mapNotNull { parameter ->
                val key = parameter.substringBefore('=')
                parameter.substringAfter('=', "").takeIf {
                    key.equals("permitMysqlScheme", ignoreCase = true)
                }
            }
        require(compatibilityFlags.all { it.equals("true", ignoreCase = true) }) {
            "使用 jdbc:mysql:// 时 permitMysqlScheme 必须为 true。"
        }
        return if (compatibilityFlags.isNotEmpty()) endpoint.jdbcUrl else buildString {
            append(endpoint.jdbcUrl)
            append(if ('?' in endpoint.jdbcUrl) '&' else '?')
            append("permitMysqlScheme=true")
        }
    }

    fun query(config: DatabaseConnectionConfig, password: String, sql: String, requestedRows: Int): DatabaseQueryResult {
        val safeSql = validateReadOnlySql(sql)
        val maxRows = requestedRows.coerceIn(1, config.maxRows.coerceIn(1, MAX_ROWS))
        connect(config, password, requireEnabled = true).use { connection ->
            connection.autoCommit = false
            connection.isReadOnly = true
            applyReadOnlyTransaction(connection, config.driver)
            try {
                connection.createStatement().use { statement ->
                    statement.queryTimeout = config.queryTimeoutSeconds.coerceIn(1, MAX_QUERY_TIMEOUT_SECONDS)
                    statement.maxRows = maxRows + 1
                    statement.fetchSize = minOf(maxRows, 100)
                    statement.executeQuery(safeSql).use { result ->
                        val decoded = decode(result, maxRows)
                        connection.rollback()
                        return decoded
                    }
                }
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            }
        }
    }

    fun validateReadOnlySql(sql: String): String {
        val value = sql.trim().removeSuffix(";").trim()
        require(value.length in 1..MAX_SQL_CHARS && value.none(Char::isISOControl)) {
            "SQL 为空、过长或包含控制字符。"
        }
        val tokens = sqlTokens(value)
        val first = tokens.firstOrNull()?.uppercase() ?: throw IllegalArgumentException("SQL 为空。")
        require(first in READ_ONLY_PREFIXES) {
            "远程数据库工具只允许 SELECT、SHOW、DESCRIBE 或 DESC。"
        }
        require(tokens.none { it.equals("INTO", ignoreCase = true) }) {
            "为避免 SELECT INTO/OUTFILE 写入，查询中不能使用 INTO。"
        }
        return value
    }

    private fun connect(config: DatabaseConnectionConfig, password: String, requireEnabled: Boolean): Connection {
        validate(config)
        if (requireEnabled) require(config.enabled) { "数据库连接未启用。" }
        require(config.username.isNotBlank() && config.username.length <= 256) { "数据库用户名未配置。" }
        Class.forName(config.driver.className)
        val connectSeconds = config.connectTimeoutSeconds.coerceIn(2, MAX_CONNECT_TIMEOUT_SECONDS)
        val querySeconds = config.queryTimeoutSeconds.coerceIn(1, MAX_QUERY_TIMEOUT_SECONDS)
        val properties = Properties().apply {
            setProperty("user", config.username)
            setProperty("password", password)
            when (config.driver) {
                DatabaseDriver.POSTGRESQL -> {
                    setProperty("connectTimeout", connectSeconds.toString())
                    setProperty("socketTimeout", querySeconds.toString())
                    setProperty("ApplicationName", "OmniCode-read-only")
                }
                DatabaseDriver.MARIADB -> {
                    setProperty("connectTimeout", (connectSeconds * 1_000).toString())
                    setProperty("socketTimeout", (querySeconds * 1_000).toString())
                    setProperty("useReadAheadInput", "false")
                    setProperty("permitMysqlScheme", "true")
                }
            }
        }
        val connection = DriverManager.getConnection(driverJdbcUrl(config), properties)
        runCatching { connection.setNetworkTimeout(NETWORK_TIMEOUT_EXECUTOR, querySeconds * 1_000) }
        return connection
    }

    private fun applyReadOnlyTransaction(connection: Connection, driver: DatabaseDriver) {
        val command = when (driver) {
            DatabaseDriver.POSTGRESQL, DatabaseDriver.MARIADB -> "SET TRANSACTION READ ONLY"
        }
        connection.createStatement().use { statement -> statement.execute(command) }
    }

    private fun decode(result: ResultSet, maxRows: Int): DatabaseQueryResult {
        val metadata = result.metaData
        val columnCount = metadata.columnCount.coerceAtMost(MAX_COLUMNS)
        val columns = (1..columnCount).map { index ->
            metadata.getColumnLabel(index).orEmpty().ifBlank { "column_$index" }.take(MAX_COLUMN_NAME_CHARS)
        }
        val rows = mutableListOf<List<String?>>()
        while (result.next() && rows.size <= maxRows) {
            rows += (1..columnCount).map { index ->
                result.getString(index)?.take(MAX_CELL_CHARS)
            }
        }
        val truncated = rows.size > maxRows || metadata.columnCount > MAX_COLUMNS
        return DatabaseQueryResult(columns, rows.take(maxRows), truncated)
    }

    private fun sqlTokens(sql: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var index = 0
        fun flush() {
            if (token.isNotEmpty()) {
                tokens += token.toString()
                token.setLength(0)
            }
        }
        while (index < sql.length) {
            val char = sql[index]
            if (quote != null) {
                if (char == quote) {
                    if (index + 1 < sql.length && sql[index + 1] == quote) index++ else quote = null
                }
                index++
                continue
            }
            when {
                char == '\'' || char == '"' || char == '`' -> {
                    flush()
                    quote = char
                }
                char == ';' -> throw IllegalArgumentException("一次只能执行一条只读 SQL。")
                char == '-' && index + 1 < sql.length && sql[index + 1] == '-' ->
                    throw IllegalArgumentException("只读查询中不允许 SQL 注释。")
                char == '/' && index + 1 < sql.length && sql[index + 1] == '*' ->
                    throw IllegalArgumentException("只读查询中不允许 SQL 注释。")
                char.isLetterOrDigit() || char == '_' -> token.append(char)
                else -> flush()
            }
            index++
        }
        require(quote == null) { "SQL 字符串没有闭合。" }
        flush()
        return tokens
    }

    private const val MAX_JDBC_URL_CHARS = 4_096
    private const val MAX_SQL_CHARS = 32_000
    private const val MAX_HOST_CHARS = 255
    private const val MAX_DATABASE_NAME_CHARS = 255
    private const val MAX_ROWS = 1_000
    private const val MAX_COLUMNS = 100
    private const val MAX_COLUMN_NAME_CHARS = 128
    private const val MAX_CELL_CHARS = 2_000
    private const val MAX_CONNECT_TIMEOUT_SECONDS = 60
    private const val MAX_QUERY_TIMEOUT_SECONDS = 300
    private val READ_ONLY_PREFIXES = setOf("SELECT", "SHOW", "DESCRIBE", "DESC")
    private val SECRET_QUERY_KEYS = setOf(
        "user", "username", "password", "passwd", "pwd", "pass", "sslpassword",
        "token", "access_token", "accesstoken", "api_key", "apikey", "client_secret", "clientsecret",
    )
    private val UNSAFE_QUERY_KEYS = setOf(
        "socketfactory", "sslfactory", "sslpasswordcallback", "authenticationpluginclassname",
        "credentialtype", "logger", "propertiestransform", "localsocket", "pipe",
    )
    private val NETWORK_TIMEOUT_EXECUTOR = Executor { runnable ->
        Thread(runnable, "omnicode-database-timeout").apply {
            isDaemon = true
            start()
        }
    }
}

fun databaseErrorMessage(error: Throwable): String {
    val causes = generateSequence(error) { current -> current.cause.takeUnless { it === current } }
        .take(12)
        .toList()
    if (causes.any { it is UnknownHostException }) {
        return "数据库主机无法解析，请检查 DNS、VPN 或 JDBC URL。"
    }
    if (causes.any { it is ConnectException }) {
        return "数据库端口无法连接，请检查地址、防火墙、云数据库白名单和服务状态。"
    }
    if (causes.any { it is SocketTimeoutException }) {
        return "数据库连接或查询超时，请检查网络并适当提高连接/查询超时。"
    }
    if (causes.any { it is SSLException }) {
        return "数据库 TLS 握手失败，请检查证书、主机名和 JDBC URL 中的 TLS 参数。"
    }
    val sqlError = causes.filterIsInstance<SQLException>().firstOrNull()
    if (sqlError != null) {
        return when (sqlError.sqlState?.take(2)) {
            "28" -> "数据库认证失败，请重新保存用户名和密码，并检查账号是否允许远程登录。"
            "08" -> "数据库连接失败，请检查 URL、网络、TLS、端口白名单和服务状态。"
            "42" -> "SQL 语法或对象权限错误：${sqlError.message.orEmpty().take(300)}"
            else -> "数据库请求失败（SQLState ${sqlError.sqlState?.take(12) ?: "unknown"}）。"
        }
    }
    return when (error) {
        is IllegalArgumentException -> error.message.orEmpty().take(300).ifBlank { "数据库配置或查询无效。" }
        else -> "数据库请求失败：${error::class.java.simpleName.take(80)}。"
    }
}
