package io.github.gooseandroid.ui.providers

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@Serializable
data class CustomProvider(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val apiUrl: String,
    val basePath: String = "/v1/chat/completions",
    val apiKey: String = "",
    val headers: Map<String, String> = emptyMap(),
    val models: List<String> = listOf("default"),
    val createdAt: Long = System.currentTimeMillis()
)

class CustomProviderStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(context.filesDir, "custom_providers.json")

    fun loadProviders(): List<CustomProvider> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<CustomProvider>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveProviders(providers: List<CustomProvider>) {
        file.writeText(json.encodeToString(providers))
    }

    fun addProvider(provider: CustomProvider) {
        val providers = loadProviders().toMutableList()
        val index = providers.indexOfFirst { it.id == provider.id }
        if (index >= 0) {
            providers[index] = provider
        } else {
            providers.add(provider)
        }
        saveProviders(providers)
    }

    fun removeProvider(id: String) {
        val providers = loadProviders().filter { it.id != id }
        saveProviders(providers)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomProviderDialog(
    provider: CustomProvider? = null,
    onSave: (CustomProvider) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val isEdit = provider != null
    var displayName by remember { mutableStateOf(provider?.displayName ?: "") }
    var apiUrl by remember { mutableStateOf(provider?.apiUrl ?: "") }
    var basePath by remember { mutableStateOf(provider?.basePath ?: "/v1/chat/completions") }
    var apiKey by remember { mutableStateOf(provider?.apiKey ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var modelsText by remember { mutableStateOf(provider?.models?.joinToString(", ") ?: "default") }
    var headers by remember { mutableStateOf(provider?.headers?.toList() ?: emptyList()) }
    var newHeaderKey by remember { mutableStateOf("") }
    var newHeaderValue by remember { mutableStateOf("") }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var testInProgress by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val isValid = displayName.isNotBlank() && apiUrl.isNotBlank() && basePath.isNotBlank()

    if (showDeleteConfirm && isEdit) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Provider") },
            text = { Text("Remove \"${provider!!.displayName}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke(provider!!.id)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (isEdit) "Edit Provider" else "Add Custom Provider")
                if (isEdit && onDelete != null) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete provider",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Provider Name") },
                    placeholder = { Text("My Provider") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it.trimEnd('/') },
                    label = { Text("API URL") },
                    placeholder = { Text("https://api.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text("Base Path") },
                    placeholder = { Text("/v1/chat/completions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = if (apiKeyVisible) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Custom headers section
                Text(
                    "Custom Headers",
                    style = MaterialTheme.typography.titleSmall
                )

                headers.forEachIndexed { index, (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$key: $value",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { headers = headers.toMutableList().also { it.removeAt(index) } },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove header", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newHeaderKey,
                        onValueChange = { newHeaderKey = it },
                        label = { Text("Key") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = newHeaderValue,
                        onValueChange = { newHeaderValue = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (newHeaderKey.isNotBlank()) {
                                headers = headers + (newHeaderKey to newHeaderValue)
                                newHeaderKey = ""
                                newHeaderValue = ""
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add header")
                    }
                }

                OutlinedTextField(
                    value = modelsText,
                    onValueChange = { modelsText = it },
                    label = { Text("Models (comma-separated)") },
                    placeholder = { Text("gpt-4, gpt-3.5-turbo") },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Test connection button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            testInProgress = true
                            testStatus = null
                            scope.launch {
                                testStatus = testConnection(apiUrl, basePath, apiKey, headers.toMap())
                                testInProgress = false
                            }
                        },
                        enabled = apiUrl.isNotBlank() && basePath.isNotBlank() && !testInProgress
                    ) {
                        if (testInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Test Connection")
                    }
                }

                if (testStatus != null) {
                    Text(
                        testStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testStatus!!.startsWith("Success"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val models = modelsText.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .ifEmpty { listOf("default") }

                    val result = CustomProvider(
                        id = provider?.id ?: UUID.randomUUID().toString(),
                        displayName = displayName.trim(),
                        apiUrl = apiUrl.trim().trimEnd('/'),
                        basePath = basePath.trim(),
                        apiKey = apiKey.trim(),
                        headers = headers.toMap(),
                        models = models,
                        createdAt = provider?.createdAt ?: System.currentTimeMillis()
                    )
                    onSave(result)
                },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private suspend fun testConnection(
    apiUrl: String,
    basePath: String,
    apiKey: String,
    headers: Map<String, String>
): String = withContext(Dispatchers.IO) {
    try {
        val url = URL(apiUrl.trimEnd('/') + basePath)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        headers.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }
        connection.doOutput = true

        val body = """{"model":"test","messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
        connection.outputStream.use { it.write(body.toByteArray()) }

        val code = connection.responseCode
        connection.disconnect()

        when (code) {
            200 -> "Success: Connection established (HTTP 200)"
            401 -> "Error: Unauthorized (HTTP 401) - check API key"
            403 -> "Error: Forbidden (HTTP 403) - check permissions"
            404 -> "Error: Not found (HTTP 404) - check base path"
            else -> "Response: HTTP $code"
        }
    } catch (e: Exception) {
        "Error: ${e.message ?: "Connection failed"}"
    }
}
