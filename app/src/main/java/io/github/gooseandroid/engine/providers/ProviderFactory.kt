package io.github.gooseandroid.engine.providers

/**
 * Factory for creating LLM provider instances.
 * Maps provider IDs to their concrete implementations.
 */
object ProviderFactory {

    /**
     * Create an LLM provider by ID.
     *
     * @param providerId One of: "anthropic", "openai", "google"
     * @param apiKey The API key for authentication
     * @param modelId The model identifier (e.g., "claude-sonnet-4-20250514", "gpt-4o", "gemini-2.0-flash")
     * @param baseUrl Optional base URL override (only used by OpenAI-compatible providers)
     * @return An LlmProvider instance ready to use
     * @throws IllegalArgumentException if the provider ID is unknown
     */
    fun create(
        providerId: String,
        apiKey: String,
        modelId: String,
        baseUrl: String? = null
    ): LlmProvider = when (providerId) {
        "anthropic" -> AnthropicProvider(apiKey, modelId)
        "openai" -> {
            if (baseUrl != null) {
                OpenAIProvider(apiKey, modelId, baseUrl)
            } else {
                OpenAIProvider(apiKey, modelId)
            }
        }
        "google" -> GoogleProvider(apiKey, modelId)
        else -> throw IllegalArgumentException("Unknown provider: $providerId. Supported: anthropic, openai, google")
    }

    /**
     * List all supported provider IDs.
     */
    fun supportedProviders(): List<String> = listOf("anthropic", "openai", "google")
}
