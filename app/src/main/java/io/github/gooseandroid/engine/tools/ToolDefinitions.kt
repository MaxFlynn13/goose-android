package io.github.gooseandroid.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Core interfaces & data classes
// ---------------------------------------------------------------------------

interface Tool {
    val name: String
    val description: String
    fun getSchema(): JSONObject
    suspend fun execute(input: JSONObject): ToolResult
}

data class ToolResult(
    val output: String,
    val isError: Boolean = false
)

// ---------------------------------------------------------------------------
// Helper – build an OpenAI-style function-calling schema
// ---------------------------------------------------------------------------

private fun functionSchema(
    name: String,
    description: String,
    properties: JSONObject,
    required: JSONArray
): JSONObject = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", required)
        })
    })
}

// ---------------------------------------------------------------------------
// Helper – resolve a path safely inside the workspace
// ---------------------------------------------------------------------------

private fun resolveSafe(workingDir: File, path: String): File {
    // Reject null bytes and control characters in path
    if (path.any { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) {
        throw SecurityException("Path contains invalid characters (null bytes or control characters): rejected")
    }

    val resolved = if (File(path).isAbsolute) File(path) else File(workingDir, path)
    val canonical = resolved.canonicalFile
    val workspaceCanonical = workingDir.canonicalFile

    // Strict path traversal check: canonical path must start with workspace path
    // followed by either nothing (same dir) or a path separator
    if (canonical.path != workspaceCanonical.path &&
        !canonical.path.startsWith(workspaceCanonical.path + File.separator)
    ) {
        throw SecurityException(
            "Path traversal blocked: '$path' resolves to '${canonical.path}' which is outside workspace '${workspaceCanonical.path}'"
        )
    }

    return canonical
}

// ---------------------------------------------------------------------------
// 1. ShellTool – Hardened against:
//    1. Command injection via null bytes
//    2. Process hangs (60s timeout with destroyForcibly + stream close)
//    3. OOM from huge output (50KB/2000 line hard cap)
//    4. Working directory doesn't exist
//    5. Shell binary not found
// ---------------------------------------------------------------------------

class ShellTool(
    private val workingDir: File,
    private val extraEnv: Map<String, String> = emptyMap()
) : Tool {

    override val name = "shell"
    override val description =
        "Execute a shell command in the working directory. Returns combined stdout/stderr and exit code."

    override fun getSchema(): JSONObject = functionSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "The shell command to execute")
            })
        },
        required = JSONArray().apply { put("command") }
    )

    /** Timeout in seconds for command execution */
    private val timeoutSeconds = 60L

    /** Shell binary candidates in priority order */
    private val shellCandidates = listOf("sh", "/system/bin/sh", "/system/xbin/sh", "/bin/sh")

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val rawCommand = input.optString("command", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'command' is required", isError = true)
        }

        // HARDENING 1: Strip null bytes to prevent command injection
        val command = rawCommand.replace("\u0000", "")
        if (command != rawCommand) {
            // Log that null bytes were stripped (command was potentially malicious)
            // Continue with sanitized command
        }

        if (command.isBlank()) {
            return@withContext ToolResult("Error: Command is empty after sanitization", isError = true)
        }

        try {
            // HARDENING 4: Ensure working directory exists, create if needed
            if (!workingDir.exists()) {
                val created = workingDir.mkdirs()
                if (!created && !workingDir.exists()) {
                    return@withContext ToolResult(
                        "Error: Working directory '${workingDir.absolutePath}' does not exist and could not be created",
                        isError = true
                    )
                }
            }
            if (!workingDir.isDirectory) {
                return@withContext ToolResult(
                    "Error: Working directory path '${workingDir.absolutePath}' exists but is not a directory",
                    isError = true
                )
            }

            // Ensure tmp directory exists inside workspace
            val tmpDir = File(workingDir, ".tmp").apply { mkdirs() }

            // Build PATH: extra env paths + system paths
            val pathParts = mutableListOf<String>()
            val extraPath = extraEnv["PATH"]
            if (!extraPath.isNullOrBlank()) {
                pathParts.addAll(extraPath.split(":").filter { it.isNotBlank() })
            }
            if (!pathParts.contains("/system/bin")) pathParts.add("/system/bin")
            if (!pathParts.contains("/system/xbin")) pathParts.add("/system/xbin")

            // HARDENING 5: Find a working shell binary
            val shellBinary = findShellBinary()
                ?: return@withContext ToolResult(
                    "Error: No shell binary found. Tried: ${shellCandidates.joinToString(", ")}",
                    isError = true
                )

            val pb = ProcessBuilder(shellBinary, "-c", command)
                .directory(workingDir)
                .redirectErrorStream(true)

            // Set up environment
            val env = pb.environment()
            env["HOME"] = workingDir.absolutePath
            env["TMPDIR"] = tmpDir.absolutePath
            env["PATH"] = pathParts.joinToString(":")
            env["LANG"] = "en_US.UTF-8"
            env["TERM"] = "xterm-256color"

            // Overlay any additional env vars from extraEnv (except PATH which we built above)
            for ((key, value) in extraEnv) {
                if (key != "PATH") {
                    env[key] = value
                }
            }

            val process = pb.start()

            // HARDENING 3: Strict output limits with immediate stream closure
            val maxLines = 2000
            val maxBytes = 50 * 1024L // 50 KB
            val lines = mutableListOf<String>()
            var totalBytes = 0L
            var truncated = false

            val inputStream = process.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream))

            try {
                var line = reader.readLine()
                while (line != null) {
                    if (lines.size >= maxLines || totalBytes >= maxBytes) {
                        truncated = true
                        break // Stop reading immediately
                    }
                    lines.add(line)
                    totalBytes += line.length + 1 // +1 for newline
                    line = reader.readLine()
                }
            } finally {
                // Close streams immediately after limit hit or read complete
                try { reader.close() } catch (_: Exception) {}
                try { inputStream.close() } catch (_: Exception) {}
                // Also close the output stream to the process (stdin)
                try { process.outputStream.close() } catch (_: Exception) {}
            }

            // HARDENING 2: Timeout with destroyForcibly AND stream cleanup
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                // Process did not complete — kill it forcibly
                process.destroyForcibly()
                // Close any remaining streams
                try { process.errorStream.close() } catch (_: Exception) {}
                // Give it a moment to actually die
                process.waitFor(2, TimeUnit.SECONDS)

                val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
                return@withContext ToolResult(
                    output = buildString {
                        append(lines.joinToString("\n"))
                        if (truncated) {
                            append("\n\n[TRUNCATED] Output exceeded $maxLines lines or ${maxBytes / 1024}KB limit.")
                        }
                        append("\n\n[TIMEOUT] Command exceeded ${timeoutSeconds}s limit and was killed (destroyForcibly).")
                        append("\nExit code: $exitCode")
                    },
                    isError = true
                )
            }

            val exitCode = process.exitValue()
            val outputText = buildString {
                append(lines.joinToString("\n"))
                if (truncated) {
                    append("\n\n[TRUNCATED] Output exceeded $maxLines lines or ${maxBytes / 1024}KB limit.")
                }
                append("\n\nExit code: $exitCode")
            }

            ToolResult(output = outputText, isError = exitCode != 0)
        } catch (e: IOException) {
            ToolResult(output = "Error executing command (I/O failure): ${e.message}", isError = true)
        } catch (e: SecurityException) {
            ToolResult(output = "Error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult(output = "Error executing command: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    /**
     * HARDENING 5: Try multiple shell binary paths to find one that exists and is executable.
     */
    private fun findShellBinary(): String? {
        for (candidate in shellCandidates) {
            if (candidate.contains("/")) {
                // Absolute path — check if file exists and is executable
                val file = File(candidate)
                if (file.exists() && file.canExecute()) {
                    return candidate
                }
            } else {
                // Relative name — check if it's resolvable via common paths
                val commonPaths = listOf("/system/bin", "/system/xbin", "/bin", "/usr/bin")
                for (dir in commonPaths) {
                    val file = File(dir, candidate)
                    if (file.exists() && file.canExecute()) {
                        return file.absolutePath
                    }
                }
                // Fall back to just the name and let ProcessBuilder resolve via PATH
                return candidate
            }
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// 2. FileWriteTool – Hardened against:
//    1. Path traversal attack (resolveSafe rejects ".." escapes)
//    2. Disk full (catch IOException with clear error)
//    3. File locked by another process (retry once after 100ms)
//    4. Invalid filename characters (null bytes, control chars)
// ---------------------------------------------------------------------------

class FileWriteTool(private val workingDir: File) : Tool {

    override val name = "write"
    override val description =
        "Create a new file or overwrite an existing file. Creates parent directories if needed."

    override fun getSchema(): JSONObject = functionSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "File path (relative to workspace or absolute within workspace)")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "The full content to write to the file")
            })
        },
        required = JSONArray().apply { put("path"); put("content") }
    )

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = input.optString("path", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'path' is required", isError = true)
        }
        val content = input.optString("content", "")

        // HARDENING 4: Reject filenames with null bytes or control characters
        if (path.any { it == '\u0000' }) {
            return@withContext ToolResult(
                "Error: Path contains null bytes — this is not allowed",
                isError = true
            )
        }
        val filename = File(path).name
        if (filename.any { it.isISOControl() }) {
            return@withContext ToolResult(
                "Error: Filename contains control characters — this is not allowed. Got: '${
                    filename.map { if (it.isISOControl()) "\\x${it.code.toString(16).padStart(2, '0')}" else it.toString() }.joinToString("")
                }'",
                isError = true
            )
        }

        try {
            // HARDENING 1: Path traversal protection via resolveSafe
            val file = resolveSafe(workingDir, path)

            // Create parent directories
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    val created = parent.mkdirs()
                    if (!created && !parent.exists()) {
                        return@withContext ToolResult(
                            "Error: Could not create parent directories for '${file.path}'",
                            isError = true
                        )
                    }
                }
            }

            // HARDENING 2 & 3: Write with retry for locked files, catch disk full
            val writeResult = attemptWrite(file, content)
            if (writeResult != null) {
                return@withContext writeResult
            }

            ToolResult(output = "Wrote ${content.length} bytes to ${file.path}")
        } catch (e: SecurityException) {
            ToolResult(output = "Error: ${e.message}", isError = true)
        } catch (e: IOException) {
            // HARDENING 2: Disk full or other I/O error
            val reason = when {
                e.message?.contains("No space left", ignoreCase = true) == true -> "Disk full"
                e.message?.contains("ENOSPC", ignoreCase = true) == true -> "Disk full"
                e.message?.contains("Read-only file system", ignoreCase = true) == true -> "Read-only file system"
                else -> "I/O error"
            }
            ToolResult(
                output = "Error writing file ($reason): ${e.message}",
                isError = true
            )
        } catch (e: Exception) {
            ToolResult(output = "Error writing file: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    /**
     * Attempt to write file content. Returns null on success, or a ToolResult on failure.
     * HARDENING 3: Retries once after 100ms if file appears locked.
     */
    private fun attemptWrite(file: File, content: String): ToolResult? {
        try {
            file.writeText(content)
            return null // success
        } catch (e: IOException) {
            // Check if it might be a lock issue (file exists but can't write)
            val isLockLikely = file.exists() && !file.canWrite() ||
                    e.message?.contains("lock", ignoreCase = true) == true ||
                    e.message?.contains("busy", ignoreCase = true) == true ||
                    e.message?.contains("EBUSY", ignoreCase = true) == true ||
                    e.message?.contains("EAGAIN", ignoreCase = true) == true

            if (isLockLikely) {
                // HARDENING 3: Retry once after 100ms delay
                try {
                    Thread.sleep(100)
                    file.writeText(content)
                    return null // success on retry
                } catch (retryEx: IOException) {
                    val reason = when {
                        retryEx.message?.contains("No space left", ignoreCase = true) == true -> "Disk full"
                        retryEx.message?.contains("ENOSPC", ignoreCase = true) == true -> "Disk full"
                        else -> "File locked or busy (retry also failed)"
                    }
                    return ToolResult(
                        output = "Error writing file ($reason): ${retryEx.message}. " +
                                "The file may be locked by another process.",
                        isError = true
                    )
                }
            }

            // Not a lock issue — re-throw for outer handler
            throw e
        }
    }
}

// ---------------------------------------------------------------------------
// 3. FileEditTool – Hardened against:
//    1. File doesn't exist (clear error with suggestion)
//    2. Before text not found (show first 200 chars to help user)
//    3. Before text matches multiple times (show count and positions)
//    4. File too large (reject > 5MB)
// ---------------------------------------------------------------------------

class FileEditTool(private val workingDir: File) : Tool {

    override val name = "edit"
    override val description =
        "Edit a file by finding and replacing text. The 'before' text must match exactly and uniquely."

    override fun getSchema(): JSONObject = functionSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "File path to edit")
            })
            put("before", JSONObject().apply {
                put("type", "string")
                put("description", "Exact text to find (must appear exactly once)")
            })
            put("after", JSONObject().apply {
                put("type", "string")
                put("description", "Replacement text (use empty string to delete)")
            })
        },
        required = JSONArray().apply { put("path"); put("before"); put("after") }
    )

    companion object {
        /** Maximum file size we'll read into memory for editing (5 MB) */
        private const val MAX_FILE_SIZE = 5L * 1024 * 1024
    }

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = input.optString("path", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'path' is required", isError = true)
        }
        val before = input.optString("before", "").also {
            if (it.isEmpty()) return@withContext ToolResult("Error: 'before' text is required", isError = true)
        }
        val after = input.optString("after", "")

        try {
            val file = resolveSafe(workingDir, path)

            // HARDENING 1: File doesn't exist — clear error with suggestion
            if (!file.exists()) {
                return@withContext ToolResult(
                    "Error: File not found: '${file.path}'\n" +
                    "Suggestion: Use the 'write' tool to create this file first, " +
                    "or check the path with 'tree' tool.",
                    isError = true
                )
            }
            if (!file.canRead()) {
                return@withContext ToolResult(
                    "Error: Permission denied (cannot read): ${file.path}",
                    isError = true
                )
            }
            if (!file.canWrite()) {
                return@withContext ToolResult(
                    "Error: Permission denied (cannot write): ${file.path}",
                    isError = true
                )
            }

            // HARDENING 4: File too large to read into memory
            val fileSize = file.length()
            if (fileSize > MAX_FILE_SIZE) {
                val sizeMB = String.format("%.1f", fileSize.toDouble() / (1024 * 1024))
                return@withContext ToolResult(
                    "Error: File is too large to edit in memory (${sizeMB}MB, limit is ${MAX_FILE_SIZE / (1024 * 1024)}MB).\n" +
                    "Consider using 'shell' tool with sed/awk for large file edits, " +
                    "or split the file into smaller parts.",
                    isError = true
                )
            }

            val content = file.readText()

            // Count occurrences and record positions
            val positions = mutableListOf<Int>()
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(before, searchFrom)
                if (idx == -1) break
                positions.add(idx)
                searchFrom = idx + before.length
            }

            when (positions.size) {
                0 -> {
                    // HARDENING 2: Before text not found — show first 200 chars to help user
                    val preview = content.take(200).let { preview ->
                        if (content.length > 200) "$preview..." else preview
                    }
                    return@withContext ToolResult(
                        "Error: 'before' text not found in ${file.path}\n\n" +
                        "File starts with:\n```\n$preview\n```\n\n" +
                        "File size: ${content.length} chars, ${content.lines().size} lines.\n" +
                        "Make sure the 'before' text matches exactly (including whitespace and newlines).",
                        isError = true
                    )
                }
                1 -> { /* exactly one match – proceed */ }
                else -> {
                    // HARDENING 3: Multiple matches — show count and line positions
                    val linePositions = positions.map { pos ->
                        val lineNum = content.substring(0, pos).count { it == '\n' } + 1
                        val col = pos - content.lastIndexOf('\n', pos - 1)
                        "line $lineNum, col $col"
                    }
                    return@withContext ToolResult(
                        "Error: 'before' text found ${positions.size} times in ${file.path}. It must be unique.\n\n" +
                        "Occurrences at: ${linePositions.joinToString("; ")}\n\n" +
                        "Include more surrounding context in 'before' to make it unique.",
                        isError = true
                    )
                }
            }

            val newContent = content.replaceFirst(before, after)
            file.writeText(newContent)

            // Build a preview with context
            val matchIdx = positions[0]
            val allLines = content.lines()
            var charCount = 0
            var matchLine = 0
            for ((i, line) in allLines.withIndex()) {
                if (charCount + line.length >= matchIdx) {
                    matchLine = i
                    break
                }
                charCount += line.length + 1 // +1 for \n
            }

            val contextStart = maxOf(0, matchLine - 3)
            val newLines = newContent.lines()
            val afterLines = after.lines().size
            val contextEnd = minOf(newLines.size - 1, matchLine + afterLines + 3)

            val preview = buildString {
                appendLine("Edit applied to ${file.path}")
                appendLine("--- Context around change (lines ${contextStart + 1}..${contextEnd + 1}) ---")
                for (i in contextStart..contextEnd) {
                    if (i < newLines.size) appendLine("${i + 1}: ${newLines[i]}")
                }
            }

            ToolResult(output = preview)
        } catch (e: SecurityException) {
            ToolResult(output = "Error: ${e.message}", isError = true)
        } catch (e: IOException) {
            ToolResult(output = "Error reading/writing file: ${e.message}", isError = true)
        } catch (e: OutOfMemoryError) {
            ToolResult(
                output = "Error: File is too large to process (out of memory). Use 'shell' tool with sed/awk instead.",
                isError = true
            )
        } catch (e: Exception) {
            ToolResult(output = "Error editing file: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }
}

// ---------------------------------------------------------------------------
// 4. TreeTool – Hardened against:
//    1. Symlink loops (track visited canonical paths)
//    2. Permission denied on subdirectory (skip and note)
//    3. Too many entries (hard cap at 500 with "... and N more")
// ---------------------------------------------------------------------------

class TreeTool(private val workingDir: File) : Tool {

    override val name = "tree"
    override val description =
        "List directory structure with line counts per file. Respects .gitignore if present."

    override fun getSchema(): JSONObject = functionSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "Directory path (default: current directory)")
            })
            put("max_depth", JSONObject().apply {
                put("type", "integer")
                put("description", "Maximum directory depth (default: 3)")
            })
        },
        required = JSONArray() // both optional
    )

    companion object {
        /** Hard cap on entries to prevent runaway output */
        private const val MAX_ENTRIES = 500
    }

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = input.optString("path", ".")
        val maxDepth = input.optInt("max_depth", 3).coerceIn(1, 10) // Sane bounds

        try {
            val dir = resolveSafe(workingDir, path)
            if (!dir.exists()) {
                return@withContext ToolResult("Error: Path not found: ${dir.path}", isError = true)
            }
            if (!dir.isDirectory) {
                // Single file – just report line count
                val lines = try { dir.readLines().size } catch (_: Exception) { -1 }
                val info = if (lines >= 0) "$lines lines" else "binary/unreadable"
                return@withContext ToolResult("${dir.name} ($info)")
            }
            if (!dir.canRead()) {
                return@withContext ToolResult("Error: Permission denied: cannot read '${dir.path}'", isError = true)
            }

            // Load .gitignore patterns (simple glob support)
            val ignorePatterns = loadGitignore(dir)

            val entries = mutableListOf<String>()
            var totalSkipped = 0
            var truncated = false
            var permissionDeniedDirs = mutableListOf<String>()

            // HARDENING 1: Track visited canonical paths to detect symlink loops
            val visitedCanonicalPaths = mutableSetOf<String>()
            // Add the root itself
            visitedCanonicalPaths.add(dir.canonicalPath)

            fun walk(current: File, depth: Int, prefix: String) {
                if (depth > maxDepth || truncated) return

                // HARDENING 2: Permission denied on subdirectory
                if (!current.canRead()) {
                    permissionDeniedDirs.add(current.relativeTo(dir).path)
                    return
                }

                val children = try {
                    current.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                } catch (e: SecurityException) {
                    permissionDeniedDirs.add(current.relativeTo(dir).path)
                    null
                } ?: return

                for ((index, child) in children.withIndex()) {
                    if (truncated) return

                    // HARDENING 3: Hard cap at MAX_ENTRIES
                    if (entries.size >= MAX_ENTRIES) {
                        totalSkipped = children.size - index
                        truncated = true
                        return
                    }

                    val relativePath = child.relativeTo(dir).path
                    if (shouldIgnore(relativePath, child.isDirectory, ignorePatterns)) continue

                    val isLast = index == children.lastIndex
                    val connector = if (isLast) "└── " else "├── "
                    val childPrefix = if (isLast) "$prefix    " else "$prefix│   "

                    if (child.isDirectory) {
                        // HARDENING 1: Symlink loop detection
                        val canonicalPath = try {
                            child.canonicalPath
                        } catch (_: IOException) {
                            // Can't resolve canonical path — skip this entry
                            entries.add("$prefix$connector${child.name}/ [unresolvable symlink]")
                            continue
                        }

                        if (canonicalPath in visitedCanonicalPaths) {
                            // Symlink loop detected — skip
                            entries.add("$prefix$connector${child.name}/ [symlink loop → $canonicalPath]")
                            continue
                        }
                        visitedCanonicalPaths.add(canonicalPath)

                        entries.add("$prefix$connector${child.name}/")
                        walk(child, depth + 1, childPrefix)
                    } else {
                        val lineCount = try {
                            child.readLines().size
                        } catch (_: Exception) {
                            -1
                        }
                        val lineInfo = if (lineCount >= 0) " ($lineCount lines)" else " (binary)"
                        entries.add("$prefix$connector${child.name}$lineInfo")
                    }
                }
            }

            entries.add("${dir.name}/")
            walk(dir, 1, "")

            val output = buildString {
                entries.forEach { appendLine(it) }

                // HARDENING 2: Report permission-denied directories
                if (permissionDeniedDirs.isNotEmpty()) {
                    appendLine()
                    appendLine("[PERMISSION DENIED] Skipped ${permissionDeniedDirs.size} directories:")
                    for (d in permissionDeniedDirs.take(10)) {
                        appendLine("  - $d")
                    }
                    if (permissionDeniedDirs.size > 10) {
                        appendLine("  ... and ${permissionDeniedDirs.size - 10} more")
                    }
                }

                // HARDENING 3: Truncation notice
                if (truncated) {
                    appendLine()
                    appendLine("[TRUNCATED] Output limited to $MAX_ENTRIES entries. Use a deeper path or increase specificity.")
                }
            }

            ToolResult(output = output.trimEnd())
        } catch (e: SecurityException) {
            ToolResult(output = "Error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult(output = "Error listing directory: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    // Simple .gitignore support
    private fun loadGitignore(root: File): List<String> {
        val gitignoreFile = File(root, ".gitignore")
        if (!gitignoreFile.exists()) return emptyList()
        return try {
            gitignoreFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun shouldIgnore(relativePath: String, isDir: Boolean, patterns: List<String>): Boolean {
        // Always ignore .git directory
        if (relativePath == ".git" || relativePath.startsWith(".git/")) return true

        val pathSegments = relativePath.split("/")
        for (pattern in patterns) {
            val cleanPattern = pattern.trimEnd('/')
            val dirOnly = pattern.endsWith("/")

            if (dirOnly && !isDir) continue

            // Match against any path segment or the full relative path
            if (pathSegments.any { matchGlob(cleanPattern, it) }) return true
            if (matchGlob(cleanPattern, relativePath)) return true
        }
        return false
    }

    private fun matchGlob(pattern: String, text: String): Boolean {
        // Convert simple glob to regex: * -> [^/]*, ** -> .*, ? -> .
        val regex = buildString {
            append("^")
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern[i] == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                        append(".*")
                        i += 2
                    }
                    pattern[i] == '*' -> {
                        append("[^/]*")
                        i++
                    }
                    pattern[i] == '?' -> {
                        append(".")
                        i++
                    }
                    pattern[i] in ".+()[]{}^$|\\".toSet() -> {
                        append("\\")
                        append(pattern[i])
                        i++
                    }
                    else -> {
                        append(pattern[i])
                        i++
                    }
                }
            }
            append("$")
        }
        return try {
            Regex(regex).matches(text)
        } catch (_: Exception) {
            false
        }
    }
}
