package io.github.gooseandroid.engine.extensions

import io.github.gooseandroid.engine.tools.ToolResult
import org.json.JSONObject

/**
 * Interface for built-in MCP extensions implemented in pure Kotlin.
 *
 * These replace Node.js-based stdio MCP servers with native implementations
 * that work on Android without any external runtime dependency.
 *
 * Each extension provides:
 * - A list of tool definitions (JSON schemas for the LLM)
 * - An execution method that handles tool calls
 *
 * Built-in extensions are registered in [BuiltInExtensionRegistry] and
 * their tools are merged with the agent's tool list during the agent loop.
 */
interface BuiltInExtension {
    /** Unique identifier for this extension */
    val id: String

    /** Human-readable name */
    val name: String

    /** Tool definitions in OpenAI function-calling schema format */
    val tools: List<JSONObject>

    /** Execute a tool call and return the result */
    suspend fun executeTool(name: String, input: JSONObject): ToolResult

    /** Check if this extension is properly configured (has required credentials) */
    fun isConfigured(): Boolean = true
}
