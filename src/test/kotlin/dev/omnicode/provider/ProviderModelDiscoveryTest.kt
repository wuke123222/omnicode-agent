package dev.omnicode.provider

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderModelDiscoveryTest {
    @Test
    fun `OpenCode Zen discovers Big Pickle and free models through its shared catalog`() = runBlocking {
        val client = RecordingClient(
            """{
                "object": "list",
                "data": [
                    {"id": "big-pickle"},
                    {"id": "mimo-v2.5-free"},
                    {"id": "north-mini-code-free"},
                    {"id": "nemotron-3-ultra-free"},
                    {"id": "deepseek-v4-flash-free"}
                ]
            }""".trimIndent(),
        )
        val connection = connection(
            protocol = ProviderProtocol.OPENCODE_ZEN,
            baseUrl = "https://opencode.ai/zen/v1/",
            model = "big-pickle",
            defaultModel = "big-pickle",
            apiKey = "zen-saved-secret",
        )

        val result = ProviderModelDiscovery.discover(connection, client)

        assertTrue(ProviderModelDiscovery.supportsRemoteDiscovery(ProviderProtocol.OPENCODE_ZEN))
        assertTrue(result.discoveredRemotely)
        assertEquals(
            listOf(
                "big-pickle",
                "deepseek-v4-flash-free",
                "mimo-v2.5-free",
                "nemotron-3-ultra-free",
                "north-mini-code-free",
            ),
            result.models,
        )
        val request = client.requests.single()
        assertEquals("https://opencode.ai/zen/v1/models", request.url)
        assertEquals("Bearer zen-saved-secret", request.headers["Authorization"])
        assertTrue(request.sensitiveValues.contains("zen-saved-secret"))
        assertFalse(request.url.contains("zen-saved-secret"))
    }

    @Test
    fun `OpenAI compatible discovery uses models endpoint and bearer credential`() = runBlocking {
        val client = RecordingClient(
            """{
                "data": [
                    {"id": "z-model"},
                    {"id": "a-model"},
                    {"id": "a-model"}
                ]
            }""".trimIndent(),
        )
        val connection = connection(
            protocol = ProviderProtocol.OPENAI_CHAT,
            baseUrl = "https://example.test/v1/",
            apiKey = "sk-saved-secret",
        )

        val result = ProviderModelDiscovery.discover(connection, client)

        assertTrue(result.discoveredRemotely)
        assertEquals(listOf("a-model", "z-model"), result.models)
        assertEquals("https://example.test/v1/models", client.requests.single().url)
        assertEquals("Bearer sk-saved-secret", client.requests.single().headers["Authorization"])
        assertFalse(client.requests.single().url.contains("sk-saved-secret"))
        assertTrue(client.requests.single().sensitiveValues.contains("sk-saved-secret"))
    }

    @Test
    fun `Gemini discovery follows pagination and keeps generation models only`() = runBlocking {
        val client = RecordingClient(
            """{
                "models": [
                    {
                        "name": "models/gemini-embed-only",
                        "baseModelId": "gemini-embed-only",
                        "supportedGenerationMethods": ["embedContent"]
                    },
                    {
                        "name": "models/gemini-flash-001",
                        "baseModelId": "gemini-flash",
                        "supportedGenerationMethods": ["generateContent"]
                    }
                ],
                "nextPageToken": "next page"
            }""".trimIndent(),
            """{
                "models": [
                    {
                        "name": "models/gemini-pro-002",
                        "supportedGenerationMethods": ["GENERATECONTENT"]
                    }
                ]
            }""".trimIndent(),
        )
        val connection = connection(
            protocol = ProviderProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "saved-gemini-key",
        )

        val result = ProviderModelDiscovery.discover(connection, client)

        assertEquals(listOf("gemini-flash", "gemini-pro-002"), result.models)
        assertEquals(2, client.requests.size)
        assertTrue(client.requests[0].url.endsWith("/models?pageSize=1000"))
        assertTrue(client.requests[1].url.endsWith("/models?pageSize=1000&pageToken=next+page"))
        assertEquals("saved-gemini-key", client.requests[0].headers["x-goog-api-key"])
        assertFalse(client.requests.any { it.url.contains("saved-gemini-key") })
    }

    @Test
    fun `Gemini discovery rejects a repeating page token`() {
        val page = """{"models": [], "nextPageToken": "same-token"}"""
        val client = RecordingClient(page, page)

        val error = assertFailsWith<ProviderException> {
            runBlocking {
                ProviderModelDiscovery.discover(
                    connection(ProviderProtocol.GEMINI),
                    client,
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("repeating model-list page token"))
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `Anthropic discovery uses saved headers and follows last id pagination`() = runBlocking {
        val client = RecordingClient(
            """{
                "data": [
                    {"id": "claude-z"},
                    {"id": "claude-a"}
                ],
                "has_more": true,
                "last_id": "opaque cursor/+"
            }""".trimIndent(),
            """{
                "data": [
                    {"id": "claude-b"},
                    {"id": "claude-a"}
                ],
                "has_more": false,
                "last_id": "claude-b"
            }""".trimIndent(),
        )
        val connection = connection(
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            baseUrl = "https://api.anthropic.test/v1/",
            apiKey = "sk-ant-saved-secret",
            apiVersion = "2024-07-15",
        )

        val result = ProviderModelDiscovery.discover(connection, client)

        assertTrue(ProviderModelDiscovery.supportsRemoteDiscovery(ProviderProtocol.ANTHROPIC_MESSAGES))
        assertTrue(result.discoveredRemotely)
        assertEquals(listOf("claude-a", "claude-b", "claude-z"), result.models)
        assertEquals(2, client.requests.size)
        assertEquals("https://api.anthropic.test/v1/models?limit=1000", client.requests[0].url)
        assertEquals(
            "https://api.anthropic.test/v1/models?limit=1000&after_id=opaque+cursor%2F%2B",
            client.requests[1].url,
        )
        client.requests.forEach { request ->
            assertEquals("sk-ant-saved-secret", request.headers["x-api-key"])
            assertEquals("2024-07-15", request.headers["anthropic-version"])
            assertTrue(request.sensitiveValues.contains("sk-ant-saved-secret"))
            assertFalse(request.url.contains("sk-ant-saved-secret"))
        }
    }

    @Test
    fun `Anthropic discovery rejects a repeating last id cursor`() {
        val page = """{"data": [{"id": "claude-a"}], "has_more": true, "last_id": "same-id"}"""
        val client = RecordingClient(page, page)

        val error = assertFailsWith<ProviderException> {
            runBlocking {
                ProviderModelDiscovery.discover(
                    connection(ProviderProtocol.ANTHROPIC_MESSAGES),
                    client,
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("repeating model-list last_id"))
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `Anthropic discovery limits pagination`() {
        val client = RecordingClient(
            *Array(20) { index ->
                """{"data": [{"id": "claude-$index"}], "has_more": true, "last_id": "cursor-$index"}"""
            },
        )

        val error = assertFailsWith<ProviderException> {
            runBlocking {
                ProviderModelDiscovery.discover(
                    connection(ProviderProtocol.ANTHROPIC_MESSAGES),
                    client,
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("exceeded 20 pages"))
        assertEquals(20, client.requests.size)
    }

    @Test
    fun `unsupported provider falls back without making a request`() = runBlocking {
        val client = RecordingClient()
        val connection = connection(
            protocol = ProviderProtocol.BEDROCK_CONVERSE,
            model = "configured-model",
            defaultModel = "default-model",
        )

        val result = ProviderModelDiscovery.discover(connection, client)

        assertFalse(result.discoveredRemotely)
        assertEquals(setOf("configured-model", "default-model"), result.models.toSet())
        assertTrue(result.status.contains("configured/default"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `Anthropic unsupported model endpoint falls back to configured models`() = runBlocking {
        val client = object : ModelDiscoveryHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutSeconds: Long,
                sensitiveValues: Collection<String>,
            ): HttpResult = throw ProviderException("Model API returned HTTP 404", statusCode = 404)
        }

        val result = ProviderModelDiscovery.discover(
            connection(
                protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
                model = "configured-claude",
                defaultModel = "default-claude",
            ),
            client,
        )

        assertFalse(result.discoveredRemotely)
        assertEquals(listOf("configured-claude", "default-claude"), result.models)
        assertTrue(result.status.contains("HTTP 404"))
    }

    @Test
    fun `empty compatible response clearly falls back to configured model`() = runBlocking {
        val client = RecordingClient("""{"data": []}""")
        val connection = connection(
            protocol = ProviderProtocol.OPENAI_RESPONSES,
            model = "configured-model",
            defaultModel = "default-model",
        )

        val result = ProviderModelDiscovery.discover(connection, client)

        assertFalse(result.discoveredRemotely)
        assertEquals(setOf("configured-model", "default-model"), result.models.toSet())
        assertTrue(result.status.contains("no selectable models"))
    }

    @Test
    fun `compatible endpoint without model listing falls back on unsupported status`() = runBlocking {
        val client = object : ModelDiscoveryHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutSeconds: Long,
                sensitiveValues: Collection<String>,
            ): HttpResult = throw ProviderException("Model API returned HTTP 404", statusCode = 404)
        }

        val result = ProviderModelDiscovery.discover(
            connection(
                protocol = ProviderProtocol.OPENAI_CHAT,
                model = "configured-model",
                defaultModel = "default-model",
            ),
            client,
        )

        assertFalse(result.discoveredRemotely)
        assertEquals(listOf("configured-model", "default-model"), result.models)
        assertTrue(result.status.contains("HTTP 404"))
    }

    @Test
    fun `invalid response reports a safe parse error`() {
        val secret = "sk-never-disclose-this"
        val error = assertFailsWith<ProviderException> {
            runBlocking {
                ProviderModelDiscovery.discover(
                    connection(ProviderProtocol.OPENAI_CHAT, apiKey = secret),
                    RecordingClient("not-json-$secret"),
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("invalid model-list response"))
        assertFalse(error.message.orEmpty().contains(secret))
        assertFalse(error.responseBody.orEmpty().contains(secret))
    }

    private fun connection(
        protocol: ProviderProtocol,
        baseUrl: String = "https://example.test/v1",
        model: String = "configured-model",
        defaultModel: String = "default-model",
        apiKey: String = "saved-key",
        apiVersion: String = "2025-04-01-preview",
    ): ProviderConnection = ProviderConnection(
        preset = ProviderPreset(
            id = "test-${protocol.name.lowercase()}",
            displayName = "Test Provider",
            protocol = protocol,
            defaultBaseUrl = baseUrl,
            defaultModel = defaultModel,
        ),
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        apiVersion = apiVersion,
        requestTimeoutSeconds = 7,
    )

    private class RecordingClient(
        vararg bodies: String,
    ) : ModelDiscoveryHttpClient {
        val requests = mutableListOf<Request>()
        private val responses = ArrayDeque(bodies.toList())

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
            sensitiveValues: Collection<String>,
        ): HttpResult {
            requests += Request(url, headers, timeoutSeconds, sensitiveValues.toList())
            check(responses.isNotEmpty()) { "Unexpected model discovery request: $url" }
            return HttpResult(200, responses.removeFirst(), emptyMap())
        }
    }

    private data class Request(
        val url: String,
        val headers: Map<String, String>,
        val timeoutSeconds: Long,
        val sensitiveValues: List<String>,
    )
}
