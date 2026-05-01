package io.github.gooseandroid

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/**
 * Main activity hosting the Goose WebView UI.
 *
 * Binds to GooseService to get the local server port, then loads
 * the bundled React frontend which connects via WebSocket.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GooseMain"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    private lateinit var webView: WebView
    private var gooseService: GooseService? = null
    private var serviceBound = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GooseService.LocalBinder
            gooseService = binder.getService()
            serviceBound = true
            Log.i(TAG, "Bound to GooseService")

            // Wait for service to be ready, then load UI
            scope.launch {
                waitForServiceReady()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gooseService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Setup WebView
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false

            // Enable debugging in debug builds
            WebView.setWebContentsDebuggingEnabled(true)

            webViewClient = GooseWebViewClient()
            webChromeClient = GooseChromeClient()
        }

        // Add JavaScript bridge
        webView.addJavascriptInterface(GooseBridge(this), "AndroidBridge")

        setContentView(webView)

        // Start and bind to GooseService
        val serviceIntent = Intent(this, GooseService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Show loading state
        loadLoadingPage()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private suspend fun waitForServiceReady() {
        withContext(Dispatchers.IO) {
            val timeout = 35_000L
            val start = System.currentTimeMillis()

            while (System.currentTimeMillis() - start < timeout) {
                val service = gooseService
                if (service != null && service.isRunning()) {
                    val port = service.getPort()
                    Log.i(TAG, "Goose service ready on port $port")
                    withContext(Dispatchers.Main) {
                        loadFrontend(port)
                    }
                    return@withContext
                }
                delay(200)
            }

            withContext(Dispatchers.Main) {
                loadErrorPage("Goose failed to start within timeout")
            }
        }
    }

    private fun loadFrontend(port: Int) {
        Log.i(TAG, "Loading frontend with goose on port $port")

        // Inject the port into the frontend before loading
        val jsSetup = """
            window.GOOSE_PORT = $port;
            window.GOOSE_WS_URL = 'ws://127.0.0.1:$port/acp';
            window.GOOSE_PLATFORM = 'android';
        """.trimIndent()

        // Load the bundled frontend
        webView.loadUrl("file:///android_asset/web/index.html")

        // Inject config after page loads
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(jsSetup, null)
            }
        }
    }

    private fun loadLoadingPage() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        margin: 0;
                        background: #1a1a2e;
                        color: #e0e0e0;
                    }
                    .container {
                        text-align: center;
                        padding: 2rem;
                    }
                    .spinner {
                        width: 48px;
                        height: 48px;
                        border: 4px solid #333;
                        border-top: 4px solid #ff6b35;
                        border-radius: 50%;
                        animation: spin 1s linear infinite;
                        margin: 0 auto 1.5rem;
                    }
                    @keyframes spin {
                        to { transform: rotate(360deg); }
                    }
                    h2 { margin: 0 0 0.5rem; font-weight: 500; }
                    p { color: #888; font-size: 0.9rem; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="spinner"></div>
                    <h2>Starting Goose...</h2>
                    <p>Initializing the AI agent backend</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun loadErrorPage(error: String) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        margin: 0;
                        background: #1a1a2e;
                        color: #e0e0e0;
                    }
                    .container { text-align: center; padding: 2rem; }
                    .error-icon { font-size: 3rem; margin-bottom: 1rem; }
                    h2 { color: #ff4444; margin: 0 0 0.5rem; }
                    p { color: #888; font-size: 0.9rem; max-width: 300px; }
                    button {
                        margin-top: 1.5rem;
                        padding: 0.75rem 1.5rem;
                        background: #ff6b35;
                        color: white;
                        border: none;
                        border-radius: 8px;
                        font-size: 1rem;
                        cursor: pointer;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="error-icon">⚠️</div>
                    <h2>Failed to Start</h2>
                    <p>$error</p>
                    <button onclick="AndroidBridge.restartService()">Retry</button>
                </div>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    // Called from GooseBridge when service needs restart
    fun restartGooseService() {
        scope.launch {
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }

            val stopIntent = Intent(this@MainActivity, GooseService::class.java)
            stopService(stopIntent)

            delay(1000)

            loadLoadingPage()

            val startIntent = Intent(this@MainActivity, GooseService::class.java)
            startForegroundService(startIntent)
            bindService(startIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * WebView client that handles navigation within the app.
     */
    private inner class GooseWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false

            // Keep local/asset URLs in WebView
            if (url.startsWith("file://") || url.startsWith("http://127.0.0.1")) {
                return false
            }

            // Open external URLs in browser
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open external URL: $url", e)
            }
            return true
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(TAG, "WebView error: ${error?.description}")
            }
        }
    }

    /**
     * Chrome client for console logging and permission handling.
     */
    private inner class GooseChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage?.let {
                Log.d(TAG, "[WebView] ${it.messageLevel()}: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
            }
            return true
        }
    }
}
