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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.gooseandroid.ui.GooseNavigation
import io.github.gooseandroid.ui.theme.GooseTheme

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

        val serviceIntent = Intent(this, GooseService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            GooseTheme {
                // Scaffold handles system bar insets properly
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when {
                            serviceError.value != null -> ErrorScreen(serviceError.value!!) { restartService() }
                            !serviceReady.value -> LoadingScreen()
                            else -> {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .consumeWindowInsets(innerPadding)
                                ) {
                                    GooseNavigation()
                                }
                            }
                        }
                    }
                }
            }
        }

        pollServiceReady()
    }

    private fun pollServiceReady() {
        Thread {
            val timeout = 15_000L
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeout) {
                if (GoosePortHolder.localOnlyMode) {
                    runOnUiThread { serviceReady.value = true }
                    return@Thread
                }
                val service = gooseService
                if (service != null && service.isRunning()) {
                    GoosePortHolder.port = service.getPort()
                    runOnUiThread { serviceReady.value = true }
                    return@Thread
                }
                Thread.sleep(200)
            }
            if (GoosePortHolder.localOnlyMode) {
                runOnUiThread { serviceReady.value = true }
            } else {
                runOnUiThread {
                    serviceError.value = "Goose backend not available.\nConfigure a cloud API key or download a local model in Settings."
                }
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Starting Goose...", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Initializing backend", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorScreen(error: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Setup Required", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
