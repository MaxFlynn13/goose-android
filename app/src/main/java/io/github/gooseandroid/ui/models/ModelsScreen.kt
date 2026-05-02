package io.github.gooseandroid.ui.models

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import io.github.gooseandroid.*
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.launch

/**
 * Model management screen — download, manage, and select local LLMs.
 *
 * Models sourced from HuggingFace litert-community (same as Google AI Edge Gallery).
 * Downloads happen directly in-app with progress tracking and resume support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelManager = remember { LocalModelManager(context) }
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val downloads by modelManager.downloads.collectAsState()
    val activeModelId by settingsStore.getLocalModelId().collectAsState(initial = "")

    // Refresh model list when downloads complete
    val models = remember(downloads) { modelManager.getAvailableModels() }
    val usedStorage = remember(downloads) { modelManager.getUsedStorageMb() }
    val availableStorage = remember { modelManager.getAvailableStorageMb() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Storage info
            item {
                StorageCard(usedMb = usedStorage, availableMb = availableStorage)
            }

            // Hardware info
            item {
                HardwareInfoCard()
            }

            // Section: Recommended
            item {
                Text(
                    "Recommended for your device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(models.filter { it.model.recommended }) { modelStatus ->
                ModelCard(
                    modelStatus = modelStatus,
                    downloadState = downloads[modelStatus.model.id],
                    isActive = activeModelId == modelStatus.model.id,
                    modelManager = modelManager,
                    onDownload = { modelManager.downloadModel(it) },
                    onCancel = { modelManager.cancelDownload(it) },
                    onDelete = { modelManager.deleteModel(it.id) },
                    onSelect = {
                        scope.launch {
                            settingsStore.setLocalModelId(it.id)
                            Toast.makeText(context, "${it.name} selected", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // Section: More models
            val otherModels = models.filter { !it.model.recommended }
            if (otherModels.isNotEmpty()) {
                item {
                    Text(
                        "More models",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                items(otherModels) { modelStatus ->
                    ModelCard(
                        modelStatus = modelStatus,
                        downloadState = downloads[modelStatus.model.id],
                        isActive = activeModelId == modelStatus.model.id,
                        modelManager = modelManager,
                        onDownload = { modelManager.downloadModel(it) },
                        onCancel = { modelManager.cancelDownload(it) },
                        onDelete = { modelManager.deleteModel(it.id) },
                        onSelect = {
                            scope.launch {
                                settingsStore.setLocalModelId(it.id)
                                Toast.makeText(context, "${it.name} selected", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            // Info footer
            item {
                InfoFooter()
            }
        }
    }
}

@Composable
private fun StorageCard(usedMb: Long, availableMb: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Storage", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${usedMb}MB used / ${availableMb / 1024}GB free",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (availableMb > 0) usedMb.toFloat() / availableMb else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun HardwareInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    "Hardware Acceleration Available",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "LiteRT will use GPU/DSP for faster inference",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    modelStatus: ModelStatus,
    downloadState: DownloadState?,
    isActive: Boolean = false,
    modelManager: LocalModelManager,
    onDownload: (ModelInfo) -> Unit,
    onCancel: (ModelInfo) -> Unit,
    onDelete: (ModelInfo) -> Unit,
    onSelect: (ModelInfo) -> Unit = {}
) {
    val model = modelStatus.model
    val compatibility = remember { modelManager.canRunModel(model) }
    val isDownloaded = modelStatus.downloaded || downloadState is DownloadState.Complete

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isDownloaded) {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (isActive) "Active" else "Ready") },
                        leadingIcon = {
                            Icon(
                                if (isActive) Icons.Default.Star else Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Model specs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DetailChip(Icons.Default.Storage, formatSize(model.sizeBytes))
                DetailChip(Icons.Default.Memory, "${model.minRamMb}MB RAM")
                DetailChip(Icons.Default.Speed, "LiteRT")
            }

            // Warnings (advisory only)
            if (!compatibility.meetsRecommended) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Warning: Below recommended RAM (${compatibility.totalRamMb}MB / ${compatibility.requiredRamMb}MB rec.) — may be slow",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (!compatibility.hasEnoughStorage && !isDownloaded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Warning: Low storage — ${formatSize(model.sizeBytes)} needed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action area
            when {
                // Currently downloading
                downloadState is DownloadState.Downloading -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Downloading... ${(downloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { onCancel(model) }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Download error
                downloadState is DownloadState.Error -> {
                    Column {
                        Text(
                            "Download failed: ${downloadState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onDownload(model) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Download")
                        }
                    }
                }

                // Already downloaded
                isDownloaded -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onDelete(model) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                        Button(
                            onClick = { onSelect(model) },
                            enabled = !isActive
                        ) {
                            Text(if (isActive) "Active" else "Use Model")
                        }
                    }
                }

                // Not downloaded — show download button
                else -> {
                    Button(
                        onClick = { onDownload(model) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download (${formatSize(model.sizeBytes)})")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoFooter() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "About Local Models",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Models run entirely on your device using Google AI Edge LiteRT. " +
                "No internet connection or API key required after download.\n\n" +
                "Hardware acceleration (GPU/DSP) is used automatically for fast inference. " +
                "Models are sourced from HuggingFace (litert-community).\n\n" +
                "Smaller models (1-3B) are fast but less capable. " +
                "Larger models (4B+) are smarter but use more RAM and are slower.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1fGB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0fMB".format(bytes / 1_000_000.0)
        else -> "%.0fKB".format(bytes / 1_000.0)
    }
}
