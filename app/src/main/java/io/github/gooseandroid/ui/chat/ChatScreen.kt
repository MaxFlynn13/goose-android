package io.github.gooseandroid.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

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

/**
 * Data class representing an attached file ready to be sent with a message.
 */
private data class FileAttachment(
    val uri: Uri,
    val name: String,
    val content: String,
    val mimeType: String
)

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
                    ProviderModelChip(viewModel)
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
private fun ProviderModelChip(viewModel: ChatViewModel) {
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

// ---------------------------------------------------------------------------
// User Bubble
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: ChatMessage,
    onRetry: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Retry") },
                    onClick = {
                        onRetry(message.content)
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        onDelete(message)
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Assistant Bubble (with markdown rendering and inline images)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: ChatMessage,
    onDelete: (ChatMessage) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Render inline images if present
                    val imageUrls = extractImageUrls(message.content)
                    val textContent = removeImageMarkdown(message.content)

                    if (textContent.isNotBlank()) {
                        MarkdownContent(
                            content = textContent,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Display extracted images
                    imageUrls.forEach { url ->
                        Spacer(modifier = Modifier.height(8.dp))
                        InlineImage(url = url)
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        onDelete(message)
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                )
            }
        }

        // Tool calls attached to this message
        if (message.toolCalls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            message.toolCalls.forEach { toolCall ->
                ToolCallCard(toolCall = toolCall)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Inline Image Display
// ---------------------------------------------------------------------------

/**
 * Renders an image from a URL or base64 data URI inline in the message.
 */
@Composable
private fun InlineImage(url: String) {
    val context = LocalContext.current
    val imageModel = if (url.startsWith("data:image")) {
        // Base64 image data URI
        url
    } else {
        url
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageModel)
            .crossfade(true)
            .build(),
        contentDescription = "Inline image",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit
    )
}

/**
 * Extracts image URLs from markdown content.
 * Matches: ![alt](url) and bare URLs ending in image extensions,
 * and base64 data URIs.
 */
private fun extractImageUrls(content: String): List<String> {
    val urls = mutableListOf<String>()

    // Markdown image syntax: ![alt](url)
    val markdownImageRegex = Regex("!\\[.*?]\\((.*?)\\)")
    markdownImageRegex.findAll(content).forEach { match ->
        urls.add(match.groupValues[1])
    }

    // Base64 data URIs in the text
    val base64Regex = Regex("(data:image/[^;]+;base64,[A-Za-z0-9+/=]+)")
    base64Regex.findAll(content).forEach { match ->
        val dataUri = match.groupValues[1]
        if (dataUri !in urls) {
            urls.add(dataUri)
        }
    }

    // Bare image URLs (http/https ending in common image extensions)
    val bareUrlRegex = Regex("(https?://\\S+\\.(?:png|jpg|jpeg|gif|webp|svg))(\\s|$|\\))")
    bareUrlRegex.findAll(content).forEach { match ->
        val bareUrl = match.groupValues[1]
        if (bareUrl !in urls) {
            urls.add(bareUrl)
        }
    }

    return urls
}

/**
 * Removes image markdown syntax from content so text renders cleanly.
 */
private fun removeImageMarkdown(content: String): String {
    var result = content
    // Remove ![alt](url) patterns
    result = Regex("!\\[.*?]\\(.*?\\)").replace(result, "")
    // Remove standalone base64 data URIs
    result = Regex("data:image/[^;]+;base64,[A-Za-z0-9+/=]+").replace(result, "")
    return result.trim()
}

// ---------------------------------------------------------------------------
// System Bubble
// ---------------------------------------------------------------------------

@Composable
private fun SystemBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Streaming Bubble (shows text appearing progressively)
// ---------------------------------------------------------------------------

@Composable
private fun StreamingBubble(content: String) {
    var displayedLength by remember { mutableIntStateOf(0) }

    LaunchedEffect(content) {
        while (displayedLength < content.length) {
            displayedLength = (displayedLength + 1).coerceAtMost(content.length)
            delay(12L)
        }
    }

    LaunchedEffect(content.length < displayedLength) {
        if (content.length < displayedLength) {
            displayedLength = content.length
        }
    }

    val visibleText = content.take(displayedLength)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .animateContentSize()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                MarkdownContent(
                    content = visibleText,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tool Call Card (expandable)
// ---------------------------------------------------------------------------

@Composable
private fun ToolCallCard(toolCall: ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val toolIcon = when (toolCall.status) {
                    ToolCallStatus.RUNNING -> Icons.Default.Sync
                    ToolCallStatus.COMPLETE -> Icons.Default.CheckCircle
                    ToolCallStatus.ERROR -> Icons.Default.Error
                }
                val iconTint = when (toolCall.status) {
                    ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                    ToolCallStatus.COMPLETE -> MaterialTheme.colorScheme.primary
                    ToolCallStatus.ERROR -> MaterialTheme.colorScheme.error
                }
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint
                )

                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )

                ToolStatusChip(status = toolCall.status)

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                if (toolCall.input.isNotBlank()) {
                    Text(
                        text = "Input:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = toolCall.input,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (toolCall.output.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Output:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(toolCall.output))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy output",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = toolCall.output,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusChip(status: ToolCallStatus) {
    val (label, color) = when (status) {
        ToolCallStatus.RUNNING -> "running" to MaterialTheme.colorScheme.tertiary
        ToolCallStatus.COMPLETE -> "done" to MaterialTheme.colorScheme.primary
        ToolCallStatus.ERROR -> "error" to MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// ---------------------------------------------------------------------------
// Typing Indicator
// ---------------------------------------------------------------------------

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val delay = index * 200
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = delay, easing = EaseInOut),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot_$index"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                            )
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown Content Renderer
// ---------------------------------------------------------------------------

@Composable
private fun MarkdownContent(
    content: String,
    textColor: Color
) {
    val blocks = parseMarkdownBlocks(content)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    CodeBlockView(
                        code = block.code,
                        language = block.language
                    )
                }
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = block.text,
                        style = style,
                        color = textColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = textColor.copy(alpha = 0.3f)
                    )
                }
                is MarkdownBlock.ListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = block.bullet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.width(20.dp)
                        )
                        InlineMarkdownText(
                            text = block.text,
                            textColor = textColor
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    InlineMarkdownText(
                        text = block.text,
                        textColor = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMarkdownText(
    text: String,
    textColor: Color
) {
    val codeBackground = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary

    val annotated = remember(text, textColor) {
        buildInlineAnnotatedString(text, textColor, codeBackground, linkColor)
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor
    )
}

private fun buildInlineAnnotatedString(
    text: String,
    textColor: Color,
    codeBackground: Color,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold: **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold: __text__
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Inline code: `code`
                text[i] == '`' && !text.startsWith("```", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                fontSize = 13.sp
                            )
                        ) {
                            append(" ")
                            append(text.substring(i + 1, end))
                            append(" ")
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Link: [text](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            withStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                append(linkText)
                            }
                            i = closeParen + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: *text*
                text[i] == '*' && !text.startsWith("**", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: _text_
                text[i] == '_' && !text.startsWith("__", i) -> {
                    val end = text.indexOf('_', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Code Block View (with syntax highlighting and copy button)
// ---------------------------------------------------------------------------

@Composable
private fun CodeBlockView(
    code: String,
    language: String
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.05f)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            SelectionContainer {
                Text(
                    text = highlightSyntax(code, language),
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                )
            }
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
}

@Composable
private fun highlightSyntax(code: String, language: String): AnnotatedString {
    val keywordColor = MaterialTheme.colorScheme.primary
    val stringColor = MaterialTheme.colorScheme.tertiary
    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val numberColor = MaterialTheme.colorScheme.error
    val defaultColor = MaterialTheme.colorScheme.onSurface

    return remember(code, language) {
        buildAnnotatedString {
            val keywords = setOf(
                "fun", "val", "var", "class", "object", "interface", "if", "else",
                "when", "for", "while", "return", "import", "package", "private",
                "public", "protected", "internal", "override", "suspend", "data",
                "sealed", "enum", "companion", "const", "let", "function", "def",
                "self", "this", "true", "false", "null", "None", "True", "False",
                "async", "await", "try", "catch", "finally", "throw", "new",
                "static", "final", "abstract", "extends", "implements", "in", "is"
            )

            var i = 0
            while (i < code.length) {
                when {
                    code.startsWith("//", i) || code.startsWith("#", i) -> {
                        val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                        withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i] == '"' -> {
                        val end = findStringEnd(code, i, '"')
                        withStyle(SpanStyle(color = stringColor)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i] == '\'' -> {
                        val end = findStringEnd(code, i, '\'')
                        withStyle(SpanStyle(color = stringColor)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i].isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit()) -> {
                        val end = (i until code.length).firstOrNull {
                            !code[it].isDigit() && code[it] != '.'
                        } ?: code.length
                        withStyle(SpanStyle(color = numberColor)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i].isLetter() || code[i] == '_' -> {
                        val end = (i until code.length).firstOrNull {
                            !code[it].isLetterOrDigit() && code[it] != '_'
                        } ?: code.length
                        val word = code.substring(i, end)
                        if (word in keywords) {
                            withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) {
                                append(word)
                            }
                        } else {
                            withStyle(SpanStyle(color = defaultColor)) {
                                append(word)
                            }
                        }
                        i = end
                    }
                    else -> {
                        withStyle(SpanStyle(color = defaultColor)) {
                            append(code[i])
                        }
                        i++
                    }
                }
            }
        }
    }
}

private fun findStringEnd(code: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < code.length) {
        if (code[i] == '\\') {
            i += 2
            continue
        }
        if (code[i] == quote) {
            return i + 1
        }
        i++
    }
    return code.length
}

// ---------------------------------------------------------------------------
// Markdown Block Parser
// ---------------------------------------------------------------------------

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String) : MarkdownBlock()
    data class ListItem(val bullet: String, val text: String) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
}

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = content.split("\n")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        when {
            line.trimStart().startsWith("```") -> {
                val language = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), language))
                i++
            }
            line.startsWith("# ") -> {
                blocks.add(MarkdownBlock.Header(1, line.removePrefix("# ")))
                i++
            }
            line.startsWith("## ") -> {
                blocks.add(MarkdownBlock.Header(2, line.removePrefix("## ")))
                i++
            }
            line.startsWith("### ") -> {
                blocks.add(MarkdownBlock.Header(3, line.removePrefix("### ")))
                i++
            }
            line.startsWith("#### ") -> {
                blocks.add(MarkdownBlock.Header(3, line.removePrefix("#### ")))
                i++
            }
            line.trim().matches(Regex("^-{3,}$")) || line.trim().matches(Regex("^\\*{3,}$")) -> {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val bullet = if (line.trimStart().startsWith("- ")) "-" else "*"
                val text = line.trimStart().removePrefix("$bullet ").trim()
                blocks.add(MarkdownBlock.ListItem("  -", text))
                i++
            }
            line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                val match = Regex("^(\\d+)\\.\\s(.*)").find(line.trimStart())
                if (match != null) {
                    val num = match.groupValues[1]
                    val text = match.groupValues[2]
                    blocks.add(MarkdownBlock.ListItem("$num.", text))
                }
                i++
            }
            line.isBlank() -> {
                i++
            }
            else -> {
                val paragraphLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() &&
                    !lines[i].startsWith("#") &&
                    !lines[i].trimStart().startsWith("```") &&
                    !lines[i].trimStart().startsWith("- ") &&
                    !lines[i].trimStart().startsWith("* ") &&
                    !lines[i].trimStart().matches(Regex("^\\d+\\.\\s.*")) &&
                    !lines[i].trim().matches(Regex("^-{3,}$"))
                ) {
                    paragraphLines.add(lines[i])
                    i++
                }
                if (paragraphLines.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
                }
            }
        }
    }

    return blocks
}

// ---------------------------------------------------------------------------
// Chat Input Bar (with file attachments and voice input)
// ---------------------------------------------------------------------------

@Composable
private fun ChatInputBar(
    onSend: (String) -> Unit,
    isGenerating: Boolean,
    onCancel: () -> Unit,
    prefillText: String? = null,
    onPrefillConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<FileAttachment>() }
    var isListening by remember { mutableStateOf(false) }

    // Handle recipe prefill
    LaunchedEffect(prefillText) {
        if (prefillText != null) {
            text = prefillText
            onPrefillConsumed()
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileContent = readFileContent(context, uri)
            val fileName = getFileName(context, uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            if (fileContent != null) {
                attachments.add(
                    FileAttachment(
                        uri = uri,
                        name = fileName,
                        content = fileContent,
                        mimeType = mimeType
                    )
                )
            }
        }
    }

    // Voice input (SpeechRecognizer)
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val voiceText = remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onError(error: Int) {
                isListening = false
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceText.value = matches[0]
                }
                isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceText.value = matches[0]
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            speechRecognizer.destroy()
        }
    }

    // Insert voice transcription into text field
    LaunchedEffect(voiceText.value) {
        if (voiceText.value.isNotBlank()) {
            text = if (text.isBlank()) {
                voiceText.value
            } else {
                "$text ${voiceText.value}"
            }
            voiceText.value = ""
        }
    }

    // Microphone permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening(context, speechRecognizer)
            isListening = true
        }
    }

    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Attachment chips row
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        InputChip(
                            selected = false,
                            onClick = { attachments.removeAt(index) },
                            label = {
                                Text(
                                    text = attachment.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Input row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Goose...") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank() && !isGenerating) {
                                sendWithAttachments(text.trim(), attachments, onSend)
                                text = ""
                                attachments.clear()
                            }
                        }
                    )
                )

                // Voice input button
                if (isListening) {
                    // Pulsing indicator while recording
                    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500, easing = EaseInOut),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_alpha"
                    )
                    FilledIconButton(
                        onClick = {
                            speechRecognizer.stopListening()
                            isListening = false
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha),
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Stop recording"
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Attach file button
                IconButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "image/*",
                                "text/*"
                            )
                        )
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Send / Cancel button
                if (isGenerating) {
                    FilledIconButton(
                        onClick = onCancel,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Cancel generation"
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (text.isNotBlank() || attachments.isNotEmpty()) {
                                sendWithAttachments(text.trim(), attachments, onSend)
                                text = ""
                                attachments.clear()
                            }
                        },
                        enabled = text.isNotBlank() || attachments.isNotEmpty(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helper: Send message with file attachments included
// ---------------------------------------------------------------------------

private fun sendWithAttachments(
    text: String,
    attachments: List<FileAttachment>,
    onSend: (String) -> Unit
) {
    if (attachments.isEmpty()) {
        onSend(text)
    } else {
        val attachmentBlock = attachments.joinToString("\n\n") { attachment ->
            "[Attached file: ${attachment.name}]\n${attachment.content}"
        }
        val fullMessage = if (text.isNotBlank()) {
            "$text\n\n$attachmentBlock"
        } else {
            attachmentBlock
        }
        onSend(fullMessage)
    }
}

// ---------------------------------------------------------------------------
// Helper: Read file content from URI
// ---------------------------------------------------------------------------

private fun readFileContent(context: Context, uri: Uri): String? {
    return try {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        if (mimeType.startsWith("image/")) {
            // For images, return a placeholder indicating the image URI
            "[Image: $uri]"
        } else {
            // For text files, read content
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.use { it.readText() }
            // Limit content to prevent excessively large messages
            if (content.length > 50_000) {
                content.take(50_000) + "\n... [truncated, file too large]"
            } else {
                content
            }
        }
    } catch (e: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------
// Helper: Get file name from URI
// ---------------------------------------------------------------------------

private fun getFileName(context: Context, uri: Uri): String {
    var name = "file"
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                name = it.getString(nameIndex) ?: "file"
            }
        }
    }
    return name
}

// ---------------------------------------------------------------------------
// Helper: Start speech recognition
// ---------------------------------------------------------------------------

private fun startListening(context: Context, speechRecognizer: SpeechRecognizer) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    speechRecognizer.startListening(intent)
}
