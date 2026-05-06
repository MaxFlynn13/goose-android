package io.github.gooseandroid.engine.tools

import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Python execution tool using Chaquopy (embedded CPython).
 *
 * Allows Goose to execute Python scripts on-device without any external runtime.
 * Bundled packages: requests, pyyaml, beautifulsoup4, httpx
 *
 * Usage by the AI:
 * - Execute inline Python code
 * - Run Python scripts from the workspace
 * - Use pip-installed packages for web scraping, HTTP, YAML parsing
 */
class PythonTool(
    private val context: Context,
    private val workingDir: File
) : Tool {

    companion object {
        private const val TAG = "PythonTool"
        private const val MAX_OUTPUT_LENGTH = 50_000
        private const val TIMEOUT_MS = 60_000L
    }

    override val name = "python"
    override val description = "Execute Python 3.11 code on-device. " +
        "Available packages: requests, pyyaml, beautifulsoup4, httpx. " +
        "Use for data processing, web scraping, API calls, file manipulation, and scripting."

    private var pythonInitialized = false

    override fun getSchema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", "python")
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("code", JSONObject().apply {
                        put("type", "string")
                        put("description", "Python code to execute. Can be multi-line. " +
                            "Use print() for output. The working directory is the workspace.")
                    })
                    put("script_path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional: path to a .py file in the workspace to execute instead of inline code")
                    })
                })
                put("required", JSONArray().apply { put("code") })
            })
        })
    }

    override suspend fun execute(input: JSONObject): ToolResult {
        val code = input.optString("code", "")
        val scriptPath = input.optString("script_path", "")

        if (code.isBlank() && scriptPath.isBlank()) {
            return ToolResult(output = "Error: either 'code' or 'script_path' must be provided", isError = true)
        }

        // Initialize Python if needed
        if (!ensurePythonInitialized()) {
            return ToolResult(
                output = "Python runtime not available. Chaquopy may not be properly configured.",
                isError = true
            )
        }

        val pythonCode = if (scriptPath.isNotBlank()) {
            val file = File(workingDir, scriptPath)
            if (!file.exists()) {
                return ToolResult(output = "Script not found: $scriptPath", isError = true)
            }
            if (!file.canonicalPath.startsWith(workingDir.canonicalPath)) {
                return ToolResult(output = "Access denied: path outside workspace", isError = true)
            }
            file.readText()
        } else {
            code
        }

        return executePython(pythonCode)
    }

    private fun ensurePythonInitialized(): Boolean {
        if (pythonInitialized) return true
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            pythonInitialized = true
            Log.i(TAG, "Python runtime initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python: ${e.message}", e)
            false
        }
    }

    private fun executePython(code: String): ToolResult {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("__main__")

            // Set working directory
            py.getModule("os").callAttr("chdir", workingDir.absolutePath)

            // Capture stdout/stderr
            val io = py.getModule("io")
            val sys = py.getModule("sys")
            val stdout = io.callAttr("StringIO")
            val stderr = io.callAttr("StringIO")
            val oldStdout = sys.get("stdout")
            val oldStderr = sys.get("stderr")
            sys.put("stdout", stdout)
            sys.put("stderr", stderr)

            try {
                // Execute code via a helper module to avoid "frame does not exist" errors.
                // Chaquopy's exec() needs a proper module context with __builtins__.
                // We use runpy-style execution through a temp file approach.
                val tempScript = File(workingDir, ".goose_exec_temp.py")
                try {
                    tempScript.writeText(code)
                    // Use runpy.run_path which creates a proper execution frame
                    val runpy = py.getModule("runpy")
                    runpy.callAttr("run_path", tempScript.absolutePath)
                } finally {
                    tempScript.delete()
                }

                // Get output
                val output = stdout.callAttr("getvalue").toString()
                val errors = stderr.callAttr("getvalue").toString()

                val result = buildString {
                    if (output.isNotBlank()) append(output)
                    if (errors.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("STDERR: $errors")
                    }
                    if (isEmpty()) append("(no output)")
                }

                val truncated = if (result.length > MAX_OUTPUT_LENGTH) {
                    result.take(MAX_OUTPUT_LENGTH) + "\n... (output truncated at $MAX_OUTPUT_LENGTH chars)"
                } else result

                ToolResult(output = truncated, isError = errors.isNotBlank())
            } finally {
                // Restore stdout/stderr
                sys.put("stdout", oldStdout)
                sys.put("stderr", oldStderr)
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown Python error"
            Log.e(TAG, "Python execution error: $errorMsg", e)
            ToolResult(output = "Python error: $errorMsg", isError = true)
        }
    }
}
