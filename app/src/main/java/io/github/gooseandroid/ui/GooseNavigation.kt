package io.github.gooseandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.gooseandroid.ui.chat.ChatScreen
import io.github.gooseandroid.ui.chat.ChatViewModel
import io.github.gooseandroid.ui.models.ModelsScreen
import io.github.gooseandroid.ui.settings.SettingsScreen

/**
 * Top-level navigation for the Goose Android app.
 *
 * Routes:
 * - chat: Main chat interface (default)
 * - settings: Provider config, API keys, extensions
 * - models: Local model download and management
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooseNavigation() {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = "chat"
    ) {
        composable("chat") {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("🪿 Goose") },
                        actions = {
                            IconButton(onClick = { navController.navigate("settings") }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
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

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToModels = { navController.navigate("models") }
            )
        }

        composable("models") {
            ModelsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
