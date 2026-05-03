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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * MCP transport over the **Streamable HTTP** transport defined in the MCP spec.
 *
 * - **Sending**: HTTP POST with `Content-Type: application/json` to [serverUrl].
 * - **Receiving**: The POST response (or a long-lived GET) uses
 *   `text/event-stream` (SSE). Each `data:` frame contains one JSON-RPC message.
 * - A server may return a `Mcp-Session-Id` header that must be echoed on
 *   subsequent requests.
 *
 * @param serverUrl  Full URL of the MCP HTTP endpoint (e.g. `http://localhost:3000/mcp`).
 * @param headers    Extra headers to include on every request (auth tokens, etc.).
 */
class HttpTransport(
    private val serverUrl: String,
    private val headers: Map<String, String> = emptyMap()
) : McpTransport {

    companion object {
        private const val TAG = "HttpTransport"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val SESSION_HEADER = "Mcp-Session-Id"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)      // SSE streams can be long-lived
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Incoming JSON-RPC messages parsed from SSE frames. */
    private val incoming = Channel<String>(Channel.UNLIMITED)

    /** Background coroutine scope for the SSE reader. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Session id returned by the server (may be null). */
    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    /** The long-lived SSE response kept open for server-initiated messages. */
    @Volatile
    private var sseResponse: Response? = null

    // ------------------------------------------------------------------ public

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Opening HTTP transport to $serverUrl")
            _isConnected = true

            // Optionally open a GET SSE stream for server-initiated notifications.
            scope.launch {
                try {
                    openSseListener()
                } catch (e: Exception) {
                    Log.d(TAG, "GET SSE listener not available: ${e.message}")
                }
            }
            Unit  // Explicit Unit return for Result<Unit>
        }
    }

    /**
     * POST a JSON-RPC message to the server.
     *
     * The response may be:
     * - `application/json` → single response, enqueue it.
     * - `text/event-stream` → SSE stream, read all `data:` frames and enqueue.
     * - 202 Accepted with no body → response will arrive on the SSE stream.
     */
    override suspend fun send(message: String): Unit = withContext(Dispatchers.IO) {
        Log.v(TAG, "POST → $message")

        val requestBuilder = Request.Builder()
            .url(serverUrl)
            .post(message.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json, text/event-stream")

        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        sessionId?.let { requestBuilder.header(SESSION_HEADER, it) }

        val response = client.newCall(requestBuilder.build()).execute()

        // Capture session id if the server provides one.
        response.header(SESSION_HEADER)?.let { id ->
            sessionId = id
            Log.d(TAG, "Session id: $id")
        }

        val code = response.code
        val contentType = response.header("Content-Type") ?: ""

        when {
            code == 202 -> {
                // Accepted – response will arrive on the SSE stream.
                Log.d(TAG, "202 Accepted (response via SSE)")
                response.close()
            }

            contentType.contains("text/event-stream") -> {
                // Read SSE frames from the POST response body.
                val body = response.body ?: run {
                    response.close()
                    return@withContext
                }
                scope.launch {
                    readSseStream(BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)))
                    response.close()
                }
            }

            contentType.contains("application/json") -> {
                val body = response.body?.string() ?: ""
                response.close()
                if (body.isNotBlank()) {
                    Log.v(TAG, "JSON ← $body")
                    incoming.send(body)
                }
            }

            else -> {
                Log.w(TAG, "Unexpected response: $code $contentType")
                response.close()
            }
        }
    }

    override suspend fun receive(): String = try {
        incoming.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw IllegalStateException("Transport closed while waiting for message", e)
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        _isConnected = false
        Log.d(TAG, "Closing HTTP transport")

        // Send DELETE to terminate the session if we have one.
        sessionId?.let { id ->
            try {
                val req = Request.Builder()
                    .url(serverUrl)
                    .delete()
                    .header(SESSION_HEADER, id)
                    .build()
                client.newCall(req).execute().close()
                Log.d(TAG, "Session terminated")
            } catch (e: Exception) {
                Log.w(TAG, "Error terminating session", e)
            }
        }

        sseResponse?.close()
        scope.cancel()
        incoming.close()
        Log.d(TAG, "HTTP transport closed")
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Open a long-lived GET request with `Accept: text/event-stream` for
     * server-initiated notifications.
     */
    private fun openSseListener() {
        val requestBuilder = Request.Builder()
            .url(serverUrl)
            .get()
            .header("Accept", "text/event-stream")

        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        sessionId?.let { requestBuilder.header(SESSION_HEADER, it) }

        val response = client.newCall(requestBuilder.build()).execute()
        response.header(SESSION_HEADER)?.let { sessionId = it }

        if (response.header("Content-Type")?.contains("text/event-stream") != true) {
            response.close()
            return
        }

        sseResponse = response
        val body = response.body ?: return
        readSseStream(BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)))
        response.close()
    }

    /**
     * Parse an SSE stream, extracting `data:` lines and posting them to [incoming].
     *
     * Per the SSE spec, multi-line data events are concatenated with newlines
     * and dispatched on the blank-line boundary.
     */
    private fun readSseStream(reader: BufferedReader) {
        val dataBuffer = StringBuilder()
        try {
            var line = reader.readLine()
            while (line != null && _isConnected) {
                when {
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                        dataBuffer.append(payload)
                    }

                    line.isBlank() -> {
                        // End of an SSE event – dispatch accumulated data.
                        if (dataBuffer.isNotEmpty()) {
                            val msg = dataBuffer.toString()
                            dataBuffer.clear()
                            Log.v(TAG, "SSE ← $msg")
                            incoming.trySend(msg)
                        }
                    }

                    line.startsWith("event:") -> {
                        // Named events – currently unused by MCP, but log them.
                        Log.d(TAG, "SSE event type: ${line.removePrefix("event:").trim()}")
                    }

                    line.startsWith(":") -> {
                        // SSE comment / keep-alive – ignore.
                    }
                }
                line = reader.readLine()
            }

            // Flush any trailing data without a final blank line.
            if (dataBuffer.isNotEmpty()) {
                val msg = dataBuffer.toString()
                Log.v(TAG, "SSE (trailing) ← $msg")
                incoming.trySend(msg)
            }
        } catch (e: Exception) {
            if (_isConnected) Log.w(TAG, "SSE reader error", e)
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
    }
}
