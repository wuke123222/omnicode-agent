package dev.omnicode.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** In-memory, line-oriented stdio process used to exercise the real MCP transport. */
internal class FakeMcpProcess(
    private val responder: (JsonObject) -> JsonObject?,
) : Process() {
    private val clientStdout = PipedInputStream(PIPE_BUFFER_SIZE)
    private val serverStdout = PipedOutputStream(clientStdout)
    private val serverStdin = PipedInputStream(PIPE_BUFFER_SIZE)
    private val clientStdin = PipedOutputStream(serverStdin)
    private val clientStderr = PipedInputStream(PIPE_BUFFER_SIZE)
    private val serverStderr = PipedOutputStream(clientStderr)
    private val alive = AtomicBoolean(true)
    private val terminated = CountDownLatch(1)
    private val serverFailure = AtomicReference<Throwable?>()
    private val protocolWriter = AtomicReference<BufferedWriter?>()
    private val writeLock = Any()

    val rawRequestLines = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<JsonObject>()

    private val serverThread = Thread(::serve, "Fake MCP stdio server").apply {
        isDaemon = true
        start()
    }

    override fun getOutputStream(): OutputStream = clientStdin

    override fun getInputStream(): InputStream = clientStdout

    override fun getErrorStream(): InputStream = clientStderr

    override fun waitFor(): Int {
        terminated.await()
        return 0
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = terminated.await(timeout, unit)

    override fun exitValue(): Int {
        if (alive.get()) throw IllegalThreadStateException("Fake MCP process is still running")
        return 0
    }

    override fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        runCatching { clientStdin.close() }
        runCatching { serverStdin.close() }
        runCatching { serverStdout.close() }
        runCatching { clientStdout.close() }
        runCatching { serverStderr.close() }
        runCatching { clientStderr.close() }
        terminated.countDown()
        serverThread.interrupt()
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    override fun isAlive(): Boolean = alive.get()

    fun assertServerHealthy() {
        serverFailure.get()?.let { throw AssertionError("Fake MCP server failed", it) }
    }

    fun sendServerMessage(message: JsonObject) {
        sendServerRawLine(message.toString())
    }

    fun sendServerRawLine(line: String) {
        val writer = protocolWriter.get() ?: error("Fake MCP server writer is not ready")
        synchronized(writeLock) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    private fun serve() {
        try {
            BufferedReader(InputStreamReader(serverStdin, StandardCharsets.UTF_8)).use { reader ->
                BufferedWriter(OutputStreamWriter(serverStdout, StandardCharsets.UTF_8)).use { writer ->
                    protocolWriter.set(writer)
                    try {
                        while (alive.get()) {
                            val line = reader.readLine() ?: break
                            rawRequestLines += line
                            val request = JsonParser.parseString(line).asJsonObject
                            requests += request.deepCopy()
                            responder(request)?.let(::sendServerMessage)
                        }
                    } finally {
                        protocolWriter.compareAndSet(writer, null)
                    }
                }
            }
        } catch (error: Throwable) {
            if (alive.get()) serverFailure.compareAndSet(null, error)
        }
    }

    private companion object {
        const val PIPE_BUFFER_SIZE = 64 * 1_024
    }
}

internal fun mcpSuccess(request: JsonObject, result: JsonObject): JsonObject = JsonObject().apply {
    addProperty("jsonrpc", "2.0")
    add("id", request.get("id").deepCopy())
    add("result", result)
}

internal fun mcpError(request: JsonObject, code: Int, message: String): JsonObject = JsonObject().apply {
    addProperty("jsonrpc", "2.0")
    add("id", request.get("id").deepCopy())
    add("error", JsonObject().apply {
        addProperty("code", code)
        addProperty("message", message)
    })
}

internal fun emptyMcpResult(request: JsonObject): JsonObject = mcpSuccess(request, JsonObject())
