package io.github.gooseandroid

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.gooseandroid.ui.chat.ChatScreen
import io.github.gooseandroid.ui.chat.ChatViewModel
import io.github.gooseandroid.ui.theme.GooseTheme
import kotlinx.coroutines.delay

/**
 * Main activity — native Jetpack Compose UI for Goose.
 *
 * No WebView. Direct Kotlin → WebSocket → goose serve.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GooseMain"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    private var gooseService: GooseService? = null
    private var serviceBound = false
    private var serviceReady = mutableStateOf(false)
    private var serviceError = mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GooseService.LocalBinder
            gooseService = binder.getService()
            serviceBound = true
            Log.i(TAG, "Bound to GooseService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gooseService = null
            serviceBound = false
            serviceReady.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()

        // Start goose service
        val serviceIntent = Intent(this, GooseService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            GooseTheme {
                GooseApp(
                    serviceReady = serviceReady.value,
                    serviceError = serviceError.value,
                    onRetry = { restartService() }
                )
            }
        }

        // Poll for service readiness
        pollServiceReady()
    }

    private fun pollServiceReady() {
        Thread {
            val timeout = 35_000L
            val start = System.currentTimeMillis()

            while (System.currentTimeMillis() - start < timeout) {
                val service = gooseService
                if (service != null && service.isRunning()) {
                    GoosePortHolder.port = service.getPort()
                    runOnUiThread { serviceReady.value = true }
                    return@Thread
                }
                Thread.sleep(200)
            }

            runOnUiThread {
                serviceError.value = "Goose failed to start within timeout"
            }
        }.start()
    }

    private fun restartService() {
        serviceError.value = null
        serviceReady.value = false

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        val stopIntent = Intent(this, GooseService::class.java)
        stopService(stopIntent)

        Thread {
            Thread.sleep(1000)
            runOnUiThread {
                val startIntent = Intent(this, GooseService::class.java)
                startForegroundService(startIntent)
                bindService(startIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                pollServiceReady()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
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
}

@Composable
fun GooseApp(
    serviceReady: Boolean,
    serviceError: String?,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when {
            serviceError != null -> ErrorScreen(serviceError, onRetry)
            !serviceReady -> LoadingScreen()
            else -> {
                val chatViewModel: ChatViewModel = viewModel()
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Starting Goose...",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Initializing the AI agent backend",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorScreen(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "⚠️",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Failed to Start",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
