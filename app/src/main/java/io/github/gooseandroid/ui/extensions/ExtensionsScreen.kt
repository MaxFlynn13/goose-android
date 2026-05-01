package io.github.gooseandroid.ui.extensions

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
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.launch

/**
 * Extensions management screen.
 * Toggle built-in extensions and configure MCP servers.
 */

data class ExtensionInfo(
    val id: String,
    val name: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val builtIn: Boolean = true
)

val AVAILABLE_EXTENSIONS = listOf(
    ExtensionInfo("developer", "Developer", "Shell commands, file editing, directory tree", Icons.Default.Terminal, true),
    ExtensionInfo("memory", "Memory", "Persistent preferences across sessions", Icons.Default.Psychology, true),
    ExtensionInfo("web_search", "Web Search", "Search the internet for current information", Icons.Default.Search, false),
    ExtensionInfo("github", "GitHub", "Interact with GitHub repos, issues, PRs", Icons.Default.Code, false),
    ExtensionInfo("browser", "Browser", "Navigate and interact with web pages", Icons.Default.Language, false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val devEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_DEVELOPER, true).collectAsState(initial = true)
    val memEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_MEMORY, false).collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Built-in Extensions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(AVAILABLE_EXTENSIONS.filter { it.builtIn }) { ext ->
                val isEnabled = when (ext.id) {
                    "developer" -> devEnabled
                    "memory" -> memEnabled
                    else -> false
                }
                ExtensionCard(
                    extension = ext,
                    enabled = isEnabled,
                    onToggle = { enabled ->
                        scope.launch {
                            when (ext.id) {
                                "developer" -> settingsStore.setBoolean(SettingsKeys.EXTENSION_DEVELOPER, enabled)
                                "memory" -> settingsStore.setBoolean(SettingsKeys.EXTENSION_MEMORY, enabled)
                            }
                        }
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "MCP Extensions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Connect to external MCP servers for additional capabilities. " +
                    "These require network access and may need API keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(AVAILABLE_EXTENSIONS.filter { !it.builtIn }) { ext ->
                ExtensionCard(
                    extension = ext,
                    enabled = false,
                    available = false,
                    onToggle = { /* TODO: MCP extension config */ }
                )
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: ExtensionInfo,
    enabled: Boolean,
    available: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                extension.icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(extension.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    extension.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (available) {
                Switch(checked = enabled, onCheckedChange = onToggle)
            } else {
                Text(
                    "Coming soon",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
