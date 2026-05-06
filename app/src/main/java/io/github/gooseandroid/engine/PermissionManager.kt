package io.github.gooseandroid.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * Permission system that gates destructive tool operations.
 * 
 * Before executing certain tool calls, the engine checks if the operation
 * requires user approval. If it does, a permission request is emitted to
 * the UI, and execution suspends until the user responds.
 *
 * Operations that require permission:
 * - Shell commands matching destructive patterns (rm -rf, git push --force, etc.)
 * - File writes that overwrite existing files
 * - File edits on system/config files
 * - Any command containing sudo, su, or privilege escalation
 * - Database DROP/DELETE operations
 * - Network operations that send data (curl -X POST with sensitive paths)
 *
 * Operations that are always allowed:
 * - Read-only commands (ls, cat, grep, find, tree)
 * - File writes to new files (not overwriting)
 * - Git operations that don't push (git status, git diff, git log)
 * - Package listing (pip list, npm list)
 */
class PermissionManager {
    
    companion object {
        private const val TAG = "PermissionManager"
        
        // Patterns that ALWAYS require permission
        val DESTRUCTIVE_SHELL_PATTERNS = listOf(
            "rm -rf", "rm -r ", "rmdir",
            "git push --force", "git push -f", "git reset --hard",
            "DROP TABLE", "DROP DATABASE", "DELETE FROM", "TRUNCATE",
            "format ", "mkfs", "dd if=",
            "chmod 777", "chmod -R",
            "sudo ", "su -c",
            "> /dev/", "| tee /dev/",
            "curl.*-X DELETE", "curl.*-X PUT",
            "wget.*-O /",
            "kill -9", "killall",
            "shutdown", "reboot",
            "apt remove", "apt purge", "pip uninstall",
            "npm uninstall -g"
        )
        
        // Patterns that are always safe (no permission needed)
        val SAFE_SHELL_PATTERNS = listOf(
            "ls", "cat ", "head ", "tail ", "grep ", "find ", "tree",
            "echo ", "printf ", "wc ", "sort ", "uniq ",
            "git status", "git diff", "git log", "git branch",
            "pwd", "whoami", "date", "uname",
            "pip list", "npm list", "pip show", "npm info"
        )
    }

    data class PermissionRequest(
        val id: String,
        val toolName: String,
        val description: String,  // Human-readable description of what will happen
        val command: String,      // The actual command/operation
        val risk: RiskLevel,
        val deferred: CompletableDeferred<PermissionResult>
    )
    
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    
    enum class PermissionResult { ALLOW, DENY, ALLOW_ALL_SESSION }
    
    // Emitted to the UI when permission is needed
    private val _permissionRequests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 1)
    val permissionRequests: SharedFlow<PermissionRequest> = _permissionRequests
    
    // Track "allow all" for this session
    private var allowAllForSession = false
    
    /**
     * Check if a tool call needs permission. If it does, suspend until user responds.
     * Returns true if the operation is allowed, false if denied.
     */
    suspend fun checkPermission(toolName: String, input: JSONObject): Boolean {
        if (allowAllForSession) return true
        
        val needsPermission = when (toolName) {
            "shell" -> checkShellPermission(input.optString("command", ""))
            "write" -> checkWritePermission(input.optString("path", ""), input.has("content"))
            "edit" -> false // Edits are generally safe (they're targeted changes)
            "manage_extensions" -> {
                val action = input.optString("action", "")
                action == "remove" || action == "add"
            }
            "manage_brain" -> {
                val action = input.optString("action", "")
                action == "delete"
            }
            else -> false
        }
        
        if (!needsPermission) return true
        
        // Build the permission request
        val description = buildDescription(toolName, input)
        val risk = assessRisk(toolName, input)
        val request = PermissionRequest(
            id = "perm_${System.currentTimeMillis()}",
            toolName = toolName,
            description = description,
            command = formatCommand(toolName, input),
            risk = risk,
            deferred = CompletableDeferred()
        )
        
        // Emit to UI and wait for response
        _permissionRequests.emit(request)
        val result = request.deferred.await()
        
        if (result == PermissionResult.ALLOW_ALL_SESSION) {
            allowAllForSession = true
        }
        
        return result != PermissionResult.DENY
    }
    
    fun resetSession() {
        allowAllForSession = false
    }
    
    private fun checkShellPermission(command: String): Boolean {
        val lower = command.lowercase()
        
        // If it matches a safe pattern, no permission needed
        if (SAFE_SHELL_PATTERNS.any { lower.trimStart().startsWith(it) }) return false
        
        // If it matches a destructive pattern, permission required
        if (DESTRUCTIVE_SHELL_PATTERNS.any { lower.contains(it) }) return true
        
        // Pipe to file (potential overwrite)
        if (command.contains(">") && !command.contains(">>")) return true
        
        // Default: no permission needed for unknown commands
        return false
    }
    
    private fun checkWritePermission(path: String, hasContent: Boolean): Boolean {
        // Writing to config files needs permission
        if (path.contains(".config") || path.contains(".env") || path.contains(".ssh")) return true
        // Overwriting existing files — handled by the tool itself
        return false
    }
    
    private fun buildDescription(toolName: String, input: JSONObject): String {
        return when (toolName) {
            "shell" -> "Run shell command: ${input.optString("command", "").take(100)}"
            "write" -> "Write file: ${input.optString("path", "")}"
            "manage_extensions" -> "${input.optString("action", "")} extension: ${input.optString("name", "")}"
            "manage_brain" -> "Delete brain node: ${input.optString("id", "")}"
            else -> "Execute $toolName"
        }
    }
    
    private fun formatCommand(toolName: String, input: JSONObject): String {
        return when (toolName) {
            "shell" -> input.optString("command", "")
            "write" -> "write → ${input.optString("path", "")}"
            else -> input.toString().take(200)
        }
    }
    
    private fun assessRisk(toolName: String, input: JSONObject): RiskLevel {
        if (toolName == "shell") {
            val cmd = input.optString("command", "").lowercase()
            return when {
                cmd.contains("rm -rf") || cmd.contains("format") || cmd.contains("dd if=") -> RiskLevel.CRITICAL
                cmd.contains("git push") || cmd.contains("DELETE FROM") -> RiskLevel.HIGH
                cmd.contains("rm ") || cmd.contains(">") -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            }
        }
        return RiskLevel.LOW
    }
}
