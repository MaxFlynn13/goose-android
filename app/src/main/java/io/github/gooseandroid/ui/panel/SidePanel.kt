package io.github.gooseandroid.ui.panel

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Side Panel — pop-out navigation for all Goose modules.
 *
 * - Thin tab button always visible on screen edge
 * - Tab stays OUTSIDE the panel so it's never covered
 * - Can be positioned left or right (configurable in settings)
 * - Swipe gesture to open/close
 */

enum class PanelSide { LEFT, RIGHT }

data class PanelModule(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val route: String
)

val PANEL_MODULES = listOf(
    PanelModule("chat", "Chat", Icons.AutoMirrored.Filled.Chat, "chat"),
    PanelModule("history", "History", Icons.Default.History, "history"),
    PanelModule("agents", "Agents", Icons.Default.Face, "agents"),
    PanelModule("projects", "Projects", Icons.Default.Folder, "projects"),
    PanelModule("skills", "Skills", Icons.Default.AutoAwesome, "skills"),
    PanelModule("brain", "Brain", Icons.Default.Psychology, "brain"),
    PanelModule("extensions", "Extensions", Icons.Default.Extension, "extensions"),
    PanelModule("models", "Models", Icons.Default.Memory, "models"),
    PanelModule("doctor", "Doctor", Icons.Default.HealthAndSafety, "doctor"),
    PanelModule("settings", "Settings", Icons.Default.Settings, "settings"),
)

@Composable
fun SidePanel(
    isOpen: Boolean,
    onToggle: () -> Unit,
    side: PanelSide = PanelSide.LEFT,
    currentRoute: String = "chat",
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val panelWidth = 72.dp
    val tabWidth = 28.dp
    val tabHeight = 72.dp

    // The entire panel + tab is a Row so the tab is NEVER covered
    Box(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .align(if (side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            if (side == PanelSide.RIGHT && isOpen) {
                // Tab on the left of panel when panel is on right side
                TabButton(
                    isOpen = isOpen,
                    side = side,
                    tabWidth = tabWidth,
                    tabHeight = tabHeight,
                    onToggle = onToggle
                )
            }

            // Panel content
            AnimatedVisibility(
                visible = isOpen,
                enter = if (side == PanelSide.LEFT) {
                    slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
                } else {
                    slideInHorizontally(initialOffsetX = { it }) + fadeIn()
                },
                exit = if (side == PanelSide.LEFT) {
                    slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                } else {
                    slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                }
            ) {
                Surface(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Module buttons
                        PANEL_MODULES.forEach { module ->
                            PanelButton(
                                module = module,
                                isSelected = currentRoute == module.route,
                                onClick = { onNavigate(module.route) }
                            )
                        }
                    }
                }
            }

            if (side == PanelSide.LEFT || !isOpen) {
                // Tab on the right of panel when panel is on left side
                // Also shows here when panel is closed (right side)
                TabButton(
                    isOpen = isOpen,
                    side = side,
                    tabWidth = tabWidth,
                    tabHeight = tabHeight,
                    onToggle = onToggle
                )
            }
        }

        // When panel is closed and on right side, position tab at the right edge
        if (!isOpen && side == PanelSide.RIGHT) {
            TabButton(
                isOpen = false,
                side = side,
                tabWidth = tabWidth,
                tabHeight = tabHeight,
                onToggle = onToggle,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun TabButton(
    isOpen: Boolean,
    side: PanelSide,
    tabWidth: androidx.compose.ui.unit.Dp,
    tabHeight: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(tabWidth)
            .height(tabHeight)
            .clip(
                if (side == PanelSide.LEFT) {
                    RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                } else {
                    RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                }
            )
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onToggle() }
            .zIndex(10f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isOpen) {
                if (side == PanelSide.LEFT) Icons.Default.ChevronLeft else Icons.Default.ChevronRight
            } else {
                if (side == PanelSide.LEFT) Icons.Default.ChevronRight else Icons.Default.ChevronLeft
            },
            contentDescription = if (isOpen) "Close panel" else "Open panel",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PanelButton(
    module: PanelModule,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val iconColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = bgColor
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                module.icon,
                contentDescription = module.name,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                module.name,
                style = MaterialTheme.typography.labelSmall,
                color = iconColor,
                maxLines = 1
            )
        }
    }
}
