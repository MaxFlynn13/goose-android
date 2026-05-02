package io.github.gooseandroid.ui.recipes

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Recipes — user-created prompt templates and workflows.
 *
 * Users can create, edit, and delete their own recipes.
 * Recipes are persisted to local storage as JSON.
 * Selecting a recipe pre-fills the chat input with the recipe prompt.
 */

@Serializable
data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val category: String = "General",
    val prompt: String
)

class RecipeStore(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "recipes.json")

    suspend fun loadRecipes(): List<Recipe> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<Recipe>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveRecipes(recipes: List<Recipe>) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(recipes))
    }

    suspend fun addRecipe(recipe: Recipe) {
        val current = loadRecipes().toMutableList()
        current.add(recipe)
        saveRecipes(current)
    }

    suspend fun updateRecipe(recipe: Recipe) {
        val current = loadRecipes().toMutableList()
        val index = current.indexOfFirst { it.id == recipe.id }
        if (index >= 0) {
            current[index] = recipe
            saveRecipes(current)
        }
    }

    suspend fun deleteRecipe(id: String) {
        val current = loadRecipes().filter { it.id != id }
        saveRecipes(current)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    onBack: () -> Unit,
    onUseRecipe: (Recipe) -> Unit
) {
    val context = LocalContext.current
    val recipeStore = remember { RecipeStore(context) }
    val scope = rememberCoroutineScope()

    var recipes by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingRecipe by remember { mutableStateOf<Recipe?>(null) }

    LaunchedEffect(Unit) {
        recipes = recipeStore.loadRecipes()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, "Create Recipe")
                    }
                }
            )
        }
    ) { padding ->
        if (recipes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No recipes yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Create reusable prompt templates.\nTap a recipe to pre-fill your chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Recipe")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recipes, key = { it.id }) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        onUse = { onUseRecipe(recipe) },
                        onEdit = { editingRecipe = recipe },
                        onDelete = {
                            scope.launch {
                                recipeStore.deleteRecipe(recipe.id)
                                recipes = recipeStore.loadRecipes()
                            }
                        }
                    )
                }
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        RecipeDialog(
            title = "Create Recipe",
            onDismiss = { showCreateDialog = false },
            onSave = { name, description, category, prompt ->
                scope.launch {
                    recipeStore.addRecipe(Recipe(
                        name = name,
                        description = description,
                        category = category,
                        prompt = prompt
                    ))
                    recipes = recipeStore.loadRecipes()
                    showCreateDialog = false
                }
            }
        )
    }

    // Edit dialog
    editingRecipe?.let { recipe ->
        RecipeDialog(
            title = "Edit Recipe",
            initialName = recipe.name,
            initialDescription = recipe.description,
            initialCategory = recipe.category,
            initialPrompt = recipe.prompt,
            onDismiss = { editingRecipe = null },
            onSave = { name, description, category, prompt ->
                scope.launch {
                    recipeStore.updateRecipe(recipe.copy(
                        name = name,
                        description = description,
                        category = category,
                        prompt = prompt
                    ))
                    recipes = recipeStore.loadRecipes()
                    editingRecipe = null
                }
            }
        )
    }
}

@Composable
private fun RecipeCard(
    recipe: Recipe,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(recipe.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    if (recipe.description.isNotBlank()) {
                        Text(
                            recipe.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (recipe.category.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                SuggestionChip(
                    onClick = {},
                    label = { Text(recipe.category, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp)
                )
            }
        }
    }
}

@Composable
private fun RecipeDialog(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    initialCategory: String = "General",
    initialPrompt: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, category: String, prompt: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var category by remember { mutableStateOf(initialCategory) }
    var prompt by remember { mutableStateOf(initialPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
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
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt template") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, description, category, prompt) },
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
