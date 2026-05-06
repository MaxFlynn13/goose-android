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
        // Kaggle base URL (used by Google AI Edge Gallery — no auth required)

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
         * These models are sourced from the Google AI Edge Gallery and
         * litert-community repositories on HuggingFace. They use .task
         * format which MediaPipe's LlmInference API loads natively with
         * GPU acceleration (OpenCL/Vulkan on Adreno/Mali GPUs).
         *
         * Format: .task (MediaPipe Task bundle) or .bin (raw weights)
         * Quantization: int4 (4-bit) for optimal mobile performance
         */
        val MODEL_CATALOG = listOf(
            // === Gemma 4 (from Google AI Edge Gallery — litert-community, ungated) ===
            ModelInfo(
                id = "gemma4-2b-it",
                name = "Gemma 4 2B",
                description = "Google's newest small model. Fast reasoning and instruction following.",
                sizeBytes = 2_588_147_712L,
                downloadUrl = "$HF_BASE/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
                filename = "gemma-4-E2B-it.litertlm",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = true
            ),
            ModelInfo(
                id = "gemma4-4b-it",
                name = "Gemma 4 4B",
                description = "Google's newest 4B model. Near-cloud quality on-device.",
                sizeBytes = 3_659_530_240L,
                downloadUrl = "$HF_BASE/litert-community/gemma-4-E4B-it-litert-lm/resolve/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true",
                filename = "gemma-4-E4B-it.litertlm",
                format = ModelFormat.LITERT,
                minRamMb = 8192,
                recommended = true
            ),
            // === Gemma 3 (stable, litert-community) ===
            ModelInfo(
                id = "gemma3-1b-it",
                name = "Gemma 3 1B",
                description = "Ultra-fast, great for simple tasks and quick responses.",
                sizeBytes = 1_073_741_824L,
                downloadUrl = "$HF_BASE/litert-community/Gemma3-1B-IT/resolve/42d538a932e8d5b12e6b3b455f5572560bd60b2c/gemma3-1b-it-int4.litertlm?download=true",
                filename = "gemma3-1b-it-int4.litertlm",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),
            // === Qwen 2.5 (Alibaba, ungated) ===
            ModelInfo(
                id = "qwen2.5-1.5b",
                name = "Qwen 2.5 1.5B",
                description = "Strong multilingual and coding capabilities.",
                sizeBytes = 1_800_000_000L,
                downloadUrl = "$HF_BASE/litert-community/Qwen2.5-1.5B-Instruct/resolve/19edb84c69a0212f29a6ef17ba0d6f278b6a1614/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
                filename = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),
            // === DeepSeek R1 (reasoning model, ungated) ===
            ModelInfo(
                id = "deepseek-r1-1.5b",
                name = "DeepSeek R1 1.5B",
                description = "Chain-of-thought reasoning model distilled to 1.5B parameters.",
                sizeBytes = 1_800_000_000L,
                downloadUrl = "$HF_BASE/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/e34bb88632342d1f9640bad579a45134eb1cf988/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
                filename = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = false
            ),
            // === Gemma 3n (newest nano, multimodal) ===
            ModelInfo(
                id = "gemma3n-2b-it",
                name = "Gemma 3n 2B",
                description = "Google's newest nano model. Vision + audio + text.",
                sizeBytes = 3_655_827_456L,
                downloadUrl = "$HF_BASE/google/gemma-3n-E2B-it-litert-lm/resolve/ba9ca88da013b537b6ed38108be609b8db1c3a16/gemma-3n-E2B-it-int4.litertlm?download=true",
                filename = "gemma-3n-E2B-it-int4.litertlm",
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

                    // Add HuggingFace auth token for gated models
                    val hfToken = try {
                        kotlinx.coroutines.runBlocking {
                            io.github.gooseandroid.data.SettingsStore(context)
                                .getString(io.github.gooseandroid.data.SettingsKeys.HUGGINGFACE_TOKEN, "")
                                .first()
                        }
                    } catch (_: Exception) { "" }
                    if (hfToken.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $hfToken")
                    }

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
    LITERT      // Google AI Edge LiteRT (.task/.bin file for MediaPipe)
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
