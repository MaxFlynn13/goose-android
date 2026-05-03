package io.github.gooseandroid.engine.mcp

/**
 * Transport layer abstraction for MCP (Model Context Protocol) communication.
 *
 * Implementations handle the mechanics of sending/receiving newline-delimited
 * JSON-RPC 2.0 messages over a specific transport (stdio, HTTP+SSE, etc.).
 */
interface McpTransport {

    /** Start the transport (spawn process, open connection, etc.). */
    suspend fun start(): Result<Unit>

    /** Send a JSON-RPC message string to the server. */
    suspend fun send(message: String)

    /** Block until the next JSON-RPC message is received from the server. */
    suspend fun receive(): String

    /** Shut down the transport and release all resources. */
    suspend fun close()

    /** Whether the transport is currently connected and usable. */
    val isConnected: Boolean
}
