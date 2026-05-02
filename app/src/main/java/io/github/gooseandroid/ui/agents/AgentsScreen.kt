package io.github.gooseandroid.ui.agents

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.gooseandroid.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// ---------------------------------------------------------------------------
// Data Model
// ---------------------------------------------------------------------------

@Serializable
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val systemPrompt: String = "",
    val providerId: String = "",
    val modelId: String = "",
    val isBuiltin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

class PersonaStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File
        get() = File(context.filesDir, "personas.json")

    private val builtinPersonas: List<Persona> = listOf(
        Persona(
            id = "builtin_default",
            displayName = "Default",
            systemPrompt = "",
            providerId = "",
            modelId = "",
            isBuiltin = true,
            createdAt = 0L
        ),
        Persona(
            id = "builtin_code_assistant",
            displayName = "Code Assistant",
            systemPrompt = "You are an expert software engineer. Focus on writing clean, efficient, well-documented code. Provide explanations of your approach, suggest best practices, and help debug issues. Prefer concise answers with code examples.",
            providerId = "",
            modelId = "",
            isBuiltin = true,
            createdAt = 0L
        ),
        Persona(
            id = "builtin_writer",
            displayName = "Writer",
            systemPrompt = "You are a skilled writer and editor. Help with drafting, revising, and polishing written content. Focus on clarity, tone, structure, and grammar. Adapt your style to match the user's needs whether formal, casual, technical, or creative.",
            providerId = "",
            modelId = "",
            isBuiltin = true,
            createdAt = 0L
        )
    )

    suspend fun loadAll(): List<Persona> = withContext(Dispatchers.IO) {
        val custom = loadCustom()
        builtinPersonas + custom
    }

    suspend fun loadCustom(): List<Persona> = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) {
                val text = file.readText()
                json.decodeFromString<List<Persona>>(text)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveCustom(personas: List<Persona>) = withContext(Dispatchers.IO) {
        val filtered = personas.filter { !it.isBuiltin }
        file.writeText(json.encodeToString(filtered))
    }

    suspend fun addPersona(persona: Persona): List<Persona> {
        val custom = loadCustom().toMutableList()
        custom.add(persona)
        saveCustom(custom)
        return builtinPersonas + custom
    }

    suspend fun updatePersona(persona: Persona): List<Persona> {
        val custom = loadCustom().toMutableList()
        val index = custom.indexOfFirst { it.id == persona.id }
        if (index >= 0) {
            custom[index] = persona
        }
        saveCustom(custom)
        return builtinPersonas + custom
    }

    suspend fun deletePersona(id: String): List<Persona> {
        val custom = loadCustom().toMutableList()
        custom.removeAll { it.id == id }
        saveCustom(custom)
        return builtinPersonas + custom
    }

    fun getBuiltins(): List<Persona> = builtinPersonas
}

// ---------------------------------------------------------------------------
// Avatar Colors
// ---------------------------------------------------------------------------

private val avatarColors = listOf(
    Color(0xFF1976D2),
    Color(0xFF388E3C),
    Color(0xFFD32F2F),
    Color(0xFF7B1FA2),
    Color(0xFFF57C00),
    Color(0xFF0097A7),
    Color(0xFF5D4037),
    Color(0xFF455A64),
    Color(0xFFC2185B),
    Color(0xFF00796B)
)

private fun avatarColorFor(name: String): Color {
    val index = (name.hashCode().and(0x7FFFFFFF)) % avatarColors.size
    return avatarColors[index]
}

// ---------------------------------------------------------------------------
// Main Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onBack: () -> Unit,
    onSelectPersona: (Persona) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val personaStore = remember { PersonaStore(context) }

    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }
    var activePersonaId by remember { mutableStateOf("builtin_default") }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }
    var deletingPersona by remember { mutableStateOf<Persona?>(null) }

    // Load personas and active ID
    LaunchedEffect(Unit) {
        personas = personaStore.loadAll()
        activePersonaId = settingsStore.getString("active_persona_id", "builtin_default").first()
    }

    val filteredPersonas = remember(personas, searchQuery) {
        if (searchQuery.isBlank()) {
            personas
        } else {
            val query = searchQuery.lowercase()
            personas.filter {
                it.displayName.lowercase().contains(query) ||
                    it.systemPrompt.lowercase().contains(query)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Create persona")
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
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search personas...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Grid of persona cards
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredPersonas, key = { it.id }) { persona ->
                    PersonaCard(
                        persona = persona,
                        isActive = persona.id == activePersonaId,
                        onTap = {
                            scope.launch {
                                activePersonaId = persona.id
                                settingsStore.setString("active_persona_id", persona.id)
                                onSelectPersona(persona)
                            }
                        },
                        onEdit = {
                            if (!persona.isBuiltin) {
                                editingPersona = persona
                            }
                        },
                        onDelete = {
                            if (!persona.isBuiltin) {
                                deletingPersona = persona
                            }
                        }
                    )
                }
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        PersonaEditorDialog(
            title = "Create Persona",
            initial = null,
            onDismiss = { showCreateDialog = false },
            onSave = { newPersona ->
                scope.launch {
                    personas = personaStore.addPersona(newPersona)
                    showCreateDialog = false
                }
            }
        )
    }

    // Edit dialog
    editingPersona?.let { persona ->
        PersonaEditorDialog(
            title = "Edit Persona",
            initial = persona,
            onDismiss = { editingPersona = null },
            onSave = { updated ->
                scope.launch {
                    personas = personaStore.updatePersona(updated)
                    editingPersona = null
                }
            }
        )
    }

    // Delete confirmation
    deletingPersona?.let { persona ->
        AlertDialog(
            onDismissRequest = { deletingPersona = null },
            title = { Text("Delete Persona") },
            text = { Text("Are you sure you want to delete \"${persona.displayName}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            personas = personaStore.deletePersona(persona.id)
                            if (activePersonaId == persona.id) {
                                activePersonaId = "builtin_default"
                                settingsStore.setString("active_persona_id", "builtin_default")
                            }
                            deletingPersona = null
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
                TextButton(onClick = { deletingPersona = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Persona Card
// ---------------------------------------------------------------------------

@Composable
private fun PersonaCard(
    persona: Persona,
    isActive: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isActive) 2.dp else 1.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Top row: avatar + actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Letter avatar
                val letter = persona.displayName.firstOrNull()?.uppercase() ?: "?"
                val color = avatarColorFor(persona.displayName)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Active indicator
                if (isActive) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Action buttons for custom personas
                if (!persona.isBuiltin) {
                    Row {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Name
            Text(
                text = persona.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // System prompt preview
            if (persona.systemPrompt.isNotBlank()) {
                Text(
                    text = persona.systemPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = "No system prompt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                )
            }

            // Provider info
            if (persona.providerId.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(persona.providerId)
                        if (persona.modelId.isNotBlank()) {
                            append(" / ")
                            append(persona.modelId)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Create / Edit Dialog
// ---------------------------------------------------------------------------

@Composable
private fun PersonaEditorDialog(
    title: String,
    initial: Persona?,
    onDismiss: () -> Unit,
    onSave: (Persona) -> Unit
) {
    var name by remember { mutableStateOf(initial?.displayName ?: "") }
    var systemPrompt by remember { mutableStateOf(initial?.systemPrompt ?: "") }
    var providerId by remember { mutableStateOf(initial?.providerId ?: "") }
    var modelId by remember { mutableStateOf(initial?.modelId ?: "") }

    val isValid = name.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g. Research Assistant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = name.isBlank()
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System Prompt") },
                    placeholder = { Text("Instructions for the AI personality...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    maxLines = 8
                )

                OutlinedTextField(
                    value = providerId,
                    onValueChange = { providerId = it },
                    label = { Text("Provider (optional)") },
                    placeholder = { Text("e.g. anthropic, openai") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model (optional)") },
                    placeholder = { Text("e.g. claude-sonnet-4-20250514") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val persona = if (initial != null) {
                                initial.copy(
                                    displayName = name.trim(),
                                    systemPrompt = systemPrompt.trim(),
                                    providerId = providerId.trim(),
                                    modelId = modelId.trim()
                                )
                            } else {
                                Persona(
                                    displayName = name.trim(),
                                    systemPrompt = systemPrompt.trim(),
                                    providerId = providerId.trim(),
                                    modelId = modelId.trim()
                                )
                            }
                            onSave(persona)
                        },
                        enabled = isValid
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
