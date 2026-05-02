package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import io.github.gooseandroid.data.SessionRepository
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages session lifecycle: creation, switching, deletion, renaming, forking, export.
 * Pure logic — no Android ViewModel dependency.
 * Communicates state changes via return values and callbacks.
 */
class SessionManager(
    private val sessionRepository: SessionRepository,
    private val settingsStore: SettingsStore
) {

    /**
     * Callbacks for state changes that need to be applied in the ViewModel.
     */
    interface Callbacks {
        fun getSessions(): List<SessionInfo>
        fun setSessions(sessions: List<SessionInfo>)
        fun getCurrentSessionId(): String?
        fun setCurrentSessionId(id: String?)
        fun getMessages(): List<ChatMessage>
        fun setMessages(messages: List<ChatMessage>)
        fun setToolCalls(toolCalls: List<ToolCall>)
        fun setStreamingContent(content: String)
        fun setIsGenerating(generating: Boolean)
        fun saveSessions()
    }

    private var callbacks: Callbacks? = null

    fun setCallbacks(cb: Callbacks) {
        callbacks = cb
    }

    /**
     * Persist a new session that was already created synchronously in the ViewModel.
     * This handles the I/O portion (saving sessions to disk).
     */
    suspend fun persistNewSession(session: SessionInfo) {
        val cb = callbacks ?: return
        sessionRepository.saveSessions(cb.getSessions())
    }

    /**
     * Create a new session. Returns the new session ID.
     */
    suspend fun createNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        val provider = settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first()
        val model = settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first()

        val session = SessionInfo(
            id = sessionId,
            title = "New Chat",
            createdAt = System.currentTimeMillis(),
            messageCount = 0,
            lastMessage = "",
            providerId = provider,
            modelId = model
        )

        val cb = callbacks ?: return sessionId
        cb.setSessions(cb.getSessions() + session)
        cb.setCurrentSessionId(sessionId)
        cb.setMessages(emptyList())
        cb.setToolCalls(emptyList())
        cb.setStreamingContent("")
        cb.saveSessions()
        return sessionId
    }

    /**
     * Switch to a different session. Saves current state first.
     */
    suspend fun switchSession(sessionId: String) {
        val cb = callbacks ?: return
        if (sessionId == cb.getCurrentSessionId()) return

        // Save current messages before switching
        saveCurrentSessionMessages()
        updateCurrentSessionMetadata()

        cb.setCurrentSessionId(sessionId)
        cb.setToolCalls(emptyList())
        cb.setStreamingContent("")
        cb.setIsGenerating(false)

        // Load messages for the target session
        val loaded = sessionRepository.loadMessagesFromDisk(sessionId)
        cb.setMessages(loaded)
    }

    /**
     * Delete a session. If it's the current session, switches to another or creates new.
     */
    suspend fun deleteSession(sessionId: String) {
        val cb = callbacks ?: return
        cb.setSessions(cb.getSessions().filter { it.id != sessionId })
        sessionRepository.deleteSessionMessages(sessionId)

        if (cb.getCurrentSessionId() == sessionId) {
            val remaining = cb.getSessions()
            if (remaining.isNotEmpty()) {
                switchSession(remaining.last().id)
            } else {
                createNewSession()
            }
        }
        cb.saveSessions()
    }

    /**
     * Rename a session.
     */
    suspend fun renameSession(sessionId: String, newTitle: String) {
        val cb = callbacks ?: return
        cb.setSessions(cb.getSessions().map {
            if (it.id == sessionId) it.copy(title = newTitle) else it
        })
        cb.saveSessions()
    }

    /**
     * Fork (duplicate) a session: creates a new session with copies of all messages.
     * Returns the new session ID.
     */
    suspend fun forkSession(sessionId: String): String {
        val cb = callbacks ?: return ""

        // Save current session state first
        saveCurrentSessionMessages()
        updateCurrentSessionMetadata()

        // Load source session messages from disk
        val sourceMessages = withContext(Dispatchers.IO) {
            sessionRepository.loadMessagesFromDiskSync(sessionId)
        }

        // If the source is the current session and disk was empty, use in-memory messages
        val messagesToCopy = if (sourceMessages.isEmpty() && sessionId == cb.getCurrentSessionId()) {
            cb.getMessages()
        } else {
            sourceMessages
        }

        // Find the source session metadata
        val sourceSession = cb.getSessions().find { it.id == sessionId }

        // Create the new session
        val newSessionId = UUID.randomUUID().toString()
        val provider = settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first()
        val model = settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first()

        val newSession = SessionInfo(
            id = newSessionId,
            title = (sourceSession?.title ?: "New Chat") + " (fork)",
            createdAt = System.currentTimeMillis(),
            messageCount = messagesToCopy.size,
            lastMessage = messagesToCopy.lastOrNull { it.role == MessageRole.USER }?.content?.take(100) ?: "",
            providerId = sourceSession?.providerId ?: provider,
            modelId = sourceSession?.modelId ?: model
        )

        cb.setSessions(cb.getSessions() + newSession)

        // Copy messages with new IDs
        val copiedMessages = messagesToCopy.map { msg ->
            msg.copy(id = UUID.randomUUID().toString())
        }

        // Save the copied messages to disk for the new session
        sessionRepository.saveMessagesToDisk(newSessionId, copiedMessages)

        // Switch to the new session
        cb.setCurrentSessionId(newSessionId)
        cb.setMessages(copiedMessages)
        cb.setToolCalls(emptyList())
        cb.setStreamingContent("")

        cb.saveSessions()
        return newSessionId
    }

    /**
     * Export a session's messages as a JSON string.
     */
    suspend fun exportSession(sessionId: String): String {
        val cb = callbacks ?: return "{}"

        // Get messages: either from memory (if current) or from disk
        val messagesToExport = if (sessionId == cb.getCurrentSessionId()) {
            cb.getMessages()
        } else {
            withContext(Dispatchers.IO) {
                sessionRepository.loadMessagesFromDiskSync(sessionId)
            }
        }

        val sessionInfo = cb.getSessions().find { it.id == sessionId }

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

    /**
     * Update current session metadata (message count, last message).
     */
    suspend fun updateCurrentSessionMetadata() {
        val cb = callbacks ?: return
        val currentId = cb.getCurrentSessionId() ?: return
        val msgs = cb.getMessages()
        val lastUserMsg = msgs.lastOrNull { it.role == MessageRole.USER }?.content ?: ""

        cb.setSessions(cb.getSessions().map { session ->
            if (session.id == currentId) {
                session.copy(
                    messageCount = msgs.size,
                    lastMessage = lastUserMsg.take(100)
                )
            } else session
        })
        cb.saveSessions()
    }

    /**
     * Save current session messages to disk.
     */
    suspend fun saveCurrentSessionMessages() {
        val cb = callbacks ?: return
        val sessionId = cb.getCurrentSessionId() ?: return
        sessionRepository.saveMessagesToDisk(sessionId, cb.getMessages())
    }

    /**
     * Generate an auto-title from the first user message if needed.
     * Returns true if title was updated.
     */
    fun generateAutoTitle(firstMessage: String): Boolean {
        val cb = callbacks ?: return false
        val currentId = cb.getCurrentSessionId() ?: return false
        val session = cb.getSessions().find { it.id == currentId } ?: return false

        // Only auto-title if it's still the default "New Chat" and this is the first user message
        if (session.title != "New Chat") return false
        val userMessages = cb.getMessages().count { it.role == MessageRole.USER }
        if (userMessages > 1) return false // Only on first message (the one we just added)

        val title = generateTitle(firstMessage)
        cb.setSessions(cb.getSessions().map {
            if (it.id == currentId) it.copy(title = title) else it
        })
        cb.saveSessions()
        return true
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
}
