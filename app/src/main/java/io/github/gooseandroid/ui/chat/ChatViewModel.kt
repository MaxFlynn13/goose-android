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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
                    // ACP session failed — null out client so cloud fallback is used
                    acpClient = null
                    client.disconnect()
                    addSystemMessage("Goose agent unavailable — using direct LLM mode.\nYou can chat normally but tool use (shell, edit, file ops) requires the Goose backend.")
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to connect to goose backend", error)
                // Connection failed — null out client so cloud fallback is used
                acpClient = null
                if (_sessions.value.isEmpty()) createNewSession()
                addSystemMessage(
                    "Goose agent unavailable — using direct LLM mode.\n" +
                    "Chat works normally. Tool use (shell, edit, file ops) requires the Goose backend.\n\n" +
                    "Check Logs screen for details on why the backend failed to start."
                )
            }
        }
    }

    // ─── Public Methods (delegating) ────────────────────────────────────────────

    // Session ID and state are set synchronously BEFORE any coroutine launch.
    fun createNewSession() {
        val newId = UUID.randomUUID().toString()
        Log.i(TAG, "createNewSession: id=$newId")

        val newSession = SessionInfo(
            id = newId,
            title = "New Chat",
            createdAt = System.currentTimeMillis()
        )

        // Synchronous state updates — _currentSessionId is set BEFORE returning
        _currentSessionId.value = newId
        _sessions.value = _sessions.value + newSession
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        _isLoadingSession.value = false

        Log.i(TAG, "createNewSession: state updated synchronously, currentSessionId=${_currentSessionId.value}")

        // Only the disk I/O is async
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

    fun forkSession(sessionId: String) {
        viewModelScope.launch {
            val forkedId = sessionManager.forkSession(sessionId)
            _currentSessionId.value = forkedId
        }
    }

    fun exportSession(sessionId: String) {
        viewModelScope.launch {
            val json = sessionManager.exportSession(sessionId)
            _exportedJson.value = json
        }
    }

    fun clearExportedJson() {
        _exportedJson.value = null
    }

    fun clearLastError() {
        _lastError.value = null
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
        Log.i(TAG, "sendMessage: entering, currentSessionId=${_currentSessionId.value}, text='${text.take(50)}...'")

        if (_currentSessionId.value == null) {
            Log.i(TAG, "sendMessage: no session, creating new one")
            createNewSession()
        }

        Log.i(TAG, "sendMessage: sessionId=${_currentSessionId.value}")

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text
        )
        _messages.value = _messages.value + userMsg
        sessionManager.generateAutoTitle(text)

        // Cancel previous job
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            // Wait for the old job's finally block to complete before we set isGenerating=true
            // This prevents the race where old finally{isGenerating=false} runs after new isGenerating=true
            kotlinx.coroutines.yield()
            _isGenerating.value = true
            _streamingContent.value = ""
            _thinkingContent.value = ""
            _lastError.value = null

            // Track the assistant placeholder ID so we can check it after completion
            var assistantPlaceholderId: String? = null

            try {
                kotlinx.coroutines.withTimeout(120_000L) {
                    val isAcpMode = !GoosePortHolder.localOnlyMode && acpClient != null
                    Log.i(TAG, "sendMessage: isAcpMode=$isAcpMode, localOnlyMode=${GoosePortHolder.localOnlyMode}, acpClient=${acpClient != null}")

                    if (isAcpMode) {
                        Log.i(TAG, "sendMessage: sending via ACP")
                        acpClient!!.sendPrompt(text)
                    } else {
                        Log.i(TAG, "sendMessage: sending via local/cloud path")
                        assistantPlaceholderId = addAssistantPlaceholder()
                        Log.i(TAG, "sendMessage: added assistant placeholder id=$assistantPlaceholderId")

                        val found = localModelClient.handleLocalMessage(
                            _messages.value, activeSystemPrompt, createStreamingCallbacks(assistantPlaceholderId!!)
                        )
                        Log.i(TAG, "sendMessage: handleLocalMessage returned found=$found")

                        if (!found) {
                            removeMessage(assistantPlaceholderId!!)
                            val errorMsg = "No model configured.\n\n" +
                                "Go to Settings to add a cloud API key or download a local model."
                            _lastError.value = errorMsg
                            addSystemMessage(errorMsg)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Message timed out after 120s", e)
                val errorMsg = "Request timed out. Check your network connection and try again."
                _lastError.value = errorMsg
                addSystemMessage(errorMsg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Message cancelled")
                throw e // Don't swallow cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                val errorMsg = "Error: ${e.message}"
                _lastError.value = errorMsg
                addSystemMessage(errorMsg)
            } finally {
                _isGenerating.value = false

                // Check if the assistant placeholder message is still empty after everything completed.
                // This catches the case where streaming callbacks never fired or the API threw after
                // the placeholder was added but before any tokens arrived.
                if (assistantPlaceholderId != null) {
                    val msgs = _messages.value
                    val assistantMsg = msgs.find { it.id == assistantPlaceholderId }
                    if (assistantMsg != null && assistantMsg.content.isBlank()) {
                        Log.w(TAG, "sendMessage: assistant placeholder still empty after completion, replacing with error")
                        val fallbackError = "No response received — check your API key and network connection."
                        _lastError.value = fallbackError
                        _messages.value = msgs.map { msg ->
                            if (msg.id == assistantPlaceholderId) msg.copy(content = fallbackError) else msg
                        }
                    }
                }

                Log.i(TAG, "sendMessage: finished, isGenerating=false, messageCount=${_messages.value.size}")
            }
        }
    }

    fun sendMessageWithAttachments(text: String, attachments: List<AttachmentInfo>) {
        Log.i(TAG, "sendMessageWithAttachments: entering, attachments=${attachments.size}")

        if (_currentSessionId.value == null) {
            Log.i(TAG, "sendMessageWithAttachments: no session, creating new one")
            createNewSession()
        }

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

        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            kotlinx.coroutines.yield()
            _isGenerating.value = true
            _streamingContent.value = ""
            _thinkingContent.value = ""
            _lastError.value = null

            var assistantPlaceholderId: String? = null

            try {
                kotlinx.coroutines.withTimeout(120_000L) {
                    val isAcpMode = !GoosePortHolder.localOnlyMode && acpClient != null
                    Log.i(TAG, "sendMessageWithAttachments: isAcpMode=$isAcpMode")

                    if (isAcpMode) {
                        val base64Images = imageAttachments.map { it.content }
                        acpClient!!.sendPrompt(contentBuilder.toString(), base64Images)
                    } else {
                        assistantPlaceholderId = addAssistantPlaceholder()
                        Log.i(TAG, "sendMessageWithAttachments: added assistant placeholder id=$assistantPlaceholderId")

                        val found = localModelClient.handleLocalMessageWithAttachments(
                            _messages.value, imageAttachments, activeSystemPrompt,
                            createStreamingCallbacks(assistantPlaceholderId!!)
                        )
                        Log.i(TAG, "sendMessageWithAttachments: handleLocalMessageWithAttachments returned found=$found")

                        if (!found) {
                            removeMessage(assistantPlaceholderId!!)
                            val errorMsg = "No model configured.\n\nGo to Settings to configure a provider."
                            _lastError.value = errorMsg
                            addSystemMessage(errorMsg)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Message with attachments timed out", e)
                val errorMsg = "Request timed out. Check your network connection and try again."
                _lastError.value = errorMsg
                addSystemMessage(errorMsg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message with attachments", e)
                val errorMsg = "Error: ${e.message}"
                _lastError.value = errorMsg
                addSystemMessage(errorMsg)
            } finally {
                _isGenerating.value = false

                // Check if the assistant placeholder message is still empty
                if (assistantPlaceholderId != null) {
                    val msgs = _messages.value
                    val assistantMsg = msgs.find { it.id == assistantPlaceholderId }
                    if (assistantMsg != null && assistantMsg.content.isBlank()) {
                        Log.w(TAG, "sendMessageWithAttachments: assistant placeholder still empty, replacing with error")
                        val fallbackError = "No response received — check your API key and network connection."
                        _lastError.value = fallbackError
                        _messages.value = msgs.map { msg ->
                            if (msg.id == assistantPlaceholderId) msg.copy(content = fallbackError) else msg
                        }
                    }
                }

                Log.i(TAG, "sendMessageWithAttachments: finished, isGenerating=false")
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

                Log.i(TAG, "compactConversation: provider=$activeProvider, model=$activeModel")

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
                Log.i(TAG, "StreamingCallbacks.onComplete: contentLength=${fullContent.length}")
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
                Log.e(TAG, "StreamingCallbacks.onError: $error")
                val current = _streamingContent.value
                if (current.isBlank()) {
                    _messages.value = _messages.value.map { msg ->
                        if (msg.id == assistantId) msg.copy(content = "Error: $error") else msg
                    }
                }
                _streamingContent.value = ""
                _thinkingContent.value = ""
                _isGenerating.value = false
                _lastError.value = error
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

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            sessionManager.saveCurrentSessionMessages()
            sessionManager.updateCurrentSessionMetadata()
        }
        acpClient?.disconnect()
    }
}
