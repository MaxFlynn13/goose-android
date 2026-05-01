package io.github.gooseandroid.ui.models

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
import io.github.gooseandroid.LocalModelManager
import io.github.gooseandroid.ModelFormat
import io.github.gooseandroid.ModelInfo
import io.github.gooseandroid.ModelStatus

/**
 * Model management screen.
 *
 * Allows users to:
 * - Browse available models optimized for their device
 * - Download models for offline use
 * - Delete downloaded models to free space
 * - See hardware compatibility info
 *
 * Models run via Google AI Edge LiteRT with hardware acceleration:
 * - Snapdragon 888: Hexagon 780 DSP (fastest) or Adreno 660 GPU
 * - Other Qualcomm: QNN delegate
 * - Other devices: GPU delegate or CPU fallback
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelManager = remember { LocalModelManager(context) }
    val models = remember { modelManager.getAvailableModels() }
    val usedStorage = remember { modelManager.getUsedStorageMb() }
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
                    modelManager = modelManager,
                    onDownload = { /* TODO: Download with progress */ },
                    onDelete = { modelManager.deleteModel(it.id) }
                )
            }

            // Section: All models
            item {
                Text(
                    "All available models",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            items(models.filter { !it.model.recommended }) { modelStatus ->
                ModelCard(
                    modelStatus = modelStatus,
                    modelManager = modelManager,
                    onDownload = { /* TODO */ },
                    onDelete = { modelManager.deleteModel(it.id) }
                )
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
    modelManager: LocalModelManager,
    onDownload: (ModelInfo) -> Unit,
    onDelete: (ModelInfo) -> Unit
) {
    val model = modelStatus.model
    val compatibility = remember { modelManager.canRunModel(model) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
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

                // Status badge
                if (modelStatus.downloaded) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Downloaded") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Model details row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DetailChip(
                    icon = Icons.Default.Storage,
                    text = formatSize(model.sizeBytes)
                )
                DetailChip(
                    icon = Icons.Default.Memory,
                    text = "${model.minRamMb}MB RAM"
                )
                DetailChip(
                    icon = Icons.Default.Speed,
                    text = when (model.format) {
                        ModelFormat.LITERT -> "LiteRT"
                        ModelFormat.GGUF -> "GGUF"
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action button
            if (!compatibility.canRun) {
                Text(
                    "⚠️ Insufficient RAM (need ${compatibility.requiredRamMb}MB, have ${compatibility.availableRamMb}MB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (modelStatus.downloaded) {
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
                    Button(onClick = { /* TODO: Set as active model */ }) {
                        Text("Use Model")
                    }
                }
            } else {
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
                "Local models run entirely on your device using Google AI Edge LiteRT. " +
                "No internet connection or API key required. " +
                "Models use hardware acceleration (GPU/DSP) for fast inference.\n\n" +
                "Smaller models (1-3B) are fast but less capable. " +
                "Larger models (7B+) are more capable but slower and use more RAM.",
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
