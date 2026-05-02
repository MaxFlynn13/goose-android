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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File

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

// --- JSON persistence helpers for MCP extension configs ---

@Serializable
data class McpExtensionConfig(
    val id: String,
    val name: String,
    val type: String,
    val url: String = "",
    val command: String = ""
)

@Serializable
data class McpExtensionsFile(
    val extensions: List<McpExtensionConfig> = emptyList()
)

private val mcpJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private fun getMcpConfigFile(context: android.content.Context): File =
    File(context.filesDir, "mcp_extensions.json")

private fun loadMcpConfigs(context: android.content.Context): McpExtensionsFile {
    val file = getMcpConfigFile(context)
    return if (file.exists()) {
        try {
            mcpJson.decodeFromString<McpExtensionsFile>(file.readText())
        } catch (_: Exception) {
            McpExtensionsFile()
        }
    } else {
        McpExtensionsFile()
    }
}

private fun saveMcpConfigs(context: android.content.Context, configs: McpExtensionsFile) {
    val file = getMcpConfigFile(context)
    file.writeText(mcpJson.encodeToString(configs))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    // NOTE: Extension toggles save to DataStore for UI state only.
    // These are not yet communicated to GooseService — that integration
    // will be added once goose serve is wired up on Android.
    val devEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_DEVELOPER, true).collectAsState(initial = true)
    val memEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_MEMORY, true).collectAsState(initial = true)

    var showAddDialog by remember { mutableStateOf(false) }

    // Track user-added MCP extensions loaded from JSON config
    var userExtensions by remember { mutableStateOf<List<McpExtensionConfig>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            userExtensions = loadMcpConfigs(context).extensions
        }
    }

    // State for the MCP Setup dialog
    var setupTarget by remember { mutableStateOf<ExtensionInfo?>(null) }

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
                McpExtensionCard(
                    ext = ext,
                    onSetupClick = { setupTarget = ext }
                )
            }

            // User-added custom MCP extensions
            if (userExtensions.isNotEmpty()) {
                item {
                    Text(
                        "Custom MCP Extensions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }

                items(userExtensions, key = { it.id }) { cfg ->
                    val ext = ExtensionInfo(
                        id = cfg.id,
                        name = cfg.name,
                        description = if (cfg.type == "http") cfg.url else cfg.command,
                        type = cfg.type,
                        isBuiltIn = false,
                        configurable = true
                    )
                    McpExtensionCard(
                        ext = ext,
                        onSetupClick = { setupTarget = ext }
                    )
                }
            }
        }
    }

    // Setup dialog for pre-defined MCP extensions
    setupTarget?.let { ext ->
        McpSetupDialog(
            ext = ext,
            existingConfig = userExtensions.find { it.id == ext.id }
                ?: loadMcpConfigs(context).extensions.find { it.id == ext.id },
            onDismiss = { setupTarget = null },
            onSave = { url, command ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val current = loadMcpConfigs(context)
                        val newConfig = McpExtensionConfig(
                            id = ext.id,
                            name = ext.name,
                            type = ext.type,
                            url = url,
                            command = command
                        )
                        val updated = current.extensions.filter { it.id != ext.id } + newConfig
                        saveMcpConfigs(context, McpExtensionsFile(updated))
                        userExtensions = updated
                    }
                    setupTarget = null
                }
            }
        )
    }

    if (showAddDialog) {
        AddExtensionDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, type, url, command ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val current = loadMcpConfigs(context)
                        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                        val newConfig = McpExtensionConfig(
                            id = id,
                            name = name,
                            type = type,
                            url = url,
                            command = command
                        )
                        val updated = current.extensions + newConfig
                        saveMcpConfigs(context, McpExtensionsFile(updated))
                        userExtensions = updated
                    }
                    showAddDialog = false
                }
            }
        )
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
            // NOTE: Toggle saves to DataStore for UI state only.
            // Not yet communicated to GooseService until goose serve integration is complete.
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun McpExtensionCard(
    ext: ExtensionInfo,
    onSetupClick: () -> Unit
) {
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
            FilledTonalButton(onClick = onSetupClick) {
                Text("Setup")
            }
        }
    }
}

/**
 * Dialog shown when the user taps "Setup" on an MCP extension card.
 * Lets the user enter the server URL (for http) or command (for stdio),
 * then persists to mcp_extensions.json.
 */
@Composable
private fun McpSetupDialog(
    ext: ExtensionInfo,
    existingConfig: McpExtensionConfig?,
    onDismiss: () -> Unit,
    onSave: (url: String, command: String) -> Unit
) {
    var url by remember { mutableStateOf(existingConfig?.url ?: "") }
    var command by remember { mutableStateOf(existingConfig?.command ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure ${ext.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    ext.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ext.type == "http") {
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
                onClick = { onSave(url, command) },
                enabled = if (ext.type == "http") url.isNotBlank() else command.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddExtensionDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, type: String, url: String, command: String) -> Unit
) {
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
                onClick = { onAdd(name, type, url, command) },
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
