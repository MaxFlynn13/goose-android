package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gooseandroid.GoosePortHolder
import io.github.gooseandroid.LocalModelManager
import io.github.gooseandroid.acp.AcpClient
import io.github.gooseandroid.acp.AcpNotificationHandler
import io.github.gooseandroid.data.SessionRepository
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.network.CloudApiClient
import io.github.gooseandroid.network.LocalModelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the chat screen — the brain of the Goose AI Android app.
 *
 * Delegates to extracted helper classes:
 * - SessionRepository: file I/O for sessions and messages
 * - CloudApiClient: streaming cloud API calls
 * - LocalModelClient: local model inference
 * - AcpNotificationHandler: ACP notification parsing
 * - SessionManager: session lifecycle management
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"

        val PROVIDER_MODELS: Map<String, List<String>> = PROVIDER_CATALOG
            .associate { it.id to it.models.map { m -> m.id } }
    }

    private val appContext = application.applicationContext
    private val settingsStore = SettingsStore(application)
    private val modelManager = LocalModelManager(application)

    // ─── Helper Classes ─────────────────────────────────────────────────────────

    private val sessionRepository = SessionRepository(appContext)
    private val cloudApiClient = CloudApiClient(settingsStore)
    private val localModelClient = LocalModelClient(settingsStore, modelManager, cloudApiClient)
    private val acpNotificationHandler = AcpNotificationHandler()
    private val sessionManager = SessionManager(sessionRepository, settingsStore)

    // ─── State Flows ────────────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isLoadingSession = MutableStateFlow(false)
    val isLoadingSession: StateFlow<Boolean> = _isLoadingSession.asStateFlow()

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

    private val _exportedJson = MutableStateFlow<String?>(null)
    val exportedJson: StateFlow<String?> = _exportedJson.asStateFlow()

    private val _thinkingContent = MutableStateFlow("")
    val thinkingContent: StateFlow<String> = _thinkingContent.asStateFlow()

    val tokenCount: StateFlow<Int> = _messages
        .map { msgs -> msgs.sumOf { it.content.length } / 4 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private var acpClient: AcpClient? = null
    private var streamingJob: Job? = null
    private var notificationJob: Job? = null
    private var activeSystemPrompt: String = ""

    // ─── Initialization ─────────────────────────────────────────────────────────

    init {
        setupCallbacks()
        loadSessions()
        connectToGoose()
    }

    private fun setupCallbacks() {
        sessionManager.setCallbacks(object : SessionManager.Callbacks {
            override fun getSessions() = _sessions.value
            override fun setSessions(sessions: List<SessionInfo>) { _sessions.value = sessions }
            override fun getCurrentSessionId() = _currentSessionId.value
            override fun setCurrentSessionId(id: String?) { _currentSessionId.value = id }
            override fun getMessages() = _messages.value
            override fun setMessages(messages: List<ChatMessage>) { _messages.value = messages }
            override fun setToolCalls(toolCalls: List<ToolCall>) { _toolCalls.value = toolCalls }
            override fun setStreamingContent(content: String) { _streamingContent.value = content }
            override fun setIsGenerating(generating: Boolean) { _isGenerating.value = generating }
            override fun saveSessions() { this@ChatViewModel.saveSessions() }
        })

        acpNotificationHandler.setCallbacks(object : AcpNotificationHandler.Callbacks {
            override fun onStreamingContent(content: String, isComplete: Boolean) {
                handleAcpStreamingContent(content, isComplete)
            }
            override fun onToolCallStart(name: String, arguments: String?) {
                _toolCalls.value = AcpNotificationHandler.addToolCallStart(_toolCalls.value, name, arguments ?: "")
                updateLastAssistantToolCalls()
            }
            override fun onToolCallEnd(name: String, output: String, isError: Boolean) {
                _toolCalls.value = AcpNotificationHandler.updateToolCallEnd(_toolCalls.value, name, output, isError)
                updateLastAssistantToolCalls()
            }
        })
    }

    private fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = sessionRepository.loadSessions()
            if (loaded.isNotEmpty()) {
                _sessions.value = loaded
                if (_currentSessionId.value == null) {
                    val latest = loaded.maxByOrNull { it.createdAt }
                    latest?.let {
                        _currentSessionId.value = it.id
                        val msgs = sessionRepository.loadMessagesFromDisk(it.id)
                        _messages.value = msgs
                    }
                }
            }
        }
    }

    private fun connectToGoose() {
        viewModelScope.launch {
            if (GoosePortHolder.localOnlyMode) {
                Log.i(TAG, "Running in local-only mode")
                if (_sessions.value.isEmpty()) {
                    createNewSession()
                } else {
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

            // Fix #6: Cancel previous notification collector before starting a new one
            notificationJob?.cancel()
            notificationJob = launch {
                client.notifications.collect { notification ->
                    acpNotificationHandler.handleAcpNotification(notification)
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

    // ─── Public Methods (delegating) ────────────────────────────────────────────

    // Fix #1: No longer blocks the main thread. Generates session ID synchronously,
    // updates state synchronously, then launches coroutine for I/O.
    fun createNewSession() {
        val newId = UUID.randomUUID().toString()
        val newSession = SessionInfo(
            id = newId,
            title = "New Chat",
            createdAt = System.currentTimeMillis()
        )
        _sessions.value = _sessions.value + newSession
        _currentSessionId.value = newId
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        _isLoadingSession.value = false

        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.persistNewSession(newSession)
        }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            _isLoadingSession.value = true
            sessionManager.switchSession(sessionId)
            _isLoadingSession.value = false
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { sessionManager.deleteSession(sessionId) }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch { sessionManager.renameSession(sessionId, newTitle) }
    }

    // Fix #2: No longer blocks the main thread. Launches coroutine internally.
    fun forkSession(sessionId: String) {
        viewModelScope.launch {
            val forkedId = sessionManager.forkSession(sessionId)
            _currentSessionId.value = forkedId
        }
    }

    // Fix #3: No longer blocks the main thread. Sets result in exportedJson StateFlow.
    fun exportSession(sessionId: String) {
        viewModelScope.launch {
            val json = sessionManager.exportSession(sessionId)
            _exportedJson.value = json
        }
    }

    fun clearExportedJson() {
        _exportedJson.value = null
    }

    fun prefillPrompt(prompt: String) {
        _pendingPrompt.value = prompt
    }

    fun clearPendingPrompt() {
        _pendingPrompt.value = null
    }

    fun setActivePersona(personaId: String, name: String, systemPrompt: String) {
        viewModelScope.launch {
            settingsStore.setString("active_persona_id", personaId)
            activeSystemPrompt = systemPrompt
            createNewSession()
            addSystemMessage("Switched to persona: $name")
        }
    }

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
        viewModelScope.launch {
            sessionManager.saveCurrentSessionMessages()
            sessionManager.updateCurrentSessionMetadata()
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

    fun getModelsForProvider(provider: String): List<String> {
        return PROVIDER_MODELS[provider] ?: emptyList()
    }

    fun setActiveProviderAndModel(provider: String, model: String) {
        viewModelScope.launch {
            settingsStore.setString(SettingsKeys.ACTIVE_PROVIDER, provider)
            settingsStore.setString(SettingsKeys.ACTIVE_MODEL, model)
            val currentId = _currentSessionId.value ?: return@launch
            _sessions.value = _sessions.value.map {
                if (it.id == currentId) it.copy(providerId = provider, modelId = model) else it
            }
            saveSessions()
        }
    }

    /**
     * Edit a previously sent user message and re-send it.
     * Removes the edited message and all messages after it (including assistant responses),
     * then sends the new content as a fresh message to regenerate.
     */
    fun editAndResend(messageId: String, newContent: String) {
        val currentMessages = _messages.value
        val messageIndex = currentMessages.indexOfFirst { it.id == messageId }
        if (messageIndex == -1) return

        // Keep only messages before the edited one (remove it and everything after)
        _messages.value = currentMessages.subList(0, messageIndex)

        // Clear tool calls and streaming state
        _toolCalls.value = emptyList()
        _streamingContent.value = ""

        // Send the new content (this will add a fresh user message and trigger generation)
        sendMessage(newContent)
    }

    fun sendMessage(text: String) {
        if (_currentSessionId.value == null) createNewSession()

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text
        )
        _messages.value = _messages.value + userMsg
        sessionManager.generateAutoTitle(text)

        viewModelScope.launch {
            _isGenerating.value = true
            _streamingContent.value = ""
            _thinkingContent.value = ""

            try {
                if (!GoosePortHolder.localOnlyMode && acpClient != null) {
                    acpClient!!.sendPrompt(text)
                } else {
                    val assistantId = addAssistantPlaceholder()
                    val found = localModelClient.handleLocalMessage(
                        _messages.value, activeSystemPrompt, createStreamingCallbacks(assistantId)
                    )
                    if (!found) {
                        removeMessage(assistantId)
                        addSystemMessage(
                            "No model configured.\n\n" +
                            "Go to Settings to either:\n" +
                            "- Add a cloud API key (Anthropic, OpenAI, Google, Mistral, OpenRouter)\n" +
                            "- Configure a custom OpenAI-compatible endpoint\n" +
                            "- Download a local model for offline use"
                        )
                        _isGenerating.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                addSystemMessage("Error: ${e.message}")
                _isGenerating.value = false
            }
        }
    }

    fun sendMessageWithAttachments(text: String, attachments: List<AttachmentInfo>) {
        if (_currentSessionId.value == null) createNewSession()

        val contentBuilder = StringBuilder()
        val textAttachments = attachments.filter { !it.isImage }
        for (attachment in textAttachments) {
            contentBuilder.append("--- File: ${attachment.name} (${attachment.mimeType}) ---\n")
            contentBuilder.append(attachment.content)
            contentBuilder.append("\n--- End of ${attachment.name} ---\n\n")
        }
        if (text.isNotBlank()) contentBuilder.append(text)

        val imageAttachments = attachments.filter { it.isImage }
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
        sessionManager.generateAutoTitle(text.ifBlank { attachments.firstOrNull()?.name ?: "Attachment" })

        viewModelScope.launch {
            _isGenerating.value = true
            _streamingContent.value = ""
            _thinkingContent.value = ""

            try {
                if (!GoosePortHolder.localOnlyMode && acpClient != null) {
                    val base64Images = imageAttachments.map { it.content }
                    acpClient!!.sendPrompt(contentBuilder.toString(), base64Images)
                } else {
                    val assistantId = addAssistantPlaceholder()
                    val found = localModelClient.handleLocalMessageWithAttachments(
                        _messages.value, imageAttachments, activeSystemPrompt,
                        createStreamingCallbacks(assistantId)
                    )
                    if (!found) {
                        removeMessage(assistantId)
                        addSystemMessage(
                            "No model configured.\n\nGo to Settings to configure a provider."
                        )
                        _isGenerating.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message with attachments", e)
                addSystemMessage("Error: ${e.message}")
                _isGenerating.value = false
            }
        }
    }

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
                val apiKey = cloudApiClient.getApiKeyForProvider(activeProvider)

                if (apiKey.isBlank() && activeProvider != "custom" && activeProvider != "local") {
                    addSystemMessage("Cannot compact: no API key configured for provider '$activeProvider'.")
                    _isGenerating.value = false
                    return@launch
                }

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
                val summary = cloudApiClient.callCloudApiForSummary(
                    activeProvider, apiKey, activeModel, summaryMessages, activeSystemPrompt
                )

                if (summary.isNotBlank()) {
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
                    sessionManager.saveCurrentSessionMessages()
                    sessionManager.updateCurrentSessionMetadata()
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

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private fun saveSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.saveSessions(_sessions.value)
        }
    }

    private fun addAssistantPlaceholder(): String {
        val assistantId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = ""
        )
        _messages.value = _messages.value + placeholder
        return assistantId
    }

    private fun removeMessage(messageId: String) {
        _messages.value = _messages.value.filter { it.id != messageId }
    }

    private fun createStreamingCallbacks(assistantId: String): CloudApiClient.StreamingCallbacks {
        return object : CloudApiClient.StreamingCallbacks {
            // Fix #5: Only update the last message efficiently instead of mapping the entire list
            override fun onToken(token: String) {
                _streamingContent.value = token
                val currentMessages = _messages.value
                val lastIndex = currentMessages.lastIndex
                if (lastIndex >= 0 && currentMessages[lastIndex].id == assistantId) {
                    val updatedMsg = currentMessages[lastIndex].copy(content = token)
                    _messages.value = currentMessages.toMutableList().apply {
                        this[lastIndex] = updatedMsg
                    }
                } else {
                    // Fallback: scan the list (should rarely happen)
                    _messages.value = currentMessages.map { msg ->
                        if (msg.id == assistantId) msg.copy(content = token) else msg
                    }
                }
            }

            override fun onComplete(fullContent: String) {
                val finalContent = fullContent.ifBlank { "No response received" }
                val thinkingText = _thinkingContent.value
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == assistantId) msg.copy(
                        content = finalContent,
                        thinking = thinkingText
                    ) else msg
                }
                _streamingContent.value = ""
                _thinkingContent.value = ""
                _isGenerating.value = false
                viewModelScope.launch {
                    sessionManager.saveCurrentSessionMessages()
                    sessionManager.updateCurrentSessionMetadata()
                }
            }

            override fun onError(error: String) {
                val current = _streamingContent.value
                if (current.isBlank()) {
                    _messages.value = _messages.value.map { msg ->
                        if (msg.id == assistantId) msg.copy(content = "Error: $error") else msg
                    }
                }
                _streamingContent.value = ""
                _thinkingContent.value = ""
                _isGenerating.value = false
                if (current.isBlank()) {
                    addSystemMessage(error)
                }
                viewModelScope.launch {
                    sessionManager.saveCurrentSessionMessages()
                    sessionManager.updateCurrentSessionMetadata()
                }
            }

            override fun onToolCallStart(name: String) {
                _toolCalls.value = AcpNotificationHandler.addToolCallStart(_toolCalls.value, name, "")
                updateLastAssistantToolCalls()
            }

            override fun onToolCallEnd(name: String, output: String, isError: Boolean) {
                _toolCalls.value = AcpNotificationHandler.updateToolCallEnd(_toolCalls.value, name, output, isError)
                updateLastAssistantToolCalls()
            }

            override fun onThinking(text: String) {
                _thinkingContent.value = text
            }
        }
    }

    private fun handleAcpStreamingContent(content: String, isComplete: Boolean) {
        val currentMessages = _messages.value
        val lastMsg = currentMessages.lastOrNull()

        if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && _isGenerating.value) {
            val updated = lastMsg.copy(content = lastMsg.content + content)
            _messages.value = currentMessages.dropLast(1) + updated
            _streamingContent.value = updated.content
        } else {
            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = content
            )
            _messages.value = currentMessages + msg
            _streamingContent.value = content
        }

        if (isComplete) {
            _isGenerating.value = false
            _streamingContent.value = ""
            viewModelScope.launch {
                sessionManager.saveCurrentSessionMessages()
                sessionManager.updateCurrentSessionMetadata()
            }
        }
    }

    private fun updateLastAssistantToolCalls() {
        val currentMessages = _messages.value
        val lastAssistant = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        val updated = lastAssistant.copy(toolCalls = _toolCalls.value.toList())
        _messages.value = currentMessages.map { if (it.id == lastAssistant.id) updated else it }
    }

    // Fix #4: Use NonCancellable coroutine instead of runBlocking to avoid ANR
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            sessionManager.saveCurrentSessionMessages()
            sessionManager.updateCurrentSessionMetadata()
        }
        acpClient?.disconnect()
    }
}
