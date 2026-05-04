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

    /**
     * Creates a new session synchronously (sets all state immediately).
     * Safe to call before sendMessage — the session ID is guaranteed to be set
     * by the time this function returns.
     */
    fun createNewSession() {
        val newId = UUID.randomUUID().toString()
        val newSession = SessionInfo(id = newId, title = "New Chat", createdAt = System.currentTimeMillis())
        _currentSessionId.value = newId
        _sessions.value = _sessions.value + newSession
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        _thinkingContent.value = ""
        _isLoadingSession.value = false
        _isGenerating.value = false
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
        viewModelScope.launch {
            try { engineManager.getEngine()?.cancel() } catch (_: Exception) {}
        }
        _isGenerating.value = false
        _streamingContent.value = ""
        _thinkingContent.value = ""
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _toolCalls.value = emptyList()
        _streamingContent.value = ""
        _thinkingContent.value = ""
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
        _thinkingContent.value = ""
        sendMessage(newContent)
    }

    // ─── Message Sending ────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        // Ensure session exists synchronously BEFORE anything else
        if (_currentSessionId.value == null) {
            createNewSession()
        }

        _messages.value = _messages.value + ChatMessage(
            id = UUID.randomUUID().toString(), role = MessageRole.USER, content = text
        )
        sessionManager.generateAutoTitle(text)
        launchEngineRequest(text)
    }

    fun sendMessageWithAttachments(text: String, attachments: List<AttachmentInfo>) {
        // Ensure session exists synchronously BEFORE anything else
        if (_currentSessionId.value == null) {
            createNewSession()
        }

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
        // Cancel any existing streaming job
        streamingJob?.cancel()

        streamingJob = viewModelScope.launch {
            yield() // let old job's finally block complete

            // Clear ALL streaming state at the START of each new message
            _isGenerating.value = true
            _streamingContent.value = ""
            _thinkingContent.value = ""
            _toolCalls.value = emptyList()
            _lastError.value = null

            // Create assistant placeholder with empty content
            val assistantId = addAssistantPlaceholder()

            // Build conversation history EXCLUDING system messages (they go via systemPrompt param)
            // and excluding the assistant placeholder we just added
            val history = buildConversationHistory(_messages.value)

            try {
                val engine = engineManager.getEngine()
                if (engine == null) {
                    val errorMsg = "No AI engine available. Configure an API key in Settings."
                    addSystemMessage(errorMsg)
                    _lastError.value = errorMsg
                    _isGenerating.value = false
                    return@launch
                }

                engine.sendMessage(messageText, history, activeSystemPrompt)
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
                viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                    sessionManager.saveCurrentSessionMessages()
                    sessionManager.updateCurrentSessionMetadata()
                }
            }
        }
    }

    /**
     * Process a single AgentEvent and update UI state accordingly.
     *
     * IMPORTANT: AgentEvent.Token contains the FULL accumulated text so far (not a delta).
     * We REPLACE the assistant message content with it — never append.
     */
    private fun handleAgentEvent(event: AgentEvent, assistantId: String) {
        when (event) {
            is AgentEvent.Token -> {
                // event.accumulated is the FULL text so far — replace, don't append
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
                // Finalize: use the Complete text if available, otherwise use what we accumulated
                val finalContent = event.fullText.ifBlank { _streamingContent.value }
                updateAssistantMessage(assistantId, finalContent, _thinkingContent.value)
                // Clear streaming state — message is finalized
                _streamingContent.value = ""
                _thinkingContent.value = ""
                _isGenerating.value = false
            }
            is AgentEvent.Error -> {
                // Show error as system message AND stop generating
                _lastError.value = event.message
                addSystemMessage(event.message)
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

                val engine = engineManager.getEngine() ?: run {
                    addSystemMessage("Engine not available for compaction.")
                    return@launch
                }
                engine.sendMessage(prompt, history, activeSystemPrompt)
                    .collect { event ->
                        when (event) {
                            is AgentEvent.Token -> {
                                sb.clear()
                                sb.append(event.accumulated)
                            }
                            is AgentEvent.Complete -> {
                                sb.clear()
                                sb.append(event.fullText)
                            }
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

    /**
     * Update the assistant message content by REPLACING it entirely.
     * This is critical because Token events contain accumulated (full) text, not deltas.
     */
    private fun updateAssistantMessage(assistantId: String, content: String, thinking: String = "") {
        val msgs = _messages.value
        val last = msgs.lastIndex
        if (last >= 0 && msgs[last].id == assistantId) {
            // Fast path: the assistant message is the last one (common case during streaming)
            val updated = if (thinking.isNotEmpty()) msgs[last].copy(content = content, thinking = thinking)
                          else msgs[last].copy(content = content)
            _messages.value = msgs.toMutableList().apply { this[last] = updated }
        } else {
            // Fallback: find by ID (e.g., system messages were added after the placeholder)
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

    /**
     * Convert UI ChatMessages to engine ConversationMessages for the LLM.
     *
     * Rules:
     * - System messages are NOT included (the system prompt is passed separately)
     * - The latest user message is excluded (it's passed as the `message` parameter)
     * - The assistant placeholder (empty content) is excluded
     * - Tool results from ToolCall data on assistant messages are included
     * - Messages with blank content are excluded (except tool role messages)
     */
    private fun buildConversationHistory(messages: List<ChatMessage>): List<ConversationMessage> {
        // Drop the last message if it's the user message we're about to send
        // Also drop the empty assistant placeholder we just added
        val relevant = messages.filter { msg ->
            // Exclude system messages — system prompt is passed separately
            if (msg.role == MessageRole.SYSTEM) return@filter false
            // Exclude empty assistant placeholders
            if (msg.role == MessageRole.ASSISTANT && msg.content.isBlank()) return@filter false
            true
        }

        // Drop the last user message (it's passed as the `message` param to the engine)
        val history = if (relevant.isNotEmpty() && relevant.last().role == MessageRole.USER) {
            relevant.dropLast(1)
        } else {
            relevant
        }

        val result = mutableListOf<ConversationMessage>()

        for (msg in history) {
            if (msg.content.isBlank() && msg.toolCalls.isEmpty()) continue

            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> continue // should not reach here, but safety
            }

            result.add(ConversationMessage(role = role, content = msg.content))

            // If this assistant message had tool calls, include the tool results
            if (msg.role == MessageRole.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                for (tc in msg.toolCalls) {
                    if (tc.output.isNotBlank()) {
                        result.add(ConversationMessage(
                            role = "tool",
                            content = tc.output,
                            toolCallId = tc.id,
                            toolName = tc.name
                        ))
                    }
                }
            }
        }

        return result
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
