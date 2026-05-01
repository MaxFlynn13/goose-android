package io.github.gooseandroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToAppearance: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    // Load persisted values
    val anthropicKey by settingsStore.getString(SettingsKeys.ANTHROPIC_API_KEY).collectAsState(initial = "")
    val openaiKey by settingsStore.getString(SettingsKeys.OPENAI_API_KEY).collectAsState(initial = "")
    val googleKey by settingsStore.getString(SettingsKeys.GOOGLE_API_KEY).collectAsState(initial = "")
    val devExtEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_DEVELOPER, true).collectAsState(initial = true)
    val memExtEnabled by settingsStore.getBoolean(SettingsKeys.EXTENSION_MEMORY, false).collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SettingsSection("Provider") }

            item {
                ProviderCard(
                    title = "Cloud LLM",
                    description = "Anthropic, OpenAI, Google, OpenRouter",
                    icon = Icons.Default.Cloud,
                    onClick = { /* Expand inline below */ }
                )
            }

            item {
                ProviderCard(
                    title = "Local Models",
                    description = "On-device inference via LiteRT / GGUF",
                    icon = Icons.Default.PhoneAndroid,
                    onClick = onNavigateToModels
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection("API Keys")
            }

            item {
                PersistentApiKeyField(
                    label = "Anthropic API Key",
                    value = anthropicKey,
                    onSave = { scope.launch { settingsStore.setString(SettingsKeys.ANTHROPIC_API_KEY, it) } }
                )
            }

            item {
                PersistentApiKeyField(
                    label = "OpenAI API Key",
                    value = openaiKey,
                    onSave = { scope.launch { settingsStore.setString(SettingsKeys.OPENAI_API_KEY, it) } }
                )
            }

            item {
                PersistentApiKeyField(
                    label = "Google AI API Key",
                    value = googleKey,
                    onSave = { scope.launch { settingsStore.setString(SettingsKeys.GOOGLE_API_KEY, it) } }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection("Appearance")
            }

            item {
                ProviderCard(
                    title = "UI Customization",
                    description = "Colors, text size, panel position",
                    icon = Icons.Default.Palette,
                    onClick = onNavigateToAppearance
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection("Extensions")
            }

            item {
                PersistentExtensionToggle(
                    name = "Developer",
                    description = "Shell, file edit, tree tools",
                    enabled = devExtEnabled,
                    onToggle = { scope.launch { settingsStore.setBoolean(SettingsKeys.EXTENSION_DEVELOPER, it) } }
                )
            }

            item {
                PersistentExtensionToggle(
                    name = "Memory",
                    description = "Persistent preferences across sessions",
                    enabled = memExtEnabled,
                    onToggle = { scope.launch { settingsStore.setBoolean(SettingsKeys.EXTENSION_MEMORY, it) } }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ProviderCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

/**
 * API key field that persists on every change (debounced).
 */
@Composable
private fun PersistentApiKeyField(
    label: String,
    value: String,
    onSave: (String) -> Unit
) {
    var localValue by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = localValue,
        onValueChange = {
            localValue = it
            onSave(it) // Save immediately
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "Toggle visibility"
                )
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    )
}

@Composable
private fun PersistentExtensionToggle(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
