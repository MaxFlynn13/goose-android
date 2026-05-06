package io.github.gooseandroid.ui.workspace

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ═════════════════════════════════════════════════════════════════════════════
// Data Models
// ═════════════════════════════════════════════════════════════════════════════

private data class FileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

private enum class CreateMode { FILE, FOLDER }

private data class ClipboardItem(val file: File, val isCut: Boolean)

// ═════════════════════════════════════════════════════════════════════════════
// Main Screen
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val workspaceRoot = remember { File(context.filesDir, "workspace").apply { mkdirs() } }
    var currentPath by remember { mutableStateOf(workspaceRoot) }
    var fileEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var usedBytes by remember { mutableLongStateOf(0L) }
    val quotaBytes = 500L * 1024L * 1024L // 500MB

    // Dialog states
    var showCreateSheet by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf<CreateMode?>(null) }
    var createNameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var contextMenuTarget by remember { mutableStateOf<File?>(null) }

    // Editor state
    var editingFile by remember { mutableStateOf<File?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var editorOriginal by remember { mutableStateOf("") }
    var editorDirty by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }

    // Clipboard for move
    var clipboard by remember { mutableStateOf<ClipboardItem?>(null) }

    // ─── Refresh logic ──────────────────────────────────────────────────────
    fun refreshEntries() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val files = currentPath.listFiles()?.toList() ?: emptyList()
                fileEntries = files
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { file ->
                        FileEntry(
                            file = file,
                            name = file.name,
                            isDirectory = file.isDirectory,
                            size = if (file.isFile) file.length() else calcDirSize(file),
                            lastModified = file.lastModified()
                        )
                    }
                usedBytes = calcDirSize(workspaceRoot)
            }
        }
    }

    LaunchedEffect(currentPath) { refreshEntries() }

    // ─── SAF Launchers ──────────────────────────────────────────────────────
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_file"
                    val destFile = File(currentPath, displayName)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    refreshEntries()
                    snackbarHostState.showSnackbar("Imported \"${destFile.name}\"")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Import failed: ${e.message}")
                }
            }
        }
    }

    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            ZipInputStream(input.buffered()).use { zis ->
                                var entry: ZipEntry? = zis.nextEntry
                                while (entry != null) {
                                    val outFile = File(currentPath, entry.name).canonicalFile
                                    if (!outFile.canonicalPath.startsWith(currentPath.canonicalPath)) {
                                        zis.closeEntry()
                                        entry = zis.nextEntry
                                        continue
                                    }
                                    if (entry.isDirectory) {
                                        outFile.mkdirs()
                                    } else {
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { fos ->
                                            zis.copyTo(fos)
                                        }
                                    }
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                }
                            }
                        }
                    }
                    refreshEntries()
                    snackbarHostState.showSnackbar("ZIP extracted successfully")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("ZIP import failed: ${e.message}")
                }
            }
        }
    }

    // ─── Back handler ───────────────────────────────────────────────────────
    BackHandler {
        if (editingFile != null) {
            editingFile = null
            editorError = null
        } else if (currentPath.canonicalPath != workspaceRoot.canonicalPath) {
            val parent = currentPath.parentFile
            currentPath = if (parent != null && parent.canonicalPath.startsWith(workspaceRoot.canonicalPath)) {
                parent
            } else {
                workspaceRoot
            }
        } else {
            onBack()
        }
    }

    // ─── Editor Screen ──────────────────────────────────────────────────────
    editingFile?.let { file ->
        FileEditorScreen(
            file = file,
            content = editorContent,
            originalContent = editorOriginal,
            isDirty = editorDirty,
            error = editorError,
            onContentChange = { newContent ->
                editorContent = newContent
                editorDirty = newContent != editorOriginal
            },
            onSave = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { file.writeText(editorContent) }
                        editorOriginal = editorContent
                        editorDirty = false
                        snackbarHostState.showSnackbar("Saved")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Save failed: ${e.message}")
                    }
                }
            },
            onRevert = {
                editorContent = editorOriginal
                editorDirty = false
            },
            onClose = {
                editingFile = null
                editorError = null
                refreshEntries()
            }
        )
        return
    }

    // ─── Main File Browser ──────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath.canonicalPath != workspaceRoot.canonicalPath) {
                            val parent = currentPath.parentFile
                            currentPath = if (parent != null && parent.canonicalPath.startsWith(workspaceRoot.canonicalPath)) {
                                parent
                            } else {
                                workspaceRoot
                            }
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Paste button when clipboard has content
                    clipboard?.let { clip ->
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    val dest = File(currentPath, clip.file.name)
                                    withContext(Dispatchers.IO) {
                                        if (clip.file.isDirectory) {
                                            clip.file.copyRecursively(dest, overwrite = true)
                                        } else {
                                            clip.file.copyTo(dest, overwrite = true)
                                        }
                                        if (clip.isCut) clip.file.deleteRecursively()
                                    }
                                    clipboard = null
                                    refreshEntries()
                                    snackbarHostState.showSnackbar("Pasted \"${clip.file.name}\"")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Paste failed: ${e.message}")
                                }
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Breadcrumb bar
            BreadcrumbBar(
                currentPath = currentPath,
                rootPath = workspaceRoot,
                onNavigate = { currentPath = it }
            )

            // Storage info
            StorageBar(usedBytes = usedBytes, quotaBytes = quotaBytes)

            HorizontalDivider()

            // File list
            if (fileEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen, contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Empty folder",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap + to create files or import content",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(fileEntries, key = { it.file.absolutePath }) { entry ->
                        FileEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    currentPath = entry.file
                                } else {
                                    // Open file in editor
                                    scope.launch {
                                        openFileForEditing(
                                            file = entry.file,
                                            onSuccess = { content ->
                                                editorOriginal = content
                                                editorContent = content
                                                editorDirty = false
                                                editorError = null
                                                editingFile = entry.file
                                            },
                                            onBinary = {
                                                editorOriginal = ""
                                                editorContent = ""
                                                editorDirty = false
                                                editorError = "Binary file — cannot edit"
                                                editingFile = entry.file
                                            },
                                            onTooLarge = { size ->
                                                editorOriginal = ""
                                                editorContent = ""
                                                editorDirty = false
                                                editorError = "File too large (${Formatter.formatShortFileSize(context, size)}). Max 1MB for editing."
                                                editingFile = entry.file
                                            }
                                        )
                                    }
                                }
                            },
                            onLongPress = { contextMenuTarget = entry.file }
                        )
                    }
                }
            }
        }
    }

    // ─── Create Bottom Sheet ────────────────────────────────────────────────
    if (showCreateSheet) {
        ModalBottomSheet(onDismissRequest = { showCreateSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Create", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                ListItem(
                    headlineContent = { Text("New File") },
                    leadingContent = { Icon(Icons.Default.NoteAdd, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        showCreateSheet = false
                        createMode = CreateMode.FILE
                        createNameInput = ""
                    })
                )
                ListItem(
                    headlineContent = { Text("New Folder") },
                    leadingContent = { Icon(Icons.Default.CreateNewFolder, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        showCreateSheet = false
                        createMode = CreateMode.FOLDER
                        createNameInput = ""
                    })
                )
                ListItem(
                    headlineContent = { Text("Import File") },
                    leadingContent = { Icon(Icons.Default.UploadFile, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        showCreateSheet = false
                        importFileLauncher.launch(arrayOf("*/*"))
                    })
                )
                ListItem(
                    headlineContent = { Text("Import ZIP (extract here)") },
                    leadingContent = { Icon(Icons.Default.FolderZip, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        showCreateSheet = false
                        importZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    })
                )
                ListItem(
                    headlineContent = { Text("Export folder as ZIP") },
                    leadingContent = { Icon(Icons.Default.Archive, null) },
                    modifier = Modifier.combinedClickable(onClick = {
                        showCreateSheet = false
                        scope.launch {
                            try {
                                val zipFile = withContext(Dispatchers.IO) {
                                    exportFolderAsZip(currentPath, context.cacheDir)
                                }
                                shareFile(context, zipFile)
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Export failed: ${e.message}")
                            }
                        }
                    })
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ─── Create File/Folder Dialog ──────────────────────────────────────────
    createMode?.let { mode ->
        val title = if (mode == CreateMode.FILE) "New File" else "New Folder"
        val label = if (mode == CreateMode.FILE) "Filename (e.g. main.py)" else "Folder name"

        AlertDialog(
            onDismissRequest = { createMode = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = createNameInput,
                    onValueChange = { createNameInput = it },
                    label = { Text(label) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createNameInput.trim()
                        createMode = null
                        if (name.isNotBlank()) {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val target = File(currentPath, name)
                                        if (mode == CreateMode.FOLDER) {
                                            target.mkdirs()
                                        } else {
                                            target.parentFile?.mkdirs()
                                            target.createNewFile()
                                        }
                                    }
                                    refreshEntries()
                                    snackbarHostState.showSnackbar("Created \"$name\"")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Failed: ${e.message}")
                                }
                            }
                        }
                    },
                    enabled = createNameInput.isNotBlank() && !createNameInput.contains("/") && !createNameInput.contains("\\")
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { createMode = null }) { Text("Cancel") }
            }
        )
    }

    // ─── Context Menu Dialog ────────────────────────────────────────────────
    contextMenuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { contextMenuTarget = null },
            title = { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        contextMenuTarget = null
                        renameTarget = target
                        renameInput = target.name
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Rename")
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        contextMenuTarget = null
                        clipboard = ClipboardItem(target, isCut = true)
                        scope.launch { snackbarHostState.showSnackbar("Cut \"${target.name}\" — navigate and paste") }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cut (Move)")
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        contextMenuTarget = null
                        clipboard = ClipboardItem(target, isCut = false)
                        scope.launch { snackbarHostState.showSnackbar("Copied \"${target.name}\" — navigate and paste") }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy")
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            contextMenuTarget = null
                            deleteTarget = target
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete")
                        Spacer(Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { contextMenuTarget = null }) { Text("Close") }
            }
        )
    }

    // ─── Delete Confirmation ────────────────────────────────────────────────
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (target.isDirectory) "Delete Folder" else "Delete File") },
            text = {
                Text(
                    if (target.isDirectory) "Delete \"${target.name}\" and all contents? This cannot be undone."
                    else "Delete \"${target.name}\"? This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = target.name
                        scope.launch {
                            withContext(Dispatchers.IO) { target.deleteRecursively() }
                            deleteTarget = null
                            refreshEntries()
                            snackbarHostState.showSnackbar("Deleted \"$name\"")
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

    // ─── Rename Dialog ──────────────────────────────────────────────────────
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("New name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameInput.trim()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val dest = File(target.parentFile, newName)
                                    if (!target.renameTo(dest)) {
                                        // Fallback: copy + delete
                                        if (target.isDirectory) {
                                            target.copyRecursively(dest, overwrite = true)
                                            target.deleteRecursively()
                                        } else {
                                            target.copyTo(dest, overwrite = true)
                                            target.delete()
                                        }
                                    }
                                }
                                renameTarget = null
                                refreshEntries()
                                snackbarHostState.showSnackbar("Renamed to \"$newName\"")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Rename failed: ${e.message}")
                            }
                        }
                    },
                    enabled = renameInput.isNotBlank() && !renameInput.contains("/") && !renameInput.contains("\\")
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// File Editor Screen (full-screen)
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorScreen(
    file: File,
    content: String,
    originalContent: String,
    isDirty: Boolean,
    error: String?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    onClose: () -> Unit
) {
    val scrollState = rememberScrollState()
    val textColor = contentColorFor(MaterialTheme.colorScheme.surface)
    val isCode = isCodeFile(file.name)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isDirty) {
                            Text(
                                "Modified",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close editor")
                    }
                },
                actions = {
                    if (error == null) {
                        if (isDirty) {
                            IconButton(onClick = onRevert) {
                                Icon(Icons.Default.Undo, contentDescription = "Revert")
                            }
                        }
                        IconButton(onClick = onSave, enabled = isDirty) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Block, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
            // Line-numbered editor
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Line numbers
                val lines = content.split("\n")
                val lineCount = lines.size.coerceAtLeast(1)
                val lineNumWidth = lineCount.toString().length

                Box(
                    modifier = Modifier
                        .width((lineNumWidth * 10 + 16).dp)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                ) {
                    Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp)) {
                        for (i in 1..lineCount) {
                            Text(
                                text = i.toString().padStart(lineNumWidth),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }

                VerticalDivider()

                // Text editor area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = content,
                        onValueChange = onContentChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = getEditorTextColor(file.name, textColor)
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Breadcrumb Bar
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun BreadcrumbBar(
    currentPath: File,
    rootPath: File,
    onNavigate: (File) -> Unit
) {
    val scrollState = rememberScrollState()
    val isRoot = currentPath.canonicalPath == rootPath.canonicalPath
    val relativePath = if (isRoot) "" else currentPath.relativeTo(rootPath).path
    val segments = relativePath.split(File.separator).filter { it.isNotBlank() }

    LaunchedEffect(currentPath) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Home segment
        TextButton(
            onClick = { onNavigate(rootPath) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(Icons.Default.Home, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("home", style = MaterialTheme.typography.labelMedium)
        }

        var accumulatedPath = rootPath
        for ((index, segment) in segments.withIndex()) {
            accumulatedPath = File(accumulatedPath, segment)
            val navTarget = accumulatedPath

            Icon(
                Icons.Default.ChevronRight, null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (index == segments.lastIndex) {
                Text(
                    segment,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else {
                TextButton(
                    onClick = { onNavigate(navTarget) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(segment, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Storage Bar
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun StorageBar(usedBytes: Long, quotaBytes: Long) {
    val context = LocalContext.current
    val usedFmt = Formatter.formatShortFileSize(context, usedBytes)
    val totalFmt = Formatter.formatShortFileSize(context, quotaBytes)
    val progress = if (quotaBytes > 0) (usedBytes.toFloat() / quotaBytes) else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Storage, null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$usedFmt / $totalFmt",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
            color = if (progress > 0.9f) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// File Entry Row
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current

    ListItem(
        headlineContent = {
            Text(
                entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        supportingContent = {
            Text(
                Formatter.formatShortFileSize(context, entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.FolderOpen else getFileIcon(entry.name),
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            if (entry.isDirectory) {
                Icon(
                    Icons.Default.ChevronRight, null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress
        )
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// Utility Functions
// ═════════════════════════════════════════════════════════════════════════════

private fun getFileIcon(fileName: String): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "java", "py", "js", "ts", "rs", "go", "c", "cpp", "h", "swift", "dart", "rb", "scala" -> Icons.Default.Code
        "txt", "md", "log", "doc", "rtf" -> Icons.Default.Description
        "json", "yaml", "yml", "toml", "xml" -> Icons.Default.DataObject
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> Icons.Default.Image
        "zip", "tar", "gz", "rar", "7z" -> Icons.Default.FolderZip
        "sh", "bash", "zsh", "bat", "ps1" -> Icons.Default.Terminal
        "pdf" -> Icons.Default.PictureAsPdf
        else -> Icons.Default.InsertDriveFile
    }
}

private fun isCodeFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in setOf(
        "kt", "java", "py", "js", "ts", "rs", "go", "c", "cpp", "h", "hpp",
        "swift", "dart", "rb", "scala", "sh", "bash", "zsh", "json", "yaml",
        "yml", "toml", "xml", "html", "css", "sql", "lua", "r", "ex", "exs",
        "hs", "ml", "clj", "lisp", "el", "vim", "gradle", "makefile"
    )
}

private fun isTextFile(file: File): Boolean {
    val textExtensions = setOf(
        "txt", "md", "json", "xml", "html", "css", "js", "ts", "kt", "java",
        "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "sh", "bash", "zsh",
        "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "csv", "sql",
        "gradle", "properties", "gitignore", "dockerfile", "makefile",
        "swift", "dart", "lua", "r", "scala", "clj", "ex", "exs", "erl",
        "hs", "ml", "fs", "vim", "el", "lisp", "tex", "bib", "svg", "bat",
        "ps1", "rtf", "env", "lock"
    )
    val ext = file.extension.lowercase()
    if (ext in textExtensions) return true

    // Known text files without extensions
    if (ext.isBlank()) {
        val name = file.name.lowercase()
        if (name in setOf(
                "readme", "makefile", "dockerfile", "license", "changelog",
                "authors", "todo", "gemfile", "rakefile", "procfile", "vagrantfile"
            )
        ) return true
    }

    // Heuristic: read first 512 bytes
    return try {
        val buffer = ByteArray(512)
        val bytesRead = file.inputStream().use { it.read(buffer) }
        if (bytesRead <= 0) return true
        buffer.take(bytesRead).all { byte ->
            val b = byte.toInt() and 0xFF
            b in 9..13 || b in 32..126 || b > 127
        }
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun getEditorTextColor(
    fileName: String,
    defaultColor: androidx.compose.ui.graphics.Color
): androidx.compose.ui.graphics.Color {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "py", "rb" -> MaterialTheme.colorScheme.tertiary
        "kt", "java", "swift", "dart", "scala" -> MaterialTheme.colorScheme.primary
        "js", "ts" -> MaterialTheme.colorScheme.secondary
        "json", "yaml", "yml", "toml", "xml" -> MaterialTheme.colorScheme.onSurface
        "md", "txt" -> MaterialTheme.colorScheme.onSurface
        else -> defaultColor
    }
}

private suspend fun openFileForEditing(
    file: File,
    onSuccess: (String) -> Unit,
    onBinary: () -> Unit,
    onTooLarge: (Long) -> Unit
) {
    withContext(Dispatchers.IO) {
        val size = file.length()
        if (size > 1L * 1024L * 1024L) {
            withContext(Dispatchers.Main) { onTooLarge(size) }
            return@withContext
        }
        if (!isTextFile(file)) {
            withContext(Dispatchers.Main) { onBinary() }
            return@withContext
        }
        try {
            val content = file.readText()
            withContext(Dispatchers.Main) { onSuccess(content) }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { onBinary() }
        }
    }
}

private fun calcDirSize(dir: File): Long {
    if (!dir.exists()) return 0
    var size = 0L
    dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
    return size
}

private fun exportFolderAsZip(folder: File, cacheDir: File): File {
    val zipFile = File(cacheDir, "${folder.name}.zip")
    ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
        folder.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = file.relativeTo(folder).path
                val entry = ZipEntry(relativePath)
                entry.time = file.lastModified()
                zos.putNextEntry(entry)
                file.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
    return zipFile
}

private fun shareFile(context: android.content.Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ZIP"))
    } catch (_: Exception) {
        // Silently fail if no activity can handle the intent
    }
}
