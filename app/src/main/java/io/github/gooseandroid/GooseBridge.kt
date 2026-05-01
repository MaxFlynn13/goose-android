package io.github.gooseandroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject

/**
 * JavaScript interface exposed to the WebView as `window.AndroidBridge`.
 *
 * Provides native Android capabilities to the React frontend:
 * - Server URL discovery
 * - External link handling
 * - Clipboard access
 * - Notifications
 * - Settings persistence
 */
class GooseBridge(private val activity: MainActivity) {

    companion object {
        private const val TAG = "GooseBridge"
        private const val PREFS_NAME = "goose_settings"
    }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the WebSocket URL for the running goose server.
     * Called by the frontend to establish ACP connection.
     * The port is injected via window.GOOSE_WS_URL at page load,
     * but this method serves as a fallback for dynamic queries.
     */
    @JavascriptInterface
    fun getGooseServeUrl(): String {
        return "ws://127.0.0.1:${GoosePortHolder.port}/acp"
    }

    /**
     * Get the platform identifier.
     */
    @JavascriptInterface
    fun getPlatform(): String = "android"

    /**
     * Get device architecture.
     */
    @JavascriptInterface
    fun getArch(): String = System.getProperty("os.arch") ?: "aarch64"

    /**
     * Open a URL in the system browser.
     */
    @JavascriptInterface
    fun openExternal(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
        }
    }

    /**
     * Copy text to clipboard.
     */
    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean {
        return try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Goose", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
            false
        }
    }

    /**
     * Read text from clipboard.
     */
    @JavascriptInterface
    fun readClipboard(): String {
        return try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard", e)
            ""
        }
    }

    /**
     * Show a native toast notification.
     */
    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Persist a setting value.
     */
    @JavascriptInterface
    fun setSetting(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * Retrieve a persisted setting value.
     */
    @JavascriptInterface
    fun getSetting(key: String): String {
        return prefs.getString(key, "") ?: ""
    }

    /**
     * Get all settings as a JSON string.
     */
    @JavascriptInterface
    fun getAllSettings(): String {
        val json = JSONObject()
        prefs.all.forEach { (key, value) ->
            json.put(key, value?.toString() ?: "")
        }
        return json.toString()
    }

    /**
     * Restart the goose backend service.
     */
    @JavascriptInterface
    fun restartService() {
        activity.runOnUiThread {
            activity.restartGooseService()
        }
    }

    /**
     * Get the app version.
     */
    @JavascriptInterface
    fun getVersion(): String {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pInfo.versionName ?: "0.1.0"
        } catch (e: Exception) {
            "0.1.0"
        }
    }

    /**
     * Get the home directory path (for display purposes).
     */
    @JavascriptInterface
    fun getHomeDir(): String {
        return activity.filesDir.resolve("home").absolutePath
    }

    /**
     * Check if the goose service is currently running.
     */
    @JavascriptInterface
    fun isServiceRunning(): Boolean {
        // Simple check - try to connect to the port
        return try {
            java.net.Socket("127.0.0.1", 3284).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Share text content via Android share sheet.
     */
    @JavascriptInterface
    fun shareText(title: String, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share", e)
        }
    }

    /**
     * Log a message from the frontend (for debugging).
     */
    @JavascriptInterface
    fun logInfo(message: String) {
        Log.i(TAG, "[Frontend] $message")
    }

    /**
     * Log an error from the frontend.
     */
    @JavascriptInterface
    fun logError(message: String) {
        Log.e(TAG, "[Frontend] $message")
    }
}
