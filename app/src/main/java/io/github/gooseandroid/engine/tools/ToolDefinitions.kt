package io.github.gooseandroid.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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
    val resolved = if (File(path).isAbsolute) File(path) else File(workingDir, path)
    val canonical = resolved.canonicalFile
    val workspaceCanonical = workingDir.canonicalFile
    if (!canonical.path.startsWith(workspaceCanonical.path)) {
        throw SecurityException("Path traversal blocked: $path resolves outside workspace")
    }
    return canonical
}

// ---------------------------------------------------------------------------
// 1. ShellTool
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

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val command = input.optString("command", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'command' is required", isError = true)
        }

        try {
            // Ensure tmp directory exists inside workspace
            val tmpDir = File(workingDir, ".tmp").apply { mkdirs() }

            // Build PATH: extra env paths + system paths
            val pathParts = mutableListOf<String>()
            // Add any paths from extraEnv first
            val extraPath = extraEnv["PATH"]
            if (!extraPath.isNullOrBlank()) {
                pathParts.addAll(extraPath.split(":").filter { it.isNotBlank() })
            }
            // Always include system paths
            if (!pathParts.contains("/system/bin")) pathParts.add("/system/bin")
            if (!pathParts.contains("/system/xbin")) pathParts.add("/system/xbin")

            // Use "sh" and let PATH resolve it, rather than hardcoding /system/bin/sh
            // This handles devices where sh is in a different location
            val pb = ProcessBuilder("sh", "-c", command)
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

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = mutableListOf<String>()
            var totalBytes = 0L
            val maxLines = 2000
            val maxBytes = 50 * 1024L // 50 KB
            var truncated = false

            reader.use { r ->
                var line = r.readLine()
                while (line != null) {
                    if (lines.size >= maxLines || totalBytes >= maxBytes) {
                        truncated = true
                        break
                    }
                    lines.add(line)
                    totalBytes += line.length + 1 // +1 for newline
                    line = r.readLine()
                }
            }

            // Wait with a real timeout that kills the process if exceeded
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                // Process did not complete within timeout — kill it forcibly
                process.destroyForcibly()
                // Give it a moment to actually die
                process.waitFor(2, TimeUnit.SECONDS)

                val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
                return@withContext ToolResult(
                    output = buildString {
                        append(lines.joinToString("\n"))
                        if (truncated) {
                            append("\n\n[TRUNCATED] Output exceeded $maxLines lines or ${maxBytes / 1024}KB limit.")
                        }
                        append("\n\n[TIMEOUT] Command exceeded ${timeoutSeconds}s limit and was killed.")
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
        } catch (e: Exception) {
            ToolResult(output = "Error executing command: ${e.message}", isError = true)
        }
    }
}

// ---------------------------------------------------------------------------
// 2. FileWriteTool
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

        try {
            val file = resolveSafe(workingDir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult(output = "Wrote ${content.length} bytes to ${file.path}")
        } catch (e: SecurityException) {
            ToolResult(output = "Error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult(output = "Error writing file: ${e.message}", isError = true)
        }
    }
}

// ---------------------------------------------------------------------------
// 3. FileEditTool
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
            if (!file.exists()) {
                return@withContext ToolResult("Error: File not found: ${file.path}", isError = true)
            }
            if (!file.canRead() || !file.canWrite()) {
                return@withContext ToolResult("Error: Permission denied: ${file.path}", isError = true)
            }

            val content = file.readText()

            // Count occurrences
            var count = 0
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(before, searchFrom)
                if (idx == -1) break
                count++
                searchFrom = idx + before.length
            }

            when (count) {
                0 -> return@withContext ToolResult(
                    "Error: 'before' text not found in ${file.path}",
                    isError = true
                )
                1 -> { /* exactly one match – proceed */ }
                else -> return@withContext ToolResult(
                    "Error: 'before' text found $count times in ${file.path}. It must be unique.",
                    isError = true
                )
            }

            val newContent = content.replaceFirst(before, after)
            file.writeText(newContent)

            // Build a preview with context
            val matchIdx = content.indexOf(before)
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
        } catch (e: Exception) {
            ToolResult(output = "Error editing file: ${e.message}", isError = true)
        }
    }
}

// ---------------------------------------------------------------------------
// 4. TreeTool
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

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = input.optString("path", ".")
        val maxDepth = input.optInt("max_depth", 3)

        try {
            val dir = resolveSafe(workingDir, path)
            if (!dir.exists()) {
                return@withContext ToolResult("Error: Path not found: ${dir.path}", isError = true)
            }
            if (!dir.isDirectory) {
                // Single file – just report line count
                val lines = dir.readLines().size
                return@withContext ToolResult("${dir.name} ($lines lines)")
            }

            // Load .gitignore patterns (simple glob support)
            val ignorePatterns = loadGitignore(dir)

            val entries = mutableListOf<String>()
            val maxEntries = 500
            var truncated = false

            fun walk(current: File, depth: Int, prefix: String) {
                if (depth > maxDepth || truncated) return
                val children = current.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    ?: return

                for ((index, child) in children.withIndex()) {
                    if (truncated) return
                    if (entries.size >= maxEntries) {
                        truncated = true
                        return
                    }

                    val relativePath = child.relativeTo(dir).path
                    if (shouldIgnore(relativePath, child.isDirectory, ignorePatterns)) continue

                    val isLast = index == children.lastIndex
                    val connector = if (isLast) "└── " else "├── "
                    val childPrefix = if (isLast) "$prefix    " else "$prefix│   "

                    if (child.isDirectory) {
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
                if (truncated) appendLine("\n[TRUNCATED] Output limited to $maxEntries entries.")
            }

            ToolResult(output = output.trimEnd())
        } catch (e: SecurityException) {
            ToolResult(output = "Error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult(output = "Error listing directory: ${e.message}", isError = true)
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
