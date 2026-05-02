package io.github.gooseandroid.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import io.github.gooseandroid.ui.brain.BrainScreen
import io.github.gooseandroid.ui.chat.ChatScreen
import io.github.gooseandroid.ui.chat.ChatViewModel
import io.github.gooseandroid.ui.extensions.ExtensionsScreen
import io.github.gooseandroid.ui.history.HistoryScreen
import io.github.gooseandroid.ui.models.ModelsScreen
import io.github.gooseandroid.ui.panel.PanelSide
import io.github.gooseandroid.ui.panel.SidePanel
import io.github.gooseandroid.ui.recipes.RecipesScreen
import io.github.gooseandroid.ui.settings.AppTheme
import io.github.gooseandroid.ui.settings.AppearanceSettingsScreen
import io.github.gooseandroid.ui.settings.SettingsScreen

/**
 * Top-level navigation for the Goose AI Android app.
 *
 * Uses a drawer-style overlay pattern: the main content always fills the
 * entire screen, and the SidePanel slides over it with a scrim backdrop
 * when opened. Panel side (left / right) is read from DataStore so the
 * user's preference is applied immediately on launch.
 *
 * ChatViewModel is created here and shared across navigation so conversation
 * state survives navigation between destinations. New sessions can be created
 * from multiple places (panel, history screen, chat screen) and previous
 * sessions can be resumed from history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooseNavigation() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route ?: "chat"

    var panelOpen by remember { mutableStateOf(false) }
    var appTheme by remember { mutableStateOf(AppTheme()) }

    // Read panel side from persisted DataStore settings
    val panelSideStr by settingsStore
        .getString(SettingsKeys.PANEL_SIDE, "LEFT")
        .collectAsState(initial = "LEFT")
    val panelSide = if (panelSideStr == "RIGHT") PanelSide.RIGHT else PanelSide.LEFT

    // Animated scrim alpha for smooth open/close transitions
    val scrimAlpha by animateFloatAsState(
        targetValue = if (panelOpen) 0.5f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "scrimAlpha"
    )

    // Shared navigation helper: close panel and navigate
    val navigateAndClosePanel: (String) -> Unit = { route ->
        panelOpen = false
        if (route != currentRoute) {
            navController.navigate(route) {
                popUpTo("chat") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Shared action: create a new chat session and navigate to chat
    val startNewChat: () -> Unit = {
        panelOpen = false
        chatViewModel.createNewSession()
        navController.navigate("chat") {
            popUpTo("chat") { inclusive = true }
            launchSingleTop = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- Main content: always fills the full screen ----
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("chat") {
                ChatScreen(
                    viewModel = chatViewModel,
                    onMenuClick = { panelOpen = true },
                    onNewChat = startNewChat,
                    modifier = Modifier.fillMaxSize()
                )
            }

            composable("history") {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onResumeSession = { sessionId ->
                        chatViewModel.switchSession(sessionId)
                        navController.navigate("chat") {
                            popUpTo("chat") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onNewChat = startNewChat
                )
            }

            composable("recipes") {
                RecipesScreen(
                    onBack = { navController.popBackStack() },
                    onUseRecipe = { recipe ->
                        chatViewModel.prefillPrompt(recipe.prompt)
                        navController.navigate("chat") {
                            popUpTo("chat") { inclusive = true }
                        }
                    }
                )
            }

            composable("brain") {
                BrainScreen(onBack = { navController.popBackStack() })
            }

            composable("extensions") {
                ExtensionsScreen(onBack = { navController.popBackStack() })
            }

            composable("models") {
                ModelsScreen(onBack = { navController.popBackStack() })
            }

            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToModels = { navController.navigate("models") },
                    onNavigateToAppearance = { navController.navigate("appearance") },
                    onNavigateToExtensions = { navController.navigate("extensions") }
                )
            }

            composable("appearance") {
                AppearanceSettingsScreen(
                    currentTheme = appTheme,
                    onThemeChanged = { appTheme = it },
                    onBack = { navController.popBackStack() }
                )
            }

            composable("scheduler") {
                SchedulerPlaceholder(onBack = { navController.popBackStack() })
            }
        }

        // ---- Scrim: visible only when the panel is open ----
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { panelOpen = false }
            )
        }

        // ---- Side panel: slides over content ----
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

/**
 * Placeholder screen for the Scheduler feature (not yet implemented).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerPlaceholder(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduler") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Scheduler", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Schedule recurring tasks for Goose to run automatically.\nComing in a future update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
