package io.github.gooseandroid.network

import android.util.Log
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.data.models.AttachmentInfo
import io.github.gooseandroid.data.models.ChatMessage
import io.github.gooseandroid.data.models.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles ALL cloud API streaming calls (Anthropic, OpenAI, Google, Mistral, OpenRouter, Custom).
 * Communicates results via callbacks — no ViewModel dependency.
 */
class CloudApiClient(private val settingsStore: SettingsStore) {

    companion object {
        private const val TAG = "CloudApiClient"

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

    /**
     * Callbacks for streaming events.
     */
    interface StreamingCallbacks {
        fun onToken(token: String)
        fun onComplete(fullContent: String)
        fun onError(error: String)
        fun onToolCallStart(name: String)
        fun onToolCallEnd(name: String, output: String, isError: Boolean)
    }

    /**
     * Unified streaming call dispatcher. Routes to the appropriate provider.
     */
    suspend fun callCloudApiStreaming(
        provider: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        activeSystemPrompt: String,
        imageAttachments: List<AttachmentInfo> = emptyList(),
        callbacks: StreamingCallbacks
    ) {
        val resolvedModel = model.ifBlank { getDefaultModel(provider) }

        try {
            when (provider) {
                "anthropic" -> streamAnthropic(apiKey, resolvedModel, messages, activeSystemPrompt, imageAttachments, callbacks)
                "openai" -> streamOpenAI(apiKey, resolvedModel, messages, activeSystemPrompt,
                    "https://api.openai.com/v1/chat/completions", emptyMap(), imageAttachments, callbacks)
                "google" -> streamGoogle(apiKey, resolvedModel, messages, activeSystemPrompt, imageAttachments, callbacks)
                "mistral" -> streamOpenAI(apiKey, resolvedModel, messages, activeSystemPrompt,
                    "https://api.mistral.ai/v1/chat/completions", emptyMap(), imageAttachments, callbacks)
                "openrouter" -> streamOpenAI(apiKey, resolvedModel, messages, activeSystemPrompt,
                    "https://openrouter.ai/api/v1/chat/completions",
                    mapOf("HTTP-Referer" to "https://github.com/MaxFlynn13/goose-android"),
                    imageAttachments, callbacks)
                "custom" -> {
                    val baseUrl = settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_URL).first()
                    val customModel = settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_MODEL).first()
                    val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions"
                              else if (baseUrl.contains("/chat/completions")) baseUrl
                              else "$baseUrl/v1/chat/completions"
                    streamOpenAI(apiKey, customModel.ifBlank { resolvedModel }, messages, activeSystemPrompt,
                        url, emptyMap(), imageAttachments, callbacks)
                }
                else -> {
                    callbacks.onError("Unknown provider: $provider")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming API call failed ($provider)", e)
            callbacks.onError("API Error ($provider): ${e.message}")
        }
    }

    /**
     * Get the API key for a given provider from settings.
     */
    suspend fun getApiKeyForProvider(provider: String): String {
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

    /**
     * Get the default model for a provider.
     */
    fun getDefaultModel(provider: String): String {
        return PROVIDER_MODELS[provider]?.firstOrNull() ?: "gpt-4o"
    }

    /**
     * Build the conversation history as a list of role/content pairs,
     * filtering out system messages (which are UI-only notifications).
     */
    fun buildConversationHistory(messages: List<ChatMessage>): List<Pair<String, String>> {
        return messages
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

    /**
     * Non-streaming API call used for compact summarization.
     * Returns the response content as a string.
     */
    suspend fun callCloudApiForSummary(
        provider: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        activeSystemPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val resolvedModel = model.ifBlank { getDefaultModel(provider) }

        when (provider) {
            "anthropic" -> {
                val messagesArray = JSONArray()
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

    // ─── Streaming Implementations ──────────────────────────────────────────────

    /**
     * Stream from Anthropic Messages API with SSE.
     * Includes activeSystemPrompt as the top-level "system" field.
     * Supports multimodal content (images as base64).
     */
    private suspend fun streamAnthropic(
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        activeSystemPrompt: String,
        imageAttachments: List<AttachmentInfo>,
        callbacks: StreamingCallbacks
    ) = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()

        for (i in messages.indices) {
            val (role, content) = messages[i]
            val msgObj = JSONObject()
            msgObj.put("role", role)

            val isLastUserMessage = role == "user" && i == messages.lastIndex
            if (isLastUserMessage && imageAttachments.isNotEmpty()) {
                val contentArray = JSONArray()

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
                                    callbacks.onToken(accumulated.toString())
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
                        Log.w(TAG, "Skipping malformed SSE event: ${data.take(100)}")
                    }
                }
            }

            callbacks.onComplete(accumulated.toString())
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
        activeSystemPrompt: String,
        endpoint: String,
        extraHeaders: Map<String, String>,
        imageAttachments: List<AttachmentInfo>,
        callbacks: StreamingCallbacks
    ) = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray()

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

            val isLastUserMessage = role == "user" && i == messages.lastIndex
            if (isLastUserMessage && imageAttachments.isNotEmpty()) {
                val contentArray = JSONArray()

                val textPart = JSONObject()
                textPart.put("type", "text")
                textPart.put("text", content)
                contentArray.put(textPart)

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
                            callbacks.onToken(accumulated.toString())
                        }

                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason == "stop" || finishReason == "end_turn") break
                    } catch (e: org.json.JSONException) {
                        Log.w(TAG, "Skipping malformed SSE event: ${data.take(100)}")
                    }
                }
            }

            callbacks.onComplete(accumulated.toString())
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
        activeSystemPrompt: String,
        imageAttachments: List<AttachmentInfo>,
        callbacks: StreamingCallbacks
    ) = withContext(Dispatchers.IO) {
        val contentsArray = JSONArray()

        if (activeSystemPrompt.isNotBlank()) {
            val sysContent = JSONObject()
            sysContent.put("role", "user")
            val sysParts = JSONArray()
            val sysPart = JSONObject()
            sysPart.put("text", "[System Instructions]: $activeSystemPrompt")
            sysParts.put(sysPart)
            sysContent.put("parts", sysParts)
            contentsArray.put(sysContent)

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

            val isLastUserMessage = role == "user" && i == messages.lastIndex
            if (isLastUserMessage && imageAttachments.isNotEmpty()) {
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
                                callbacks.onToken(accumulated.toString())
                            }
                        }
                    } catch (e: org.json.JSONException) {
                        Log.w(TAG, "Skipping malformed SSE event: ${data.take(100)}")
                    }
                }
            }

            callbacks.onComplete(accumulated.toString())
        } finally {
            conn.disconnect()
        }
    }
}
