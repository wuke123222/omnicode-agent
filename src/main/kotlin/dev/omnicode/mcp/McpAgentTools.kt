package dev.omnicode.mcp

import com.google.gson.JsonObject
import dev.omnicode.settings.McpServerConfig
import dev.omnicode.settings.McpTransport
import dev.omnicode.tool.AgentTool
import dev.omnicode.tool.ApprovalRequest
import dev.omnicode.tool.ToolExecutionContext
import dev.omnicode.tool.ToolExecutionResult
import dev.omnicode.tool.ToolEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

data class McpConnectionError(
    val serverName: String,
    val message: String,
)

class McpToolBundle(
    val tools: List<AgentTool>,
    val errors: List<McpConnectionError>,
    private val clients: List<McpClient>,
) : Closeable {
    override fun close() {
        clients.forEach { runCatching(it::close) }
    }

    /** HTTP session shutdown may block briefly, so task finalization closes independent clients together. */
    suspend fun closeConcurrently() {
        withContext(NonCancellable) {
            // Never let a provider/MCP shutdown hang the agent cancellation path indefinitely.
            withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { closeClientsConcurrently(clients) }
        }
    }
}

class McpToolConnector(
    private val launcher: McpProcessLauncher,
    private val httpConnector: McpHttpClientConnector = McpHttpClientConnector {
        throw McpProtocolException("MCP Streamable HTTP connector is not configured")
    },
) {
    suspend fun connect(configs: List<McpServerConfig>): McpToolBundle {
        val enabled = configs.filter(::isConnectable)
        if (enabled.isEmpty()) return McpToolBundle(emptyList(), emptyList(), emptyList())

        val openedClients = ConcurrentLinkedQueue<McpClient>()
        val connected = try {
            coroutineScope {
                val semaphore = Semaphore(minOf(DEFAULT_MAX_PARALLEL_CONNECTIONS, enabled.size))
                enabled.map { config ->
                    async {
                        semaphore.withPermit { connectServer(config, openedClients) }
                    }
                }.awaitAll()
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                closeClientsConcurrently(openedClients.toList())
            }
            throw error
        }

        val clients = mutableListOf<McpClient>()
        val tools = mutableListOf<AgentTool>()
        val toolNames = linkedSetOf<String>()
        val errors = mutableListOf<McpConnectionError>()
        connected.forEach { server ->
            server.error?.let {
                errors += McpConnectionError(server.config.name, it)
                return@forEach
            }
            val client = requireNotNull(server.client)
            clients += client
            server.tools.forEach { descriptor ->
                val tool = McpAgentTool(server.config, client, descriptor)
                if (toolNames.add(tool.name)) {
                    tools += tool
                } else {
                    errors += McpConnectionError(
                        server.config.name,
                        "MCP tool name collision after normalization: '${tool.name}'. The later tool was skipped.",
                    )
                }
            }
        }
        // There is no suspension between acquisition ownership transfer and return. Cancellation
        // during acquisition is handled above; cancellation after return is handled by the
        // caller's surrounding finally block.
        return McpToolBundle(tools, errors, clients)
    }

    private suspend fun connectServer(
        config: McpServerConfig,
        openedClients: ConcurrentLinkedQueue<McpClient>,
    ): ConnectedMcpServer {
        var client: McpClient? = null
        return try {
            val connected = when (config.transport) {
                McpTransport.STDIO -> McpStdioClient.connect(config, launcher)
                McpTransport.HTTP -> httpConnector.connect(config)
            }
            client = connected
            openedClients += connected
            ConnectedMcpServer(config, connected, connected.listTools(), null)
        } catch (cancelled: CancellationException) {
            client?.let { connected ->
                openedClients.remove(connected)
                closeClientsConcurrently(listOf(connected))
            }
            throw cancelled
        } catch (error: Throwable) {
            client?.let { connected ->
                openedClients.remove(connected)
                closeClientsConcurrently(listOf(connected))
            }
            ConnectedMcpServer(config, null, emptyList(), safeMessage(error))
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.lineSequence()?.firstOrNull()?.take(300) ?: error::class.java.simpleName

    private fun isConnectable(config: McpServerConfig): Boolean = config.enabled && when (config.transport) {
        McpTransport.STDIO -> config.command.isNotBlank()
        McpTransport.HTTP -> config.url.isNotBlank()
    }

    private data class ConnectedMcpServer(
        val config: McpServerConfig,
        val client: McpClient?,
        val tools: List<McpToolDescriptor>,
        val error: String?,
    )

    private companion object {
        const val DEFAULT_MAX_PARALLEL_CONNECTIONS = 4
    }
}

private suspend fun closeClientsConcurrently(clients: List<McpClient>) {
    if (clients.isEmpty()) return
    withContext(NonCancellable) {
        coroutineScope {
            val semaphore = Semaphore(minOf(clients.size, 4))
            clients.map { client ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { runCatching(client::close) }
                }
            }.awaitAll()
        }
    }
}

private const val CLOSE_TIMEOUT_MILLIS = 3_000L

private class McpAgentTool(
    private val server: McpServerConfig,
    private val client: McpClient,
    private val descriptor: McpToolDescriptor,
) : AgentTool {
    override val name: String = "mcp__${safeName(server.name)}__${safeName(descriptor.name)}".take(96)
    override val description: String = "MCP ${server.name}: ${descriptor.description}".take(1_200)
    override val inputSchema: JsonObject = descriptor.inputSchema
    override val dangerous: Boolean = true
    override val effect: ToolEffect = ToolEffect.EXTERNAL

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
        val approved = context.approvalGate.approve(
            ApprovalRequest(
                toolName = name,
                title = "Run MCP tool ${descriptor.name}",
                details = "Server: ${server.name}\nTool: ${descriptor.name}\nArguments: ${arguments.toString().take(2_000)}",
                risk = "MCP servers are external programs and may perform network or system side effects.",
            ),
        )
        if (!approved) return ToolExecutionResult("REJECTED_BY_USER: MCP tool was not run.", true)
        val result = client.callTool(descriptor.name, arguments)
        return ToolExecutionResult(result.text, result.isError)
    }

    private fun safeName(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
        .ifBlank { "tool" }
}
