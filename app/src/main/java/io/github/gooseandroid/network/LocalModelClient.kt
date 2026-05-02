package io.github.gooseandroid.network

import android.util.Log
import io.github.gooseandroid.GoosePortHolder
import io.github.gooseandroid.LocalModelManager
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.data.models.AttachmentInfo
import io.github.gooseandroid.data.models.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles local model inference via the LiteRT server and routing logic
 * for local-only / cloud API mode.
 */
class LocalModelClient(
    private val settingsStore: SettingsStore,
    private val modelManager: LocalModelManager,
    private val cloudApiClient: CloudApiClient
) {

    companion object {
        private const val TAG = "LocalModelClient"
    }

    /**
     * Result of a local model call.
     */
    data class LocalModelResult(
        val content: String,
        val isError: Boolean = false,
        val errorMessage: String? = null
    )

    /**
     * Handle message when in local-only mode.
     * Priority: Active provider > any configured key > local model > error
     *
     * Returns true if a provider was found and called, false if no provider available.
     * When using cloud API, streaming is handled via callbacks.
     */
    suspend fun handleLocalMessage(
        messages: List<ChatMessage>,
        activeSystemPrompt: String,
        callbacks: CloudApiClient.StreamingCallbacks
    ): Boolean {
        val activeProvider = settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first()
        val activeModel = settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first()

        val conversationMessages = cloudApiClient.buildConversationHistory(messages)

        // If an active provider is explicitly set, use it
        if (activeProvider.isNotBlank()) {
            val apiKey = cloudApiClient.getApiKeyForProvider(activeProvider)
            if (apiKey.isNotBlank() || activeProvider == "custom" || activeProvider == "local") {
                cloudApiClient.callCloudApiStreaming(
                    activeProvider, apiKey, activeModel, conversationMessages,
                    activeSystemPrompt, emptyList(), callbacks
                )
                return true
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
                cloudApiClient.callCloudApiStreaming("anthropic", anthropicKey, "claude-sonnet-4-20250514",
                    conversationMessages, activeSystemPrompt, emptyList(), callbacks)
                return true
            }
            openaiKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("openai", openaiKey, "gpt-4o",
                    conversationMessages, activeSystemPrompt, emptyList(), callbacks)
                return true
            }
            googleKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("google", googleKey, "gemini-2.0-flash",
                    conversationMessages, activeSystemPrompt, emptyList(), callbacks)
                return true
            }
            mistralKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("mistral", mistralKey, "mistral-large-latest",
                    conversationMessages, activeSystemPrompt, emptyList(), callbacks)
                return true
            }
            openrouterKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("openrouter", openrouterKey,
                    "anthropic/claude-sonnet-4-20250514", conversationMessages, activeSystemPrompt,
                    emptyList(), callbacks)
                return true
            }
        }

        // No cloud key — try local model
        val localModelId = settingsStore.getLocalModelId().first()
        val downloadedModels = modelManager.getDownloadedModels()
        val localModel = downloadedModels.find { it.id == localModelId }

        if (localModel != null) {
            val modelFile = modelManager.getModelFile(localModel)
            if (modelFile.exists()) {
                val result = callLocalModel(messages, localModel.id, activeSystemPrompt)
                if (result.isError) {
                    callbacks.onError(result.errorMessage ?: "Local model error")
                } else {
                    callbacks.onComplete(result.content)
                }
                return true
            }
        }

        return false
    }

    /**
     * Handle message with image attachments in local-only / cloud API mode.
     * Returns true if a provider was found, false otherwise.
     */
    suspend fun handleLocalMessageWithAttachments(
        messages: List<ChatMessage>,
        imageAttachments: List<AttachmentInfo>,
        activeSystemPrompt: String,
        callbacks: CloudApiClient.StreamingCallbacks
    ): Boolean {
        val activeProvider = settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER).first()
        val activeModel = settingsStore.getString(SettingsKeys.ACTIVE_MODEL).first()

        val conversationMessages = cloudApiClient.buildConversationHistory(messages)

        if (activeProvider.isNotBlank()) {
            val apiKey = cloudApiClient.getApiKeyForProvider(activeProvider)
            if (apiKey.isNotBlank() || activeProvider == "custom" || activeProvider == "local") {
                cloudApiClient.callCloudApiStreaming(
                    activeProvider, apiKey, activeModel, conversationMessages,
                    activeSystemPrompt, imageAttachments, callbacks
                )
                return true
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
                cloudApiClient.callCloudApiStreaming("anthropic", anthropicKey, "claude-sonnet-4-20250514",
                    conversationMessages, activeSystemPrompt, imageAttachments, callbacks)
                return true
            }
            openaiKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("openai", openaiKey, "gpt-4o",
                    conversationMessages, activeSystemPrompt, imageAttachments, callbacks)
                return true
            }
            googleKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("google", googleKey, "gemini-2.0-flash",
                    conversationMessages, activeSystemPrompt, imageAttachments, callbacks)
                return true
            }
            mistralKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("mistral", mistralKey, "mistral-large-latest",
                    conversationMessages, activeSystemPrompt, imageAttachments, callbacks)
                return true
            }
            openrouterKey.isNotBlank() -> {
                cloudApiClient.callCloudApiStreaming("openrouter", openrouterKey,
                    "anthropic/claude-sonnet-4-20250514", conversationMessages, activeSystemPrompt,
                    imageAttachments, callbacks)
                return true
            }
            else -> return false
        }
    }

    /**
     * Call the local LiteRT inference server using OpenAI-compatible endpoint.
     * Includes activeSystemPrompt as the first system message.
     */
    suspend fun callLocalModel(
        messages: List<ChatMessage>,
        modelId: String,
        activeSystemPrompt: String
    ): LocalModelResult = withContext(Dispatchers.IO) {
        try {
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

                if (activeSystemPrompt.isNotBlank()) {
                    val sysMsg = JSONObject()
                    sysMsg.put("role", "system")
                    sysMsg.put("content", activeSystemPrompt)
                    messagesArray.put(sysMsg)
                }

                for ((role, content) in cloudApiClient.buildConversationHistory(messages)) {
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
                    return@withContext LocalModelResult(
                        content = "",
                        isError = true,
                        errorMessage = "Local model error ($responseCode): $error"
                    )
                }

                val responseBody = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)
                val choices = json.getJSONArray("choices")
                if (choices.length() == 0) {
                    return@withContext LocalModelResult(content = "No response from local model")
                }
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                LocalModelResult(content = message.getString("content"))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local model call failed", e)
            LocalModelResult(
                content = "",
                isError = true,
                errorMessage = "Local model error: ${e.message}\n\nThe inference engine may still be loading."
            )
        }
    }
}
