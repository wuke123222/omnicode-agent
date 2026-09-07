package dev.omnicode.database

import dev.omnicode.settings.DatabaseConnectionConfig
import dev.omnicode.settings.DatabaseDriver
import org.mariadb.jdbc.Driver
import java.net.UnknownHostException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteDatabaseAccessTest {
    @Test
    fun `supported remote drivers are bundled`() {
        assertEquals("org.postgresql.Driver", Class.forName(DatabaseDriver.POSTGRESQL.className).name)
        assertEquals("org.mariadb.jdbc.Driver", Class.forName(DatabaseDriver.MARIADB.className).name)
    }

    @Test
    fun `accepts remote postgres mariadb and mysql endpoints`() {
        val postgres = RemoteDatabaseAccess.validate(
            "jdbc:postgresql://db.example.com:5432/research?sslmode=require",
            DatabaseDriver.POSTGRESQL,
        )
        val maria = RemoteDatabaseAccess.validate(
            "jdbc:mariadb://10.0.0.8:3306/lab?sslMode=verify-full",
            DatabaseDriver.MARIADB,
        )
        val mysql = RemoteDatabaseAccess.validate(
            "jdbc:mysql://mysql.example.com:3306/app",
            DatabaseDriver.MARIADB,
        )
        val friendlyPostgres = RemoteDatabaseAccess.validate(
            "postgresql://db.example.com:5432/research?sslmode=require",
            DatabaseDriver.POSTGRESQL,
        )
        val friendlyMysql = RemoteDatabaseAccess.validate(
            "mysql://mysql.example.com:3306/app",
            DatabaseDriver.MARIADB,
        )
        val postgresWithoutDatabase = RemoteDatabaseAccess.validate(
            "postgresql://db.example.com:5432?sslmode=require",
            DatabaseDriver.POSTGRESQL,
        )
        val mariaWithoutDatabase = RemoteDatabaseAccess.validate(
            "mariadb://10.0.0.8:3306",
            DatabaseDriver.MARIADB,
        )

        assertEquals("db.example.com", postgres.host)
        assertEquals("research", postgres.database)
        assertEquals("lab", maria.database)
        assertEquals("mysql.example.com", mysql.host)
        assertEquals("jdbc:postgresql://db.example.com:5432/research?sslmode=require", friendlyPostgres.jdbcUrl)
        assertEquals("jdbc:mysql://mysql.example.com:3306/app", friendlyMysql.jdbcUrl)
        assertEquals("", postgresWithoutDatabase.database)
        assertEquals("jdbc:postgresql://db.example.com:5432/?sslmode=require", postgresWithoutDatabase.jdbcUrl)
        assertEquals("", mariaWithoutDatabase.database)
        assertEquals("jdbc:mariadb://10.0.0.8:3306", mariaWithoutDatabase.jdbcUrl)
        assertTrue(org.postgresql.Driver().acceptsURL(postgresWithoutDatabase.jdbcUrl))
        assertTrue(Driver().acceptsURL(mariaWithoutDatabase.jdbcUrl))
        assertTrue(
            Driver().acceptsURL(RemoteDatabaseAccess.driverJdbcUrl(
                DatabaseConnectionConfig(
                    id = "mysql", name = "MySQL", enabled = true,
                    jdbcUrl = mysql.jdbcUrl, driver = DatabaseDriver.MARIADB, username = "reader",
                    connectTimeoutSeconds = 10, queryTimeoutSeconds = 30, maxRows = 100,
                ),
            )),
        )
        assertFailsWith<IllegalArgumentException> {
            RemoteDatabaseAccess.driverJdbcUrl(
                DatabaseConnectionConfig(
                    id = "mysql", name = "MySQL", enabled = true,
                    jdbcUrl = "mysql://mysql.example.com/app?permitMysqlScheme=false",
                    driver = DatabaseDriver.MARIADB, username = "reader",
                    connectTimeoutSeconds = 10, queryTimeoutSeconds = 30, maxRows = 100,
                ),
            )
        }
    }

    @Test
    fun `rejects local files and credentials in url`() {
        assertFailsWith<IllegalArgumentException> {
            RemoteDatabaseAccess.validate("jdbc:postgresql:./data", DatabaseDriver.POSTGRESQL)
        }
        val error = assertFailsWith<IllegalArgumentException> {
            RemoteDatabaseAccess.validate(
                "jdbc:postgresql://user:secret@db.example.com/app",
                DatabaseDriver.POSTGRESQL,
            )
        }
        assertTrue(error.message.orEmpty().contains("用户名或密码"))
        assertFailsWith<IllegalArgumentException> {
            RemoteDatabaseAccess.validate(
                "jdbc:mariadb://db.example.com/app?password=secret",
                DatabaseDriver.MARIADB,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteDatabaseAccess.validate(
                "jdbc:postgresql://db.example.com/app?access_token=secret",
                DatabaseDriver.POSTGRESQL,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteDatabaseAccess.validate(
                "postgresql://db.example.com/app?socketFactory=project.UntrustedFactory",
                DatabaseDriver.POSTGRESQL,
            )
        }
    }

    @Test
    fun `read only validator permits queries and rejects mutations comments and batches`() {
        assertEquals("SELECT id, name FROM projects LIMIT 10", RemoteDatabaseAccess.validateReadOnlySql(
            " SELECT id, name FROM projects LIMIT 10; ",
        ))
        assertEquals("SHOW TABLES", RemoteDatabaseAccess.validateReadOnlySql("SHOW TABLES"))
        assertFailsWith<IllegalArgumentException> { RemoteDatabaseAccess.validateReadOnlySql("DELETE FROM projects") }
        assertFailsWith<IllegalArgumentException> { RemoteDatabaseAccess.validateReadOnlySql("SELECT * INTO backup FROM projects") }
        assertFailsWith<IllegalArgumentException> { RemoteDatabaseAccess.validateReadOnlySql("SELECT 1; SELECT 2") }
        assertFailsWith<IllegalArgumentException> { RemoteDatabaseAccess.validateReadOnlySql("SELECT 1 -- hidden") }
    }

    @Test
    fun `keywords inside string literals do not turn a safe select into mutation`() {
        assertEquals(
            "SELECT 'INTO; -- DELETE' AS sample",
            RemoteDatabaseAccess.validateReadOnlySql("SELECT 'INTO; -- DELETE' AS sample"),
        )
    }

    @Test
    fun `connection errors are classified through jdbc wrapper causes`() {
        val wrappedDns = SQLException("connection failed", "08001", UnknownHostException("private.invalid"))
        assertTrue(databaseErrorMessage(wrappedDns).contains("无法解析"))
        assertTrue(databaseErrorMessage(SQLException("bad password", "28000")).contains("认证失败"))
    }
}
