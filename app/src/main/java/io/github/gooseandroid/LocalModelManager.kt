package io.github.gooseandroid

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages local LLM models — download, storage, and lifecycle.
 *
 * Models are sourced from HuggingFace (litert-community) and run via
 * Google AI Edge LiteRT with hardware acceleration.
 *
 * Model catalog sourced from Google AI Edge Gallery:
 * https://github.com/nicola-carraro/proot-rs/ai-edge-gallery
 */
class LocalModelManager(private val context: Context) {

    companion object {
        private const val TAG = "LocalModelManager"
        private const val MODELS_DIR = "models"
        private const val HF_BASE = "https://huggingface.co"

        /**
         * Model catalog — real download URLs from HuggingFace litert-community.
         * Ordered by recommendation for Snapdragon 888 (12GB RAM).
         */
        val MODEL_CATALOG = listOf(
            // === Gemma 3 (Google, latest generation) ===
            ModelInfo(
                id = "gemma3-1b-it",
                name = "Gemma 3 1B Instruct",
                description = "Google's latest small model. Fast, efficient, great for quick tasks.",
                sizeBytes = 800_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
                filename = "gemma3-1b-it-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),
            ModelInfo(
                id = "gemma3-4b-it",
                name = "Gemma 3 4B Instruct",
                description = "Excellent balance of speed and intelligence. Best pick for most tasks.",
                sizeBytes = 2_500_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma3-4B-IT/resolve/main/gemma3-4b-it-int4.task",
                filename = "gemma3-4b-it-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = true
            ),
            ModelInfo(
                id = "gemma3-12b-it",
                name = "Gemma 3 12B Instruct",
                description = "High-quality reasoning. Needs more RAM but significantly smarter.",
                sizeBytes = 7_000_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma3-12B-IT/resolve/main/gemma3-12b-it-int4.task",
                filename = "gemma3-12b-it-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 8192,
                recommended = true
            ),

            // === Gemma 2 (previous gen, still good) ===
            ModelInfo(
                id = "gemma2-2b-it",
                name = "Gemma 2 2B Instruct",
                description = "Previous gen 2B model. Solid performance, well-tested.",
                sizeBytes = 1_500_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-int4.task",
                filename = "gemma2-2b-it-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 3072,
                recommended = false
            ),

            // === Llama 3.2 (Meta) ===
            ModelInfo(
                id = "llama-3.2-1b",
                name = "Llama 3.2 1B Instruct",
                description = "Meta's smallest Llama. Ultra-fast, good for simple tasks.",
                sizeBytes = 700_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Llama-3.2-1B-Instruct/resolve/main/llama-3.2-1b-instruct-int4.task",
                filename = "llama-3.2-1b-instruct-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),
            ModelInfo(
                id = "llama-3.2-3b",
                name = "Llama 3.2 3B Instruct",
                description = "Meta's 3B Llama. Good balance of speed and capability.",
                sizeBytes = 1_800_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Llama-3.2-3B-Instruct/resolve/main/llama-3.2-3b-instruct-int4.task",
                filename = "llama-3.2-3b-instruct-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = true
            ),

            // === Phi (Microsoft) ===
            ModelInfo(
                id = "phi-3.5-mini",
                name = "Phi 3.5 Mini (3.8B)",
                description = "Microsoft's compact model. Strong reasoning for its size.",
                sizeBytes = 2_300_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Phi-3.5-mini-instruct/resolve/main/phi-3.5-mini-instruct-int4.task",
                filename = "phi-3.5-mini-instruct-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = false
            )
        )
    }

    private val modelsDir: File = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Download state tracking
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    /**
     * List all downloaded models.
     */
    fun getDownloadedModels(): List<ModelInfo> {
        return MODEL_CATALOG.filter { model ->
            File(modelsDir, model.filename).exists()
        }
    }

    /**
     * List all available models with their download status.
     */
    fun getAvailableModels(): List<ModelStatus> {
        return MODEL_CATALOG.map { model ->
            val file = File(modelsDir, model.filename)
            ModelStatus(
                model = model,
                downloaded = file.exists(),
                fileSizeBytes = if (file.exists()) file.length() else 0
            )
        }
    }

    /**
     * Get the file path for a downloaded model.
     */
    fun getModelFile(model: ModelInfo): File {
        return File(modelsDir, model.filename)
    }

    /**
     * Download a model from HuggingFace.
     * Progress is emitted via the downloads StateFlow.
     */
    fun downloadModel(model: ModelInfo) {
        // Don't start duplicate downloads
        val currentState = _downloads.value[model.id]
        if (currentState is DownloadState.Downloading) return

        scope.launch {
            val outputFile = File(modelsDir, model.filename)
            val tempFile = File(modelsDir, "${model.filename}.tmp")

            try {
                _downloads.value = _downloads.value + (model.id to DownloadState.Downloading(0f))
                Log.i(TAG, "Starting download: ${model.name} from ${model.downloadUrl}")

                val url = URL(model.downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "GooseAndroid/0.1.0")

                // Support resume if temp file exists
                if (tempFile.exists()) {
                    connection.setRequestProperty("Range", "bytes=${tempFile.length()}-")
                }

                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw Exception("HTTP $responseCode: ${connection.responseMessage}")
                }

                val totalBytes = connection.contentLengthLong.let { len ->
                    if (len > 0) len + (if (tempFile.exists()) tempFile.length() else 0)
                    else model.sizeBytes // fallback to catalog size
                }

                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(tempFile, tempFile.exists())
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = if (tempFile.exists()) tempFile.length() else 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val progress = if (totalBytes > 0) {
                        (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else 0f

                    _downloads.value = _downloads.value + (model.id to DownloadState.Downloading(progress))
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                connection.disconnect()

                // Rename temp to final
                tempFile.renameTo(outputFile)

                Log.i(TAG, "Download complete: ${model.name} (${outputFile.length()} bytes)")
                _downloads.value = _downloads.value + (model.id to DownloadState.Complete)

            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${model.name}", e)
                _downloads.value = _downloads.value + (model.id to DownloadState.Error(e.message ?: "Unknown error"))
                // Don't delete temp file — allows resume on retry
            }
        }
    }

    /**
     * Cancel an in-progress download.
     */
    fun cancelDownload(model: ModelInfo) {
        _downloads.value = _downloads.value - model.id
        // The coroutine will fail on next write since we're not tracking the job
        // TODO: Track individual download jobs for proper cancellation
        val tempFile = File(modelsDir, "${model.filename}.tmp")
        tempFile.delete()
    }

    /**
     * Delete a downloaded model.
     */
    fun deleteModel(modelId: String): Boolean {
        val model = MODEL_CATALOG.find { it.id == modelId } ?: return false
        val file = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")
        val deleted = file.delete()
        tempFile.delete()
        _downloads.value = _downloads.value - modelId
        if (deleted) Log.i(TAG, "Deleted model: $modelId")
        return deleted
    }

    /**
     * Get available storage space for models.
     */
    fun getAvailableStorageMb(): Long {
        return modelsDir.freeSpace / (1024 * 1024)
    }

    /**
     * Get total space used by downloaded models.
     */
    fun getUsedStorageMb(): Long {
        return modelsDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() } / (1024 * 1024)
    }

    /**
     * Check device specs against model requirements.
     * Returns compatibility info as a WARNING only — never blocks download.
     */
    fun canRunModel(model: ModelInfo): ModelCompatibility {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availableRamMb = memInfo.availMem / (1024 * 1024)
        val hasEnoughStorage = getAvailableStorageMb() > (model.sizeBytes / (1024 * 1024))

        return ModelCompatibility(
            canRun = true, // NEVER block — user decides
            meetsRecommended = totalRamMb >= model.minRamMb,
            hasEnoughStorage = hasEnoughStorage,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            requiredRamMb = model.minRamMb.toLong()
        )
    }
}

// === Data Models ===

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val filename: String,
    val format: ModelFormat,
    val minRamMb: Int,
    val recommended: Boolean
)

enum class ModelFormat {
    LITERT,     // Google AI Edge LiteRT (.task file)
    GGUF        // llama.cpp format (fallback)
}

data class ModelStatus(
    val model: ModelInfo,
    val downloaded: Boolean,
    val fileSizeBytes: Long
)

data class ModelCompatibility(
    val canRun: Boolean,
    val meetsRecommended: Boolean,
    val hasEnoughStorage: Boolean,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val requiredRamMb: Long
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    data object Complete : DownloadState()
    data class Error(val message: String) : DownloadState()
}
