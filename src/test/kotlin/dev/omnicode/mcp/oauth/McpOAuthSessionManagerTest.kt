package dev.omnicode.mcp.oauth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpOAuthSessionStore
import dev.omnicode.settings.McpOAuthStoredSession
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthSessionManagerTest {
    @Test
    fun `interactive dynamic login persists credentials and refresh preserves rotated token`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        val transport = OAuthFlowTransport()
        val store = MemoryOAuthSessionStore()
        val manager = McpOAuthSessionManager(
            discoveryClient = McpOAuthDiscoveryClient(transport),
            registrationClient = McpOAuthDynamicRegistrationClient(transport),
            tokenClient = McpOAuthTokenClient(transport, clock),
            credentialStore = store,
            clock = clock,
            challengeReader = { error("well-known metadata should avoid an authorization probe") },
        )
        val config = config()
        var approvalSeen = false

        val session = manager.login(
            config = config,
            confirm = { approval ->
                approvalSeen = approval.dynamicRegistration && approval.scopes == setOf("tools:read")
                true
            },
            openBrowser = { authorizationUri ->
                val query = query(authorizationUri)
                val callback = URI(query.getValue("redirect_uri"))
                val result = URI(
                    callback.scheme,
                    callback.userInfo,
                    callback.host,
                    callback.port,
                    callback.path,
                    "code=code-1&state=${query.getValue("state")}",
                    null,
                )
                val connection = result.toURL().openConnection() as HttpURLConnection
                assertEquals(200, connection.responseCode)
                connection.disconnect()
            },
        )

        assertTrue(approvalSeen)
        assertEquals("access-1", session.accessToken)
        assertEquals("refresh-1", session.refreshToken)
        assertEquals("dynamic-client", session.clientId)
        assertEquals(session, store.load(config.id))

        // expires_in=60 is inside the manager's refresh skew, so this call refreshes immediately.
        assertEquals("access-2", manager.accessToken(config))
        assertEquals("refresh-1", store.load(config.id)?.refreshToken)
        assertTrue(transport.forms.any { it.contains("grant_type=refresh_token") })

        assertFailsWith<McpOAuthLoginRequiredException> {
            manager.accessToken(config.copy(oauthScopes = listOf("tools:write")))
        }
        Unit
    }

    @Test
    fun `concurrent force refresh across manager instances performs one token rotation`() = runBlocking {
        val config = config("oauth-single-flight")
        val store = MemoryOAuthSessionStore().apply { save(config.id, expiredSession(config)) }
        val transport = CoordinatedRefreshTransport()
        val firstManager = refreshManager(store, transport)
        val secondManager = refreshManager(store, transport)

        val first = async(Dispatchers.IO) { firstManager.accessToken(config, forceRefresh = true) }
        assertTrue(transport.refreshStarted.await(5, TimeUnit.SECONDS))
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            secondManager.accessToken(config, forceRefresh = true)
        }
        transport.releaseRefresh.countDown()

        assertEquals(listOf("access-2", "access-2"), listOf(first.await(), second.await()))
        assertEquals(1, transport.refreshRequests.get())
        assertEquals("refresh-2", store.load(config.id)?.refreshToken)
    }

    @Test
    fun `logout invalidates an in-flight refresh before it can restore credentials`() = runBlocking {
        val config = config("oauth-logout-race")
        val store = MemoryOAuthSessionStore().apply { save(config.id, expiredSession(config)) }
        val transport = CoordinatedRefreshTransport()
        val refreshing = refreshManager(store, transport)
        val loggingOut = refreshManager(store, transport)

        supervisorScope {
            val result = async(Dispatchers.IO) { refreshing.accessToken(config, forceRefresh = true) }
            assertTrue(transport.refreshStarted.await(5, TimeUnit.SECONDS))
            loggingOut.logout(config.id)
            transport.releaseRefresh.countDown()
            assertFailsWith<McpOAuthOperationSupersededException> { result.await() }
        }
        assertNull(store.load(config.id))
    }

    @Test
    fun `invalid grant atomically clears the unusable session`() = runBlocking {
        val config = config("oauth-invalid-grant")
        val store = MemoryOAuthSessionStore().apply { save(config.id, expiredSession(config)) }
        val transport = CoordinatedRefreshTransport(errorCode = "invalid_grant", blockRefresh = false)
        val manager = refreshManager(store, transport)

        assertFailsWith<McpOAuthLoginRequiredException> {
            manager.accessToken(config, forceRefresh = true)
        }
        assertNull(store.load(config.id))
        assertEquals(1, transport.refreshRequests.get())
    }

    @Test
    fun `issuer comparison preserves trailing slash semantics before refresh`() = runBlocking {
        val config = config("oauth-exact-issuer")
        val store = MemoryOAuthSessionStore().apply {
            save(config.id, expiredSession(config, issuer = "https://auth.example.com/tenant"))
        }
        val transport = CoordinatedRefreshTransport(
            issuer = "https://auth.example.com/tenant/",
            blockRefresh = false,
        )
        val manager = refreshManager(store, transport)

        val error = assertFailsWith<McpOAuthException> {
            manager.accessToken(config, forceRefresh = true)
        }
        assertTrue(error.message.orEmpty().contains("issuer changed"))
        assertEquals(0, transport.refreshRequests.get())
        assertTrue(store.load(config.id) != null)
    }

    private fun config(id: String = "oauth-server"): McpServerConfig = McpServerConfig(
        id = id,
        name = "OAuth MCP",
        enabled = true,
        command = "",
        arguments = emptyList(),
        environmentKeys = emptySet(),
        workingDirectory = ".",
        transport = McpTransport.HTTP,
        url = "https://mcp.example.com/mcp",
        httpAuthMode = McpHttpAuthMode.OAUTH,
    )

    private fun expiredSession(
        config: McpServerConfig,
        issuer: String = "https://auth.example.com",
    ): McpOAuthStoredSession = McpOAuthStoredSession(
        configurationBinding = oauthConfigurationBinding(config),
        resource = config.url,
        issuer = issuer,
        tokenEndpoint = issuer.trimEnd('/') + "/token",
        clientId = "public-client",
        clientSecret = "",
        clientSecretExpiresAtEpochSeconds = 0L,
        tokenEndpointAuthMethod = "none",
        redirectUri = "http://127.0.0.1:49152/omnicode/oauth/callback/test",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        tokenType = "Bearer",
        scopes = listOf("tools:read"),
        expiresAtEpochMillis = 1L,
    )

    private fun refreshManager(
        store: McpOAuthSessionStore,
        transport: McpOAuthHttpTransport,
    ): McpOAuthSessionManager {
        val clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        return McpOAuthSessionManager(
            discoveryClient = McpOAuthDiscoveryClient(transport),
            registrationClient = McpOAuthDynamicRegistrationClient(transport),
            tokenClient = McpOAuthTokenClient(transport, clock),
            credentialStore = store,
            clock = clock,
            challengeReader = { error("well-known metadata should be available") },
        )
    }
}

private class MemoryOAuthSessionStore : McpOAuthSessionStore {
    private val values = ConcurrentHashMap<String, McpOAuthStoredSession>()

    override fun load(serverId: String): McpOAuthStoredSession? = values[serverId]
    override fun save(serverId: String, session: McpOAuthStoredSession) {
        values[serverId] = session
    }
    override fun clear(serverId: String) {
        values.remove(serverId)
    }
}

private class CoordinatedRefreshTransport(
    private val issuer: String = "https://auth.example.com",
    private val errorCode: String? = null,
    blockRefresh: Boolean = true,
) : McpOAuthHttpTransport {
    val refreshRequests = AtomicInteger()
    val refreshStarted = CountDownLatch(1)
    val releaseRefresh = CountDownLatch(if (blockRefresh) 1 else 0)

    override fun execute(request: McpOAuthHttpRequest): McpOAuthHttpResponse = when {
        request.method == "GET" && request.uri.host == "mcp.example.com" -> json(
            """{"resource":"https://mcp.example.com/mcp","authorization_servers":["$issuer"],"scopes_supported":["tools:read"]}""",
        )
        request.method == "GET" && request.uri.host == "auth.example.com" -> json(
            """{"issuer":"$issuer","authorization_endpoint":"https://auth.example.com/authorize","token_endpoint":"https://auth.example.com/token","code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["none"]}""",
        )
        request.method == "POST" && request.uri.path == "/token" -> {
            refreshRequests.incrementAndGet()
            refreshStarted.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS)) { "refresh test was not released" }
            if (errorCode != null) {
                json("""{"error":"$errorCode","error_description":"must not be surfaced"}""", status = 400)
            } else {
                json(
                    """{"access_token":"access-2","refresh_token":"refresh-2","token_type":"Bearer","scope":"tools:read","expires_in":3600}""",
                )
            }
        }
        else -> McpOAuthHttpResponse(404, emptyMap(), ByteArray(0))
    }

    private fun json(body: String, status: Int = 200): McpOAuthHttpResponse = McpOAuthHttpResponse(
        statusCode = status,
        headers = mapOf("Content-Type" to listOf("application/json")),
        body = body.toByteArray(StandardCharsets.UTF_8),
    )
}

private class OAuthFlowTransport : McpOAuthHttpTransport {
    val forms = mutableListOf<String>()
    private var tokenRequests = 0

    override fun execute(request: McpOAuthHttpRequest): McpOAuthHttpResponse = when {
        request.method == "GET" && request.uri.path == "/.well-known/oauth-protected-resource/mcp" -> json(
            """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"],"scopes_supported":["tools:read"]}""",
        )
        request.method == "GET" && request.uri.path == "/.well-known/oauth-authorization-server" -> json(
            """{"issuer":"https://auth.example.com","authorization_endpoint":"https://auth.example.com/authorize","token_endpoint":"https://auth.example.com/token","registration_endpoint":"https://auth.example.com/register","code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["none"]}""",
        )
        request.method == "POST" && request.uri.path == "/register" -> {
            val submitted = JsonParser.parseString(request.body!!.toString(StandardCharsets.UTF_8)).asJsonObject
            JsonObject().apply {
                addProperty("client_id", "dynamic-client")
                add("redirect_uris", submitted.getAsJsonArray("redirect_uris"))
                addProperty("token_endpoint_auth_method", "none")
            }.let { json(it.toString()) }
        }
        request.method == "POST" && request.uri.path == "/token" -> {
            forms += request.body!!.toString(StandardCharsets.UTF_8)
            tokenRequests++
            if (tokenRequests == 1) {
                json("""{"access_token":"access-1","refresh_token":"refresh-1","token_type":"Bearer","scope":"tools:read","expires_in":60}""")
            } else {
                json("""{"access_token":"access-2","token_type":"Bearer","scope":"tools:read","expires_in":3600}""")
            }
        }
        else -> McpOAuthHttpResponse(404, emptyMap(), ByteArray(0))
    }

    private fun json(body: String): McpOAuthHttpResponse = McpOAuthHttpResponse(
        statusCode = 200,
        headers = mapOf("Content-Type" to listOf("application/json")),
        body = body.toByteArray(StandardCharsets.UTF_8),
    )
}

private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { pair ->
    val (name, value) = pair.split('=', limit = 2)
    URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
}
