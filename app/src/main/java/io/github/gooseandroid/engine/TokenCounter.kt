package io.github.gooseandroid.engine

import android.util.Log
import org.json.JSONObject

/**
 * Estimates token counts and manages context window limits.
 *
 * Uses a character-based approximation (1 token ≈ 4 characters for English text).
 * This is intentionally conservative — better to truncate slightly early than
 * hit a context limit error mid-generation.
 *
 * Context window sizes by model:
 * - Claude Sonnet/Opus 4: 200K tokens
 * - Claude 3.5 Haiku: 200K tokens
 * - GPT-4o: 128K tokens
 * - GPT-4o-mini: 128K tokens
 * - o3/o4-mini: 200K tokens
 * - Gemini 2.5 Flash/Pro: 1M tokens
 * - Mistral Large: 128K tokens
 * - Local models: 4K-8K tokens typically
 *
 * Strategy:
 * 1. Before each LLM call, estimate total tokens in conversation
 * 2. If > 80% of context window, auto-compact oldest messages
 * 3. If still > 90% after compaction, hard-truncate oldest messages
 * 4. Always preserve: system prompt + last 4 messages (user/assistant pairs)
 */
class TokenCounter {

    companion object {
        private const val TAG = "TokenCounter"
        private const val CHARS_PER_TOKEN = 4  // Conservative estimate
        private const val COMPACT_THRESHOLD = 0.80  // Compact at 80% usage
        private const val TRUNCATE_THRESHOLD = 0.90  // Hard truncate at 90%
        private const val MIN_PRESERVED_MESSAGES = 4  // Always keep last N messages
        
        // Context window sizes (in tokens)
        private val CONTEXT_WINDOWS = mapOf(
            // Anthropic
            "claude-sonnet-4-20250514" to 200_000,
            "claude-opus-4-20250514" to 200_000,
            "claude-3-5-haiku-20241022" to 200_000,
            // OpenAI
            "gpt-4o" to 128_000,
            "gpt-4o-mini" to 128_000,
            "o3-mini" to 200_000,
            "o4-mini" to 200_000,
            // Google
            "gemini-2.5-flash" to 1_000_000,
            "gemini-2.5-pro" to 1_000_000,
            "gemini-2.0-flash" to 1_000_000,
            // Mistral
            "mistral-large-latest" to 128_000,
            "mistral-small-latest" to 32_000,
            // Local models (conservative)
            "local" to 4_096
        )
        
        private const val DEFAULT_CONTEXT_WINDOW = 128_000
    }

    /**
     * Estimate token count for a string.
     */
    fun estimateTokens(text: String): Int {
        return (text.length / CHARS_PER_TOKEN) + 1
    }

    /**
     * Estimate total tokens for a conversation.
     */
    fun estimateConversationTokens(messages: List<ConversationMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += estimateTokens(msg.content)
            // Tool calls add overhead
            msg.toolCalls?.forEach { tc ->
                total += estimateTokens(tc.name) + estimateTokens(tc.input.toString())
            }
            // Tool results
            if (msg.toolCallId != null) {
                total += 10  // Overhead for tool_call_id formatting
            }
        }
        // Add per-message overhead (role tokens, formatting)
        total += messages.size * 4
        return total
    }

    /**
     * Get the context window size for a model.
     */
    fun getContextWindow(modelId: String): Int {
        return CONTEXT_WINDOWS[modelId] ?: DEFAULT_CONTEXT_WINDOW
    }

    /**
     * Check if conversation needs truncation.
     * Returns the action needed.
     */
    fun checkContextUsage(messages: List<ConversationMessage>, modelId: String): ContextAction {
        val totalTokens = estimateConversationTokens(messages)
        val contextWindow = getContextWindow(modelId)
        val usage = totalTokens.toDouble() / contextWindow

        Log.d(TAG, "Context usage: $totalTokens / $contextWindow tokens (${(usage * 100).toInt()}%)")

        return when {
            usage >= TRUNCATE_THRESHOLD -> ContextAction.HARD_TRUNCATE
            usage >= COMPACT_THRESHOLD -> ContextAction.COMPACT
            else -> ContextAction.OK
        }
    }

    /**
     * Hard-truncate messages to fit within context window.
     * Preserves: system prompt (first message) + last N messages.
     * Removes oldest messages from the middle.
     */
    fun truncateMessages(
        messages: List<ConversationMessage>,
        modelId: String
    ): List<ConversationMessage> {
        val contextWindow = getContextWindow(modelId)
        val targetTokens = (contextWindow * 0.75).toInt()  // Leave 25% headroom for response

        if (messages.size <= MIN_PRESERVED_MESSAGES + 1) return messages

        val result = mutableListOf<ConversationMessage>()

        // Always keep system prompt
        val systemMessages = messages.filter { it.role == "system" }
        result.addAll(systemMessages)

        // Always keep last N messages
        val nonSystem = messages.filter { it.role != "system" }
        val preserved = nonSystem.takeLast(MIN_PRESERVED_MESSAGES)

        // Add a truncation notice
        result.add(ConversationMessage(
            role = "system",
            content = "[Earlier conversation was truncated to fit context window. " +
                "${nonSystem.size - preserved.size} messages removed.]"
        ))

        result.addAll(preserved)

        val finalTokens = estimateConversationTokens(result)
        Log.i(TAG, "Truncated: ${messages.size} → ${result.size} messages, " +
            "$finalTokens tokens (target: $targetTokens)")

        return result
    }

    enum class ContextAction {
        OK,             // Under threshold, no action needed
        COMPACT,        // Should compact (summarize old messages)
        HARD_TRUNCATE   // Must truncate immediately
    }
}
