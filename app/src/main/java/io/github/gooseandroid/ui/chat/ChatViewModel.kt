package io.github.gooseandroid.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.gooseandroid.GoosePortHolder
import io.github.gooseandroid.acp.AcpClient
import io.github.gooseandroid.acp.AcpNotification
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

class ChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var acpClient: AcpClient? = null
    val connectionState: StateFlow<AcpClient.ConnectionState>
        get() = acpClient?.connectionState ?: MutableStateFlow(AcpClient.ConnectionState.DISCONNECTED)

    private var currentAssistantMessageId: String? = null

    init {
        connectToGoose()
    }

    private fun connectToGoose() {
        viewModelScope.launch {
            // Check if we're in local-only mode (no goose binary)
            if (GoosePortHolder.localOnlyMode) {
                Log.i(TAG, "Running in local-only mode")
                addSystemMessage(
                    "🪿 Welcome to Goose!\n\n" +
                    "The Goose backend binary isn't installed yet. You can:\n\n" +
                    "• **Configure a cloud API** — Go to Settings and add an API key " +
                    "(Anthropic, OpenAI, or Google)\n\n" +
                    "• **Download a local model** — Go to Settings → Local Models " +
                    "to run AI completely on-device\n\n" +
                    "Once the full build is ready, Goose will have shell access, " +
                    "file editing, and all MCP extensions."
                )
                return@launch
            }

            val port = GoosePortHolder.port
            val url = "ws://127.0.0.1:$port/acp"
            Log.i(TAG, "Connecting to goose at $url")

            val client = AcpClient(url)
            acpClient = client

            // Listen for notifications (streaming responses)
            launch {
                client.notifications.collect { notification ->
                    handleNotification(notification)
                }
            }

            // Connect and initialize
            val result = client.connect()
            result.onSuccess {
                Log.i(TAG, "Connected to goose, creating session...")
                val sessionResult = client.newSession()
                sessionResult.onSuccess { sessionId ->
                    Log.i(TAG, "Session created: $sessionId")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to create session", error)
                    addSystemMessage("Failed to create session: ${error.message}")
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to connect to goose", error)
                addSystemMessage("Unable to connect to Goose backend: ${error.message}\n\nGo to Settings to configure a provider.")
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Add user message to UI
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text
        )
        _messages.value = _messages.value + userMessage
        _isGenerating.value = true

        // Prepare assistant message placeholder
        currentAssistantMessageId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
            id = currentAssistantMessageId!!,
            role = MessageRole.ASSISTANT,
            content = ""
        )
        _messages.value = _messages.value + assistantMessage

        // Send to goose
        viewModelScope.launch {
            val result = acpClient?.sendPrompt(text)
            result?.onFailure { error ->
                Log.e(TAG, "Failed to send prompt", error)
                updateAssistantMessage("Error: ${error.message}")
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        viewModelScope.launch {
            acpClient?.cancel()
            _isGenerating.value = false
        }
    }

    private fun handleNotification(notification: AcpNotification) {
        when (notification.method) {
            "session/update" -> handleSessionUpdate(notification.params)
            "session/error" -> handleSessionError(notification.params)
            else -> Log.d(TAG, "Unhandled notification: ${notification.method}")
        }
    }

    private fun handleSessionUpdate(params: JsonObject) {
        // Extract content from the session update
        // ACP sends incremental updates with content blocks
        val update = params["update"]?.jsonObject ?: params

        // Check for message content
        val content = update["content"]?.let { contentElement ->
            when (contentElement) {
                is JsonArray -> {
                    contentElement.mapNotNull { block ->
                        val blockObj = block.jsonObject
                        when (blockObj["type"]?.jsonPrimitive?.content) {
                            "text" -> blockObj["text"]?.jsonPrimitive?.content
                            else -> null
                        }
                    }.joinToString("")
                }
                is JsonPrimitive -> contentElement.content
                else -> null
            }
        }

        if (content != null) {
            updateAssistantMessage(content)
        }

        // Check for tool calls
        val toolCalls = update["toolCalls"]?.jsonArray
        toolCalls?.forEach { toolCallElement ->
            val toolCall = toolCallElement.jsonObject
            val name = toolCall["name"]?.jsonPrimitive?.content ?: "unknown"
            val status = toolCall["status"]?.jsonPrimitive?.content
            addToolCallToCurrentMessage(name, status)
        }

        // Check if generation is complete
        val finished = update["finished"]?.jsonPrimitive?.booleanOrNull
            ?: update["done"]?.jsonPrimitive?.booleanOrNull
        if (finished == true) {
            _isGenerating.value = false
            currentAssistantMessageId = null
        }
    }

    private fun handleSessionError(params: JsonObject) {
        val message = params["message"]?.jsonPrimitive?.content
            ?: params["error"]?.jsonPrimitive?.content
            ?: "Unknown error"
        updateAssistantMessage("Error: $message")
        _isGenerating.value = false
    }

    private fun updateAssistantMessage(content: String) {
        val msgId = currentAssistantMessageId ?: return
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) msg.copy(content = content) else msg
        }
    }

    private fun addToolCallToCurrentMessage(name: String, status: String?) {
        val msgId = currentAssistantMessageId ?: return
        _messages.value = _messages.value.map { msg ->
            if (msg.id == msgId) {
                val toolStatus = when (status) {
                    "running" -> ToolCallStatus.RUNNING
                    "complete", "success" -> ToolCallStatus.COMPLETE
                    "error" -> ToolCallStatus.ERROR
                    else -> ToolCallStatus.RUNNING
                }
                val existingTool = msg.toolCalls.find { it.name == name }
                val updatedTools = if (existingTool != null) {
                    msg.toolCalls.map { if (it.name == name) it.copy(status = toolStatus) else it }
                } else {
                    msg.toolCalls + ToolCall(name = name, status = toolStatus)
                }
                msg.copy(toolCalls = updatedTools)
            } else msg
        }
    }

    private fun addSystemMessage(text: String) {
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.SYSTEM,
            content = text
        )
        _messages.value = _messages.value + msg
    }

    override fun onCleared() {
        super.onCleared()
        acpClient?.disconnect()
    }
}
