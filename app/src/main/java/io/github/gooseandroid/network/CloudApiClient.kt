package io.github.gooseandroid.network

import android.util.Log
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.data.models.AttachmentInfo
import io.github.gooseandroid.data.models.ChatMessage
import io.github.gooseandroid.data.models.MessageRole
import io.github.gooseandroid.data.models.PROVIDER_CATALOG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles ALL cloud API streaming calls (Anthropic, OpenAI, Google, Mistral, OpenRouter, Custom).
 * Communicates results via callbacks — no ViewModel dependency.
 */
class CloudApiClient(private val settingsStore: SettingsStore) {

    companion object {
        private const val TAG = "CloudApiClient"
        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 1000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Reference to the currently active streaming call, enabling cancellation. */
    private val activeCall = AtomicReference<Call?>(null)

    /**
     * Cancel the currently active streaming call, if any.
     */
    fun cancelActiveCall() {
        activeCall.getAndSet(null)?.cancel()
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
     * Uses PROVIDER_CATALOG as the single source of truth for settings key mapping.
     */
    suspend fun getApiKeyForProvider(provider: String): String {
        val providerInfo = PROVIDER_CATALOG.find { it.id == provider } ?: return ""
        val settingsKey = providerInfo.apiKeySettingsKey
        if (settingsKey.isBlank()) return ""
        return settingsStore.getString(settingsKey).first()
    }

    /**
     * Get the default model for a provider from the shared PROVIDER_CATALOG.
     */
    fun getDefaultModel(provider: String): String {
        return PROVIDER_CATALOG.find { it.id == provider }
            ?.models?.firstOrNull()?.id ?: "gpt-4o"
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
     * Execute an OkHttp call with a single retry on connection errors.
     * Stores the active call reference for cancellation support.
     * Returns the response (caller must close it).
     */
    private fun executeWithRetry(request: Request): okhttp3.Response {
        var lastException: IOException? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                Log.d(TAG, "Retrying request (attempt ${attempt + 1})...")
                Thread.sleep(RETRY_DELAY_MS)
            }
            val call = httpClient.newCall(request)
            activeCall.set(call)
            try {
                val response = call.execute()
                if (response.isSuccessful) {
                    return response
                }
                // Non-retryable HTTP errors (4xx, 5xx that aren't connection issues)
                // Return immediately so caller can read the error body
                return response
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Request failed (attempt ${attempt + 1}): ${e.message}")
                // Continue to retry on connection errors
            }
        }
        throw lastException ?: IOException("Request failed after ${MAX_RETRIES + 1} attempts")
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

                val request = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .build()

                val response = executeWithRetry(request)
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val error = resp.body?.string() ?: "HTTP ${resp.code}"
                        throw Exception("Anthropic API error (${resp.code}): $error")
                    }

                    val responseBody = resp.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    val contentArray = json.optJSONArray("content")
                    if (contentArray != null && contentArray.length() > 0) {
                        contentArray.getJSONObject(0).optString("text", "")
                    } else ""
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

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$resolvedModel:generateContent")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .build()

                val response = executeWithRetry(request)
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val error = resp.body?.string() ?: "HTTP ${resp.code}"
                        throw Exception("Google AI error (${resp.code}): $error")
                    }

                    val responseBody = resp.body?.string() ?: ""
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

                val requestBuilder = Request.Builder()
                    .url(endpoint)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                if (provider == "openrouter") {
                    requestBuilder.header("HTTP-Referer", "https://github.com/MaxFlynn13/goose-android")
                }

                val response = executeWithRetry(requestBuilder.build())
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val error = resp.body?.string() ?: "HTTP ${resp.code}"
                        throw Exception("API error (${resp.code}): $error")
                    }

                    val responseBody = resp.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.optJSONObject("message")
                        message?.optString("content", "") ?: ""
                    } else ""
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

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()

        val response = executeWithRetry(request)
        response.use { resp ->
            if (!resp.isSuccessful) {
                val error = resp.body?.string() ?: "HTTP ${resp.code}"
                throw Exception("Anthropic API error (${resp.code}): $error")
            }

            val accumulated = StringBuilder()
            resp.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
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

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $apiKey")
        for ((key, value) in extraHeaders) {
            requestBuilder.header(key, value)
        }

        val response = executeWithRetry(requestBuilder.build())
        response.use { resp ->
            if (!resp.isSuccessful) {
                val error = resp.body?.string() ?: "HTTP ${resp.code}"
                throw Exception("API error (${resp.code}): $error")
            }

            val accumulated = StringBuilder()
            resp.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
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

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("x-goog-api-key", apiKey)
            .build()

        val response = executeWithRetry(request)
        response.use { resp ->
            if (!resp.isSuccessful) {
                val error = resp.body?.string() ?: "HTTP ${resp.code}"
                throw Exception("Google AI error (${resp.code}): $error")
            }

            val accumulated = StringBuilder()
            resp.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
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
        }
    }
}
