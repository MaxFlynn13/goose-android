package io.github.gooseandroid.ui.chat

/**
 * Data models for the chat UI.
 */

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ToolCall(
    val name: String,
    val status: ToolCallStatus = ToolCallStatus.RUNNING,
    val arguments: String? = null,
    val result: String? = null
)

enum class ToolCallStatus {
    RUNNING,
    COMPLETE,
    ERROR
}
