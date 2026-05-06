package io.github.gooseandroid.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages engine lifecycle and automatic failover.
 *
 * Startup sequence:
 * 1. Try RustBinaryEngine (goose serve via ACP WebSocket)
 * 2. If Rust fails → KotlinNativeEngine activates automatically
 * 3. User never sees the failover — both engines produce identical AgentEvents
 */
class GooseEngineManager(private val context: Context) {

    companion object {
        private const val TAG = "EngineManager"
    }

    private var rustEngine: GooseEngine? = null
    private var kotlinEngine: GooseEngine? = null

    private val _activeEngine = MutableStateFlow<GooseEngine?>(null)
    val activeEngine: StateFlow<GooseEngine?> = _activeEngine.asStateFlow()

    private val _engineInfo = MutableStateFlow("Initializing...")
    val engineInfo: StateFlow<String> = _engineInfo.asStateFlow()

    /** Signals when initialization is complete. getEngine() waits on this. */
    private val initComplete = CompletableDeferred<Unit>()

    /**
     * Initialize engines with automatic failover.
     * Always results in a working engine (Kotlin native is the guaranteed fallback).
     */
    suspend fun initialize() {
        Log.i(TAG, "Initializing engine manager...")
        _engineInfo.value = "Starting Goose engine..."

        // Phase 1: Try the Rust binary engine (strict 5s timeout)
        // If the binary can't execute on this device, fail fast and move on.
        try {
            Log.i(TAG, "Attempting Rust binary engine (5s timeout)...")
            _engineInfo.value = "Trying Goose binary..."

            val rustResult = withTimeoutOrNull(5_000L) {
                val rust = RustBinaryEngine(context)
                rustEngine = rust
                val ready = rust.initialize()
                if (ready) rust else null
            }

            if (rustResult != null) {
                _activeEngine.value = rustResult
                _engineInfo.value = "Goose (native binary)"
                Log.i(TAG, "Rust binary engine connected successfully")
                initComplete.complete(Unit)
                return
            } else {
                Log.w(TAG, "Rust binary engine failed or timed out")
                rustEngine?.shutdown()
                rustEngine = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rust binary engine threw exception: ${e.message}")
            rustEngine = null
        }

        // Phase 2: Fall back to Kotlin native engine (always works)
        Log.i(TAG, "Falling back to Kotlin native engine")
        _engineInfo.value = "Starting native engine..."

        try {
            val kotlin = KotlinNativeEngine(context)
            kotlinEngine = kotlin

            val kotlinReady = kotlin.initialize()
            if (kotlinReady) {
                _activeEngine.value = kotlin
                _engineInfo.value = "Goose (native Kotlin)"
                Log.i(TAG, "Kotlin native engine ready")
                initComplete.complete(Unit)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kotlin native engine failed: ${e.message}", e)
        }

        // This should never happen — Kotlin engine has no external dependencies
        _engineInfo.value = "Engine initialization failed"
        Log.e(TAG, "FATAL: No engine could be initialized")
        initComplete.complete(Unit)
    }

    /**
     * Get the current active engine.
     * Suspends until initialization is complete (max 30 seconds).
     * Returns null if no engine could be initialized.
     */
    suspend fun getEngine(): GooseEngine? {
        // If already initialized, return immediately
        _activeEngine.value?.let { return it }

        // Wait for initialization to complete (with timeout)
        withTimeoutOrNull(30_000L) { initComplete.await() }

        return _activeEngine.value
    }

    /**
     * Check if the active engine is the Rust binary (full Goose) or Kotlin (native).
     */
    fun isRustEngine(): Boolean {
        return _activeEngine.value is RustBinaryEngine
    }

    /**
     * Force switch to Kotlin engine (e.g., if Rust engine starts misbehaving mid-session).
     */
    suspend fun forceKotlinEngine() {
        Log.i(TAG, "Force-switching to Kotlin native engine")
        rustEngine?.shutdown()

        val kotlin = kotlinEngine ?: KotlinNativeEngine(context).also { kotlinEngine = it }
        if (kotlin.status.value != EngineStatus.CONNECTED) {
            kotlin.initialize()
        }
        _activeEngine.value = kotlin
        _engineInfo.value = "Goose (native Kotlin)"
    }

    /**
     * Shutdown all engines.
     */
    suspend fun shutdown() {
        rustEngine?.shutdown()
        kotlinEngine?.shutdown()
        _activeEngine.value = null
    }
}
