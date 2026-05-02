package io.github.gooseandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.net.ServerSocket

/**
 * Foreground service that manages the goose binary lifecycle.
 *
 * Spawns `goose serve --host 127.0.0.1 --port PORT` and keeps it alive
 * as long as the app is in use. The Compose UI connects to this
 * local server via WebSocket.
 */
class GooseService : Service() {

    companion object {
        private const val TAG = "GooseService"
        private const val CHANNEL_ID = "goose_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val CONNECT_RETRY_DELAY_MS = 100L
        /** Minimum binary size in bytes; anything smaller is a build placeholder. */
        private const val MIN_BINARY_SIZE_BYTES = 1000L
        /** Maximum number of full start-up retries when waitForServer times out. */
        private const val MAX_SERVER_RETRIES = 2
    }

    private var gooseProcess: Process? = null
    private var port: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Reference to the local LiteRT server so we can shut it down in onDestroy(). */
    private var liteRTServer: LiteRTInferenceServer? = null

    // Binder for Activity to get port info
    inner class LocalBinder : Binder() {
        fun getService(): GooseService = this@GooseService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Goose is starting...")
        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            startGoose()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGoose()
        stopLocalServer()
        scope.cancel()
    }

    fun getPort(): Int = port

    fun isRunning(): Boolean = gooseProcess?.isAlive == true || GoosePortHolder.localOnlyMode

    private suspend fun startGoose() {
        try {
            // Initialize runtime tools (BusyBox, Git) if not already done
            val runtimeManager = io.github.gooseandroid.runtime.RuntimeManager(this)
            if (!runtimeManager.isInitialized()) {
                Log.i(TAG, "Initializing runtime tools...")
                runtimeManager.initialize()
            }

            port = findFreePort()
            Log.i(TAG, "Starting goose on port $port")

            val binaryFile = extractBinary()
            if (binaryFile == null) {
                Log.w(TAG, "Goose binary not available — starting in local-only mode")
                // Start the LiteRT inference server as fallback
                // This allows the app to work with local models even without the goose binary
                startLocalOnlyMode()
                return
            }

            val homeDir = File(filesDir, "home")
            homeDir.mkdirs()

            val gooseDir = File(homeDir, ".goose")
            gooseDir.mkdirs()

            val env = buildEnvironment(homeDir)

            val command = listOf(
                binaryFile.absolutePath,
                "serve",
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--with-builtin", "developer,memory"
            )

            Log.i(TAG, "Executing: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command)
                .directory(homeDir)
                .redirectErrorStream(false)

            processBuilder.environment().putAll(env)

            gooseProcess = processBuilder.start()

            // Capture stdout for log viewer
            scope.launch {
                try {
                    gooseProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[goose] $line")
                            io.github.gooseandroid.ui.doctor.LogCollector.addLine(line)
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Error reading goose stdout", e)
                }
            }

            // Capture stderr for log viewer
            scope.launch {
                try {
                    gooseProcess?.errorStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[goose stderr] $line")
                            io.github.gooseandroid.ui.doctor.LogCollector.addLine("[ERR] $line")
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Error reading goose stderr", e)
                }
            }

            // Wait for server with retry logic (#4)
            var ready = false
            for (attempt in 1..MAX_SERVER_RETRIES) {
                ready = waitForServer(port)
                if (ready) break
                Log.w(TAG, "waitForServer attempt $attempt/$MAX_SERVER_RETRIES timed out, retrying...")
            }

            if (ready) {
                GoosePortHolder.port = port
                Log.i(TAG, "Goose is ready on port $port")
                updateNotification("Goose is running on port $port")
            } else {
                Log.e(TAG, "Goose failed to become ready after $MAX_SERVER_RETRIES attempts")
                updateNotification("Goose failed to start")
                stopGoose()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start goose", e)
            updateNotification("Goose error: ${e.message}")
        }
    }

    /**
     * Start in local-only mode when the goose binary isn't available.
     * Launches the LiteRT inference server so the app can still function
     * with downloaded local models, or show a helpful setup screen.
     */
    private suspend fun startLocalOnlyMode() {
        val modelManager = LocalModelManager(this)
        val server = LiteRTInferenceServer(modelManager)
        liteRTServer = server                       // (#1) store reference for cleanup
        port = server.start()
        GoosePortHolder.port = port
        GoosePortHolder.localOnlyMode = true
        Log.i(TAG, "Local-only mode active on port $port")
        updateNotification("Goose (local mode) — download models or configure cloud API")
    }

    private fun extractBinary(): File? {
        // The binary is shipped as libgoose.so in jniLibs/arm64-v8a/
        // Android extracts it to the native library directory
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, "libgoose.so")

        if (!binary.exists()) {
            Log.e(TAG, "Binary not found at: ${binary.absolutePath}")
            return null
        }

        // Check if it's a real binary (not the placeholder from smoke test) (#5)
        if (binary.length() < MIN_BINARY_SIZE_BYTES) {
            Log.w(TAG, "Binary too small (${binary.length()} bytes) - likely placeholder")
            return null
        }

        if (!binary.canExecute()) {
            // Try to set executable permission
            val success = binary.setExecutable(true)
            if (!success) {
                Log.e(TAG, "Cannot set executable permission on: ${binary.absolutePath}")
                return null
            }
        }

        Log.i(TAG, "Found goose binary at: ${binary.absolutePath} (${binary.length()} bytes)")
        return binary
    }

    private fun buildEnvironment(homeDir: File): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // Set HOME to workspace so goose operates in the sandboxed filesystem
        val workspaceDir = File(filesDir, "workspace")
        workspaceDir.mkdirs()
        env["HOME"] = workspaceDir.absolutePath
        env["GOOSE_DISABLE_KEYRING"] = "true"
        env["GOOSE_SHELL"] = "/system/bin/sh"
        env["TMPDIR"] = cacheDir.absolutePath
        env["XDG_CONFIG_HOME"] = File(workspaceDir, ".config").apply { mkdirs() }.absolutePath
        env["XDG_DATA_HOME"] = File(workspaceDir, ".local/share").apply { mkdirs() }.absolutePath

        // PATH: runtime tools (busybox, git) + system binaries
        val runtimeManager = io.github.gooseandroid.runtime.RuntimeManager(this)
        val runtimeBin = runtimeManager.getRuntimePath()
        env["PATH"] = "$runtimeBin:/system/bin:/system/xbin"

        // Git config
        env["GIT_EXEC_PATH"] = runtimeBin
        env["GIT_TEMPLATE_DIR"] = File(workspaceDir, ".git-templates").apply { mkdirs() }.absolutePath

        return env
    }

    private suspend fun waitForServer(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket("127.0.0.1", port).use {
                    return true
                }
            } catch (e: Exception) {
                // Check if process died
                gooseProcess?.let { process ->
                    if (!process.isAlive) {
                        Log.e(TAG, "Goose process exited with code: ${process.exitValue()}")
                        return false
                    }
                }
                delay(CONNECT_RETRY_DELAY_MS)
            }
        }

        return false
    }

    private fun stopGoose() {
        gooseProcess?.let { process ->
            Log.i(TAG, "Stopping goose process")
            process.destroy()

            // Force-kill in a NonCancellable context so scope.cancel() won't prevent it (#2)
            scope.launch(NonCancellable) {
                delay(5000)
                if (process.isAlive) {
                    Log.w(TAG, "Force killing goose process")
                    process.destroyForcibly()
                }
            }
        }
        gooseProcess = null
    }

    /**
     * Shuts down the local LiteRT inference server if it is running. (#7)
     */
    private fun stopLocalServer() {
        liteRTServer?.let { server ->
            Log.i(TAG, "Stopping local LiteRT inference server")
            try {
                server.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping LiteRT server", e)
            }
        }
        liteRTServer = null
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Goose Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Goose AI agent running"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Goose")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)   // (#6) use proper app icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
