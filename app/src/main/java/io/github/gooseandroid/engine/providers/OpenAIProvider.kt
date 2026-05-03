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
import java.util.concurrent.TimeUnit

/**
 * OpenAI provider implementation with full tool use (function calling) support.
 * Compatible with OpenAI API and any OpenAI-compatible endpoint.
 */
class OpenAIProvider(
    private val apiKey: String,
    override val modelId: String,
    private val baseUrl: String = "https://api.openai.com/v1"
) : LlmProvider {

    override val providerId: String = "openai"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "OpenAIProvider"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    override suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            val body = buildRequestBody(messages, tools, stream = false)
            val request = buildRequest(body)

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
            val body = buildRequestBody(messages, tools, stream = true)
            val request = buildRequest(body)

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
            val toolCallIds = mutableMapOf<Int, String>()
            val toolCallNames = mutableMapOf<Int, String>()
            val toolInputBuffers = mutableMapOf<Int, StringBuilder>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data.isEmpty() || data == "[DONE]") {
                    if (data == "[DONE]") {
                        // Finalize any pending tool calls
                        for ((index, inputBuffer) in toolInputBuffers) {
                            val id = toolCallIds[index] ?: ""
                            val name = toolCallNames[index] ?: ""
                            val inputJson = try {
                                JSONObject(inputBuffer.toString())
                            } catch (e: Exception) {
                                JSONObject()
                            }
                            val toolCall = LlmToolCall(id, name, inputJson)
                            toolCalls.add(toolCall)
                            trySend(StreamEvent.ToolCallEnd(id, name, inputJson))
                        }
                        trySend(StreamEvent.Done(fullText.toString(), toolCalls.toList()))
                    }
                    continue
                }

                try {
                    val chunk = JSONObject(data)
                    val choices = chunk.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) continue

                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue
                    val finishReason = choice.optString("finish_reason", "")

                    // Handle text content
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        fullText.append(content)
                        trySend(StreamEvent.Token(content))
                    }

                    // Handle tool calls
                    val deltaToolCalls = delta.optJSONArray("tool_calls")
                    if (deltaToolCalls != null) {
                        for (i in 0 until deltaToolCalls.length()) {
                            val tc = deltaToolCalls.getJSONObject(i)
                            val index = tc.optInt("index", 0)
                            val id = tc.optString("id", "")
                            val function = tc.optJSONObject("function")

                            // If we have an id, this is the start of a new tool call
                            if (id.isNotEmpty()) {
                                toolCallIds[index] = id
                                val name = function?.optString("name", "") ?: ""
                                toolCallNames[index] = name
                                toolInputBuffers[index] = StringBuilder()
                                trySend(StreamEvent.ToolCallStart(id, name))
                            }

                            // Accumulate function arguments
                            val args = function?.optString("arguments", "") ?: ""
                            if (args.isNotEmpty()) {
                                toolInputBuffers[index]?.append(args)
                                val toolId = toolCallIds[index] ?: ""
                                trySend(StreamEvent.ToolCallInput(toolId, args))
                            }
                        }
                    }

                    // Handle finish with tool_calls reason
                    if (finishReason == "tool_calls" || finishReason == "stop") {
                        // Tool calls will be finalized at [DONE]
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
                }
            }

            reader.close()
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}", e)
            trySend(StreamEvent.Error(e.message ?: "Unknown streaming error"))
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
        body.put("stream", stream)

        // Build messages array
        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            when (msg.role) {
                "system" -> {
                    msgObj.put("role", "system")
                    msgObj.put("content", msg.content)
                }
                "user" -> {
                    msgObj.put("role", "user")
                    msgObj.put("content", msg.content)
                }
                "assistant" -> {
                    msgObj.put("role", "assistant")
                    if (msg.content.isNotEmpty()) {
                        msgObj.put("content", msg.content)
                    }
                    // If this assistant message has tool calls encoded, they would be
                    // handled by the conversation history manager
                }
                "tool" -> {
                    msgObj.put("role", "tool")
                    msgObj.put("content", msg.content)
                    msgObj.put("tool_call_id", msg.toolCallId ?: "")
                }
                else -> {
                    msgObj.put("role", msg.role)
                    msgObj.put("content", msg.content)
                }
            }
            messagesArray.put(msgObj)
        }
        body.put("messages", messagesArray)

        // Build tools array in OpenAI format
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(convertToolToOpenAIFormat(tool))
            }
            body.put("tools", toolsArray)
        }

        return body.toString()
    }

    private fun convertToolToOpenAIFormat(tool: JSONObject): JSONObject {
        val openaiTool = JSONObject()
        openaiTool.put("type", "function")

        val function = JSONObject()
        function.put("name", tool.optString("name", ""))
        function.put("description", tool.optString("description", ""))

        // Accept either "parameters" or "input_schema" as the schema key
        val schema = tool.optJSONObject("parameters")
            ?: tool.optJSONObject("input_schema")
            ?: JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        function.put("parameters", schema)

        openaiTool.put("function", function)
        return openaiTool
    }

    private fun buildRequest(body: String): Request {
        return Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseFullResponse(json: JSONObject): LlmResponse {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return LlmResponse(text = "", finishReason = "error")
        }

        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message") ?: JSONObject()
        val finishReason = choice.optString("finish_reason", "stop")

        val text = message.optString("content", "") ?: ""
        val toolCalls = mutableListOf<LlmToolCall>()

        val toolCallsArray = message.optJSONArray("tool_calls")
        if (toolCallsArray != null) {
            for (i in 0 until toolCallsArray.length()) {
                val tc = toolCallsArray.getJSONObject(i)
                val id = tc.optString("id", "")
                val function = tc.optJSONObject("function")
                val name = function?.optString("name", "") ?: ""
                val argsStr = function?.optString("arguments", "{}") ?: "{}"
                val input = try {
                    JSONObject(argsStr)
                } catch (e: Exception) {
                    JSONObject()
                }
                toolCalls.add(LlmToolCall(id, name, input))
            }
        }

        return LlmResponse(
            text = text,
            toolCalls = toolCalls,
            finishReason = finishReason
        )
    }
}
