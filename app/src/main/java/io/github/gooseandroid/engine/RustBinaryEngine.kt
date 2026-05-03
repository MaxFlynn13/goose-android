package io.github.gooseandroid.engine

import android.content.Context
import android.util.Log
import io.github.gooseandroid.GoosePortHolder
import io.github.gooseandroid.acp.AcpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Socket

/**
 * Engine implementation that wraps the Rust goose binary.
 * Communicates via ACP (Agent Client Protocol) over WebSocket.
 *
 * This is the "full power" engine — if the binary can execute on this device,
 * it provides 100% Goose capability including all MCP extensions.
 *
 * If the binary fails to execute (common on Android due to W^X policy),
 * the GooseEngineManager falls back to KotlinNativeEngine.
 */
class RustBinaryEngine(private val context: Context) : GooseEngine {

    companion object {
        private const val TAG = "RustBinaryEngine"
        private const val MIN_BINARY_SIZE = 1000L
        private const val STARTUP_TIMEOUT_MS = 15_000L
    }

    private val _status = MutableStateFlow(EngineStatus.DISCONNECTED)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()
    override val engineName = "Goose (Rust binary)"

    private var process: Process? = null
    private var acpClient: AcpClient? = null
    private var port: Int = 0

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        _status.value = EngineStatus.CONNECTING

        // Step 1: Find the binary
        val binary = findBinary()
        if (binary == null) {
            Log.w(TAG, "Binary not found or too small")
            _status.value = EngineStatus.DISCONNECTED
            return@withContext false
        }

        // Step 2: Pre-flight check
        if (!preflightCheck(binary)) {
            Log.w(TAG, "Pre-flight check failed — binary cannot execute on this device")
            _status.value = EngineStatus.DISCONNECTED
            return@withContext false
        }

        // Step 3: Start goose serve
        port = findFreePort()
        val started = startProcess(binary, port)
        if (!started) {
            Log.w(TAG, "Process failed to start")
            _status.value = EngineStatus.DISCONNECTED
            return@withContext false
        }

        // Step 4: Wait for server to be ready
        val ready = waitForServer(port)
        if (!ready) {
            Log.w(TAG, "Server never became ready")
            process?.destroyForcibly()
            _status.value = EngineStatus.DISCONNECTED
            return@withContext false
        }

        // Step 5: Connect ACP client
        val client = AcpClient("ws://127.0.0.1:$port/acp")
        val connectResult = client.connect()
        if (connectResult.isFailure) {
            Log.w(TAG, "ACP connection failed: ${connectResult.exceptionOrNull()?.message}")
            process?.destroyForcibly()
            _status.value = EngineStatus.DISCONNECTED
            return@withContext false
        }

        // Step 6: Create session
        val sessionResult = client.newSession()
        if (sessionResult.isFailure) {
            Log.w(TAG, "ACP session creation failed: ${sessionResult.exceptionOrNull()?.message}")
            client.disconnect()
            process?.destroyForcibly()
            _status.value = EngineStatus.DISCONNECTED
            return@withContext false
        }

        acpClient = client
        GoosePortHolder.port = port
        GoosePortHolder.localOnlyMode = false
        _status.value = EngineStatus.CONNECTED
        Log.i(TAG, "Rust binary engine ready on port $port")
        return@withContext true
    }

    override fun sendMessage(
        message: String,
        conversationHistory: List<ConversationMessage>,
        systemPrompt: String
    ): Flow<AgentEvent> = flow {
        val client = acpClient ?: run {
            emit(AgentEvent.Error("ACP client not connected"))
            return@flow
        }

        try {
            client.sendPrompt(message)

            // Collect ACP notifications and convert to AgentEvents
            // Note: notification.params is com.google.gson.JsonObject, not org.json.JSONObject
            client.notifications.collect { notification ->
                fun safeGet(key: String): String =
                    try { notification.params.get(key)?.asString ?: "" } catch (_: Exception) { "" }

                when (notification.method) {
                    "notifications/progress" -> {
                        val text = safeGet("text")
                        if (text.isNotBlank()) {
                            emit(AgentEvent.Token(text))
                        }
                    }
                    "notifications/tool_call" -> {
                        emit(AgentEvent.ToolStart(safeGet("id"), safeGet("name"), safeGet("input")))
                    }
                    "notifications/tool_result" -> {
                        emit(AgentEvent.ToolEnd(safeGet("id"), safeGet("name"), safeGet("output")))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during ACP message", e)
            emit(AgentEvent.Error("ACP error: ${e.message}"))
        }
    }

    override fun cancel() {
        acpClient?.cancel()
    }

    override suspend fun shutdown() {
        acpClient?.disconnect()
        acpClient = null
        process?.destroyForcibly()
        process = null
        _status.value = EngineStatus.DISCONNECTED
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private fun findBinary(): File? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, "libgoose.so")
        if (!binary.exists() || binary.length() < MIN_BINARY_SIZE) return null
        if (!binary.canExecute()) binary.setExecutable(true)
        return binary
    }

    private fun preflightCheck(binary: File): Boolean {
        return try {
            val process = ProcessBuilder(listOf(binary.absolutePath, "--version"))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().take(500)
            val exitCode = process.waitFor()
            Log.i(TAG, "Pre-flight: exit=$exitCode, output='$output'")
            exitCode == 0 || output.isNotBlank()
        } catch (e: Exception) {
            Log.w(TAG, "Pre-flight exception: ${e.message}")
            false
        }
    }

    private fun startProcess(binary: File, port: Int): Boolean {
        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }
        val env = mapOf(
            "HOME" to workspaceDir.absolutePath,
            "GOOSE_DISABLE_KEYRING" to "true",
            "GOOSE_SHELL" to "/system/bin/sh",
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "${File(context.filesDir, "workspace/runtimes/bin").absolutePath}:/system/bin:/system/xbin"
        )

        return try {
            val pb = ProcessBuilder(
                binary.absolutePath, "serve",
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--with-builtin", "developer,memory"
            ).directory(workspaceDir)

            pb.environment().putAll(env)
            process = pb.start()

            // Check if it died immediately
            Thread.sleep(500)
            process?.isAlive == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start process: ${e.message}")
            false
        }
    }

    private suspend fun waitForServer(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).close()
                return true
            } catch (e: Exception) {
                delay(200)
            }
        }
        return false
    }

    private fun findFreePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }
}
