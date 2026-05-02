package io.github.gooseandroid.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/**
 * Brain — Goose's local knowledge/memory system.
 *
 * A SQLite-backed node graph that functions as a local RAG system.
 * Each node stores a piece of knowledge with metadata, tags, and embeddings.
 *
 * Features:
 * - Create, edit, delete individual nodes
 * - Tag-based organization and search
 * - Full-text search across all nodes
 * - Import/export entire brain as JSON (for device migration)
 * - Import/export individual nodes
 * - Goose can store/retrieve information on request
 * - Functions as context for smaller local models
 *
 * Export format is portable — download from one device, upload to another.
 */
class BrainDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        private const val TAG = "BrainDB"
        private const val DATABASE_NAME = "goose_brain.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_NODES = "nodes"
        private const val TABLE_TAGS = "tags"
        private const val TABLE_NODE_TAGS = "node_tags"

        @Volatile private var instance: BrainDatabase? = null
        fun getInstance(context: Context): BrainDatabase =
            instance ?: synchronized(this) {
                instance ?: BrainDatabase(context.applicationContext).also { instance = it }
            }
    }

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    // Observable state for UI reactivity
    private val _nodesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val nodesChanged: SharedFlow<Unit> = _nodesChanged.asSharedFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NODES (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                node_type TEXT NOT NULL DEFAULT 'note',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                source TEXT DEFAULT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_TAGS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_NODE_TAGS (
                node_id TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                PRIMARY KEY (node_id, tag_id),
                FOREIGN KEY (node_id) REFERENCES $TABLE_NODES(id) ON DELETE CASCADE,
                FOREIGN KEY (tag_id) REFERENCES $TABLE_TAGS(id) ON DELETE CASCADE
            )
        """)

        // Full-text search index (FTS5 — available on Android API 24+)
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(
                    title, content, content=$TABLE_NODES, content_rowid=rowid
                )
            """)

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS nodes_ai AFTER INSERT ON $TABLE_NODES BEGIN
                    INSERT INTO nodes_fts(rowid, title, content) VALUES (new.rowid, new.title, new.content);
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS nodes_ad AFTER DELETE ON $TABLE_NODES BEGIN
                    INSERT INTO nodes_fts(nodes_fts, rowid, title, content) VALUES('delete', old.rowid, old.title, old.content);
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS nodes_au AFTER UPDATE ON $TABLE_NODES BEGIN
                    INSERT INTO nodes_fts(nodes_fts, rowid, title, content) VALUES('delete', old.rowid, old.title, old.content);
                    INSERT INTO nodes_fts(rowid, title, content) VALUES (new.rowid, new.title, new.content);
                END
            """)
            Log.i(TAG, "FTS5 index created successfully")
        } catch (e: Exception) {
            Log.w(TAG, "FTS5 not available, falling back to LIKE search", e)
            // FTS5 is optional — search will use LIKE fallback
        }

        Log.i(TAG, "Brain database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    // === CRUD Operations ===

    suspend fun createNode(
        title: String,
        content: String,
        type: NodeType = NodeType.NOTE,
        tags: List<String> = emptyList(),
        source: String? = null
    ): BrainNode = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("content", content)
            put("node_type", type.value)
            put("created_at", now)
            put("updated_at", now)
            put("pinned", 0)
            put("source", source)
        }
        db.insert(TABLE_NODES, null, values)

        // Add tags
        tags.forEach { tagName -> addTagToNode(db, id, tagName) }

        _nodesChanged.tryEmit(Unit)
        Log.i(TAG, "Created node: $title ($id)")

        BrainNode(
            id = id,
            title = title,
            content = content,
            type = type,
            tags = tags,
            createdAt = now,
            updatedAt = now,
            pinned = false,
            source = source
        )
    }

    suspend fun updateNode(
        id: String,
        title: String? = null,
        content: String? = null,
        tags: List<String>? = null,
        pinned: Boolean? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            title?.let { put("title", it) }
            content?.let { put("content", it) }
            pinned?.let { put("pinned", if (it) 1 else 0) }
            put("updated_at", System.currentTimeMillis())
        }

        val updated = db.update(TABLE_NODES, values, "id = ?", arrayOf(id))

        // Update tags if provided
        if (tags != null) {
            db.delete(TABLE_NODE_TAGS, "node_id = ?", arrayOf(id))
            tags.forEach { tagName -> addTagToNode(db, id, tagName) }
        }

        _nodesChanged.tryEmit(Unit)
        updated > 0
    }

    suspend fun deleteNode(id: String): Boolean = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val deleted = db.delete(TABLE_NODES, "id = ?", arrayOf(id))
        _nodesChanged.tryEmit(Unit)
        deleted > 0
    }

    suspend fun getNode(id: String): BrainNode? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_NODES WHERE id = ?", arrayOf(id)
        )
        cursor.use {
            if (it.moveToFirst()) cursorToNode(db, it) else null
        }
    }

    suspend fun getAllNodes(): List<BrainNode> = withContext(Dispatchers.IO) {
        val db = readableDatabase

        // Load all tags in one query to avoid N+1
        val allNodeTags = loadAllNodeTags(db)

        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_NODES ORDER BY pinned DESC, updated_at DESC", null
        )
        val nodes = mutableListOf<BrainNode>()
        cursor.use {
            while (it.moveToNext()) {
                cursorToNodeWithTags(it, allNodeTags)?.let { node -> nodes.add(node) }
            }
        }
        nodes
    }

    suspend fun searchNodes(query: String): List<BrainNode> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val nodes = mutableListOf<BrainNode>()

        // Try FTS5 first, fall back to LIKE
        try {
            val cursor = db.rawQuery("""
                SELECT n.* FROM $TABLE_NODES n
                INNER JOIN nodes_fts fts ON n.rowid = fts.rowid
                WHERE nodes_fts MATCH ?
                ORDER BY rank
            """, arrayOf(query))
            cursor.use {
                while (it.moveToNext()) {
                    cursorToNode(db, it)?.let { node -> nodes.add(node) }
                }
            }
        } catch (e: Exception) {
            // FTS5 not available — use LIKE search
            val likeQuery = "%$query%"
            val cursor = db.rawQuery("""
                SELECT * FROM $TABLE_NODES
                WHERE title LIKE ? OR content LIKE ?
                ORDER BY updated_at DESC
            """, arrayOf(likeQuery, likeQuery))
            cursor.use {
                while (it.moveToNext()) {
                    cursorToNode(db, it)?.let { node -> nodes.add(node) }
                }
            }
        }
        nodes
    }

    suspend fun getNodesByTag(tag: String): List<BrainNode> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT n.* FROM $TABLE_NODES n
            INNER JOIN $TABLE_NODE_TAGS nt ON n.id = nt.node_id
            INNER JOIN $TABLE_TAGS t ON nt.tag_id = t.id
            WHERE t.name = ?
            ORDER BY n.updated_at DESC
        """, arrayOf(tag))
        val nodes = mutableListOf<BrainNode>()
        cursor.use {
            while (it.moveToNext()) {
                cursorToNode(db, it)?.let { node -> nodes.add(node) }
            }
        }
        nodes
    }

    suspend fun getAllTags(): List<String> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT name FROM $TABLE_TAGS ORDER BY name", null)
        val tags = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                tags.add(it.getString(0))
            }
        }
        tags
    }

    suspend fun getNodeCount(): Int = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NODES", null)
        cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // === Import / Export ===

    /**
     * Export the entire brain as a JSON string.
     * Portable format — can be imported on any Goose instance.
     */
    suspend fun exportBrain(): String = withContext(Dispatchers.IO) {
        val nodes = getAllNodes()
        val export = BrainExport(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            nodeCount = nodes.size,
            nodes = nodes
        )
        json.encodeToString(export)
    }

    /**
     * Export a single node as JSON.
     */
    suspend fun exportNode(id: String): String? = withContext(Dispatchers.IO) {
        val node = getNode(id) ?: return@withContext null
        json.encodeToString(node)
    }

    /**
     * Import brain data from JSON. Merges with existing nodes (by ID).
     * Returns the number of nodes imported.
     */
    suspend fun importBrain(jsonString: String): Int = withContext(Dispatchers.IO) {
        try {
            val export = json.decodeFromString<BrainExport>(jsonString)
            var imported = 0
            export.nodes.forEach { node ->
                val existing = getNode(node.id)
                if (existing == null) {
                    // Insert new node
                    val db = writableDatabase
                    val values = ContentValues().apply {
                        put("id", node.id)
                        put("title", node.title)
                        put("content", node.content)
                        put("node_type", node.type.value)
                        put("created_at", node.createdAt)
                        put("updated_at", node.updatedAt)
                        put("pinned", if (node.pinned) 1 else 0)
                        put("source", node.source)
                    }
                    db.insert(TABLE_NODES, null, values)
                    node.tags.forEach { tag -> addTagToNode(db, node.id, tag) }
                    imported++
                }
            }
            _nodesChanged.tryEmit(Unit)
            Log.i(TAG, "Imported $imported nodes")
            imported
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            0
        }
    }

    /**
     * Import a single node from JSON.
     */
    suspend fun importNode(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val node = json.decodeFromString<BrainNode>(jsonString)
            createNode(
                title = node.title,
                content = node.content,
                type = node.type,
                tags = node.tags,
                source = node.source
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Node import failed", e)
            false
        }
    }

    /**
     * Get brain as a file for sharing/backup.
     */
    suspend fun exportToFile(context: Context): File = withContext(Dispatchers.IO) {
        val exportJson = exportBrain()
        val file = File(context.cacheDir, "goose-brain-export.json")
        file.writeText(exportJson)
        file
    }

    // === RAG Support ===

    /**
     * Find relevant nodes for a given query (simple keyword matching).
     * Used by Goose to inject context from the brain into prompts.
     * Returns top N most relevant nodes.
     */
    suspend fun findRelevant(query: String, limit: Int = 5): List<BrainNode> {
        // First try FTS search
        val ftsResults = searchNodes(query)
        if (ftsResults.isNotEmpty()) return ftsResults.take(limit)

        // Fallback: simple keyword matching on tags and titles
        val allNodes = getAllNodes()
        val queryWords = query.lowercase().split(" ", ",", ".", "?", "!")
        return allNodes
            .map { node ->
                val score = queryWords.count { word ->
                    node.title.lowercase().contains(word) ||
                    node.content.lowercase().contains(word) ||
                    node.tags.any { it.lowercase().contains(word) }
                }
                node to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // === Private helpers ===

    private fun addTagToNode(db: SQLiteDatabase, nodeId: String, tagName: String) {
        // Ensure tag exists
        val tagId = getOrCreateTag(db, tagName)
        // Link node to tag
        val values = ContentValues().apply {
            put("node_id", nodeId)
            put("tag_id", tagId)
        }
        db.insertWithOnConflict(TABLE_NODE_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun getOrCreateTag(db: SQLiteDatabase, name: String): String {
        val cursor = db.rawQuery(
            "SELECT id FROM $TABLE_TAGS WHERE name = ?", arrayOf(name)
        )
        cursor.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("name", name)
        }
        db.insert(TABLE_TAGS, null, values)
        return id
    }

    /**
     * Load all node→tags mappings in a single query to avoid N+1.
     * Returns a map of nodeId → list of tag names.
     */
    private fun loadAllNodeTags(db: SQLiteDatabase): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        val cursor = db.rawQuery("""
            SELECT nt.node_id, t.name FROM $TABLE_NODE_TAGS nt
            INNER JOIN $TABLE_TAGS t ON nt.tag_id = t.id
        """, null)
        cursor.use {
            while (it.moveToNext()) {
                val nodeId = it.getString(0)
                val tagName = it.getString(1)
                result.getOrPut(nodeId) { mutableListOf() }.add(tagName)
            }
        }
        return result
    }

    /**
     * Convert cursor row to BrainNode using a pre-loaded tags map (avoids N+1).
     */
    private fun cursorToNodeWithTags(
        cursor: android.database.Cursor,
        allNodeTags: Map<String, List<String>>
    ): BrainNode? {
        return try {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val tags = allNodeTags[id] ?: emptyList()
            BrainNode(
                id = id,
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                type = NodeType.fromValue(cursor.getString(cursor.getColumnIndexOrThrow("node_type"))),
                tags = tags,
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                pinned = cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) == 1,
                source = cursor.getString(cursor.getColumnIndexOrThrow("source"))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading node from cursor", e)
            null
        }
    }

    private fun cursorToNode(db: SQLiteDatabase, cursor: android.database.Cursor): BrainNode? {
        return try {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val tags = getTagsForNode(db, id)
            BrainNode(
                id = id,
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                type = NodeType.fromValue(cursor.getString(cursor.getColumnIndexOrThrow("node_type"))),
                tags = tags,
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                pinned = cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) == 1,
                source = cursor.getString(cursor.getColumnIndexOrThrow("source"))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading node from cursor", e)
            null
        }
    }

    private fun getTagsForNode(db: SQLiteDatabase, nodeId: String): List<String> {
        val cursor = db.rawQuery("""
            SELECT t.name FROM $TABLE_TAGS t
            INNER JOIN $TABLE_NODE_TAGS nt ON t.id = nt.tag_id
            WHERE nt.node_id = ?
        """, arrayOf(nodeId))
        val tags = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                tags.add(it.getString(0))
            }
        }
        return tags
    }
}

// === Data Models ===

@Serializable
data class BrainNode(
    val id: String,
    val title: String,
    val content: String,
    val type: NodeType = NodeType.NOTE,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val source: String? = null
)

@Serializable
enum class NodeType(val value: String) {
    NOTE("note"),           // General knowledge
    FACT("fact"),           // Specific fact/data point
    PREFERENCE("preference"), // User preference
    INSTRUCTION("instruction"), // How-to / procedure
    CONTEXT("context"),     // Background context
    CONVERSATION("conversation"); // Saved from a chat

    companion object {
        fun fromValue(value: String): NodeType =
            entries.find { it.value == value } ?: NOTE
    }
}

@Serializable
data class BrainExport(
    val version: Int = 1,
    val exportedAt: Long,
    val nodeCount: Int,
    val nodes: List<BrainNode>
)
