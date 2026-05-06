package io.github.gooseandroid.ui.doctor

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

data class DoctorCheck(
    val id: String,
    val label: String,
    val status: CheckStatus,
    val detail: String = ""
)

enum class CheckStatus { PASS, WARN, FAIL, RUNNING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<DoctorCheck>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }

    fun runDiagnostics() {
        scope.launch {
            isRunning = true
            checks = listOf(
                DoctorCheck("goose_binary", "Goose Binary", CheckStatus.RUNNING),
                DoctorCheck("goose_server", "Goose Server", CheckStatus.RUNNING),
                DoctorCheck("internet", "Internet Connection", CheckStatus.RUNNING),
                DoctorCheck("storage", "Storage Space", CheckStatus.RUNNING),
                DoctorCheck("ram", "RAM Available", CheckStatus.RUNNING),
                DoctorCheck("provider", "Provider Configured", CheckStatus.RUNNING),
                DoctorCheck("local_models", "Local Models", CheckStatus.RUNNING),
                DoctorCheck("extensions", "Extensions", CheckStatus.RUNNING)
            )

            // Goose Binary
            val binaryCheck = withContext(Dispatchers.IO) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val libFile = File(nativeLibDir, "libgoose.so")
                if (libFile.exists() && libFile.canExecute()) {
                    DoctorCheck("goose_binary", "Goose Binary", CheckStatus.PASS, "libgoose.so found at $nativeLibDir")
                } else if (libFile.exists()) {
                    DoctorCheck("goose_binary", "Goose Binary", CheckStatus.WARN, "libgoose.so exists but not executable")
                } else {
                    DoctorCheck("goose_binary", "Goose Binary", CheckStatus.FAIL, "libgoose.so not found in $nativeLibDir")
                }
            }
            checks = checks.map { if (it.id == "goose_binary") binaryCheck else it }

            // Goose Engine — Kotlin native engine (always available)
            val serverCheck = DoctorCheck(
                "goose_server", "Goose Engine", CheckStatus.PASS,
                "Kotlin native engine active (no binary dependency)"
            )
            checks = checks.map { if (it.id == "goose_server") serverCheck else it }

            // Internet Connection — try google.com, then cloudflare as fallback
            val internetCheck = withContext(Dispatchers.IO) {
                fun tryConnect(urlString: String): Int? {
                    return try {
                        val url = URL(urlString)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.requestMethod = "HEAD"
                        val responseCode = connection.responseCode
                        connection.disconnect()
                        responseCode
                    } catch (e: Exception) {
                        null
                    }
                }

                val googleResponse = tryConnect("https://www.google.com")
                if (googleResponse != null && googleResponse in 200..399) {
                    DoctorCheck("internet", "Internet Connection", CheckStatus.PASS, "Connected (google.com HTTP $googleResponse)")
                } else {
                    // Fallback to Cloudflare
                    val cfResponse = tryConnect("https://1.1.1.1")
                    if (cfResponse != null && cfResponse in 200..399) {
                        DoctorCheck("internet", "Internet Connection", CheckStatus.PASS, "Connected (cloudflare HTTP $cfResponse)")
                    } else if (googleResponse != null) {
                        DoctorCheck("internet", "Internet Connection", CheckStatus.WARN, "Unexpected response: HTTP $googleResponse")
                    } else {
                        DoctorCheck("internet", "Internet Connection", CheckStatus.FAIL, "No internet: could not reach google.com or cloudflare.com")
                    }
                }
            }
            checks = checks.map { if (it.id == "internet") internetCheck else it }

            // Storage Space
            val storageCheck = withContext(Dispatchers.IO) {
                try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
                    val availableGB = availableBytes / (1024.0 * 1024.0 * 1024.0)
                    val formatted = String.format("%.1f GB available", availableGB)
                    when {
                        availableGB >= 1.0 -> DoctorCheck("storage", "Storage Space", CheckStatus.PASS, formatted)
                        availableGB >= 0.5 -> DoctorCheck("storage", "Storage Space", CheckStatus.WARN, "$formatted (low)")
                        else -> DoctorCheck("storage", "Storage Space", CheckStatus.FAIL, "$formatted (critically low)")
                    }
                } catch (e: Exception) {
                    DoctorCheck("storage", "Storage Space", CheckStatus.FAIL, "Unable to check: ${e.message}")
                }
            }
            checks = checks.map { if (it.id == "storage") storageCheck else it }

            // RAM Available
            val ramCheck = withContext(Dispatchers.IO) {
                try {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memInfo)
                    val availableMB = memInfo.availMem / (1024.0 * 1024.0)
                    val totalMB = memInfo.totalMem / (1024.0 * 1024.0)
                    val formatted = String.format("%.0f MB / %.0f MB", availableMB, totalMB)
                    when {
                        memInfo.lowMemory -> DoctorCheck("ram", "RAM Available", CheckStatus.FAIL, "$formatted (low memory)")
                        availableMB < 512 -> DoctorCheck("ram", "RAM Available", CheckStatus.WARN, "$formatted (limited)")
                        else -> DoctorCheck("ram", "RAM Available", CheckStatus.PASS, formatted)
                    }
                } catch (e: Exception) {
                    DoctorCheck("ram", "RAM Available", CheckStatus.FAIL, "Unable to check: ${e.message}")
                }
            }
            checks = checks.map { if (it.id == "ram") ramCheck else it }

            // Provider Configured — use SettingsStore to read API keys properly
            val providerCheck = withContext(Dispatchers.IO) {
                val configured = mutableListOf<String>()
                val settingsStore = SettingsStore(context)

                val keyChecks = listOf(
                    SettingsKeys.ANTHROPIC_API_KEY to "Anthropic",
                    SettingsKeys.OPENAI_API_KEY to "OpenAI",
                    SettingsKeys.GOOGLE_API_KEY to "Google",
                    SettingsKeys.MISTRAL_API_KEY to "Mistral",
                    SettingsKeys.OPENROUTER_API_KEY to "OpenRouter",
                    SettingsKeys.OLLAMA_BASE_URL to "Ollama"
                )

                for ((key, label) in keyChecks) {
                    val value = withTimeoutOrNull(2000) {
                        settingsStore.getString(key).first()
                    }
                    if (!value.isNullOrBlank()) {
                        configured.add(label)
                    }
                }

                // Also check environment variables
                if (!System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) configured.add("Anthropic (env)")
                if (!System.getenv("OPENAI_API_KEY").isNullOrBlank()) configured.add("OpenAI (env)")

                when {
                    configured.isNotEmpty() -> DoctorCheck("provider", "Provider Configured", CheckStatus.PASS, "Configured: ${configured.joinToString(", ")}")
                    else -> DoctorCheck("provider", "Provider Configured", CheckStatus.WARN, "No API keys configured. Set one in Settings.")
                }
            }
            checks = checks.map { if (it.id == "provider") providerCheck else it }

            // Local Models
            val modelsCheck = withContext(Dispatchers.IO) {
                val modelsDir = File(context.filesDir, "models")
                if (modelsDir.exists() && modelsDir.isDirectory) {
                    val models = modelsDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
                    if (models.isNotEmpty()) {
                        DoctorCheck("local_models", "Local Models", CheckStatus.PASS, "${models.size} model(s) downloaded")
                    } else {
                        DoctorCheck("local_models", "Local Models", CheckStatus.WARN, "Models directory exists but empty")
                    }
                } else {
                    DoctorCheck("local_models", "Local Models", CheckStatus.WARN, "No local models directory found")
                }
            }
            checks = checks.map { if (it.id == "local_models") modelsCheck else it }

            // Extensions — use SettingsStore
            val extensionsCheck = withContext(Dispatchers.IO) {
                val enabled = mutableListOf<String>()
                val settingsStore = SettingsStore(context)

                val devEnabled = withTimeoutOrNull(2000) {
                    settingsStore.getString(SettingsKeys.EXTENSION_DEVELOPER).first()
                }
                val memEnabled = withTimeoutOrNull(2000) {
                    settingsStore.getString(SettingsKeys.EXTENSION_MEMORY).first()
                }

                if (!devEnabled.isNullOrBlank()) enabled.add("Developer")
                if (!memEnabled.isNullOrBlank()) enabled.add("Memory")

                // Default: developer and memory are enabled
                if (enabled.isEmpty()) enabled.addAll(listOf("Developer (default)", "Memory (default)"))
                DoctorCheck("extensions", "Extensions", CheckStatus.PASS, "Active: ${enabled.joinToString(", ")}")
            }
            checks = checks.map { if (it.id == "extensions") extensionsCheck else it }

            isRunning = false
        }
    }

    // Auto-run on screen open
    LaunchedEffect(Unit) {
        runDiagnostics()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Doctor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "System Diagnostics",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { runDiagnostics() },
                        enabled = !isRunning
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Run Diagnostics")
                    }

                    OutlinedButton(
                        onClick = {
                            val report = buildReport(checks)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Goose Doctor Report", report)
                            clipboard.setPrimaryClip(clip)
                        },
                        enabled = checks.isNotEmpty() && !isRunning
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Report")
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(checks) { check ->
                CheckResultRow(check)
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                AboutSection()
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CheckResultRow(check: DoctorCheck) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (check.status) {
                CheckStatus.PASS -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Pass",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                CheckStatus.WARN -> Icon(
                    Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(24.dp)
                )
                CheckStatus.FAIL -> Icon(
                    Icons.Default.Cancel,
                    contentDescription = "Fail",
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(24.dp)
                )
                CheckStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = check.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (check.detail.isNotBlank()) {
                    Text(
                        text = check.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    Column {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        AboutRow("App version", "0.1.0")
        AboutRow("GitHub", "github.com/MaxFlynn13/goose-android")
        AboutRow("License", "Apache 2.0")
        AboutRow("Based on", "Goose by Block (github.com/block/goose)")
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun buildReport(checks: List<DoctorCheck>): String {
    val sb = StringBuilder()
    sb.appendLine("Goose Android - Doctor Report")
    sb.appendLine("=============================")
    sb.appendLine()
    for (check in checks) {
        val statusLabel = when (check.status) {
            CheckStatus.PASS -> "[PASS]"
            CheckStatus.WARN -> "[WARN]"
            CheckStatus.FAIL -> "[FAIL]"
            CheckStatus.RUNNING -> "[....]"
        }
        sb.appendLine("$statusLabel ${check.label}")
        if (check.detail.isNotBlank()) {
            sb.appendLine("       ${check.detail}")
        }
    }
    sb.appendLine()
    sb.appendLine("App version: 0.1.0")
    sb.appendLine("GitHub: github.com/MaxFlynn13/goose-android")
    sb.appendLine("License: Apache 2.0")
    sb.appendLine("Based on: Goose by Block (github.com/block/goose)")
    return sb.toString()
}
