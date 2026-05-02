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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * ViewModel for the chat screen — the brain of the Goose AI Android app.
 *
 * Features:
 * - Streaming responses (SSE for cloud APIs, ACP notifications for goose serve)
 * - Multiple sessions with persistence
 * - Tool call tracking from ACP notifications
 * - Support for Anthropic, OpenAI, Google, Mistral, OpenRouter, custom endpoints
 * - Model selection per provider
 * - Auto-title generation from first user message
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SESSIONS_FILE = "sessions.json"

        val PROVIDER_MODELS = mapOf(
            "anthropic" to listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022"),
            "openai" to listOf("gpt-4o", "gpt-4o-mini", "o3-mini"),
            "google" to listOf("gemini-2.0-flash", "gemini-2.5-pro-preview-06-05"),
            "mistral" to listOf("mistral-large-latest", "mistral-small-latest"),
            "openrouter" to listOf(
                "anthropic/claude-sonnet-4-20250514",
                "openai/gpt-4o",
                "google/gemini-2.0-flash-001"
            )
        )
    }

    private val appContext = application.applicationContext
    private val settingsStore = SettingsStore(application)
    private val modelManager = LocalModelManager(application)

    // ─── State Flows ────────────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _currentSessionTitle = MutableStateFlow("")
    val currentSessionTitle: StateFlow<String> = _currentSessionTitle.asStateFlow()

    private val _toolCalls = MutableStateFlow<List<ToolCall>>(emptyList())
    val toolCalls: StateFlow<List<ToolCall>> = _toolCalls.asStateFlow()

    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt.asStateFlow()

    private var acpClient: AcpClient? = null
    private var streamingJob: Job? = null

    // ─── Initialization ─────────────────────────────────────────────────────────

    init {
        loadSessions()
        connectToGoose()
    }

    private fun connectToGoose() {
        viewModelScope.launch {
            if (GoosePortHolder.localOnlyMode) {
                Log.i(TAG, "Running in local-only mode")
                // Create a default session if none exist
                if (_sessions.value.isEmpty()) {
                    createNewSession()
                } else {
                    // Activate the most recent session
                    val latest = _sessions.value.maxByOrNull { it.createdAt }
                    latest?.let { switchSession(it.id) }
                }
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
                createNewSession()
                val sessionResult = client.newSession()
                sessionResult.onSuccess { sessionId ->
                    Log.i(TAG, "ACP Session created: $sessionId")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to create ACP session", error)
                    addSystemMessage("Failed to create session: ${error.message}")
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to connect to goose", error)
                if (_sessions.value.isEmpty()) createNewSession()
                addSystemMessage(
                    "Unable to connect to Goose backend: ${error.message}\n\n" +
                    "Go to Settings to configure a provider."
                )
            }
        }
    }

    // ─── Session Management ─────────────────────────────────────────────────────

    fun createNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        val provider = runBlocking { settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first() }
        val model = runBlocking { settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first() }

        val session = SessionInfo(
            id = sessionId,
            title = "New Chat",
            createdAt = System.currentTimeMillis(),
            messageCount = 0,
            lastMessage = "",
            providerId = provider,
            modelId = model
        )

        _sessions.value = _sessions.value + session
        _currentSessionId.value = sessionId
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        saveSessions()
        return sessionId
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _currentSessionId.value) return
        // Save current state before switching
        updateCurrentSessionMetadata()

        _currentSessionId.value = sessionId
        // Clear current messages (in a full impl, messages would be persisted per session)
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        _isGenerating.value = false
        streamingJob?.cancel()
    }

    fun deleteSession(sessionId: String) {
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        if (_currentSessionId.value == sessionId) {
            val remaining = _sessions.value
            if (remaining.isNotEmpty()) {
                switchSession(remaining.last().id)
            } else {
                createNewSession()
            }
        }
        saveSessions()
    }

    private fun updateCurrentSessionMetadata() {
        val currentId = _currentSessionId.value ?: return
        val msgs = _messages.value
        val lastUserMsg = msgs.lastOrNull { it.role == MessageRole.USER }?.content ?: ""

        _sessions.value = _sessions.value.map { session ->
            if (session.id == currentId) {
                session.copy(
                    messageCount = msgs.size,
                    lastMessage = lastUserMsg.take(100)
                )
            } else session
        }
        saveSessions()
    }

    // ─── Session Persistence ────────────────────────────────────────────────────

    private fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(appContext.filesDir, SESSIONS_FILE)
                if (!file.exists()) return@launch

                val jsonStr = file.readText()
                val jsonArray = JSONArray(jsonStr)
                val loaded = mutableListOf<SessionInfo>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    loaded.add(
                        SessionInfo(
                            id = obj.getString("id"),
                            title = obj.optString("title", "Untitled"),
                            createdAt = obj.optLong("createdAt", 0L),
                            messageCount = obj.optInt("messageCount", 0),
                            lastMessage = obj.optString("lastMessage", ""),
                            providerId = obj.optString("providerId", ""),
                            modelId = obj.optString("modelId", "")
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    _sessions.value = loaded
                    // Activate the most recent session
                    if (loaded.isNotEmpty() && _currentSessionId.value == null) {
                        val latest = loaded.maxByOrNull { it.createdAt }
                        latest?.let { _currentSessionId.value = it.id }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sessions", e)
            }
        }
    }

    private fun saveSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                for (session in _sessions.value) {
                    val obj = JSONObject()
                    obj.put("id", session.id)
                    obj.put("title", session.title)
                    obj.put("createdAt", session.createdAt)
                    obj.put("messageCount", session.messageCount)
                    obj.put("lastMessage", session.lastMessage)
                    obj.put("providerId", session.providerId)
                    obj.put("modelId", session.modelId)
                    jsonArray.put(obj)
                }

                val file = File(appContext.filesDir, SESSIONS_FILE)
                file.writeText(jsonArray.toString(2))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save sessions", e)
            }
        }
    }

    // ─── Sending Messages ───────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (_currentSessionId.value == null) createNewSession()

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text
        )
        _messages.value = _messages.value + userMsg

        // Auto-title: generate title from first user message
        autoTitleIfNeeded(text)

        viewModelScope.launch {
            _isGenerating.value = true
            _streamingContent.value = ""

            try {
                if (!GoosePortHolder.localOnlyMode && acpClient != null) {
                    // Use ACP (goose serve) — responses stream via notifications
                    acpClient!!.sendPrompt(text)
                } else {
                    handleLocalMessage(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                addSystemMessage("Error: ${e.message}")
                _isGenerating.value = false
            }
        }
    }

    /**
     * Handle message when in local-only mode.
     * Priority: Active provider > any configured key > local model > error
     */
    private suspend fun handleLocalMessage(text: String) {
        val activeProvider = settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first()
        val activeModel = settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first()

        val conversationMessages = buildConversationHistory()

        // If an active provider is explicitly set, use it
        if (activeProvider.isNotBlank()) {
            val apiKey = getApiKeyForProvider(activeProvider)
            if (apiKey.isNotBlank() || activeProvider == "custom" || activeProvider == "local") {
                callCloudApiStreaming(activeProvider, apiKey, activeModel, conversationMessages)
                return
            }
        }

        // Fallback: try providers in order
        val anthropicKey = settingsStore.getString(SettingsKeys.ANTHROPIC_API_KEY).first()
        val openaiKey = settingsStore.getString(SettingsKeys.OPENAI_API_KEY).first()
        val googleKey = settingsStore.getString(SettingsKeys.GOOGLE_API_KEY).first()
        val mistralKey = settingsStore.getString(SettingsKeys.MISTRAL_API_KEY).first()
        val openrouterKey = settingsStore.getString(SettingsKeys.OPENROUTER_API_KEY).first()

        when {
            anthropicKey.isNotBlank() -> {
                callCloudApiStreaming("anthropic", anthropicKey, "claude-sonnet-4-20250514", conversationMessages)
                return
            }
            openaiKey.isNotBlank() -> {
                callCloudApiStreaming("openai", openaiKey, "gpt-4o", conversationMessages)
                return
            }
            googleKey.isNotBlank() -> {
                callCloudApiStreaming("google", googleKey, "gemini-2.0-flash", conversationMessages)
                return
            }
            mistralKey.isNotBlank() -> {
                callCloudApiStreaming("mistral", mistralKey, "mistral-large-latest", conversationMessages)
                return
            }
            openrouterKey.isNotBlank() -> {
                callCloudApiStreaming("openrouter", openrouterKey, "anthropic/claude-sonnet-4-20250514", conversationMessages)
                return
            }
        }

        // No cloud key — try local model
        val localModelId = settingsStore.getLocalModelId().first()
        val downloadedModels = modelManager.getDownloadedModels()
        val localModel = downloadedModels.find { it.id == localModelId }

        if (localModel != null) {
            val modelFile = modelManager.getModelFile(localModel)
            if (modelFile.exists()) {
                callLocalModel(text, localModel.id)
                return
            }
        }

        addSystemMessage(
            "No model configured.\n\n" +
            "Go to Settings to either:\n" +
            "- Add a cloud API key (Anthropic, OpenAI, Google, Mistral, OpenRouter)\n" +
            "- Configure a custom OpenAI-compatible endpoint\n" +
            "- Download a local model for offline use"
        )
        _isGenerating.value = false
    }

    private suspend fun getApiKeyForProvider(provider: String): String {
        return when (provider) {
            "anthropic" -> settingsStore.getString(SettingsKeys.ANTHROPIC_API_KEY).first()
            "openai" -> settingsStore.getString(SettingsKeys.OPENAI_API_KEY).first()
            "google" -> settingsStore.getString(SettingsKeys.GOOGLE_API_KEY).first()
            "mistral" -> settingsStore.getString(SettingsKeys.MISTRAL_API_KEY).first()
            "openrouter" -> settingsStore.getString(SettingsKeys.OPENROUTER_API_KEY).first()
            "custom" -> settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_KEY).first()
            else -> ""
        }
    }

    // ─── Streaming Cloud API Calls ──────────────────────────────────────────────

    /**
     * Unified streaming call dispatcher. Adds an empty assistant message,
     * streams tokens into it, and finalizes on completion.
     */
    private suspend fun callCloudApiStreaming(
        provider: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>
    ) {
        // Add empty assistant message placeholder
        val assistantId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = ""
        )
        _messages.value = _messages.value + placeholder
        _streamingContent.value = ""

        try {
            val resolvedModel = model.ifBlank { getDefaultModel(provider) }

            when (provider) {
                "anthropic" -> streamAnthropic(apiKey, resolvedModel, messages, assistantId)
                "openai" -> streamOpenAI(apiKey, resolvedModel, messages, assistantId,
                    "https://api.openai.com/v1/chat/completions", emptyMap())
                "google" -> streamGoogle(apiKey, resolvedModel, messages, assistantId)
                "mistral" -> streamOpenAI(apiKey, resolvedModel, messages, assistantId,
                    "https://api.mistral.ai/v1/chat/completions", emptyMap())
                "openrouter" -> streamOpenAI(apiKey, resolvedModel, messages, assistantId,
                    "https://openrouter.ai/api/v1/chat/completions",
                    mapOf("HTTP-Referer" to "https://github.com/MaxFlynn13/goose-android"))
                "custom" -> {
                    val baseUrl = settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_URL).first()
                    val customModel = settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_MODEL).first()
                    val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions"
                              else if (baseUrl.contains("/chat/completions")) baseUrl
                              else "$baseUrl/v1/chat/completions"
                    streamOpenAI(apiKey, customModel.ifBlank { resolvedModel }, messages, assistantId, url, emptyMap())
                }
                else -> {
                    addSystemMessage("Unknown provider: $provider")
                    _isGenerating.value = false
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming API call failed ($provider)", e)
            // Update the placeholder with error
            finalizeStreamingMessage(assistantId, _streamingContent.value.ifBlank {
                "Error: ${e.message}"
            })
            if (_streamingContent.value.isBlank()) {
                addSystemMessage("API Error ($provider): ${e.message}")
            }
        } finally {
            _isGenerating.value = false
            updateCurrentSessionMetadata()
        }
    }

    private fun getDefaultModel(provider: String): String {
        return PROVIDER_MODELS[provider]?.firstOrNull() ?: "gpt-4o"
    }

    /**
     * Stream from Anthropic Messages API with SSE.
     */
    private suspend fun streamAnthropic(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        assistantId: String
    ) = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()
        for ((role, content) in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", role)
            msgObj.put("content", content)
            messagesArray.put(msgObj)
        }

        val body = JSONObject()
        body.put("model", model)
        body.put("max_tokens", 4096)
        body.put("stream", true)
        body.put("messages", messagesArray)

        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
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

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val accumulated = StringBuilder()

            reader.useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val event = JSONObject(data)
                        val type = event.optString("type", "")

                        when (type) {
                            "content_block_delta" -> {
                                val delta = event.optJSONObject("delta")
                                val text = delta?.optString("text", "") ?: ""
                                if (text.isNotEmpty()) {
                                    accumulated.append(text)
                                    _streamingContent.value = accumulated.toString()
                                    updateStreamingMessage(assistantId, accumulated.toString())
                                }
                            }
                            "message_stop" -> break
                            "error" -> {
                                val errorObj = event.optJSONObject("error")
                                val errorMsg = errorObj?.optString("message", "Unknown error") ?: "Unknown error"
                                throw Exception("Stream error: $errorMsg")
                            }
                        }
                    } catch (e: org.json.JSONException) {
                        // Skip malformed events
                        Log.w(TAG, "Skipping malformed SSE event: ${data.take(100)}")
                    }
                }
            }

            finalizeStreamingMessage(assistantId, accumulated.toString())
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Stream from OpenAI-compatible API (also used for Mistral, OpenRouter, custom).
     */
    private suspend fun streamOpenAI(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        assistantId: String,
        endpoint: String,
        extraHeaders: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()
        for ((role, content) in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", role)
            msgObj.put("content", content)
            messagesArray.put(msgObj)
        }

        val body = JSONObject()
        body.put("model", model)
        body.put("stream", true)
        body.put("messages", messagesArray)

        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            for ((key, value) in extraHeaders) {
                conn.setRequestProperty(key, value)
            }
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.doOutput = true

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                throw Exception("API error ($responseCode): $error")
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val accumulated = StringBuilder()

            reader.useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val event = JSONObject(data)
                        val choices = event.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue

                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta") ?: continue
                        val content = delta.optString("content", "")

                        if (content.isNotEmpty()) {
                            accumulated.append(content)
                            _streamingContent.value = accumulated.toString()
                            updateStreamingMessage(assistantId, accumulated.toString())
                        }

                        // Check for finish
                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason == "stop" || finishReason == "end_turn") break
                    } catch (e: org.json.JSONException) {
                        Log.w(TAG, "Skipping malformed SSE event: ${data.take(100)}")
                    }
                }
            }

            finalizeStreamingMessage(assistantId, accumulated.toString())
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Stream from Google Gemini API with SSE.
     */
    private suspend fun streamGoogle(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        assistantId: String
    ) = withContext(Dispatchers.IO) {
        val contentsArray = JSONArray()
        for ((role, content) in messages) {
            val contentObj = JSONObject()
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
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.doOutput = true

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                throw Exception("Google AI error ($responseCode): $error")
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val accumulated = StringBuilder()

            reader.useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]" || data.isEmpty()) continue

                    try {
                        val event = JSONObject(data)
                        val candidates = event.optJSONArray("candidates") ?: continue
                        if (candidates.length() == 0) continue

                        val candidate = candidates.getJSONObject(0)
                        val contentObj = candidate.optJSONObject("content") ?: continue
                        val parts = contentObj.optJSONArray("parts") ?: continue

                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            val text = part.optString("text", "")
                            if (text.isNotEmpty()) {
                                accumulated.append(text)
                                _streamingContent.value = accumulated.toString()
                                updateStreamingMessage(assistantId, accumulated.toString())
                            }
                        }
                    } catch (e: org.json.JSONException) {
                        Log.w(TAG, "Skipping malformed SSE event: ${data.take(100)}")
                    }
                }
            }

            finalizeStreamingMessage(assistantId, accumulated.toString())
        } finally {
            conn.disconnect()
        }
    }

    // ─── Streaming Helpers ──────────────────────────────────────────────────────

    private fun updateStreamingMessage(assistantId: String, content: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == assistantId) msg.copy(content = content) else msg
        }
    }

    private fun finalizeStreamingMessage(assistantId: String, content: String) {
        val finalContent = content.ifBlank { "No response received" }
        _messages.value = _messages.value.map { msg ->
            if (msg.id == assistantId) msg.copy(content = finalContent) else msg
        }
        _streamingContent.value = ""
    }

    // ─── Local Model ────────────────────────────────────────────────────────────

    /**
     * Call the local LiteRT inference server using OpenAI-compatible endpoint.
     */
    private suspend fun callLocalModel(text: String, modelId: String) {
        val assistantId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = ""
        )
        _messages.value = _messages.value + placeholder

        try {
            val response = withContext(Dispatchers.IO) {
                val port = GoosePortHolder.port.takeIf { it > 0 } ?: 11435
                val localUrl = URL("http://127.0.0.1:$port/v1/chat/completions")
                val conn = localUrl.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 60_000
                    conn.readTimeout = 120_000
                    conn.doOutput = true

                    val messagesArray = JSONArray()
                    // Include conversation history for local model
                    for ((role, content) in buildConversationHistory()) {
                        val msgObj = JSONObject()
                        msgObj.put("role", role)
                        msgObj.put("content", content)
                        messagesArray.put(msgObj)
                    }

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

            finalizeStreamingMessage(assistantId, response)
        } catch (e: Exception) {
            Log.e(TAG, "Local model call failed", e)
            finalizeStreamingMessage(assistantId, "")
            addSystemMessage(
                "Local model error: ${e.message}\n\n" +
                "The inference engine may still be loading."
            )
        } finally {
            _isGenerating.value = false
            updateCurrentSessionMetadata()
        }
    }

    // ─── Conversation History ───────────────────────────────────────────────────

    /**
     * Build the conversation history as a list of role/content pairs,
     * filtering out system messages (which are UI-only notifications).
     */
    private fun buildConversationHistory(): List<Pair<String, String>> {
        return _messages.value
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
            .map { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> "user"
                }
                role to msg.content
            }
    }

    // ─── ACP Notification Handling ──────────────────────────────────────────────

    private fun handleAcpNotification(notification: io.github.gooseandroid.acp.AcpNotification) {
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
     * Accumulates into the current assistant message or creates a new one.
     */
    private fun handleAcpStreamingMessage(params: JsonObject) {
        val content = params["content"]?.jsonPrimitive?.contentOrNull ?: return

        val currentMessages = _messages.value
        val lastMsg = currentMessages.lastOrNull()

        if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && _isGenerating.value) {
            // Append to existing streaming message
            val updated = lastMsg.copy(content = lastMsg.content + content)
            _messages.value = currentMessages.dropLast(1) + updated
            _streamingContent.value = updated.content
        } else {
            // Create new assistant message
            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = content
            )
            _messages.value = currentMessages + msg
            _streamingContent.value = content
        }

        // Check if this is the final message (complete flag)
        val isComplete = params["complete"]?.jsonPrimitive?.booleanOrNull ?: false
        if (isComplete) {
            _isGenerating.value = false
            _streamingContent.value = ""
            updateCurrentSessionMetadata()
        }
    }

    private fun handleToolCallStart(params: JsonObject) {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val arguments = params["arguments"]?.toString()

        val toolCall = ToolCall(
            name = name,
            status = ToolCallStatus.RUNNING,
            input = arguments ?: ""
        )
        _toolCalls.value = _toolCalls.value + toolCall

        // Also update the last assistant message's tool calls
        updateLastAssistantToolCalls()
    }

    private fun handleToolCallEnd(params: JsonObject) {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val isError = params["error"]?.jsonPrimitive?.booleanOrNull ?: false
        val output = params["result"]?.jsonPrimitive?.contentOrNull ?: ""

        val newStatus = if (isError) ToolCallStatus.ERROR else ToolCallStatus.COMPLETE

        _toolCalls.value = _toolCalls.value.map { tc ->
            if (tc.name == name && tc.status == ToolCallStatus.RUNNING) {
                tc.copy(status = newStatus, output = output)
            } else tc
        }

        updateLastAssistantToolCalls()
    }

    private fun updateLastAssistantToolCalls() {
        val currentMessages = _messages.value
        val lastAssistant = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return

        val updated = lastAssistant.copy(toolCalls = _toolCalls.value.toList())
        _messages.value = currentMessages.map { if (it.id == lastAssistant.id) updated else it }
    }

    // ─── Auto-Title Generation ──────────────────────────────────────────────────

    private fun autoTitleIfNeeded(firstMessage: String) {
        val currentId = _currentSessionId.value ?: return
        val session = _sessions.value.find { it.id == currentId } ?: return

        // Only auto-title if it's still the default "New Chat" and this is the first user message
        if (session.title != "New Chat") return
        val userMessages = _messages.value.count { it.role == MessageRole.USER }
        if (userMessages > 1) return // Only on first message (the one we just added)

        val title = generateTitle(firstMessage)
        _sessions.value = _sessions.value.map {
            if (it.id == currentId) it.copy(title = title) else it
        }
        saveSessions()
    }

    /**
     * Generate a short title from the first user message.
     * Simple heuristic: take first sentence or first N words.
     */
    private fun generateTitle(message: String): String {
        val cleaned = message.trim().lines().first().trim()

        // If short enough, use as-is
        if (cleaned.length <= 40) return cleaned

        // Try to find a natural break point
        val sentenceEnd = cleaned.indexOfFirst { it == '.' || it == '?' || it == '!' }
        if (sentenceEnd in 1..60) {
            return cleaned.substring(0, sentenceEnd + 1)
        }

        // Take first ~40 chars at a word boundary
        val truncated = cleaned.take(40)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > 20) {
            truncated.substring(0, lastSpace) + "…"
        } else {
            truncated + "…"
        }
    }

    // ─── Public API ─────────────────────────────────────────────────────────────

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
            streamingJob?.cancel()
            acpClient?.cancel()
            _isGenerating.value = false
            _streamingContent.value = ""
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        updateCurrentSessionMetadata()
    }

    fun renameSession(sessionId: String, newTitle: String) {
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(title = newTitle) else it
        }
        saveSessions()
    }

    fun addSystemMessage(text: String) {
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.SYSTEM,
            content = text
        )
        _messages.value = _messages.value + msg
    }

    /**
     * Get available models for a given provider.
     */
    fun getModelsForProvider(provider: String): List<String> {
        return PROVIDER_MODELS[provider] ?: emptyList()
    }

    /**
     * Set the active provider and model.
     */
    fun setActiveProviderAndModel(provider: String, model: String) {
        viewModelScope.launch {
            settingsStore.setString(SettingsKeys.ACTIVE_PROVIDER, provider)
            settingsStore.setString(SettingsKeys.ACTIVE_MODEL, model)

            // Update current session metadata
            val currentId = _currentSessionId.value ?: return@launch
            _sessions.value = _sessions.value.map {
                if (it.id == currentId) it.copy(providerId = provider, modelId = model) else it
            }
            saveSessions()
        }
    }

    override fun onCleared() {
        super.onCleared()
        updateCurrentSessionMetadata()
        acpClient?.disconnect()
    }

    // ─── Utility ────────────────────────────────────────────────────────────────

    /**
     * Blocking read for initialization only. Use sparingly.
     */
    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
