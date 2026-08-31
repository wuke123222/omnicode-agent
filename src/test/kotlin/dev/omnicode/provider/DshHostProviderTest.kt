package dev.omnicode.provider

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import dev.omnicode.util.Json
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class DshHostProviderTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `factory uses persistent host provider for DSH`() {
        assertTrue(ProviderFactory.create(connection()) is DshHostProvider)
    }

    @Test
    fun `origin accepts loopback only`() {
        val local = dshOrigin("http://127.0.0.1:43123")
        assertEquals("http://127.0.0.1:43123/", local.httpUri.toString())
        assertEquals("ws://127.0.0.1:43123/api/events.mux", local.muxUri.toString())

        val error = assertThrows(ProviderException::class.java) {
            dshOrigin("http://example.com:3080")
        }
        assertTrue(error.message.orEmpty().contains("仅允许本机"))
    }

    @Test
    fun `rpc client validates envelope and extracts value`() = runBlocking {
        val origin = startServer { request ->
            val rpcId = request.get("rpcId").asString
            JsonObject().apply {
                addProperty("type", "server-response")
                addProperty("rpcId", rpcId)
                add("result", JsonObject().apply {
                    addProperty("ok", true)
                    add("value", JsonObject().apply { addProperty("sessionId", "session-1") })
                })
            }
        }
        val value = DshHostClient(origin, 5).call("session.create", JsonObject())
        assertEquals("session-1", value.stringOrNull("sessionId"))
    }

    @Test
    fun `rpc client rejects mismatched response id`() = runBlocking {
        val origin = startServer {
            JsonObject().apply {
                addProperty("type", "server-response")
                addProperty("rpcId", "another-request")
                add("result", JsonObject().apply {
                    addProperty("ok", true)
                    add("value", JsonObject())
                })
            }
        }
        val error = assertThrows(ProviderException::class.java) {
            runBlocking { DshHostClient(origin, 5).call("host.describe", JsonObject()) }
        }
        assertTrue(error.message.orEmpty().contains("响应标识不匹配"))
    }

    @Test
    fun `DSH model discovery reads host catalog`() = runBlocking {
        val origin = startServer { request ->
            val rpcId = request.get("rpcId").asString
            JsonObject().apply {
                addProperty("type", "server-response")
                addProperty("rpcId", rpcId)
                add("result", JsonObject().apply {
                    addProperty("ok", true)
                    add("value", JsonObject().apply {
                        add("groups", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", "openai")
                                add("models", com.google.gson.JsonArray().apply {
                                    add(JsonObject().apply { addProperty("id", "gpt-test") })
                                    add(JsonObject().apply { addProperty("id", "gpt-test") })
                                })
                            })
                        })
                    })
                })
            }
        }
        val result = DshHostModelDiscovery.discover(
            connection(origin.httpUri.toString()),
        )
        assertEquals(listOf("openai/gpt-test"), result.models)
        assertFalse(result.discoveredRemotely)
        assertTrue(ProviderModelDiscovery.supportsModelDiscovery(ProviderProtocol.CLI_DSH))
    }

    private fun startServer(response: (JsonObject) -> JsonObject): DshOrigin {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/api") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val payload = Json.parseObject(body)
            val bytes = Json.stringify(response(payload)).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return dshOrigin("http://127.0.0.1:${http.address.port}")
    }

    private fun connection(baseUrl: String = "cli://local") = ProviderConnection(
        preset = ProviderPresets.byId("cli-dsh"),
        baseUrl = baseUrl,
        model = "default",
        apiKey = "",
        requestTimeoutSeconds = 5,
    )
}
