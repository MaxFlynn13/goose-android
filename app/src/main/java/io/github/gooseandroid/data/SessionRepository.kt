package io.github.gooseandroid.data

import android.content.Context
import android.util.Log
import io.github.gooseandroid.data.models.ChatMessage
import io.github.gooseandroid.data.models.MessageRole
import io.github.gooseandroid.data.models.SessionInfo
import io.github.gooseandroid.data.models.ToolCall
import io.github.gooseandroid.data.models.ToolCallStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Handles all session and message persistence via JSON file I/O.
 */
class SessionRepository(private val context: Context) {

    companion object {
        private const val TAG = "SessionRepository"
        private const val SESSIONS_FILE = "sessions.json"
        private const val MESSAGES_DIR = "session_messages"
    }

    /**
     * Load all sessions from disk. Returns the list of SessionInfo.
     */
    suspend fun loadSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, SESSIONS_FILE)
            if (!file.exists()) return@withContext emptyList()

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

            loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            emptyList()
        }
    }

    /**
     * Save the given list of sessions to disk.
     */
    suspend fun saveSessions(sessions: List<SessionInfo>) = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            for (session in sessions) {
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

            val file = File(context.filesDir, SESSIONS_FILE)
            file.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions", e)
        }
    }

    /**
     * Get (and create if needed) the messages directory.
     */
    private fun getMessagesDir(): File {
        val dir = File(context.filesDir, MESSAGES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Save messages for a given session to disk.
     */
    suspend fun saveMessagesToDisk(sessionId: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
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

    /**
     * Load messages for a given session from disk.
     */
    suspend fun loadMessagesFromDisk(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            val file = File(getMessagesDir(), "$sessionId.json")
            if (!file.exists()) return@withContext emptyList()

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

    /**
     * Load messages from disk synchronously (for fork/export operations).
     * Should be called from a coroutine with IO dispatcher already.
     */
    fun loadMessagesFromDiskSync(sessionId: String): List<ChatMessage> {
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
            Log.e(TAG, "Failed to load messages (sync) for session $sessionId", e)
            emptyList()
        }
    }

    /**
     * Delete persisted messages for a session.
     */
    suspend fun deleteSessionMessages(sessionId: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(getMessagesDir(), "$sessionId.json")
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete messages for session $sessionId", e)
        }
    }

    /**
     * Convenience: save current session messages.
     */
    suspend fun saveCurrentSessionMessages(sessionId: String?, messages: List<ChatMessage>) {
        val id = sessionId ?: return
        saveMessagesToDisk(id, messages)
    }
}
