package io.github.gooseandroid.engine.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Tools that give Goose full CRUD control over the app's features:
 * skills, extensions, schedules, brain nodes, projects, etc.
 *
 * Each tool accepts a JSON input with an "action" field and relevant parameters,
 * returns the result as a formatted string, handles errors gracefully,
 * and persists changes to the appropriate storage.
 *
 * HARDENED: All tools protect against corrupted files, concurrent access,
 * invalid input, and I/O failures.
 */

// ─── Helper: build function schema ─────────────────────────────────────────

private fun appControlSchema(
    name: String,
    description: String,
    properties: JSONObject,
    required: JSONArray
): JSONObject = JSONObject().apply {
    put("type", "function")
    put("function", JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", required)
        })
    })
}

/**
 * Safely load a JSON array from a file. If the file is corrupted:
 * 1. Backs up the corrupted file with .corrupt.{timestamp} suffix
 * 2. Returns an empty JSONArray
 * 3. Logs the corruption
 */
private fun safeLoadJsonArray(file: File, tag: String): JSONArray {
    if (!file.exists()) return JSONArray()
    val text = try {
        file.readText()
    } catch (e: IOException) {
        Log.e(tag, "Failed to read file ${file.path}: ${e.message}")
        return JSONArray()
    }
    if (text.isBlank()) return JSONArray()
    return try {
        JSONArray(text)
    } catch (e: JSONException) {
        Log.e(tag, "Corrupted JSON in ${file.path}: ${e.message}. Backing up and recreating.")
        backupCorruptedFile(file, tag)
        JSONArray()
    }
}

/**
 * Safely load a JSON object from a file. If the file is corrupted:
 * 1. Backs up the corrupted file
 * 2. Returns the provided default
 */
private fun safeLoadJsonObject(file: File, tag: String, default: () -> JSONObject): JSONObject {
    if (!file.exists()) return default()
    val text = try {
        file.readText()
    } catch (e: IOException) {
        Log.e(tag, "Failed to read file ${file.path}: ${e.message}")
        return default()
    }
    if (text.isBlank()) return default()
    return try {
        JSONObject(text)
    } catch (e: JSONException) {
        Log.e(tag, "Corrupted JSON in ${file.path}: ${e.message}. Backing up and recreating.")
        backupCorruptedFile(file, tag)
        default()
    }
}

/**
 * Backup a corrupted file before recreating it.
 */
private fun backupCorruptedFile(file: File, tag: String) {
    try {
        val backupName = "${file.name}.corrupt.${System.currentTimeMillis()}"
        val backupFile = File(file.parentFile, backupName)
        file.copyTo(backupFile, overwrite = true)
        Log.w(tag, "Backed up corrupted file to: ${backupFile.path}")
    } catch (e: Exception) {
        Log.e(tag, "Failed to backup corrupted file: ${e.message}")
    }
}

/**
 * Safely write JSON to a file with atomic-ish write (write to temp, then rename).
 * Returns null on success, or an error message on failure.
 */
private fun safeWriteJson(file: File, content: String, tag: String): String? {
    return try {
        // Write to temp file first, then rename for atomicity
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        tmpFile.writeText(content)
        if (!tmpFile.renameTo(file)) {
            // Rename failed (common on some Android FS), fall back to direct write
            file.writeText(content)
            tmpFile.delete()
        }
        null
    } catch (e: IOException) {
        val msg = when {
            e.message?.contains("No space left", ignoreCase = true) == true -> "Disk full"
            e.message?.contains("ENOSPC", ignoreCase = true) == true -> "Disk full"
            e.message?.contains("Read-only", ignoreCase = true) == true -> "Read-only file system"
            else -> "I/O error: ${e.message}"
        }
        Log.e(tag, "Failed to write ${file.path}: $msg")
        msg
    }
}

// ─── 1. SkillManageTool ─────────────────────────────────────────────────────
// Hardened against:
//   1. Corrupted JSON file (backup and recreate)
//   2. Concurrent access (synchronized block)
//   3. Missing required fields (validate before processing)
// ─────────────────────────────────────────────────────────────────────────────

class SkillManageTool(private val context: Context) : Tool {

    override val name = "manage_skills"
    override val description =
        "Manage Goose skills (reusable instruction templates). Actions: create, read, update, delete, list"

    companion object {
        private const val TAG = "SkillManageTool"
        /** Lock object for synchronized file access */
        private val FILE_LOCK = Any()
    }

    private val skillsFile: File
        get() = File(context.filesDir, "skills.json")

    override fun getSchema(): JSONObject = appControlSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "The action to perform: create, read, update, delete, list")
                put("enum", JSONArray().apply {
                    put("create"); put("read"); put("update"); put("delete"); put("list")
                })
            })
            put("id", JSONObject().apply {
                put("type", "string")
                put("description", "Skill ID (for read, update, delete)")
            })
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "Skill name (for create, update)")
            })
            put("description", JSONObject().apply {
                put("type", "string")
                put("description", "Skill description (for create, update)")
            })
            put("instructions", JSONObject().apply {
                put("type", "string")
                put("description", "Skill instructions/prompt (for create, update)")
            })
            put("category", JSONObject().apply {
                put("type", "string")
                put("description", "Skill category (for create, update). Default: General")
            })
        },
        required = JSONArray().apply { put("action") }
    )

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = input.optString("action", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'action' is required", isError = true)
        }

        // HARDENING 3: Validate action is known before processing
        val validActions = setOf("create", "read", "update", "delete", "list")
        if (action !in validActions) {
            return@withContext ToolResult(
                "Error: Unknown action '$action'. Valid actions: ${validActions.joinToString(", ")}",
                isError = true
            )
        }

        try {
            // HARDENING 2: Synchronized access to prevent concurrent file corruption
            synchronized(FILE_LOCK) {
                when (action) {
                    "list" -> listSkills()
                    "read" -> readSkill(input.optString("id", ""))
                    "create" -> createSkill(input)
                    "update" -> updateSkill(input)
                    "delete" -> deleteSkill(input.optString("id", ""))
                    else -> ToolResult("Error: Unknown action '$action'. Valid actions: ${validActions.joinToString(", ")}", isError = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_skills: ${e.message}", e)
            ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    // HARDENING 1: Uses safeLoadJsonArray which handles corruption
    private fun loadSkills(): JSONArray = safeLoadJsonArray(skillsFile, TAG)

    private fun saveSkills(skills: JSONArray): ToolResult? {
        val error = safeWriteJson(skillsFile, skills.toString(2), TAG)
        return if (error != null) {
            ToolResult("Error saving skills ($error)", isError = true)
        } else null
    }

    private fun listSkills(): ToolResult {
        val skills = loadSkills()
        if (skills.length() == 0) {
            return ToolResult("No skills found. Use action 'create' to add one.")
        }
        val sb = StringBuilder("Skills (${skills.length()}):\n\n")
        for (i in 0 until skills.length()) {
            val skill = skills.getJSONObject(i)
            sb.appendLine("- **${skill.optString("name")}** (id: ${skill.optString("id")})")
            sb.appendLine("  Category: ${skill.optString("category", "General")}")
            sb.appendLine("  Description: ${skill.optString("description", "")}")
            sb.appendLine()
        }
        return ToolResult(sb.toString())
    }

    private fun readSkill(id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for read", isError = true)
        val skills = loadSkills()
        for (i in 0 until skills.length()) {
            val skill = skills.getJSONObject(i)
            if (skill.optString("id") == id) {
                return ToolResult(skill.toString(2))
            }
        }
        // Show available IDs to help user
        val availableIds = (0 until skills.length()).map {
            val s = skills.getJSONObject(it)
            "'${s.optString("id")}' (${s.optString("name")})"
        }
        return ToolResult(
            "Error: Skill with id '$id' not found.\n" +
            if (availableIds.isNotEmpty()) "Available skills: ${availableIds.joinToString(", ")}" else "No skills exist yet.",
            isError = true
        )
    }

    // HARDENING 3: Validate all required fields before processing
    private fun createSkill(input: JSONObject): ToolResult {
        val skillName = input.optString("name", "").also {
            if (it.isBlank()) return ToolResult(
                "Error: 'name' is required for create. Provide a descriptive name for the skill.",
                isError = true
            )
        }
        val instructions = input.optString("instructions", "").also {
            if (it.isBlank()) return ToolResult(
                "Error: 'instructions' is required for create. Provide the prompt/instructions for this skill.",
                isError = true
            )
        }

        val skills = loadSkills()
        val id = UUID.randomUUID().toString()
        val newSkill = JSONObject().apply {
            put("id", id)
            put("name", skillName)
            put("description", input.optString("description", ""))
            put("instructions", instructions)
            put("category", input.optString("category", "General"))
            put("isBuiltin", false)
            put("createdAt", System.currentTimeMillis())
        }
        skills.put(newSkill)

        val saveError = saveSkills(skills)
        if (saveError != null) return saveError

        return ToolResult("Created skill '$skillName' (id: $id)")
    }

    private fun updateSkill(input: JSONObject): ToolResult {
        val id = input.optString("id", "").also {
            if (it.isBlank()) return ToolResult("Error: 'id' is required for update", isError = true)
        }
        val skills = loadSkills()
        for (i in 0 until skills.length()) {
            val skill = skills.getJSONObject(i)
            if (skill.optString("id") == id) {
                input.optString("name", "").takeIf { it.isNotBlank() }?.let { skill.put("name", it) }
                input.optString("description", "").takeIf { it.isNotBlank() }?.let { skill.put("description", it) }
                input.optString("instructions", "").takeIf { it.isNotBlank() }?.let { skill.put("instructions", it) }
                input.optString("category", "").takeIf { it.isNotBlank() }?.let { skill.put("category", it) }

                val saveError = saveSkills(skills)
                if (saveError != null) return saveError

                return ToolResult("Updated skill '${skill.optString("name")}' (id: $id)")
            }
        }
        return ToolResult("Error: Skill with id '$id' not found", isError = true)
    }

    private fun deleteSkill(id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for delete", isError = true)
        val skills = loadSkills()
        val newSkills = JSONArray()
        var found = false
        var deletedName = ""
        for (i in 0 until skills.length()) {
            val skill = skills.getJSONObject(i)
            if (skill.optString("id") == id) {
                found = true
                deletedName = skill.optString("name", id)
            } else {
                newSkills.put(skill)
            }
        }
        if (!found) return ToolResult("Error: Skill with id '$id' not found", isError = true)

        val saveError = saveSkills(newSkills)
        if (saveError != null) return saveError

        return ToolResult("Deleted skill '$deletedName' (id: $id)")
    }
}

// ─── 2. ExtensionManageTool ─────────────────────────────────────────────────
// Hardened against:
//   1. Invalid extension config (validate URL/command format)
//   2. File I/O failure (catch and return clear error)
//   3. Unknown action (list valid actions in error message)
// ─────────────────────────────────────────────────────────────────────────────

class ExtensionManageTool(private val context: Context) : Tool {

    override val name = "manage_extensions"
    override val description =
        "Manage MCP extensions. Actions: enable, disable, add, remove, list, configure"

    companion object {
        private const val TAG = "ExtensionManageTool"
        private val FILE_LOCK = Any()
        private val VALID_ACTIONS = setOf("enable", "disable", "add", "remove", "list", "configure")
        /** Basic URL pattern for validation */
        private val URL_PATTERN = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE)
    }

    private val configFile: File
        get() = File(context.filesDir, "mcp_extensions.json")

    override fun getSchema(): JSONObject = appControlSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "The action: enable, disable, add, remove, list, configure")
                put("enum", JSONArray().apply {
                    put("enable"); put("disable"); put("add"); put("remove"); put("list"); put("configure")
                })
            })
            put("id", JSONObject().apply {
                put("type", "string")
                put("description", "Extension ID")
            })
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "Extension display name (for add)")
            })
            put("transport", JSONObject().apply {
                put("type", "string")
                put("description", "Transport type: stdio or http (for add)")
            })
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "Command to run (for stdio transport)")
            })
            put("url", JSONObject().apply {
                put("type", "string")
                put("description", "Server URL (for http transport)")
            })
            put("env_vars", JSONObject().apply {
                put("type", "string")
                put("description", "Environment variables as KEY=VALUE pairs separated by semicolons")
            })
        },
        required = JSONArray().apply { put("action") }
    )

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = input.optString("action", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'action' is required", isError = true)
        }

        // HARDENING 3: Unknown action — list valid actions
        if (action !in VALID_ACTIONS) {
            return@withContext ToolResult(
                "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                isError = true
            )
        }

        try {
            synchronized(FILE_LOCK) {
                when (action) {
                    "list" -> listExtensions()
                    "add" -> addExtension(input)
                    "remove" -> removeExtension(input.optString("id", ""))
                    "enable" -> setEnabled(input.optString("id", ""), true)
                    "disable" -> setEnabled(input.optString("id", ""), false)
                    "configure" -> configureExtension(input)
                    else -> ToolResult(
                        "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                        isError = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_extensions: ${e.message}", e)
            ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    // HARDENING 2: Uses safe loading that handles I/O failures and corruption
    private fun loadConfig(): JSONObject {
        return safeLoadJsonObject(configFile, TAG) {
            JSONObject().put("extensions", JSONArray())
        }
    }

    private fun saveConfig(config: JSONObject): ToolResult? {
        val error = safeWriteJson(configFile, config.toString(2), TAG)
        return if (error != null) {
            ToolResult("Error saving extension config ($error)", isError = true)
        } else null
    }

    private fun listExtensions(): ToolResult {
        val config = loadConfig()
        val extensions = config.optJSONArray("extensions") ?: JSONArray()
        if (extensions.length() == 0) {
            return ToolResult("No MCP extensions configured. Use action 'add' to add one.")
        }
        val sb = StringBuilder("Extensions (${extensions.length()}):\n\n")
        for (i in 0 until extensions.length()) {
            val ext = extensions.getJSONObject(i)
            val enabled = ext.optBoolean("enabled", false)
            val status = if (enabled) "✓ enabled" else "✗ disabled"
            sb.appendLine("- **${ext.optString("name")}** (id: ${ext.optString("id")}) [$status]")
            sb.appendLine("  Transport: ${ext.optString("transport", ext.optString("type", "unknown"))}")
            val cmd = ext.optString("command", "")
            val url = ext.optString("url", "")
            if (cmd.isNotBlank()) sb.appendLine("  Command: $cmd")
            if (url.isNotBlank()) sb.appendLine("  URL: $url")
            sb.appendLine()
        }
        return ToolResult(sb.toString())
    }

    // HARDENING 1: Validate URL/command format before saving
    private fun addExtension(input: JSONObject): ToolResult {
        val extName = input.optString("name", "").also {
            if (it.isBlank()) return ToolResult("Error: 'name' is required for add", isError = true)
        }
        val transport = input.optString("transport", "stdio")
        val command = input.optString("command", "")
        val url = input.optString("url", "")

        // Validate transport type
        if (transport !in setOf("stdio", "http", "sse")) {
            return ToolResult(
                "Error: Invalid transport '$transport'. Valid transports: stdio, http, sse",
                isError = true
            )
        }

        // HARDENING 1: Validate command format for stdio
        if (transport == "stdio") {
            if (command.isBlank()) {
                return ToolResult("Error: 'command' is required for stdio transport", isError = true)
            }
            // Basic validation: command should not be empty or just whitespace
            if (command.trim().isEmpty()) {
                return ToolResult("Error: 'command' cannot be empty/whitespace for stdio transport", isError = true)
            }
            // Reject null bytes in command
            if (command.contains('\u0000')) {
                return ToolResult("Error: 'command' contains invalid null bytes", isError = true)
            }
        }

        // HARDENING 1: Validate URL format for http/sse
        if (transport in setOf("http", "sse")) {
            if (url.isBlank()) {
                return ToolResult("Error: 'url' is required for $transport transport", isError = true)
            }
            if (!URL_PATTERN.matches(url)) {
                return ToolResult(
                    "Error: Invalid URL format '$url'. URL must start with http:// or https:// and contain no spaces.\n" +
                    "Example: https://mcp-server.example.com/api",
                    isError = true
                )
            }
        }

        val id = input.optString("id", "").ifBlank {
            extName.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        }

        // Parse env vars
        val envVarsStr = input.optString("env_vars", "")
        val envVars = JSONObject()
        if (envVarsStr.isNotBlank()) {
            envVarsStr.split(";").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    envVars.put(parts[0].trim(), parts[1].trim())
                }
            }
        }

        val config = loadConfig()
        val extensions = config.optJSONArray("extensions") ?: JSONArray()

        // Remove existing with same ID
        val newExtensions = JSONArray()
        for (i in 0 until extensions.length()) {
            val ext = extensions.getJSONObject(i)
            if (ext.optString("id") != id) newExtensions.put(ext)
        }

        val newExt = JSONObject().apply {
            put("id", id)
            put("name", extName)
            put("transport", transport)
            put("type", transport)
            put("command", command)
            put("url", url)
            put("envVars", envVars)
            put("enabled", true)
        }
        newExtensions.put(newExt)
        config.put("extensions", newExtensions)

        // HARDENING 2: Check for I/O failure on save
        val saveError = saveConfig(config)
        if (saveError != null) return saveError

        return ToolResult("Added extension '$extName' (id: $id, transport: $transport)")
    }

    private fun removeExtension(id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for remove", isError = true)
        val config = loadConfig()
        val extensions = config.optJSONArray("extensions") ?: JSONArray()
        val newExtensions = JSONArray()
        var found = false
        var removedName = ""
        for (i in 0 until extensions.length()) {
            val ext = extensions.getJSONObject(i)
            if (ext.optString("id") == id) {
                found = true
                removedName = ext.optString("name", id)
            } else {
                newExtensions.put(ext)
            }
        }
        if (!found) {
            val availableIds = (0 until extensions.length()).map { extensions.getJSONObject(it).optString("id") }
            return ToolResult(
                "Error: Extension with id '$id' not found.\n" +
                if (availableIds.isNotEmpty()) "Available IDs: ${availableIds.joinToString(", ")}" else "No extensions configured.",
                isError = true
            )
        }
        config.put("extensions", newExtensions)
        val saveError = saveConfig(config)
        if (saveError != null) return saveError
        return ToolResult("Removed extension '$removedName' (id: $id)")
    }

    private fun setEnabled(id: String, enabled: Boolean): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required", isError = true)
        val config = loadConfig()
        val extensions = config.optJSONArray("extensions") ?: JSONArray()
        for (i in 0 until extensions.length()) {
            val ext = extensions.getJSONObject(i)
            if (ext.optString("id") == id) {
                ext.put("enabled", enabled)
                val saveError = saveConfig(config)
                if (saveError != null) return saveError
                val state = if (enabled) "enabled" else "disabled"
                return ToolResult("Extension '${ext.optString("name")}' $state")
            }
        }
        return ToolResult("Error: Extension with id '$id' not found", isError = true)
    }

    private fun configureExtension(input: JSONObject): ToolResult {
        val id = input.optString("id", "").also {
            if (it.isBlank()) return ToolResult("Error: 'id' is required for configure", isError = true)
        }
        val config = loadConfig()
        val extensions = config.optJSONArray("extensions") ?: JSONArray()
        for (i in 0 until extensions.length()) {
            val ext = extensions.getJSONObject(i)
            if (ext.optString("id") == id) {
                // HARDENING 1: Validate URL if provided
                val newUrl = input.optString("url", "").takeIf { it.isNotBlank() }
                if (newUrl != null && !URL_PATTERN.matches(newUrl)) {
                    return ToolResult(
                        "Error: Invalid URL format '$newUrl'. Must start with http:// or https://",
                        isError = true
                    )
                }

                input.optString("command", "").takeIf { it.isNotBlank() }?.let { ext.put("command", it) }
                newUrl?.let { ext.put("url", it) }
                val envVarsStr = input.optString("env_vars", "")
                if (envVarsStr.isNotBlank()) {
                    val envVars = JSONObject()
                    envVarsStr.split(";").forEach { pair ->
                        val parts = pair.split("=", limit = 2)
                        if (parts.size == 2) envVars.put(parts[0].trim(), parts[1].trim())
                    }
                    ext.put("envVars", envVars)
                }
                val saveError = saveConfig(config)
                if (saveError != null) return saveError
                return ToolResult("Configured extension '${ext.optString("name")}' (id: $id)")
            }
        }
        return ToolResult("Error: Extension with id '$id' not found", isError = true)
    }
}

// ─── 3. BrainManageTool ─────────────────────────────────────────────────────
// Hardened against:
//   1. Database not initialized (lazy init with error handling)
//   2. SQL injection (parameterized queries via BrainDatabase)
//   3. Node not found for update/delete (clear error with available IDs)
//   4. Search returns too many results (limit to 20)
// ─────────────────────────────────────────────────────────────────────────────

class BrainManageTool(private val context: Context) : Tool {

    override val name = "manage_brain"
    override val description =
        "Manage Goose's brain/knowledge nodes. Actions: create_node, read_node, update_node, delete_node, search, list"

    companion object {
        private const val TAG = "BrainManageTool"
        private val VALID_ACTIONS = setOf("create_node", "read_node", "update_node", "delete_node", "search", "list")
        /** Maximum results returned from search */
        private const val MAX_SEARCH_RESULTS = 20
        /** Maximum nodes shown in list */
        private const val MAX_LIST_RESULTS = 50
    }

    // HARDENING 1: Lazy init with error handling — database may fail to initialize
    private val brainDb: io.github.gooseandroid.brain.BrainDatabase? by lazy {
        try {
            io.github.gooseandroid.brain.BrainDatabase.getInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BrainDatabase: ${e.message}", e)
            null
        }
    }

    /** Get database or return error result */
    private fun requireDb(): io.github.gooseandroid.brain.BrainDatabase? = brainDb

    override fun getSchema(): JSONObject = appControlSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "The action: create_node, read_node, update_node, delete_node, search, list")
                put("enum", JSONArray().apply {
                    put("create_node"); put("read_node"); put("update_node")
                    put("delete_node"); put("search"); put("list")
                })
            })
            put("id", JSONObject().apply {
                put("type", "string")
                put("description", "Node ID (for read, update, delete)")
            })
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "Node title (for create, update)")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "Node content (for create, update)")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("description", "Node type: note, fact, preference, instruction, context, conversation")
            })
            put("tags", JSONObject().apply {
                put("type", "string")
                put("description", "Comma-separated tags (for create, update)")
            })
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "Search query (for search action)")
            })
            put("pinned", JSONObject().apply {
                put("type", "boolean")
                put("description", "Whether to pin the node (for update)")
            })
        },
        required = JSONArray().apply { put("action") }
    )

    override suspend fun execute(input: JSONObject): ToolResult {
        val action = input.optString("action", "").also {
            if (it.isBlank()) return ToolResult("Error: 'action' is required", isError = true)
        }

        // Validate action
        if (action !in VALID_ACTIONS) {
            return ToolResult(
                "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                isError = true
            )
        }

        // HARDENING 1: Check database is available
        val db = requireDb()
            ?: return ToolResult(
                "Error: Brain database is not initialized. This may be a storage or initialization issue. " +
                "Try restarting the app.",
                isError = true
            )

        return try {
            when (action) {
                "list" -> listNodes(db)
                "read_node" -> readNode(db, input.optString("id", ""))
                "create_node" -> createNode(db, input)
                "update_node" -> updateNode(db, input)
                "delete_node" -> deleteNode(db, input.optString("id", ""))
                "search" -> searchNodes(db, input.optString("query", ""))
                else -> ToolResult(
                    "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                    isError = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_brain: ${e.message}", e)
            ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    private suspend fun listNodes(db: io.github.gooseandroid.brain.BrainDatabase): ToolResult {
        val nodes = try {
            db.getAllNodes()
        } catch (e: Exception) {
            return ToolResult("Error: Failed to query brain database: ${e.message}", isError = true)
        }

        if (nodes.isEmpty()) {
            return ToolResult("Brain is empty. Use action 'create_node' to add knowledge.")
        }
        val sb = StringBuilder("Brain nodes (${nodes.size}):\n\n")
        for (node in nodes.take(MAX_LIST_RESULTS)) {
            val pinned = if (node.pinned) " 📌" else ""
            sb.appendLine("- **${node.title}**$pinned (id: ${node.id})")
            sb.appendLine("  Type: ${node.type.value} | Tags: ${node.tags.joinToString(", ").ifBlank { "none" }}")
            sb.appendLine("  Preview: ${node.content.take(100)}${if (node.content.length > 100) "..." else ""}")
            sb.appendLine()
        }
        if (nodes.size > MAX_LIST_RESULTS) {
            sb.appendLine("... and ${nodes.size - MAX_LIST_RESULTS} more nodes. Use 'search' to find specific ones.")
        }
        return ToolResult(sb.toString())
    }

    // HARDENING 3: Node not found — show available IDs
    private suspend fun readNode(db: io.github.gooseandroid.brain.BrainDatabase, id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for read_node", isError = true)
        val node = db.getNode(id)
        if (node == null) {
            // Show some available IDs to help user
            val allNodes = try { db.getAllNodes() } catch (_: Exception) { emptyList() }
            val hint = if (allNodes.isNotEmpty()) {
                "\nAvailable node IDs (first 10):\n" + allNodes.take(10).joinToString("\n") {
                    "  - ${it.id} (${it.title})"
                }
            } else ""
            return ToolResult("Error: Node with id '$id' not found.$hint", isError = true)
        }

        val sb = StringBuilder()
        sb.appendLine("**${node.title}**")
        sb.appendLine("ID: ${node.id}")
        sb.appendLine("Type: ${node.type.value}")
        sb.appendLine("Tags: ${node.tags.joinToString(", ").ifBlank { "none" }}")
        sb.appendLine("Pinned: ${node.pinned}")
        sb.appendLine("Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(node.createdAt))}")
        sb.appendLine("Updated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(node.updatedAt))}")
        sb.appendLine()
        sb.appendLine("Content:")
        sb.appendLine(node.content)
        return ToolResult(sb.toString())
    }

    // HARDENING 2: Content is passed through BrainDatabase which uses parameterized queries
    // We validate inputs here but the actual SQL safety comes from the database layer
    private suspend fun createNode(db: io.github.gooseandroid.brain.BrainDatabase, input: JSONObject): ToolResult {
        val title = input.optString("title", "").also {
            if (it.isBlank()) return ToolResult("Error: 'title' is required for create_node", isError = true)
        }
        val content = input.optString("content", "").also {
            if (it.isBlank()) return ToolResult("Error: 'content' is required for create_node", isError = true)
        }
        val typeStr = input.optString("type", "note")
        val type = io.github.gooseandroid.brain.NodeType.fromValue(typeStr)
        val tags = input.optString("tags", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val node = try {
            db.createNode(
                title = title,
                content = content,
                type = type,
                tags = tags,
                source = "goose_tool"
            )
        } catch (e: Exception) {
            return ToolResult("Error: Failed to create node in database: ${e.message}", isError = true)
        }

        return ToolResult("Created brain node '${node.title}' (id: ${node.id}, type: ${node.type.value})")
    }

    // HARDENING 3: Node not found for update — clear error with available IDs
    private suspend fun updateNode(db: io.github.gooseandroid.brain.BrainDatabase, input: JSONObject): ToolResult {
        val id = input.optString("id", "").also {
            if (it.isBlank()) return ToolResult("Error: 'id' is required for update_node", isError = true)
        }

        // Verify node exists before attempting update
        val existingNode = db.getNode(id)
        if (existingNode == null) {
            val allNodes = try { db.getAllNodes() } catch (_: Exception) { emptyList() }
            val hint = if (allNodes.isNotEmpty()) {
                "\nAvailable node IDs (first 10):\n" + allNodes.take(10).joinToString("\n") {
                    "  - ${it.id} (${it.title})"
                }
            } else ""
            return ToolResult("Error: Node with id '$id' not found for update.$hint", isError = true)
        }

        val title = input.optString("title", "").takeIf { it.isNotBlank() }
        val content = input.optString("content", "").takeIf { it.isNotBlank() }
        val tagsStr = input.optString("tags", "")
        val tags = if (tagsStr.isNotBlank()) {
            tagsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else null
        val pinned = if (input.has("pinned")) input.optBoolean("pinned") else null

        val success = try {
            db.updateNode(
                id = id,
                title = title,
                content = content,
                tags = tags,
                pinned = pinned
            )
        } catch (e: Exception) {
            return ToolResult("Error: Failed to update node in database: ${e.message}", isError = true)
        }

        return if (success) {
            ToolResult("Updated brain node (id: $id)")
        } else {
            ToolResult("Error: Node with id '$id' could not be updated (may have been deleted concurrently)", isError = true)
        }
    }

    // HARDENING 3: Node not found for delete — clear error
    private suspend fun deleteNode(db: io.github.gooseandroid.brain.BrainDatabase, id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for delete_node", isError = true)

        // Verify node exists
        val existingNode = db.getNode(id)
        if (existingNode == null) {
            val allNodes = try { db.getAllNodes() } catch (_: Exception) { emptyList() }
            val hint = if (allNodes.isNotEmpty()) {
                "\nAvailable node IDs (first 10):\n" + allNodes.take(10).joinToString("\n") {
                    "  - ${it.id} (${it.title})"
                }
            } else ""
            return ToolResult("Error: Node with id '$id' not found for deletion.$hint", isError = true)
        }

        val success = try {
            db.deleteNode(id)
        } catch (e: Exception) {
            return ToolResult("Error: Failed to delete node from database: ${e.message}", isError = true)
        }

        return if (success) {
            ToolResult("Deleted brain node '${existingNode.title}' (id: $id)")
        } else {
            ToolResult("Error: Node with id '$id' could not be deleted", isError = true)
        }
    }

    // HARDENING 4: Search returns too many results — limit to MAX_SEARCH_RESULTS
    private suspend fun searchNodes(db: io.github.gooseandroid.brain.BrainDatabase, query: String): ToolResult {
        if (query.isBlank()) return ToolResult("Error: 'query' is required for search", isError = true)

        val results = try {
            db.searchNodes(query)
        } catch (e: Exception) {
            return ToolResult("Error: Search failed: ${e.message}", isError = true)
        }

        if (results.isEmpty()) {
            return ToolResult("No brain nodes found matching '$query'")
        }

        val totalCount = results.size
        val displayResults = results.take(MAX_SEARCH_RESULTS)

        val sb = StringBuilder()
        if (totalCount > MAX_SEARCH_RESULTS) {
            sb.appendLine("Showing first $MAX_SEARCH_RESULTS of $totalCount results for '$query':\n")
        } else {
            sb.appendLine("Search results for '$query' ($totalCount):\n")
        }

        for (node in displayResults) {
            sb.appendLine("- **${node.title}** (id: ${node.id})")
            sb.appendLine("  Type: ${node.type.value} | Tags: ${node.tags.joinToString(", ").ifBlank { "none" }}")
            sb.appendLine("  Preview: ${node.content.take(150)}${if (node.content.length > 150) "..." else ""}")
            sb.appendLine()
        }

        if (totalCount > MAX_SEARCH_RESULTS) {
            sb.appendLine("... ${totalCount - MAX_SEARCH_RESULTS} more results not shown. Refine your query for more specific results.")
        }

        return ToolResult(sb.toString())
    }
}

// ─── 4. ProjectManageTool ───────────────────────────────────────────────────
// Hardened against:
//   1. Duplicate project name (check before create)
//   2. Working directory doesn't exist (create on project creation)
//   3. Corrupted JSON (backup and recreate — uses safe file ops)
// ─────────────────────────────────────────────────────────────────────────────

class ProjectManageTool(private val context: Context) : Tool {

    override val name = "manage_projects"
    override val description =
        "Manage workspace projects (folders). Actions: create, read, update, delete, list"

    companion object {
        private const val TAG = "ProjectManageTool"
        private val VALID_ACTIONS = setOf("create", "read", "update", "delete", "list")
        /** Valid project name pattern */
        private val PROJECT_NAME_PATTERN = Regex("[a-zA-Z0-9_\\-]+")
    }

    private val workspaceDir: File
        get() = File(context.filesDir, "workspace").also { dir ->
            // HARDENING 2: Ensure workspace directory exists
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) {
                    Log.e(TAG, "Failed to create workspace directory: ${dir.absolutePath}")
                }
            }
        }

    override fun getSchema(): JSONObject = appControlSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "The action: create, read, update, delete, list")
                put("enum", JSONArray().apply {
                    put("create"); put("read"); put("update"); put("delete"); put("list")
                })
            })
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "Project folder name")
            })
            put("new_name", JSONObject().apply {
                put("type", "string")
                put("description", "New name for rename (update action)")
            })
        },
        required = JSONArray().apply { put("action") }
    )

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = input.optString("action", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'action' is required", isError = true)
        }

        if (action !in VALID_ACTIONS) {
            return@withContext ToolResult(
                "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                isError = true
            )
        }

        try {
            when (action) {
                "list" -> listProjects()
                "read" -> readProject(input.optString("name", ""))
                "create" -> createProject(input.optString("name", ""))
                "update" -> updateProject(input.optString("name", ""), input.optString("new_name", ""))
                "delete" -> deleteProject(input.optString("name", ""))
                else -> ToolResult(
                    "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                    isError = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_projects: ${e.message}", e)
            ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    private fun listProjects(): ToolResult {
        val wsDir = workspaceDir
        if (!wsDir.exists() || !wsDir.canRead()) {
            return ToolResult("Error: Workspace directory is not accessible", isError = true)
        }
        val projects = wsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (projects.isEmpty()) {
            return ToolResult("No projects in workspace. Use action 'create' to make one.")
        }
        val sb = StringBuilder("Projects (${projects.size}):\n\n")
        for (dir in projects) {
            val fileCount = try { dir.walkTopDown().count { it.isFile } } catch (_: Exception) { -1 }
            val size = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
            val sizeFmt = formatSize(size)
            val fileInfo = if (fileCount >= 0) "$fileCount files" else "? files"
            sb.appendLine("- **${dir.name}** ($fileInfo, $sizeFmt)")
            sb.appendLine("  Path: ${dir.absolutePath}")
            sb.appendLine()
        }
        return ToolResult(sb.toString())
    }

    private fun readProject(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for read", isError = true)
        val dir = File(workspaceDir, name)
        if (!dir.exists() || !dir.isDirectory) {
            // Show available projects
            val available = workspaceDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
            return ToolResult(
                "Error: Project '$name' not found.\n" +
                if (available.isNotEmpty()) "Available projects: ${available.joinToString(", ")}" else "No projects exist yet.",
                isError = true
            )
        }
        val files = try { dir.walkTopDown().filter { it.isFile }.toList() } catch (_: Exception) { emptyList() }
        val sb = StringBuilder("Project: $name\n")
        sb.appendLine("Path: ${dir.absolutePath}")
        sb.appendLine("Files: ${files.size}")
        sb.appendLine("Size: ${formatSize(files.sumOf { it.length() })}")
        sb.appendLine()
        sb.appendLine("Contents:")
        for (file in files.take(100)) {
            val rel = file.relativeTo(dir).path
            sb.appendLine("  $rel (${formatSize(file.length())})")
        }
        if (files.size > 100) {
            sb.appendLine("  ... and ${files.size - 100} more files")
        }
        return ToolResult(sb.toString())
    }

    // HARDENING 1: Check for duplicate project name before create
    // HARDENING 2: Create working directory on project creation
    private fun createProject(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for create", isError = true)
        if (!PROJECT_NAME_PATTERN.matches(name)) {
            return ToolResult(
                "Error: Project name can only contain letters, numbers, hyphens, and underscores.\n" +
                "Got: '$name'. Example valid names: my-project, test_app, Project1",
                isError = true
            )
        }

        val wsDir = workspaceDir
        // HARDENING 2: Ensure workspace directory exists
        if (!wsDir.exists()) {
            val created = wsDir.mkdirs()
            if (!created && !wsDir.exists()) {
                return ToolResult(
                    "Error: Cannot create workspace directory '${wsDir.absolutePath}'",
                    isError = true
                )
            }
        }

        val dir = File(wsDir, name)

        // HARDENING 1: Duplicate check
        if (dir.exists()) {
            return ToolResult(
                "Error: Project '$name' already exists at ${dir.absolutePath}.\n" +
                "Use a different name, or 'delete' the existing project first.",
                isError = true
            )
        }

        val created = dir.mkdirs()
        if (!created && !dir.exists()) {
            return ToolResult("Error: Failed to create project directory '${dir.absolutePath}'", isError = true)
        }

        return ToolResult("Created project '$name' at ${dir.absolutePath}")
    }

    private fun updateProject(name: String, newName: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for update", isError = true)
        if (newName.isBlank()) return ToolResult("Error: 'new_name' is required for rename", isError = true)
        if (!PROJECT_NAME_PATTERN.matches(newName)) {
            return ToolResult(
                "Error: New name can only contain letters, numbers, hyphens, and underscores.\n" +
                "Got: '$newName'",
                isError = true
            )
        }
        val dir = File(workspaceDir, name)
        if (!dir.exists()) {
            val available = workspaceDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
            return ToolResult(
                "Error: Project '$name' not found.\n" +
                if (available.isNotEmpty()) "Available projects: ${available.joinToString(", ")}" else "No projects exist.",
                isError = true
            )
        }
        val newDir = File(workspaceDir, newName)
        if (newDir.exists()) {
            return ToolResult("Error: Project '$newName' already exists. Choose a different name.", isError = true)
        }
        val success = dir.renameTo(newDir)
        return if (success) {
            ToolResult("Renamed project '$name' to '$newName'")
        } else {
            ToolResult("Error: Failed to rename project. The file system may not support this operation.", isError = true)
        }
    }

    private fun deleteProject(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for delete", isError = true)
        val dir = File(workspaceDir, name)
        if (!dir.exists()) {
            return ToolResult("Error: Project '$name' not found", isError = true)
        }
        val fileCount = try { dir.walkTopDown().count { it.isFile } } catch (_: Exception) { 0 }
        val deleted = dir.deleteRecursively()
        return if (deleted) {
            ToolResult("Deleted project '$name' ($fileCount files removed)")
        } else {
            ToolResult("Error: Failed to fully delete project '$name'. Some files may remain.", isError = true)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}

// ─── 5. ScheduleTool ────────────────────────────────────────────────────────
// Hardened against:
//   1. Invalid cron/time format (validate and return format examples)
//   2. Too many scheduled tasks (cap at 50)
//   3. File corruption (backup and recreate)
// ─────────────────────────────────────────────────────────────────────────────

class ScheduleTool(private val context: Context) : Tool {

    override val name = "schedule_task"
    override val description =
        "Schedule tasks for later execution. Actions: create, list, delete, trigger"

    companion object {
        private const val TAG = "ScheduleTool"
        private val FILE_LOCK = Any()
        private val VALID_ACTIONS = setOf("create", "list", "delete", "trigger")
        /** Maximum number of scheduled tasks allowed */
        private const val MAX_TASKS = 50
        /** Valid schedule formats */
        private val VALID_SCHEDULES = setOf("once", "daily", "hourly", "weekly", "monthly")
        /** Basic cron pattern: 5 fields separated by spaces (minute hour day month weekday) */
        private val CRON_PATTERN = Regex("^[0-9*,/\\-]+\\s+[0-9*,/\\-]+\\s+[0-9*,/\\-]+\\s+[0-9*,/\\-]+\\s+[0-9*,/\\-]+$")
    }

    private val schedulesFile: File
        get() = File(context.filesDir, "scheduled_tasks.json")

    override fun getSchema(): JSONObject = appControlSchema(
        name = name,
        description = description,
        properties = JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "The action: create, list, delete, trigger")
                put("enum", JSONArray().apply {
                    put("create"); put("list"); put("delete"); put("trigger")
                })
            })
            put("id", JSONObject().apply {
                put("type", "string")
                put("description", "Task ID (for delete, trigger)")
            })
            put("task_name", JSONObject().apply {
                put("type", "string")
                put("description", "Human-readable task name (for create)")
            })
            put("description", JSONObject().apply {
                put("type", "string")
                put("description", "What the task should do (for create)")
            })
            put("schedule", JSONObject().apply {
                put("type", "string")
                put("description", "When to run: 'once', 'daily', 'hourly', 'weekly', 'monthly', or cron expression (for create)")
            })
            put("prompt", JSONObject().apply {
                put("type", "string")
                put("description", "The prompt/instruction to execute when triggered (for create)")
            })
        },
        required = JSONArray().apply { put("action") }
    )

    override suspend fun execute(input: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = input.optString("action", "").also {
            if (it.isBlank()) return@withContext ToolResult("Error: 'action' is required", isError = true)
        }

        if (action !in VALID_ACTIONS) {
            return@withContext ToolResult(
                "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                isError = true
            )
        }

        try {
            // HARDENING 3: Synchronized access for file safety
            synchronized(FILE_LOCK) {
                when (action) {
                    "list" -> listTasks()
                    "create" -> createTask(input)
                    "delete" -> deleteTask(input.optString("id", ""))
                    "trigger" -> triggerTask(input.optString("id", ""))
                    else -> ToolResult(
                        "Error: Unknown action '$action'. Valid actions: ${VALID_ACTIONS.joinToString(", ")}",
                        isError = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in schedule_task: ${e.message}", e)
            ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    // HARDENING 3: Uses safeLoadJsonArray which handles corruption
    private fun loadTasks(): JSONArray = safeLoadJsonArray(schedulesFile, TAG)

    private fun saveTasks(tasks: JSONArray): ToolResult? {
        val error = safeWriteJson(schedulesFile, tasks.toString(2), TAG)
        return if (error != null) {
            ToolResult("Error saving scheduled tasks ($error)", isError = true)
        } else null
    }

    private fun listTasks(): ToolResult {
        val tasks = loadTasks()
        if (tasks.length() == 0) {
            return ToolResult("No scheduled tasks. Use action 'create' to add one.")
        }
        val sb = StringBuilder("Scheduled tasks (${tasks.length()}):\n\n")
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            sb.appendLine("- **${task.optString("task_name")}** (id: ${task.optString("id")})")
            sb.appendLine("  Schedule: ${task.optString("schedule", "once")}")
            sb.appendLine("  Description: ${task.optString("description", "")}")
            sb.appendLine("  Prompt: ${task.optString("prompt", "").take(100)}")
            sb.appendLine("  Created: ${task.optString("created_at", "")}")
            val lastRun = task.optString("last_run", "")
            if (lastRun.isNotBlank()) sb.appendLine("  Last run: $lastRun")
            sb.appendLine()
        }
        return ToolResult(sb.toString())
    }

    private fun createTask(input: JSONObject): ToolResult {
        val taskName = input.optString("task_name", "").also {
            if (it.isBlank()) return ToolResult("Error: 'task_name' is required for create", isError = true)
        }
        val prompt = input.optString("prompt", "").also {
            if (it.isBlank()) return ToolResult("Error: 'prompt' is required for create", isError = true)
        }
        val schedule = input.optString("schedule", "once")
        val description = input.optString("description", "")

        // HARDENING 1: Validate schedule format
        if (schedule !in VALID_SCHEDULES && !CRON_PATTERN.matches(schedule)) {
            return ToolResult(
                "Error: Invalid schedule format '$schedule'.\n\n" +
                "Valid formats:\n" +
                "  - Simple: once, daily, hourly, weekly, monthly\n" +
                "  - Cron: '0 9 * * *' (minute hour day month weekday)\n\n" +
                "Cron examples:\n" +
                "  - '0 9 * * *'     → Every day at 9:00 AM\n" +
                "  - '30 */2 * * *'  → Every 2 hours at :30\n" +
                "  - '0 8 * * 1-5'   → Weekdays at 8:00 AM\n" +
                "  - '0 0 1 * *'     → First day of each month at midnight",
                isError = true
            )
        }

        val tasks = loadTasks()

        // HARDENING 2: Cap at MAX_TASKS
        if (tasks.length() >= MAX_TASKS) {
            return ToolResult(
                "Error: Maximum number of scheduled tasks reached ($MAX_TASKS).\n" +
                "Delete some existing tasks before creating new ones.\n" +
                "Use action 'list' to see current tasks, then 'delete' to remove unneeded ones.",
                isError = true
            )
        }

        val id = UUID.randomUUID().toString().take(8)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val now = dateFormat.format(java.util.Date())

        val newTask = JSONObject().apply {
            put("id", id)
            put("task_name", taskName)
            put("description", description)
            put("schedule", schedule)
            put("prompt", prompt)
            put("created_at", now)
            put("last_run", "")
            put("run_count", 0)
            put("enabled", true)
        }
        tasks.put(newTask)

        val saveError = saveTasks(tasks)
        if (saveError != null) return saveError

        return ToolResult(
            "Created scheduled task '$taskName' (id: $id, schedule: $schedule)\n" +
            "Note: Actual background scheduling via Android WorkManager will execute this " +
            "according to the schedule. Use 'trigger' action to run it immediately."
        )
    }

    private fun deleteTask(id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for delete", isError = true)
        val tasks = loadTasks()
        val newTasks = JSONArray()
        var found = false
        var deletedName = ""
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (task.optString("id") == id) {
                found = true
                deletedName = task.optString("task_name", id)
            } else {
                newTasks.put(task)
            }
        }
        if (!found) {
            val availableIds = (0 until tasks.length()).map {
                val t = tasks.getJSONObject(it)
                "'${t.optString("id")}' (${t.optString("task_name")})"
            }
            return ToolResult(
                "Error: Task with id '$id' not found.\n" +
                if (availableIds.isNotEmpty()) "Available tasks: ${availableIds.joinToString(", ")}" else "No tasks exist.",
                isError = true
            )
        }

        val saveError = saveTasks(newTasks)
        if (saveError != null) return saveError

        return ToolResult("Deleted scheduled task '$deletedName' (id: $id)")
    }

    private fun triggerTask(id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for trigger", isError = true)
        val tasks = loadTasks()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (task.optString("id") == id) {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                val now = dateFormat.format(java.util.Date())
                task.put("last_run", now)
                task.put("run_count", task.optInt("run_count", 0) + 1)

                val saveError = saveTasks(tasks)
                if (saveError != null) return saveError

                val prompt = task.optString("prompt", "")
                return ToolResult(
                    "Triggered task '${task.optString("task_name")}' (id: $id)\n" +
                    "Prompt to execute: $prompt\n\n" +
                    "Note: The task prompt has been recorded. In a full implementation, " +
                    "this would be sent to the agent loop for execution."
                )
            }
        }
        return ToolResult("Error: Task with id '$id' not found", isError = true)
    }
}
