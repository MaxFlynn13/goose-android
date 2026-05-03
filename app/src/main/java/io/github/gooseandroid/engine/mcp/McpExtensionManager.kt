package io.github.gooseandroid.engine.mcp

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Manages multiple MCP server connections ("extensions") and provides a
 * unified tool catalogue to the agent loop.
 *
 * Each extension is identified by a human-readable [name]. Tools are namespaced
 * internally so that [callTool] can route to the correct [McpClient].
 */
class McpExtensionManager {

    companion object {
        private const val TAG = "McpExtensionManager"
    }

    /** Active MCP clients keyed by extension name. */
    private val clients = mutableMapOf<String, McpClient>()

    /** Tool name → extension name mapping for routing. */
    private val toolRouting = mutableMapOf<String, String>()

    /** Cached tool list per extension. */
    private val toolsByExtension = mutableMapOf<String, List<McpTool>>()

    /** Serialises mutations to the maps above. */
    private val mutex = Mutex()

    // ------------------------------------------------------------------ public

    /**
     * Connect to an MCP server, perform the handshake, discover its tools,
     * and register them for later invocation.
     *
     * @param name       A unique human-readable identifier for this extension.
     * @param transport  The transport to use (stdio, HTTP, etc.).
     * @return The list of tools the server exposes, or a failure.
     */
    suspend fun addExtension(name: String, transport: McpTransport): Result<List<McpTool>> =
        runCatching {
            mutex.withLock {
                require(!clients.containsKey(name)) {
                    "Extension '$name' is already registered"
                }
            }

            val client = McpClient(transport)
            client.connect().getOrThrow()

            val tools = client.listTools()
            Log.i(TAG, "Extension '$name' provides ${tools.size} tool(s): " +
                    tools.joinToString { it.name })

            mutex.withLock {
                clients[name] = client
                toolsByExtension[name] = tools
                for (tool in tools) {
                    if (toolRouting.containsKey(tool.name)) {
                        Log.w(TAG, "Tool '${tool.name}' already registered by " +
                                "extension '${toolRouting[tool.name]}'; overwriting with '$name'")
                    }
                    toolRouting[tool.name] = name
                }
            }

            tools
        }

    /**
     * Disconnect from an extension and unregister all its tools.
     */
    suspend fun removeExtension(name: String) {
        mutex.withLock {
            val client = clients.remove(name)
            val tools = toolsByExtension.remove(name) ?: emptyList()

            // Remove routing entries that point to this extension.
            for (tool in tools) {
                if (toolRouting[tool.name] == name) {
                    toolRouting.remove(tool.name)
                }
            }

            client?.disconnect()
            Log.i(TAG, "Extension '$name' removed")
        }
    }

    /**
     * Return a flat list of every tool from every connected extension.
     */
    fun getAllTools(): List<McpTool> = synchronized(toolsByExtension) {
        toolsByExtension.values.flatten()
    }

    /**
     * Invoke a tool by name. The manager routes the call to whichever MCP
     * server originally advertised the tool.
     *
     * @throws IllegalArgumentException if no extension provides [name].
     */
    suspend fun callTool(name: String, arguments: JSONObject): McpToolResult {
        val extensionName: String
        val client: McpClient

        mutex.withLock {
            extensionName = toolRouting[name]
                ?: throw IllegalArgumentException(
                    "No extension provides tool '$name'. " +
                            "Available tools: ${toolRouting.keys.joinToString()}"
                )
            client = clients[extensionName]
                ?: throw IllegalStateException(
                    "Extension '$extensionName' is registered but has no active client"
                )
        }

        Log.d(TAG, "Routing tool '$name' → extension '$extensionName'")
        return client.callTool(name, arguments)
    }

    /**
     * Gracefully disconnect all extensions and clear internal state.
     */
    suspend fun shutdown() {
        mutex.withLock {
            Log.i(TAG, "Shutting down ${clients.size} extension(s)")
            for ((name, client) in clients) {
                try {
                    client.disconnect()
                    Log.d(TAG, "Extension '$name' disconnected")
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting extension '$name'", e)
                }
            }
            clients.clear()
            toolRouting.clear()
            toolsByExtension.clear()
        }
    }
}
