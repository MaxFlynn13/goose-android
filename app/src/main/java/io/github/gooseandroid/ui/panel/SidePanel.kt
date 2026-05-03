package io.github.gooseandroid.ui.panel

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Side Panel — slides in from the left edge.
 * No tab button. Opened via hamburger menu in the top bar.
 * Closed by tapping the scrim (handled in GooseNavigation).
 */

data class PanelModule(val name: String, val icon: ImageVector, val route: String)

// Removed PanelSide enum — always left. Removed tab button — use hamburger menu.

private val MODULES = listOf(
    PanelModule("Chat", Icons.AutoMirrored.Filled.Chat, "chat"),
    PanelModule("History", Icons.Default.History, "history"),
    PanelModule("Agents", Icons.Default.Face, "agents"),
    PanelModule("Projects", Icons.Default.Folder, "projects"),
    PanelModule("Skills", Icons.Default.AutoAwesome, "skills"),
    PanelModule("Workspace", Icons.Default.Storage, "workspace"),
    PanelModule("Brain", Icons.Default.Psychology, "brain"),
    PanelModule("Extensions", Icons.Default.Extension, "extensions"),
    PanelModule("Models", Icons.Default.Memory, "models"),
    PanelModule("Runtimes", Icons.Default.Build, "runtimes"),
    PanelModule("Logs", Icons.Default.Terminal, "logs"),
    PanelModule("Doctor", Icons.Default.HealthAndSafety, "doctor"),
    PanelModule("Settings", Icons.Default.Settings, "settings"),
)

@Composable
fun SidePanel(
    isOpen: Boolean,
    onToggle: () -> Unit,
    currentRoute: String = "chat",
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .width(72.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MODULES.forEach { module ->
                    PanelButton(
                        module = module,
                        isSelected = currentRoute == module.route,
                        onClick = { onNavigate(module.route) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelButton(
    module: PanelModule,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isSelected) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = bg
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(module.icon, contentDescription = module.name, tint = fg, modifier = Modifier.size(20.dp))
            Text(module.name, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
        }
    }
}
