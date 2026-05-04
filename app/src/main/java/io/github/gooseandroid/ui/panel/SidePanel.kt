package io.github.gooseandroid.ui.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Side navigation panel — slides in from the left edge.
 * Opened via hamburger menu in the top bar.
 * Closed by tapping the scrim (handled in GooseNavigation).
 */

@Stable
data class PanelModule(
    val title: String,
    val description: String?,
    val icon: ImageVector,
    val route: String,
)

private val PRIMARY_MODULES = listOf(
    PanelModule(
        title = "Chat",
        description = "Conversations with Goose",
        icon = Icons.AutoMirrored.Filled.Chat,
        route = "chat",
    ),
    PanelModule(
        title = "History",
        description = "Past sessions",
        icon = Icons.Default.History,
        route = "history",
    ),
    PanelModule(
        title = "Agents",
        description = "Manage agent profiles",
        icon = Icons.Default.Face,
        route = "agents",
    ),
    PanelModule(
        title = "Projects",
        description = null,
        icon = Icons.Default.Folder,
        route = "projects",
    ),
    PanelModule(
        title = "Skills",
        description = null,
        icon = Icons.Default.AutoAwesome,
        route = "skills",
    ),
    PanelModule(
        title = "Workspace",
        description = null,
        icon = Icons.Default.Storage,
        route = "workspace",
    ),
    PanelModule(
        title = "Brain",
        description = "Memory & knowledge",
        icon = Icons.Default.Psychology,
        route = "brain",
    ),
    PanelModule(
        title = "Extensions",
        description = "Plugins & integrations",
        icon = Icons.Default.Extension,
        route = "extensions",
    ),
)

private val SETTINGS_MODULE = PanelModule(
    title = "Settings",
    description = "Models, runtimes & more",
    icon = Icons.Default.Settings,
    route = "settings",
)

private const val ANIMATION_DURATION_MS = 250

@Composable
fun SidePanel(
    isOpen: Boolean,
    onToggle: () -> Unit,
    currentRoute: String = "chat",
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enterTransition = remember {
        slideInHorizontally(
            animationSpec = tween(ANIMATION_DURATION_MS),
            initialOffsetX = { fullWidth -> -fullWidth },
        ) + fadeIn(animationSpec = tween(ANIMATION_DURATION_MS))
    }
    val exitTransition = remember {
        slideOutHorizontally(
            animationSpec = tween(ANIMATION_DURATION_MS),
            targetOffsetX = { fullWidth -> -fullWidth },
        ) + fadeOut(animationSpec = tween(ANIMATION_DURATION_MS))
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header branding
                PanelHeader()

                Spacer(modifier = Modifier.height(16.dp))

                // Primary navigation items
                PRIMARY_MODULES.forEach { module ->
                    PanelNavigationItem(
                        module = module,
                        isSelected = currentRoute == module.route,
                        onClick = { onNavigate(module.route) },
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Spacer(modifier = Modifier.weight(1f))

                // Divider before Settings
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // Settings item
                PanelNavigationItem(
                    module = SETTINGS_MODULE,
                    isSelected = currentRoute == SETTINGS_MODULE.route,
                    onClick = { onNavigate(SETTINGS_MODULE.route) },
                )
            }
        }
    }
}

@Composable
private fun PanelHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Goose",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PanelNavigationItem(
    module: PanelModule,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val descriptionText = buildString {
        append("Navigate to ")
        append(module.title)
        if (isSelected) append(", currently selected")
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = descriptionText
                role = Role.Button
            },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                imageVector = module.icon,
                contentDescription = null, // Described by parent semantics
                modifier = Modifier.size(24.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                )
                if (module.description != null) {
                    Text(
                        text = module.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
