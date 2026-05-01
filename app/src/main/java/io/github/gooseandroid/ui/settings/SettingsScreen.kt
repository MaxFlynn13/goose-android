package io.github.gooseandroid.ui.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gooseandroid.LocalModelManager
import io.github.gooseandroid.ModelInfo
import io.github.gooseandroid.ModelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToAppearance: () -> Unit = {}
) {
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
            item {
                SettingsSection("Provider")
            }

            item {
                ProviderCard(
                    title = "Cloud LLM",
                    description = "Anthropic, OpenAI, Google, OpenRouter",
                    icon = Icons.Default.Cloud,
                    onClick = { /* TODO: Provider config */ }
                )
            }

            item {
                ProviderCard(
                    title = "Local Models",
                    description = "On-device inference via LiteRT",
                    icon = Icons.Default.PhoneAndroid,
                    onClick = onNavigateToModels
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection("API Keys")
            }

            item {
                ApiKeyField(
                    label = "Anthropic API Key",
                    keyName = "ANTHROPIC_API_KEY"
                )
            }

            item {
                ApiKeyField(
                    label = "OpenAI API Key",
                    keyName = "OPENAI_API_KEY"
                )
            }

            item {
                ApiKeyField(
                    label = "Google AI API Key",
                    keyName = "GOOGLE_API_KEY"
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
                ExtensionToggle(
                    name = "Developer",
                    description = "Shell, file edit, tree tools",
                    enabled = true,
                    onToggle = { /* TODO */ }
                )
            }

            item {
                ExtensionToggle(
                    name = "Memory",
                    description = "Persistent preferences across sessions",
                    enabled = false,
                    onToggle = { /* TODO */ }
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun ApiKeyField(label: String, keyName: String) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
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
        visualTransformation = if (visible) {
            androidx.compose.ui.text.input.VisualTransformation.None
        } else {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        }
    )
}

@Composable
private fun ExtensionToggle(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var isEnabled by remember { mutableStateOf(enabled) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = {
                    isEnabled = it
                    onToggle(it)
                }
            )
        }
    }
}
