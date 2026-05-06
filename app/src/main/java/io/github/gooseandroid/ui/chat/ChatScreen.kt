package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import androidx.compose.animation.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.gooseandroid.engine.PermissionManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gooseandroid.data.SettingsKeys
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onMenuClick: () -> Unit,
    onNewChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isLoadingSession by viewModel.isLoadingSession.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val toolCalls by viewModel.toolCalls.collectAsState()
    val messageQueue by viewModel.messageQueue.collectAsState()
    val pendingPrompt by viewModel.pendingPrompt.collectAsState()
    val currentSessionTitle by viewModel.currentSessionTitle.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val pendingPermission by viewModel.pendingPermission.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    // Active model label from settings
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val activeProvider by settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER, "anthropic")
        .collectAsState(initial = "anthropic")
    val activeModel by settingsStore.getString(SettingsKeys.ACTIVE_MODEL, "claude-sonnet-4-20250514")
        .collectAsState(initial = "claude-sonnet-4-20250514")

    val modelLabel = remember(activeProvider, activeModel) {
        val provider = getProviderById(activeProvider)
        provider?.models?.find { it.id == activeModel }?.displayName ?: activeModel
    }

    // Track "Done" state: show briefly after generation completes
    var showDone by remember { mutableStateOf(false) }
    var wasGenerating by remember { mutableStateOf(false) }

    LaunchedEffect(isGenerating) {
        if (wasGenerating && !isGenerating) {
            // Generation just completed
            showDone = true
            delay(2000L)
            showDone = false
        }
        wasGenerating = isGenerating
    }

    // Show errors as snackbar
    LaunchedEffect(lastError) {
        lastError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error.take(100),
                duration = SnackbarDuration.Long
            )
        }
    }

    // Smart scroll: only auto-scroll when user is near the bottom
    val isNearBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems == 0 || lastVisible >= totalItems - 3
        }
    }

    // Auto-scroll only when near bottom AND new messages arrive
    LaunchedEffect(messages.size) {
        if (isNearBottom) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    // Build items with date separators
    val itemsWithSeparators = remember(messages) {
        buildChatItems(messages)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentSessionTitle.ifBlank { "" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp)
                    ) {
                        Text(
                            text = "Goose",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // Loading skeleton while session is loading
                isLoadingSession -> {
                    LoadingSkeleton(modifier = Modifier.weight(1f))
                }
                messages.isEmpty() && !isGenerating -> {
                    // Welcome / empty state
                    WelcomeScreen(
                        onSuggestionClick = { viewModel.sendMessage(it) },
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    // Messages list with date separators
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(itemsWithSeparators, key = { it.key }) { item ->
                            when (item) {
                                is ChatItem.DateSeparator -> DateSeparatorRow(item.label)
                                is ChatItem.Message -> MessageItem(
                                    message = item.message,
                                    onRetry = { viewModel.sendMessage(it) },
                                    onCopy = { text ->
                                        clipboardManager.setText(AnnotatedString(text))
                                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                                    },
                                    onEdit = { messageId, newContent ->
                                        viewModel.editAndResend(messageId, newContent)
                                    }
                                )
                            }
                        }

                        // Streaming bubble
                        if (isGenerating && streamingContent.isNotBlank()) {
                            item(key = "_streaming") {
                                StreamingBubble(content = streamingContent)
                            }
                        }

                        // Running tool calls (grouped as a chain)
                        val running = toolCalls.filter { it.status == ToolCallStatus.RUNNING }
                        if (running.isNotEmpty()) {
                            item(key = "_running_tools") {
                                ToolCallChain(toolCalls = running)
                            }
                        }

                        // Typing indicator
                        if (isGenerating && streamingContent.isBlank()) {
                            item(key = "_typing") { TypingIndicator() }
                        }
                    }
                }
            }

            // Status indicator bar between messages and input
            StatusIndicatorBar(
                isGenerating = isGenerating,
                streamingContent = streamingContent,
                toolCalls = toolCalls,
                showDone = showDone,
                onCancel = { viewModel.cancelGeneration() }
            )

            // Message queue indicator
            if (messageQueue.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${messageQueue.size} queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.sendNextQueued() }) {
                        Text("Send Now", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        messageQueue.forEach { viewModel.removeQueuedMessage(it.id) }
                    }) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Input bar
            ChatInputBar(
                onSend = { text ->
                    val trimmed = text.trim()
                    when {
                        trimmed == "/compact" -> viewModel.compactConversation()
                        trimmed.startsWith("/") -> viewModel.addSystemMessage("Unknown command: $trimmed. Available: /compact")
                        else -> viewModel.sendMessage(text)
                    }
                    // Scroll to bottom after sending
                    scope.launch {
                        delay(100)
                        val total = listState.layoutInfo.totalItemsCount
                        if (total > 0) listState.animateScrollToItem(total - 1)
                    }
                },
                isGenerating = isGenerating,
                onCancel = { viewModel.cancelGeneration() },
                prefillText = pendingPrompt,
                onPrefillConsumed = { viewModel.clearPendingPrompt() },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.ime)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
    }

    // Permission approval dialog
    pendingPermission?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.respondToPermission(PermissionManager.PermissionResult.DENY) },
            title = { Text("Permission Required") },
            text = {
                Column {
                    Text(request.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = request.command,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    if (request.risk == PermissionManager.RiskLevel.HIGH ||
                        request.risk == PermissionManager.RiskLevel.CRITICAL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Risk: ${request.risk.name}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.respondToPermission(PermissionManager.PermissionResult.ALLOW) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.respondToPermission(PermissionManager.PermissionResult.DENY) }) {
                        Text("Deny")
                    }
                    TextButton(onClick = { viewModel.respondToPermission(PermissionManager.PermissionResult.ALLOW_ALL_SESSION) }) {
                        Text("Allow All")
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Status Indicator Bar
// ---------------------------------------------------------------------------

@Composable
private fun StatusIndicatorBar(
    isGenerating: Boolean,
    streamingContent: String,
    toolCalls: List<ToolCall>,
    showDone: Boolean,
    onCancel: () -> Unit
) {
    val runningTools = toolCalls.filter { it.status == ToolCallStatus.RUNNING }

    val statusText = when {
        isGenerating && runningTools.isNotEmpty() -> "Running: ${runningTools.first().name}..."
        isGenerating && streamingContent.isNotBlank() -> "Writing response..."
        isGenerating && streamingContent.isBlank() -> "Thinking..."
        showDone -> "Done"
        else -> null
    }

    AnimatedVisibility(
        visible = statusText != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        if (statusText != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.5.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showDone) {
                        // Checkmark icon for "Done" state
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        // Animated pulsing dot
                        PulsingDot()
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showDone) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Cancel button (only while generating)
                    if (isGenerating) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel generation",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_scale"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}

// ---------------------------------------------------------------------------
// Loading Skeleton
// ---------------------------------------------------------------------------

@Composable
private fun LoadingSkeleton(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton_shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )

    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)

    data class SkeletonRow(val widthFraction: Float, val isUser: Boolean)

    val rows = listOf(
        SkeletonRow(0.60f, true),
        SkeletonRow(0.90f, false),
        SkeletonRow(0.75f, false),
        SkeletonRow(0.50f, true),
        SkeletonRow(0.85f, false),
        SkeletonRow(0.65f, false)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { row ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (row.isUser) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(row.widthFraction)
                        .height(if (row.isUser) 40.dp else 52.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (row.isUser) 16.dp else 4.dp,
                                bottomEnd = if (row.isUser) 4.dp else 16.dp
                            )
                        )
                        .background(shimmerColor)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Welcome / Empty State
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeScreen(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    val suggestions = remember {
        listOf(
            "Explain how Kotlin coroutines work",
            "Help me debug a crash in my Android app",
            "Write a bash script to find large files",
            "Summarize the key ideas of a paper I paste"
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "How can Goose help you today?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))

            suggestions.forEach { suggestion ->
                OutlinedCard(
                    onClick = { onSuggestionClick(suggestion) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Date Separators
// ---------------------------------------------------------------------------

private sealed class ChatItem(val key: String) {
    class DateSeparator(val label: String, key: String) : ChatItem(key)
    class Message(val message: ChatMessage) : ChatItem(message.id)
}

private fun buildChatItems(messages: List<ChatMessage>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()
    val items = mutableListOf<ChatItem>()
    var lastDateLabel = ""
    for (msg in messages) {
        val dateLabel = formatDateLabel(msg.timestamp)
        if (dateLabel != lastDateLabel) {
            items.add(ChatItem.DateSeparator(dateLabel, "sep_${msg.timestamp}"))
            lastDateLabel = dateLabel
        }
        items.add(ChatItem.Message(msg))
    }
    return items
}

private fun formatDateLabel(timestamp: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR)
    val year = cal.get(Calendar.YEAR)
    cal.timeInMillis = timestamp
    val msgDay = cal.get(Calendar.DAY_OF_YEAR)
    val msgYear = cal.get(Calendar.YEAR)
    return when {
        msgYear == year && msgDay == today -> "Today"
        msgYear == year && msgDay == today - 1 -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
private fun DateSeparatorRow(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------
// Message Item dispatcher
// ---------------------------------------------------------------------------

@Composable
private fun MessageItem(
    message: ChatMessage,
    onRetry: (String) -> Unit,
    onCopy: (String) -> Unit,
    onEdit: (String, String) -> Unit = { _, _ -> }
) {
    Column {
        when (message.role) {
            MessageRole.USER -> UserBubble(
                message = message,
                onRetry = onRetry,
                onDelete = {},
                onEdit = onEdit
            )
            MessageRole.ASSISTANT -> AssistantBubble(message = message, onDelete = {})
            MessageRole.SYSTEM -> SystemBubble(message = message)
            MessageRole.TOOL -> {
                // Tool calls render as their own cards between message bubbles
                for (tc in message.toolCalls) {
                    ToolCallCard(toolCall = tc)
                }
            }
        }
        // Message timestamp
        Text(
            text = formatTimestamp(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = if (message.role == MessageRole.USER) 0.dp else 4.dp, top = 2.dp)
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}
