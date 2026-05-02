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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * ViewModel for the chat screen.
 * Scoped to the activity lifecycle -- survives navigation.
 *
 * Connects to either:
 * 1. goose serve (full backend with tools) via ACP WebSocket
 * 2. Local model inference (GGUF via LiteRT) for offline use
 * 3. Cloud LLM API directly (when goose binary is not available)
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

    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt.asStateFlow()

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
                addSystemMessage(
                    "Unable to connect to Goose backend: ${error.message}\n\n" +
                    "Go to Settings to configure a provider."
                )
            }
        }
    }

    fun sendMessage(text: String) {
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
                    handleLocalMessage(text)
                } else {
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
        val anthropicKey = settingsStore.getString(SettingsKeys.ANTHROPIC_API_KEY).first()
        val openaiKey = settingsStore.getString(SettingsKeys.OPENAI_API_KEY).first()
        val googleKey = settingsStore.getString(SettingsKeys.GOOGLE_API_KEY).first()

        // Build conversation history from all user/assistant messages
        val conversationMessages = buildConversationHistory()

        when {
            anthropicKey.isNotBlank() -> {
                callCloudApi("anthropic", anthropicKey, conversationMessages)
                return
            }
            openaiKey.isNotBlank() -> {
                callCloudApi("openai", openaiKey, conversationMessages)
                return
            }
            googleKey.isNotBlank() -> {
                callCloudApi("google", googleKey, conversationMessages)
                return
            }
        }

        // No cloud key -- try local model
        val localModelId = settingsStore.getLocalModelId().first()
        val downloadedModels = modelManager.getDownloadedModels()
        val activeModel = downloadedModels.find { it.id == localModelId }

        if (activeModel != null) {
            val modelFile = modelManager.getModelFile(activeModel)
            if (modelFile.exists()) {
                callLocalModel(text, activeModel.id)
                return
            }
        }

        addSystemMessage(
            "No model configured.\n\n" +
            "Go to Settings to either:\n" +
            "- Add a cloud API key (Anthropic, OpenAI, Google)\n" +
            "- Download a local model for offline use"
        )
    }

    /**
     * Build the conversation history as a list of role/content pairs,
     * filtering out system messages (which are UI-only notifications).
     */
    private fun buildConversationHistory(): List<Pair<String, String>> {
        return _messages.value
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .map { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> "user"
                }
                role to msg.content
            }
    }

    /**
     * Direct cloud API call (without goose serve).
     * Simple completion -- no tools, no MCP, just chat with full conversation history.
     */
    private suspend fun callCloudApi(
        provider: String,
        apiKey: String,
        messages: List<Pair<String, String>>
    ) {
        try {
            val response = when (provider) {
                "anthropic" -> callAnthropic(apiKey, messages)
                "openai" -> callOpenAI(apiKey, messages)
                "google" -> callGoogle(apiKey, messages)
                else -> "Unknown provider"
            }
            val assistantMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = response
            )
            _messages.value = _messages.value + assistantMsg
        } catch (e: Exception) {
            Log.e(TAG, "Cloud API call failed ($provider)", e)
            addSystemMessage("API Error ($provider): ${e.message}")
        }
    }

    private suspend fun callAnthropic(
        apiKey: String,
        messages: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()
        for ((role, content) in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", role)
            msgObj.put("content", content)
            messagesArray.put(msgObj)
        }

        val body = JSONObject()
        body.put("model", "claude-sonnet-4-20250514")
        body.put("max_tokens", 4096)
        body.put("messages", messagesArray)

        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.doOutput = true

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                throw Exception("Anthropic API error ($responseCode): $error")
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val contentArray = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                if (block.getString("type") == "text") {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(block.getString("text"))
                }
            }
            sb.toString().ifEmpty { "No response received" }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun callOpenAI(
        apiKey: String,
        messages: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()
        for ((role, content) in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", role)
            msgObj.put("content", content)
            messagesArray.put(msgObj)
        }

        val body = JSONObject()
        body.put("model", "gpt-4o")
        body.put("messages", messagesArray)

        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.doOutput = true

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                throw Exception("OpenAI API error ($responseCode): $error")
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) return@withContext "No response received"
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.getJSONObject("message")
            message.getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun callGoogle(
        apiKey: String,
        messages: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        // Google Gemini uses a different format: contents with parts
        val contentsArray = JSONArray()
        for ((role, content) in messages) {
            val contentObj = JSONObject()
            // Gemini uses "user" and "model" as role names
            val geminiRole = if (role == "assistant") "model" else "user"
            contentObj.put("role", geminiRole)

            val partsArray = JSONArray()
            val partObj = JSONObject()
            partObj.put("text", content)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)

            contentsArray.put(contentObj)
        }

        val body = JSONObject()
        body.put("contents", contentsArray)

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.doOutput = true

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                throw Exception("Google AI error ($responseCode): $error")
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() == 0) return@withContext "No response received"
            val firstCandidate = candidates.getJSONObject(0)
            val contentObj = firstCandidate.getJSONObject("content")
            val parts = contentObj.getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(part.getString("text"))
                }
            }
            sb.toString().ifEmpty { "No response received" }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Call the local LiteRT inference server using OpenAI-compatible endpoint.
     */
    private suspend fun callLocalModel(text: String, modelId: String) {
        try {
            val response = withContext(Dispatchers.IO) {
                val port = GoosePortHolder.port.takeIf { it > 0 } ?: 11435
                val url = URL("http://127.0.0.1:$port/v1/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 60_000
                    conn.readTimeout = 120_000
                    conn.doOutput = true

                    val messagesArray = JSONArray()
                    val userMsg = JSONObject()
                    userMsg.put("role", "user")
                    userMsg.put("content", text)
                    messagesArray.put(userMsg)

                    val body = JSONObject()
                    body.put("model", modelId)
                    body.put("messages", messagesArray)

                    conn.outputStream.use { it.write(body.toString().toByteArray()) }

                    val responseCode = conn.responseCode
                    if (responseCode != 200) {
                        val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                        throw Exception("Local model error ($responseCode): $error")
                    }

                    val responseBody = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() == 0) return@withContext "No response from local model"
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.getJSONObject("message")
                    message.getString("content")
                } finally {
                    conn.disconnect()
                }
            }

            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = response
            )
            _messages.value = _messages.value + msg
        } catch (e: Exception) {
            Log.e(TAG, "Local model call failed", e)
            addSystemMessage(
                "Local model error: ${e.message}\n\n" +
                "The inference engine may still be loading."
            )
        }
    }

    /**
     * Pre-fill the chat with a recipe prompt (user can edit before sending).
     */
    fun prefillPrompt(prompt: String) {
        _pendingPrompt.value = prompt
    }

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
                val content = notification.params["content"]?.toString()
                    ?.removeSurrounding("\"") ?: return
                val msg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = content
                )
                _messages.value = _messages.value + msg
            }
        }
    }

    fun addSystemMessage(text: String) {
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.SYSTEM,
            content = text
        )
        _messages.value = _messages.value + msg
    }
}
