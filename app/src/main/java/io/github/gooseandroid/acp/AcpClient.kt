package io.github.gooseandroid.acp

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ACP (Agent Client Protocol) WebSocket client.
 *
 * Connects to `goose serve` via WebSocket and implements the ACP protocol:
 * - initialize → establish connection
 * - session/new → create a chat session
 * - session/prompt → send a message and stream responses
 * - session/cancel → cancel in-progress generation
 *
 * All communication is JSON-RPC 2.0 over WebSocket.
 */
class AcpClient(private val wsUrl: String) {

    companion object {
        private const val TAG = "AcpClient"
        private const val PROTOCOL_VERSION = "2025-03-26"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(30))
        .build()

    private val requestId = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val _notifications = MutableSharedFlow<AcpNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<AcpNotification> = _notifications.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var sessionId: String? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    /**
     * Connect to the goose server and initialize the ACP session.
     */
    suspend fun connect(): Result<JsonObject> {
        _connectionState.value = ConnectionState.CONNECTING

        val connected = CompletableDeferred<Unit>()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to $wsUrl")
                connected.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connectionState.value = ConnectionState.ERROR
                connected.completeExceptionally(t)
                failAllPending(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                failAllPending(Exception("Connection closed"))
            }
        })

        // Wait for connection
        try {
            withTimeout(10_000) { connected.await() }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            return Result.failure(e)
        }

        // Send initialize
        val initResult = sendRequest("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("clientInfo", buildJsonObject {
                put("name", "goose-android")
                put("version", "0.1.0")
            })
            put("clientCapabilities", buildJsonObject {})
        })

        return initResult.onSuccess {
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "ACP initialized successfully")
        }.onFailure {
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Create a new chat session.
     */
    suspend fun newSession(): Result<String> {
        val result = sendRequest("session/new", buildJsonObject {})
        return result.map { response ->
            val sid = response["sessionId"]?.jsonPrimitive?.content
                ?: throw Exception("No sessionId in response")
            sessionId = sid
            Log.i(TAG, "Created session: $sid")
            sid
        }
    }

    /**
     * Send a prompt message. Responses stream via notifications flow.
     */
    suspend fun sendPrompt(message: String, images: List<String> = emptyList()): Result<Unit> {
        val sid = sessionId ?: return Result.failure(Exception("No active session"))

        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", message)
            })
            images.forEach { imageBase64 ->
                add(buildJsonObject {
                    put("type", "image")
                    put("data", imageBase64)
                    put("mimeType", "image/png")
                })
            }
        }

        val result = sendRequest("session/prompt", buildJsonObject {
            put("sessionId", sid)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", content)
                })
            })
        })

        return result.map { }
    }

    /**
     * Cancel the current generation.
     */
    suspend fun cancel(): Result<Unit> {
        val sid = sessionId ?: return Result.failure(Exception("No active session"))
        val result = sendRequest("session/cancel", buildJsonObject {
            put("sessionId", sid)
        })
        return result.map { }
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun sendRequest(method: String, params: JsonObject): Result<JsonObject> {
        val id = requestId.incrementAndGet()
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val sent = webSocket?.send(json.encodeToString(message)) ?: false
        if (!sent) {
            pendingRequests.remove(id)
            return Result.failure(Exception("Failed to send message"))
        }

        return try {
            val response = withTimeout(60_000) { deferred.await() }
            Result.success(response)
        } catch (e: Exception) {
            pendingRequests.remove(id)
            Result.failure(e)
        }
    }

    private fun handleMessage(text: String) {
        try {
            val msg = json.parseToJsonElement(text).jsonObject

            // Check if it's a response (has "id" and "result" or "error")
            val id = msg["id"]?.jsonPrimitive?.intOrNull
            if (id != null) {
                val deferred = pendingRequests.remove(id)
                val error = msg["error"]?.jsonObject
                if (error != null) {
                    val errorMsg = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    deferred?.completeExceptionally(Exception(errorMsg))
                } else {
                    val result = msg["result"]?.jsonObject ?: buildJsonObject {}
                    deferred?.complete(result)
                }
                return
            }

            // It's a notification (has "method" but no "id")
            val method = msg["method"]?.jsonPrimitive?.content ?: return
            val params = msg["params"]?.jsonObject ?: buildJsonObject {}

            val notification = AcpNotification(method, params)
            _notifications.tryEmit(notification)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${text.take(200)}", e)
        }
    }

    private fun failAllPending(error: Throwable) {
        val entries = pendingRequests.entries.toList()
        entries.forEach { (key, deferred) ->
            deferred.completeExceptionally(error)
            pendingRequests.remove(key)
        }
    }
}

/**
 * An ACP notification from the server (streaming responses, tool calls, etc.)
 */
data class AcpNotification(
    val method: String,
    val params: JsonObject
)
