package io.github.gooseandroid.ui.doctor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Singleton that collects log lines from the goose binary process.
 * GooseService calls [addLine] when reading from process stdout/stderr.
 */
object LogCollector {
    private const val MAX_LINES = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _isProcessRunning = MutableStateFlow(false)
    val isProcessRunning: StateFlow<Boolean> = _isProcessRunning.asStateFlow()

    fun addLine(line: String) {
        _lines.update { current ->
            val updated = current + line
            if (updated.size > MAX_LINES) updated.takeLast(MAX_LINES) else updated
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun setProcessRunning(running: Boolean) {
        _isProcessRunning.value = running
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by LogCollector.lines.collectAsState()
    val isRunning by LogCollector.isProcessRunning.collectAsState()
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(lines.size, autoScroll) {
        if (autoScroll && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Goose Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Auto-scroll toggle
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            Icons.Default.VerticalAlignBottom,
                            contentDescription = "Auto-scroll",
                            tint = if (autoScroll)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Copy All
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                        val text = lines.joinToString("\n")
                        clipboard.setPrimaryClip(ClipData.newPlainText("Goose Logs", text))
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Logs copied to clipboard")
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy All")
                    }
                    // Clear
                    IconButton(onClick = { LogCollector.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection status indicator
            ProcessStatusBar(isRunning = isRunning)

            // Log display
            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No logs yet. Start the Goose process to see output.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = logLineColor(line),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessStatusBar(isRunning: Boolean) {
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (isRunning) Color(0xFF4CAF50) else Color(0xFFE53935),
                        shape = MaterialTheme.shapes.small
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) "Goose process running" else "Goose process stopped",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun logLineColor(line: String): Color {
    return when {
        line.contains("ERROR", ignoreCase = false) ||
                line.contains("error", ignoreCase = false) -> Color(0xFFEF5350)
        line.contains("WARN", ignoreCase = false) ||
                line.contains("warn", ignoreCase = false) -> Color(0xFFFFA726)
        line.contains("INFO", ignoreCase = false) ||
                line.contains("info", ignoreCase = false) -> Color(0xFF66BB6A)
        else -> MaterialTheme.colorScheme.onSurface
    }
}
