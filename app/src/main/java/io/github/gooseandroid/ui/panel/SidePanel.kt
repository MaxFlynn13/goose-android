package io.github.gooseandroid.ui.panel

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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

/**
 * Side Panel — pop-out navigation for all Goose modules.
 *
 * Features:
 * - Thin tab button to open/close (always visible on edge)
 * - Can be positioned left or right (configurable in settings)
 * - Swipe gesture to open/close
 * - Contains all module navigation icons
 *
 * Modules:
 * - Chat/History
 * - Recipes
 * - Skills
 * - Apps (maybe)
 * - Scheduler
 * - Extensions
 * - Brain 🧠
 * - Settings
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
    PanelModule("brain", "Brain", Icons.Default.Psychology, "brain"),
    PanelModule("extensions", "Extensions", Icons.Default.Extension, "extensions"),
    PanelModule("models", "Models", Icons.Default.Memory, "models"),
    PanelModule("scheduler", "Scheduler", Icons.Default.Schedule, "scheduler"),
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
    val tabWidth = 24.dp

    Box(modifier = modifier.fillMaxHeight()) {
        // Panel content (slides in/out)
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
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Goose logo at top
                    Text(
                        "🪿",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

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

        // Tab button (always visible on the edge)
        val tabOffset = if (isOpen) {
            if (side == PanelSide.LEFT) panelWidth else (-panelWidth - tabWidth)
        } else {
            0.dp
        }

        Box(
            modifier = Modifier
                .align(if (side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                .offset(x = tabOffset)
                .width(tabWidth)
                .height(64.dp)
                .clip(
                    if (side == PanelSide.LEFT) {
                        RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                    } else {
                        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                    }
                )
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { onToggle() }
                .pointerInput(side) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        val shouldOpen = if (side == PanelSide.LEFT) dragAmount > 10 else dragAmount < -10
                        val shouldClose = if (side == PanelSide.LEFT) dragAmount < -10 else dragAmount > 10
                        if (shouldOpen && !isOpen) onToggle()
                        if (shouldClose && isOpen) onToggle()
                    }
                },
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
                modifier = Modifier.size(16.dp)
            )
        }
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
            .size(56.dp)
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
                modifier = Modifier.size(22.dp)
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
