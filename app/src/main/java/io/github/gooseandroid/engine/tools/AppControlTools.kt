package io.github.gooseandroid.engine.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Tools that give Goose full CRUD control over the app's features:
 * skills, extensions, schedules, brain nodes, projects, etc.
 *
 * Each tool accepts a JSON input with an "action" field and relevant parameters,
 * returns the result as a formatted string, handles errors gracefully,
 * and persists changes to the appropriate storage.
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

// ─── 1. SkillManageTool ─────────────────────────────────────────────────────

class SkillManageTool(private val context: Context) : Tool {

    override val name = "manage_skills"
    override val description =
        "Manage Goose skills (reusable instruction templates). Actions: create, read, update, delete, list"

    companion object {
        private const val TAG = "SkillManageTool"
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

        try {
            when (action) {
                "list" -> listSkills()
                "read" -> readSkill(input.optString("id", ""))
                "create" -> createSkill(input)
                "update" -> updateSkill(input)
                "delete" -> deleteSkill(input.optString("id", ""))
                else -> ToolResult("Error: Unknown action '$action'. Use: create, read, update, delete, list", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_skills: ${e.message}", e)
            ToolResult("Error: ${e.message}", isError = true)
        }
    }

    private fun loadSkills(): JSONArray {
        if (!skillsFile.exists()) return JSONArray()
        return try {
            JSONArray(skillsFile.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveSkills(skills: JSONArray) {
        skillsFile.writeText(skills.toString(2))
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
        return ToolResult("Error: Skill with id '$id' not found", isError = true)
    }

    private fun createSkill(input: JSONObject): ToolResult {
        val skillName = input.optString("name", "").also {
            if (it.isBlank()) return ToolResult("Error: 'name' is required for create", isError = true)
        }
        val instructions = input.optString("instructions", "").also {
            if (it.isBlank()) return ToolResult("Error: 'instructions' is required for create", isError = true)
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
        saveSkills(skills)

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
                saveSkills(skills)
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
        saveSkills(newSkills)
        return ToolResult("Deleted skill '$deletedName' (id: $id)")
    }
}

// ─── 2. ExtensionManageTool ─────────────────────────────────────────────────

class ExtensionManageTool(private val context: Context) : Tool {

    override val name = "manage_extensions"
    override val description =
        "Manage MCP extensions. Actions: enable, disable, add, remove, list, configure"

    companion object {
        private const val TAG = "ExtensionManageTool"
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

        try {
            when (action) {
                "list" -> listExtensions()
                "add" -> addExtension(input)
                "remove" -> removeExtension(input.optString("id", ""))
                "enable" -> setEnabled(input.optString("id", ""), true)
                "disable" -> setEnabled(input.optString("id", ""), false)
                "configure" -> configureExtension(input)
                else -> ToolResult("Error: Unknown action '$action'", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_extensions: ${e.message}", e)
            ToolResult("Error: ${e.message}", isError = true)
        }
    }

    private fun loadConfig(): JSONObject {
        if (!configFile.exists()) return JSONObject().put("extensions", JSONArray())
        return try {
            JSONObject(configFile.readText())
        } catch (_: Exception) {
            JSONObject().put("extensions", JSONArray())
        }
    }

    private fun saveConfig(config: JSONObject) {
        configFile.writeText(config.toString(2))
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

    private fun addExtension(input: JSONObject): ToolResult {
        val extName = input.optString("name", "").also {
            if (it.isBlank()) return ToolResult("Error: 'name' is required for add", isError = true)
        }
        val transport = input.optString("transport", "stdio")
        val command = input.optString("command", "")
        val url = input.optString("url", "")

        if (transport == "stdio" && command.isBlank()) {
            return ToolResult("Error: 'command' is required for stdio transport", isError = true)
        }
        if (transport == "http" && url.isBlank()) {
            return ToolResult("Error: 'url' is required for http transport", isError = true)
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
        saveConfig(config)

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
        if (!found) return ToolResult("Error: Extension with id '$id' not found", isError = true)
        config.put("extensions", newExtensions)
        saveConfig(config)
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
                saveConfig(config)
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
                input.optString("command", "").takeIf { it.isNotBlank() }?.let { ext.put("command", it) }
                input.optString("url", "").takeIf { it.isNotBlank() }?.let { ext.put("url", it) }
                val envVarsStr = input.optString("env_vars", "")
                if (envVarsStr.isNotBlank()) {
                    val envVars = JSONObject()
                    envVarsStr.split(";").forEach { pair ->
                        val parts = pair.split("=", limit = 2)
                        if (parts.size == 2) envVars.put(parts[0].trim(), parts[1].trim())
                    }
                    ext.put("envVars", envVars)
                }
                saveConfig(config)
                return ToolResult("Configured extension '${ext.optString("name")}' (id: $id)")
            }
        }
        return ToolResult("Error: Extension with id '$id' not found", isError = true)
    }
}

// ─── 3. BrainManageTool ─────────────────────────────────────────────────────

class BrainManageTool(private val context: Context) : Tool {

    override val name = "manage_brain"
    override val description =
        "Manage Goose's brain/knowledge nodes. Actions: create_node, read_node, update_node, delete_node, search, list"

    companion object {
        private const val TAG = "BrainManageTool"
    }

    private val brainDb by lazy {
        io.github.gooseandroid.brain.BrainDatabase.getInstance(context)
    }

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

        return try {
            when (action) {
                "list" -> listNodes()
                "read_node" -> readNode(input.optString("id", ""))
                "create_node" -> createNode(input)
                "update_node" -> updateNode(input)
                "delete_node" -> deleteNode(input.optString("id", ""))
                "search" -> searchNodes(input.optString("query", ""))
                else -> ToolResult("Error: Unknown action '$action'", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_brain: ${e.message}", e)
            ToolResult("Error: ${e.message}", isError = true)
        }
    }

    private suspend fun listNodes(): ToolResult {
        val nodes = brainDb.getAllNodes()
        if (nodes.isEmpty()) {
            return ToolResult("Brain is empty. Use action 'create_node' to add knowledge.")
        }
        val sb = StringBuilder("Brain nodes (${nodes.size}):\n\n")
        for (node in nodes.take(50)) { // Limit to 50 for readability
            val pinned = if (node.pinned) " 📌" else ""
            sb.appendLine("- **${node.title}**$pinned (id: ${node.id})")
            sb.appendLine("  Type: ${node.type.value} | Tags: ${node.tags.joinToString(", ").ifBlank { "none" }}")
            sb.appendLine("  Preview: ${node.content.take(100)}${if (node.content.length > 100) "..." else ""}")
            sb.appendLine()
        }
        if (nodes.size > 50) {
            sb.appendLine("... and ${nodes.size - 50} more nodes. Use 'search' to find specific ones.")
        }
        return ToolResult(sb.toString())
    }

    private suspend fun readNode(id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for read_node", isError = true)
        val node = brainDb.getNode(id)
            ?: return ToolResult("Error: Node with id '$id' not found", isError = true)

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

    private suspend fun createNode(input: JSONObject): ToolResult {
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

        val node = brainDb.createNode(
            title = title,
            content = content,
            type = type,
            tags = tags,
            source = "goose_tool"
        )

        return ToolResult("Created brain node '${node.title}' (id: ${node.id}, type: ${node.type.value})")
    }

    private suspend fun updateNode(input: JSONObject): ToolResult {
        val id = input.optString("id", "").also {
            if (it.isBlank()) return ToolResult("Error: 'id' is required for update_node", isError = true)
        }

        val title = input.optString("title", "").takeIf { it.isNotBlank() }
        val content = input.optString("content", "").takeIf { it.isNotBlank() }
        val tagsStr = input.optString("tags", "")
        val tags = if (tagsStr.isNotBlank()) {
            tagsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else null
        val pinned = if (input.has("pinned")) input.optBoolean("pinned") else null

        val success = brainDb.updateNode(
            id = id,
            title = title,
            content = content,
            tags = tags,
            pinned = pinned
        )

        return if (success) {
            ToolResult("Updated brain node (id: $id)")
        } else {
            ToolResult("Error: Node with id '$id' not found", isError = true)
        }
    }

    private suspend fun deleteNode(id: String): ToolResult {
        if (id.isBlank()) return ToolResult("Error: 'id' is required for delete_node", isError = true)
        val success = brainDb.deleteNode(id)
        return if (success) {
            ToolResult("Deleted brain node (id: $id)")
        } else {
            ToolResult("Error: Node with id '$id' not found", isError = true)
        }
    }

    private suspend fun searchNodes(query: String): ToolResult {
        if (query.isBlank()) return ToolResult("Error: 'query' is required for search", isError = true)
        val results = brainDb.searchNodes(query)
        if (results.isEmpty()) {
            return ToolResult("No brain nodes found matching '$query'")
        }
        val sb = StringBuilder("Search results for '$query' (${results.size}):\n\n")
        for (node in results.take(20)) {
            sb.appendLine("- **${node.title}** (id: ${node.id})")
            sb.appendLine("  Type: ${node.type.value} | Tags: ${node.tags.joinToString(", ").ifBlank { "none" }}")
            sb.appendLine("  Preview: ${node.content.take(150)}${if (node.content.length > 150) "..." else ""}")
            sb.appendLine()
        }
        return ToolResult(sb.toString())
    }
}

// ─── 4. ProjectManageTool ───────────────────────────────────────────────────

class ProjectManageTool(private val context: Context) : Tool {

    override val name = "manage_projects"
    override val description =
        "Manage workspace projects (folders). Actions: create, read, update, delete, list"

    companion object {
        private const val TAG = "ProjectManageTool"
    }

    private val workspaceDir: File
        get() = File(context.filesDir, "workspace").apply { mkdirs() }

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

        try {
            when (action) {
                "list" -> listProjects()
                "read" -> readProject(input.optString("name", ""))
                "create" -> createProject(input.optString("name", ""))
                "update" -> updateProject(input.optString("name", ""), input.optString("new_name", ""))
                "delete" -> deleteProject(input.optString("name", ""))
                else -> ToolResult("Error: Unknown action '$action'", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in manage_projects: ${e.message}", e)
            ToolResult("Error: ${e.message}", isError = true)
        }
    }

    private fun listProjects(): ToolResult {
        val projects = workspaceDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (projects.isEmpty()) {
            return ToolResult("No projects in workspace. Use action 'create' to make one.")
        }
        val sb = StringBuilder("Projects (${projects.size}):\n\n")
        for (dir in projects) {
            val fileCount = dir.walkTopDown().count { it.isFile }
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val sizeFmt = formatSize(size)
            sb.appendLine("- **${dir.name}** ($fileCount files, $sizeFmt)")
            sb.appendLine("  Path: ${dir.absolutePath}")
            sb.appendLine()
        }
        return ToolResult(sb.toString())
    }

    private fun readProject(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for read", isError = true)
        val dir = File(workspaceDir, name)
        if (!dir.exists() || !dir.isDirectory) {
            return ToolResult("Error: Project '$name' not found", isError = true)
        }
        val files = dir.walkTopDown().filter { it.isFile }.toList()
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

    private fun createProject(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for create", isError = true)
        if (!name.matches(Regex("[a-zA-Z0-9_\\-]+"))) {
            return ToolResult("Error: Project name can only contain letters, numbers, hyphens, and underscores", isError = true)
        }
        val dir = File(workspaceDir, name)
        if (dir.exists()) {
            return ToolResult("Error: Project '$name' already exists", isError = true)
        }
        dir.mkdirs()
        return ToolResult("Created project '$name' at ${dir.absolutePath}")
    }

    private fun updateProject(name: String, newName: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for update", isError = true)
        if (newName.isBlank()) return ToolResult("Error: 'new_name' is required for rename", isError = true)
        if (!newName.matches(Regex("[a-zA-Z0-9_\\-]+"))) {
            return ToolResult("Error: New name can only contain letters, numbers, hyphens, and underscores", isError = true)
        }
        val dir = File(workspaceDir, name)
        if (!dir.exists()) return ToolResult("Error: Project '$name' not found", isError = true)
        val newDir = File(workspaceDir, newName)
        if (newDir.exists()) return ToolResult("Error: Project '$newName' already exists", isError = true)
        val success = dir.renameTo(newDir)
        return if (success) {
            ToolResult("Renamed project '$name' to '$newName'")
        } else {
            ToolResult("Error: Failed to rename project", isError = true)
        }
    }

    private fun deleteProject(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("Error: 'name' is required for delete", isError = true)
        val dir = File(workspaceDir, name)
        if (!dir.exists()) return ToolResult("Error: Project '$name' not found", isError = true)
        val fileCount = dir.walkTopDown().count { it.isFile }
        dir.deleteRecursively()
        return ToolResult("Deleted project '$name' ($fileCount files removed)")
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

class ScheduleTool(private val context: Context) : Tool {

    override val name = "schedule_task"
    override val description =
        "Schedule tasks for later execution. Actions: create, list, delete, trigger"

    companion object {
        private const val TAG = "ScheduleTool"
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
                put("description", "When to run: 'once', 'daily', 'hourly', or cron-like expression (for create)")
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

        try {
            when (action) {
                "list" -> listTasks()
                "create" -> createTask(input)
                "delete" -> deleteTask(input.optString("id", ""))
                "trigger" -> triggerTask(input.optString("id", ""))
                else -> ToolResult("Error: Unknown action '$action'", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in schedule_task: ${e.message}", e)
            ToolResult("Error: ${e.message}", isError = true)
        }
    }

    private fun loadTasks(): JSONArray {
        if (!schedulesFile.exists()) return JSONArray()
        return try {
            JSONArray(schedulesFile.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveTasks(tasks: JSONArray) {
        schedulesFile.writeText(tasks.toString(2))
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

        val tasks = loadTasks()
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
        saveTasks(tasks)

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
        if (!found) return ToolResult("Error: Task with id '$id' not found", isError = true)
        saveTasks(newTasks)
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
                saveTasks(tasks)

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
