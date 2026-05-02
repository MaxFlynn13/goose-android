package io.github.gooseandroid.ui.brain

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gooseandroid.brain.*
import kotlinx.coroutines.launch
import android.widget.Toast

/**
 * Brain screen — view, search, create, and manage knowledge nodes.
 * Represented by a wireframe brain icon in the side panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val brainDb = remember { BrainDatabase(context) }
    val scope = rememberCoroutineScope()

    var nodes by remember { mutableStateOf<List<BrainNode>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportExportSheet by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf<BrainNode?>(null) }
    var nodeCount by remember { mutableStateOf(0) }
    var allTags by remember { mutableStateOf<List<String>>(emptyList()) }

    // Load nodes
    LaunchedEffect(Unit) {
        try {
            nodes = brainDb.getAllNodes()
            nodeCount = brainDb.getNodeCount()
            allTags = brainDb.getAllTags()
        } catch (e: Exception) {
            Log.e("BrainScreen", "Failed to load nodes", e)
        }
    }

    // React to changes
    LaunchedEffect(Unit) {
        try {
            brainDb.nodesChanged.collect {
                nodes = if (searchQuery.isBlank()) {
                    brainDb.getAllNodes()
                } else {
                    brainDb.searchNodes(searchQuery)
                }
                nodeCount = brainDb.getNodeCount()
                allTags = brainDb.getAllTags()
            }
        } catch (e: Exception) {
            Log.e("BrainScreen", "Error in nodesChanged flow", e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Brain")
                        Text(
                            "$nodeCount nodes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showImportExportSheet = true }) {
                        Icon(Icons.Default.SwapVert, "Import/Export")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, "Create Node")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { query ->
                    searchQuery = query
                    scope.launch {
                        nodes = if (query.isBlank()) {
                            brainDb.getAllNodes()
                        } else {
                            brainDb.searchNodes(query)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search brain...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            scope.launch { nodes = brainDb.getAllNodes() }
                        }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            // Tags row
            if (allTags.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    // Using a Row inside would be better but keeping it simple
                }
            }

            // Nodes list
            if (nodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (searchQuery.isBlank()) "Brain is empty"
                            else "No results for \"$searchQuery\"",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Ask Goose to \"remember\" something, or create a node manually.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create First Node")
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(nodes, key = { it.id }) { node ->
                        NodeCard(
                            node = node,
                            onClick = { selectedNode = node },
                            onPin = {
                                scope.launch {
                                    brainDb.updateNode(node.id, pinned = !node.pinned)
                                }
                            },
                            onDelete = {
                                scope.launch { brainDb.deleteNode(node.id) }
                            }
                        )
                    }
                }
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        CreateNodeDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, content, type, tags ->
                scope.launch {
                    brainDb.createNode(title, content, type, tags)
                    showCreateDialog = false
                }
            }
        )
    }

    // Edit dialog
    selectedNode?.let { node ->
        EditNodeDialog(
            node = node,
            onDismiss = { selectedNode = null },
            onSave = { title, content, tags ->
                scope.launch {
                    brainDb.updateNode(node.id, title = title, content = content, tags = tags)
                    selectedNode = null
                }
            }
        )
    }

    // Import/Export bottom sheet
    if (showImportExportSheet) {
        ImportExportSheet(
            brainDb = brainDb,
            onDismiss = { showImportExportSheet = false }
        )
    }
}

@Composable
private fun NodeCard(
    node: BrainNode,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (node.pinned) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            node.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        node.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row {
                    IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (node.pinned) Icons.Default.PushPin else Icons.Default.PushPin,
                            contentDescription = "Pin",
                            modifier = Modifier.size(16.dp),
                            tint = if (node.pinned) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Tags
            if (node.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.tags.take(4).forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                    if (node.tags.size > 4) {
                        Text(
                            "+${node.tags.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateNodeDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, NodeType, List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(NodeType.NOTE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Node") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    onCreate(title, content, selectedType, tags)
                },
                enabled = title.isNotBlank() && content.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditNodeDialog(
    node: BrainNode,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>) -> Unit
) {
    var title by remember { mutableStateOf(node.title) }
    var content by remember { mutableStateOf(node.content) }
    var tagsText by remember { mutableStateOf(node.tags.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Node") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    onSave(title, content, tags)
                },
                enabled = title.isNotBlank() && content.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportExportSheet(
    brainDb: BrainDatabase,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Import / Export",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Export your entire brain to transfer between devices. " +
                "Import a previously exported brain to restore all knowledge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    scope.launch {
                        val file = brainDb.exportToFile(context)
                        // TODO: Share via Android share sheet
                        Log.d("Brain", "Exported to: ${file.absolutePath}")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Brain (JSON)")
            }

            OutlinedButton(
                onClick = {
                    // TODO: Open file picker for import
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Brain (JSON)")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}


