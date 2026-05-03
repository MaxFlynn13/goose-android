package io.github.gooseandroid.ui.runtime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gooseandroid.runtime.PackState
import io.github.gooseandroid.runtime.PackStatus
import io.github.gooseandroid.runtime.RuntimePack
import io.github.gooseandroid.runtime.RuntimePackManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimePacksScreen(
    packManager: RuntimePackManager,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val packs = remember { packManager.getAvailablePacks() }

    val packStates = packs.associate { pack ->
        pack.id to packManager.getPackState(pack.id).collectAsState()
    }

    val totalStorage = remember(packStates.values.map { it.value.status }) {
        formatBytes(packManager.getTotalStorageUsed())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Runtime Packs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                RuntimePacksHeader(totalStorage = totalStorage)
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(packs, key = { it.id }) { pack ->
                val state = packStates[pack.id]?.value
                    ?: PackState(packId = pack.id, status = PackStatus.NOT_INSTALLED)

                RuntimePackCard(
                    pack = pack,
                    state = state,
                    onInstall = {
                        scope.launch { packManager.installPack(pack.id) }
                    },
                    onUninstall = {
                        scope.launch { packManager.uninstallPack(pack.id) }
                    },
                    onCancel = {
                        scope.launch { packManager.cancelInstall(pack.id) }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                RuntimePacksFooter()
            }
        }
    }
}

@Composable
private fun RuntimePacksHeader(totalStorage: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Extend Goose's Capabilities",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Runtime packs add programming languages and tools that MCP extensions may need. " +
                        "Install only what you need — each pack downloads on-demand.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Storage used: $totalStorage",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun RuntimePackCard(
    pack: RuntimePack,
    state: PackState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state.status) {
                PackStatus.INSTALLED -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                PackStatus.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pack.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (state.status == PackStatus.INSTALLED) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Installed",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "v${pack.version} • ${pack.sizeDescription}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                PackActionButton(
                    state = state,
                    onInstall = onInstall,
                    onUninstall = { showDeleteConfirmation = true },
                    onCancel = onCancel
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = pack.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Provides: ${pack.binaries.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(
                visible = state.status == PackStatus.DOWNLOADING,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${(state.progress * 100).toInt()}% downloaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = state.status == PackStatus.ERROR,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.error ?: "Installation failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Uninstall ${pack.name}?") },
            text = {
                Text("This will remove the runtime pack and free up storage. " +
                        "MCP extensions that depend on it may stop working.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onUninstall()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Uninstall")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PackActionButton(
    state: PackState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit
) {
    when (state.status) {
        PackStatus.NOT_INSTALLED -> {
            FilledTonalButton(
                onClick = onInstall,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Install", style = MaterialTheme.typography.labelMedium)
            }
        }

        PackStatus.DOWNLOADING -> {
            OutlinedButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelMedium)
            }
        }

        PackStatus.INSTALLED -> {
            IconButton(onClick = onUninstall) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Uninstall",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }

        PackStatus.ERROR -> {
            FilledTonalButton(
                onClick = onInstall,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text("Retry", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RuntimePacksFooter() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "About Runtime Packs",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Node.js is needed for MCP servers that use npx (most community extensions)\n" +
                        "• Python is needed for Python-based MCP servers and scripts\n" +
                        "• Build Tools let you compile native modules (node-gyp, pip install with C deps)\n" +
                        "• Termux Bootstrap is a full Linux environment — install this if you want everything",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Packs are downloaded from official sources and extracted to your device. " +
                        "They don't require root access.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
