package dev.fxforge.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class LinuxRuntimeState(
    val installed: Boolean = false,
    val installing: Boolean = false,
    val phase: String = "Not installed",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = DebianRootfs.ARCHIVE_SIZE,
    val error: String? = null
)

data class LinuxCommandResult(
    val output: String,
    val exitCode: Int?,
    val truncated: Boolean,
    val error: String? = null
)

private object DebianRootfs {
    const val ARCHIVE_SIZE = 28_144_305L
    const val SHA256 = "7f1aa0b5c351d0542b2066749ed47608e5873bcdd1fd14f4df6f0a05cac02f8e"
    const val VERSION = "debian-bookworm-slim-20261123-arm64"
    const val URL = "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/657d5664bd8e7d6833fa48225b8110774cc17666/bookworm/slim/oci/blobs/rootfs.tar.gz"
}

class LinuxRuntimeManager(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeDirectory = File(appContext.filesDir, "linux")
    private val rootfsDirectory = File(runtimeDirectory, DebianRootfs.VERSION)
    private val markerFile = File(rootfsDirectory, ".fxforge-rootfs-version")
    private val downloadFile = File(appContext.cacheDir, "debian-bookworm-arm64.tar.gz")
    private val mutableState = MutableStateFlow(
        LinuxRuntimeState(
            installed = markerFile.isFile,
            phase = if (markerFile.isFile) "Debian installed" else "Not installed"
        )
    )
    val state: StateFlow<LinuxRuntimeState> = mutableState.asStateFlow()

    fun isProotAvailable(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } && ProotBinaryRuntime.isBundled(appContext)

    suspend fun installDebian() = withContext(Dispatchers.IO) {
        if (!isProotAvailable()) {
            mutableState.value = mutableState.value.copy(
                error = "The ARM64 PRoot executable is not available in this APK."
            )
            return@withContext
        }
        if (markerFile.isFile) {
            mutableState.value = LinuxRuntimeState(installed = true, phase = "Debian installed")
            return@withContext
        }
        mutableState.value = LinuxRuntimeState(installing = true, phase = "Preparing Debian download")
        val stagingDirectory = File(runtimeDirectory, DebianRootfs.VERSION + ".staging")
        try {
            runtimeDirectory.mkdirs()
            downloadRootfs()
            mutableState.value = mutableState.value.copy(phase = "Verifying Debian archive")
            extractRootfs(stagingDirectory)
            if (rootfsDirectory.exists() && !rootfsDirectory.deleteRecursively()) {
                throw IOException("Could not replace the previous Debian root filesystem.")
            }
            if (!stagingDirectory.renameTo(rootfsDirectory)) {
                throw IOException("Could not activate the extracted Debian root filesystem.")
            }
            downloadFile.delete()
            mutableState.value = LinuxRuntimeState(installed = true, phase = "Debian installed")
        } catch (exception: CancellationException) {
            stagingDirectory.deleteRecursively()
            throw exception
        } catch (exception: Exception) {
            stagingDirectory.deleteRecursively()
            mutableState.value = LinuxRuntimeState(
                phase = "Installation failed",
                error = exception.message ?: "Debian installation failed."
            )
        }
    }

    suspend fun runCommand(command: String, timeoutSeconds: Int = 30): LinuxCommandResult =
        withContext(Dispatchers.IO) {
            if (!markerFile.isFile) {
                return@withContext LinuxCommandResult("", null, false, "Install Debian before running Linux commands.")
            }
            if (!isProotAvailable()) {
                return@withContext LinuxCommandResult("", null, false, "The ARM64 PRoot executable is unavailable.")
            }
            if (command.isBlank()) {
                return@withContext LinuxCommandResult("", null, false, "Enter a Linux command.")
            }
            if (command.length > MAX_COMMAND_CHARS) {
                return@withContext LinuxCommandResult("", null, false, "Command exceeds the 8 KiB limit.")
            }
            val temporaryDirectory = File(appContext.cacheDir, "proot-tmp")
            if (!temporaryDirectory.exists() && !temporaryDirectory.mkdirs()) {
                return@withContext LinuxCommandResult("", null, false, "Could not create the PRoot temporary directory.")
            }
            val binary = File(appContext.applicationInfo.nativeLibraryDir, PROOT_BINARY)
            val loader = File(appContext.applicationInfo.nativeLibraryDir, PROOT_LOADER)
            val loader32 = File(appContext.applicationInfo.nativeLibraryDir, PROOT_LOADER_32)
            val process = try {
                ProcessBuilder(
                    binary.absolutePath,
                    "--link2symlink",
                    "-0",
                    "-r", rootfsDirectory.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-w", "/root",
                    "/usr/bin/env",
                    "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM=xterm-256color",
                    "/bin/sh",
                    "-lc",
                    command
                ).directory(rootfsDirectory)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
                        environment()["PROOT_LOADER"] = loader.absolutePath
                        environment()["PROOT_LOADER_32"] = loader32.absolutePath
                        environment()["PROOT_NO_SECCOMP"] = "1"
                    }
                    .start()
            } catch (exception: IOException) {
                return@withContext LinuxCommandResult("", null, false, "Could not start Debian: " + (exception.message ?: "process error"))
            } catch (exception: SecurityException) {
                return@withContext LinuxCommandResult("", null, false, "Android denied PRoot execution: " + (exception.message ?: "permission denied"))
            }

            val outputTask = FutureTask {
                process.inputStream.use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(4096)
                        var truncated = false
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            val remaining = MAX_OUTPUT_CHARS - output.length
                            if (count > remaining) {
                                output.append(buffer, 0, remaining)
                                truncated = true
                                process.destroyForcibly()
                                break
                            }
                            output.append(buffer, 0, count)
                        }
                        CommandOutput(output.toString(), truncated)
                    }
                }
            }
            Thread(outputTask, "fxforge-linux-output").apply {
                isDaemon = true
                start()
            }
            try {
                val timeout = timeoutSeconds.coerceIn(1, MAX_COMMAND_TIMEOUT_SECONDS)
                if (!process.waitFor(timeout.toLong(), TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    LinuxCommandResult("", null, false, "Linux command timed out after " + timeout + " seconds.")
                } else {
                    val output = outputTask.get(2, TimeUnit.SECONDS)
                    LinuxCommandResult(output.text, process.exitValue(), output.truncated)
                }
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
                LinuxCommandResult("", null, false, "Linux command was interrupted.")
            } catch (exception: ExecutionException) {
                process.destroyForcibly()
                LinuxCommandResult("", null, false, "Could not read Linux output: " + (exception.cause?.message ?: "stream error"))
            } catch (exception: TimeoutException) {
                process.destroyForcibly()
                LinuxCommandResult("", null, false, "Timed out while reading Linux output.")
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

    private fun downloadRootfs() {
        mutableState.value = mutableState.value.copy(
            phase = "Downloading Debian Bookworm ARM64",
            downloadedBytes = 0,
            totalBytes = DebianRootfs.ARCHIVE_SIZE
        )
        val connection = URL(DebianRootfs.URL).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "FxForge-Android-Workstation")
        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Debian download failed with HTTP " + connection.responseCode + ".")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(downloadFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > DebianRootfs.ARCHIVE_SIZE) {
                            throw IOException("Debian archive is larger than the expected size.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        mutableState.value = mutableState.value.copy(downloadedBytes = total)
                    }
                }
            }
            if (total != DebianRootfs.ARCHIVE_SIZE) {
                throw IOException("Debian archive size did not match the pinned release.")
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != DebianRootfs.SHA256) {
                throw IOException("Debian archive SHA-256 verification failed.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRootfs(stagingDirectory: File) {
        mutableState.value = mutableState.value.copy(phase = "Extracting Debian root filesystem")
        stagingDirectory.deleteRecursively()
        if (!stagingDirectory.mkdirs()) {
            throw IOException("Could not create the Debian staging directory.")
        }
        val stagingCanonical = stagingDirectory.canonicalFile
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(downloadFile)))
        ).use { archive ->
            val buffer = ByteArray(EXTRACT_BUFFER_SIZE)
            while (true) {
                val entry = archive.nextEntry ?: break
                val relativeName = entry.name.removePrefix("./").trimStart('/')
                if (relativeName.isBlank() || relativeName == ".") continue
                val destination = File(stagingDirectory, relativeName)
                val canonicalDestination = destination.canonicalFile
                if (!canonicalDestination.path.startsWith(stagingCanonical.path + File.separator)) {
                    throw IOException("Debian archive contains an unsafe path.")
                }
                when {
                    entry.isDirectory -> {
                        if (!destination.exists() && !destination.mkdirs()) {
                            throw IOException("Could not create Debian directory: " + relativeName)
                        }
                        destination.setReadable(true, false)
                        destination.setWritable(true, false)
                        destination.setExecutable(true, false)
                    }
                    entry.isSymbolicLink -> {
                        destination.parentFile?.mkdirs()
                        if (Files.exists(destination.toPath()) || Files.isSymbolicLink(destination.toPath())) destination.delete()
                        Files.createSymbolicLink(destination.toPath(), Paths.get(entry.linkName))
                    }
                    entry.isLink -> {
                        destination.parentFile?.mkdirs()
                        if (Files.exists(destination.toPath()) || Files.isSymbolicLink(destination.toPath())) destination.delete()
                        val linkName = entry.linkName.removePrefix("./").trimStart('/')
                        val target = File(stagingDirectory, linkName)
                        if (target.exists()) {
                            try {
                                Files.createLink(destination.toPath(), target.toPath())
                            } catch (_: IOException) {
                                target.copyTo(destination, overwrite = true)
                            }
                        }
                    }
                    entry.isFile -> {
                        destination.parentFile?.mkdirs()
                        if (Files.exists(destination.toPath()) || Files.isSymbolicLink(destination.toPath())) destination.delete()
                        FileOutputStream(destination).use { output ->
                            while (true) {
                                val count = archive.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        destination.setReadable((entry.mode and 0x124) != 0, false)
                        destination.setWritable((entry.mode and 0x92) != 0, false)
                        destination.setExecutable((entry.mode and 0x49) != 0, false)
                    }
                }
            }
        }
        ensureDirectory(stagingDirectory, "tmp").apply {
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        ensureDirectory(stagingDirectory, "root")
        ensureDirectory(stagingDirectory, "dev")
        ensureDirectory(stagingDirectory, "proc")
        ensureDirectory(stagingDirectory, "sys")
        ensureDirectory(stagingDirectory, "run")
        val etc = ensureDirectory(stagingDirectory, "etc")
        val resolv = File(etc, "resolv.conf")
        if (Files.isSymbolicLink(resolv.toPath())) resolv.delete()
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(etc, "hosts").apply {
            if (!exists()) writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        }
        File(stagingDirectory, ".fxforge-rootfs-version").writeText(DebianRootfs.VERSION)
    }

    private fun ensureDirectory(parent: File, name: String): File {
        val directory = File(parent, name)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create Debian directory: " + name)
        }
        return directory
    }

    companion object {
        private const val PROOT_BINARY = "libfxforge_proot.so"
        private const val PROOT_LOADER = "libfxforge_proot_loader.so"
        private const val PROOT_LOADER_32 = "libfxforge_proot_loader32.so"
        private const val MAX_COMMAND_CHARS = 8192
        private const val MAX_OUTPUT_CHARS = 64 * 1024
        private const val MAX_COMMAND_TIMEOUT_SECONDS = 60
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private const val EXTRACT_BUFFER_SIZE = 32 * 1024
    }
}

private data class CommandOutput(val text: String, val truncated: Boolean)
