package io.github.gooseandroid.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the Goose engine lifecycle.
 *
 * This is the single entry point for all AI engine operations.
 * Uses the Kotlin native engine exclusively — no Rust binary dependency.
 * This ensures the app works on every Android device regardless of
 * binary execution restrictions.
 */
class GooseEngineManager(private val context: Context) {

    companion object {
        private const val TAG = "GooseEngineManager"
    }

    private var engine: KotlinNativeEngine? = null

    /** Access to permission manager for UI dialog */
    val permissionManager: PermissionManager?
        get() = engine?.permissionManager

    private val _engineInfo = MutableStateFlow("Initializing...")
    val engineInfo: StateFlow<String> = _engineInfo.asStateFlow()

    private val initComplete = CompletableDeferred<Unit>()

    /**
     * Initialize the engine. Call once from ChatViewModel.init.
     * Always succeeds — the Kotlin engine has no external dependencies.
     */
    suspend fun initialize() {
        Log.i(TAG, "Initializing Kotlin native engine...")
        _engineInfo.value = "Starting engine..."

        try {
            val kotlinEngine = KotlinNativeEngine(context)
            val ready = kotlinEngine.initialize()

            if (ready) {
                engine = kotlinEngine
                _engineInfo.value = "Goose ready"
                Log.i(TAG, "Kotlin native engine ready")
            } else {
                engine = kotlinEngine // Still usable — will show config message
                _engineInfo.value = "Configure API key in Settings"
                Log.w(TAG, "Engine initialized but no provider configured")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine initialization failed", e)
            _engineInfo.value = "Engine error: ${e.message}"
        }

        initComplete.complete(Unit)
    }

    /**
     * Get the engine. Suspends until initialization is complete (max 30s).
     * Returns null only if initialization catastrophically failed.
     */
    suspend fun getEngine(): GooseEngine? {
        // Fast path: already initialized
        engine?.let { return it }

        // Wait for initialization
        withTimeoutOrNull(30_000L) { initComplete.await() }

        return engine
    }

    /**
     * Shut down the engine and release resources.
     */
    suspend fun shutdown() {
        engine?.shutdown()
        engine = null
        _engineInfo.value = "Stopped"
    }
}
