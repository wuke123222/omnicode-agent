package dev.omnicode.mcp

import com.google.gson.JsonObject
import dev.omnicode.settings.McpHttpAuthMode
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolConnectorTest {
    @Test
    fun `connector overlaps independent servers but preserves configured tool order`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val connector = McpToolConnector(
            launcher = McpProcessLauncher { error("stdio is not used") },
            httpConnector = McpHttpClientConnector { config ->
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                try {
                    withTimeout(1_000) { bothStarted.await() }
                    TestClient(config, listOf(descriptor(config.id)))
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val bundle = connector.connect(listOf(httpConfig("first", "Server One"), httpConfig("second", "Server Two")))
        try {
            assertEquals(2, peak.get())
            assertTrue(bundle.errors.isEmpty())
            assertEquals(
                listOf("mcp__server_one__first", "mcp__server_two__second"),
                bundle.tools.map { it.name },
            )
        } finally {
            bundle.close()
        }
    }

    @Test
    fun `cancelling discovery closes clients that already connected`() = runBlocking {
        val firstClient = TestClient(httpConfig("first", "First"), listOf(descriptor("ready")))
        val firstConnected = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val connector = McpToolConnector(
            launcher = McpProcessLauncher { error("stdio is not used") },
            httpConnector = McpHttpClientConnector { config ->
                when (config.id) {
                    "first" -> firstClient.also { firstConnected.complete(Unit) }
                    else -> {
                        secondStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            },
        )
        val connection = async {
            connector.connect(listOf(httpConfig("first", "First"), httpConfig("second", "Second")))
        }

        withTimeout(1_000) {
            firstConnected.await()
            secondStarted.await()
        }
        connection.cancelAndJoin()

        assertTrue(firstClient.closed.get(), "a cancelled discovery must not leak an already connected client")
    }

    private fun httpConfig(id: String, name: String): McpServerConfig = McpServerConfig(
        id = id,
        name = name,
        enabled = true,
        command = "",
        arguments = emptyList(),
        environmentKeys = emptySet(),
        workingDirectory = ".",
        transport = McpTransport.HTTP,
        url = "https://example.test/$id",
        httpAuthMode = McpHttpAuthMode.NONE,
    )

    private fun descriptor(name: String): McpToolDescriptor = McpToolDescriptor(
        name = name,
        description = "test tool",
        inputSchema = JsonObject().apply { addProperty("type", "object") },
    )

    private class TestClient(
        override val config: McpServerConfig,
        private val descriptors: List<McpToolDescriptor>,
    ) : McpClient {
        val closed = AtomicBoolean(false)

        override suspend fun listTools(): List<McpToolDescriptor> = descriptors

        override suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult =
            McpToolCallResult("unused", false)

        override fun close() {
            closed.set(true)
        }
    }
}
