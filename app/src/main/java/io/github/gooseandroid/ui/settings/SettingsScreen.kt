package io.github.gooseandroid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

// --- Data model for provider catalog (local to Settings, avoids name collision
//     with io.github.gooseandroid.data.models.ProviderInfo) ---

private data class SettingsProviderEntry(
    val id: String,
    val name: String,
    val description: String,
    val models: List<String>,
    val requiresApiKey: Boolean = true,
    val requiresBaseUrl: Boolean = false,
    val defaultBaseUrl: String = "",
    val requiresModelName: Boolean = false
)

private val providerCatalog = listOf(
    SettingsProviderEntry(
        id = "anthropic",
        name = "Anthropic",
        description = "Claude models with advanced reasoning and coding capabilities",
        models = listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022")
    ),
    SettingsProviderEntry(
        id = "openai",
        name = "OpenAI",
        description = "GPT-4o and reasoning models from OpenAI",
        models = listOf("gpt-4o", "gpt-4o-mini", "o3-mini")
    ),
    SettingsProviderEntry(
        id = "google",
        name = "Google Gemini",
        description = "Gemini multimodal models from Google DeepMind",
        models = listOf("gemini-2.0-flash", "gemini-2.5-pro-preview-06-05")
    ),
    SettingsProviderEntry(
        id = "mistral",
        name = "Mistral",
        description = "Open-weight and commercial models from Mistral AI",
        models = listOf("mistral-large-latest", "mistral-small-latest")
    ),
    SettingsProviderEntry(
        id = "openrouter",
        name = "OpenRouter",
        description = "Unified API gateway to multiple model providers",
        models = listOf(
            "anthropic/claude-sonnet-4-20250514",
            "openai/gpt-4o",
            "google/gemini-2.0-flash-001"
        )
    ),
    SettingsProviderEntry(
        id = "ollama",
        name = "Ollama",
        description = "Run open-source models locally via Ollama",
        models = emptyList(),
        requiresApiKey = false,
        requiresBaseUrl = true,
        defaultBaseUrl = "http://localhost:11434"
    ),
    SettingsProviderEntry(
        id = "custom",
        name = "Custom OpenAI-compatible",
        description = "Any provider with an OpenAI-compatible API endpoint",
        models = emptyList(),
        requiresApiKey = true,
        requiresBaseUrl = true,
        requiresModelName = true
    )
)

// --- Main Settings Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToModels: () -> Unit,

    onNavigateToExtensions: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    // Active provider and model
    val activeProvider by settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER, "anthropic")
        .collectAsState(initial = "anthropic")
    val activeModel by settingsStore.getString(SettingsKeys.ACTIVE_MODEL, "claude-sonnet-4-20250514")
        .collectAsState(initial = "claude-sonnet-4-20250514")

    // API keys
    val anthropicKey by settingsStore.getString(SettingsKeys.ANTHROPIC_API_KEY)
        .collectAsState(initial = "")
    val openaiKey by settingsStore.getString(SettingsKeys.OPENAI_API_KEY)
        .collectAsState(initial = "")
    val googleKey by settingsStore.getString(SettingsKeys.GOOGLE_API_KEY)
        .collectAsState(initial = "")
    val mistralKey by settingsStore.getString(SettingsKeys.MISTRAL_API_KEY)
        .collectAsState(initial = "")
    val openrouterKey by settingsStore.getString(SettingsKeys.OPENROUTER_API_KEY)
        .collectAsState(initial = "")

    // Ollama
    val ollamaBaseUrl by settingsStore.getString(SettingsKeys.OLLAMA_BASE_URL, "http://localhost:11434")
        .collectAsState(initial = "http://localhost:11434")

    // Custom provider
    val customUrl by settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_URL)
        .collectAsState(initial = "")
    val customKey by settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_KEY)
        .collectAsState(initial = "")
    val customModel by settingsStore.getString(SettingsKeys.CUSTOM_PROVIDER_MODEL)
        .collectAsState(initial = "")

    // Compaction
    val autoCompact by settingsStore.getBoolean(SettingsKeys.AUTO_COMPACT, false)
        .collectAsState(initial = false)

    // General
    val workingDirectory by settingsStore.getString(SettingsKeys.WORKING_DIRECTORY, "/data/data/io.github.gooseandroid/files")
        .collectAsState(initial = "/data/data/io.github.gooseandroid/files")
    val shellPath by settingsStore.getString(SettingsKeys.SHELL_PATH, "/system/bin/sh")
        .collectAsState(initial = "/system/bin/sh")

    // Track which provider card is expanded
    var expandedProviderId by remember { mutableStateOf<String?>(null) }
    var themeMode by remember { mutableStateOf("SYSTEM") }
    LaunchedEffect(Unit) {
        settingsStore.getString(SettingsKeys.THEME_MODE, "SYSTEM").collect { themeMode = it }
    }

    // Helper to get the stored key for a provider
    fun getKeyForProvider(providerId: String): String = when (providerId) {
        "anthropic" -> anthropicKey
        "openai" -> openaiKey
        "google" -> googleKey
        "mistral" -> mistralKey
        "openrouter" -> openrouterKey
        "custom" -> customKey
        else -> ""
    }

    fun isProviderConfigured(providerId: String): Boolean = when (providerId) {
        "anthropic" -> anthropicKey.isNotBlank()
        "openai" -> openaiKey.isNotBlank()
        "google" -> googleKey.isNotBlank()
        "mistral" -> mistralKey.isNotBlank()
        "openrouter" -> openrouterKey.isNotBlank()
        "ollama" -> ollamaBaseUrl.isNotBlank()
        "custom" -> customUrl.isNotBlank() && customModel.isNotBlank()
        else -> false
    }

    fun settingsKeyForProvider(providerId: String): String = when (providerId) {
        "anthropic" -> SettingsKeys.ANTHROPIC_API_KEY
        "openai" -> SettingsKeys.OPENAI_API_KEY
        "google" -> SettingsKeys.GOOGLE_API_KEY
        "mistral" -> SettingsKeys.MISTRAL_API_KEY
        "openrouter" -> SettingsKeys.OPENROUTER_API_KEY
        "custom" -> SettingsKeys.CUSTOM_PROVIDER_KEY
        else -> ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==================== PROVIDERS SECTION ====================
            item {
                SectionHeader(title = "Providers", icon = Icons.Filled.Cloud)
            }

            items(providerCatalog, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    isActive = activeProvider == provider.id,
                    isConfigured = isProviderConfigured(provider.id),
                    isExpanded = expandedProviderId == provider.id,
                    apiKey = getKeyForProvider(provider.id),
                    baseUrl = when (provider.id) {
                        "ollama" -> ollamaBaseUrl
                        "custom" -> customUrl
                        else -> ""
                    },
                    modelName = if (provider.id == "custom") customModel else "",
                    onToggleExpand = {
                        expandedProviderId = if (expandedProviderId == provider.id) null else provider.id
                    },
                    onSelectActive = {
                        scope.launch {
                            settingsStore.setString(SettingsKeys.ACTIVE_PROVIDER, provider.id)
                            // Set default model if provider has models
                            if (provider.models.isNotEmpty()) {
                                settingsStore.setString(SettingsKeys.ACTIVE_MODEL, provider.models.first())
                            }
                        }
                    },
                    settingsKey = settingsKeyForProvider(provider.id),
                    settingsStore = settingsStore,
                    onBaseUrlChange = { newUrl ->
                        scope.launch {
                            when (provider.id) {
                                "ollama" -> settingsStore.setString(SettingsKeys.OLLAMA_BASE_URL, newUrl)
                                "custom" -> settingsStore.setString(SettingsKeys.CUSTOM_PROVIDER_URL, newUrl)
                            }
                        }
                    },
                    onModelNameChange = { newModel ->
                        scope.launch {
                            settingsStore.setString(SettingsKeys.CUSTOM_PROVIDER_MODEL, newModel)
                        }
                    }
                )
            }

            // ==================== MODEL SELECTION SECTION ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "Model", icon = Icons.Filled.Memory)
            }

            item {
                ModelSelectionCard(
                    activeProvider = activeProvider,
                    activeModel = activeModel,
                    customModel = customModel,
                    onModelSelected = { model ->
                        scope.launch {
                            settingsStore.setString(SettingsKeys.ACTIVE_MODEL, model)
                        }
                    }
                )
            }

            // ==================== EXTENSIONS SECTION ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "Extensions", icon = Icons.Filled.Extension)
            }

            item {
                NavigationCard(
                    title = "Manage Extensions",
                    subtitle = "Configure built-in and custom extensions",
                    icon = Icons.Filled.Extension,
                    onClick = onNavigateToExtensions
                )
            }

            // ==================== LOCAL MODELS SECTION ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "Local Models", icon = Icons.Filled.PhoneAndroid)
            }

            item {
                NavigationCard(
                    title = "Manage Local Models",
                    subtitle = "Download and manage on-device models",
                    icon = Icons.Filled.Download,
                    onClick = onNavigateToModels
                )
            }

            // ==================== COMPACTION SECTION ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "Compaction", icon = Icons.Filled.Compress)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-compact conversations",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Automatically summarize long conversations to reduce token usage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoCompact,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsStore.setBoolean(SettingsKeys.AUTO_COMPACT, enabled)
                                }
                            }
                        )
                    }
                }
            }

            // ==================== GENERAL SECTION ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "General", icon = Icons.Filled.Settings)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Working directory
                        Column {
                            Text(
                                text = "Working Directory",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = workingDirectory,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        HorizontalDivider()

                        // Shell path
                        Column {
                            Text(
                                text = "Shell Path",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = shellPath,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ==================== APPEARANCE SECTION ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "Appearance", icon = Icons.Filled.Palette)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = themeMode == "SYSTEM",
                                onClick = { scope.launch { settingsStore.setString(SettingsKeys.THEME_MODE, "SYSTEM") }; themeMode = "SYSTEM" },
                                label = { Text("System") }
                            )
                            FilterChip(
                                selected = themeMode == "DARK",
                                onClick = { scope.launch { settingsStore.setString(SettingsKeys.THEME_MODE, "DARK") }; themeMode = "DARK" },
                                label = { Text("Dark") }
                            )
                            FilterChip(
                                selected = themeMode == "LIGHT",
                                onClick = { scope.launch { settingsStore.setString(SettingsKeys.THEME_MODE, "LIGHT") }; themeMode = "LIGHT" },
                                label = { Text("Light") }
                            )
                        }
                    }
                }
            }

            // ==================== ABOUT SECTION ====================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "About", icon = Icons.Filled.Info)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Goose Android",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Version 1.0.0",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Text(
                            text = "An open-source AI assistant for Android, powered by multiple LLM providers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "github.com/block/goose",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Apache License 2.0",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Bottom spacer for navigation bar clearance
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// --- Section Header ---

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// --- Provider Card ---

@OptIn(FlowPreview::class)
@Composable
private fun ProviderCard(
    provider: SettingsProviderEntry,
    isActive: Boolean,
    isConfigured: Boolean,
    isExpanded: Boolean,
    apiKey: String,
    baseUrl: String,
    modelName: String,
    onToggleExpand: () -> Unit,
    onSelectActive: () -> Unit,
    settingsKey: String,
    settingsStore: SettingsStore,
    onBaseUrlChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isActive) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Radio button for active selection
                RadioButton(
                    selected = isActive,
                    onClick = onSelectActive
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Provider info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = provider.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Status indicator
                if (isConfigured) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Configured",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Not configured",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Expand/collapse icon
                Icon(
                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(24.dp)
                )
            }

            // Expandable config fields
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider()

                    // Base URL field (for Ollama and Custom)
                    if (provider.requiresBaseUrl) {
                        var urlValue by remember(baseUrl) { mutableStateOf(baseUrl) }
                        OutlinedTextField(
                            value = urlValue,
                            onValueChange = { newVal ->
                                urlValue = newVal
                                onBaseUrlChange(newVal)
                            },
                            label = { Text("Base URL") },
                            placeholder = {
                                if (provider.defaultBaseUrl.isNotBlank()) {
                                    Text(provider.defaultBaseUrl)
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // API key field — debounced: saves 500ms after last keystroke
                    if (provider.requiresApiKey) {
                        var keyValue by remember(apiKey) { mutableStateOf(apiKey) }
                        var keyVisible by remember { mutableStateOf(false) }

                        // Debounce the API key save: only persist 500ms after the user
                        // stops typing, avoiding 40 writes for a 40-char key.
                        LaunchedEffect(Unit) {
                            snapshotFlow { keyValue }
                                .debounce(500L)
                                .collect { debouncedKey ->
                                    settingsStore.setString(settingsKey, debouncedKey)
                                }
                        }

                        OutlinedTextField(
                            value = keyValue,
                            onValueChange = { newVal ->
                                keyValue = newVal
                            },
                            label = { Text("API Key") },
                            placeholder = { Text("Enter your API key") },
                            singleLine = true,
                            visualTransformation = if (keyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        if (keyVisible) Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                        contentDescription = if (keyVisible) "Hide" else "Show"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Model name field (for Custom provider)
                    if (provider.requiresModelName) {
                        var modelValue by remember(modelName) { mutableStateOf(modelName) }
                        OutlinedTextField(
                            value = modelValue,
                            onValueChange = { newVal ->
                                modelValue = newVal
                                onModelNameChange(newVal)
                            },
                            label = { Text("Model Name") },
                            placeholder = { Text("e.g. gpt-4o") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Status text
                    Text(
                        text = if (isConfigured) "Status: Configured" else "Status: Not configured",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }
    }
}

// --- Model Selection Card ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionCard(
    activeProvider: String,
    activeModel: String,
    customModel: String,
    onModelSelected: (String) -> Unit
) {
    val provider = providerCatalog.find { it.id == activeProvider }
    val availableModels = when {
        provider == null -> emptyList()
        provider.id == "custom" -> if (customModel.isNotBlank()) listOf(customModel) else emptyList()
        provider.id == "ollama" -> emptyList()
        else -> provider.models
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Active Model",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (availableModels.isEmpty()) {
                Text(
                    text = when (activeProvider) {
                        "ollama" -> "Models are managed via the Ollama server. Use the Local Models screen to configure."
                        "custom" -> "Enter a model name in the Custom provider configuration above."
                        else -> "No models available for this provider."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = activeModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onModelSelected(model)
                                    expanded = false
                                },
                                leadingIcon = {
                                    if (model == activeModel) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Selected",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Show current selection info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Provider: ${provider?.name ?: activeProvider}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// --- Navigation Card ---

@Composable
private fun NavigationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Navigate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
