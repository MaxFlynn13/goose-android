package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val streamingContent by viewModel.streamingContent.collectAsState()
    val toolCalls by viewModel.toolCalls.collectAsState()
    val pendingPrompt by viewModel.pendingPrompt.collectAsState()
    val currentSessionTitle by viewModel.currentSessionTitle.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

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
                        text = currentSessionTitle.ifBlank { "Goose" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                    ProviderModelChip()
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
            if (messages.isEmpty() && !isGenerating) {
                // P0: Welcome / empty state
                WelcomeScreen(
                    onSuggestionClick = { viewModel.sendMessage(it) },
                    modifier = Modifier.weight(1f)
                )
            } else {
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

                    // Running tool calls
                    val running = toolCalls.filter { it.status == ToolCallStatus.RUNNING }
                    if (running.isNotEmpty()) {
                        items(running, key = { "tool_${it.id}" }) { ToolCallCard(it) }
                    }

                    // Typing indicator
                    if (isGenerating && streamingContent.isBlank()) {
                        item(key = "_typing") { TypingIndicator() }
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
                    .windowInsetsPadding(WindowInsets.ime)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Welcome / Empty State (P0 #1)
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
// Date Separators (P1 #5)
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
// Provider/Model Chip with dropdown
// ---------------------------------------------------------------------------

@Composable
private fun ProviderModelChip() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val activeProvider by settingsStore.getString(SettingsKeys.ACTIVE_PROVIDER, "anthropic")
        .collectAsState(initial = "anthropic")
    val activeModel by settingsStore.getString(SettingsKeys.ACTIVE_MODEL, "claude-sonnet-4-20250514")
        .collectAsState(initial = "claude-sonnet-4-20250514")

    val modelLabel = remember(activeProvider, activeModel) {
        val provider = getProviderById(activeProvider)
        provider?.models?.find { it.id == activeModel }?.displayName ?: activeModel
    }

    Box {
        SuggestionChip(
            onClick = { expanded = true },
            label = { Text(modelLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
            modifier = Modifier.padding(end = 8.dp)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PROVIDER_CATALOG.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) },
                    onClick = {}, enabled = false
                )
                provider.models.forEach { model ->
                    val isActive = activeProvider == provider.id && activeModel == model.id
                    DropdownMenuItem(
                        text = { Text(if (isActive) "  ${model.displayName}  *" else "  ${model.displayName}", style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            scope.launch {
                                settingsStore.setString(SettingsKeys.ACTIVE_PROVIDER, provider.id)
                                settingsStore.setString(SettingsKeys.ACTIVE_MODEL, model.id)
                            }
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Message Item dispatcher
// ---------------------------------------------------------------------------

@Composable
private fun MessageItem(
    message: ChatMessage,
    onRetry: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    Column {
        when (message.role) {
            MessageRole.USER -> UserBubble(message = message, onRetry = onRetry, onDelete = {})
            MessageRole.ASSISTANT -> AssistantBubble(message = message, onDelete = {})
            MessageRole.SYSTEM -> SystemBubble(message = message)
        }
        // P1 #6: Message timestamp
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
