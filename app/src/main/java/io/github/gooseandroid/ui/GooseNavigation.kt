package io.github.gooseandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.gooseandroid.ui.brain.BrainScreen
import io.github.gooseandroid.ui.chat.ChatScreen
import io.github.gooseandroid.ui.chat.ChatViewModel
import io.github.gooseandroid.ui.models.ModelsScreen
import io.github.gooseandroid.ui.panel.SidePanel
import io.github.gooseandroid.ui.panel.PanelSide
import io.github.gooseandroid.ui.settings.AppTheme
import io.github.gooseandroid.ui.settings.AppearanceSettingsScreen
import io.github.gooseandroid.ui.settings.SettingsScreen

/**
 * Top-level navigation with side panel.
 *
 * The side panel provides access to all Goose modules:
 * Chat, History, Brain, Extensions, Models, Scheduler, Settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooseNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route ?: "chat"

    var panelOpen by remember { mutableStateOf(false) }
    var appTheme by remember { mutableStateOf(AppTheme()) }

    // Determine panel side from theme settings
    val panelSide = when (appTheme.panelSide) {
        io.github.gooseandroid.ui.settings.PanelSide.LEFT -> PanelSide.LEFT
        io.github.gooseandroid.ui.settings.PanelSide.RIGHT -> PanelSide.RIGHT
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("chat") {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("🪿 Goose") },
                            navigationIcon = {
                                IconButton(onClick = { panelOpen = !panelOpen }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            }
                        )
                    }
                ) { padding ->
                    ChatScreen(
                        viewModel = chatViewModel,
                        modifier = Modifier.padding(padding)
                    )
                }
            }

            composable("brain") {
                BrainScreen(onBack = { navController.popBackStack() })
            }

            composable("models") {
                ModelsScreen(onBack = { navController.popBackStack() })
            }

            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToModels = { navController.navigate("models") },
                    onNavigateToAppearance = { navController.navigate("appearance") }
                )
            }

            composable("appearance") {
                AppearanceSettingsScreen(
                    currentTheme = appTheme,
                    onThemeChanged = { appTheme = it },
                    onBack = { navController.popBackStack() }
                )
            }

            composable("history") {
                // TODO: Session history screen
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("History") })
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                        Text("Session history coming soon",
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }

            composable("extensions") {
                // TODO: Extensions management screen
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("Extensions") })
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                        Text("Extensions management coming soon",
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }

            composable("scheduler") {
                // TODO: Task scheduler screen
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("Scheduler") })
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                        Text("Task scheduler coming soon",
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }

        // Side panel overlay
        SidePanel(
            isOpen = panelOpen,
            onToggle = { panelOpen = !panelOpen },
            side = panelSide,
            currentRoute = currentRoute,
            onNavigate = { route ->
                panelOpen = false
                if (route != currentRoute) {
                    navController.navigate(route) {
                        popUpTo("chat") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        )
    }
}
