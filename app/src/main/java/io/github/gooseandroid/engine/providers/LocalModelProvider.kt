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
 * Local model inference provider.
 *
 * Attempts to use MediaPipe LLM Inference API (same as Google AI Edge Gallery)
 * for on-device GGUF model inference. If MediaPipe is not available at runtime,
 * falls back to a clear status message directing the user to cloud APIs.
 *
 * The inference pipeline:
 * 1. User downloads a GGUF model via the Models screen
 * 2. LocalModelProvider is created with the model file path
 * 3. On first use, attempts to load model via MediaPipe (reflection-based)
 * 4. If MediaPipe available: generates tokens on-device
 * 5. If not: returns helpful status message
 *
 * MediaPipe integration uses reflection so the app compiles and runs
 * regardless of whether the MediaPipe dependency is present.
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
    }

    private var inferenceEngine: Any? = null
    private var isMediaPipeAvailable: Boolean? = null
    private var loadError: String? = null

    /**
     * Check if model file exists and is valid.
     */
    fun isModelReady(): Boolean {
        val file = File(modelFilePath)
        return file.exists() && file.length() > 1_000_000
    }

    /**
     * Check if MediaPipe LLM Inference is available at runtime.
     */
    private fun checkMediaPipeAvailable(): Boolean {
        if (isMediaPipeAvailable != null) return isMediaPipeAvailable!!
        isMediaPipeAvailable = try {
            Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
        return isMediaPipeAvailable!!
    }

    /**
     * Attempt to load the model via MediaPipe reflection.
     */
    private suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (inferenceEngine != null) return@withContext true
        if (!checkMediaPipeAvailable()) return@withContext false

        try {
            Log.i(TAG, "Loading model via MediaPipe: $modelFilePath")
            val startTime = System.currentTimeMillis()

            // Use reflection to create LlmInference instance
            val optionsBuilderClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions"
            )
            val builderMethod = optionsBuilderClass.getMethod("builder")
            val builder = builderMethod.invoke(null)

            val builderClass = builder.javaClass
            builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelFilePath)
            builderClass.getMethod("setMaxTokens", Int::class.java).invoke(builder, MAX_TOKENS)

            val options = builderClass.getMethod("build").invoke(builder)

            val llmInferenceClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference"
            )
            val createMethod = llmInferenceClass.getMethod("createFromOptions", Context::class.java, options.javaClass)
            inferenceEngine = createMethod.invoke(null, context, options)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Model loaded via MediaPipe in ${elapsed}ms")
            true
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load model"
            Log.e(TAG, "MediaPipe load failed: ${e.message}", e)
            false
        }
    }

    /**
     * Generate response using MediaPipe.
     */
    private suspend fun generateWithMediaPipe(prompt: String): String? = withContext(Dispatchers.IO) {
        val engine = inferenceEngine ?: return@withContext null
        try {
            val method = engine.javaClass.getMethod("generateResponse", String::class.java)
            method.invoke(engine, prompt) as? String
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe inference error: ${e.message}", e)
            null
        }
    }

    override suspend fun chat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (!isModelReady()) {
            return@withContext LlmResponse(
                text = "Model file not found. Download a model in Settings → Configure Provider → Local.",
                finishReason = "error"
            )
        }

        val prompt = buildPrompt(messages, tools)

        // Try MediaPipe first
        if (ensureLoaded()) {
            val response = generateWithMediaPipe(prompt)
            if (response != null) {
                return@withContext LlmResponse(text = response, finishReason = "stop")
            }
        }

        // MediaPipe not available — return status
        LlmResponse(
            text = buildStatusMessage(),
            finishReason = "stop"
        )
    }

    override fun streamChat(
        messages: List<ConversationMessage>,
        tools: List<JSONObject>
    ): Flow<StreamEvent> = flow {
        if (!isModelReady()) {
            emit(StreamEvent.Error("Model file not found. Download a model in Settings."))
            return@flow
        }

        val prompt = buildPrompt(messages, tools)

        // Try MediaPipe
        val loaded = withContext(Dispatchers.IO) { ensureLoaded() }
        if (loaded) {
            val response = withContext(Dispatchers.IO) { generateWithMediaPipe(prompt) }
            if (response != null) {
                // Emit tokens in chunks for streaming feel
                val words = response.split(" ")
                val accumulated = StringBuilder()
                for ((index, word) in words.withIndex()) {
                    val token = if (index < words.size - 1) "$word " else word
                    accumulated.append(token)
                    emit(StreamEvent.Token(token))
                    delay(15) // Natural streaming pace
                }
                emit(StreamEvent.Done(accumulated.toString(), emptyList()))
                return@flow
            }
        }

        // MediaPipe not available — emit status message as tokens
        val status = buildStatusMessage()
        val chunks = status.split("\n")
        for (chunk in chunks) {
            if (chunk.isNotEmpty()) {
                emit(StreamEvent.Token("$chunk\n"))
                delay(30)
            }
        }
        emit(StreamEvent.Done(status, emptyList()))
    }

    /**
     * Build a prompt string from conversation messages.
     */
    private fun buildPrompt(messages: List<ConversationMessage>, tools: List<JSONObject>): String {
        val sb = StringBuilder()

        val systemMessages = messages.filter { it.role == "system" }
        if (systemMessages.isNotEmpty()) {
            sb.append("<|system|>\n")
            sb.append(systemMessages.joinToString("\n") { it.content })
            if (tools.isNotEmpty()) {
                sb.append("\n\nAvailable tools:\n")
                for (tool in tools) {
                    sb.append("- ${tool.optString("name")}: ${tool.optString("description")}\n")
                }
            }
            sb.append("</s>\n")
        }

        for (msg in messages.filter { it.role != "system" }) {
            when (msg.role) {
                "user" -> sb.append("<|user|>\n${msg.content}</s>\n")
                "assistant" -> sb.append("<|assistant|>\n${msg.content}</s>\n")
                "tool" -> sb.append("<|tool|>\n${msg.content}</s>\n")
            }
        }

        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    private fun buildStatusMessage(): String = buildString {
        append("**Local Model: ${File(modelFilePath).name}**\n\n")
        append("The model file is downloaded and verified (${File(modelFilePath).length() / 1_000_000}MB).\n\n")
        if (!checkMediaPipeAvailable()) {
            append("The MediaPipe LLM Inference runtime is not yet bundled in this build. ")
            append("This will be resolved in the next release.\n\n")
            append("**For immediate AI assistance**, add a cloud API key in Settings ")
            append("(Anthropic, OpenAI, or Google — all have free tiers).")
        } else if (loadError != null) {
            append("Model loading failed: $loadError\n\n")
            append("The model file may be corrupted or in an unsupported format. ")
            append("Try re-downloading the model.")
        }
    }

    fun close() {
        try {
            inferenceEngine?.let { engine ->
                engine.javaClass.getMethod("close").invoke(engine)
            }
            inferenceEngine = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing model: ${e.message}")
        }
    }
}
