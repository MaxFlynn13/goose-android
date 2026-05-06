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
 * Databricks Model Serving provider implementation with full tool use (function calling) support.
 * Uses the OpenAI-compatible chat completions API exposed by Databricks Foundation Model APIs.
 *
 * Base URL format: {workspaceUrl}/serving-endpoints/{model}/invocations
 *
 * Supported models include:
 * - databricks-meta-llama-3-1-70b-instruct
 * - databricks-meta-llama-3-1-405b-instruct
 * - databricks-dbrx-instruct
 * - databricks-mixtral-8x7b-instruct
 * - Any custom model deployed to a Databricks serving endpoint
 */
class DatabricksProvider(
    private val apiToken: String,
    override val modelId: String,
    private val workspaceUrl: String
) : LlmProvider {

    override val providerId: String = "databricks"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val endpointUrl: String =
        "${workspaceUrl.trimEnd('/')}/serving-endpoints/$modelId/invocations"

    companion object {
        private const val TAG = "DatabricksProvider"
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

                if (response.code == 429 || response.code == 503) {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        ?: (2L * (retryCount + 1))
                    Log.w(TAG, "Rate limited (${response.code}). Retrying in ${retryAfter}s (attempt ${retryCount + 1}/$MAX_RETRIES)")
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

                if (response.code == 429 || response.code == 503) {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        ?: (2L * (retryCount + 1))
                    Log.w(TAG, "Rate limited on stream (${response.code}). Retrying in ${retryAfter}s (attempt ${retryCount + 1}/$MAX_RETRIES)")
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
                response.close()
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
                        reader.close()
                        response.close()
                        close()
                        return@callbackFlow
                    }
                    continue
                }

                try {
                    val chunk = JSONObject(data)
                    val choices = chunk.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) continue

                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue

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

                            if (id.isNotEmpty()) {
                                toolCallIds[index] = id
                                val name = function?.optString("name", "") ?: ""
                                toolCallNames[index] = name
                                toolInputBuffers[index] = StringBuilder()
                                trySend(StreamEvent.ToolCallStart(id, name))
                            }

                            val args = function?.optString("arguments", "") ?: ""
                            if (args.isNotEmpty()) {
                                toolInputBuffers[index]?.append(args)
                                val toolId = toolCallIds[index] ?: ""
                                trySend(StreamEvent.ToolCallInput(toolId, args))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
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
        body.put("stream", stream)

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
                    } else {
                        msgObj.put("content", JSONObject.NULL)
                    }

                    if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                        val toolCallsArray = JSONArray()
                        for (tc in msg.toolCalls) {
                            val toolCallObj = JSONObject()
                            toolCallObj.put("id", tc.id)
                            toolCallObj.put("type", "function")
                            val functionObj = JSONObject()
                            functionObj.put("name", tc.name)
                            functionObj.put("arguments", tc.input.toString())
                            toolCallObj.put("function", functionObj)
                            toolCallsArray.put(toolCallObj)
                        }
                        msgObj.put("tool_calls", toolCallsArray)
                    }
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
        if (tool.optString("type") == "function" && tool.has("function")) {
            return tool
        }

        val openaiTool = JSONObject()
        openaiTool.put("type", "function")

        val function = JSONObject()
        function.put("name", tool.optString("name", ""))
        function.put("description", tool.optString("description", ""))

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
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $apiToken")
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
