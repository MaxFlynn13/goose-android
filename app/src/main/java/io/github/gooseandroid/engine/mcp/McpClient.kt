package io.github.gooseandroid.engine.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP (Model Context Protocol) client.
 *
 * Implements the JSON-RPC 2.0 based MCP handshake and full protocol:
 *   1. `initialize` → server capabilities
 *   2. `notifications/initialized`
 *   3. `tools/list` → discover tools
 *   4. `tools/call` → invoke a tool
 *   5. `resources/list` → discover resources
 *   6. `resources/read` → read a resource
 *   7. `prompts/list` → discover prompts
 *   8. `prompts/get` → get a prompt
 */
class McpClient(private val transport: McpTransport) {

    companion object {
        private const val TAG = "McpClient"
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val CLIENT_NAME = "goose-android"
        private const val CLIENT_VERSION = "1.0.0"
    }

    /** Monotonically increasing JSON-RPC request id. */
    private var requestId = 0

    /** Protects [requestId] and serialises request/response pairs. */
    private val mutex = Mutex()

    /** Capabilities reported by the server after `initialize`. */
    private var serverCapabilities: JSONObject? = null

    // ------------------------------------------------------------------ public

    /**
     * Open the transport, perform the MCP `initialize` handshake, and send the
     * `notifications/initialized` notification.
     */
    suspend fun connect(): Result<Unit> = runCatching {
        transport.start().getOrThrow()
        Log.d(TAG, "Transport started, sending initialize request")

        val initParams = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", CLIENT_NAME)
                put("version", CLIENT_VERSION)
            })
        }

        val response = sendRequest("initialize", initParams)
        serverCapabilities = response.optJSONObject("result")
            ?.optJSONObject("capabilities")
        Log.d(TAG, "Server capabilities: $serverCapabilities")

        // Send the initialized notification (fire-and-forget, no id).
        val notification = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        transport.send(notification.toString())
        Log.d(TAG, "MCP session initialized")
    }

    /**
     * Ask the server for its tool catalogue via `tools/list`.
     *
     * Returns an empty list when the server exposes no tools.
     */
    suspend fun listTools(): List<McpTool> {
        val response = sendRequest("tools/list", JSONObject())
        val result = response.optJSONObject("result") ?: return emptyList()
        val toolsArray: JSONArray = result.optJSONArray("tools") ?: return emptyList()

        return (0 until toolsArray.length()).map { i ->
            val obj = toolsArray.getJSONObject(i)
            McpTool(
                name = obj.getString("name"),
                description = obj.optString("description", ""),
                inputSchema = obj.optJSONObject("inputSchema") ?: JSONObject()
            )
        }
    }

    /**
     * Execute a tool on the server via `tools/call`.
     */
    suspend fun callTool(name: String, arguments: JSONObject): McpToolResult {
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val response = sendRequest("tools/call", params)

        // Check for JSON-RPC level error
        response.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown MCP error")
            Log.e(TAG, "tools/call error: $msg")
            return McpToolResult(content = msg, isError = true)
        }

        val result = response.optJSONObject("result")
        val isError = result?.optBoolean("isError", false) ?: false

        // MCP tool results contain a `content` array; concatenate text pieces.
        val contentArray: JSONArray? = result?.optJSONArray("content")
        val text = if (contentArray != null && contentArray.length() > 0) {
            (0 until contentArray.length()).joinToString("\n") { i ->
                val piece = contentArray.getJSONObject(i)
                piece.optString("text", piece.toString())
            }
        } else {
            result?.toString() ?: ""
        }

        return McpToolResult(content = text, isError = isError)
    }

    // ── Resources ───────────────────────────────────────────────────────────

    /**
     * List all resources exposed by the server via `resources/list`.
     *
     * Returns an empty list if the server does not support resources or has none.
     */
    suspend fun listResources(): Result<List<McpResource>> = runCatching {
        val response = sendRequest("resources/list", JSONObject())

        // Check for JSON-RPC level error
        response.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown MCP error")
            throw McpException("resources/list failed: $msg")
        }

        val result = response.optJSONObject("result") ?: return@runCatching emptyList()
        val resourcesArray: JSONArray = result.optJSONArray("resources") ?: return@runCatching emptyList()

        (0 until resourcesArray.length()).map { i ->
            val obj = resourcesArray.getJSONObject(i)
            McpResource(
                uri = obj.getString("uri"),
                name = obj.optString("name", ""),
                description = obj.optString("description", null),
                mimeType = obj.optString("mimeType", null)
            )
        }
    }

    /**
     * Read the content of a resource by URI via `resources/read`.
     *
     * Returns the text content of the resource. For resources with multiple content
     * parts, they are concatenated with newlines.
     */
    suspend fun readResource(uri: String): Result<String> = runCatching {
        val params = JSONObject().apply {
            put("uri", uri)
        }
        val response = sendRequest("resources/read", params)

        // Check for JSON-RPC level error
        response.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown MCP error")
            throw McpException("resources/read failed: $msg")
        }

        val result = response.optJSONObject("result")
            ?: throw McpException("resources/read returned no result")

        val contentsArray: JSONArray = result.optJSONArray("contents")
            ?: throw McpException("resources/read returned no contents")

        (0 until contentsArray.length()).joinToString("\n") { i ->
            val piece = contentsArray.getJSONObject(i)
            // MCP resource content can be text or blob (base64)
            when {
                piece.has("text") -> piece.getString("text")
                piece.has("blob") -> piece.getString("blob")
                else -> piece.toString()
            }
        }
    }

    // ── Prompts ─────────────────────────────────────────────────────────────

    /**
     * List all prompts exposed by the server via `prompts/list`.
     *
     * Returns an empty list if the server does not support prompts or has none.
     */
    suspend fun listPrompts(): Result<List<McpPrompt>> = runCatching {
        val response = sendRequest("prompts/list", JSONObject())

        // Check for JSON-RPC level error
        response.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown MCP error")
            throw McpException("prompts/list failed: $msg")
        }

        val result = response.optJSONObject("result") ?: return@runCatching emptyList()
        val promptsArray: JSONArray = result.optJSONArray("prompts") ?: return@runCatching emptyList()

        (0 until promptsArray.length()).map { i ->
            val obj = promptsArray.getJSONObject(i)
            val argsArray = obj.optJSONArray("arguments")
            val arguments = if (argsArray != null) {
                (0 until argsArray.length()).map { j ->
                    val argObj = argsArray.getJSONObject(j)
                    McpPromptArgument(
                        name = argObj.getString("name"),
                        description = argObj.optString("description", null),
                        required = argObj.optBoolean("required", false)
                    )
                }
            } else {
                null
            }

            McpPrompt(
                name = obj.getString("name"),
                description = obj.optString("description", null),
                arguments = arguments
            )
        }
    }

    /**
     * Get a prompt from the server via `prompts/get`.
     *
     * @param name The prompt name
     * @param arguments Key-value arguments to fill prompt template variables
     * @return The rendered prompt text (all message content parts concatenated)
     */
    suspend fun getPrompt(name: String, arguments: Map<String, String> = emptyMap()): Result<String> = runCatching {
        val params = JSONObject().apply {
            put("name", name)
            if (arguments.isNotEmpty()) {
                val argsObj = JSONObject()
                for ((key, value) in arguments) {
                    argsObj.put(key, value)
                }
                put("arguments", argsObj)
            }
        }
        val response = sendRequest("prompts/get", params)

        // Check for JSON-RPC level error
        response.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Unknown MCP error")
            throw McpException("prompts/get failed: $msg")
        }

        val result = response.optJSONObject("result")
            ?: throw McpException("prompts/get returned no result")

        val messagesArray: JSONArray = result.optJSONArray("messages")
            ?: throw McpException("prompts/get returned no messages")

        // Concatenate all message content into a single string.
        // Each message has a role and content (which can be text or structured).
        buildString {
            for (i in 0 until messagesArray.length()) {
                val message = messagesArray.getJSONObject(i)
                val role = message.optString("role", "")
                val content = message.opt("content")

                if (i > 0) append("\n\n")

                if (role.isNotEmpty()) {
                    append("[$role]\n")
                }

                when (content) {
                    is String -> append(content)
                    is JSONObject -> {
                        // Structured content with type field
                        val type = content.optString("type", "text")
                        when (type) {
                            "text" -> append(content.optString("text", ""))
                            "resource" -> {
                                val resource = content.optJSONObject("resource")
                                if (resource != null) {
                                    append(resource.optString("text", resource.toString()))
                                }
                            }
                            else -> append(content.toString())
                        }
                    }
                    is JSONArray -> {
                        // Array of content parts
                        for (j in 0 until content.length()) {
                            val part = content.getJSONObject(j)
                            val type = part.optString("type", "text")
                            when (type) {
                                "text" -> append(part.optString("text", ""))
                                "resource" -> {
                                    val resource = part.optJSONObject("resource")
                                    if (resource != null) {
                                        append(resource.optString("text", resource.toString()))
                                    }
                                }
                                else -> append(part.toString())
                            }
                            if (j < content.length() - 1) append("\n")
                        }
                    }
                    else -> append(content?.toString() ?: "")
                }
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Gracefully disconnect: close the transport.
     */
    suspend fun disconnect() {
        try {
            transport.close()
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect", e)
        }
    }

    // ----------------------------------------------------------------- private

    /**
     * Send a JSON-RPC 2.0 **request** (has an `id`) and block until the
     * matching response arrives.
     *
     * Notifications received while waiting are logged and skipped.
     */
    private suspend fun sendRequest(method: String, params: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val id = ++requestId
                val envelope = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    if (params != null) put("params", params)
                }

                Log.d(TAG, "→ $method (id=$id)")
                transport.send(envelope.toString())

                // Read messages until we get the response matching our id.
                while (true) {
                    val raw = transport.receive()
                    val msg = try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-JSON message from server, skipping: $raw")
                        continue
                    }

                    // Skip notifications (messages without an id).
                    if (!msg.has("id")) {
                        val notifMethod = msg.optString("method", "<unknown>")
                        Log.d(TAG, "← notification: $notifMethod")
                        continue
                    }

                    val responseId = msg.optInt("id", -1)
                    if (responseId == id) {
                        Log.d(TAG, "← response for $method (id=$id)")
                        return@withContext msg
                    }

                    // Response for a different id – shouldn't happen with the
                    // mutex, but log and keep waiting just in case.
                    Log.w(TAG, "Unexpected response id=$responseId while waiting for id=$id")
                }

                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("Unreachable")
            }
        }
}

// ---------------------------------------------------------------------- models

/**
 * Descriptor for a single tool exposed by an MCP server.
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

/**
 * Result returned after invoking a tool via `tools/call`.
 */
data class McpToolResult(
    val content: String,
    val isError: Boolean = false
)

/**
 * Descriptor for a resource exposed by an MCP server.
 */
data class McpResource(
    val uri: String,
    val name: String,
    val description: String?,
    val mimeType: String?
)

/**
 * Descriptor for a prompt exposed by an MCP server.
 */
data class McpPrompt(
    val name: String,
    val description: String?,
    val arguments: List<McpPromptArgument>?
)

/**
 * A single argument definition for an MCP prompt.
 */
data class McpPromptArgument(
    val name: String,
    val description: String?,
    val required: Boolean
)

/**
 * Exception type for MCP protocol errors.
 */
class McpException(message: String) : Exception(message)
