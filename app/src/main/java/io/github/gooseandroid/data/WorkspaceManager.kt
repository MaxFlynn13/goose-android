package io.github.gooseandroid.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Metadata about a project folder within the workspace.
 */
data class ProjectFolder(
    val name: String,
    val path: File,
    val sizeBytes: Long,
    val fileCount: Int,
    val lastModified: Long
)

/**
 * Aggregate workspace statistics.
 */
data class WorkspaceStats(
    val totalQuotaBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val fileCount: Int,
    val projectCount: Int
)

/**
 * Error types for workspace operations.
 */
sealed class WorkspaceError(override val message: String) : Exception(message) {
    class QuotaExceeded(val required: Long, val available: Long) :
        WorkspaceError("Quota exceeded: need $required bytes, only $available available")

    class FileTooLarge(val size: Long, val limit: Long) :
        WorkspaceError("File too large: $size bytes exceeds limit of $limit bytes")

    class ProjectNotFound(val name: String) :
        WorkspaceError("Project not found: $name")

    class ProjectAlreadyExists(val name: String) :
        WorkspaceError("Project already exists: $name")

    class InvalidProjectName(val name: String) :
        WorkspaceError("Invalid project name: $name (must be alphanumeric, hyphens, underscores)")

    class ExtractionFailed(cause: Throwable) :
        WorkspaceError("Zip extraction failed: ${cause.message}")

    class IoError(cause: Throwable) :
        WorkspaceError("IO error: ${cause.message}")
}

/**
 * WorkspaceManager provides a sandboxed local filesystem for Goose.
 *
 * The workspace lives at `context.filesDir/workspace/` and is the HOME directory
 * for `goose serve`. It enforces storage quotas to protect against OOM conditions
 * while giving Goose full file access within the sandbox.
 *
 * Thread-safe: all mutable state is protected by a mutex, and all IO operations
 * run on Dispatchers.IO.
 */
class WorkspaceManager(private val context: Context) {

    companion object {
        /** Default storage quota: 500 MB */
        const val DEFAULT_QUOTA_BYTES: Long = 500L * 1024L * 1024L

        /** Maximum single file read size: 1 MB */
        const val MAX_READ_SIZE: Long = 1L * 1024L * 1024L

        /** Maximum single file write size: 10 MB */
        const val MAX_WRITE_SIZE: Long = 10L * 1024L * 1024L

        /** Settings key for the workspace quota */
        const val SETTINGS_KEY_QUOTA = "workspace_quota_bytes"

        /** Buffer size for IO operations */
        private const val BUFFER_SIZE = 8192

        /** Valid project name pattern */
        private val PROJECT_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")
    }

    private val settingsStore = SettingsStore(context)
    private val mutex = Mutex()

    // --- Directory structure ---

    /** Root workspace directory — this is Goose's HOME */
    val workspaceRoot: File = File(context.filesDir, "workspace")

    /** Directory for user projects */
    val projectsDir: File = File(workspaceRoot, "projects")

    /** Scratch/temp directory for ephemeral files */
    val scratchDir: File = File(workspaceRoot, "scratch")

    /** Exports directory for zipped project archives */
    val exportsDir: File = File(workspaceRoot, "exports")

    /** Runtimes directory for language runtimes/tools */
    val runtimesDir: File = File(workspaceRoot, "runtimes")

    init {
        // Ensure directory structure exists
        workspaceRoot.mkdirs()
        projectsDir.mkdirs()
        scratchDir.mkdirs()
        exportsDir.mkdirs()
        runtimesDir.mkdirs()
    }

    // =========================================================================
    // Quota Management
    // =========================================================================

    /**
     * Get the configured storage quota in bytes.
     */
    suspend fun getQuotaBytes(): Long {
        return settingsStore.getString(SETTINGS_KEY_QUOTA, DEFAULT_QUOTA_BYTES.toString())
            .first()
            .toLongOrNull() ?: DEFAULT_QUOTA_BYTES
    }

    /**
     * Set the storage quota in bytes.
     */
    suspend fun setQuotaBytes(bytes: Long) {
        require(bytes > 0) { "Quota must be positive" }
        settingsStore.setString(SETTINGS_KEY_QUOTA, bytes.toString())
    }

    /**
     * Calculate total bytes used by the workspace.
     */
    suspend fun getUsedBytes(): Long = withContext(Dispatchers.IO) {
        calculateDirectorySize(workspaceRoot)
    }

    /**
     * Calculate remaining bytes available under the quota.
     */
    suspend fun getRemainingBytes(): Long {
        val quota = getQuotaBytes()
        val used = getUsedBytes()
        return (quota - used).coerceAtLeast(0)
    }

    /**
     * Check whether writing [sizeBytes] would stay within quota.
     */
    suspend fun canWrite(sizeBytes: Long): Boolean {
        return sizeBytes <= getRemainingBytes()
    }

    /**
     * Enforce that a write of [sizeBytes] is allowed.
     * @throws WorkspaceError.QuotaExceeded if quota would be exceeded
     * @throws WorkspaceError.FileTooLarge if size exceeds max write limit
     */
    private suspend fun enforceWriteLimit(sizeBytes: Long) {
        if (sizeBytes > MAX_WRITE_SIZE) {
            throw WorkspaceError.FileTooLarge(sizeBytes, MAX_WRITE_SIZE)
        }
        val remaining = getRemainingBytes()
        if (sizeBytes > remaining) {
            throw WorkspaceError.QuotaExceeded(sizeBytes, remaining)
        }
    }

    // =========================================================================
    // Project Operations
    // =========================================================================

    /**
     * List all projects in the workspace with metadata.
     */
    suspend fun getProjects(): List<ProjectFolder> = withContext(Dispatchers.IO) {
        val dirs = projectsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        dirs.map { dir ->
            ProjectFolder(
                name = dir.name,
                path = dir,
                sizeBytes = calculateDirectorySize(dir),
                fileCount = countFiles(dir),
                lastModified = getLastModified(dir)
            )
        }.sortedByDescending { it.lastModified }
    }

    /**
     * Get a specific project by name.
     */
    suspend fun getProject(name: String): Result<ProjectFolder> = withContext(Dispatchers.IO) {
        val dir = File(projectsDir, name)
        if (!dir.exists() || !dir.isDirectory) {
            return@withContext Result.failure(WorkspaceError.ProjectNotFound(name))
        }
        Result.success(
            ProjectFolder(
                name = dir.name,
                path = dir,
                sizeBytes = calculateDirectorySize(dir),
                fileCount = countFiles(dir),
                lastModified = getLastModified(dir)
            )
        )
    }

    /**
     * Create an empty project directory.
     */
    suspend fun createProject(name: String): Result<File> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isValidProjectName(name)) {
                return@withContext Result.failure(WorkspaceError.InvalidProjectName(name))
            }
            val dir = File(projectsDir, name)
            if (dir.exists()) {
                return@withContext Result.failure(WorkspaceError.ProjectAlreadyExists(name))
            }
            if (dir.mkdirs()) {
                Result.success(dir)
            } else {
                Result.failure(WorkspaceError.IoError(IOException("Failed to create directory: $dir")))
            }
        }
    }

    /**
     * Delete a project and all its contents.
     */
    suspend fun deleteProject(name: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = File(projectsDir, name)
            if (!dir.exists() || !dir.isDirectory) return@withContext false
            dir.deleteRecursively()
        }
    }

    // =========================================================================
    // Import: Zip Archive
    // =========================================================================

    /**
     * Import a zip archive into the workspace as a new project.
     *
     * Extracts the zip contents into `projects/<projectName>/`. Performs quota
     * checks during extraction and rolls back on failure.
     *
     * @param inputStream The zip file input stream
     * @param projectName Name for the new project
     * @return Result containing the project directory on success
     */
    suspend fun importZip(inputStream: InputStream, projectName: String): Result<File> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isValidProjectName(projectName)) {
                    return@withContext Result.failure(WorkspaceError.InvalidProjectName(projectName))
                }

                val projectDir = File(projectsDir, projectName)
                if (projectDir.exists()) {
                    return@withContext Result.failure(WorkspaceError.ProjectAlreadyExists(projectName))
                }

                projectDir.mkdirs()
                var totalExtracted: Long = 0

                try {
                    val quota = getQuotaBytes()
                    val usedBefore = getUsedBytes()
                    val budget = quota - usedBefore

                    ZipInputStream(inputStream.buffered()).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(projectDir, entry.name).canonicalFile

                            // Path traversal protection
                            if (!outFile.canonicalPath.startsWith(projectDir.canonicalPath)) {
                                throw SecurityException("Zip entry outside target dir: ${entry.name}")
                            }

                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()

                                // Check single file size limit
                                if (entry.size > MAX_WRITE_SIZE && entry.size != -1L) {
                                    throw WorkspaceError.FileTooLarge(entry.size, MAX_WRITE_SIZE)
                                }

                                FileOutputStream(outFile).use { fos ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytesRead: Int
                                    var fileSize: Long = 0

                                    while (zis.read(buffer).also { bytesRead = it } != -1) {
                                        fileSize += bytesRead
                                        totalExtracted += bytesRead

                                        // Check per-file limit
                                        if (fileSize > MAX_WRITE_SIZE) {
                                            throw WorkspaceError.FileTooLarge(fileSize, MAX_WRITE_SIZE)
                                        }

                                        // Check quota
                                        if (totalExtracted > budget) {
                                            throw WorkspaceError.QuotaExceeded(totalExtracted, budget)
                                        }

                                        fos.write(buffer, 0, bytesRead)
                                    }
                                }
                            }

                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }

                    Result.success(projectDir)
                } catch (e: WorkspaceError) {
                    // Rollback: delete partially extracted project
                    projectDir.deleteRecursively()
                    Result.failure(e)
                } catch (e: SecurityException) {
                    projectDir.deleteRecursively()
                    Result.failure(WorkspaceError.ExtractionFailed(e))
                } catch (e: Exception) {
                    projectDir.deleteRecursively()
                    Result.failure(WorkspaceError.ExtractionFailed(e))
                }
            }
        }

    // =========================================================================
    // Export: Zip Archive
    // =========================================================================

    /**
     * Export a project as a zip archive.
     *
     * Creates a zip file at `exports/<projectName>.zip` containing all project files.
     *
     * @param projectName Name of the project to export
     * @return The zip file
     * @throws WorkspaceError.ProjectNotFound if project doesn't exist
     */
    suspend fun exportProject(projectName: String): Result<File> = withContext(Dispatchers.IO) {
        val projectDir = File(projectsDir, projectName)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@withContext Result.failure(WorkspaceError.ProjectNotFound(projectName))
        }

        val zipFile = File(exportsDir, "$projectName.zip")

        try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                projectDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relativePath = file.relativeTo(projectDir).path
                        val entry = ZipEntry(relativePath)
                        entry.time = file.lastModified()
                        zos.putNextEntry(entry)

                        file.inputStream().buffered().use { fis ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                zos.write(buffer, 0, bytesRead)
                            }
                        }

                        zos.closeEntry()
                    }
            }

            Result.success(zipFile)
        } catch (e: Exception) {
            zipFile.delete()
            Result.failure(WorkspaceError.IoError(e))
        }
    }

    // =========================================================================
    // Import: SAF (Storage Access Framework) URI
    // =========================================================================

    /**
     * Import files from a SAF content URI tree into the workspace.
     *
     * Copies the directory tree from the content URI into `projects/<projectName>/`.
     * Enforces quota during copy and rolls back on failure.
     *
     * @param context Android context for content resolver access
     * @param uri Content URI (tree) from SAF picker
     * @param projectName Name for the new project
     * @return Result containing the project directory on success
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        projectName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isValidProjectName(projectName)) {
                return@withContext Result.failure(WorkspaceError.InvalidProjectName(projectName))
            }

            val projectDir = File(projectsDir, projectName)
            if (projectDir.exists()) {
                return@withContext Result.failure(WorkspaceError.ProjectAlreadyExists(projectName))
            }

            projectDir.mkdirs()
            var totalCopied: Long = 0

            try {
                val quota = getQuotaBytes()
                val usedBefore = getUsedBytes()
                val budget = quota - usedBefore

                val documentFile = DocumentFile.fromTreeUri(context, uri)
                    ?: throw IOException("Cannot open URI: $uri")

                totalCopied = copyDocumentTree(
                    context = context,
                    source = documentFile,
                    destDir = projectDir,
                    budget = budget,
                    totalCopied = 0L
                )

                Result.success(projectDir)
            } catch (e: WorkspaceError) {
                projectDir.deleteRecursively()
                Result.failure(e)
            } catch (e: Exception) {
                projectDir.deleteRecursively()
                Result.failure(WorkspaceError.IoError(e))
            }
        }
    }

    /**
     * Recursively copy a DocumentFile tree into a local directory.
     * Returns total bytes copied.
     */
    private fun copyDocumentTree(
        context: Context,
        source: DocumentFile,
        destDir: File,
        budget: Long,
        totalCopied: Long
    ): Long {
        var copied = totalCopied

        for (child in source.listFiles()) {
            if (child.isDirectory) {
                val subDir = File(destDir, child.name ?: "unnamed")
                subDir.mkdirs()
                copied = copyDocumentTree(context, child, subDir, budget, copied)
            } else if (child.isFile) {
                val fileName = child.name ?: "unnamed"
                val destFile = File(destDir, fileName)
                val fileSize = child.length()

                // Check single file limit
                if (fileSize > MAX_WRITE_SIZE) {
                    throw WorkspaceError.FileTooLarge(fileSize, MAX_WRITE_SIZE)
                }

                // Check quota
                if (copied + fileSize > budget) {
                    throw WorkspaceError.QuotaExceeded(copied + fileSize, budget)
                }

                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                } ?: throw IOException("Cannot open file: ${child.uri}")

                copied += fileSize
            }
        }

        return copied
    }

    // =========================================================================
    // Workspace Stats
    // =========================================================================

    /**
     * Get comprehensive workspace statistics.
     */
    suspend fun getStats(): WorkspaceStats = withContext(Dispatchers.IO) {
        val quota = getQuotaBytes()
        val used = calculateDirectorySize(workspaceRoot)
        val fileCount = countFiles(workspaceRoot)
        val projectCount = projectsDir.listFiles()?.count { it.isDirectory } ?: 0

        WorkspaceStats(
            totalQuotaBytes = quota,
            usedBytes = used,
            availableBytes = (quota - used).coerceAtLeast(0),
            fileCount = fileCount,
            projectCount = projectCount
        )
    }

    // =========================================================================
    // File Operations (with size guards)
    // =========================================================================

    /**
     * Read a file within the workspace, enforcing the max read size limit.
     *
     * @param relativePath Path relative to workspace root
     * @return File contents as bytes
     * @throws WorkspaceError.FileTooLarge if file exceeds MAX_READ_SIZE
     */
    suspend fun readFile(relativePath: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val file = resolveAndValidate(relativePath)
                ?: return@withContext Result.failure(
                    WorkspaceError.IoError(IOException("File not found: $relativePath"))
                )

            if (file.length() > MAX_READ_SIZE) {
                return@withContext Result.failure(
                    WorkspaceError.FileTooLarge(file.length(), MAX_READ_SIZE)
                )
            }

            Result.success(file.readBytes())
        } catch (e: Exception) {
            Result.failure(WorkspaceError.IoError(e))
        }
    }

    /**
     * Write a file within the workspace, enforcing quota and size limits.
     *
     * @param relativePath Path relative to workspace root
     * @param data Bytes to write
     * @throws WorkspaceError.FileTooLarge if data exceeds MAX_WRITE_SIZE
     * @throws WorkspaceError.QuotaExceeded if write would exceed quota
     */
    suspend fun writeFile(relativePath: String, data: ByteArray): Result<File> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    enforceWriteLimit(data.size.toLong())

                    val file = File(workspaceRoot, relativePath).canonicalFile

                    // Sandbox check
                    if (!file.canonicalPath.startsWith(workspaceRoot.canonicalPath)) {
                        return@withContext Result.failure(
                            WorkspaceError.IoError(
                                SecurityException("Path escapes workspace: $relativePath")
                            )
                        )
                    }

                    file.parentFile?.mkdirs()
                    file.writeBytes(data)
                    Result.success(file)
                } catch (e: WorkspaceError) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(WorkspaceError.IoError(e))
                }
            }
        }

    /**
     * Delete a file within the workspace.
     */
    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolveAndValidate(relativePath) ?: return@withContext false
        file.delete()
    }

    // =========================================================================
    // Scratch Space
    // =========================================================================

    /**
     * Create a temporary file in the scratch directory.
     */
    suspend fun createScratchFile(prefix: String, suffix: String = ".tmp"): File =
        withContext(Dispatchers.IO) {
            File.createTempFile(prefix, suffix, scratchDir)
        }

    /**
     * Clear all files in the scratch directory.
     */
    suspend fun clearScratch(): Boolean = withContext(Dispatchers.IO) {
        scratchDir.listFiles()?.forEach { it.deleteRecursively() }
        true
    }

    /**
     * Clear all files in the exports directory.
     */
    suspend fun clearExports(): Boolean = withContext(Dispatchers.IO) {
        exportsDir.listFiles()?.forEach { it.deleteRecursively() }
        true
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /**
     * Validate a project name against allowed characters.
     */
    fun isValidProjectName(name: String): Boolean {
        return PROJECT_NAME_REGEX.matches(name)
    }

    /**
     * Resolve a relative path to a file within the workspace, with sandbox validation.
     * Returns null if the file doesn't exist or escapes the workspace.
     */
    private fun resolveAndValidate(relativePath: String): File? {
        val file = File(workspaceRoot, relativePath).canonicalFile
        if (!file.canonicalPath.startsWith(workspaceRoot.canonicalPath)) return null
        if (!file.exists()) return null
        return file
    }

    /**
     * Calculate the total size of a directory tree in bytes.
     */
    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Count all files (non-directory) in a directory tree.
     */
    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .count { it.isFile }
    }

    /**
     * Get the most recent modification time in a directory tree.
     */
    private fun getLastModified(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .maxOfOrNull { it.lastModified() } ?: 0
    }
}
