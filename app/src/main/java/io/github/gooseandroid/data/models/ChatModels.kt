package io.github.gooseandroid.data.models

import kotlinx.serialization.Serializable

/**
 * Core data models shared across the entire application.
 * Located in data.models to avoid circular dependencies.
 *
 * Imported by: ui.chat, network, data, acp
 */

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val thinking: String = ""
)

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

data class ToolCall(
    val id: String = "",
    val name: String,
    val status: ToolCallStatus,
    val input: String = "",
    val output: String = ""
)

enum class ToolCallStatus { RUNNING, COMPLETE, ERROR }

@Serializable
data class SessionInfo(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastMessage: String = "",
    val providerId: String = "",
    val modelId: String = ""
)

data class AttachmentInfo(
    val name: String,
    val mimeType: String,
    val content: String,
    val isImage: Boolean = false
)

/**
 * Provider and model catalog.
 */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val apiKeySettingsKey: String,
    val models: List<ModelOption>,
    val requiresApiKey: Boolean = true,
    val baseUrlSettingsKey: String? = null,
    val defaultBaseUrl: String = "",
    val requiresModelName: Boolean = false
) {
    /** Whether this provider needs a base URL configured. */
    val requiresBaseUrl: Boolean get() = baseUrlSettingsKey != null
}

data class ModelOption(
    val id: String,
    val displayName: String
)

val PROVIDER_CATALOG = listOf(
    ProviderInfo(
        id = "anthropic",
        displayName = "Anthropic",
        description = "Claude models",
        apiKeySettingsKey = "anthropic_api_key",
        models = listOf(
            ModelOption("claude-sonnet-4-20250514", "Claude Sonnet 4"),
            ModelOption("claude-opus-4-20250514", "Claude Opus 4"),
            ModelOption("claude-3-5-haiku-20241022", "Claude 3.5 Haiku")
        )
    ),
    ProviderInfo(
        id = "openai",
        displayName = "OpenAI",
        description = "GPT and o-series models",
        apiKeySettingsKey = "openai_api_key",
        models = listOf(
            ModelOption("gpt-4o", "GPT-4o"),
            ModelOption("gpt-4o-mini", "GPT-4o Mini"),
            ModelOption("o3-mini", "o3 Mini"),
            ModelOption("o4-mini", "o4 Mini")
        )
    ),
    ProviderInfo(
        id = "google",
        displayName = "Google Gemini",
        description = "Gemini models",
        apiKeySettingsKey = "google_api_key",
        models = listOf(
            ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash"),
            ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro"),
            ModelOption("gemini-2.0-flash", "Gemini 2.0 Flash")
        )
    ),
    ProviderInfo(
        id = "mistral",
        displayName = "Mistral AI",
        description = "Frontier models from Mistral AI",
        apiKeySettingsKey = "mistral_api_key",
        models = listOf(
            ModelOption("mistral-large-latest", "Mistral Large"),
            ModelOption("mistral-small-latest", "Mistral Small")
        )
    ),
    ProviderInfo(
        id = "openrouter",
        displayName = "OpenRouter",
        description = "Access multiple providers through one API",
        apiKeySettingsKey = "openrouter_api_key",
        models = listOf(
            ModelOption("anthropic/claude-sonnet-4-20250514", "Claude Sonnet 4"),
            ModelOption("openai/gpt-4o", "GPT-4o"),
            ModelOption("google/gemini-2.0-flash-001", "Gemini 2.0 Flash"),
            ModelOption("mistralai/mistral-large-latest", "Mistral Large")
        )
    ),
    ProviderInfo(
        id = "ollama",
        displayName = "Ollama",
        description = "Local models via Ollama",
        apiKeySettingsKey = "",
        requiresApiKey = false,
        baseUrlSettingsKey = "ollama_base_url",
        defaultBaseUrl = "http://localhost:11434",
        models = listOf(
            ModelOption("llama3.2", "Llama 3.2"),
            ModelOption("qwen2.5-coder", "Qwen 2.5 Coder"),
            ModelOption("gemma3", "Gemma 3")
        )
    ),
    ProviderInfo(
        id = "custom",
        displayName = "Custom Provider",
        description = "Any OpenAI-compatible API endpoint",
        apiKeySettingsKey = "custom_provider_key",
        baseUrlSettingsKey = "custom_provider_url",
        requiresModelName = true,
        models = listOf(
            ModelOption("custom", "Custom Model")
        )
    )
)

fun getProviderById(id: String): ProviderInfo? = PROVIDER_CATALOG.find { it.id == id }
