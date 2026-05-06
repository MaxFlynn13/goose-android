package io.github.gooseandroid.engine.tools

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Registry and dispatcher for all tools the agent can invoke.
 *
 * Tools register themselves at startup. The agent loop asks the router for
 * tool definitions (to send to the LLM) and delegates execution here.
 *
 * The primary constructor auto-registers the built-in developer tools
 * (shell, write, edit, tree) scoped to the given workspace directory,
 * and optionally registers app control tools if a Context is provided.
 */
class ToolRouter(
    workspaceDir: File,
    shellEnv: Map<String, String> = emptyMap(),
    context: Context? = null
) {

    private val tools = mutableMapOf<String, Tool>()

    init {
        // Register the built-in developer tools
        register(ShellTool(workspaceDir, shellEnv))
        register(FileWriteTool(workspaceDir))
        register(FileEditTool(workspaceDir))
        register(TreeTool(workspaceDir))
        register(GitTool(workspaceDir).AsRegisteredTool())

        // Python tool (Chaquopy embedded CPython)
        context?.let { ctx ->
            try {
                register(PythonTool(ctx, workspaceDir))
            } catch (e: Exception) {
                android.util.Log.w("ToolRouter", "Python tool unavailable: ${e.message}")
            }
        }

        // App control tools (if context available)
        context?.let {
            register(SkillManageTool(it))
            register(ExtensionManageTool(it))
            register(BrainManageTool(it))
            register(ProjectManageTool(it))
            register(ScheduleTool(it))
        }
    }

    /** Register a tool. Overwrites any existing tool with the same name. */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /** Register multiple tools at once. */
    fun registerAll(vararg toolList: Tool) {
        for (tool in toolList) {
            register(tool)
        }
    }

    /** Get the names of all registered tools. */
    fun getToolNames(): List<String> = tools.keys.toList()

    /**
     * Build the list of tool definitions as [JSONObject]s to send to the LLM.
     * Each tool provides its own schema via [Tool.getSchema].
     *
     * The returned objects follow the OpenAI function-calling format:
     * ```json
     * {
     *   "type": "function",
     *   "function": {
     *     "name": "...",
     *     "description": "...",
     *     "parameters": { ... }
     *   }
     * }
     * ```
     *
     * Providers are responsible for converting this common format into their
     * own wire format (e.g. Anthropic's `input_schema` wrapper).
     */
    fun getToolDefinitions(): List<JSONObject> {
        return tools.values.map { it.getSchema() }
    }

    /**
     * Execute a tool by name with the given input.
     *
     * @param name  The tool name (must match a registered tool)
     * @param input The arguments parsed from the LLM's tool call
     * @return A [ToolResult] with the output text and error flag
     */
    suspend fun executeTool(name: String, input: JSONObject): ToolResult {
        val tool = tools[name]
            ?: return ToolResult(
                output = "Error: Unknown tool '$name'. Available tools: ${tools.keys.joinToString()}",
                isError = true
            )

        return try {
            tool.execute(input)
        } catch (e: Exception) {
            ToolResult(
                output = "Tool '$name' threw an exception: ${e.message ?: e.toString()}",
                isError = true
            )
        }
    }
}
