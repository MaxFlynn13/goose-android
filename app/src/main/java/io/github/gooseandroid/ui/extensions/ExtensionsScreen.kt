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
import androidx.compose.ui.graphics.Color
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
 * Pre-configured extensions can be enabled with one tap.
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

// --- Pre-configured MCP extensions that users can enable with one tap ---

@Serializable
data class McpExtensionConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val transport: String = "stdio",
    val type: String = transport, // alias for backward compat
    val url: String = "",
    val command: String = "",
    val envVars: Map<String, String> = emptyMap(),
    val args: List<String> = emptyList(),
    val requiresRuntime: String = "",
    val enabled: Boolean = false
)

val PRECONFIGURED_EXTENSIONS = listOf(
    McpExtensionConfig(
        id = "github",
        name = "GitHub",
        description = "Create repos, manage issues, PRs, and code search",
        transport = "stdio",
        command = "npx -y @modelcontextprotocol/server-github",
        envVars = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to ""),
        requiresRuntime = "nodejs"
    ),
    McpExtensionConfig(
        id = "filesystem",
        name = "Filesystem",
        description = "Read, write, and manage files in the workspace",
        transport = "stdio",
        command = "npx -y @modelcontextprotocol/server-filesystem /workspace",
        envVars = emptyMap(),
        requiresRuntime = "nodejs"
    ),
    McpExtensionConfig(
        id = "web-search",
        name = "Web Search",
        description = "Search the web using Brave Search API",
        transport = "stdio",
        command = "npx -y @nicoulaj/mcp-server-brave-search",
        envVars = mapOf("BRAVE_API_KEY" to ""),
        requiresRuntime = "nodejs"
    ),
    McpExtensionConfig(
        id = "memory",
        name = "Memory",
        description = "Persistent memory for the AI assistant",
        transport = "stdio",
        command = "npx -y @modelcontextprotocol/server-memory",
        envVars = emptyMap(),
        requiresRuntime = "nodejs"
    ),
    McpExtensionConfig(
        id = "fetch",
        name = "Web Fetch",
        description = "Fetch and read web pages, convert HTML to markdown",
        transport = "stdio",
        command = "npx -y @modelcontextprotocol/server-fetch",
        envVars = emptyMap(),
        requiresRuntime = "nodejs"
    )
)

// --- Extension connection status ---

enum class ExtensionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

// --- JSON persistence helpers for MCP extension configs ---

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

    // State for pre-configured extension setup
    var preconfiguredSetupTarget by remember { mutableStateOf<McpExtensionConfig?>(null) }

    // Track connection status for pre-configured extensions
    val extensionStatuses = remember { mutableStateMapOf<String, ExtensionStatus>() }

    // Initialize statuses from saved configs
    LaunchedEffect(userExtensions) {
        for (ext in userExtensions) {
            if (ext.enabled && !extensionStatuses.containsKey(ext.id)) {
                extensionStatuses[ext.id] = ExtensionStatus.CONNECTED
            }
        }
    }

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

            // Pre-configured MCP Extensions section
            item {
                Text(
                    "Quick Setup Extensions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    "One-tap MCP extensions. Requires Node.js runtime to be installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(PRECONFIGURED_EXTENSIONS, key = { it.id }) { preconfig ->
                val savedConfig = userExtensions.find { it.id == preconfig.id }
                val isEnabled = savedConfig?.enabled == true
                val status = extensionStatuses[preconfig.id] ?: ExtensionStatus.DISCONNECTED

                PreconfiguredExtensionCard(
                    config = preconfig,
                    isEnabled = isEnabled,
                    status = status,
                    onEnableClick = {
                        if (preconfig.envVars.any { it.value.isBlank() }) {
                            // Needs API key configuration — show setup dialog
                            preconfiguredSetupTarget = preconfig
                        } else {
                            // No env vars needed — enable directly
                            scope.launch {
                                extensionStatuses[preconfig.id] = ExtensionStatus.CONNECTING
                                withContext(Dispatchers.IO) {
                                    val current = loadMcpConfigs(context)
                                    val newConfig = preconfig.copy(enabled = true, type = preconfig.transport)
                                    val updated = current.extensions.filter { it.id != preconfig.id } + newConfig
                                    saveMcpConfigs(context, McpExtensionsFile(updated))
                                    userExtensions = updated
                                }
                                extensionStatuses[preconfig.id] = ExtensionStatus.CONNECTED
                            }
                        }
                    },
                    onDisableClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val current = loadMcpConfigs(context)
                                val updated = current.extensions.map {
                                    if (it.id == preconfig.id) it.copy(enabled = false) else it
                                }
                                saveMcpConfigs(context, McpExtensionsFile(updated))
                                userExtensions = updated
                            }
                            extensionStatuses[preconfig.id] = ExtensionStatus.DISCONNECTED
                        }
                    },
                    onConfigureClick = {
                        preconfiguredSetupTarget = preconfig
                    }
                )
            }

            // MCP Extensions section (legacy cards)
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
                    onSetupClick = { setupTarget = ext },
                    onRemove = null
                )
            }

            // User-added custom MCP extensions
            if (userExtensions.any { cfg -> PRECONFIGURED_EXTENSIONS.none { it.id == cfg.id } }) {
                item {
                    Text(
                        "Custom MCP Extensions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }

                val customExtensions = userExtensions.filter { cfg ->
                    PRECONFIGURED_EXTENSIONS.none { it.id == cfg.id }
                }

                items(customExtensions, key = { it.id }) { cfg ->
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
                        onSetupClick = { setupTarget = ext },
                        onRemove = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val current = loadMcpConfigs(context)
                                    val updated = current.extensions.filter { it.id != cfg.id }
                                    saveMcpConfigs(context, McpExtensionsFile(updated))
                                    userExtensions = updated
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Pre-configured extension setup dialog (for API keys)
    preconfiguredSetupTarget?.let { preconfig ->
        PreconfiguredSetupDialog(
            config = preconfig,
            existingConfig = userExtensions.find { it.id == preconfig.id },
            onDismiss = { preconfiguredSetupTarget = null },
            onSave = { envVars ->
                scope.launch {
                    extensionStatuses[preconfig.id] = ExtensionStatus.CONNECTING
                    withContext(Dispatchers.IO) {
                        val current = loadMcpConfigs(context)
                        val newConfig = preconfig.copy(
                            enabled = true,
                            type = preconfig.transport,
                            envVars = envVars
                        )
                        val updated = current.extensions.filter { it.id != preconfig.id } + newConfig
                        saveMcpConfigs(context, McpExtensionsFile(updated))
                        userExtensions = updated
                    }
                    extensionStatuses[preconfig.id] = ExtensionStatus.CONNECTED
                    preconfiguredSetupTarget = null
                }
            }
        )
    }

    // Setup dialog for pre-defined MCP extensions
    setupTarget?.let { ext ->
        McpSetupDialog(
            ext = ext,
            existingConfig = userExtensions.find { it.id == ext.id },
            onDismiss = { setupTarget = null },
            onSave = { url, command, envVars, args ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val current = loadMcpConfigs(context)
                        val newConfig = McpExtensionConfig(
                            id = ext.id,
                            name = ext.name,
                            type = ext.type,
                            url = url,
                            command = command,
                            envVars = envVars,
                            args = args,
                            enabled = true
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
            onAdd = { name, type, url, command, envVars, args ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val current = loadMcpConfigs(context)
                        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                        val newConfig = McpExtensionConfig(
                            id = id,
                            name = name,
                            type = type,
                            url = url,
                            command = command,
                            envVars = envVars,
                            args = args,
                            enabled = true
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

// ─── Pre-configured Extension Card ─────────────────────────────────────────

@Composable
private fun PreconfiguredExtensionCard(
    config: McpExtensionConfig,
    isEnabled: Boolean,
    status: ExtensionStatus,
    onEnableClick: () -> Unit,
    onDisableClick: () -> Unit,
    onConfigureClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Terminal,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(config.name, style = MaterialTheme.typography.titleSmall)

                        // Status indicator
                        StatusDot(status)
                    }

                    Text(
                        config.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    // Badges row
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (config.requiresRuntime.isNotBlank()) {
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "Requires ${config.requiresRuntime.replaceFirstChar { it.uppercase() }}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(24.dp),
                                icon = {
                                    Icon(
                                        Icons.Default.Memory,
                                        null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    config.transport.uppercase(),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }

                // Action buttons
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isEnabled) {
                        FilledTonalButton(
                            onClick = onDisableClick,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text("Disable")
                        }
                        if (config.envVars.isNotEmpty()) {
                            IconButton(onClick = onConfigureClick, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Settings, "Configure", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        Button(onClick = onEnableClick) {
                            Text("Enable")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: ExtensionStatus) {
    val color = when (status) {
        ExtensionStatus.CONNECTED -> Color(0xFF4CAF50) // Green
        ExtensionStatus.CONNECTING -> Color(0xFFFFC107) // Amber
        ExtensionStatus.ERROR -> Color(0xFFF44336) // Red
        ExtensionStatus.DISCONNECTED -> Color(0xFF9E9E9E) // Grey
    }
    val label = when (status) {
        ExtensionStatus.CONNECTED -> "Connected"
        ExtensionStatus.CONNECTING -> "Connecting..."
        ExtensionStatus.ERROR -> "Error"
        ExtensionStatus.DISCONNECTED -> "Disabled"
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = MaterialTheme.shapes.small,
            color = color
        ) {}
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Pre-configured Setup Dialog (for env vars / API keys) ──────────────────

@Composable
private fun PreconfiguredSetupDialog(
    config: McpExtensionConfig,
    existingConfig: McpExtensionConfig?,
    onDismiss: () -> Unit,
    onSave: (envVars: Map<String, String>) -> Unit
) {
    // Initialize env var fields from existing config or defaults
    val envVarStates = remember {
        config.envVars.map { (key, defaultValue) ->
            val existingValue = existingConfig?.envVars?.get(key) ?: defaultValue
            key to mutableStateOf(existingValue)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure ${config.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    config.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Command: ${config.command}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (envVarStates.isNotEmpty()) {
                    Text(
                        "Environment Variables",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    for ((key, state) in envVarStates) {
                        OutlinedTextField(
                            value = state.value,
                            onValueChange = { state.value = it },
                            label = { Text(key) },
                            placeholder = { Text("Enter value for $key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val envVars = envVarStates.associate { (key, state) -> key to state.value }
                    onSave(envVars)
                },
                enabled = envVarStates.all { (_, state) -> state.value.isNotBlank() }
            ) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─── Existing cards (unchanged) ─────────────────────────────────────────────

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
private fun McpExtensionCard(
    ext: ExtensionInfo,
    onSetupClick: () -> Unit,
    onRemove: (() -> Unit)? = null
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onSetupClick) {
                    Text("Setup")
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove extension",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog shown when the user taps "Setup" on an MCP extension card.
 */
@Composable
private fun McpSetupDialog(
    ext: ExtensionInfo,
    existingConfig: McpExtensionConfig?,
    onDismiss: () -> Unit,
    onSave: (url: String, command: String, envVars: Map<String, String>, args: List<String>) -> Unit
) {
    var url by remember { mutableStateOf(existingConfig?.url ?: "") }
    var command by remember { mutableStateOf(existingConfig?.command ?: "") }
    var envVarsText by remember {
        mutableStateOf(
            existingConfig?.envVars?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: ""
        )
    }
    var argsText by remember {
        mutableStateOf(existingConfig?.args?.joinToString("\n") ?: "")
    }

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
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Arguments (one per line)") },
                        placeholder = { Text("--port\n8080") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = envVarsText,
                    onValueChange = { envVarsText = it },
                    label = { Text("Environment Variables (KEY=VALUE, one per line)") },
                    placeholder = { Text("API_KEY=sk-xxx\nDEBUG=true") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val envVars = envVarsText.lines()
                        .filter { it.contains("=") }
                        .associate { line ->
                            val idx = line.indexOf("=")
                            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                        }
                    val args = argsText.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    onSave(url, command, envVars, args)
                },
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
    onAdd: (name: String, type: String, url: String, command: String, envVars: Map<String, String>, args: List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("http") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var envVarsText by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }

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
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Arguments (one per line)") },
                        placeholder = { Text("--port\n8080") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = envVarsText,
                    onValueChange = { envVarsText = it },
                    label = { Text("Environment Variables (KEY=VALUE, one per line)") },
                    placeholder = { Text("API_KEY=sk-xxx\nDEBUG=true") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val envVars = envVarsText.lines()
                        .filter { it.contains("=") }
                        .associate { line ->
                            val idx = line.indexOf("=")
                            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                        }
                    val args = argsText.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    onAdd(name, type, url, command, envVars, args)
                },
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
