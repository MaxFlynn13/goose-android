package io.github.gooseandroid.acp

import io.github.gooseandroid.data.models.ToolCall
import io.github.gooseandroid.data.models.ToolCallStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles ACP notification processing — parsing streaming messages,
 * tool call starts, and tool call ends.
 * Communicates results via callbacks — no ViewModel dependency.
 */
class AcpNotificationHandler {

    /**
     * Callbacks for ACP notification events.
     */
    interface Callbacks {
        fun onStreamingContent(content: String, isComplete: Boolean)
        fun onToolCallStart(name: String, arguments: String?)
        fun onToolCallEnd(name: String, output: String, isError: Boolean)
    }

    private var callbacks: Callbacks? = null

    fun setCallbacks(cb: Callbacks) {
        callbacks = cb
    }

    /**
     * Router: dispatches ACP notifications to the appropriate handler.
     */
    fun handleAcpNotification(notification: AcpNotification) {
        when (notification.method) {
            "notifications/message" -> {
                handleAcpStreamingMessage(notification.params)
            }
            "notifications/tools/call_start" -> {
                handleToolCallStart(notification.params)
            }
            "notifications/tools/call_end" -> {
                handleToolCallEnd(notification.params)
            }
        }
    }

    /**
     * Handle streaming text content from ACP.
     * Extracts content and complete flag, then delegates to callback.
     */
    private fun handleAcpStreamingMessage(params: JsonObject) {
        val content = params["content"]?.jsonPrimitive?.contentOrNull ?: return
        val isComplete = params["complete"]?.jsonPrimitive?.booleanOrNull ?: false
        callbacks?.onStreamingContent(content, isComplete)
    }

    /**
     * Handle tool call start notification.
     * Extracts name and arguments, then delegates to callback.
     */
    private fun handleToolCallStart(params: JsonObject) {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val arguments = params["arguments"]?.toString()
        callbacks?.onToolCallStart(name, arguments)
    }

    /**
     * Handle tool call end notification.
     * Extracts name, output, and error status, then delegates to callback.
     */
    private fun handleToolCallEnd(params: JsonObject) {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val isError = params["error"]?.jsonPrimitive?.booleanOrNull ?: false
        val output = params["result"]?.jsonPrimitive?.contentOrNull ?: ""
        callbacks?.onToolCallEnd(name, output, isError)
    }

    companion object {
        /**
         * Update the last assistant message's tool calls list.
         * Returns the updated tool calls list after applying the start event.
         */
        fun addToolCallStart(currentToolCalls: List<ToolCall>, name: String, input: String): List<ToolCall> {
            val toolCall = ToolCall(
                name = name,
                status = ToolCallStatus.RUNNING,
                input = input
            )
            return currentToolCalls + toolCall
        }

        /**
         * Update tool calls list after a tool call ends.
         * Marks the first matching running tool call as complete/error.
         */
        fun updateToolCallEnd(
            currentToolCalls: List<ToolCall>,
            name: String,
            output: String,
            isError: Boolean
        ): List<ToolCall> {
            val newStatus = if (isError) ToolCallStatus.ERROR else ToolCallStatus.COMPLETE
            return currentToolCalls.map { tc ->
                if (tc.name == name && tc.status == ToolCallStatus.RUNNING) {
                    tc.copy(status = newStatus, output = output)
                } else tc
            }
        }
    }
}
