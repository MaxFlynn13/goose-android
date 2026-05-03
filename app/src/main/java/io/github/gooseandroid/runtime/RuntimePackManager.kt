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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

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

        val PACKS = listOf(
            RuntimePack(
                id = "nodejs",
                name = "Node.js 22 LTS",
                description = "JavaScript runtime for MCP extensions that use npx",
                sizeDescription = "~55MB",
                downloadUrl = "https://nodejs.org/dist/v22.15.0/node-v22.15.0-linux-arm64.tar.gz",
                extractedDirName = "node-v22.15.0-linux-arm64",
                binaries = listOf("node", "npm", "npx"),
                version = "22.15.0"
            ),
            RuntimePack(
                id = "python",
                name = "Python 3.12",
                description = "Python runtime for MCP extensions and scripts",
                sizeDescription = "~40MB",
                downloadUrl = "https://github.com/nicoulaj/static-python/releases/download/v3.12.0/cpython-3.12.0-aarch64-unknown-linux-gnu-install_only.tar.gz",
                extractedDirName = "python",
                binaries = listOf("python3", "pip3"),
                version = "3.12.0"
            ),
            RuntimePack(
                id = "buildtools",
                name = "Build Tools",
                description = "C/C++ compiler and build tools for native extensions",
                sizeDescription = "~35MB",
                downloadUrl = "https://github.com/nicoulaj/static-toolchain/releases/download/v13.2.0/aarch64-linux-gnu-toolchain-13.2.0.tar.gz",
                extractedDirName = "toolchain",
                binaries = listOf("gcc", "g++", "make", "cmake"),
                version = "13.2.0"
            ),
            RuntimePack(
                id = "termux",
                name = "Termux Bootstrap",
                description = "Full Linux development environment (includes everything above)",
                sizeDescription = "~150MB",
                downloadUrl = "https://github.com/nicoulaj/termux-bootstrap/releases/download/v0.1/bootstrap-aarch64.zip",
                extractedDirName = "termux",
                binaries = listOf(
                    "node", "npm", "npx", "python3", "pip3",
                    "gcc", "g++", "make", "cmake",
                    "ssh", "scp", "git", "curl", "wget",
                    "vim", "tar", "gzip", "find", "grep",
                    "awk", "sed", "bash", "zsh", "tmux"
                ),
                version = "0.1"
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

    private fun extractTarGz(archiveFile: File, destDir: File) {
        val gzipStream = GZIPInputStream(FileInputStream(archiveFile))
        val tarStream = TarArchiveInputStream(gzipStream)

        tarStream.use { tar ->
            var entry = tar.nextEntry
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
                        tar.copyTo(output)
                    }
                    if (entry.mode and 0b001_000_000 != 0) {
                        entryFile.setExecutable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
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
