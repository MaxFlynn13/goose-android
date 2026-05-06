package io.github.gooseandroid.engine

import android.util.Log
import io.github.gooseandroid.engine.mcp.McpExtensionManager
import io.github.gooseandroid.engine.mcp.McpTool
import io.github.gooseandroid.engine.providers.LlmProvider
import io.github.gooseandroid.engine.providers.LlmToolCall
import io.github.gooseandroid.engine.providers.StreamEvent
import io.github.gooseandroid.engine.tools.ToolRouter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Streaming variant of the Goose agent loop.
 *
 * Identical think → act → observe cycle as [AgentLoop], but uses
 * [LlmProvider.streamChat] so that tokens are emitted to the UI the instant
 * they arrive from the API, rather than waiting for the full response.
 *
 * The streaming flow from the provider emits [StreamEvent]s:
 *  - [StreamEvent.Token]         — a new text token
 *  - [StreamEvent.Thinking]      — a chunk of chain-of-thought reasoning
 *  - [StreamEvent.ToolCallStart] — a tool call is beginning (id + name known)
 *  - [StreamEvent.ToolCallInput] — partial JSON input for a tool call being streamed
 *  - [StreamEvent.ToolCallEnd]   — a tool call is fully parsed (id, name, complete input)
 *  - [StreamEvent.Done]          — the stream is complete (accumulated text + all tool calls)
 *  - [StreamEvent.Error]         — an error during streaming
 *
 * This loop collects those events, forwards tokens/thinking to the caller
 * immediately, and when the stream finishes with tool calls it executes them,
 * appends the results, and starts a new streaming turn.
 */
class StreamingAgentLoop(
    private val provider: LlmProvider,
    private val toolRouter: ToolRouter,
    private val mcpManager: McpExtensionManager,
    private val permissionManager: PermissionManager? = null,
    private val contextTracker: ContextTracker? = null,
    private val maxIterations: Int = 25
) {
    companion object {
        private const val TAG = "StreamingAgentLoop"
    }

    // ── System prompt (same as AgentLoop) ───────────────────────────────
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

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Run the streaming agent loop for a user message.
     *
     * @param userMessage            The new user message
     * @param conversationHistory    Previous messages for context
     * @param additionalSystemPrompt Extra instructions appended to the system prompt
     * @return A cold [Flow] of [AgentEvent]s — collect it to drive the loop
     */
    fun run(
        userMessage: String,
        conversationHistory: List<ConversationMessage>,
        additionalSystemPrompt: String = ""
    ): Flow<AgentEvent> = flow {

        // ── Build system prompt ─────────────────────────────────────────
        val systemPrompt = buildString {
            append(GOOSE_SYSTEM_PROMPT)
            if (additionalSystemPrompt.isNotBlank()) {
                append("\n\n")
                append(additionalSystemPrompt)
            }
        }

        // ── Seed message list ───────────────────────────────────────────
        // The system prompt MUST be the first message so providers handle it correctly
        val messages = mutableListOf<ConversationMessage>()
        messages.add(ConversationMessage(role = "system", content = systemPrompt))

        // Add conversation history, but filter out any system messages from history
        // (we already have our system prompt as the first message)
        for (histMsg in conversationHistory) {
            if (histMsg.role == "system") continue
            messages.add(histMsg)
        }

        // Add the new user message
        messages.add(ConversationMessage(role = "user", content = userMessage))

        // ── Merge built-in + MCP tool definitions ───────────────────────
        val toolDefs: List<JSONObject> = buildToolDefinitions()

        var iteration = 0
        val globalAccumulatedText = StringBuilder()   // text across all iterations

        // ── Main loop ───────────────────────────────────────────────────
        while (iteration < maxIterations) {
            iteration++
            Log.i(TAG, "Streaming agent loop iteration $iteration")

            // Support cancellation at the top of each iteration
            currentCoroutineContext().ensureActive()

            // State for this single streaming turn
            val turnText = StringBuilder()
            val turnThinking = StringBuilder()
            val turnToolCalls = mutableListOf<LlmToolCall>()
            var streamError: String? = null

            // ── Stream from the LLM ─────────────────────────────────────
            try {
                val streamFlow: Flow<StreamEvent> = provider.streamChat(messages, toolDefs)

                streamFlow.collect { event ->
                    // Check for cancellation on every event
                    currentCoroutineContext().ensureActive()

                    when (event) {
                        is StreamEvent.Token -> {
                            turnText.append(event.text)
                            globalAccumulatedText.append(event.text)
                            // Emit every token immediately so the UI can render it
                            // The accumulated text is the FULL response so far
                            emit(AgentEvent.Token(globalAccumulatedText.toString()))
                        }

                        is StreamEvent.Thinking -> {
                            turnThinking.append(event.text)
                            emit(AgentEvent.Thinking(event.text))
                        }

                        is StreamEvent.ToolCallStart -> {
                            // The provider has detected the start of a tool call.
                            // We log it but don't emit to UI yet — we wait for ToolCallEnd
                            // which has the fully-parsed input.
                            Log.d(TAG, "Tool call starting: ${event.name} (id=${event.id})")
                        }

                        is StreamEvent.ToolCallInput -> {
                            // Partial JSON input arriving for an in-progress tool call.
                            // We don't emit anything to the UI yet — we wait for
                            // ToolCallEnd which has the fully-parsed input.
                            Log.v(TAG, "Tool call input chunk for ${event.id}")
                        }

                        is StreamEvent.ToolCallEnd -> {
                            // A tool call is fully parsed. Record it for execution
                            // after the stream finishes.
                            turnToolCalls.add(
                                LlmToolCall(
                                    id = event.id,
                                    name = event.name,
                                    input = event.input
                                )
                            )
                            Log.d(TAG, "Tool call complete: ${event.name} (id=${event.id})")
                        }

                        is StreamEvent.Done -> {
                            // The provider signals the stream is complete.
                            // Use Done.toolCalls as the authoritative list if our
                            // incremental tracking missed anything.
                            if (turnToolCalls.isEmpty() && event.toolCalls.isNotEmpty()) {
                                turnToolCalls.addAll(event.toolCalls)
                            }

                            // If the provider accumulated text we missed via tokens
                            if (turnText.isEmpty() && event.fullText.isNotBlank()) {
                                turnText.append(event.fullText)
                                globalAccumulatedText.append(event.fullText)
                                emit(AgentEvent.Token(globalAccumulatedText.toString()))
                            }
                        }

                        is StreamEvent.Error -> {
                            streamError = event.message
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Propagate cancellation — don't swallow it
                throw e
            } catch (e: Exception) {
                // Catch provider errors during streaming and emit as AgentEvent.Error
                Log.e(TAG, "Stream failed on iteration $iteration", e)
                emit(AgentEvent.Error("Streaming error: ${e.message}"))
                return@flow
            }

            // ── Handle stream-level errors ──────────────────────────────
            if (streamError != null) {
                Log.e(TAG, "Stream reported error: $streamError")
                emit(AgentEvent.Error("LLM stream error: $streamError"))
                return@flow
            }

            // ── No tool calls → done ────────────────────────────────────
            if (turnToolCalls.isEmpty()) {
                Log.i(TAG, "Streaming loop complete after $iteration iteration(s) — no tool calls")
                emit(AgentEvent.Complete(globalAccumulatedText.toString()))
                return@flow
            }

            // ── Record the assistant turn in messages ────────────────────
            // The assistant message includes both text content and tool call info
            // so that providers can reconstruct the correct wire format
            // (e.g., Anthropic needs tool_use blocks, OpenAI needs tool_calls array,
            // Google needs functionCall parts).
            messages.add(ConversationMessage(
                role = "assistant",
                content = turnText.toString(),
                toolCalls = turnToolCalls.map { tc ->
                    ToolCallInfo(id = tc.id, name = tc.name, input = tc.input)
                }
            ))

            // ── Execute tool calls ──────────────────────────────────────
            for (toolCall in turnToolCalls) {
                // Check cancellation before each tool execution
                currentCoroutineContext().ensureActive()

                Log.i(TAG, "Executing tool: ${toolCall.name} (id=${toolCall.id})")
                emit(AgentEvent.ToolStart(toolCall.id, toolCall.name, toolCall.input.toString()))

                val result = executeTool(toolCall)

                Log.i(TAG, "Tool ${toolCall.name} finished (error=${result.isError}, " +
                        "output=${result.output.take(200)})")
                emit(AgentEvent.ToolEnd(toolCall.id, toolCall.name, result.output, result.isError))

                // Append tool result for the next LLM turn in the correct format
                // The role is "tool" with the toolCallId so the provider can match it
                messages.add(ConversationMessage(
                    role = "tool",
                    content = result.output,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name
                ))
            }

            // Loop back — the LLM will see tool results and stream its next response
        }

        // ── Safety limit reached ────────────────────────────────────────
        // Even if we hit max iterations, emit Complete with whatever text we have
        // so the UI finalizes properly, then emit the error
        Log.w(TAG, "Streaming agent loop hit max iterations ($maxIterations)")
        if (globalAccumulatedText.isNotEmpty()) {
            emit(AgentEvent.Complete(globalAccumulatedText.toString()))
        }
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
        val builtinNames = toolRouter.getToolNames()
        if (toolCall.name in builtinNames) {
            val result = toolRouter.executeTool(toolCall.name, toolCall.input)
            return ToolCallResult(result.output, result.isError)
        }

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
