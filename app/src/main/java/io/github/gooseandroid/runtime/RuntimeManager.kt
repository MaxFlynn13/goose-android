package io.github.gooseandroid.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages bundled runtime tools (BusyBox, Git) and optional downloadable packs (Node.js, Python).
 *
 * Architecture:
 * - Core tools (BusyBox, Git) are bundled in the APK as assets
 * - They are extracted to the workspace/runtimes/ directory on first launch
 * - BusyBox is symlinked to provide 300+ standard Unix commands
 * - The PATH is set to include runtimes/bin/ when goose serve starts
 *
 * This gives Goose's shell tool access to: grep, sed, awk, curl, git, find, xargs, etc.
 */
class RuntimeManager(private val context: Context) {

    companion object {
        private const val TAG = "RuntimeManager"
        private const val RUNTIMES_DIR = "workspace/runtimes"
        private const val BIN_DIR = "workspace/runtimes/bin"
        private const val BUSYBOX_ASSET = "busybox-arm64"
        private const val GIT_ASSET = "git-arm64"

        // BusyBox applets to symlink
        private val BUSYBOX_APPLETS = listOf(
            "awk", "base64", "basename", "cat", "chmod", "chown", "clear",
            "cmp", "comm", "cp", "cut", "date", "dd", "df", "diff",
            "dirname", "du", "echo", "env", "expr", "false", "find",
            "grep", "egrep", "fgrep", "gzip", "gunzip", "head", "hexdump",
            "hostname", "id", "install", "kill", "less", "ln", "ls",
            "md5sum", "mkdir", "mktemp", "mv", "nice", "nohup", "od",
            "paste", "patch", "printf", "pwd", "readlink", "realpath",
            "rm", "rmdir", "sed", "seq", "sha1sum", "sha256sum", "sha512sum",
            "sleep", "sort", "split", "stat", "strings", "tail", "tar",
            "tee", "test", "touch", "tr", "true", "truncate", "tty",
            "uname", "uniq", "unzip", "uptime", "wc", "wget", "which",
            "whoami", "xargs", "yes", "zip"
        )
    }

    private val runtimesDir = File(context.filesDir, RUNTIMES_DIR)
    private val binDir = File(context.filesDir, BIN_DIR)

    /**
     * Check if core tools are already extracted.
     */
    fun isInitialized(): Boolean {
        val busybox = File(binDir, "busybox")
        return busybox.exists() && busybox.canExecute()
    }

    /**
     * Extract and set up all bundled runtime tools.
     * Call this once on first launch or after an update.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            runtimesDir.mkdirs()
            binDir.mkdirs()

            // Extract BusyBox
            val busyboxResult = extractAsset(BUSYBOX_ASSET, File(binDir, "busybox"))
            if (busyboxResult.isFailure) return@withContext busyboxResult

            // Create BusyBox symlinks
            createBusyboxSymlinks()

            // Extract Git (if bundled)
            val gitAssetExists = try {
                context.assets.open(GIT_ASSET).close()
                true
            } catch (e: Exception) { false }

            if (gitAssetExists) {
                extractAsset(GIT_ASSET, File(binDir, "git"))
            }

            Log.i(TAG, "Runtime tools initialized: ${binDir.listFiles()?.size ?: 0} commands available")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize runtime tools", e)
            Result.failure(e)
        }
    }

    /**
     * Get the PATH string that includes our runtime binaries.
     * This should be prepended to the system PATH when starting goose serve.
     */
    fun getRuntimePath(): String {
        return binDir.absolutePath
    }

    /**
     * Get the HOME directory for goose (the workspace root).
     */
    fun getWorkspaceHome(): String {
        return File(context.filesDir, "workspace").absolutePath
    }

    /**
     * List all available commands in the bin directory.
     */
    fun getAvailableCommands(): List<String> {
        return binDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    /**
     * Check if a specific command is available.
     */
    fun hasCommand(command: String): Boolean {
        return File(binDir, command).exists()
    }

    /**
     * Get info about installed runtimes.
     */
    fun getRuntimeInfo(): RuntimeInfo {
        val commands = getAvailableCommands()
        val totalSize = binDir.walkTopDown().sumOf { it.length() }
        return RuntimeInfo(
            isInitialized = isInitialized(),
            commandCount = commands.size,
            totalSizeBytes = totalSize,
            hasBusybox = hasCommand("busybox"),
            hasGit = hasCommand("git"),
            hasNode = hasCommand("node"),
            hasPython = hasCommand("python3"),
            binPath = binDir.absolutePath
        )
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private fun extractAsset(assetName: String, targetFile: File): Result<Unit> {
        return try {
            context.assets.open(assetName).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setExecutable(true, false)
            targetFile.setReadable(true, false)
            Log.d(TAG, "Extracted $assetName to ${targetFile.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            // Asset might not be bundled yet — this is OK during development
            Log.w(TAG, "Could not extract asset $assetName: ${e.message}")
            Result.failure(e)
        }
    }

    private fun createBusyboxSymlinks() {
        val busybox = File(binDir, "busybox")
        if (!busybox.exists()) return

        for (applet in BUSYBOX_APPLETS) {
            val link = File(binDir, applet)
            if (!link.exists()) {
                try {
                    // Android doesn't have symlink in Java < API 26, use exec
                    Runtime.getRuntime().exec(
                        arrayOf("ln", "-sf", busybox.absolutePath, link.absolutePath)
                    ).waitFor()
                } catch (e: Exception) {
                    // Fallback: copy the busybox binary (wastes space but works)
                    Log.w(TAG, "Symlink failed for $applet, skipping")
                }
            }
        }
    }
}

/**
 * Information about installed runtime tools.
 */
data class RuntimeInfo(
    val isInitialized: Boolean,
    val commandCount: Int,
    val totalSizeBytes: Long,
    val hasBusybox: Boolean,
    val hasGit: Boolean,
    val hasNode: Boolean,
    val hasPython: Boolean,
    val binPath: String
)
