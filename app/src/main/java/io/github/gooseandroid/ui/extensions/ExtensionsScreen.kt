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
 * Matches Desktop Goose's extension system.
 *
 * Built-in extensions are toggled locally.
 * MCP extensions (stdio/http) can be added with config.
 */

data class ExtensionInfo(
    val id: String,
    val name: String,
    val description: String,
    val type: String, // "builtin", "stdio", "http"
    val settingsKey: String = "",
    val isBuiltIn: Boolean = true,
    val configurable: Boolean = false
)

val BUILT_IN_EXTENSIONS = listOf(
    ExtensionInfo(
        id = "developer",
        name = "Developer",
        description = "Shell commands, file editing, directory listing. Core development tools.",
        type = "builtin",
        settingsKey = SettingsKeys.EXTENSION_DEVELOPER,
        isBuiltIn = true
    ),
    ExtensionInfo(
        id = "memory",
        name = "Memory",
        description = "Persistent memory across sessions. Goose remembers what you tell it.",
        type = "builtin",
        settingsKey = SettingsKeys.EXTENSION_MEMORY,
        isBuiltIn = true
    )
)

val MCP_EXTENSIONS = listOf(
    ExtensionInfo(
        id = "web_search",
        name = "Web Search",
        description = "Search the web for current information",
        type = "http",
        isBuiltIn = false,
        configurable = true
    ),
    ExtensionInfo(
        id = "github",
        name = "GitHub",
        description = "Interact with GitHub repositories, issues, and pull requests",
        type = "stdio",
        isBuiltIn = false,
        configurable = true
    ),
    ExtensionInfo(
        id = "browser",
        name = "Browser",
        description = "Browse web pages and extract content",
        type = "http",
        isBuiltIn = false,
        configurable = true
    ),
    ExtensionInfo(
        id = "filesystem",
        name = "Filesystem",
        description = "Read and write files on the device",
        type = "stdio",
        isBuiltIn = false,
        configurable = true
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val devEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_DEVELOPER, true).collectAsState(initial = true)
    val memEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_MEMORY, true).collectAsState(initial = true)

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Add Extension")
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
            // Built-in section
            item {
                Text(
                    "Built-in",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                ExtensionCard(
                    ext = BUILT_IN_EXTENSIONS[0],
                    enabled = devEnabled,
                    onToggle = { scope.launch { settingsStore.setBoolean(SettingsKeys.EXTENSION_DEVELOPER, it) } }
                )
            }

            item {
                ExtensionCard(
                    ext = BUILT_IN_EXTENSIONS[1],
                    enabled = memEnabled,
                    onToggle = { scope.launch { settingsStore.setBoolean(SettingsKeys.EXTENSION_MEMORY, it) } }
                )
            }

            // MCP Extensions section
            item {
                Text(
                    "MCP Extensions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    "Connect to external tool servers via the Model Context Protocol.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(MCP_EXTENSIONS) { ext ->
                McpExtensionCard(ext = ext)
            }
        }
    }

    if (showAddDialog) {
        AddExtensionDialog(onDismiss = { showAddDialog = false })
    }
}

@Composable
private fun ExtensionCard(
    ext: ExtensionInfo,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Extension, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(ext.name, style = MaterialTheme.typography.titleSmall)
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Built-in", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp)
                    )
                }
                Text(
                    ext.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun McpExtensionCard(ext: ExtensionInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        when (ext.type) {
                            "http" -> Icons.Default.Cloud
                            else -> Icons.Default.Terminal
                        },
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(ext.name, style = MaterialTheme.typography.titleSmall)
                    SuggestionChip(
                        onClick = {},
                        label = { Text(ext.type.uppercase(), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp)
                    )
                }
                Text(
                    ext.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            FilledTonalButton(onClick = { /* TODO: configure */ }) {
                Text("Setup")
            }
        }
    }
}

@Composable
private fun AddExtensionDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("http") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP Extension") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == "http",
                        onClick = { type = "http" },
                        label = { Text("HTTP") }
                    )
                    FilterChip(
                        selected = type == "stdio",
                        onClick = { type = "stdio" },
                        label = { Text("Stdio") }
                    )
                }

                if (type == "http") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("https://mcp-server.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        placeholder = { Text("npx @mcp/server-name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { /* TODO: save extension config */ onDismiss() },
                enabled = name.isNotBlank() && (url.isNotBlank() || command.isNotBlank())
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
