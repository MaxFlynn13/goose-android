package io.github.gooseandroid.engine

import android.util.Log
import io.github.gooseandroid.engine.mcp.McpExtensionManager
import io.github.gooseandroid.engine.mcp.McpTool
import io.github.gooseandroid.engine.providers.LlmProvider
import io.github.gooseandroid.engine.providers.LlmResponse
import io.github.gooseandroid.engine.providers.LlmToolCall
import io.github.gooseandroid.engine.tools.ToolRouter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The non-streaming Goose agent loop.
 *
 * Orchestrates the core think → act → observe cycle:
 *  1. Sends the conversation + tool definitions to the [LlmProvider]
 *  2. Parses the response for tool calls
 *  3. Executes each tool call via the [ToolRouter] or [McpExtensionManager]
 *  4. Appends tool results to the conversation
 *  5. Loops back to step 1 until the LLM responds with no tool calls (or [maxIterations] is hit)
 *
 * Each iteration is a full round-trip: the provider returns a complete [LlmResponse]
 * before any tool execution begins. For token-by-token streaming, see [StreamingAgentLoop].
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val toolRouter: ToolRouter,
    private val mcpManager: McpExtensionManager,
    private val maxIterations: Int = 25
) {
    companion object {
        private const val TAG = "AgentLoop"
    }

    // ── System prompt ───────────────────────────────────────────────────────
    private val GOOSE_SYSTEM_PROMPT = """
You are Goose, a powerful AI developer assistant created by Block.

You have access to tools that let you interact with the user's system:
- shell: Execute terminal commands
- write: Create or overwrite files
- edit: Make targeted edits to existing files (find and replace)
- tree: View directory structure

When the user asks you to do something:
1. Think about what steps are needed
2. Use your tools to accomplish the task
3. Verify your work by checking the results
4. Report back to the user

Guidelines:
- Always use the shell tool to run commands rather than just suggesting them
- When editing files, use the edit tool for targeted changes, write for new files
- Check your work — after making changes, verify they're correct
- If a command fails, read the error and try to fix it
- Be proactive — if you see related issues while working, mention them
- Keep the user informed about what you're doing and why

You are running on an Android device. The shell is /system/bin/sh with BusyBox utilities available.
The working directory is the user's project workspace.
""".trimIndent()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Run the full agent loop for a user message.
     *
     * @param userMessage            The new user message
     * @param conversationHistory    Previous [ConversationMessage]s (may be empty for a fresh chat)
     * @param additionalSystemPrompt Extra instructions appended to the default system prompt
     * @return A cold [Flow] of [AgentEvent]s — collect it to drive the loop
     */
    fun run(
        userMessage: String,
        conversationHistory: List<ConversationMessage>,
        additionalSystemPrompt: String = ""
    ): Flow<AgentEvent> = flow {

        // ── Build the system prompt ─────────────────────────────────────
        val systemPrompt = buildString {
            append(GOOSE_SYSTEM_PROMPT)
            if (additionalSystemPrompt.isNotBlank()) {
                append("\n\n")
                append(additionalSystemPrompt)
            }
        }

        // ── Seed the message list ───────────────────────────────────────
        val messages = mutableListOf<ConversationMessage>()
        messages.add(ConversationMessage(role = "system", content = systemPrompt))
        messages.addAll(conversationHistory)
        messages.add(ConversationMessage(role = "user", content = userMessage))

        // ── Merge built-in + MCP tool definitions ───────────────────────
        val toolDefs: List<JSONObject> = buildToolDefinitions()

        var iteration = 0
        val accumulatedText = StringBuilder()

        // ── Main loop ───────────────────────────────────────────────────
        while (iteration < maxIterations) {
            iteration++
            Log.i(TAG, "Agent loop iteration $iteration")

            // Check for cancellation before each LLM call
            currentCoroutineContext().ensureActive()

            // 1. Call the LLM ────────────────────────────────────────────
            val response: LlmResponse = try {
                provider.chat(messages, toolDefs)
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed on iteration $iteration", e)
                emit(AgentEvent.Error("LLM error: ${e.message}"))
                return@flow
            }

            // 2. Emit thinking ───────────────────────────────────────────
            if (response.thinking.isNotBlank()) {
                emit(AgentEvent.Thinking(response.thinking))
            }

            // 3. Emit text content ───────────────────────────────────────
            if (response.text.isNotBlank()) {
                accumulatedText.append(response.text)
                emit(AgentEvent.Token(accumulatedText.toString()))
            }

            // 4. No tool calls → we're done ──────────────────────────────
            if (response.toolCalls.isEmpty()) {
                Log.i(TAG, "Agent loop complete after $iteration iteration(s) — no more tool calls")
                emit(AgentEvent.Complete(accumulatedText.toString()))
                return@flow
            }

            // 5. Record the assistant turn ───────────────────────────────
            messages.add(ConversationMessage(
                role = "assistant",
                content = response.text
            ))

            // 6. Execute each tool call ──────────────────────────────────
            for (toolCall in response.toolCalls) {
                currentCoroutineContext().ensureActive()

                Log.i(TAG, "Executing tool: ${toolCall.name} (id=${toolCall.id})")
                emit(AgentEvent.ToolStart(toolCall.id, toolCall.name, toolCall.input.toString()))

                val result = executeTool(toolCall)

                Log.i(TAG, "Tool ${toolCall.name} finished (error=${result.isError}, " +
                        "output=${result.output.take(200)})")
                emit(AgentEvent.ToolEnd(toolCall.id, toolCall.name, result.output, result.isError))

                // 7. Append the tool result so the LLM sees it next turn ─
                messages.add(ConversationMessage(
                    role = "tool",
                    content = result.output,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name
                ))
            }

            // Loop back — the LLM will see the tool results and decide what to do next
        }

        // ── Safety limit reached ────────────────────────────────────────
        Log.w(TAG, "Agent loop hit max iterations ($maxIterations)")
        emit(
            AgentEvent.Error(
                "Reached maximum number of steps ($maxIterations). " +
                "The task may be too complex for a single interaction."
            )
        )
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Merge built-in tool definitions from [ToolRouter] with any tools
     * discovered from MCP extensions.
     */
    private fun buildToolDefinitions(): List<JSONObject> {
        val defs = toolRouter.getToolDefinitions().toMutableList()

        // Add MCP extension tools
        val mcpTools: List<McpTool> = mcpManager.getAllTools()
        for (tool in mcpTools) {
            defs.add(mcpToolToJson(tool))
        }

        return defs
    }

    /**
     * Execute a tool call — routes to [ToolRouter] for built-in tools,
     * or to [McpExtensionManager] for MCP extension tools.
     */
    private suspend fun executeTool(toolCall: LlmToolCall): ToolCallResult {
        // Try built-in tools first
        val builtinNames = toolRouter.getToolNames()
        if (toolCall.name in builtinNames) {
            val result = toolRouter.executeTool(toolCall.name, toolCall.input)
            return ToolCallResult(result.output, result.isError)
        }

        // Try MCP extensions
        return try {
            val mcpResult = mcpManager.callTool(toolCall.name, toolCall.input)
            ToolCallResult(mcpResult.content, mcpResult.isError)
        } catch (e: IllegalArgumentException) {
            ToolCallResult(
                "Error: Unknown tool '${toolCall.name}'. " +
                "Available: ${builtinNames.joinToString()}, " +
                "${mcpManager.getAllTools().joinToString { it.name }}",
                isError = true
            )
        } catch (e: Exception) {
            ToolCallResult("MCP tool error: ${e.message}", isError = true)
        }
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────────

/**
 * Simple result holder used internally by both [AgentLoop] and [StreamingAgentLoop]
 * to unify built-in and MCP tool results.
 */
internal data class ToolCallResult(
    val output: String,
    val isError: Boolean = false
)

/**
 * Convert an [McpTool] descriptor into the common JSONObject tool definition
 * format that providers expect.
 */
internal fun mcpToolToJson(tool: McpTool): JSONObject = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", if (tool.inputSchema.length() > 0) {
            tool.inputSchema
        } else {
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        })
    })
}
