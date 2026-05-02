package io.github.gooseandroid.ui.workspace

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.gooseandroid.data.ProjectFolder
import io.github.gooseandroid.data.WorkspaceManager
import io.github.gooseandroid.data.WorkspaceStats
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private enum class ImportMode { ZIP, FOLDER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspaceManager = remember { WorkspaceManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    var projects by remember { mutableStateOf<List<ProjectFolder>>(emptyList()) }
    var stats by remember { mutableStateOf<WorkspaceStats?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf<ImportMode?>(null) }
    var projectNameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProjectFolder?>(null) }

    fun refresh() {
        scope.launch {
            projects = workspaceManager.getProjects()
            stats = workspaceManager.getStats()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && projectNameInput.isNotBlank()) {
            scope.launch {
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    val result = workspaceManager.importZip(stream, projectNameInput.trim())
                    stream.close()
                    result.fold(
                        onSuccess = { refresh(); snackbarHostState.showSnackbar("Imported \"${projectNameInput.trim()}\"") },
                        onFailure = { snackbarHostState.showSnackbar("Import failed: ${it.message}") }
                    )
                }
                projectNameInput = ""
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && projectNameInput.isNotBlank()) {
            scope.launch {
                val result = workspaceManager.importFromUri(context, uri, projectNameInput.trim())
                result.fold(
                    onSuccess = { refresh(); snackbarHostState.showSnackbar("Imported \"${projectNameInput.trim()}\"") },
                    onFailure = { snackbarHostState.showSnackbar("Import failed: ${it.message}") }
                )
                projectNameInput = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showImportMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Import")
                }
                DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Import ZIP") },
                        leadingIcon = { Icon(Icons.Default.FolderZip, null) },
                        onClick = {
                            showImportMenu = false
                            importMode = ImportMode.ZIP
                            projectNameInput = ""
                            showNameDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Import Folder") },
                        leadingIcon = { Icon(Icons.Default.Folder, null) },
                        onClick = {
                            showImportMenu = false
                            importMode = ImportMode.FOLDER
                            projectNameInput = ""
                            showNameDialog = true
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Storage indicator
            stats?.let { s ->
                StorageIndicator(s.usedBytes, s.totalQuotaBytes, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen, contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No projects yet.\nImport a ZIP or folder to get started.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.name }) { project ->
                        ProjectCard(
                            project = project,
                            onOpen = { /* Navigate to file browser */ },
                            onExport = {
                                scope.launch {
                                    workspaceManager.exportProject(project.name).fold(
                                        onSuccess = { file ->
                                            val action = snackbarHostState.showSnackbar(
                                                message = "Exported ${project.name}.zip",
                                                actionLabel = "Share",
                                                duration = SnackbarDuration.Long
                                            )
                                            if (action == SnackbarResult.ActionPerformed) {
                                                shareFile(context, file)
                                            }
                                        },
                                        onFailure = { snackbarHostState.showSnackbar("Export failed: ${it.message}") }
                                    )
                                }
                            },
                            onDelete = { deleteTarget = project }
                        )
                    }
                }
            }
        }
    }

    // Project name dialog
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false; importMode = null },
            title = { Text("Project Name") },
            text = {
                OutlinedTextField(
                    value = projectNameInput,
                    onValueChange = { projectNameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = { Text("Letters, numbers, hyphens, underscores") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNameDialog = false
                        when (importMode) {
                            ImportMode.ZIP -> zipPickerLauncher.launch(arrayOf("application/zip"))
                            ImportMode.FOLDER -> folderPickerLauncher.launch(null)
                            null -> {}
                        }
                    },
                    enabled = projectNameInput.isNotBlank() &&
                            workspaceManager.isValidProjectName(projectNameInput.trim())
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false; importMode = null }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Project") },
            text = { Text("Delete \"${project.name}\" and all its files? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            workspaceManager.deleteProject(project.name)
                            deleteTarget = null
                            refresh()
                            snackbarHostState.showSnackbar("Deleted \"${project.name}\"")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StorageIndicator(usedBytes: Long, totalBytes: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val usedFmt = Formatter.formatShortFileSize(context, usedBytes)
    val totalFmt = Formatter.formatShortFileSize(context, totalBytes)
    val progress = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Storage", style = MaterialTheme.typography.labelMedium)
            Text(
                "$usedFmt / $totalFmt",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (progress > 0.9f) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectFolder,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Formatter.formatShortFileSize(context, project.sizeBytes)} · " +
                            "${project.fileCount} files · " +
                            dateFormat.format(Date(project.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        onClick = { menuExpanded = false; onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text("Export as ZIP") },
                        leadingIcon = { Icon(Icons.Default.Archive, null) },
                        onClick = { menuExpanded = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ZIP"))
}
