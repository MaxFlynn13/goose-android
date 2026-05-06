package io.github.gooseandroid.engine.providers

import android.util.Log
import io.github.gooseandroid.engine.ConversationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude provider implementation with full tool use support.
 * Uses the Messages API with SSE streaming.
 *
 * Conversation history format required by Anthropic:
 * ```
 * [assistant] → content: [{type: "text", text: "..."}, {type: "tool_use", id: "...", name: "...", input: {...}}]
 * [user]      → content: [{type: "tool_result", tool_use_id: "...", content: "..."}]
 * ```
 */
class AnthropicProvider(
    private val apiKey: String,
    override val modelId: String
) : LlmProvider {

    override val providerId: String = "anthropic"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "AnthropicProvider"
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 8192
        private const val MAX_RETRIES = 3
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    override suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val body = buildRequestBody(messages, tools, stream = false)
            val request = buildRequest(body)

            var retryCount = 0
            while (retryCount < MAX_RETRIES) {
                val response = client.newCall(request).execute()

                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        ?: (2L * (retryCount + 1))
                    Log.w(TAG, "Rate limited. Retrying in ${retryAfter}s (attempt ${retryCount + 1}/$MAX_RETRIES)")
                    response.close()
                    delay(retryAfter * 1000)
                    retryCount++
                    continue
                }

                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code}: $responseBody")
                    return@withContext LlmResponse(
                        text = "",
                        finishReason = "error",
                        toolCalls = emptyList()
                    )
                }

                return@withContext parseFullResponse(JSONObject(responseBody))
            }

            // Exhausted retries
            Log.e(TAG, "Exhausted $MAX_RETRIES retries due to rate limiting")
            LlmResponse(text = "", finishReason = "error", toolCalls = emptyList())
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
            val body = buildRequestBody(messages, tools, stream = true)
            val request = buildRequest(body)

            var retryCount = 0
            var response: okhttp3.Response? = null

            while (retryCount < MAX_RETRIES) {
                response = client.newCall(request).execute()

                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        ?: (2L * (retryCount + 1))
                    Log.w(TAG, "Rate limited on stream. Retrying in ${retryAfter}s (attempt ${retryCount + 1}/$MAX_RETRIES)")
                    response.close()
                    delay(retryAfter * 1000)
                    retryCount++
                    continue
                }
                break
            }

            if (response == null) {
                trySend(StreamEvent.Error("Failed to get response after $MAX_RETRIES retries"))
                close()
                return@callbackFlow
            }

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Stream HTTP ${response.code}: $errorBody")
                trySend(StreamEvent.Error("HTTP ${response.code}: $errorBody"))
                close()
                return@callbackFlow
            }

            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            val fullText = StringBuilder()
            val thinkingText = StringBuilder()
            val toolCalls = mutableListOf<LlmToolCall>()
            val toolInputBuffers = mutableMapOf<Int, StringBuilder>()
            val toolCallIds = mutableMapOf<Int, String>()
            val toolCallNames = mutableMapOf<Int, String>()
            var currentBlockIndex = -1
            var currentBlockType = ""

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data.isEmpty() || data == "[DONE]") continue

                try {
                    val event = JSONObject(data)
                    val type = event.optString("type", "")

                    when (type) {
                        "content_block_start" -> {
                            currentBlockIndex = event.optInt("index", -1)
                            val contentBlock = event.optJSONObject("content_block")
                            currentBlockType = contentBlock?.optString("type", "") ?: ""

                            if (currentBlockType == "tool_use") {
                                val toolId = contentBlock?.optString("id", "") ?: ""
                                val toolName = contentBlock?.optString("name", "") ?: ""
                                toolCallIds[currentBlockIndex] = toolId
                                toolCallNames[currentBlockIndex] = toolName
                                toolInputBuffers[currentBlockIndex] = StringBuilder()
                                trySend(StreamEvent.ToolCallStart(toolId, toolName))
                            }
                        }

                        "content_block_delta" -> {
                            val index = event.optInt("index", currentBlockIndex)
                            val delta = event.optJSONObject("delta") ?: continue
                            val deltaType = delta.optString("type", "")

                            when (deltaType) {
                                "text_delta" -> {
                                    val text = delta.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        fullText.append(text)
                                        trySend(StreamEvent.Token(text))
                                    }
                                }

                                "thinking_delta" -> {
                                    val thinking = delta.optString("thinking", "")
                                    if (thinking.isNotEmpty()) {
                                        thinkingText.append(thinking)
                                        trySend(StreamEvent.Thinking(thinking))
                                    }
                                }

                                "input_json_delta" -> {
                                    val partialJson = delta.optString("partial_json", "")
                                    if (partialJson.isNotEmpty()) {
                                        toolInputBuffers[index]?.append(partialJson)
                                        val toolId = toolCallIds[index] ?: ""
                                        trySend(StreamEvent.ToolCallInput(toolId, partialJson))
                                    }
                                }
                            }
                        }

                        "content_block_stop" -> {
                            val index = event.optInt("index", currentBlockIndex)
                            val toolId = toolCallIds[index]
                            val toolName = toolCallNames[index]
                            val inputBuffer = toolInputBuffers[index]

                            if (toolId != null && toolName != null && inputBuffer != null) {
                                val inputJson = try {
                                    JSONObject(inputBuffer.toString())
                                } catch (e: Exception) {
                                    JSONObject()
                                }
                                val toolCall = LlmToolCall(toolId, toolName, inputJson)
                                toolCalls.add(toolCall)
                                trySend(StreamEvent.ToolCallEnd(toolId, toolName, inputJson))
                            }
                        }

                        "message_stop" -> {
                            trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
                            // CRITICAL: break out of the read loop immediately.
                            // The server may keep the connection alive (HTTP keep-alive)
                            // but we have all the data we need.
                            reader.close()
                            response.close()
                            close()
                            return@callbackFlow
                        }

                        "error" -> {
                            val errorObj = event.optJSONObject("error")
                            val errorMsg = errorObj?.optString("message", "Unknown error") ?: "Unknown error"
                            Log.e(TAG, "Stream error event: $errorMsg")
                            trySend(StreamEvent.Error(errorMsg))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SSE event: ${e.message}")
                }
            }

            reader.close()
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            trySend(StreamEvent.Error("${e.javaClass.simpleName}: ${e.message ?: "Connection failed"}"))
        }

        close()
        awaitClose()
    }

    private fun buildRequestBody(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>,
        stream: Boolean
    ): String {
        val body = JSONObject()
        body.put("model", modelId)
        body.put("max_tokens", MAX_TOKENS)
        body.put("stream", stream)

        // Extract system prompt from messages
        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }

        if (systemMessages.isNotEmpty()) {
            body.put("system", systemMessages.joinToString("\n") { it.content })
        }

        // Build messages array
        val messagesArray = JSONArray()
        val groupedMessages = groupMessagesForAnthropic(nonSystemMessages)
        for (msg in groupedMessages) {
            messagesArray.put(msg)
        }
        body.put("messages", messagesArray)

        // Build tools array in Anthropic format
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(convertToolToAnthropicFormat(tool))
            }
            body.put("tools", toolsArray)
        }

        return body.toString()
    }

    /**
     * Groups conversation messages into the format Anthropic expects:
     *
     * - Assistant messages include tool_use content blocks when they have tool calls
     * - Consecutive tool result messages are grouped into a single user message
     *   with tool_result content blocks
     * - The sequence is: [assistant with tool_use blocks] → [user with tool_result blocks]
     */
    private fun groupMessagesForAnthropic(messages: List<ConversationMessage>): List<JSONObject> {
        val result = mutableListOf<JSONObject>()

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]

            when (msg.role) {
                "user" -> {
                    val msgObj = JSONObject()
                    msgObj.put("role", "user")
                    val contentArray = JSONArray()
                    val textBlock = JSONObject()
                    textBlock.put("type", "text")
                    textBlock.put("text", msg.content)
                    contentArray.put(textBlock)
                    msgObj.put("content", contentArray)
                    result.add(msgObj)
                }

                "assistant" -> {
                    val msgObj = JSONObject()
                    msgObj.put("role", "assistant")
                    val contentArray = JSONArray()

                    // Add text content if present
                    if (msg.content.isNotEmpty()) {
                        val textBlock = JSONObject()
                        textBlock.put("type", "text")
                        textBlock.put("text", msg.content)
                        contentArray.put(textBlock)
                    }

                    // If this assistant message has associated tool calls, add them
                    // as tool_use content blocks. This is critical for Anthropic's format:
                    // the assistant message MUST contain the tool_use blocks that correspond
                    // to the tool_result blocks in the following user message.
                    if (msg.toolCalls != null) {
                        for (tc in msg.toolCalls) {
                            val toolUseBlock = JSONObject()
                            toolUseBlock.put("type", "tool_use")
                            toolUseBlock.put("id", tc.id)
                            toolUseBlock.put("name", tc.name)
                            toolUseBlock.put("input", tc.input)
                            contentArray.put(toolUseBlock)
                        }
                    }

                    // Anthropic requires at least one content block in assistant messages
                    if (contentArray.length() == 0) {
                        val textBlock = JSONObject()
                        textBlock.put("type", "text")
                        textBlock.put("text", " ")
                        contentArray.put(textBlock)
                    }

                    msgObj.put("content", contentArray)
                    result.add(msgObj)
                }

                "tool" -> {
                    // Tool results go in a user message with tool_result content blocks.
                    // Collect all consecutive tool messages into one user message.
                    val msgObj = JSONObject()
                    msgObj.put("role", "user")
                    val contentArray = JSONArray()

                    val toolResultBlock = JSONObject()
                    toolResultBlock.put("type", "tool_result")
                    toolResultBlock.put("tool_use_id", msg.toolCallId ?: "")
                    toolResultBlock.put("content", msg.content)
                    contentArray.put(toolResultBlock)

                    // Collect consecutive tool results into the same user message
                    var j = i + 1
                    while (j < messages.size && messages[j].role == "tool") {
                        val nextTool = messages[j]
                        val nextBlock = JSONObject()
                        nextBlock.put("type", "tool_result")
                        nextBlock.put("tool_use_id", nextTool.toolCallId ?: "")
                        nextBlock.put("content", nextTool.content)
                        contentArray.put(nextBlock)
                        j++
                    }

                    msgObj.put("content", contentArray)
                    result.add(msgObj)
                    i = j - 1 // Will be incremented at end of loop
                }
            }
            i++
        }

        return result
    }

    private fun convertToolToAnthropicFormat(tool: JSONObject): JSONObject {
        val anthropicTool = JSONObject()

        // Handle both OpenAI format (nested under "function") and flat format
        val functionObj = tool.optJSONObject("function")
        if (functionObj != null) {
            // OpenAI format: { "type": "function", "function": { "name": ..., "parameters": ... } }
            anthropicTool.put("name", functionObj.optString("name", ""))
            anthropicTool.put("description", functionObj.optString("description", ""))
            val schema = functionObj.optJSONObject("parameters")
                ?: JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            anthropicTool.put("input_schema", schema)
        } else {
            // Flat format: { "name": ..., "description": ..., "input_schema": ... }
            anthropicTool.put("name", tool.optString("name", ""))
            anthropicTool.put("description", tool.optString("description", ""))
            val schema = tool.optJSONObject("input_schema")
                ?: tool.optJSONObject("parameters")
                ?: JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            anthropicTool.put("input_schema", schema)
        }

        return anthropicTool
    }

    private fun buildRequest(body: String): Request {
        return Request.Builder()
            .url(BASE_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseFullResponse(json: JSONObject): LlmResponse {
        val contentArray = json.optJSONArray("content") ?: JSONArray()
        val stopReason = json.optString("stop_reason", "stop")

        val textParts = StringBuilder()
        val thinkingParts = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()

        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            when (block.optString("type", "")) {
                "text" -> textParts.append(block.optString("text", ""))
                "thinking" -> thinkingParts.append(block.optString("thinking", ""))
                "tool_use" -> {
                    val id = block.optString("id", "")
                    val name = block.optString("name", "")
                    val input = block.optJSONObject("input") ?: JSONObject()
                    toolCalls.add(LlmToolCall(id, name, input))
                }
            }
        }

        return LlmResponse(
            text = textParts.toString(),
            thinking = thinkingParts.toString(),
            toolCalls = toolCalls,
            finishReason = stopReason
        )
    }
}
