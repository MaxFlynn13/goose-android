package io.github.gooseandroid

/**
 * Simple singleton to share the goose server port between
 * GooseService (which discovers it) and GooseBridge (which exposes it to JS).
 */
object GoosePortHolder {
    @Volatile
    var port: Int = 3284
}
