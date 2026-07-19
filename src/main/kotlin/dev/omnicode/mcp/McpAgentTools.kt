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
import java.io.Closeable

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
}

class McpToolConnector(
    private val launcher: McpProcessLauncher,
    private val httpConnector: McpHttpClientConnector = McpHttpClientConnector {
        throw McpProtocolException("MCP Streamable HTTP connector is not configured")
    },
) {
    suspend fun connect(configs: List<McpServerConfig>): McpToolBundle {
        val clients = mutableListOf<McpClient>()
        val tools = mutableListOf<AgentTool>()
        val toolNames = linkedSetOf<String>()
        val errors = mutableListOf<McpConnectionError>()
        configs.filter { config ->
            config.enabled && when (config.transport) {
                McpTransport.STDIO -> config.command.isNotBlank()
                McpTransport.HTTP -> config.url.isNotBlank()
            }
        }.forEach { config ->
            val client = try {
                when (config.transport) {
                    McpTransport.STDIO -> McpStdioClient.connect(config, launcher)
                    McpTransport.HTTP -> httpConnector.connect(config)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                errors += McpConnectionError(config.name, safeMessage(error))
                return@forEach
            }
            clients += client
            try {
                client.listTools().forEach { descriptor ->
                    val tool = McpAgentTool(config, client, descriptor)
                    if (toolNames.add(tool.name)) {
                        tools += tool
                    } else {
                        errors += McpConnectionError(
                            config.name,
                            "MCP tool name collision after normalization: '${tool.name}'. The later tool was skipped.",
                        )
                    }
                }
            } catch (error: Throwable) {
                errors += McpConnectionError(config.name, safeMessage(error))
            }
        }
        return McpToolBundle(tools, errors, clients)
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.lineSequence()?.firstOrNull()?.take(300) ?: error::class.java.simpleName
}

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
