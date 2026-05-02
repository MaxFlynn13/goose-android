package io.github.gooseandroid

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
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
import androidx.lifecycle.lifecycleScope
import io.github.gooseandroid.ui.GooseNavigation
import io.github.gooseandroid.ui.theme.GooseTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GooseMain"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    private var gooseService: GooseService? = null
    private var serviceBound = false
    private val _serviceReady = MutableStateFlow(false)
    private val serviceReady: StateFlow<Boolean> = _serviceReady
    private val _serviceError = MutableStateFlow<String?>(null)
    private val serviceError: StateFlow<String?> = _serviceError

    private var pollingJob: Job? = null

    // Shared content from other apps via ShareReceiverActivity
    var sharedText: String? = null
        private set

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
            _serviceReady.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        // Check for shared content from other apps
        sharedText = intent?.getStringExtra("shared_text")

        val serviceIntent = Intent(this, GooseService::class.java)
        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.e(TAG, "Cannot start foreground service from background", e)
                _serviceError.value = "Cannot start service: app is in the background. Please reopen the app."
            } else {
                throw e
            }
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            GooseTheme {
                val ready by serviceReady.collectAsState()
                val error by serviceError.collectAsState()

                // Scaffold handles system bar insets properly
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when {
                            error != null -> ErrorScreen(error!!) { restartService() }
                            !ready -> LoadingScreen()
                            else -> {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .consumeWindowInsets(innerPadding)
                                ) {
                                    GooseNavigation(sharedText = sharedText)
                                }
                            }
                        }
                    }
                }
            }
        }

        pollServiceReady()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra("shared_text")?.let { text ->
            sharedText = text
        }
    }

    private fun pollServiceReady() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            val timeout = 15_000L
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeout) {
                if (GoosePortHolder.localOnlyMode) {
                    _serviceReady.value = true
                    return@launch
                }
                val service = gooseService
                if (service != null && service.isRunning()) {
                    GoosePortHolder.port = service.getPort()
                    _serviceReady.value = true
                    return@launch
                }
                delay(200)
            }
            if (GoosePortHolder.localOnlyMode) {
                _serviceReady.value = true
            } else {
                _serviceError.value = "Goose backend not available.\nConfigure a cloud API key or download a local model in Settings."
            }
        }
    }

    private fun restartService() {
        _serviceError.value = null
        _serviceReady.value = false
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        val stopIntent = Intent(this, GooseService::class.java)
        stopService(stopIntent)
        lifecycleScope.launch {
            delay(1000)
            val startIntent = Intent(this@MainActivity, GooseService::class.java)
            try {
                startForegroundService(startIntent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    Log.e(TAG, "Cannot start foreground service from background", e)
                    _serviceError.value = "Cannot start service: app is in the background. Please reopen the app."
                    return@launch
                } else {
                    throw e
                }
            }
            bindService(startIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            pollServiceReady()
        }
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        pollingJob = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
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
