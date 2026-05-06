package io.github.gooseandroid.engine

/**
 * Tracks what the agent has done during a session.
 * This context is injected into the system prompt so the LLM knows
 * what files it has read, what commands it has run, and what changes it made.
 *
 * This prevents the LLM from re-reading files it already has in context,
 * and helps it understand the current state of the workspace.
 */
class ContextTracker {
    
    private val _filesRead = mutableSetOf<String>()
    private val _filesModified = mutableSetOf<String>()
    private val _commandsRun = mutableListOf<CommandRecord>()
    private val _workingDirectory = StringBuilder()
    
    data class CommandRecord(
        val command: String,
        val exitCode: Int,
        val outputPreview: String, // First 200 chars of output
        val timestamp: Long = System.currentTimeMillis()
    )
    
    val filesRead: Set<String> get() = _filesRead
    val filesModified: Set<String> get() = _filesModified
    val commandsRun: List<CommandRecord> get() = _commandsRun
    
    fun recordFileRead(path: String) { _filesRead.add(path) }
    fun recordFileModified(path: String) { _filesModified.add(path) }
    fun recordCommand(command: String, exitCode: Int, output: String) {
        _commandsRun.add(CommandRecord(command, exitCode, output.take(200)))
        // Keep only last 50 commands to prevent unbounded growth
        if (_commandsRun.size > 50) _commandsRun.removeAt(0)
    }
    
    fun setWorkingDirectory(dir: String) { 
        _workingDirectory.clear()
        _workingDirectory.append(dir)
    }
    
    fun clear() {
        _filesRead.clear()
        _filesModified.clear()
        _commandsRun.clear()
    }
    
    /**
     * Generate a context summary to inject into the system prompt.
     * Only includes non-empty sections to avoid wasting tokens.
     */
    fun getContextSummary(): String {
        val parts = mutableListOf<String>()
        
        if (_workingDirectory.isNotBlank()) {
            parts.add("Working directory: $_workingDirectory")
        }
        
        if (_filesRead.isNotEmpty()) {
            parts.add("Files you've read this session: ${_filesRead.joinToString(", ")}")
        }
        
        if (_filesModified.isNotEmpty()) {
            parts.add("Files you've modified this session: ${_filesModified.joinToString(", ")}")
        }
        
        if (_commandsRun.isNotEmpty()) {
            val recent = _commandsRun.takeLast(10)
            val cmdSummary = recent.joinToString("\n") { record ->
                val status = if (record.exitCode == 0) "OK" else "FAILED(${record.exitCode})"
                "  $ ${record.command.take(80)} → $status"
            }
            parts.add("Recent commands:\n$cmdSummary")
        }
        
        return if (parts.isEmpty()) "" else parts.joinToString("\n\n")
    }
}
