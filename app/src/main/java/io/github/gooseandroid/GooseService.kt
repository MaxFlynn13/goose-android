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
import java.net.ServerSocket

/**
 * Foreground service that manages the goose binary lifecycle.
 *
 * Spawns `goose serve --host 127.0.0.1 --port PORT` and keeps it alive
 * as long as the app is in use. The frontend WebView connects to this
 * local server via WebSocket.
 */
class GooseService : Service() {

    companion object {
        private const val TAG = "GooseService"
        private const val CHANNEL_ID = "goose_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val CONNECT_RETRY_DELAY_MS = 100L
    }

    private var gooseProcess: Process? = null
    private var port: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        scope.cancel()
    }

    fun getPort(): Int = port

    fun isRunning(): Boolean = gooseProcess?.isAlive == true

    private suspend fun startGoose() {
        try {
            port = findFreePort()
            Log.i(TAG, "Starting goose on port $port")

            val binaryFile = extractBinary()
            if (binaryFile == null) {
                Log.e(TAG, "Failed to locate goose binary")
                updateNotification("Goose failed to start: binary not found")
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

            // Log stderr in background
            scope.launch {
                gooseProcess?.errorStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        Log.d(TAG, "[goose stderr] $line")
                    }
                }
            }

            // Wait for server to be ready
            val ready = waitForServer(port)
            if (ready) {
                Log.i(TAG, "Goose is ready on port $port")
                updateNotification("Goose is running on port $port")
            } else {
                Log.e(TAG, "Goose failed to become ready within timeout")
                updateNotification("Goose failed to start")
                stopGoose()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start goose", e)
            updateNotification("Goose error: ${e.message}")
        }
    }

    private fun extractBinary(): File? {
        // The binary is shipped as libgoose.so in jniLibs/arm64-v8a/
        // Android extracts it to the native library directory
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, "libgoose.so")

        if (binary.exists() && binary.canExecute()) {
            Log.i(TAG, "Found goose binary at: ${binary.absolutePath}")
            return binary
        }

        Log.e(TAG, "Binary not found or not executable at: ${binary.absolutePath}")
        return null
    }

    private fun buildEnvironment(homeDir: File): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["HOME"] = homeDir.absolutePath
        env["GOOSE_DISABLE_KEYRING"] = "true"
        env["GOOSE_SHELL"] = "/system/bin/sh"
        env["TMPDIR"] = cacheDir.absolutePath
        env["XDG_CONFIG_HOME"] = File(homeDir, ".config").absolutePath
        env["XDG_DATA_HOME"] = File(homeDir, ".local/share").absolutePath

        // PATH: include our bundled binaries + system
        val binDir = File(filesDir, "bin")
        binDir.mkdirs()
        env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"

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

            // Give it 5 seconds to shut down gracefully
            scope.launch {
                delay(5000)
                if (process.isAlive) {
                    Log.w(TAG, "Force killing goose process")
                    process.destroyForcibly()
                }
            }
        }
        gooseProcess = null
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
            .setSmallIcon(android.R.drawable.ic_menu_manage)
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
