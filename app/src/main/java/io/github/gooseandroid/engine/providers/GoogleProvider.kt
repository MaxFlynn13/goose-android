package io.github.gooseandroid.engine.providers

import android.util.Log
import io.github.gooseandroid.engine.ConversationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Google Gemini provider implementation with full tool use (function calling) support.
 * Uses the Generative Language API.
 */
class GoogleProvider(
    private val apiKey: String,
    override val modelId: String
) : LlmProvider {

    override val providerId: String = "google"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GoogleProvider"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    override suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val body = buildRequestBody(messages, tools)
            val url = "$BASE_URL/$modelId:generateContent"
            val request = buildRequest(url, body)

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code}: $responseBody")
                return@withContext LlmResponse(
                    text = "",
                    finishReason = "error",
                    toolCalls = emptyList()
                )
            }

            parseFullResponse(JSONObject(responseBody))
        } catch (e: Exception) {
            Log.e(TAG, "Chat error: ${e.message}", e)
            LlmResponse(text = "", finishReason = "error")
        }
    }

    override fun streamChat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): Flow<StreamEvent> = callbackFlow {
        try {
            val body = buildRequestBody(messages, tools)
            val url = "$BASE_URL/$modelId:streamGenerateContent?alt=sse"
            val request = buildRequest(url, body)

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Stream HTTP ${response.code}: $errorBody")
                trySend(StreamEvent.Error("HTTP ${response.code}: $errorBody"))
                close()
                return@callbackFlow
            }

            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            val fullText = StringBuilder()
            val toolCalls = mutableListOf<LlmToolCall>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data.isEmpty()) continue

                try {
                    val chunk = JSONObject(data)
                    val candidates = chunk.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) continue

                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue

                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // Handle text parts
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) {
                            fullText.append(text)
                            trySend(StreamEvent.Token(text))
                        }

                        // Handle function call parts
                        val functionCall = part.optJSONObject("functionCall")
                        if (functionCall != null) {
                            val name = functionCall.optString("name", "")
                            val args = functionCall.optJSONObject("args") ?: JSONObject()
                            val id = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"

                            trySend(StreamEvent.ToolCallStart(id, name))
                            trySend(StreamEvent.ToolCallInput(id, args.toString()))

                            val toolCall = LlmToolCall(id, name, args)
                            toolCalls.add(toolCall)
                            trySend(StreamEvent.ToolCallEnd(id, name, args))
                        }
                    }

                    // Check for finish reason
                    val finishReason = candidate.optString("finishReason", "")
                    if (finishReason == "STOP" || finishReason == "MAX_TOKENS") {
                        trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
                }
            }

            // If we didn't get a finish reason event, emit Done anyway
            if (toolCalls.isNotEmpty() || fullText.isNotEmpty()) {
                // Only emit if we haven't already (check by seeing if Done was sent)
                // Since Done is terminal, the flow will close after
            }

            reader.close()
            response.close()

            // Always ensure Done is emitted
            trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            trySend(StreamEvent.Error(e.message ?: "Unknown streaming error"))
        }

        close()
        awaitClose()
    }

    private fun buildRequestBody(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): String {
        val body = JSONObject()

        // Extract system prompt
        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }

        if (systemMessages.isNotEmpty()) {
            val systemInstruction = JSONObject()
            val parts = JSONArray()
            val textPart = JSONObject()
            textPart.put("text", systemMessages.joinToString("\n") { it.content })
            parts.put(textPart)
            systemInstruction.put("parts", parts)
            body.put("systemInstruction", systemInstruction)
        }

        // Build contents array
        val contents = JSONArray()
        for (msg in nonSystemMessages) {
            val contentObj = buildContentFromMessage(msg)
            if (contentObj != null) {
                contents.put(contentObj)
            }
        }
        body.put("contents", contents)

        // Build tools array in Google format
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            val toolDeclarations = JSONObject()
            val functionDeclarations = JSONArray()

            for (tool in tools) {
                functionDeclarations.put(convertToolToGoogleFormat(tool))
            }
            toolDeclarations.put("functionDeclarations", functionDeclarations)
            toolsArray.put(toolDeclarations)
            body.put("tools", toolsArray)
        }

        // Generation config
        val generationConfig = JSONObject()
        generationConfig.put("maxOutputTokens", 8192)
        body.put("generationConfig", generationConfig)

        return body.toString()
    }

    private fun buildContentFromMessage(msg: ConversationMessage): JSONObject? {
        val contentObj = JSONObject()
        val parts = JSONArray()

        when (msg.role) {
            "user" -> {
                contentObj.put("role", "user")
                val textPart = JSONObject()
                textPart.put("text", msg.content)
                parts.put(textPart)
            }

            "assistant" -> {
                contentObj.put("role", "model")
                if (msg.content.isNotEmpty()) {
                    val textPart = JSONObject()
                    textPart.put("text", msg.content)
                    parts.put(textPart)
                }
            }

            "tool" -> {
                // Tool results are sent as user messages with functionResponse parts
                contentObj.put("role", "user")
                val functionResponse = JSONObject()
                functionResponse.put("name", msg.toolName ?: "unknown")
                val responseContent = JSONObject()
                // Try to parse content as JSON, otherwise wrap in a text field
                try {
                    val parsed = JSONObject(msg.content)
                    responseContent.put("result", parsed)
                } catch (e: Exception) {
                    responseContent.put("result", msg.content)
                }
                functionResponse.put("response", responseContent)

                val part = JSONObject()
                part.put("functionResponse", functionResponse)
                parts.put(part)
            }

            else -> return null
        }

        if (parts.length() == 0) return null
        contentObj.put("parts", parts)
        return contentObj
    }

    private fun convertToolToGoogleFormat(tool: JSONObject): JSONObject {
        val declaration = JSONObject()
        declaration.put("name", tool.optString("name", ""))
        declaration.put("description", tool.optString("description", ""))

        // Get the schema from either key
        val schema = tool.optJSONObject("parameters")
            ?: tool.optJSONObject("input_schema")
            ?: JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            }

        // Convert schema types to uppercase for Google format
        val googleSchema = convertSchemaToGoogleFormat(schema)
        declaration.put("parameters", googleSchema)

        return declaration
    }

    private fun convertSchemaToGoogleFormat(schema: JSONObject): JSONObject {
        val result = JSONObject()

        // Convert type to uppercase
        val type = schema.optString("type", "object")
        result.put("type", type.uppercase())

        // Convert properties recursively
        val properties = schema.optJSONObject("properties")
        if (properties != null) {
            val googleProperties = JSONObject()
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val prop = properties.optJSONObject(key)
                if (prop != null) {
                    googleProperties.put(key, convertSchemaToGoogleFormat(prop))
                }
            }
            result.put("properties", googleProperties)
        }

        // Copy required array
        val required = schema.optJSONArray("required")
        if (required != null) {
            result.put("required", required)
        }

        // Copy description
        val description = schema.optString("description", "")
        if (description.isNotEmpty()) {
            result.put("description", description)
        }

        // Copy enum
        val enumValues = schema.optJSONArray("enum")
        if (enumValues != null) {
            result.put("enum", enumValues)
        }

        // Handle items for array types
        val items = schema.optJSONObject("items")
        if (items != null) {
            result.put("items", convertSchemaToGoogleFormat(items))
        }

        return result
    }

    private fun buildRequest(url: String, body: String): Request {
        return Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseFullResponse(json: JSONObject): LlmResponse {
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            // Check for error
            val error = json.optJSONObject("error")
            if (error != null) {
                val errorMsg = error.optString("message", "Unknown error")
                Log.e(TAG, "API error: $errorMsg")
                return LlmResponse(text = "", finishReason = "error")
            }
            return LlmResponse(text = "", finishReason = "error")
        }

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val finishReason = candidate.optString("finishReason", "STOP")
        val parts = content?.optJSONArray("parts") ?: JSONArray()

        val textParts = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)

            // Text part
            val text = part.optString("text", "")
            if (text.isNotEmpty()) {
                textParts.append(text)
            }

            // Function call part
            val functionCall = part.optJSONObject("functionCall")
            if (functionCall != null) {
                val name = functionCall.optString("name", "")
                val args = functionCall.optJSONObject("args") ?: JSONObject()
                val id = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"
                toolCalls.add(LlmToolCall(id, name, args))
            }
        }

        return LlmResponse(
            text = textParts.toString(),
            toolCalls = toolCalls,
            finishReason = when (finishReason) {
                "STOP" -> "stop"
                "MAX_TOKENS" -> "length"
                "SAFETY" -> "safety"
                else -> finishReason.lowercase()
            }
        )
    }
}
