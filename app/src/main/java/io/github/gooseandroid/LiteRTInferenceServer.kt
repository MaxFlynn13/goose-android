package io.github.gooseandroid

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket

/**
 * Local HTTP server that wraps LiteRT inference in an OpenAI-compatible API.
 *
 * This allows goose to connect to local models using the same provider
 * interface it uses for cloud APIs — just pointed at localhost.
 *
 * Endpoints:
 *   POST /v1/chat/completions  — OpenAI-compatible chat completion
 *   GET  /v1/models            — List available local models
 *   GET  /health               — Health check
 *
 * The goose config just needs:
 *   provider: openai
 *   host: http://127.0.0.1:LOCAL_MODEL_PORT
 *   model: gemma-2b-it
 *
 * This is a placeholder implementation. The actual LiteRT inference
 * integration will be added once the basic app shell is working.
 *
 * Future: Use Google AI Edge's InferenceSession API:
 *   val session = InferenceSession.create(context, modelPath, options)
 *   val response = session.generateResponse(prompt)
 *
 * Reference implementations:
 * - https://github.com/google-ai-edge/gallery
 * - https://ai.google.dev/edge/litert/android
 * - https://github.com/nicola-carraro/proot-rs (for model conversion)
 */
class LiteRTInferenceServer(
    private val modelManager: LocalModelManager
) {
    companion object {
        private const val TAG = "LiteRTServer"
        private const val DEFAULT_PORT = 11435 // Similar to Ollama's 11434
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var port: Int = DEFAULT_PORT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getPort(): Int = port

    fun isRunning(): Boolean = serverSocket?.isClosed == false

    /**
     * Start the local inference server.
     */
    fun start(): Int {
        if (isRunning()) return port

        port = findFreePort()
        Log.i(TAG, "Starting LiteRT inference server on port $port")

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "LiteRT server listening on port $port")

                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server error", e)
                }
            }
        }

        return port
    }

    /**
     * Stop the inference server.
     */
    fun stop() {
        Log.i(TAG, "Stopping LiteRT inference server")
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = BufferedOutputStream(socket.getOutputStream())

                val requestLine = reader.readLine() ?: return@withContext
                val (method, path) = parseRequestLine(requestLine)

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        headers[line.substring(0, colonIdx).trim().lowercase()] =
                            line.substring(colonIdx + 1).trim()
                    }
                    line = reader.readLine()
                }

                // Read body if present
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    reader.read(chars, 0, contentLength)
                    String(chars)
                } else ""

                // Route request
                val response = when {
                    method == "GET" && path == "/health" -> handleHealth()
                    method == "GET" && path == "/v1/models" -> handleListModels()
                    method == "POST" && path == "/v1/chat/completions" -> handleChatCompletion(body)
                    else -> httpResponse(404, """{"error":"not found"}""")
                }

                writer.write(response.toByteArray())
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Client handler error", e)
            } finally {
                socket.close()
            }
        }
    }

    private fun handleHealth(): String {
        return httpResponse(200, """{"status":"ok","engine":"litert"}""")
    }

    private fun handleListModels(): String {
        val models = modelManager.getDownloadedModels()
        val modelsJson = models.joinToString(",") { model ->
            """{"id":"${model.id}","object":"model","owned_by":"local","created":0}"""
        }
        return httpResponse(200, """{"object":"list","data":[$modelsJson]}""")
    }

    private fun handleChatCompletion(body: String): String {
        // TODO: Actual LiteRT inference integration
        // For now, return a placeholder response indicating local models aren't loaded yet
        //
        // Future implementation:
        // 1. Parse the request body for model ID and messages
        // 2. Load the model via LiteRT InferenceSession
        // 3. Format prompt from messages array
        // 4. Run inference with streaming
        // 5. Return OpenAI-compatible response

        val downloadedModels = modelManager.getDownloadedModels()
        val responseText = if (downloadedModels.isEmpty()) {
            "No local models downloaded yet. Please download a model from the Models settings page, or configure a cloud provider (Anthropic, OpenAI, etc.) in Goose settings."
        } else {
            "[LiteRT inference not yet implemented - model files present but inference engine pending integration]"
        }

        val response = """{
            "id": "local-${System.currentTimeMillis()}",
            "object": "chat.completion",
            "created": ${System.currentTimeMillis() / 1000},
            "model": "local",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "$responseText"
                },
                "finish_reason": "stop"
            }],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
        }""".trimIndent()

        return httpResponse(200, response)
    }

    private fun parseRequestLine(line: String): Pair<String, String> {
        val parts = line.split(" ")
        return Pair(
            parts.getOrElse(0) { "GET" },
            parts.getOrElse(1) { "/" }
        )
    }

    private fun httpResponse(status: Int, body: String): String {
        val statusText = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
        return "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
