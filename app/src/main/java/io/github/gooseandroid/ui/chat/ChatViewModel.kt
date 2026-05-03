package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gooseandroid.data.SessionRepository
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.UUID

/**
 * ViewModel for the chat screen.
 *
 * Uses GooseEngineManager for all LLM communication with automatic failover
 * between the Rust binary engine and Kotlin native engine.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        val PROVIDER_MODELS: Map<String, List<String>> = PROVIDER_CATALOG
            .associate { it.id to it.models.map { m -> m.id } }
    }

    private val appContext = application.applicationContext
    private val settingsStore = SettingsStore(application)

    // ─── Engine ─────────────────────────────────────────────────────────────────
    private val engineManager = GooseEngineManager(appContext)
    val engineInfo: StateFlow<String> = engineManager.engineInfo

    // ─── Helpers ────────────────────────────────────────────────────────────────
    private val sessionRepository = SessionRepository(appContext)
    private val sessionManager = SessionManager(sessionRepository, settingsStore)

    // ─── State ──────────────────────────────────────────────────────────────────
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

    private var streamingJob: Job? = null
    private var activeSystemPrompt: String = ""

    // ─── Init ───────────────────────────────────────────────────────────────────

    init {
        setupCallbacks()
        loadSessions()
        viewModelScope.launch {
            engineManager.initialize()
            if (_sessions.value.isEmpty()) createNewSession()
        }
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
                        _messages.value = sessionRepository.loadMessagesFromDisk(it.id)
                    }
                }
            }
        }
    }

    // ─── Session Management ─────────────────────────────────────────────────────

    fun createNewSession() {
        val newId = UUID.randomUUID().toString()
        val newSession = SessionInfo(id = newId, title = "New Chat", createdAt = System.currentTimeMillis())
        _currentSessionId.value = newId
        _sessions.value = _sessions.value + newSession
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        _isLoadingSession.value = false
        viewModelScope.launch(Dispatchers.IO) { sessionManager.persistNewSession(newSession) }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            _isLoadingSession.value = true
            sessionManager.switchSession(sessionId)
            _isLoadingSession.value = false
        }
    }

    fun deleteSession(sessionId: String) { viewModelScope.launch { sessionManager.deleteSession(sessionId) } }
    fun renameSession(sessionId: String, newTitle: String) { viewModelScope.launch { sessionManager.renameSession(sessionId, newTitle) } }

    fun forkSession(sessionId: String) {
        viewModelScope.launch { _currentSessionId.value = sessionManager.forkSession(sessionId) }
    }

    fun exportSession(sessionId: String) {
        viewModelScope.launch { _exportedJson.value = sessionManager.exportSession(sessionId) }
    }

    fun clearExportedJson() { _exportedJson.value = null }
    fun clearLastError() { _lastError.value = null }

    // ─── Prompt & Persona ───────────────────────────────────────────────────────

    fun prefillPrompt(prompt: String) { _pendingPrompt.value = prompt }
    fun clearPendingPrompt() { _pendingPrompt.value = null }

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
        streamingJob?.cancel()
        try { engineManager.getEngine().cancel() } catch (_: Exception) {}
        _isGenerating.value = false
        _streamingContent.value = ""
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
        _messages.value = _messages.value + ChatMessage(
            id = UUID.randomUUID().toString(), role = MessageRole.SYSTEM, content = text
        )
    }

    fun getModelsForProvider(provider: String): List<String> = PROVIDER_MODELS[provider] ?: emptyList()

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

    fun editAndResend(messageId: String, newContent: String) {
        val idx = _messages.value.indexOfFirst { it.id == messageId }
        if (idx == -1) return
        _messages.value = _messages.value.subList(0, idx)
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        sendMessage(newContent)
    }

    // ─── Message Sending ────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (_currentSessionId.value == null) createNewSession()

        _messages.value = _messages.value + ChatMessage(
            id = UUID.randomUUID().toString(), role = MessageRole.USER, content = text
        )
        sessionManager.generateAutoTitle(text)
        launchEngineRequest(text)
    }

    fun sendMessageWithAttachments(text: String, attachments: List<AttachmentInfo>) {
        if (_currentSessionId.value == null) createNewSession()

        val contentBuilder = StringBuilder()
        for (a in attachments.filter { !it.isImage }) {
            contentBuilder.append("--- File: ${a.name} (${a.mimeType}) ---\n")
            contentBuilder.append(a.content)
            contentBuilder.append("\n--- End of ${a.name} ---\n\n")
        }
        if (text.isNotBlank()) contentBuilder.append(text)

        val imageAttachments = attachments.filter { it.isImage }
        val displayContent = if (imageAttachments.isNotEmpty()) {
            contentBuilder.toString() + "\n[Attached images: ${imageAttachments.joinToString(", ") { it.name }}]"
        } else contentBuilder.toString()

        _messages.value = _messages.value + ChatMessage(
            id = UUID.randomUUID().toString(), role = MessageRole.USER, content = displayContent
        )
        sessionManager.generateAutoTitle(text.ifBlank { attachments.firstOrNull()?.name ?: "Attachment" })
        launchEngineRequest(contentBuilder.toString())
    }

    /**
     * Core engine request: adds assistant placeholder, streams AgentEvents, updates UI.
     * Shared by sendMessage() and sendMessageWithAttachments().
     */
    private fun launchEngineRequest(messageText: String) {
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            yield() // let old job's finally block complete
            _isGenerating.value = true
            _streamingContent.value = ""
            _thinkingContent.value = ""
            _toolCalls.value = emptyList()
            _lastError.value = null

            val assistantId = addAssistantPlaceholder()
            val history = buildConversationHistory(_messages.value)

            try {
                engineManager.getEngine()
                    .sendMessage(messageText, history, activeSystemPrompt)
                    .collect { event -> handleAgentEvent(event, assistantId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in engine request", e)
                val errorMsg = "Error: ${e.message}"
                _lastError.value = errorMsg
                addSystemMessage(errorMsg)
            } finally {
                _isGenerating.value = false
                checkEmptyAssistant(assistantId)
                viewModelScope.launch {
                    sessionManager.saveCurrentSessionMessages()
                    sessionManager.updateCurrentSessionMetadata()
                }
            }
        }
    }

    /** Process a single AgentEvent and update UI state accordingly. */
    private fun handleAgentEvent(event: AgentEvent, assistantId: String) {
        when (event) {
            is AgentEvent.Token -> {
                _streamingContent.value = event.accumulated
                updateAssistantMessage(assistantId, event.accumulated)
            }
            is AgentEvent.Thinking -> {
                _thinkingContent.value = event.text
            }
            is AgentEvent.ToolStart -> {
                addToolCall(event.id, event.name, event.input)
                updateLastAssistantToolCalls()
            }
            is AgentEvent.ToolEnd -> {
                updateToolCall(event.id, event.output, event.isError)
                updateLastAssistantToolCalls()
            }
            is AgentEvent.Complete -> {
                val finalContent = event.fullText.ifBlank { _streamingContent.value }
                updateAssistantMessage(assistantId, finalContent, _thinkingContent.value)
                _streamingContent.value = ""
                _thinkingContent.value = ""
            }
            is AgentEvent.Error -> {
                _lastError.value = event.message
                addSystemMessage(event.message)
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
                val conversationText = _messages.value
                    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                    .joinToString("\n\n") { msg ->
                        val label = if (msg.role == MessageRole.USER) "User" else "Assistant"
                        "$label: ${msg.content}"
                    }
                val prompt = "Please provide a concise but comprehensive summary of the following conversation. " +
                    "Capture all key points, decisions, code snippets, and context needed to continue. " +
                    "Format it clearly.\n\n---\n\n$conversationText"

                val history = listOf(ConversationMessage(role = "user", content = prompt))
                val sb = StringBuilder()

                engineManager.getEngine().sendMessage(prompt, history, activeSystemPrompt)
                    .collect { event ->
                        when (event) {
                            is AgentEvent.Token -> sb.clear().append(event.accumulated)
                            is AgentEvent.Complete -> sb.clear().append(event.fullText)
                            else -> {}
                        }
                    }

                val summary = sb.toString()
                if (summary.isNotBlank()) {
                    _messages.value = listOf(
                        ChatMessage(UUID.randomUUID().toString(), MessageRole.SYSTEM,
                            "⚡ Conversation compacted. Previous messages have been summarized."),
                        ChatMessage(UUID.randomUUID().toString(), MessageRole.ASSISTANT, summary)
                    )
                    _toolCalls.value = emptyList()
                    sessionManager.saveCurrentSessionMessages()
                    sessionManager.updateCurrentSessionMetadata()
                } else {
                    addSystemMessage("Compact failed: received empty summary.")
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
        viewModelScope.launch(Dispatchers.IO) { sessionRepository.saveSessions(_sessions.value) }
    }

    private fun addAssistantPlaceholder(): String {
        val id = UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "")
        return id
    }

    private fun updateAssistantMessage(assistantId: String, content: String, thinking: String = "") {
        val msgs = _messages.value
        val last = msgs.lastIndex
        if (last >= 0 && msgs[last].id == assistantId) {
            val updated = if (thinking.isNotEmpty()) msgs[last].copy(content = content, thinking = thinking)
                          else msgs[last].copy(content = content)
            _messages.value = msgs.toMutableList().apply { this[last] = updated }
        } else {
            _messages.value = msgs.map { m ->
                if (m.id == assistantId) {
                    if (thinking.isNotEmpty()) m.copy(content = content, thinking = thinking) else m.copy(content = content)
                } else m
            }
        }
    }

    private fun addToolCall(id: String, name: String, input: String) {
        _toolCalls.value = _toolCalls.value + ToolCall(id = id, name = name, status = ToolCallStatus.RUNNING, input = input)
    }

    private fun updateToolCall(id: String, output: String, isError: Boolean) {
        val status = if (isError) ToolCallStatus.ERROR else ToolCallStatus.COMPLETE
        _toolCalls.value = _toolCalls.value.map { tc ->
            if (tc.id == id || (id.isBlank() && tc.status == ToolCallStatus.RUNNING))
                tc.copy(status = status, output = output)
            else tc
        }
    }

    private fun updateLastAssistantToolCalls() {
        val msgs = _messages.value
        val last = msgs.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        val updated = last.copy(toolCalls = _toolCalls.value.toList())
        _messages.value = msgs.map { if (it.id == last.id) updated else it }
    }

    /** If the assistant placeholder is still empty after streaming, fill it with an error. */
    private fun checkEmptyAssistant(assistantId: String) {
        val msg = _messages.value.find { it.id == assistantId } ?: return
        if (msg.content.isNotBlank()) return
        val fallback = _lastError.value ?: "No response received — check your API key and network connection."
        _lastError.value = fallback
        _messages.value = _messages.value.map { m ->
            if (m.id == assistantId) m.copy(content = fallback) else m
        }
    }

    /** Convert UI ChatMessages to engine ConversationMessages (excluding the latest user message). */
    private fun buildConversationHistory(messages: List<ChatMessage>): List<ConversationMessage> {
        val history = if (messages.isNotEmpty() && messages.last().role == MessageRole.USER)
            messages.dropLast(1) else messages

        return history.mapNotNull { msg ->
            if (msg.content.isBlank()) return@mapNotNull null
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }
            ConversationMessage(role = role, content = msg.content)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            sessionManager.saveCurrentSessionMessages()
            sessionManager.updateCurrentSessionMetadata()
            engineManager.shutdown()
        }
    }
}
