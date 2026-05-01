package io.github.gooseandroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.launch

/**
 * UI Customization settings.
 *
 * Allows users to edit:
 * - Main color (primary)
 * - Secondary color
 * - Text color
 * - Accent color
 * - Text size (small, medium, large, extra-large)
 * - Panel side (left/right)
 * - Dark/Light/System theme
 */

data class AppTheme(
    val primaryColor: Color = Color(0xFFFF6B35),    // Goose orange
    val secondaryColor: Color = Color(0xFF8ECAE6),  // Light blue
    val textColor: Color = Color(0xFFE4E1E6),       // Light gray
    val accentColor: Color = Color(0xFF4CAF50),     // Green
    val textScale: Float = 1.0f,                    // 1.0 = default
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val panelSide: PanelSide = PanelSide.LEFT
)

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class PanelSide { LEFT, RIGHT }

// Preset color palettes
val COLOR_PRESETS = listOf(
    Color(0xFFFF6B35), // Goose Orange (default)
    Color(0xFF6366F1), // Indigo
    Color(0xFF8B5CF6), // Purple
    Color(0xFFEC4899), // Pink
    Color(0xFFEF4444), // Red
    Color(0xFFF59E0B), // Amber
    Color(0xFF10B981), // Emerald
    Color(0xFF06B6D4), // Cyan
    Color(0xFF3B82F6), // Blue
    Color(0xFF6B7280), // Gray
    Color(0xFFFFFFFF), // White
    Color(0xFF000000), // Black
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    currentTheme: AppTheme,
    onThemeChanged: (AppTheme) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(currentTheme) }

    // Persist every change
    fun saveAndApply(newTheme: AppTheme) {
        theme = newTheme
        onThemeChanged(newTheme)
        scope.launch {
            settingsStore.setInt(SettingsKeys.PRIMARY_COLOR, newTheme.primaryColor.toArgb())
            settingsStore.setInt(SettingsKeys.SECONDARY_COLOR, newTheme.secondaryColor.toArgb())
            settingsStore.setInt(SettingsKeys.ACCENT_COLOR, newTheme.accentColor.toArgb())
            settingsStore.setFloat(SettingsKeys.TEXT_SCALE, newTheme.textScale)
            settingsStore.setString(SettingsKeys.THEME_MODE, newTheme.themeMode.name)
            settingsStore.setString(SettingsKeys.PANEL_SIDE, newTheme.panelSide.name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Theme mode
            item {
                SectionTitle("Theme")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = theme.themeMode == mode,
                            onClick = {
                                theme = theme.copy(themeMode = mode)
                                saveAndApply(theme)
                            },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            leadingIcon = {
                                Icon(
                                    when (mode) {
                                        ThemeMode.LIGHT -> Icons.Default.LightMode
                                        ThemeMode.DARK -> Icons.Default.DarkMode
                                        ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
                                    },
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Primary color
            item {
                ColorPickerSection(
                    title = "Main Color",
                    description = "Primary buttons, links, and highlights",
                    currentColor = theme.primaryColor,
                    onColorSelected = {
                        theme = theme.copy(primaryColor = it)
                        saveAndApply(theme)
                    }
                )
            }

            // Secondary color
            item {
                ColorPickerSection(
                    title = "Secondary Color",
                    description = "Secondary elements and surfaces",
                    currentColor = theme.secondaryColor,
                    onColorSelected = {
                        theme = theme.copy(secondaryColor = it)
                        saveAndApply(theme)
                    }
                )
            }

            // Accent color
            item {
                ColorPickerSection(
                    title = "Accent Color",
                    description = "Success indicators, active states",
                    currentColor = theme.accentColor,
                    onColorSelected = {
                        theme = theme.copy(accentColor = it)
                        saveAndApply(theme)
                    }
                )
            }

            // Text size
            item {
                SectionTitle("Text Size")
                Spacer(modifier = Modifier.height(8.dp))
                TextSizeSelector(
                    currentScale = theme.textScale,
                    onScaleChanged = {
                        theme = theme.copy(textScale = it)
                        saveAndApply(theme)
                    }
                )
            }

            // Panel side
            item {
                SectionTitle("Side Panel Position")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = theme.panelSide == PanelSide.LEFT,
                        onClick = {
                            theme = theme.copy(panelSide = PanelSide.LEFT)
                            saveAndApply(theme)
                        },
                        label = { Text("Left") },
                        leadingIcon = { Icon(Icons.Default.AlignHorizontalLeft, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = theme.panelSide == PanelSide.RIGHT,
                        onClick = {
                            theme = theme.copy(panelSide = PanelSide.RIGHT)
                            saveAndApply(theme)
                        },
                        label = { Text("Right") },
                        leadingIcon = { Icon(Icons.Default.AlignHorizontalRight, null, Modifier.size(16.dp)) }
                    )
                }
            }

            // Preview
            item {
                SectionTitle("Preview")
                Spacer(modifier = Modifier.height(8.dp))
                ThemePreview(theme)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ColorPickerSection(
    title: String,
    description: String,
    currentColor: Color,
    onColorSelected: (Color) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(currentColor)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(COLOR_PRESETS) { color ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (color == currentColor) 3.dp else 1.dp,
                            color = if (color == currentColor) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) }
                ) {
                    if (color == currentColor) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(20.dp),
                            tint = if (color == Color.White || color == Color(0xFFF59E0B))
                                Color.Black else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextSizeSelector(
    currentScale: Float,
    onScaleChanged: (Float) -> Unit
) {
    val sizes = listOf(
        0.85f to "Small",
        1.0f to "Medium",
        1.15f to "Large",
        1.3f to "Extra Large"
    )

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sizes.forEach { (scale, label) ->
                FilterChip(
                    selected = currentScale == scale,
                    onClick = { onScaleChanged(scale) },
                    label = {
                        Text(
                            label,
                            fontSize = (14 * scale).sp
                        )
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "The quick brown fox jumps over the lazy dog.",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = (14 * currentScale).sp
        )
    }
}

@Composable
private fun ThemePreview(theme: AppTheme) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.primaryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Primary", color = Color.White, fontSize = (12 * theme.textScale).sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.secondaryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Secondary", color = Color.White, fontSize = (12 * theme.textScale).sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Accent", color = Color.White, fontSize = (12 * theme.textScale).sp)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Sample message text at your chosen size",
                fontSize = (14 * theme.textScale).sp
            )
        }
    }
}
