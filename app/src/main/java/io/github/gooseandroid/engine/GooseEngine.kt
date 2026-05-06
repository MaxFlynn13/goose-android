package io.github.gooseandroid.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The core interface that both the Rust binary engine and the Kotlin native engine implement.
 * The rest of the app doesn't know or care which engine is running.
 */
interface GooseEngine {

    /** Current engine status */
    val status: StateFlow<EngineStatus>

    /** Human-readable engine name for diagnostics */
    val engineName: String

    /**
     * Initialize and connect the engine.
     * Returns true if the engine is ready to accept messages.
     */
    suspend fun initialize(): Boolean

    /**
     * Send a user message and get back a stream of agent events.
     * The engine handles the full think→act→observe loop internally.
     *
     * @param message The user's message text
     * @param conversationHistory Previous messages for context
     * @param systemPrompt Optional system instructions (persona, project context)
     * @return Flow of AgentEvents — tokens, tool calls, completion, errors
     */
    fun sendMessage(
        message: String,
        conversationHistory: List<ConversationMessage>,
        systemPrompt: String = ""
    ): Flow<AgentEvent>

    /**
     * Cancel the current generation/tool execution.
     */
    fun cancel()

    /**
     * Clean shutdown of the engine.
     */
    suspend fun shutdown()
}

enum class EngineStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Events emitted by the engine during message processing.
 * The UI collects these to update the chat display.
 */
sealed class AgentEvent {
    /** A token of assistant text (accumulated — each emission is the full text so far) */
    data class Token(val accumulated: String) : AgentEvent()

    /** The assistant's thinking/reasoning (for models that support it) */
    data class Thinking(val text: String) : AgentEvent()

    /** A tool call is starting */
    data class ToolStart(val id: String, val name: String, val input: String) : AgentEvent()

    /** A tool call has completed */
    data class ToolEnd(val id: String, val name: String, val output: String, val isError: Boolean = false) : AgentEvent()

    /** The full response is complete */
    data class Complete(val fullText: String) : AgentEvent()

    /** An error occurred */
    data class Error(val message: String) : AgentEvent()
}

/**
 * Information about a single tool call made by the assistant.
 * Stored in [ConversationMessage.toolCalls] so that providers can format
 * the conversation history correctly (each provider needs tool_use/functionCall
 * blocks in the assistant message).
 */
data class ToolCallInfo(
    val id: String,
    val name: String,
    val input: JSONObject
)

/**
 * A message in the conversation history.
 * Carries all information providers need to format messages correctly:
 *
 * - role="system": system prompt, only [content] is used
 * - role="user": user message, only [content] is used
 * - role="assistant": assistant response, [content] is the text,
 *   [toolCalls] contains any tool invocations the assistant made
 * - role="tool": tool result, [content] is the output,
 *   [toolCallId] identifies which tool call this responds to,
 *   [toolName] is the tool that was called
 */
data class ConversationMessage(
    val role: String,                           // "user", "assistant", "system", "tool"
    val content: String,
    val toolCallId: String? = null,             // For role="tool": which tool call this is a result for
    val toolName: String? = null,               // For role="tool": the tool name
    val toolCalls: List<ToolCallInfo>? = null   // For role="assistant": tool calls made
)
