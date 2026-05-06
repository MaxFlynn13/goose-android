package io.github.gooseandroid.engine.providers

import android.content.Context

/**
 * Factory for creating LLM provider instances.
 * Maps provider IDs to their concrete implementations.
 */
object ProviderFactory {

    /**
     * Create an LLM provider by ID.
     *
     * @param providerId One of: "anthropic", "openai", "google", "mistral", "openrouter", "ollama", "local"
     * @param apiKey The API key for authentication (for "local" provider, this is the model file path)
     * @param modelId The model identifier (e.g., "claude-sonnet-4-20250514", "gpt-4o", "gemini-2.0-flash")
     * @param baseUrl Optional base URL override (only used by OpenAI-compatible providers)
     * @param context Optional Android context (required for "local" provider)
     * @return An LlmProvider instance ready to use, or null if creation fails
     */
    fun create(
        providerId: String,
        apiKey: String,
        modelId: String,
        baseUrl: String? = null,
        context: Context? = null
    ): LlmProvider? = when (providerId) {
        "anthropic" -> AnthropicProvider(apiKey, modelId)
        "openai", "openrouter" -> {
            val url = when {
                baseUrl != null -> baseUrl
                providerId == "openrouter" -> "https://openrouter.ai/api/v1"
                else -> null
            }
            if (url != null) OpenAIProvider(apiKey, modelId, url) else OpenAIProvider(apiKey, modelId)
        }
        "google" -> GoogleProvider(apiKey, modelId)
        "mistral" -> OpenAIProvider(apiKey, modelId, "https://api.mistral.ai/v1")
        "ollama" -> {
            val url = baseUrl ?: "http://localhost:11434/v1"
            OpenAIProvider("ollama", modelId, url)
        }
        "databricks" -> {
            // For Databricks, baseUrl is the workspace URL, apiKey is the token
            val workspaceUrl = baseUrl ?: ""
            if (workspaceUrl.isNotBlank()) {
                DatabricksProvider(workspaceUrl, apiKey, modelId)
            } else null
        }
        "local" -> {
            // For local models, apiKey carries the model file path
            if (context != null && apiKey.isNotBlank()) {
                LocalModelProvider(context, modelId, apiKey)
            } else null
        }
        else -> null  // Unknown provider — return null, don't crash
    }

    /**
     * Backwards-compatible overload without context parameter.
     * Cannot create "local" providers — use the full signature for that.
     */
    fun create(
        providerId: String,
        apiKey: String,
        modelId: String,
        baseUrl: String? = null
    ): LlmProvider? = create(providerId, apiKey, modelId, baseUrl, context = null)

    /**
     * List all supported provider IDs.
     */
    fun supportedProviders(): List<String> = listOf(
        "anthropic", "openai", "google", "mistral", "openrouter", "ollama", "databricks", "local"
    )
}
