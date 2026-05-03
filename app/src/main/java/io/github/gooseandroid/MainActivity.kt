package io.github.gooseandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.gooseandroid.ui.GooseNavigation
import io.github.gooseandroid.ui.theme.GooseTheme

/**
 * Main entry point for the Goose Android app.
 *
 * This is intentionally simple — all engine initialization happens in
 * ChatViewModel via GooseEngineManager. No ForegroundService needed at startup.
 * The app launches instantly and the engine connects in the background.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GooseMain"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    // Shared content from other apps via ShareReceiverActivity
    private var sharedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        // Check for shared content from other apps
        sharedText = intent?.getStringExtra("shared_text")

        setContent {
            GooseTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                    ) {
                        GooseNavigation(sharedText = sharedText)
                    }
                }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("shared_text")?.let { text ->
            sharedText = text
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }
}
