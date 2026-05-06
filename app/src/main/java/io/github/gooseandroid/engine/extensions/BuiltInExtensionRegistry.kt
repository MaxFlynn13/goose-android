package io.github.gooseandroid.engine.extensions

import android.content.Context
import android.util.Log
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.engine.tools.ToolResult
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Registry of built-in Kotlin MCP extensions.
 *
 * These extensions replace Node.js stdio MCP servers with native implementations:
 * - GitHub (replaces @modelcontextprotocol/server-github)
 * - Web Search (replaces @modelcontextprotocol/server-brave-search)
 * - Fetch (replaces @modelcontextprotocol/server-fetch)
 *
 * Extensions are loaded based on user settings (enabled/disabled + API keys).
 * Their tools are merged into the agent's tool list alongside shell/edit/write/tree/git.
 */
class BuiltInExtensionRegistry(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInExtRegistry"
    }

    private val extensions = mutableMapOf<String, BuiltInExtension>()
    private val toolToExtension = mutableMapOf<String, BuiltInExtension>()

    /**
     * Load all configured extensions based on user settings.
     */
    suspend fun loadExtensions() {
        val settingsStore = SettingsStore(context)

        // GitHub extension — needs a token
        try {
            val githubToken = settingsStore.getString("github_token", "").first()
            if (githubToken.isNotBlank()) {
                val github = GitHubExtension(githubToken)
                registerExtension(github)
                Log.i(TAG, "GitHub extension loaded (${github.tools.size} tools)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load GitHub extension: ${e.message}")
        }

        // Web Search — no API key needed (uses DuckDuckGo)
        try {
            val webSearch = WebSearchExtension()
            registerExtension(webSearch)
            Log.i(TAG, "Web Search extension loaded (${webSearch.tools.size} tools)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load Web Search extension: ${e.message}")
        }

        // Fetch — no API key needed
        try {
            val fetch = FetchExtension()
            registerExtension(fetch)
            Log.i(TAG, "Fetch extension loaded (${fetch.tools.size} tools)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load Fetch extension: ${e.message}")
        }

        Log.i(TAG, "Total extensions: ${extensions.size}, Total tools: ${toolToExtension.size}")
    }

    private fun registerExtension(extension: BuiltInExtension) {
        extensions[extension.id] = extension
        for (tool in extension.tools) {
            val toolName = tool.optString("name", "")
            if (toolName.isNotBlank()) {
                toolToExtension[toolName] = extension
            }
        }
    }

    /**
     * Get all tool definitions from all loaded extensions.
     */
    fun getAllToolDefinitions(): List<JSONObject> {
        return extensions.values.flatMap { it.tools }
    }

    /**
     * Check if a tool name belongs to a built-in extension.
     */
    fun hasExtensionTool(toolName: String): Boolean {
        return toolToExtension.containsKey(toolName)
    }

    /**
     * Execute a tool call via the appropriate extension.
     */
    suspend fun executeTool(toolName: String, input: JSONObject): ToolResult {
        val extension = toolToExtension[toolName]
            ?: return ToolResult("Unknown extension tool: $toolName", isError = true)

        return try {
            extension.executeTool(toolName, input)
        } catch (e: Exception) {
            Log.e(TAG, "Extension tool error: $toolName: ${e.message}", e)
            ToolResult("Extension error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    /**
     * Get list of loaded extension names.
     */
    fun getLoadedExtensions(): List<String> = extensions.keys.toList()
}
