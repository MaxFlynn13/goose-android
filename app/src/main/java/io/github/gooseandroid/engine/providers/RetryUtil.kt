package io.github.gooseandroid.engine.providers

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Retry utility with exponential backoff for API calls.
 * Handles rate limits (429), server errors (500, 502, 503), and timeouts.
 */
object RetryUtil {
    private const val TAG = "RetryUtil"
    private const val MAX_RETRIES = 3
    private const val INITIAL_DELAY_MS = 1000L
    private const val MAX_DELAY_MS = 30000L
    private const val BACKOFF_MULTIPLIER = 2.0

    /**
     * Retryable HTTP status codes.
     */
    fun isRetryable(statusCode: Int): Boolean = statusCode in listOf(
        429,  // Rate limited
        500,  // Internal server error
        502,  // Bad gateway
        503,  // Service unavailable
        504   // Gateway timeout
    )

    /**
     * Execute a block with exponential backoff retry.
     * Only retries on retryable exceptions (network, rate limit, server errors).
     */
    suspend fun <T> withRetry(
        operationName: String = "API call",
        maxRetries: Int = MAX_RETRIES,
        block: suspend (attempt: Int) -> T
    ): T {
        var lastException: Exception? = null
        var delayMs = INITIAL_DELAY_MS

        for (attempt in 0..maxRetries) {
            try {
                return block(attempt)
            } catch (e: RetryableException) {
                lastException = e
                if (attempt < maxRetries) {
                    // Check for Retry-After header hint
                    val retryAfter = e.retryAfterMs
                    val actualDelay = if (retryAfter != null && retryAfter > 0) {
                        retryAfter.coerceAtMost(MAX_DELAY_MS)
                    } else {
                        delayMs.coerceAtMost(MAX_DELAY_MS)
                    }

                    Log.w(TAG, "$operationName failed (attempt ${attempt + 1}/$maxRetries): ${e.message}. Retrying in ${actualDelay}ms")
                    delay(actualDelay)
                    delayMs = (delayMs * BACKOFF_MULTIPLIER).toLong()
                }
            } catch (e: Exception) {
                // Non-retryable exception — fail immediately
                throw e
            }
        }

        throw lastException ?: RuntimeException("$operationName failed after $maxRetries retries")
    }
}

/**
 * Exception that indicates the operation can be retried.
 */
class RetryableException(
    message: String,
    val statusCode: Int = 0,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)
