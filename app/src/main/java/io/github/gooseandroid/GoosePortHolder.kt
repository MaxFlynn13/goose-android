package io.github.gooseandroid

import java.util.concurrent.atomic.AtomicReference

data class PortState(val port: Int = 0, val localOnlyMode: Boolean = false)

/**
 * Simple singleton to share state between GooseService and the UI layer.
 */
object GoosePortHolder {
    private val state = AtomicReference(PortState())

    /** Port the goose server (or LiteRT fallback) is listening on. */
    var port: Int
        get() = state.get().port
        set(value) { state.updateAndGet { it.copy(port = value) } }

    /** True when running without the goose binary (LiteRT-only mode). */
    var localOnlyMode: Boolean
        get() = state.get().localOnlyMode
        set(value) { state.updateAndGet { it.copy(localOnlyMode = value) } }
}
