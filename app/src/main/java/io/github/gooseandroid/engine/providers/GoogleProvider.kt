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
 *
 * Key differences from OpenAI/Anthropic:
 * - Tools are sent as `tools: [{ functionDeclarations: [...] }]`
 * - Schema types must be UPPERCASE: "STRING", "OBJECT", "ARRAY", "NUMBER", "BOOLEAN", "INTEGER"
 * - Tool call responses use `functionResponse` parts
 * - After a tool call, the assistant's functionCall is echoed as a `model` message,
 *   then the tool result is sent as a `user` message with `functionResponse` parts
 * - Streaming responses contain `functionCall` parts in candidate content
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

            Log.d(TAG, "Sending non-streaming request to $url")

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

            Log.d(TAG, "Sending streaming request to $url")

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
            var doneEmitted = false

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

                        // Handle function call parts (Google's tool call format)
                        val functionCall = part.optJSONObject("functionCall")
                        if (functionCall != null) {
                            val name = functionCall.optString("name", "")
                            val args = functionCall.optJSONObject("args") ?: JSONObject()
                            val id = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"

                            Log.d(TAG, "Stream: functionCall detected: $name")

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
                        doneEmitted = true
                        trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
                }
            }

            reader.close()
            response.close()

            // Always ensure Done is emitted exactly once
            if (!doneEmitted) {
                trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            trySend(StreamEvent.Error(e.message ?: "Unknown streaming error"))
        }

        close()
        awaitClose()
    }

    // ── Request building ────────────────────────────────────────────────────

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

        // Build contents array — Google requires alternating user/model roles
        // and tool results have a specific format
        val contents = buildContentsArray(nonSystemMessages)
        body.put("contents", contents)

        // Build tools array in Google format: [{ functionDeclarations: [...] }]
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

        Log.d(TAG, "Request body contents count: ${contents.length()}, tools: ${tools.size}")

        return body.toString()
    }

    /**
     * Build the contents array for Google's API.
     *
     * Google requires:
     * - Roles alternate between "user" and "model"
     * - After a tool call, the model's functionCall is echoed as a "model" message
     * - Tool results are sent as "user" messages with functionResponse parts
     * - Adjacent messages with the same role must be merged
     */
    private fun buildContentsArray(messages: List<ConversationMessage>): JSONArray {
        val contents = JSONArray()

        // We need to handle the conversion carefully:
        // - "user" → role: "user", text part
        // - "assistant" → role: "model", text part (may also have functionCall parts)
        // - "tool" → role: "user", functionResponse part
        //
        // Google requires that after a model message with functionCall,
        // the next message is a user message with functionResponse.
        // We also need to avoid consecutive same-role messages.

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]

            when (msg.role) {
                "user" -> {
                    val contentObj = JSONObject()
                    contentObj.put("role", "user")
                    val parts = JSONArray()
                    val textPart = JSONObject()
                    textPart.put("text", msg.content)
                    parts.put(textPart)
                    contentObj.put("parts", parts)
                    contents.put(contentObj)
                    i++
                }

                "assistant" -> {
                    val contentObj = JSONObject()
                    contentObj.put("role", "model")
                    val parts = JSONArray()

                    // Add text content if present
                    if (msg.content.isNotEmpty()) {
                        val textPart = JSONObject()
                        textPart.put("text", msg.content)
                        parts.put(textPart)
                    }

                    // Look ahead: if the next messages are "tool" results,
                    // it means this assistant message had tool calls.
                    // We need to reconstruct the functionCall parts for the model message
                    // and then create a user message with functionResponse parts.
                    val toolResults = mutableListOf<ConversationMessage>()
                    var j = i + 1
                    while (j < messages.size && messages[j].role == "tool") {
                        toolResults.add(messages[j])
                        j++
                    }

                    if (toolResults.isNotEmpty()) {
                        // Add functionCall parts to the model message for each tool result
                        for (toolResult in toolResults) {
                            val functionCallPart = JSONObject()
                            val functionCall = JSONObject()
                            functionCall.put("name", toolResult.toolName ?: "unknown")
                            // We don't have the original args, but Google needs the functionCall
                            // to match the functionResponse. Use an empty args object.
                            functionCall.put("args", JSONObject())
                            functionCallPart.put("functionCall", functionCall)
                            parts.put(functionCallPart)
                        }

                        // Ensure model message has at least one part
                        if (parts.length() == 0) {
                            val textPart = JSONObject()
                            textPart.put("text", " ")
                            parts.put(textPart)
                        }
                        contentObj.put("parts", parts)
                        contents.put(contentObj)

                        // Now create the user message with functionResponse parts
                        val userResponseObj = JSONObject()
                        userResponseObj.put("role", "user")
                        val responseParts = JSONArray()

                        for (toolResult in toolResults) {
                            val frPart = JSONObject()
                            val functionResponse = JSONObject()
                            functionResponse.put("name", toolResult.toolName ?: "unknown")

                            val responseContent = JSONObject()
                            // Try to parse content as JSON, otherwise wrap as text
                            try {
                                val parsed = JSONObject(toolResult.content)
                                responseContent.put("result", parsed)
                            } catch (e: Exception) {
                                val resultWrapper = JSONObject()
                                resultWrapper.put("output", toolResult.content)
                                responseContent.put("result", resultWrapper)
                            }
                            functionResponse.put("response", responseContent)
                            frPart.put("functionResponse", functionResponse)
                            responseParts.put(frPart)
                        }

                        userResponseObj.put("parts", responseParts)
                        contents.put(userResponseObj)

                        // Skip past the tool messages we already consumed
                        i = j
                    } else {
                        // No tool results follow — just a plain model message
                        if (parts.length() == 0) {
                            val textPart = JSONObject()
                            textPart.put("text", " ")
                            parts.put(textPart)
                        }
                        contentObj.put("parts", parts)
                        contents.put(contentObj)
                        i++
                    }
                }

                "tool" -> {
                    // Orphaned tool result (no preceding assistant message).
                    // This shouldn't normally happen, but handle it gracefully
                    // by wrapping it as a user message with functionResponse.
                    val contentObj = JSONObject()
                    contentObj.put("role", "user")
                    val parts = JSONArray()

                    val frPart = JSONObject()
                    val functionResponse = JSONObject()
                    functionResponse.put("name", msg.toolName ?: "unknown")
                    val responseContent = JSONObject()
                    try {
                        val parsed = JSONObject(msg.content)
                        responseContent.put("result", parsed)
                    } catch (e: Exception) {
                        val resultWrapper = JSONObject()
                        resultWrapper.put("output", msg.content)
                        responseContent.put("result", resultWrapper)
                    }
                    functionResponse.put("response", responseContent)
                    frPart.put("functionResponse", functionResponse)
                    parts.put(frPart)

                    contentObj.put("parts", parts)
                    contents.put(contentObj)
                    i++
                }

                else -> {
                    // Skip unknown roles
                    i++
                }
            }
        }

        // Google API requires the conversation to not have consecutive same-role messages.
        // Merge any that slipped through.
        return mergeConsecutiveSameRole(contents)
    }

    /**
     * Merge consecutive messages with the same role by combining their parts arrays.
     * Google's API rejects conversations with adjacent same-role messages.
     */
    private fun mergeConsecutiveSameRole(contents: JSONArray): JSONArray {
        if (contents.length() <= 1) return contents

        val merged = JSONArray()
        var current = contents.getJSONObject(0)

        for (i in 1 until contents.length()) {
            val next = contents.getJSONObject(i)
            if (current.optString("role") == next.optString("role")) {
                // Merge parts
                val currentParts = current.optJSONArray("parts") ?: JSONArray()
                val nextParts = next.optJSONArray("parts") ?: JSONArray()
                for (j in 0 until nextParts.length()) {
                    currentParts.put(nextParts.get(j))
                }
                current.put("parts", currentParts)
            } else {
                merged.put(current)
                current = next
            }
        }
        merged.put(current)

        return merged
    }

    // ── Tool schema conversion ──────────────────────────────────────────────

    /**
     * Convert a tool definition from the common OpenAI format to Google's format.
     *
     * Input format (OpenAI):
     * ```json
     * {
     *   "type": "function",
     *   "function": {
     *     "name": "shell",
     *     "description": "...",
     *     "parameters": { "type": "object", "properties": {...}, "required": [...] }
     *   }
     * }
     * ```
     *
     * Output format (Google functionDeclaration):
     * ```json
     * {
     *   "name": "shell",
     *   "description": "...",
     *   "parameters": { "type": "OBJECT", "properties": {...}, "required": [...] }
     * }
     * ```
     */
    private fun convertToolToGoogleFormat(tool: JSONObject): JSONObject {
        val declaration = JSONObject()

        // Extract from OpenAI wrapper if present
        val functionObj = tool.optJSONObject("function")
        if (functionObj != null) {
            declaration.put("name", functionObj.optString("name", ""))
            declaration.put("description", functionObj.optString("description", ""))

            val schema = functionObj.optJSONObject("parameters")
                ?: JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                }
            declaration.put("parameters", convertSchemaToGoogleFormat(schema))
        } else {
            // Flat format (name, description, parameters at top level)
            declaration.put("name", tool.optString("name", ""))
            declaration.put("description", tool.optString("description", ""))

            val schema = tool.optJSONObject("parameters")
                ?: tool.optJSONObject("input_schema")
                ?: JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                }
            declaration.put("parameters", convertSchemaToGoogleFormat(schema))
        }

        return declaration
    }

    /**
     * Recursively convert JSON Schema types to Google's UPPERCASE format.
     * Google requires: STRING, OBJECT, ARRAY, NUMBER, BOOLEAN, INTEGER
     */
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

        // Copy required array (Google uses the same format as OpenAI)
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

    // ── HTTP request building ───────────────────────────────────────────────

    private fun buildRequest(url: String, body: String): Request {
        return Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private fun parseFullResponse(json: JSONObject): LlmResponse {
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
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
                Log.d(TAG, "Parsed functionCall: $name with args: $args")
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
