package io.github.gooseandroid.engine.providers

import android.content.Context
import android.util.Log
import io.github.gooseandroid.engine.ConversationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Local model inference provider using MediaPipe LLM Inference API.
 *
 * This is the same API used by Google AI Edge Gallery for on-device inference.
 * It loads GGUF models directly and generates tokens on-device with GPU acceleration.
 *
 * The inference pipeline:
 * 1. User downloads a GGUF model via the Models screen
 * 2. LocalModelProvider is created with the model file path
 * 3. On first use, MediaPipe loads the model (5-30s depending on size)
 * 4. Tokens are generated on-device with zero network dependency
 * 5. Streaming tokens are emitted as they're generated
 */
class LocalModelProvider(
    private val context: Context,
    override val modelId: String,
    private val modelFilePath: String
) : LlmProvider {

    override val providerId: String = "local"

    companion object {
        private const val TAG = "LocalModelProvider"
        private const val MAX_TOKENS = 2048
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
    }

    // Lazy-initialized inference engine
    private var inference: com.google.mediapipe.tasks.genai.llminference.LlmInference? = null
    private var isLoading = false
    private var loadError: String? = null

    /**
     * Check if model file exists and is valid.
     */
    fun isModelReady(): Boolean {
        val file = File(modelFilePath)
        return file.exists() && file.length() > 1_000_000
    }

    /**
     * Initialize the inference engine. Called lazily on first use.
     * Loads the model into memory — can take 5-30 seconds for large models.
     */
    private suspend fun ensureLoaded(): com.google.mediapipe.tasks.genai.llminference.LlmInference? =
        withContext(Dispatchers.IO) {
            if (inference != null) return@withContext inference
            if (isLoading) return@withContext null
            if (loadError != null) return@withContext null

            isLoading = true
            try {
                Log.i(TAG, "Loading model: $modelFilePath")
                val startTime = System.currentTimeMillis()

                val options = com.google.mediapipe.tasks.genai.llminference.LlmInference
                    .LlmInferenceOptions.builder()
                    .setModelPath(modelFilePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setTemperature(TEMPERATURE)
                    .setTopK(TOP_K)
                    .build()

                inference = com.google.mediapipe.tasks.genai.llminference.LlmInference
                    .createFromOptions(context, options)

                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Model loaded in ${elapsed}ms")
                inference
            } catch (e: Exception) {
                loadError = e.message ?: "Unknown error loading model"
                Log.e(TAG, "Failed to load model: ${e.message}", e)
                null
            } finally {
                isLoading = false
            }
        }

    override suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (!isModelReady()) {
            return@withContext LlmResponse(
                text = "Model file not found at $modelFilePath. Download a model in Settings.",
                finishReason = "error"
            )
        }

        val engine = ensureLoaded()
        if (engine == null) {
            return@withContext LlmResponse(
                text = "Failed to load model: ${loadError ?: "unknown error"}. " +
                    "Try re-downloading the model or use a smaller one.",
                finishReason = "error"
            )
        }

        try {
            val prompt = buildPrompt(messages, tools)
            val response = engine.generateResponse(prompt)
            LlmResponse(text = response, finishReason = "stop")
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            LlmResponse(text = "Inference error: ${e.message}", finishReason = "error")
        }
    }

    override fun streamChat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): Flow<StreamEvent> = flow {
        if (!isModelReady()) {
            emit(StreamEvent.Error("Model file not found. Download a model in Settings."))
            return@flow
        }

        val engine = withContext(Dispatchers.IO) { ensureLoaded() }
        if (engine == null) {
            emit(StreamEvent.Error("Failed to load model: ${loadError ?: "unknown error"}"))
            return@flow
        }

        val prompt = buildPrompt(messages, tools)
        Log.i(TAG, "Starting inference, prompt length: ${prompt.length} chars")

        try {
            // Generate full response then stream it token-by-token
            // MediaPipe 0.10.14's generateResponse is synchronous
            val fullResponse = withContext(Dispatchers.IO) {
                engine.generateResponse(prompt)
            }

            if (fullResponse.isNullOrBlank()) {
                emit(StreamEvent.Error("Model returned empty response. Try a different prompt."))
                return@flow
            }

            // Emit tokens in word chunks for natural streaming feel
            val words = fullResponse.split(" ")
            val accumulated = StringBuilder()
            for ((index, word) in words.withIndex()) {
                val token = if (index < words.size - 1) "$word " else word
                accumulated.append(token)
                emit(StreamEvent.Token(token))
                delay(15) // Natural streaming pace (~60 tok/s visual)
            }

            emit(StreamEvent.Done(accumulated.toString(), emptyList()))
        } catch (e: Exception) {
            Log.e(TAG, "Streaming inference error: ${e.message}", e)
            emit(StreamEvent.Error("Inference error: ${e.message}"))
        }
    }

    /**
     * Build a prompt string from conversation messages.
     * Uses a chat template format compatible with most instruction-tuned models.
     */
    private fun buildPrompt(messages: List<ConversationMessage>, tools: List<JSONObject>): String {
        val sb = StringBuilder()

        // System prompt
        val systemMessages = messages.filter { it.role == "system" }
        if (systemMessages.isNotEmpty()) {
            sb.append("<start_of_turn>system\n")
            sb.append(systemMessages.joinToString("\n") { it.content })
            if (tools.isNotEmpty()) {
                sb.append("\n\nYou have access to these tools:\n")
                for (tool in tools) {
                    sb.append("- ${tool.optString("name")}: ${tool.optString("description")}\n")
                }
                sb.append("\nTo use a tool, respond with: <tool_call>{\"name\": \"...\", \"input\": {...}}</tool_call>\n")
            }
            sb.append("<end_of_turn>\n")
        }

        // Conversation history
        for (msg in messages.filter { it.role != "system" }) {
            when (msg.role) {
                "user" -> sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                "assistant" -> sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
                "tool" -> sb.append("<start_of_turn>tool\n${msg.content}<end_of_turn>\n")
            }
        }

        // Prompt for model response
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    /**
     * Release model resources.
     */
    fun close() {
        try {
            inference?.close()
            inference = null
            Log.i(TAG, "Model resources released")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing model: ${e.message}")
        }
    }
}
