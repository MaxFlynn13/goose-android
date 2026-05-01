package io.github.gooseandroid

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages local LLM models using Google AI Edge LiteRT.
 *
 * Architecture:
 * - LiteRT handles on-device inference (GPU/NPU acceleration)
 * - Models stored in app-private storage (no SD card permission needed)
 * - Exposes an OpenAI-compatible HTTP API on localhost
 * - Goose connects to it as if it were any other LLM provider
 *
 * Supported hardware acceleration on Snapdragon 888:
 * - Hexagon 780 DSP via QNN delegate (fastest)
 * - Adreno 660 GPU via GPU delegate (good)
 * - CPU with NEON (fallback, still decent)
 *
 * Model format: .tflite or .bin (GGUF converted via ai-edge-torch)
 *
 * Reference: https://github.com/google-ai-edge/gallery
 */
class LocalModelManager(private val context: Context) {

    companion object {
        private const val TAG = "LocalModelManager"
        private const val MODELS_DIR = "models"

        // Pre-configured model catalog
        // These are models known to work well on Snapdragon 888 (12GB RAM)
        val MODEL_CATALOG = listOf(
            ModelInfo(
                id = "gemma-2b-it",
                name = "Gemma 2B Instruct",
                description = "Google's efficient 2B parameter model. Fast on-device inference.",
                sizeBytes = 1_500_000_000L, // ~1.5GB quantized
                downloadUrl = "", // TODO: Add HuggingFace or direct URL
                format = ModelFormat.LITERT,
                minRamMb = 3072,
                recommended = true
            ),
            ModelInfo(
                id = "gemma-7b-it",
                name = "Gemma 7B Instruct",
                description = "Larger Gemma model. Better quality, needs more RAM.",
                sizeBytes = 4_500_000_000L, // ~4.5GB quantized
                downloadUrl = "",
                format = ModelFormat.LITERT,
                minRamMb = 6144,
                recommended = true
            ),
            ModelInfo(
                id = "phi-3-mini",
                name = "Phi-3 Mini (3.8B)",
                description = "Microsoft's compact model. Great reasoning for its size.",
                sizeBytes = 2_300_000_000L,
                downloadUrl = "",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = false
            ),
            ModelInfo(
                id = "llama-3.2-1b",
                name = "Llama 3.2 1B",
                description = "Meta's smallest Llama. Ultra-fast, good for simple tasks.",
                sizeBytes = 700_000_000L,
                downloadUrl = "",
                format = ModelFormat.LITERT,
                minRamMb = 2048,
                recommended = true
            ),
            ModelInfo(
                id = "llama-3.2-3b",
                name = "Llama 3.2 3B",
                description = "Meta's 3B Llama. Good balance of speed and quality.",
                sizeBytes = 2_000_000_000L,
                downloadUrl = "",
                format = ModelFormat.LITERT,
                minRamMb = 4096,
                recommended = true
            )
        )
    }

    private val modelsDir: File = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    /**
     * List all downloaded models.
     */
    fun getDownloadedModels(): List<ModelInfo> {
        return MODEL_CATALOG.filter { model ->
            File(modelsDir, "${model.id}.bin").exists() ||
            File(modelsDir, "${model.id}.tflite").exists()
        }
    }

    /**
     * List all available models (downloaded + not yet downloaded).
     */
    fun getAvailableModels(): List<ModelStatus> {
        return MODEL_CATALOG.map { model ->
            val file = getModelFile(model)
            ModelStatus(
                model = model,
                downloaded = file?.exists() == true,
                fileSizeBytes = file?.length() ?: 0
            )
        }
    }

    /**
     * Get the file path for a model.
     */
    fun getModelFile(model: ModelInfo): File? {
        val tfliteFile = File(modelsDir, "${model.id}.tflite")
        if (tfliteFile.exists()) return tfliteFile

        val binFile = File(modelsDir, "${model.id}.bin")
        if (binFile.exists()) return binFile

        return tfliteFile // default path for download
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
     * Delete a downloaded model.
     */
    fun deleteModel(modelId: String): Boolean {
        val deleted = listOf("tflite", "bin").any { ext ->
            File(modelsDir, "$modelId.$ext").let { file ->
                if (file.exists()) file.delete() else false
            }
        }
        if (deleted) {
            Log.i(TAG, "Deleted model: $modelId")
        }
        return deleted
    }

    /**
     * Check if device meets minimum requirements for a model.
     */
    fun canRunModel(model: ModelInfo): ModelCompatibility {
        val runtime = Runtime.getRuntime()
        val availableRamMb = runtime.maxMemory() / (1024 * 1024)
        val hasEnoughRam = availableRamMb >= model.minRamMb
        val hasEnoughStorage = getAvailableStorageMb() > (model.sizeBytes / (1024 * 1024))

        return ModelCompatibility(
            canRun = hasEnoughRam && hasEnoughStorage,
            hasEnoughRam = hasEnoughRam,
            hasEnoughStorage = hasEnoughStorage,
            availableRamMb = availableRamMb,
            requiredRamMb = model.minRamMb.toLong()
        )
    }
}

/**
 * Model metadata.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val format: ModelFormat,
    val minRamMb: Int,
    val recommended: Boolean
)

enum class ModelFormat {
    LITERT,     // Google AI Edge LiteRT (.tflite)
    GGUF        // llama.cpp format (fallback)
}

data class ModelStatus(
    val model: ModelInfo,
    val downloaded: Boolean,
    val fileSizeBytes: Long
)

data class ModelCompatibility(
    val canRun: Boolean,
    val hasEnoughRam: Boolean,
    val hasEnoughStorage: Boolean,
    val availableRamMb: Long,
    val requiredRamMb: Long
)
