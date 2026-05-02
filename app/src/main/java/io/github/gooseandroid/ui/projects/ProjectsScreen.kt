package io.github.gooseandroid.ui.projects

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val workingDirectory: String = "",
    val instructions: String = "",
    val icon: String = "folder",
    val createdAt: Long = System.currentTimeMillis(),
    val sessionCount: Int = 0
)

class ProjectStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val file: File
        get() = File(context.filesDir, "projects.json")

    fun load(): List<Project> {
        return try {
            if (file.exists()) {
                json.decodeFromString<List<Project>>(file.readText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(projects: List<Project>) {
        try {
            file.writeText(json.encodeToString(projects))
        } catch (e: Exception) {
            // Silent failure - in production, log this
        }
    }

    fun addProject(project: Project): List<Project> {
        val projects = load().toMutableList()
        projects.add(project)
        save(projects)
        return projects
    }

    fun updateProject(project: Project): List<Project> {
        val projects = load().toMutableList()
        val index = projects.indexOfFirst { it.id == project.id }
        if (index >= 0) {
            projects[index] = project
        }
        save(projects)
        return projects
    }

    fun deleteProject(projectId: String): List<Project> {
        val projects = load().toMutableList()
        projects.removeAll { it.id == projectId }
        save(projects)
        return projects
    }

    fun incrementSessionCount(projectId: String): List<Project> {
        val projects = load().toMutableList()
        val index = projects.indexOfFirst { it.id == projectId }
        if (index >= 0) {
            val project = projects[index]
            projects[index] = project.copy(sessionCount = project.sessionCount + 1)
        }
        save(projects)
        return projects
    }
}

private fun iconForName(name: String): ImageVector {
    return when (name) {
        "code" -> Icons.Default.Code
        "work" -> Icons.Default.Work
        "science" -> Icons.Default.Science
        "chat" -> Icons.Default.Chat
        else -> Icons.Default.Folder
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    onStartChatInProject: (Project) -> Unit
) {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    var projects by remember { mutableStateOf(store.load()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingProject by remember { mutableStateOf<Project?>(null) }
    var deletingProject by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create Project"
                )
            }
        }
    ) { paddingValues ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No projects yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Create a project to scope chat sessions to a working directory with custom instructions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onTap = {
                            projects = store.incrementSessionCount(project.id)
                            val updated = projects.find { it.id == project.id } ?: project
                            onStartChatInProject(updated)
                        },
                        onEdit = { editingProject = project },
                        onDelete = { deletingProject = project }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        ProjectFormDialog(
            title = "Create Project",
            initial = null,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description, workingDir, instructions, icon ->
                val project = Project(
                    name = name,
                    description = description,
                    workingDirectory = workingDir,
                    instructions = instructions,
                    icon = icon
                )
                projects = store.addProject(project)
                showCreateDialog = false
            }
        )
    }

    if (editingProject != null) {
        ProjectFormDialog(
            title = "Edit Project",
            initial = editingProject,
            onDismiss = { editingProject = null },
            onConfirm = { name, description, workingDir, instructions, icon ->
                val updated = editingProject!!.copy(
                    name = name,
                    description = description,
                    workingDirectory = workingDir,
                    instructions = instructions,
                    icon = icon
                )
                projects = store.updateProject(updated)
                editingProject = null
            }
        )
    }

    if (deletingProject != null) {
        AlertDialog(
            onDismissRequest = { deletingProject = null },
            title = { Text("Delete Project") },
            text = {
                Text("Are you sure you want to delete \"${deletingProject!!.name}\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        projects = store.deleteProject(deletingProject!!.id)
                        deletingProject = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProject = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = iconForName(project.icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (project.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = project.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (project.workingDirectory.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = project.workingDirectory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${project.sessionCount} session${if (project.sessionCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Column {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectFormDialog(
    title: String,
    initial: Project?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, workingDir: String, instructions: String, icon: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var workingDirectory by remember { mutableStateOf(initial?.workingDirectory ?: "") }
    var instructions by remember { mutableStateOf(initial?.instructions ?: "") }
    var selectedIcon by remember { mutableStateOf(initial?.icon ?: "folder") }
    var nameError by remember { mutableStateOf(false) }

    val iconOptions = listOf("folder", "code", "work", "science", "chat")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = false
                    },
                    label = { Text("Name *") },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("Name is required") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text("Working Directory") },
                    placeholder = { Text("/data/project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Instructions") },
                    placeholder = { Text("Custom system prompt for this project") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    iconOptions.forEach { iconName ->
                        IconButton(
                            onClick = { selectedIcon = iconName }
                        ) {
                            Icon(
                                imageVector = iconForName(iconName),
                                contentDescription = iconName,
                                tint = if (selectedIcon == iconName) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        nameError = true
                    } else {
                        onConfirm(
                            name.trim(),
                            description.trim(),
                            workingDirectory.trim(),
                            instructions.trim(),
                            selectedIcon
                        )
                    }
                }
            ) {
                Text(if (initial == null) "Create" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
