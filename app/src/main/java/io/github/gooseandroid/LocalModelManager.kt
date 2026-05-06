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
import java.util.concurrent.ConcurrentHashMap

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

        // Singleton download scope — survives screen navigation
        private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val _globalDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

        // Track download jobs for proper cancellation
        private val downloadJobs = ConcurrentHashMap<String, Job>()

        /**
         * Model catalog — using GGUF models from ungated HuggingFace repos.
         *
         * The litert-community models require HuggingFace auth (401 error).
         * Instead we use GGUF quantized models which are:
         * - Ungated (no auth required)
         * - Widely available
         * - Compatible with llama.cpp (which LiteRT can also load via conversion)
         *
         * Sources:
         * - bartowski (popular GGUF quantizer, ungated)
         * - unsloth (fast inference optimized, ungated)
         * - lmstudio-community (ungated, tested)
         *
         * Once Google AI Edge Gallery publishes ungated .task files,
         * we'll add those as preferred options.
         */
        /**
         * Model catalog using MediaPipe/LiteRT-compatible formats.
         *
         * MediaPipe LlmInference supports:
         * - .bin files (MediaPipe GPU format, from AI Edge Model Garden)
         * - .tflite files (TensorFlow Lite format)
         *
         * These are sourced from Google's official AI Edge repositories.
         * Note: Some models require HuggingFace authentication.
         * We use publicly available models where possible.
         */
        val MODEL_CATALOG = listOf(
            // === Gemma 2 2B (Google, MediaPipe-optimized) ===
            ModelInfo(
                id = "gemma2-2b-it-gpu",
                name = "Gemma 2 2B (GPU)",
                description = "Google's Gemma 2 optimized for MediaPipe GPU inference. Fast and efficient.",
                sizeBytes = 1_300_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-gpu-int4.bin",
                filename = "gemma2-2b-it-gpu-int4.bin",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),

            // === Gemma 3 1B (Google, smallest, fastest) ===
            ModelInfo(
                id = "gemma3-1b-it-gpu",
                name = "Gemma 3 1B (GPU)",
                description = "Google's smallest Gemma 3. Ultra-fast inference on mobile GPU.",
                sizeBytes = 900_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
                filename = "gemma3-1b-it-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),

            // === Gemma 3 4B (Google, recommended) ===
            ModelInfo(
                id = "gemma3-4b-it-gpu",
                name = "Gemma 3 4B (GPU)",
                description = "Best balance of speed and intelligence. Recommended for most tasks.",
                sizeBytes = 2_500_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma3-4B-IT/resolve/main/gemma3-4b-it-int4.task",
                filename = "gemma3-4b-it-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = true
            ),

            // === Gemma 3 12B (Google, high quality) ===
            ModelInfo(
                id = "gemma3-12b-it-gpu",
                name = "Gemma 3 12B (GPU)",
                description = "High-quality reasoning. Needs 8GB+ RAM but significantly smarter.",
                sizeBytes = 6_800_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Gemma3-12B-IT/resolve/main/gemma3-12b-it-int4.task",
                filename = "gemma3-12b-it-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 8192,
                recommended = false
            ),

            // === Llama 3.2 1B (Meta, via LiteRT community) ===
            ModelInfo(
                id = "llama-3.2-1b-gpu",
                name = "Llama 3.2 1B (GPU)",
                description = "Meta's smallest Llama, optimized for MediaPipe. Ultra-fast.",
                sizeBytes = 800_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Llama-3.2-1B-Instruct/resolve/main/llama-3.2-1b-instruct-int4.task",
                filename = "llama-3.2-1b-instruct-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),

            // === Llama 3.2 3B (Meta, via LiteRT community) ===
            ModelInfo(
                id = "llama-3.2-3b-gpu",
                name = "Llama 3.2 3B (GPU)",
                description = "Meta's 3B Llama for MediaPipe. Good balance of speed and capability.",
                sizeBytes = 2_000_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Llama-3.2-3B-Instruct/resolve/main/llama-3.2-3b-instruct-int4.task",
                filename = "llama-3.2-3b-instruct-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = true
            ),

            // === Phi 3.5 Mini (Microsoft, via LiteRT community) ===
            ModelInfo(
                id = "phi-3.5-mini-gpu",
                name = "Phi 3.5 Mini (GPU)",
                description = "Microsoft's compact model. Strong reasoning for its size.",
                sizeBytes = 2_200_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Phi-3.5-mini/resolve/main/phi-3.5-mini-int4.task",
                filename = "phi-3.5-mini-int4.task",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = false
            )
        )
    }

    private val modelsDir: File = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    private val scope = downloadScope

    // Download state — uses singleton so downloads survive navigation
    val downloads: StateFlow<Map<String, DownloadState>> = _globalDownloads.asStateFlow()

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
        val currentState = _globalDownloads.value[model.id]
        if (currentState is DownloadState.Downloading) return

        val job = scope.launch {
            val outputFile = File(modelsDir, model.filename)
            val tempFile = File(modelsDir, "${model.filename}.tmp")

            try {
                _globalDownloads.value = _globalDownloads.value + (model.id to DownloadState.Downloading(0f))
                Log.i(TAG, "Starting download: ${model.name} from ${model.downloadUrl}")

                var currentUrl = model.downloadUrl
                var connection: HttpURLConnection
                var responseCode: Int
                var redirectCount = 0

                // Follow redirects manually (instanceFollowRedirects = false to avoid conflict)
                do {
                    ensureActive()
                    val url = URL(currentUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "GooseAndroid/0.1.0")

                    // Support resume if temp file exists
                    if (tempFile.exists() && tempFile.length() > 0) {
                        connection.setRequestProperty("Range", "bytes=${tempFile.length()}-")
                    }

                    connection.connect()
                    responseCode = connection.responseCode

                    if (responseCode in 301..303 || responseCode == 307 || responseCode == 308) {
                        currentUrl = connection.getHeaderField("Location") ?: break
                        connection.disconnect()
                        redirectCount++
                    } else {
                        break
                    }
                } while (redirectCount < 5)

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

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val progress = if (totalBytes > 0) {
                            (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                        } else 0f

                        _globalDownloads.value = _globalDownloads.value + (model.id to DownloadState.Downloading(progress))
                    }
                } finally {
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    connection.disconnect()
                }

                // Verify file size is > 0 and matches expected size if known
                val downloadedSize = tempFile.length()
                if (downloadedSize == 0L) {
                    tempFile.delete()
                    throw Exception("Downloaded file is empty (0 bytes)")
                }
                if (model.sizeBytes > 0 && downloadedSize < model.sizeBytes * 0.9) {
                    // Allow 10% tolerance since catalog sizes are approximate
                    Log.w(TAG, "Downloaded size ($downloadedSize) is significantly smaller than expected (${model.sizeBytes})")
                }

                // Rename temp to final
                tempFile.renameTo(outputFile)

                Log.i(TAG, "Download complete: ${model.name} (${outputFile.length()} bytes)")
                _globalDownloads.value = _globalDownloads.value + (model.id to DownloadState.Complete)

            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled: ${model.name}")
                _globalDownloads.value = _globalDownloads.value - model.id
                // Don't delete temp file — allows resume on retry
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${model.name}", e)
                _globalDownloads.value = _globalDownloads.value + (model.id to DownloadState.Error(e.message ?: "Unknown error"))
                // Don't delete temp file — allows resume on retry
            } finally {
                downloadJobs.remove(model.id)
            }
        }

        downloadJobs[model.id] = job
    }

    /**
     * Cancel an in-progress download.
     */
    fun cancelDownload(model: ModelInfo) {
        val job = downloadJobs.remove(model.id)
        job?.cancel()
        _globalDownloads.value = _globalDownloads.value - model.id
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
        _globalDownloads.value = _globalDownloads.value - modelId
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
