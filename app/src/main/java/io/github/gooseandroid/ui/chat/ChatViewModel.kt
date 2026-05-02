package io.github.gooseandroid.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gooseandroid.GoosePortHolder
import io.github.gooseandroid.LocalModelManager
import io.github.gooseandroid.acp.AcpClient
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the chat screen.
 * Scoped to the activity lifecycle — survives navigation.
 *
 * Connects to either:
 * 1. goose serve (full backend with tools) via ACP WebSocket
 * 2. Local model inference (GGUF via LiteRT) for offline use
 * 3. Cloud LLM API directly (when goose binary isn't available)
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val settingsStore = SettingsStore(application)
    private val modelManager = LocalModelManager(application)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var acpClient: AcpClient? = null

    init {
        connectToGoose()
    }

    private fun connectToGoose() {
        viewModelScope.launch {
            if (GoosePortHolder.localOnlyMode) {
                Log.i(TAG, "Running in local-only mode")
                addSystemMessage(
                    "Welcome to Goose!\n\n" +
                    "The Goose backend is not installed yet. You can:\n\n" +
                    "- Configure a cloud API key in Settings\n" +
                    "- Download a local model in Settings > Local Models\n\n" +
                    "Once configured, type a message to start chatting."
                )
                return@launch
            }

            val port = GoosePortHolder.port
            val url = "ws://127.0.0.1:$port/acp"
            Log.i(TAG, "Connecting to goose at $url")

            val client = AcpClient(url)
            acpClient = client

            launch {
                client.notifications.collect { notification ->
                    handleAcpNotification(notification)
                }
            }

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
        // Add user message immediately
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text
        )
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            _isGenerating.value = true

            try {
                if (GoosePortHolder.localOnlyMode) {
                    // Use local model or cloud API directly
                    handleLocalMessage(text)
                } else {
                    // Use goose serve via ACP
                    val client = acpClient
                    if (client != null) {
                        client.sendPrompt(text)
                    } else {
                        addSystemMessage("Not connected to Goose. Check Settings.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                addSystemMessage("Error: ${e.message}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Handle message when in local-only mode.
     * Priority: Cloud API (if key set) > Local model > Error message
     */
    private suspend fun handleLocalMessage(text: String) {
        // Check for cloud API key first (better experience)
        val anthropicKey = settingsStore.getString(SettingsKeys.ANTHROPIC_API_KEY).first()
        val openaiKey = settingsStore.getString(SettingsKeys.OPENAI_API_KEY).first()
        val googleKey = settingsStore.getString(SettingsKeys.GOOGLE_API_KEY).first()

        when {
            anthropicKey.isNotBlank() -> {
                callCloudApi("anthropic", anthropicKey, text)
                return
            }
            openaiKey.isNotBlank() -> {
                callCloudApi("openai", openaiKey, text)
                return
            }
            googleKey.isNotBlank() -> {
                callCloudApi("google", googleKey, text)
                return
            }
        }

        // No cloud key — try local model
        val localModelId = settingsStore.getLocalModelId().first()
        val downloadedModels = modelManager.getDownloadedModels()
        val activeModel = downloadedModels.find { it.id == localModelId }

        if (activeModel != null) {
            val modelFile = modelManager.getModelFile(activeModel)
            if (modelFile.exists()) {
                // Call the LiteRT inference server
                callLocalModel(text, activeModel.id)
                return
            }
        }

        // Nothing configured
        addSystemMessage(
            "No model configured.\n\n" +
            "Go to Settings to either:\n" +
            "- Add a cloud API key (Anthropic, OpenAI, Google)\n" +
            "- Download a local model for offline use"
        )
    }

    /**
     * Call the local LiteRT inference server.
     */
    private suspend fun callLocalModel(text: String, modelId: String) {
        try {
            val port = GoosePortHolder.port.takeIf { it > 0 } ?: 11435
            val url = java.net.URL("http://127.0.0.1:$port/v1/chat/completions")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.doOutput = true

            val escapedText = text.replace("\"", "\\\"").replace("\n", "\\n")
            val body = """{"model":"$modelId","messages":[{"role":"user","content":"$escapedText"}]}"""
            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                addSystemMessage("Local model error: $error")
                return
            }

            val response = conn.inputStream.bufferedReader().readText()
            val contentMatch = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(response)
            val responseText = contentMatch?.groupValues?.get(1)
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: "No response from local model"

            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = responseText
            )
            _messages.value = _messages.value + msg
        } catch (e: Exception) {
            Log.e(TAG, "Local model call failed", e)
            addSystemMessage("Local model error: ${e.message}\n\nThe inference engine may still be loading.")
        }
    }

    /**
     * Direct cloud API call (without goose serve).
     * Simple completion — no tools, no MCP, just chat.
     */
    private suspend fun callCloudApi(provider: String, apiKey: String, text: String) {
        try {
            val response = when (provider) {
                "anthropic" -> callAnthropic(apiKey, text)
                "openai" -> callOpenAI(apiKey, text)
                "google" -> callGoogle(apiKey, text)
                else -> "Unknown provider"
            }
            val assistantMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = response
            )
            _messages.value = _messages.value + assistantMsg
        } catch (e: Exception) {
            addSystemMessage("API Error: ${e.message}")
        }
    }

    private suspend fun callAnthropic(apiKey: String, text: String): String {
        val url = java.net.URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true

        val body = """
            {"model":"claude-sonnet-4-20250514","max_tokens":4096,"messages":[{"role":"user","content":"$text"}]}
        """.trimIndent()

        conn.outputStream.use { it.write(body.toByteArray()) }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw Exception("Anthropic API error: $error")
        }

        val response = conn.inputStream.bufferedReader().readText()
        // Simple JSON parsing — extract content text
        val textMatch = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(response)
        return textMatch?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: "No response received"
    }

    private suspend fun callOpenAI(apiKey: String, text: String): String {
        val url = java.net.URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true

        val body = """
            {"model":"gpt-4o","messages":[{"role":"user","content":"$text"}]}
        """.trimIndent()

        conn.outputStream.use { it.write(body.toByteArray()) }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw Exception("OpenAI API error: $error")
        }

        val response = conn.inputStream.bufferedReader().readText()
        val contentMatch = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(response)
        return contentMatch?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: "No response received"
    }

    private suspend fun callGoogle(apiKey: String, text: String): String {
        val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = """
            {"contents":[{"parts":[{"text":"$text"}]}]}
        """.trimIndent()

        conn.outputStream.use { it.write(body.toByteArray()) }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw Exception("Google AI error: $error")
        }

        val response = conn.inputStream.bufferedReader().readText()
        val textMatch = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(response)
        return textMatch?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: "No response received"
    }

    /**
     * Pre-fill the chat with a recipe prompt (user can edit before sending).
     */
    fun prefillPrompt(prompt: String) {
        _pendingPrompt.value = prompt
    }

    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt.asStateFlow()

    fun clearPendingPrompt() {
        _pendingPrompt.value = null
    }

    fun cancelGeneration() {
        viewModelScope.launch {
            acpClient?.cancel()
            _isGenerating.value = false
        }
    }

    private fun handleAcpNotification(notification: io.github.gooseandroid.acp.AcpNotification) {
        when (notification.method) {
            "notifications/message" -> {
                val content = notification.params["content"]?.toString()?.removeSurrounding("\"") ?: return
                val msg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = content
                )
                _messages.value = _messages.value + msg
            }
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
}
