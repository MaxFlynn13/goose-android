package io.github.gooseandroid.engine.providers

import kotlinx.coroutines.flow.Flow
import io.github.gooseandroid.engine.ConversationMessage
import org.json.JSONObject

/**
 * Interface for LLM providers that support tool use (function calling).
 * Each provider translates between a common format and its own API format.
 */
interface LlmProvider {
    val providerId: String
    val modelId: String

    /**
     * Non-streaming: send messages with tools, get full response.
     * @param messages Conversation history
     * @param tools Tool definitions in common format (name, description, input_schema/parameters)
     * @return Complete response with text and any tool calls
     */
    suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse

    /**
     * Streaming: send messages with tools, get token-by-token response.
     * @param messages Conversation history
     * @param tools Tool definitions in common format
     * @return Flow of streaming events
     */
    fun streamChat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): Flow<StreamEvent>
}

/**
 * Complete response from an LLM provider.
 */
data class LlmResponse(
    val text: String,
    val thinking: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val finishReason: String = "stop"
)

/**
 * A tool call requested by the LLM.
 */
data class LlmToolCall(
    val id: String,
    val name: String,
    val input: JSONObject
)

/**
 * Events emitted during streaming responses.
 */
sealed class StreamEvent {
    /** A text token from the assistant */
    data class Token(val text: String) : StreamEvent()

    /** A thinking/reasoning token (extended thinking) */
    data class Thinking(val text: String) : StreamEvent()

    /** A tool call is starting */
    data class ToolCallStart(val id: String, val name: String) : StreamEvent()

    /** Partial JSON input for a tool call being streamed */
    data class ToolCallInput(val id: String, val partialInput: String) : StreamEvent()

    /** A tool call is complete with full parsed input */
    data class ToolCallEnd(val id: String, val name: String, val input: JSONObject) : StreamEvent()

    /** Stream is done — includes accumulated text and all tool calls */
    data class Done(val fullText: String, val toolCalls: List<LlmToolCall>) : StreamEvent()

    /** An error occurred */
    data class Error(val message: String) : StreamEvent()
}
