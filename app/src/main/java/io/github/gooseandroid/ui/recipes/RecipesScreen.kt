package io.github.gooseandroid.ui.recipes

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Recipes screen — pre-built prompts and workflows.
 * Equivalent to Goose desktop's recipe system.
 */

data class Recipe(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val prompt: String
)

val BUILT_IN_RECIPES = listOf(
    Recipe("summarize", "Summarize Text", "Condense long text into key points", "Writing",
        "Please summarize the following text into bullet points, highlighting the key takeaways:"),
    Recipe("code_review", "Code Review", "Review code for bugs, style, and improvements", "Development",
        "Please review this code. Look for bugs, style issues, performance problems, and suggest improvements:"),
    Recipe("explain", "Explain Like I'm 5", "Simplify complex topics", "Learning",
        "Explain the following concept in simple terms that anyone could understand:"),
    Recipe("debug", "Debug Helper", "Help diagnose and fix issues", "Development",
        "I'm encountering this error. Help me understand what's wrong and how to fix it:"),
    Recipe("refactor", "Refactor Code", "Improve code structure without changing behavior", "Development",
        "Please refactor this code to be more readable, maintainable, and efficient:"),
    Recipe("write_tests", "Write Tests", "Generate test cases for code", "Development",
        "Write comprehensive unit tests for the following code. Cover edge cases:"),
    Recipe("brainstorm", "Brainstorm Ideas", "Generate creative ideas on a topic", "Creative",
        "Help me brainstorm ideas for the following. Give me 10 diverse and creative suggestions:"),
    Recipe("email_draft", "Draft Email", "Write a professional email", "Writing",
        "Help me draft a professional email for the following situation:"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    onBack: () -> Unit,
    onUseRecipe: (Recipe) -> Unit
) {
    val categories = BUILT_IN_RECIPES.map { it.category }.distinct()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                item {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }

                items(BUILT_IN_RECIPES.filter { it.category == category }) { recipe ->
                    RecipeCard(recipe = recipe, onUse = { onUseRecipe(recipe) })
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, onUse: () -> Unit) {
    Card(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(recipe.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    recipe.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
