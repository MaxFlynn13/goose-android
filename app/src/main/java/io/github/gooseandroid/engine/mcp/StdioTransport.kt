package io.github.gooseandroid.engine.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * MCP transport that spawns a child process and communicates over its
 * stdin / stdout using newline-delimited JSON-RPC messages.
 *
 * Stderr is read in the background and forwarded to [Log].
 *
 * @param command  The command + arguments to launch (e.g. `listOf("npx", "-y", "@anthropic/mcp-server-memory")`).
 * @param workingDir  Optional working directory for the process.
 * @param env  Extra environment variables merged into the process environment.
 */
class StdioTransport(
    private val command: List<String>,
    private val workingDir: File? = null,
    private val env: Map<String, String> = emptyMap()
) : McpTransport {

    companion object {
        private const val TAG = "StdioTransport"
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    /** Incoming messages are funnelled through a coroutine channel so that
     *  [receive] can suspend without blocking the caller's dispatcher. */
    private val incoming = Channel<String>(Channel.UNLIMITED)

    /** Scope that owns the background reader coroutines. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    // ------------------------------------------------------------------ public

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Starting process: ${command.joinToString(" ")}")

            val builder = ProcessBuilder(command).apply {
                workingDir?.let { directory(it) }
                environment().putAll(env)
                redirectErrorStream(false)          // keep stderr separate
            }

            val proc = builder.start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

            // --- stdout reader (JSON-RPC messages) ---
            scope.launch {
                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            Log.v(TAG, "stdout ← $trimmed")
                            incoming.send(trimmed)
                        }
                        line = reader.readLine()
                    }
                } catch (e: Exception) {
                    if (_isConnected) Log.w(TAG, "stdout reader error", e)
                } finally {
                    Log.d(TAG, "stdout reader finished")
                    incoming.close()
                }
            }

            // --- stderr reader (diagnostics) ---
            scope.launch {
                val reader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        Log.w(TAG, "stderr: $line")
                        line = reader.readLine()
                    }
                } catch (e: Exception) {
                    if (_isConnected) Log.w(TAG, "stderr reader error", e)
                } finally {
                    Log.d(TAG, "stderr reader finished")
                }
            }

            _isConnected = true
            Log.d(TAG, "Process started (pid=${getPid(proc)})")
        }
    }

    override suspend fun send(message: String): Unit = withContext(Dispatchers.IO) {
        val w = writer ?: throw IllegalStateException("Transport not started")
        Log.v(TAG, "stdin → $message")
        w.write(message)
        w.newLine()
        w.flush()
    }

    override suspend fun receive(): String = try {
        incoming.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw IllegalStateException("Transport closed while waiting for message", e)
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        _isConnected = false
        Log.d(TAG, "Closing stdio transport")

        try { writer?.close() } catch (_: Exception) {}

        process?.let { proc ->
            try {
                proc.destroy()
                // Give it a moment, then force-kill if still alive.
                @Suppress("BlockingMethodInNonBlockingContext")
                val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) {
                    proc.destroyForcibly()
                    Log.w(TAG, "Process force-killed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying process", e)
            }
        }

        scope.cancel()
        process = null
        writer = null
        Log.d(TAG, "Stdio transport closed")
    }

    // ----------------------------------------------------------------- helpers

    /** Best-effort PID extraction for logging (API 26+). */
    private fun getPid(proc: Process): String = try {
        // Process.pid() available on API 33+; fall back to reflection.
        val field = proc.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(proc).toString()
    } catch (_: Exception) {
        "unknown"
    }
}
