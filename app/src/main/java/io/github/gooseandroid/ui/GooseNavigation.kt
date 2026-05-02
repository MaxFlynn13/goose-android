package io.github.gooseandroid.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.gooseandroid.ui.agents.AgentsScreen
import io.github.gooseandroid.ui.brain.BrainScreen
import io.github.gooseandroid.ui.chat.ChatScreen
import io.github.gooseandroid.ui.chat.ChatViewModel
import io.github.gooseandroid.ui.doctor.DoctorScreen
import io.github.gooseandroid.ui.extensions.ExtensionsScreen
import io.github.gooseandroid.ui.history.HistoryScreen
import io.github.gooseandroid.ui.models.ModelsScreen
import io.github.gooseandroid.ui.panel.SidePanel
import io.github.gooseandroid.ui.projects.ProjectsScreen
import io.github.gooseandroid.ui.settings.SettingsScreen
import io.github.gooseandroid.ui.skills.SkillsScreen

/**
 * Top-level navigation. ChatViewModel is created once here and shared
 * across all destinations so conversation state survives navigation.
 */
@Composable
fun GooseNavigation(sharedText: String? = null) {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route ?: "chat"
    var panelOpen by remember { mutableStateOf(false) }

    // Handle shared text from other apps
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            chatViewModel.prefillPrompt(sharedText)
        }
    }

    val startNewChat: () -> Unit = {
        panelOpen = false
        chatViewModel.createNewSession()
        navController.navigate("chat") {
            popUpTo("chat") { inclusive = true }
            launchSingleTop = true
        }
    }

    // Scrim animation
    val scrimAlpha by animateFloatAsState(
        targetValue = if (panelOpen) 0.5f else 0f,
        animationSpec = tween(250), label = "scrim"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content with slide transitions
        NavHost(
            navController, startDestination = "chat", modifier = Modifier.fillMaxSize(),
            enterTransition = { slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn(tween(200)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut(tween(150)) }
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
                    onResumeSession = { id ->
                        chatViewModel.switchSession(id)
                        navController.navigate("chat") {
                            popUpTo("chat") { inclusive = true }
                        }
                    },
                    onNewChat = startNewChat
                )
            }
            composable("agents") {
                AgentsScreen(
                    onBack = { navController.popBackStack() },
                    onSelectPersona = { persona ->
                        chatViewModel.setActivePersona(persona.id, persona.displayName, persona.systemPrompt)
                        navController.navigate("chat") { popUpTo("chat") { inclusive = true } }
                    }
                )
            }
            composable("projects") {
                ProjectsScreen(
                    onBack = { navController.popBackStack() },
                    onStartChatInProject = { project ->
                        chatViewModel.startProjectSession(project.id, project.name, project.instructions)
                        navController.navigate("chat") { popUpTo("chat") { inclusive = true } }
                    }
                )
            }
            composable("skills") {
                SkillsScreen(
                    onBack = { navController.popBackStack() },
                    onUseSkill = { skill ->
                        chatViewModel.prefillPrompt(skill.instructions)
                        navController.navigate("chat") { popUpTo("chat") { inclusive = true } }
                    }
                )
            }
            composable("brain") { BrainScreen(onBack = { navController.popBackStack() }) }
            composable("extensions") { ExtensionsScreen(onBack = { navController.popBackStack() }) }
            composable("models") { ModelsScreen(onBack = { navController.popBackStack() }) }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToModels = { navController.navigate("models") },
                    onNavigateToExtensions = { navController.navigate("extensions") }
                )
            }
            composable("doctor") { DoctorScreen(onBack = { navController.popBackStack() }) }
        }

        // Scrim
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

        // Side panel — always left side, no tab button
        SidePanel(
            isOpen = panelOpen,
            onToggle = { panelOpen = !panelOpen },
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
