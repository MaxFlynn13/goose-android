package io.github.gooseandroid.ui.workspace

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.gooseandroid.data.ProjectFolder
import io.github.gooseandroid.data.WorkspaceManager
import io.github.gooseandroid.data.WorkspaceStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private enum class ImportMode { ZIP, FOLDER }

/**
 * Represents a file/folder entry in the current directory listing.
 */
private data class FileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspaceManager = remember { WorkspaceManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Root workspace directory
    val workspaceRoot = remember { File(context.filesDir, "workspace").apply { mkdirs() } }

    // Current path state for folder navigation
    var currentPath by remember { mutableStateOf(workspaceRoot) }

    var projects by remember { mutableStateOf<List<ProjectFolder>>(emptyList()) }
    var stats by remember { mutableStateOf<WorkspaceStats?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf<ImportMode?>(null) }
    var projectNameInput by remember { mutableStateOf("") }
    var createFolderName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    // File entries in the current directory
    var fileEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }

    // File viewer dialog state
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var viewingFileContent by remember { mutableStateOf("") }

    // Whether we're in the root (project list) view or navigating inside
    val isAtRoot = currentPath.canonicalPath == workspaceRoot.canonicalPath

    fun refreshEntries() {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (isAtRoot) {
                    projects = workspaceManager.getProjects()
                    stats = workspaceManager.getStats()
                    fileEntries = emptyList()
                } else {
                    val files = currentPath.listFiles()?.toList() ?: emptyList()
                    fileEntries = files
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        .map { file ->
                            FileEntry(
                                file = file,
                                name = file.name,
                                isDirectory = file.isDirectory,
                                size = if (file.isFile) file.length() else calculateDirSize(file),
                                lastModified = file.lastModified()
                            )
                        }
                }
            }
        }
    }

    LaunchedEffect(currentPath) { refreshEntries() }

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
                        onSuccess = { refreshEntries(); snackbarHostState.showSnackbar("Imported \"${projectNameInput.trim()}\"") },
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
                    onSuccess = { refreshEntries(); snackbarHostState.showSnackbar("Imported \"${projectNameInput.trim()}\"") },
                    onFailure = { snackbarHostState.showSnackbar("Import failed: ${it.message}") }
                )
                projectNameInput = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isAtRoot) {
                        Text("Workspace")
                    } else {
                        Text(currentPath.name)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isAtRoot) {
                            onBack()
                        } else {
                            // Navigate up one directory level
                            val parent = currentPath.parentFile
                            if (parent != null && parent.canonicalPath.startsWith(workspaceRoot.canonicalPath)) {
                                currentPath = parent
                            } else {
                                currentPath = workspaceRoot
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (isAtRoot) {
                Box {
                    FloatingActionButton(onClick = { showImportMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Import")
                    }
                    DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Create Folder") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = {
                                showImportMenu = false
                                createFolderName = ""
                                showCreateFolderDialog = true
                            }
                        )
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
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Breadcrumb bar (shown when not at root)
            if (!isAtRoot) {
                BreadcrumbBar(
                    currentPath = currentPath,
                    rootPath = workspaceRoot,
                    onNavigate = { path -> currentPath = path },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (isAtRoot) {
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
                                onOpen = {
                                    // Navigate INTO the project folder
                                    currentPath = project.path
                                },
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
                                onDelete = { deleteTarget = project.path }
                            )
                        }
                    }
                }
            } else {
                // File browser view inside a folder
                if (fileEntries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.FolderOpen, contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Empty folder",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(fileEntries, key = { it.file.absolutePath }) { entry ->
                            FileEntryRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        // Navigate into the folder
                                        currentPath = entry.file
                                    } else {
                                        // Try to open/view text files
                                        if (isTextFile(entry.file)) {
                                            scope.launch {
                                                viewingFileContent = withContext(Dispatchers.IO) {
                                                    try {
                                                        entry.file.readText().take(100_000) // Limit to 100KB
                                                    } catch (e: Exception) {
                                                        "Error reading file: ${e.message}"
                                                    }
                                                }
                                                viewingFile = entry.file
                                            }
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Cannot preview binary file: ${entry.name}")
                                            }
                                        }
                                    }
                                },
                                onLongPress = {
                                    deleteTarget = entry.file
                                }
                            )
                        }
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
    deleteTarget?.let { targetFile ->
        val isDir = targetFile.isDirectory
        val displayName = targetFile.name
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (isDir) "Delete Folder" else "Delete File") },
            text = {
                Text(
                    if (isDir) "Delete \"$displayName\" and all its contents? This cannot be undone."
                    else "Delete \"$displayName\"? This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (isDir) {
                                    targetFile.deleteRecursively()
                                } else {
                                    targetFile.delete()
                                }
                            }
                            deleteTarget = null
                            refreshEntries()
                            snackbarHostState.showSnackbar("Deleted \"$displayName\"")
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

    // Create new folder dialog
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = createFolderName,
                    onValueChange = { createFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    supportingText = { Text("Letters, numbers, hyphens, underscores") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createFolderName.trim()
                        showCreateFolderDialog = false
                        scope.launch {
                            if (isAtRoot) {
                                val result = workspaceManager.createProject(name)
                                result.fold(
                                    onSuccess = {
                                        refreshEntries()
                                        snackbarHostState.showSnackbar("Created \"$name\"")
                                    },
                                    onFailure = {
                                        snackbarHostState.showSnackbar("Failed: ${it.message}")
                                    }
                                )
                            } else {
                                // Create folder in current directory
                                withContext(Dispatchers.IO) {
                                    File(currentPath, name).mkdirs()
                                }
                                refreshEntries()
                                snackbarHostState.showSnackbar("Created \"$name\"")
                            }
                        }
                    },
                    enabled = createFolderName.isNotBlank() &&
                            workspaceManager.isValidProjectName(createFolderName.trim())
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    // File viewer dialog
    viewingFile?.let { file ->
        FileViewerDialog(
            fileName = file.name,
            content = viewingFileContent,
            onDismiss = {
                viewingFile = null
                viewingFileContent = ""
            }
        )
    }
}

// ─── Breadcrumb Bar ─────────────────────────────────────────────────────────

@Composable
private fun BreadcrumbBar(
    currentPath: File,
    rootPath: File,
    onNavigate: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val relativePath = currentPath.relativeTo(rootPath).path
    val segments = relativePath.split(File.separator).filter { it.isNotBlank() }

    // Auto-scroll to end when path changes
    LaunchedEffect(currentPath) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Root
        TextButton(
            onClick = { onNavigate(rootPath) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Home, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Workspace", style = MaterialTheme.typography.labelMedium)
        }

        // Path segments
        var accumulatedPath = rootPath
        for ((index, segment) in segments.withIndex()) {
            accumulatedPath = File(accumulatedPath, segment)
            val navTarget = accumulatedPath // capture for lambda

            Icon(
                Icons.Default.ChevronRight,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (index == segments.lastIndex) {
                // Current folder — not clickable
                Text(
                    segment,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else {
                TextButton(
                    onClick = { onNavigate(navTarget) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(segment, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ─── File Entry Row ─────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                if (entry.isDirectory) Icons.Default.Folder
                else getFileIcon(entry.name),
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            // Name and metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        Formatter.formatShortFileSize(context, entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        dateFormat.format(Date(entry.lastModified)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Chevron for directories
            if (entry.isDirectory) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open folder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ─── File Viewer Dialog ─────────────────────────────────────────────────────

@Composable
private fun FileViewerDialog(
    fileName: String,
    content: String,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState)
            ) {
                SelectionContainer {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    // Simple wrapper — in a real app you'd use the actual SelectionContainer from foundation
    content()
}

// ─── Storage Indicator ──────────────────────────────────────────────────────

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

// ─── Project Card ───────────────────────────────────────────────────────────

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

// ─── Utility functions ──────────────────────────────────────────────────────

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ZIP"))
}

private fun calculateDirSize(dir: File): Long {
    var size = 0L
    dir.walkTopDown().forEach { file ->
        if (file.isFile) size += file.length()
    }
    return size
}

private fun isTextFile(file: File): Boolean {
    val textExtensions = setOf(
        "txt", "md", "json", "xml", "html", "css", "js", "ts", "kt", "java",
        "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "sh", "bash", "zsh",
        "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "csv", "sql",
        "gradle", "properties", "gitignore", "dockerfile", "makefile",
        "swift", "dart", "lua", "r", "scala", "clj", "ex", "exs", "erl",
        "hs", "ml", "fs", "vim", "el", "lisp", "tex", "bib", "svg"
    )
    val ext = file.extension.lowercase()
    if (ext in textExtensions) return true

    // Also check files without extension (README, Makefile, etc.)
    if (ext.isBlank()) {
        val name = file.name.lowercase()
        return name in setOf("readme", "makefile", "dockerfile", "license", "changelog", "authors", "todo")
    }

    // Try reading first few bytes to check if it's text
    return try {
        val buffer = ByteArray(512)
        val bytesRead = file.inputStream().use { it.read(buffer) }
        if (bytesRead <= 0) return true // empty file counts as text
        buffer.take(bytesRead).all { byte ->
            val b = byte.toInt() and 0xFF
            b in 9..13 || b in 32..126 || b > 127
        }
    } catch (_: Exception) {
        false
    }
}

private fun getFileIcon(fileName: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "java", "py", "js", "ts", "go", "rs", "c", "cpp", "swift" -> Icons.Default.Code
        "json", "xml", "yaml", "yml", "toml" -> Icons.Default.DataObject
        "md", "txt", "doc", "docx" -> Icons.Default.Description
        "png", "jpg", "jpeg", "gif", "svg", "webp" -> Icons.Default.Image
        "zip", "tar", "gz", "rar" -> Icons.Default.FolderZip
        "sh", "bash", "zsh" -> Icons.Default.Terminal
        else -> Icons.Default.InsertDriveFile
    }
}
