package io.github.gooseandroid.ui.history

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gooseandroid.data.models.SessionInfo
import io.github.gooseandroid.data.models.getProviderById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "HistoryScreen"
private const val SESSIONS_FILE = "sessions.json"

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// Date grouping
// ---------------------------------------------------------------------------

private enum class DateGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    OLDER("Older")
}

private fun startOfDay(offsetDays: Int = 0): Long {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offsetDays)
    }
    return cal.timeInMillis
}

private fun groupByDate(sessions: List<SessionInfo>): Map<String, List<SessionInfo>> {
    val todayStart = startOfDay()
    val yesterdayStart = startOfDay(-1)
    val weekAgoStart = startOfDay(-7)

    val grouped = linkedMapOf<String, MutableList<SessionInfo>>()
    for (group in DateGroup.entries) {
        grouped[group.label] = mutableListOf()
    }

    val sorted = sessions.sortedByDescending { it.createdAt }
    for (session in sorted) {
        val ts = session.createdAt
        val bucket = when {
            ts >= todayStart -> DateGroup.TODAY
            ts >= yesterdayStart -> DateGroup.YESTERDAY
            ts >= weekAgoStart -> DateGroup.THIS_WEEK
            else -> DateGroup.OLDER
        }
        grouped[bucket.label]!!.add(session)
    }

    // Remove empty groups
    return grouped.filterValues { it.isNotEmpty() }
}

// ---------------------------------------------------------------------------
// File I/O helpers
// ---------------------------------------------------------------------------

private fun readSessions(context: Context): List<SessionInfo> {
    return try {
        val file = File(context.filesDir, SESSIONS_FILE)
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        json.decodeFromString<List<SessionInfo>>(text)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read sessions", e)
        emptyList()
    }
}

private fun writeSessions(context: Context, sessions: List<SessionInfo>) {
    try {
        val file = File(context.filesDir, SESSIONS_FILE)
        file.writeText(json.encodeToString(sessions))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to write sessions", e)
    }
}

private fun deleteSession(context: Context, sessionId: String): List<SessionInfo> {
    val current = readSessions(context).toMutableList()
    current.removeAll { it.id == sessionId }
    writeSessions(context, current)
    return current
}

private fun renameSession(context: Context, sessionId: String, newTitle: String): List<SessionInfo> {
    val current = readSessions(context).map { session ->
        if (session.id == sessionId) session.copy(title = newTitle) else session
    }
    writeSessions(context, current)
    return current
}

private fun exportSessionJson(context: Context, session: SessionInfo) {
    val exportData = json.encodeToString(session)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TEXT, exportData)
        putExtra(Intent.EXTRA_SUBJECT, "Goose Session: ${session.title}")
    }
    val chooser = Intent.createChooser(sendIntent, "Export Session")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

// ---------------------------------------------------------------------------
// Timestamp formatting
// ---------------------------------------------------------------------------

private fun formatTimestamp(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts

    // Less than 1 minute
    if (diff < 60_000L) return "Just now"

    // Less than 1 hour
    if (diff < 3_600_000L) {
        val mins = (diff / 60_000L).toInt()
        return "$mins min ago"
    }

    // Same day -- show time
    val todayStart = startOfDay()
    if (ts >= todayStart) {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
    }

    // Yesterday
    val yesterdayStart = startOfDay(-1)
    if (ts >= yesterdayStart) {
        return "Yesterday, " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
    }

    // This year
    val cal = Calendar.getInstance()
    val currentYear = cal.get(Calendar.YEAR)
    cal.timeInMillis = ts
    val tsYear = cal.get(Calendar.YEAR)

    return if (tsYear == currentYear) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts))
    }
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onResumeSession: (String) -> Unit,
    onNewChat: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }

    // Dialog state
    var deleteTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Load sessions on first composition
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sessions = readSessions(context)
        }
        isLoading = false
    }

    // Filtered sessions
    val filteredSessions by remember(sessions, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                sessions
            } else {
                val q = searchQuery.lowercase()
                sessions.filter { session ->
                    session.title.lowercase().contains(q) ||
                    session.lastMessage.lowercase().contains(q)
                }
            }
        }
    }

    val groupedSessions by remember(filteredSessions) {
        derivedStateOf { groupByDate(filteredSessions) }
    }

    // ---- Delete confirmation dialog ----
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Delete Session") },
            text = {
                Text("Permanently delete \"${deleteTarget!!.title}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTarget!!
                        deleteTarget = null
                        scope.launch {
                            sessions = withContext(Dispatchers.IO) {
                                deleteSession(context, target.id)
                            }
                            snackbarHostState.showSnackbar("Session deleted")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ---- Rename dialog ----
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Session title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (renameText.isNotBlank()) {
                                val target = renameTarget!!
                                renameTarget = null
                                scope.launch {
                                    sessions = withContext(Dispatchers.IO) {
                                        renameSession(context, target.id, renameText.trim())
                                    }
                                }
                            }
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            val target = renameTarget!!
                            renameTarget = null
                            scope.launch {
                                sessions = withContext(Dispatchers.IO) {
                                    renameSession(context, target.id, renameText.trim())
                                }
                            }
                        }
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            if (searchActive) Icons.Default.SearchOff else Icons.Default.Search,
                            contentDescription = if (searchActive) "Close search" else "Search"
                        )
                    }
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Add, contentDescription = "New Chat")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---- Search bar ----
            AnimatedVisibility(
                visible = searchActive,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val focusManager = LocalFocusManager.current
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search sessions...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    shape = MaterialTheme.shapes.medium
                )
            }

            // ---- Content ----
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                sessions.isEmpty() -> {
                    EmptyState(onNewChat = onNewChat)
                }

                filteredSessions.isEmpty() && searchQuery.isNotBlank() -> {
                    SearchEmptyState(query = searchQuery)
                }

                else -> {
                    SessionList(
                        groupedSessions = groupedSessions,
                        onResume = onResumeSession,
                        onRename = { session ->
                            renameText = session.title
                            renameTarget = session
                        },
                        onExport = { session ->
                            exportSessionJson(context, session)
                        },
                        onDelete = { session ->
                            deleteTarget = session
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session list with date group headers
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionList(
    groupedSessions: Map<String, List<SessionInfo>>,
    onResume: (String) -> Unit,
    onRename: (SessionInfo) -> Unit,
    onExport: (SessionInfo) -> Unit,
    onDelete: (SessionInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groupedSessions.forEach { (groupLabel, sessionsInGroup) ->
            // Sticky date header
            stickyHeader(key = groupLabel) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Text(
                        text = groupLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }

            items(
                items = sessionsInGroup,
                key = { it.id }
            ) { session ->
                SessionCard(
                    session = session,
                    onResume = { onResume(session.id) },
                    onRename = { onRename(session) },
                    onExport = { onExport(session) },
                    onDelete = { onDelete(session) },
                    modifier = Modifier.animateItemPlacement()
                )
            }

            // Spacer between groups
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Session card
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: SessionInfo,
    onResume: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onResume,
                onLongClick = { menuExpanded = true }
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Left content
            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = session.title.ifBlank { "Untitled Session" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Last message preview
                if (session.lastMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = session.lastMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Metadata row: timestamp, message count, provider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Timestamp
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatTimestamp(session.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Message count
                    if (session.messageCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${session.messageCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Provider badge
                    val providerName = resolveProviderName(session.providerId, session.modelId)
                    if (providerName.isNotBlank()) {
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = providerName,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp),
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }
            }

            // Overflow menu button
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Session options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Export as JSON") },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Share, contentDescription = null)
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Provider name resolution
// ---------------------------------------------------------------------------

@Composable
private fun resolveProviderName(providerId: String, modelId: String): String {
    if (providerId.isBlank() && modelId.isBlank()) return ""
    val provider = getProviderById(providerId)
    if (provider != null) {
        val model = provider.models.find { it.id == modelId }
        return model?.displayName ?: provider.displayName
    }
    // Fallback: show the model ID or provider ID directly
    return modelId.ifBlank { providerId }
}

// ---------------------------------------------------------------------------
// Empty states
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(onNewChat: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No chat history yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Your conversations will appear here.\nSessions persist even when you navigate away.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onNewChat) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start a new chat")
            }
        }
    }
}

@Composable
private fun SearchEmptyState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No results found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "No sessions match \"$query\".\nTry a different search term.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
