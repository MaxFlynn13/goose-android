package io.github.gooseandroid

/**
 * Simple singleton to share state between GooseService and the UI layer.
 */
object GoosePortHolder {
    /** Port the goose server (or LiteRT fallback) is listening on. */
    @Volatile
    var port: Int = 3284

    /** True when running without the goose binary (LiteRT-only mode). */
    @Volatile
    var localOnlyMode: Boolean = false
}
