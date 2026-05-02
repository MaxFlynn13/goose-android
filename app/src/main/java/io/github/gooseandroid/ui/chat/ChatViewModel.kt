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
 * - Session fork/duplicate
 * - File attachment handling (text + images)
 * - Compact conversation command
 * - Session export to JSON
 * - Token count estimation
 * - System prompt from active persona included in ALL API calls
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SESSIONS_FILE = "sessions.json"
        private const val MESSAGES_DIR = "session_messages"

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

    /**
     * Estimated token count for the current conversation.
     * Uses a simple heuristic: content.length / 4.
     */
    val tokenCount: StateFlow<Int> = _messages
        .map { msgs ->
            msgs.sumOf { it.content.length } / 4
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private var acpClient: AcpClient? = null
    private var streamingJob: Job? = null
    private var activeSystemPrompt: String = ""

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
        // Save current messages before switching
        saveCurrentSessionMessages()
        updateCurrentSessionMetadata()

        _currentSessionId.value = sessionId
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        _isGenerating.value = false
        streamingJob?.cancel()

        // Load messages for the target session
        loadSessionMessages(sessionId)
    }

    fun deleteSession(sessionId: String) {
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        // Delete persisted messages
        deleteSessionMessages(sessionId)
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

    fun renameSession(sessionId: String, newTitle: String) {
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(title = newTitle) else it
        }
        saveSessions()
    }

    /**
     * Fork (duplicate) a session: creates a new session with copies of all messages
     * from the source session.
     *
     * @param sessionId The source session to fork from.
     * @return The new session ID.
     */
    fun forkSession(sessionId: String): String {
        // Save current session state first
        saveCurrentSessionMessages()
        updateCurrentSessionMetadata()

        // Load source session messages from disk
        val sourceMessages = loadMessagesFromDisk(sessionId)

        // If the source is the current session and disk was empty, use in-memory messages
        val messagesToCopy = if (sourceMessages.isEmpty() && sessionId == _currentSessionId.value) {
            _messages.value
        } else {
            sourceMessages
        }

        // Find the source session metadata
        val sourceSession = _sessions.value.find { it.id == sessionId }

        // Create the new session
        val newSessionId = UUID.randomUUID().toString()
        val provider = runBlocking { settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first() }
        val model = runBlocking { settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first() }

        val newSession = SessionInfo(
            id = newSessionId,
            title = (sourceSession?.title ?: "New Chat") + " (fork)",
            createdAt = System.currentTimeMillis(),
            messageCount = messagesToCopy.size,
            lastMessage = messagesToCopy.lastOrNull { it.role == MessageRole.USER }?.content?.take(100) ?: "",
            providerId = sourceSession?.providerId ?: provider,
            modelId = sourceSession?.modelId ?: model
        )

        _sessions.value = _sessions.value + newSession

        // Copy messages with new IDs
        val copiedMessages = messagesToCopy.map { msg ->
            msg.copy(id = UUID.randomUUID().toString())
        }

        // Save the copied messages to disk for the new session
        saveMessagesToDisk(newSessionId, copiedMessages)

        // Switch to the new session
        _currentSessionId.value = newSessionId
        _messages.value = copiedMessages
        _toolCalls.value = emptyList()
        _streamingContent.value = ""

        saveSessions()
        return newSessionId
    }

    /**
     * Export a session's messages as a JSON string.
     *
     * @param sessionId The session to export.
     * @return JSON string of the session's messages.
     */
    fun exportSession(sessionId: String): String {
        // Get messages: either from memory (if current) or from disk
        val messagesToExport = if (sessionId == _currentSessionId.value) {
            _messages.value
        } else {
            loadMessagesFromDisk(sessionId)
        }

        val sessionInfo = _sessions.value.find { it.id == sessionId }

        val rootObj = JSONObject()
        rootObj.put("sessionId", sessionId)
        rootObj.put("title", sessionInfo?.title ?: "Unknown")
        rootObj.put("createdAt", sessionInfo?.createdAt ?: 0L)
        rootObj.put("exportedAt", System.currentTimeMillis())
        rootObj.put("providerId", sessionInfo?.providerId ?: "")
        rootObj.put("modelId", sessionInfo?.modelId ?: "")

        val messagesArray = JSONArray()
        for (msg in messagesToExport) {
            val msgObj = JSONObject()
            msgObj.put("id", msg.id)
            msgObj.put("role", msg.role.name)
            msgObj.put("content", msg.content)
            msgObj.put("timestamp", msg.timestamp)

            if (msg.toolCalls.isNotEmpty()) {
                val toolCallsArray = JSONArray()
                for (tc in msg.toolCalls) {
                    val tcObj = JSONObject()
                    tcObj.put("id", tc.id)
                    tcObj.put("name", tc.name)
                    tcObj.put("status", tc.status.name)
                    tcObj.put("input", tc.input)
                    tcObj.put("output", tc.output)
                    toolCallsArray.put(tcObj)
                }
                msgObj.put("toolCalls", toolCallsArray)
            }

            messagesArray.put(msgObj)
        }

        rootObj.put("messages", messagesArray)
        return rootObj.toString(2)
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
                        latest?.let {
                            _currentSessionId.value = it.id
                            loadSessionMessages(it.id)
                        }
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

    // ─── Per-Session Message Persistence ────────────────────────────────────────

    private fun getMessagesDir(): File {
        val dir = File(appContext.filesDir, MESSAGES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveCurrentSessionMessages() {
        val sessionId = _currentSessionId.value ?: return
        val msgs = _messages.value
        saveMessagesToDisk(sessionId, msgs)
    }

    private fun saveMessagesToDisk(sessionId: String, messages: List<ChatMessage>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                for (msg in messages) {
                    val msgObj = JSONObject()
                    msgObj.put("id", msg.id)
                    msgObj.put("role", msg.role.name)
                    msgObj.put("content", msg.content)
                    msgObj.put("timestamp", msg.timestamp)

                    if (msg.toolCalls.isNotEmpty()) {
                        val toolCallsArray = JSONArray()
                        for (tc in msg.toolCalls) {
                            val tcObj = JSONObject()
                            tcObj.put("id", tc.id)
                            tcObj.put("name", tc.name)
                            tcObj.put("status", tc.status.name)
                            tcObj.put("input", tc.input)
                            tcObj.put("output", tc.output)
                            toolCallsArray.put(tcObj)
                        }
                        msgObj.put("toolCalls", toolCallsArray)
                    }

                    jsonArray.put(msgObj)
                }

                val file = File(getMessagesDir(), "$sessionId.json")
                file.writeText(jsonArray.toString(2))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save messages for session $sessionId", e)
            }
        }
    }

    private fun loadSessionMessages(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadMessagesFromDisk(sessionId)
            withContext(Dispatchers.Main) {
                _messages.value = loaded
            }
        }
    }

    private fun loadMessagesFromDisk(sessionId: String): List<ChatMessage> {
        return try {
            val file = File(getMessagesDir(), "$sessionId.json")
            if (!file.exists()) return emptyList()

            val jsonStr = file.readText()
            val jsonArray = JSONArray(jsonStr)
            val result = mutableListOf<ChatMessage>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val role = try {
                    MessageRole.valueOf(obj.getString("role"))
                } catch (e: Exception) {
                    MessageRole.SYSTEM
                }

                val toolCalls = mutableListOf<ToolCall>()
                val tcArray = obj.optJSONArray("toolCalls")
                if (tcArray != null) {
                    for (j in 0 until tcArray.length()) {
                        val tcObj = tcArray.getJSONObject(j)
                        toolCalls.add(
                            ToolCall(
                                id = tcObj.optString("id", ""),
                                name = tcObj.optString("name", "unknown"),
                                status = try {
                                    ToolCallStatus.valueOf(tcObj.getString("status"))
                                } catch (e: Exception) {
                                    ToolCallStatus.COMPLETE
                                },
                                input = tcObj.optString("input", ""),
                                output = tcObj.optString("output", "")
                            )
                        )
                    }
                }

                result.add(
                    ChatMessage(
                        id = obj.getString("id"),
                        role = role,
                        content = obj.optString("content", ""),
                        toolCalls = toolCalls,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages for session $sessionId", e)
            emptyList()
        }
    }

    private fun deleteSessionMessages(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(getMessagesDir(), "$sessionId.json")
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete messages for session $sessionId", e)
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
     * Send a message with file attachments.
     *
     * For images: includes as base64 in the API call (multimodal content).
     * For text files: includes content inline in the message.
     */
    fun sendMessageWithAttachments(text: String, attachments: List<AttachmentInfo>) {
        if (_currentSessionId.value == null) createNewSession()

        // Build the composite message content
        val contentBuilder = StringBuilder()

        // Add text file contents first
        val textAttachments = attachments.filter { !it.isImage }
        for (attachment in textAttachments) {
            contentBuilder.append("--- File: ${attachment.name} (${attachment.mimeType}) ---\n")
            contentBuilder.append(attachment.content)
            contentBuilder.append("\n--- End of ${attachment.name} ---\n\n")
        }

        // Add the user's text
        if (text.isNotBlank()) {
            contentBuilder.append(text)
        }

        val imageAttachments = attachments.filter { it.isImage }

        // For the message stored in history, include a note about images
        val displayContent = if (imageAttachments.isNotEmpty()) {
            val imageNames = imageAttachments.joinToString(", ") { it.name }
            contentBuilder.toString() + "\n[Attached images: $imageNames]"
        } else {
            contentBuilder.toString()
        }

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = displayContent
        )
        _messages.value = _messages.value + userMsg

        autoTitleIfNeeded(text.ifBlank { attachments.firstOrNull()?.name ?: "Attachment" })

        viewModelScope.launch {
            _isGenerating.value = true
            _streamingContent.value = ""

            try {
                if (!GoosePortHolder.localOnlyMode && acpClient != null) {
                    // ACP path: send text only (images via ACP sendPrompt images param)
                    val base64Images = imageAttachments.map { it.content }
                    acpClient!!.sendPrompt(contentBuilder.toString(), base64Images)
                } else {
                    // Cloud API path: handle multimodal content
                    handleLocalMessageWithAttachments(contentBuilder.toString(), imageAttachments)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message with attachments", e)
                addSystemMessage("Error: ${e.message}")
                _isGenerating.value = false
            }
        }
    }

    /**
     * Handle message with image attachments in local-only / cloud API mode.
     */
    private suspend fun handleLocalMessageWithAttachments(
        text: String,
        imageAttachments: List<AttachmentInfo>
    ) {
        val activeProvider = settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first()
        val activeModel = settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first()

        val conversationMessages = buildConversationHistory()

        if (activeProvider.isNotBlank()) {
            val apiKey = getApiKeyForProvider(activeProvider)
            if (apiKey.isNotBlank() || activeProvider == "custom" || activeProvider == "local") {
                callCloudApiStreaming(
                    activeProvider, apiKey, activeModel, conversationMessages,
                    imageAttachments
                )
                return
            }
        }

        // Fallback: try providers in order (same logic as handleLocalMessage)
        val anthropicKey = settingsStore.getString(SettingsKeys.ANTHROPIC_API_KEY).first()
        val openaiKey = settingsStore.getString(SettingsKeys.OPENAI_API_KEY).first()
        val googleKey = settingsStore.getString(SettingsKeys.GOOGLE_API_KEY).first()
        val mistralKey = settingsStore.getString(SettingsKeys.MISTRAL_API_KEY).first()
        val openrouterKey = settingsStore.getString(SettingsKeys.OPENROUTER_API_KEY).first()

        when {
            anthropicKey.isNotBlank() -> {
                callCloudApiStreaming("anthropic", anthropicKey, "claude-sonnet-4-20250514",
                    conversationMessages, imageAttachments)
            }
            openaiKey.isNotBlank() -> {
                callCloudApiStreaming("openai", openaiKey, "gpt-4o",
                    conversationMessages, imageAttachments)
            }
            googleKey.isNotBlank() -> {
                callCloudApiStreaming("google", googleKey, "gemini-2.0-flash",
                    conversationMessages, imageAttachments)
            }
            mistralKey.isNotBlank() -> {
                callCloudApiStreaming("mistral", mistralKey, "mistral-large-latest",
                    conversationMessages, imageAttachments)
            }
            openrouterKey.isNotBlank() -> {
                callCloudApiStreaming("openrouter", openrouterKey,
                    "anthropic/claude-sonnet-4-20250514", conversationMessages, imageAttachments)
            }
            else -> {
                addSystemMessage(
                    "No model configured.\n\n" +
                    "Go to Settings to configure a provider."
                )
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

    // ─── Compact Conversation ───────────────────────────────────────────────────

    /**
     * Compact the conversation: sends a system message asking the AI to summarize,
     * then replaces all messages with the summary + a system note.
     */
    fun compactConversation() {
        if (_messages.value.isEmpty()) {
            addSystemMessage("Nothing to compact — conversation is empty.")
            return
        }

        if (_isGenerating.value) {
            addSystemMessage("Cannot compact while generating a response.")
            return
        }

        viewModelScope.launch {
            _isGenerating.value = true

            try {
                val activeProvider = settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first()
                val activeModel = settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first()
                val apiKey = getApiKeyForProvider(activeProvider)

                if (apiKey.isBlank() && activeProvider != "custom" && activeProvider != "local") {
                    addSystemMessage("Cannot compact: no API key configured for provider '$activeProvider'.")
                    _isGenerating.value = false
                    return@launch
                }

                // Build a summary request from the current conversation
                val conversationText = _messages.value
                    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                    .joinToString("\n\n") { msg ->
                        val roleLabel = if (msg.role == MessageRole.USER) "User" else "Assistant"
                        "$roleLabel: ${msg.content}"
                    }

                val summaryPrompt = "Please provide a concise but comprehensive summary of the following conversation. " +
                    "Capture all key points, decisions, code snippets, and context that would be needed to continue " +
                    "the conversation. Format it clearly.\n\n---\n\n$conversationText"

                val summaryMessages = listOf("user" to summaryPrompt)

                // Make a non-streaming call to get the summary
                val summary = callCloudApiForSummary(activeProvider, apiKey, activeModel, summaryMessages)

                if (summary.isNotBlank()) {
                    // Replace all messages with the compact summary
                    val compactNote = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = MessageRole.SYSTEM,
                        content = "⚡ Conversation compacted. Previous messages have been summarized."
                    )
                    val summaryMsg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = MessageRole.ASSISTANT,
                        content = summary
                    )

                    _messages.value = listOf(compactNote, summaryMsg)
                    _toolCalls.value = emptyList()
                    saveCurrentSessionMessages()
                    updateCurrentSessionMetadata()
                } else {
                    addSystemMessage("Compact failed: received empty summary from API.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Compact conversation failed", e)
                addSystemMessage("Compact failed: ${e.message}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Non-streaming API call used for compact summarization.
     * Returns the response content as a string.
     */
    private suspend fun callCloudApiForSummary(
        provider: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        val resolvedModel = model.ifBlank { getDefaultModel(provider) }

        when (provider) {
            "anthropic" -> {
                val messagesArray = JSONArray()
                if (activeSystemPrompt.isNotBlank()) {
                    // Anthropic uses top-level "system" field
                }
                for ((role, content) in messages) {
                    val msgObj = JSONObject()
                    msgObj.put("role", role)
                    msgObj.put("content", content)
                    messagesArray.put(msgObj)
                }

                val body = JSONObject()
                body.put("model", resolvedModel)
                body.put("max_tokens", 4096)
                body.put("stream", false)
                body.put("messages", messagesArray)
                if (activeSystemPrompt.isNotBlank()) {
                    body.put("system", activeSystemPrompt)
                }

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
                    val contentArray = json.optJSONArray("content")
                    if (contentArray != null && contentArray.length() > 0) {
                        contentArray.getJSONObject(0).optString("text", "")
                    } else ""
                } finally {
                    conn.disconnect()
                }
            }
            "google" -> {
                val contentsArray = JSONArray()

                // Add system instruction if present
                if (activeSystemPrompt.isNotBlank()) {
                    val sysContent = JSONObject()
                    sysContent.put("role", "user")
                    val sysParts = JSONArray()
                    val sysPart = JSONObject()
                    sysPart.put("text", "[System Instructions]: $activeSystemPrompt")
                    sysParts.put(sysPart)
                    sysContent.put("parts", sysParts)
                    contentsArray.put(sysContent)
                }

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
                    "https://generativelanguage.googleapis.com/v1beta/models/$resolvedModel:generateContent?key=$apiKey"
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
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val contentObj = candidate.optJSONObject("content")
                        val parts = contentObj?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            parts.getJSONObject(0).optString("text", "")
                        } else ""
                    } else ""
                } finally {
                    conn.disconnect()
                }
            }
            else -> {
                // OpenAI-compatible (openai, mistral, openrouter, custom)
                val messagesArray = JSONArray()

                // Include system prompt as first system message
                if (activeSystemPrompt.isNotBlank()) {
                    val sysMsg = JSONObject()
                    sysMsg.put("role", "system")
                    sysMsg.put("content", activeSystemPrompt)
                    messagesArray.put(sysMsg)
                }

                for ((role, content) in messages) {
                    val msgObj = JSONObject()
                    msgObj.put("role", role)
                    msgObj.put("content", content)
                    messagesArray.put(msgObj)
                }

                val body = JSONObject()
                body.put("model", resolvedModel)
                body.put("stream", false)
                body.put("messages", messagesArray)

                val endpoint = when (provider) {
                    "openai" -> "https://api.openai.com/v1/chat/completions"
                    "mistral" -> "https://api.mistral.ai/v1/chat/completions"
                    "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
                    "custom" -> {
                        val baseUrl = settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_URL).first()
                        if (baseUrl.endsWith("/")) "${baseUrl}chat/completions"
                        else if (baseUrl.contains("/chat/completions")) baseUrl
                        else "$baseUrl/v1/chat/completions"
                    }
                    else -> "https://api.openai.com/v1/chat/completions"
                }

                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    if (provider == "openrouter") {
                        conn.setRequestProperty("HTTP-Referer", "https://github.com/MaxFlynn13/goose-android")
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

                    val responseBody = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.optJSONObject("message")
                        message?.optString("content", "") ?: ""
                    } else ""
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    // ─── Streaming Cloud API Calls ──────────────────────────────────────────────

    /**
     * Unified streaming call dispatcher. Adds an empty assistant message,
     * streams tokens into it, and finalizes on completion.
     *
     * The active system prompt is included in ALL API calls:
     * - Anthropic: as the top-level "system" field
     * - OpenAI/Mistral/OpenRouter/Custom: as the first "system" role message
     * - Google: as a system instruction preamble
     */
    private suspend fun callCloudApiStreaming(
        provider: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        imageAttachments: List<AttachmentInfo> = emptyList()
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
                "anthropic" -> streamAnthropic(apiKey, resolvedModel, messages, assistantId, imageAttachments)
                "openai" -> streamOpenAI(apiKey, resolvedModel, messages, assistantId,
                    "https://api.openai.com/v1/chat/completions", emptyMap(), imageAttachments)
                "google" -> streamGoogle(apiKey, resolvedModel, messages, assistantId, imageAttachments)
                "mistral" -> streamOpenAI(apiKey, resolvedModel, messages, assistantId,
                    "https://api.mistral.ai/v1/chat/completions", emptyMap(), imageAttachments)
                "openrouter" -> streamOpenAI(apiKey, resolvedModel, messages, assistantId,
                    "https://openrouter.ai/api/v1/chat/completions",
                    mapOf("HTTP-Referer" to "https://github.com/MaxFlynn13/goose-android"),
                    imageAttachments)
                "custom" -> {
                    val baseUrl = settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_URL).first()
                    val customModel = settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_MODEL).first()
                    val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions"
                              else if (baseUrl.contains("/chat/completions")) baseUrl
                              else "$baseUrl/v1/chat/completions"
                    streamOpenAI(apiKey, customModel.ifBlank { resolvedModel }, messages, assistantId,
                        url, emptyMap(), imageAttachments)
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
            saveCurrentSessionMessages()
            updateCurrentSessionMetadata()
        }
    }

    private fun getDefaultModel(provider: String): String {
        return PROVIDER_MODELS[provider]?.firstOrNull() ?: "gpt-4o"
    }

    /**
     * Stream from Anthropic Messages API with SSE.
     * Includes activeSystemPrompt as the top-level "system" field.
     * Supports multimodal content (images as base64).
     */
    private suspend fun streamAnthropic(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        assistantId: String,
        imageAttachments: List<AttachmentInfo> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()

        for (i in messages.indices) {
            val (role, content) = messages[i]
            val msgObj = JSONObject()
            msgObj.put("role", role)

            // For the last user message, include image attachments if any
            val isLastUserMessage = role == "user" && i == messages.lastIndex
            if (isLastUserMessage && imageAttachments.isNotEmpty()) {
                val contentArray = JSONArray()

                // Add images first
                for (img in imageAttachments) {
                    val imageContent = JSONObject()
                    imageContent.put("type", "image")
                    val source = JSONObject()
                    source.put("type", "base64")
                    source.put("media_type", img.mimeType)
                    source.put("data", img.content)
                    imageContent.put("source", source)
                    contentArray.put(imageContent)
                }

                // Add text content
                val textContent = JSONObject()
                textContent.put("type", "text")
                textContent.put("text", content)
                contentArray.put(textContent)

                msgObj.put("content", contentArray)
            } else {
                msgObj.put("content", content)
            }

            messagesArray.put(msgObj)
        }

        val body = JSONObject()
        body.put("model", model)
        body.put("max_tokens", 4096)
        body.put("stream", true)
        body.put("messages", messagesArray)

        // Include system prompt as top-level "system" field for Anthropic
        if (activeSystemPrompt.isNotBlank()) {
            body.put("system", activeSystemPrompt)
        }

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
     * Includes activeSystemPrompt as the first "system" role message.
     * Supports multimodal content (images as base64 URLs).
     */
    private suspend fun streamOpenAI(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        assistantId: String,
        endpoint: String,
        extraHeaders: Map<String, String>,
        imageAttachments: List<AttachmentInfo> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()

        // Include system prompt as first system message
        if (activeSystemPrompt.isNotBlank()) {
            val sysMsg = JSONObject()
            sysMsg.put("role", "system")
            sysMsg.put("content", activeSystemPrompt)
            messagesArray.put(sysMsg)
        }

        for (i in messages.indices) {
            val (role, content) = messages[i]
            val msgObj = JSONObject()
            msgObj.put("role", role)

            // For the last user message, include image attachments if any
            val isLastUserMessage = role == "user" && i == messages.lastIndex
            if (isLastUserMessage && imageAttachments.isNotEmpty()) {
                val contentArray = JSONArray()

                // Add text content first
                val textPart = JSONObject()
                textPart.put("type", "text")
                textPart.put("text", content)
                contentArray.put(textPart)

                // Add images
                for (img in imageAttachments) {
                    val imagePart = JSONObject()
                    imagePart.put("type", "image_url")
                    val imageUrl = JSONObject()
                    imageUrl.put("url", "data:${img.mimeType};base64,${img.content}")
                    imagePart.put("image_url", imageUrl)
                    contentArray.put(imagePart)
                }

                msgObj.put("content", contentArray)
            } else {
                msgObj.put("content", content)
            }

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
     * Includes activeSystemPrompt as a system instruction preamble.
     * Supports multimodal content (images as inline data).
     */
    private suspend fun streamGoogle(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        assistantId: String,
        imageAttachments: List<AttachmentInfo> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val contentsArray = JSONArray()

        // Add system instruction as a user preamble if present
        if (activeSystemPrompt.isNotBlank()) {
            val sysContent = JSONObject()
            sysContent.put("role", "user")
            val sysParts = JSONArray()
            val sysPart = JSONObject()
            sysPart.put("text", "[System Instructions]: $activeSystemPrompt")
            sysParts.put(sysPart)
            sysContent.put("parts", sysParts)
            contentsArray.put(sysContent)

            // Add a model acknowledgment to keep alternating roles
            val ackContent = JSONObject()
            ackContent.put("role", "model")
            val ackParts = JSONArray()
            val ackPart = JSONObject()
            ackPart.put("text", "Understood. I will follow those instructions.")
            ackParts.put(ackPart)
            ackContent.put("parts", ackParts)
            contentsArray.put(ackContent)
        }

        for (i in messages.indices) {
            val (role, content) = messages[i]
            val contentObj = JSONObject()
            val geminiRole = if (role == "assistant") "model" else "user"
            contentObj.put("role", geminiRole)

            val partsArray = JSONArray()

            // For the last user message, include image attachments if any
            val isLastUserMessage = role == "user" && i == messages.lastIndex
            if (isLastUserMessage && imageAttachments.isNotEmpty()) {
                // Add images as inline data
                for (img in imageAttachments) {
                    val imagePart = JSONObject()
                    val inlineData = JSONObject()
                    inlineData.put("mime_type", img.mimeType)
                    inlineData.put("data", img.content)
                    imagePart.put("inline_data", inlineData)
                    partsArray.put(imagePart)
                }
            }

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

                        for (j in 0 until parts.length()) {
                            val part = parts.getJSONObject(j)
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
     * Includes activeSystemPrompt as the first system message.
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

                    // Include system prompt as first system message for local model
                    if (activeSystemPrompt.isNotBlank()) {
                        val sysMsg = JSONObject()
                        sysMsg.put("role", "system")
                        sysMsg.put("content", activeSystemPrompt)
                        messagesArray.put(sysMsg)
                    }

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
            saveCurrentSessionMessages()
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
            saveCurrentSessionMessages()
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

    /**
     * Set the active persona for chat. Updates the system prompt used in conversations.
     */
    fun setActivePersona(personaId: String, name: String, systemPrompt: String) {
        viewModelScope.launch {
            settingsStore.setString("active_persona_id", personaId)
            activeSystemPrompt = systemPrompt
            createNewSession()
            addSystemMessage("Switched to persona: $name")
        }
    }

    /**
     * Start a new chat session scoped to a project.
     */
    fun startProjectSession(projectId: String, projectName: String, instructions: String) {
        viewModelScope.launch {
            activeSystemPrompt = instructions
            createNewSession()
            _currentSessionTitle.value = projectName
            addSystemMessage("Started project session: $projectName")
        }
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
        saveCurrentSessionMessages()
        updateCurrentSessionMetadata()
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
        saveCurrentSessionMessages()
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
