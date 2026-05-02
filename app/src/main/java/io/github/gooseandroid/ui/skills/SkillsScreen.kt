package io.github.gooseandroid.ui.skills

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val instructions: String,
    val category: String = "General",
    val isBuiltin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

class SkillStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val file: File
        get() = File(context.filesDir, "skills.json")

    fun loadSkills(): List<Skill> {
        val userSkills = if (file.exists()) {
            try {
                json.decodeFromString<List<Skill>>(file.readText())
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return builtinSkills + userSkills
    }

    fun saveUserSkills(skills: List<Skill>) {
        val userOnly = skills.filter { !it.isBuiltin }
        file.writeText(json.encodeToString(userOnly))
    }

    fun exportSkills(skills: List<Skill>): String {
        return json.encodeToString(skills)
    }

    fun importSkills(jsonString: String): List<Skill> {
        return try {
            json.decodeFromString<List<Skill>>(jsonString).map {
                it.copy(isBuiltin = false, id = UUID.randomUUID().toString())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        val builtinSkills = listOf(
            Skill(
                id = "builtin-summarize",
                name = "Summarize",
                description = "Condense text into a brief summary",
                instructions = "Summarize the following text concisely, capturing the key points and main ideas in a clear and readable format.",
                category = "Writing",
                isBuiltin = true,
                createdAt = 0L
            ),
            Skill(
                id = "builtin-code-review",
                name = "Code Review",
                description = "Review code for issues and improvements",
                instructions = "Review this code for bugs, performance issues, security vulnerabilities, and readability. Suggest specific improvements with explanations.",
                category = "Development",
                isBuiltin = true,
                createdAt = 0L
            ),
            Skill(
                id = "builtin-explain-code",
                name = "Explain Code",
                description = "Get a step-by-step code explanation",
                instructions = "Explain what this code does step by step. Break down the logic, describe the data flow, and note any important patterns or techniques used.",
                category = "Development",
                isBuiltin = true,
                createdAt = 0L
            ),
            Skill(
                id = "builtin-debug-helper",
                name = "Debug Helper",
                description = "Assistance with debugging issues",
                instructions = "Help me debug this issue. Analyze the symptoms, suggest possible root causes, and recommend debugging steps or fixes.",
                category = "Development",
                isBuiltin = true,
                createdAt = 0L
            ),
            Skill(
                id = "builtin-write-tests",
                name = "Write Tests",
                description = "Generate comprehensive test cases",
                instructions = "Write comprehensive tests for the following code. Include unit tests covering edge cases, error conditions, and typical usage patterns.",
                category = "Development",
                isBuiltin = true,
                createdAt = 0L
            ),
            Skill(
                id = "builtin-brainstorm",
                name = "Brainstorm",
                description = "Generate creative ideas on a topic",
                instructions = "Help me brainstorm ideas for the following topic. Provide diverse, creative suggestions organized by theme or approach.",
                category = "Creative",
                isBuiltin = true,
                createdAt = 0L
            ),
            Skill(
                id = "builtin-translate",
                name = "Translate",
                description = "Translate text between languages",
                instructions = "Translate the following text to the specified language. Preserve the original tone, meaning, and formatting as closely as possible.",
                category = "Writing",
                isBuiltin = true,
                createdAt = 0L
            ),
            Skill(
                id = "builtin-refactor",
                name = "Refactor",
                description = "Improve code structure and quality",
                instructions = "Refactor this code to improve readability, maintainability, and performance. Explain the changes made and why they are improvements.",
                category = "Development",
                isBuiltin = true,
                createdAt = 0L
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    onUseSkill: (Skill) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val store = remember { SkillStore(context) }

    var skills by remember { mutableStateOf(store.loadSkills()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<Skill?>(null) }
    var deletingSkill by remember { mutableStateOf<Skill?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    val categories = remember(skills) {
        val cats = skills.map { it.category }.distinct().sorted()
        listOf("All") + cats
    }

    val filteredSkills = remember(skills, searchQuery, selectedCategory) {
        skills.filter { skill ->
            val matchesCategory = selectedCategory == "All" || skill.category == selectedCategory
            val matchesSearch = searchQuery.isBlank() ||
                skill.name.contains(searchQuery, ignoreCase = true) ||
                skill.description.contains(searchQuery, ignoreCase = true) ||
                skill.category.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    val groupedSkills = remember(filteredSkills) {
        filteredSkills.groupBy { it.category }.toSortedMap()
    }

    fun refreshSkills() {
        skills = store.loadSkills()
    }

    fun saveAndRefresh(updatedSkills: List<Skill>) {
        store.saveUserSkills(updatedSkills)
        skills = store.loadSkills()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search skills...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Skills")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchActive) "Close search" else "Search"
                        )
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Download, contentDescription = "Import skills")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create skill")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category) }
                    )
                }
            }

            if (filteredSkills.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No skills match your search"
                            else "No skills in this category",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    groupedSkills.forEach { (category, categorySkills) ->
                        item(key = "header-$category") {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    top = 12.dp,
                                    bottom = 4.dp
                                )
                            )
                        }
                        items(categorySkills, key = { it.id }) { skill ->
                            SkillItem(
                                skill = skill,
                                onUse = { onUseSkill(skill) },
                                onEdit = { editingSkill = skill },
                                onDelete = { deletingSkill = skill },
                                onExport = {
                                    val exported = store.exportSkills(listOf(skill))
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, exported)
                                        type = "application/json"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "Export Skill")
                                    context.startActivity(shareIntent)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        SkillEditorDialog(
            skill = null,
            onDismiss = { showCreateDialog = false },
            onSave = { newSkill ->
                val updated = skills + newSkill
                saveAndRefresh(updated)
                showCreateDialog = false
            }
        )
    }

    editingSkill?.let { skill ->
        SkillEditorDialog(
            skill = skill,
            onDismiss = { editingSkill = null },
            onSave = { updatedSkill ->
                val updated = skills.map { if (it.id == updatedSkill.id) updatedSkill else it }
                saveAndRefresh(updated)
                editingSkill = null
            }
        )
    }

    deletingSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { deletingSkill = null },
            title = { Text("Delete Skill") },
            text = { Text("Are you sure you want to delete \"${skill.name}\"? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = skills.filter { it.id != skill.id }
                        saveAndRefresh(updated)
                        deletingSkill = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSkill = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showImportDialog) {
        var importText by remember { mutableStateOf("") }
        var importError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Skills") },
            text = {
                Column {
                    Text(
                        "Paste skill JSON from clipboard or enter manually.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clip = clipboardManager.getText()
                            if (clip != null) {
                                importText = clip.text
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paste")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        label = { Text("JSON") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        maxLines = 10
                    )
                    importError?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val imported = store.importSkills(importText)
                    if (imported.isEmpty() && importText.isNotBlank()) {
                        importError = "Invalid JSON format. Expected an array of skill objects."
                    } else {
                        val updated = skills + imported
                        saveAndRefresh(updated)
                        showImportDialog = false
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SkillItem(
    skill: Skill,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = skill.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (skill.isBuiltin) {
                            Spacer(modifier = Modifier.width(8.dp))
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "Built-in",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                    if (skill.description.isNotBlank()) {
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Use in chat") },
                            onClick = {
                                showMenu = false
                                onUse()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                            }
                        )
                        if (!skill.isBuiltin) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Export") },
                            onClick = {
                                showMenu = false
                                onExport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null)
                            }
                        )
                        if (!skill.isBuiltin) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = skill.instructions,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(skill.category, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp)
                )
                FilledTonalButton(
                    onClick = onUse,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Use", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    skill: Skill?,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit
) {
    val isEditing = skill != null
    var name by remember { mutableStateOf(skill?.name ?: "") }
    var description by remember { mutableStateOf(skill?.description ?: "") }
    var instructions by remember { mutableStateOf(skill?.instructions ?: "") }
    var category by remember { mutableStateOf(skill?.category ?: "General") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var instructionsError by remember { mutableStateOf<String?>(null) }

    val predefinedCategories = listOf("General", "Writing", "Development", "Creative")
    var showCategoryDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Skill" else "Create Skill") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    label = { Text("Name") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = instructions,
                    onValueChange = {
                        instructions = it
                        instructionsError = null
                    },
                    label = { Text("Instructions / Prompt") },
                    isError = instructionsError != null,
                    supportingText = instructionsError?.let { { Text(it) } },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { showCategoryDropdown = !showCategoryDropdown }) {
                                Icon(
                                    if (showCategoryDropdown) Icons.Default.ArrowDropUp
                                    else Icons.Default.ArrowDropDown,
                                    contentDescription = "Select category"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = showCategoryDropdown,
                        onDismissRequest = { showCategoryDropdown = false }
                    ) {
                        predefinedCategories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    category = cat
                                    showCategoryDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var hasError = false
                if (name.isBlank()) {
                    nameError = "Name is required"
                    hasError = true
                }
                if (instructions.isBlank()) {
                    instructionsError = "Instructions are required"
                    hasError = true
                }
                if (!hasError) {
                    val savedSkill = Skill(
                        id = skill?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        description = description.trim(),
                        instructions = instructions.trim(),
                        category = category.trim().ifBlank { "General" },
                        isBuiltin = false,
                        createdAt = skill?.createdAt ?: System.currentTimeMillis()
                    )
                    onSave(savedSkill)
                }
            }) {
                Text(if (isEditing) "Save" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
