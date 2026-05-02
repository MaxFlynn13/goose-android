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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Chat screen - main conversation interface for Goose AI Android.
 *
 * Features:
 * - Markdown rendering in assistant messages
 * - Streaming text display (character by character)
 * - Expandable tool call cards with status and output
 * - Message actions (copy, retry, delete) via long press popup menu
 * - Syntax-highlighted code blocks with copy button
 * - New chat button in top bar
 * - Session title display
 * - Provider/model indicator chip
 * - File attachments (images and text files)
 * - Voice input via SpeechRecognizer
 * - Inline image display for assistant messages
 * - /compact command for context compaction
 * - Context token counter in top bar
 */

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

    // Token counter: approximate tokens from message history
    val estimatedTokens by remember(messages) {
        derivedStateOf {
            messages.sumOf { it.content.length } / 4
        }
    }

    // Auto-scroll to bottom when new messages arrive or streaming updates
    LaunchedEffect(messages.size, streamingContent) {
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentSessionTitle.ifBlank { "Goose" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "~$estimatedTokens tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open menu"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New chat"
                        )
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
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        onRetry = { viewModel.sendMessage(it) },
                        onDelete = { /* handled by viewModel if exposed */ }
                    )
                }

                // Streaming message (if generating and content is available)
                if (isGenerating && streamingContent.isNotBlank()) {
                    item(key = "_streaming_bubble") {
                        StreamingBubble(content = streamingContent)
                    }
                }

                // Active tool calls
                val runningTools = toolCalls.filter { it.status == ToolCallStatus.RUNNING }
                if (runningTools.isNotEmpty()) {
                    items(runningTools, key = { "tool_${it.id}_${it.name}" }) { tool ->
                        ToolCallCard(toolCall = tool)
                    }
                }

                // Typing indicator when generating but no streaming content yet
                if (isGenerating && streamingContent.isBlank()) {
                    item(key = "_typing_indicator") {
                        TypingIndicator()
                    }
                }
            }

            // Input bar with IME insets applied ONLY here
            ChatInputBar(
                onSend = { text ->
                    // Handle /compact command
                    if (text.trim() == "/compact") {
                        viewModel.addSystemMessage("[compact] Context compaction requested.")
                        viewModel.sendMessage("/compact")
                    } else {
                        viewModel.sendMessage(text)
                    }
                    scope.launch {
                        delay(100)
                        val totalItems = listState.layoutInfo.totalItemsCount
                        if (totalItems > 0) {
                            listState.animateScrollToItem(totalItems - 1)
                        }
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
// Provider/Model Chip
// ---------------------------------------------------------------------------

@Composable
private fun ProviderModelChip() {
    val modelLabel = remember { "Claude Sonnet 4" }
    SuggestionChip(
        onClick = {},
        label = {
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        },
        modifier = Modifier.padding(end = 8.dp)
    )
}

// ---------------------------------------------------------------------------
// Message Item (dispatches by role)
// ---------------------------------------------------------------------------

@Composable
private fun MessageItem(
    message: ChatMessage,
    onRetry: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit
) {
    when (message.role) {
        MessageRole.USER -> UserBubble(message = message, onRetry = onRetry, onDelete = onDelete)
        MessageRole.ASSISTANT -> AssistantBubble(message = message, onDelete = onDelete)
        MessageRole.SYSTEM -> SystemBubble(message = message)
    }
}
