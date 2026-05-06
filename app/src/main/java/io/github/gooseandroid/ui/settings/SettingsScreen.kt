package io.github.gooseandroid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.data.models.ModelOption
import androidx.compose.material3.Slider
import androidx.compose.ui.unit.sp
import io.github.gooseandroid.data.models.PROVIDER_CATALOG
import io.github.gooseandroid.data.models.ProviderInfo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

// ==================== Main Settings Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToExtensions: () -> Unit = {},
    onNavigateToRuntimes: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToDoctor: () -> Unit = {},
    onNavigateToConfigureProvider: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    // Active provider and model for display
    val activeProvider by settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER, "anthropic")
        .collectAsState(initial = "anthropic")
    val activeModel by settingsStore.getString(SettingsKeys.ACTIVE_MODEL, "claude-sonnet-4-20250514")
        .collectAsState(initial = "claude-sonnet-4-20250514")

    // Compaction
    val autoCompact by settingsStore.getBoolean(SettingsKeys.AUTO_COMPACT, false)
        .collectAsState(initial = false)

    // General
    val workingDirectory by settingsStore.getString(SettingsKeys.WORKING_DIRECTORY, "/data/data/io.github.gooseandroid/files")
        .collectAsState(initial = "/data/data/io.github.gooseandroid/files")
    val shellPath by settingsStore.getString(SettingsKeys.SHELL_PATH, "/system/bin/sh")
        .collectAsState(initial = "/system/bin/sh")

    // Theme
    var themeMode by remember { mutableStateOf("SYSTEM") }
    LaunchedEffect(Unit) {
        settingsStore.getString(SettingsKeys.THEME_MODE, "SYSTEM").collect { themeMode = it }
    }

    // Resolve display names
    val providerDisplay = PROVIDER_CATALOG.find { it.id == activeProvider }?.displayName ?: activeProvider
    val modelDisplay = PROVIDER_CATALOG.find { it.id == activeProvider }
        ?.models?.find { it.id == activeModel }?.displayName ?: activeModel

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==================== CONFIGURE PROVIDER ====================
            item {
                NavigationCard(
                    title = "Configure Provider",
                    subtitle = "$providerDisplay · $modelDisplay",
                    icon = Icons.Filled.Cloud,
                    onClick = onNavigateToConfigureProvider
                )
            }

            // ==================== EXTENSIONS & RUNTIMES ====================
            item { SettingsDivider() }

            item {
                NavigationCard(
                    title = "Extensions",
                    subtitle = "Configure built-in and custom extensions",
                    icon = Icons.Filled.Extension,
                    onClick = onNavigateToExtensions
                )
            }

            item {
                NavigationCard(
                    title = "Runtime Packs",
                    subtitle = "Manage language runtimes and toolchains",
                    icon = Icons.Filled.Build,
                    onClick = onNavigateToRuntimes
                )
            }

            // ==================== APPEARANCE ====================
            item { SettingsDivider() }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = themeMode == "SYSTEM",
                                onClick = {
                                    scope.launch { settingsStore.setString(SettingsKeys.THEME_MODE, "SYSTEM") }
                                    themeMode = "SYSTEM"
                                },
                                label = { Text("System") }
                            )
                            FilterChip(
                                selected = themeMode == "DARK",
                                onClick = {
                                    scope.launch { settingsStore.setString(SettingsKeys.THEME_MODE, "DARK") }
                                    themeMode = "DARK"
                                },
                                label = { Text("Dark") }
                            )
                            FilterChip(
                                selected = themeMode == "LIGHT",
                                onClick = {
                                    scope.launch { settingsStore.setString(SettingsKeys.THEME_MODE, "LIGHT") }
                                    themeMode = "LIGHT"
                                },
                                label = { Text("Light") }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Accent Color", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        AccentColorRow(settingsStore = settingsStore, scope = scope)

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Text Size", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        FontScaleSlider(settingsStore = settingsStore, scope = scope)
                    }
                }
            }

            // ==================== DIAGNOSTICS & LOGS ====================
            item { SettingsDivider() }

            item {
                NavigationCard(
                    title = "Diagnostics",
                    subtitle = "Run health checks and view system status",
                    icon = Icons.Filled.HealthAndSafety,
                    onClick = onNavigateToDoctor
                )
            }

            item {
                NavigationCard(
                    title = "Logs",
                    subtitle = "View application and session logs",
                    icon = Icons.Filled.Terminal,
                    onClick = onNavigateToLogs
                )
            }

            // ==================== COMPACTION ====================
            item { SettingsDivider() }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
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
                                text = "Summarize long conversations to reduce token usage",
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

            // ==================== GENERAL ====================
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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

            // ==================== ABOUT ====================
            item { SettingsDivider() }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
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

// ==================== Configure Provider Screen ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureProviderScreen(onBack: () -> Unit, onNavigateToModels: () -> Unit = {}) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(pageCount = { 2 })

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

    // Track which provider card is expanded
    var expandedProviderId by remember { mutableStateOf<String?>(null) }

    // Split catalog into cloud and local
    val cloudProviders = PROVIDER_CATALOG.filter { it.id != "ollama" }
    val localProviders = PROVIDER_CATALOG.filter { it.id == "ollama" }

    fun getKeyForProvider(providerId: String): String {
        return when (providerId) {
            "anthropic" -> anthropicKey
            "openai" -> openaiKey
            "google" -> googleKey
            "mistral" -> mistralKey
            "openrouter" -> openrouterKey
            "custom" -> customKey
            else -> ""
        }
    }

    fun isProviderConfigured(providerId: String): Boolean {
        val providerInfo = PROVIDER_CATALOG.find { it.id == providerId } ?: return false
        return when {
            providerInfo.id == "ollama" -> ollamaBaseUrl.isNotBlank()
            providerInfo.id == "custom" -> customUrl.isNotBlank() && customModel.isNotBlank()
            providerInfo.requiresApiKey -> getKeyForProvider(providerId).isNotBlank()
            else -> true
        }
    }

    fun settingsKeyForProvider(providerId: String): String {
        val providerInfo = PROVIDER_CATALOG.find { it.id == providerId } ?: return ""
        return providerInfo.apiKeySettingsKey
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure Provider") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Tab row
            TabRow(
                selectedTabIndex = pagerState.currentPage
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("Cloud API") },
                    icon = { Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("Local Models") },
                    icon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> {
                        // Cloud API tab
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Model selection at the top
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

                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                SectionHeader(title = "Providers", icon = Icons.Filled.Cloud)
                            }

                            items(cloudProviders, key = { it.id }) { provider ->
                                ProviderCard(
                                    provider = provider,
                                    isActive = activeProvider == provider.id,
                                    isConfigured = isProviderConfigured(provider.id),
                                    isExpanded = expandedProviderId == provider.id,
                                    apiKey = getKeyForProvider(provider.id),
                                    baseUrl = when (provider.id) {
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
                                            if (provider.models.isNotEmpty()) {
                                                settingsStore.setString(SettingsKeys.ACTIVE_MODEL, provider.models.first().id)
                                            }
                                        }
                                    },
                                    settingsKey = settingsKeyForProvider(provider.id),
                                    settingsStore = settingsStore,
                                    onBaseUrlChange = { newUrl ->
                                        scope.launch {
                                            when (provider.id) {
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

                            // Codex OAuth
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                CodexOAuthCard(settingsStore = settingsStore, scope = scope)
                            }

                            item { Spacer(modifier = Modifier.height(32.dp)) }
                        }
                    }
                    1 -> {
                        // Local Models tab
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                SectionHeader(title = "Ollama", icon = Icons.Filled.PhoneAndroid)
                            }

                            items(localProviders, key = { it.id }) { provider ->
                                ProviderCard(
                                    provider = provider,
                                    isActive = activeProvider == provider.id,
                                    isConfigured = isProviderConfigured(provider.id),
                                    isExpanded = expandedProviderId == provider.id,
                                    apiKey = "",
                                    baseUrl = ollamaBaseUrl,
                                    modelName = "",
                                    onToggleExpand = {
                                        expandedProviderId = if (expandedProviderId == provider.id) null else provider.id
                                    },
                                    onSelectActive = {
                                        scope.launch {
                                            settingsStore.setString(SettingsKeys.ACTIVE_PROVIDER, provider.id)
                                            if (provider.models.isNotEmpty()) {
                                                settingsStore.setString(SettingsKeys.ACTIVE_MODEL, provider.models.first().id)
                                            }
                                        }
                                    },
                                    settingsKey = "",
                                    settingsStore = settingsStore,
                                    onBaseUrlChange = { newUrl ->
                                        scope.launch {
                                            settingsStore.setString(SettingsKeys.OLLAMA_BASE_URL, newUrl)
                                        }
                                    },
                                    onModelNameChange = {}
                                )
                            }

                            // Model selection for local
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                SectionHeader(title = "Model Selection", icon = Icons.Filled.Memory)
                            }

                            item {
                                if (activeProvider == "ollama") {
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
                                } else {
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                        ) {
                                            Text(
                                                text = "Select Ollama as your active provider to manage local models.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            // Download GGUF Models card
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                SectionHeader(title = "Download Models", icon = Icons.Filled.Download)
                            }
                            item {
                                Card(
                                    onClick = onNavigateToModels,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Download,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "GGUF Model Downloads",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = "Download and manage local AI models (Gemma, Llama, Phi, Qwen)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(modifier = Modifier.fillMaxWidth()) {
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
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "About Local Models",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Text(
                                            text = "Local models run on your device via Ollama. " +
                                                    "Make sure Ollama is running and accessible at the configured base URL. " +
                                                    "Models must be pulled via the Ollama CLI before they appear here.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            item { Spacer(modifier = Modifier.height(32.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Shared Components ====================

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
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

@Composable
private fun NavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
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

// ==================== Provider Card ====================

@OptIn(FlowPreview::class)
@Composable
private fun ProviderCard(
    provider: ProviderInfo,
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
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isActive,
                    onClick = onSelectActive
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
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

                    // Base URL field
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

                    // API key field — debounced save
                    if (provider.requiresApiKey) {
                        var keyValue by remember(apiKey) { mutableStateOf(apiKey) }
                        var keyVisible by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            snapshotFlow { keyValue }
                                .debounce(500L)
                                .collect { debouncedKey ->
                                    settingsStore.setString(settingsKey, debouncedKey)
                                }
                        }

                        OutlinedTextField(
                            value = keyValue,
                            onValueChange = { newVal -> keyValue = newVal },
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

                    // Model name field (Custom provider)
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

// ==================== Model Selection Card ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionCard(
    activeProvider: String,
    activeModel: String,
    customModel: String,
    onModelSelected: (String) -> Unit
) {
    val provider = PROVIDER_CATALOG.find { it.id == activeProvider }
    val availableModels: List<String> = when {
        provider == null -> emptyList()
        provider.id == "custom" -> if (customModel.isNotBlank()) listOf(customModel) else emptyList()
        else -> provider.models.map { it.id }
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
                        "ollama" -> "Models are managed via the Ollama server."
                        "custom" -> "Enter a model name in the Custom provider configuration."
                        else -> "No models available for this provider."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                var expanded by remember { mutableStateOf(false) }
                val activeModelDisplay = provider?.models?.find { it.id == activeModel }?.displayName ?: activeModel

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = activeModelDisplay,
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
                        availableModels.forEach { modelId ->
                            val displayName = provider?.models?.find { it.id == modelId }?.displayName ?: modelId
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = {
                                    onModelSelected(modelId)
                                    expanded = false
                                },
                                leadingIcon = {
                                    if (modelId == activeModel) {
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
                    text = "Provider: ${provider?.displayName ?: activeProvider}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================== Codex OAuth Card ====================

@Composable
private fun CodexOAuthCard(
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var showCodexDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCodexDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.Login, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text("Sign in with Codex", style = MaterialTheme.typography.titleSmall)
                Text(
                    "GitHub Copilot / Codex OAuth device flow",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showCodexDialog) {
        io.github.gooseandroid.ui.providers.OAuthDeviceFlowDialog(
            providerName = "GitHub Codex",
            deviceAuthUrl = "https://github.com/login/device/code",
            tokenUrl = "https://github.com/login/oauth/access_token",
            clientId = "Iv1.b507a08c87ecfe98",
            scope = "read:user",
            onTokenReceived = { token ->
                scope.launch {
                    settingsStore.setString("codex_oauth_token", token)
                }
                showCodexDialog = false
            },
            onDismiss = { showCodexDialog = false }
        )
    }
}

// ==================== Accent Color Picker ====================

private data class AccentSwatch(val name: String, val color: Color)

private val ACCENT_SWATCHES = listOf(
    AccentSwatch("Orange", Color(0xFFFF6B35)),
    AccentSwatch("White", Color(0xFFE0E0E0)),
    AccentSwatch("Grey", Color(0xFF9E9E9E)),
    AccentSwatch("Black", Color(0xFF424242)),
    AccentSwatch("Blue", Color(0xFF2196F3)),
    AccentSwatch("Green", Color(0xFF00D632)),
    AccentSwatch("Purple", Color(0xFF9C27B0)),
)

@Composable
private fun AccentColorRow(
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val defaultOrange = Color(0xFFFF6B35).toArgb()
    val currentAccent by settingsStore.getInt(
        SettingsKeys.PRIMARY_COLOR, defaultOrange
    ).collectAsState(initial = defaultOrange)

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ACCENT_SWATCHES.forEach { swatch ->
            val swatchArgb = swatch.color.toArgb()
            val isSelected = currentAccent == swatchArgb
            Canvas(
                modifier = Modifier
                    .size(if (isSelected) 36.dp else 30.dp)
                    .clickable {
                        scope.launch {
                            settingsStore.setInt(SettingsKeys.PRIMARY_COLOR, swatchArgb)
                        }
                    }
            ) {
                drawCircle(color = swatch.color)
                if (isSelected) {
                    drawCircle(
                        color = Color.White,
                        radius = size.minDimension / 5f
                    )
                }
            }
        }
    }
}

@Composable
private fun FontScaleSlider(
    settingsStore: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val textScale by settingsStore.getFloat(SettingsKeys.TEXT_SCALE, 1.0f)
        .collectAsState(initial = 1.0f)
    var sliderValue by remember(textScale) { mutableStateOf(textScale) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("A", style = MaterialTheme.typography.bodySmall)
            Text(
                "${(sliderValue * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text("A", style = MaterialTheme.typography.headlineSmall)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                scope.launch {
                    settingsStore.setFloat(SettingsKeys.TEXT_SCALE, sliderValue)
                }
            },
            valueRange = 0.8f..1.5f,
            steps = 6
        )
        Text(
            "Preview: The quick brown fox jumps over the lazy dog.",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = (14 * sliderValue).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
