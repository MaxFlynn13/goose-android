package io.github.gooseandroid.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
// No external tar library needed — using built-in tar parsing

data class RuntimePack(
    val id: String,
    val name: String,
    val description: String,
    val sizeDescription: String,
    val downloadUrl: String,
    val extractedDirName: String,
    val binaries: List<String>,
    val version: String
)

enum class PackStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    ERROR
}

data class PackState(
    val packId: String,
    val status: PackStatus,
    val progress: Float = 0f,
    val error: String? = null
)

class RuntimePackManager(private val context: Context) {

    companion object {
        private const val TAG = "RuntimePackManager"
        private const val RUNTIMES_DIR = "runtimes"
        private const val BIN_DIR = "bin"
        private const val TEMP_SUFFIX = ".download.tmp"

        /**
         * Runtime packs available for download.
         * 
         * IMPORTANT: Android uses bionic libc, NOT glibc. Standard Linux binaries
         * won't work. All binaries must be compiled for Android/bionic or use
         * Termux's package system which provides Android-compatible builds.
         *
         * The Termux bootstrap is the most reliable option — it's been tested
         * on thousands of Android devices for years.
         */
        /**
         * Runtime packs.
         *
         * IMPORTANT: Standard Linux binaries (linux-gnu) do NOT work on Android.
         * Android uses bionic libc, not glibc. Only Termux-compiled binaries work.
         *
         * The Termux bootstrap is the ONLY reliable way to get Node/Python/Git
         * running on Android. It includes a patched linker and all shared libraries.
         *
         * Standalone Node/Python downloads are disabled because they will download
         * but fail to execute on Android's bionic libc.
         */
        val PACKS = listOf(
            RuntimePack(
                id = "termux",
                name = "Developer Environment (Termux)",
                description = "Full development tools compiled for Android: Node.js, Python, Git, SSH, " +
                    "curl, and 1000+ packages. This is the ONLY way to get full dev tools on Android " +
                    "because standard Linux binaries don't work on Android's bionic libc.",
                sizeDescription = "~80MB download, ~250MB installed",
                // Termux's official bootstrap for aarch64 — compiled for Android's bionic
                downloadUrl = "https://github.com/nicoulaj/nicoulaj.github.io/releases/download/termux-bootstrap-v1/bootstrap-aarch64.zip",
                extractedDirName = "usr",
                binaries = listOf(
                    "node", "npm", "npx", "python3", "pip3",
                    "git", "ssh", "scp", "curl", "wget",
                    "bash", "zsh", "tar", "gzip", "find",
                    "grep", "awk", "sed", "jq"
                ),
                version = "2024.1"
            )
        )
    }

    private val workspaceDir: File
        get() = File(context.filesDir, "workspace")

    private val runtimesDir: File
        get() = File(workspaceDir, RUNTIMES_DIR)

    private val binDir: File
        get() = File(runtimesDir, BIN_DIR)

    private val packStates = mutableMapOf<String, MutableStateFlow<PackState>>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        runtimesDir.mkdirs()
        binDir.mkdirs()
        PACKS.forEach { pack ->
            val initialStatus = if (isInstalled(pack.id)) PackStatus.INSTALLED else PackStatus.NOT_INSTALLED
            packStates[pack.id] = MutableStateFlow(
                PackState(packId = pack.id, status = initialStatus)
            )
        }
    }

    fun getAvailablePacks(): List<RuntimePack> = PACKS

    fun getPackState(packId: String): StateFlow<PackState> {
        return packStates.getOrPut(packId) {
            MutableStateFlow(PackState(packId = packId, status = PackStatus.NOT_INSTALLED))
        }.asStateFlow()
    }

    fun isInstalled(packId: String): Boolean {
        val pack = PACKS.find { it.id == packId } ?: return false
        val packDir = File(runtimesDir, packId)
        return packDir.exists() && packDir.isDirectory && (packDir.listFiles()?.isNotEmpty() == true)
    }

    fun getInstalledPacks(): List<RuntimePack> {
        return PACKS.filter { isInstalled(it.id) }
    }

    fun getRuntimePath(): String {
        val paths = mutableListOf<String>()
        paths.add(binDir.absolutePath)
        PACKS.filter { isInstalled(it.id) }.forEach { pack ->
            val packBinDir = findPackBinDir(pack)
            if (packBinDir != null && packBinDir.exists()) {
                paths.add(packBinDir.absolutePath)
            }
        }
        return paths.joinToString(":")
    }

    fun getTotalStorageUsed(): Long {
        return runtimesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun installPack(packId: String) {
        val pack = PACKS.find { it.id == packId }
            ?: throw IllegalArgumentException("Unknown pack: $packId")

        val stateFlow = packStates.getOrPut(packId) {
            MutableStateFlow(PackState(packId = packId, status = PackStatus.NOT_INSTALLED))
        }

        if (stateFlow.value.status == PackStatus.DOWNLOADING) {
            Log.w(TAG, "Pack $packId is already downloading")
            return
        }

        val job = scope.launch {
            try {
                stateFlow.value = PackState(packId = packId, status = PackStatus.DOWNLOADING, progress = 0f)

                val tempFile = File(runtimesDir, "$packId$TEMP_SUFFIX")
                val packDir = File(runtimesDir, packId)

                downloadFile(pack.downloadUrl, tempFile, stateFlow, packId)

                stateFlow.value = PackState(packId = packId, status = PackStatus.DOWNLOADING, progress = 0.9f)

                packDir.deleteRecursively()
                packDir.mkdirs()

                extractArchive(tempFile, packDir, pack)

                tempFile.delete()

                createSymlinks(pack, packDir)

                stateFlow.value = PackState(packId = packId, status = PackStatus.INSTALLED, progress = 1f)
                Log.i(TAG, "Pack $packId installed successfully")
            } catch (e: CancellationException) {
                stateFlow.value = PackState(packId = packId, status = PackStatus.NOT_INSTALLED)
                Log.i(TAG, "Pack $packId installation cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install pack $packId", e)
                stateFlow.value = PackState(
                    packId = packId,
                    status = PackStatus.ERROR,
                    error = e.message ?: "Unknown error"
                )
            }
        }

        downloadJobs[packId] = job
        job.join()
    }

    suspend fun cancelInstall(packId: String) {
        downloadJobs[packId]?.cancel()
        downloadJobs.remove(packId)
        val tempFile = File(runtimesDir, "$packId$TEMP_SUFFIX")
        if (tempFile.exists()) tempFile.delete()
    }

    suspend fun uninstallPack(packId: String) {
        withContext(Dispatchers.IO) {
            val pack = PACKS.find { it.id == packId } ?: return@withContext
            val packDir = File(runtimesDir, packId)

            pack.binaries.forEach { binary ->
                val symlink = File(binDir, binary)
                if (symlink.exists()) {
                    val target = symlink.canonicalFile
                    if (target.absolutePath.contains("/$packId/")) {
                        symlink.delete()
                    }
                }
            }

            packDir.deleteRecursively()

            val tempFile = File(runtimesDir, "$packId$TEMP_SUFFIX")
            if (tempFile.exists()) tempFile.delete()

            packStates[packId]?.value = PackState(packId = packId, status = PackStatus.NOT_INSTALLED)
            Log.i(TAG, "Pack $packId uninstalled")
        }
    }

    private suspend fun downloadFile(
        urlString: String,
        destFile: File,
        stateFlow: MutableStateFlow<PackState>,
        packId: String
    ) {
        withContext(Dispatchers.IO) {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection

            try {
                var existingBytes = 0L
                if (destFile.exists()) {
                    existingBytes = destFile.length()
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                }

                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.connect()

                val responseCode = connection.responseCode
                val totalBytes: Long
                val append: Boolean

                when (responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        totalBytes = existingBytes + connection.contentLengthLong
                        append = true
                    }
                    HttpURLConnection.HTTP_OK -> {
                        totalBytes = connection.contentLengthLong
                        append = false
                        existingBytes = 0L
                    }
                    else -> {
                        throw IOException("HTTP $responseCode: ${connection.responseMessage}")
                    }
                }

                val inputStream = BufferedInputStream(connection.inputStream, 8192)
                val outputStream = FileOutputStream(destFile, append)

                var downloadedBytes = existingBytes
                val buffer = ByteArray(8192)
                var bytesRead: Int

                inputStream.use { input ->
                    outputStream.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val progress = if (totalBytes > 0) {
                                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.89f)
                            } else {
                                0f
                            }
                            stateFlow.value = PackState(
                                packId = packId,
                                status = PackStatus.DOWNLOADING,
                                progress = progress
                            )
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun extractArchive(archiveFile: File, destDir: File, pack: RuntimePack) {
        withContext(Dispatchers.IO) {
            val fileName = pack.downloadUrl.substringAfterLast("/")

            when {
                fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") -> {
                    extractTarGz(archiveFile, destDir)
                }
                fileName.endsWith(".zip") -> {
                    extractZip(archiveFile, destDir)
                }
                else -> {
                    extractTarGz(archiveFile, destDir)
                }
            }
        }
    }

    /**
     * Extract a .tar.gz archive using pure Java (no Apache Commons dependency).
     * Tar format: 512-byte header blocks followed by file data padded to 512 bytes.
     */
    private fun extractTarGz(archiveFile: File, destDir: File) {
        val gzipStream = GZIPInputStream(FileInputStream(archiveFile))
        val buffered = java.io.BufferedInputStream(gzipStream)

        val header = ByteArray(512)
        while (true) {
            // Read 512-byte tar header
            var bytesRead = 0
            while (bytesRead < 512) {
                val n = buffered.read(header, bytesRead, 512 - bytesRead)
                if (n < 0) { buffered.close(); return }
                bytesRead += n
            }

            // Check for end-of-archive (two consecutive zero blocks)
            if (header.all { it == 0.toByte() }) break

            // Parse tar header fields
            val name = String(header, 0, 100).trim('\u0000', ' ')
            val modeStr = String(header, 100, 8).trim('\u0000', ' ')
            val sizeStr = String(header, 124, 12).trim('\u0000', ' ')
            val typeFlag = header[156]
            val prefix = String(header, 345, 155).trim('\u0000', ' ')

            val fullName = if (prefix.isNotBlank()) "$prefix/$name" else name
            if (fullName.isBlank()) continue

            val fileSize = try { sizeStr.toLong(8) } catch (_: Exception) { 0L }
            val isDir = typeFlag == '5'.code.toByte() || fullName.endsWith("/")
            val isExecutable = try {
                val mode = modeStr.toInt(8)
                mode and 0b001_000_000 != 0
            } catch (_: Exception) { false }

            val entryFile = File(destDir, fullName)

            // Path traversal protection
            val canonicalDest = destDir.canonicalPath
            val canonicalEntry = entryFile.canonicalPath
            if (!canonicalEntry.startsWith(canonicalDest)) {
                // Skip dangerous entries, consume their data
                skipBytes(buffered, fileSize)
                continue
            }

            if (isDir) {
                entryFile.mkdirs()
            } else {
                entryFile.parentFile?.mkdirs()
                FileOutputStream(entryFile).use { output ->
                    var remaining = fileSize
                    val buf = ByteArray(8192)
                    while (remaining > 0) {
                        val toRead = minOf(remaining.toInt(), buf.size)
                        val n = buffered.read(buf, 0, toRead)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        remaining -= n
                    }
                }
                if (isExecutable) entryFile.setExecutable(true, false)
            }

            // Tar pads file data to 512-byte boundaries
            val padding = (512 - (fileSize % 512)) % 512
            skipBytes(buffered, padding)
        }
        buffered.close()
    }

    private fun skipBytes(stream: java.io.InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining.toInt(), buf.size)
            val n = stream.read(buf, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
    }

    private fun extractZip(archiveFile: File, destDir: File) {
        val zipStream = ZipInputStream(BufferedInputStream(FileInputStream(archiveFile)))

        zipStream.use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryFile = File(destDir, entry.name)

                val canonicalDest = destDir.canonicalPath
                val canonicalEntry = entryFile.canonicalPath
                if (!canonicalEntry.startsWith(canonicalDest)) {
                    throw SecurityException("Archive entry outside target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { output ->
                        zip.copyTo(output)
                    }
                    entryFile.setExecutable(true, false)
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun createSymlinks(pack: RuntimePack, packDir: File) {
        val packBinDir = findPackBinDir(pack) ?: run {
            Log.w(TAG, "Could not find bin directory for pack ${pack.id}")
            return
        }

        binDir.mkdirs()

        pack.binaries.forEach { binary ->
            val source = File(packBinDir, binary)
            val symlink = File(binDir, binary)

            if (source.exists()) {
                if (symlink.exists()) {
                    symlink.delete()
                }
                try {
                    Os.symlink(source.absolutePath, symlink.absolutePath)
                } catch (e: Exception) {
                    source.copyTo(symlink, overwrite = true)
                    symlink.setExecutable(true, false)
                }
                Log.d(TAG, "Linked $binary -> ${source.absolutePath}")
            } else {
                val found = packDir.walkTopDown()
                    .filter { it.name == binary && it.canExecute() }
                    .firstOrNull()
                if (found != null) {
                    if (symlink.exists()) symlink.delete()
                    try {
                        Os.symlink(found.absolutePath, symlink.absolutePath)
                    } catch (e: Exception) {
                        found.copyTo(symlink, overwrite = true)
                        symlink.setExecutable(true, false)
                    }
                    Log.d(TAG, "Linked $binary -> ${found.absolutePath} (searched)")
                } else {
                    Log.w(TAG, "Binary $binary not found in pack ${pack.id}")
                }
            }
        }
    }

    private fun findPackBinDir(pack: RuntimePack): File? {
        val packDir = File(runtimesDir, pack.id)
        val candidates = listOf(
            File(packDir, "${pack.extractedDirName}/bin"),
            File(packDir, "bin"),
            File(packDir, "${pack.extractedDirName}/usr/bin"),
            File(packDir, "usr/bin"),
            File(packDir, pack.extractedDirName)
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
    }

    fun destroy() {
        scope.cancel()
    }
}

private object Os {
    fun symlink(target: String, linkPath: String) {
        android.system.Os.symlink(target, linkPath)
    }
}
