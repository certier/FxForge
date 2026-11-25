package dev.fxforge.app

import android.content.Context
import android.os.Build
import android.os.StatFs
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
import java.security.SecureRandom
import java.util.Base64
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

data class OpenCodeState(
    val installed: Boolean = false,
    val installing: Boolean = false,
    val starting: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
    val output: String? = null
)

data class AndroidSdkState(
    val installed: Boolean = false,
    val installing: Boolean = false,
    val phase: String = "Not installed",
    val error: String? = null,
    val output: String? = null
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
    private val openCodeBinary = File(rootfsDirectory, "root/.opencode/bin/opencode")
    private val androidSdkMarker = File(rootfsDirectory, "opt/android-sdk/.fxforge-sdk-version")
    private val mutableSdkState = MutableStateFlow(
        AndroidSdkState(
            installed = androidSdkMarker.isFile,
            phase = if (androidSdkMarker.isFile) "Android SDK installed" else "Not installed"
        )
    )
    val androidSdkState: StateFlow<AndroidSdkState> = mutableSdkState.asStateFlow()
    @Volatile private var openCodeProcess: Process? = null
    @Volatile private var openCodePassword: String? = null
    private val mutableOpenCodeState = MutableStateFlow(OpenCodeState(installed = openCodeBinary.isFile))
    val openCodeState: StateFlow<OpenCodeState> = mutableOpenCodeState.asStateFlow()
    val openCodeUrl: String = "http://localhost:4096"
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
        val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (availableBytes < MIN_FREE_SPACE_BYTES) {
            mutableState.value = mutableState.value.copy(
                error = "Debian installation needs at least 160 MiB of free app storage."
            )
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

    suspend fun installAndroidSdk() = withContext(Dispatchers.IO) {
        if (!markerFile.isFile) {
            mutableSdkState.value = mutableSdkState.value.copy(error = "Install Debian before installing Android SDK.")
            return@withContext
        }
        if (androidSdkMarker.isFile) {
            mutableSdkState.value = AndroidSdkState(installed = true, phase = "Android SDK installed")
            return@withContext
        }
        val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (availableBytes < MIN_SDK_FREE_SPACE_BYTES) {
            mutableSdkState.value = mutableSdkState.value.copy(
                error = "Android SDK installation needs at least 4 GiB of free app storage."
            )
            return@withContext
        }
        mutableSdkState.value = AndroidSdkState(installing = true, phase = "Installing Java and SDK prerequisites")
        try {
            val prerequisites = runCommand(
                "apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -qq install -y openjdk-17-jdk-headless curl unzip xz-utils ca-certificates",
                timeoutSeconds = 900
            )
            requireCommandSuccess(prerequisites, "Could not install Java and SDK prerequisites.")
            mutableSdkState.value = mutableSdkState.value.copy(phase = "Downloading Android command-line tools")
            val tools = runCommand(
                "mkdir -p /opt/android-sdk/cmdline-tools /tmp/fxforge-sdk-tools && " +
                    "curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o /tmp/fxforge-sdk-tools.zip && " +
                    "echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/fxforge-sdk-tools.zip' | sha256sum -c - && " +
                    "unzip -q /tmp/fxforge-sdk-tools.zip -d /tmp/fxforge-sdk-tools && " +
                    "mkdir -p /opt/android-sdk/cmdline-tools/latest && " +
                    "cp -a /tmp/fxforge-sdk-tools/cmdline-tools/. /opt/android-sdk/cmdline-tools/latest/ && " +
                    "rm -rf /tmp/fxforge-sdk-tools /tmp/fxforge-sdk-tools.zip",
                timeoutSeconds = 900
            )
            requireCommandSuccess(tools, "Could not install Android command-line tools.")
            mutableSdkState.value = mutableSdkState.value.copy(phase = "Installing Android API 36 and Build Tools 36.0.0")
            val sdkPackages = runCommand(
                "export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk && " +
                    "yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses >/dev/null && " +
                    "/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk " +
                    "\"platforms;android-36\" \"build-tools;36.0.0\"",
                timeoutSeconds = 1800
            )
            requireCommandSuccess(sdkPackages, "Could not install Android SDK Platform 36 and Build Tools 36.0.0.")
            mutableSdkState.value = mutableSdkState.value.copy(phase = "Installing verified ARM64 build tools and Gradle")
            val nativeTools = runCommand(
                "set -e && " +
                    "curl -fL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/aapt2 -o /opt/android-sdk/build-tools/36.0.0/aapt2 && " +
                    "echo '7512ff7e381bea6fd310b6f6e347422c8fda21e07c6e3f0162742e95eb9d7f98  /opt/android-sdk/build-tools/36.0.0/aapt2' | sha256sum -c - && " +
                    "curl -fL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/aidl -o /opt/android-sdk/build-tools/36.0.0/aidl && " +
                    "echo '8c97356b8bba8f7aad44cfd408e3ee24c66a1258244b5d08af1c9249f50dd659  /opt/android-sdk/build-tools/36.0.0/aidl' | sha256sum -c - && " +
                    "curl -fL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/zipalign -o /opt/android-sdk/build-tools/36.0.0/zipalign && " +
                    "echo 'e8856fb24b10095eb6e940c577ce96d89ddbfc45aa0f7eeaef1597ef68f11a12  /opt/android-sdk/build-tools/36.0.0/zipalign' | sha256sum -c - && " +
                    "curl -fL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/split-select -o /opt/android-sdk/build-tools/36.0.0/split-select && " +
                    "echo 'fb7f0c3c87dbd4243d7e1277ba64ded2399389244058b6a4c969cc615360766c  /opt/android-sdk/build-tools/36.0.0/split-select' | sha256sum -c - && " +
                    "chmod 755 /opt/android-sdk/build-tools/36.0.0/aapt2 /opt/android-sdk/build-tools/36.0.0/aidl /opt/android-sdk/build-tools/36.0.0/zipalign /opt/android-sdk/build-tools/36.0.0/split-select && " +
                    "mkdir -p /opt/gradle && " +
                    "curl -fL --retry 3 https://services.gradle.org/distributions/gradle-8.13-bin.zip -o /tmp/gradle-8.13-bin.zip && " +
                    "echo '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78  /tmp/gradle-8.13-bin.zip' | sha256sum -c - && " +
                    "unzip -q /tmp/gradle-8.13-bin.zip -d /opt/gradle && " +
                    "rm -f /tmp/gradle-8.13-bin.zip && " +
                    "/opt/android-sdk/build-tools/36.0.0/aapt2 version && /opt/gradle/gradle-8.13/bin/gradle --version",
                timeoutSeconds = 1800
            )
            requireCommandSuccess(nativeTools, "Could not install ARM64 Android build tools or Gradle.")
            androidSdkMarker.parentFile?.mkdirs()
            androidSdkMarker.writeText("android-36\nbuild-tools-36.0.0\ngradle-8.13\narm64-v8a\n")
            mutableSdkState.value = AndroidSdkState(
                installed = true,
                phase = "Android SDK and ARM64 build tools installed",
                output = nativeTools.output
            )
        } catch (exception: CancellationException) {
            mutableSdkState.value = AndroidSdkState(phase = "Installation cancelled")
            throw exception
        } catch (exception: Exception) {
            mutableSdkState.value = AndroidSdkState(
                phase = "Installation failed",
                error = exception.message ?: "Android SDK installation failed."
            )
        }
    }

    private fun requireCommandSuccess(result: LinuxCommandResult, message: String) {
        if (result.exitCode != 0) {
            throw IOException(result.error ?: message + " Exit code: " + (result.exitCode?.toString() ?: "unknown") +
                if (result.output.isBlank()) "" else "\n" + result.output.takeLast(2000))
        }
    }

    suspend fun installOpenCode() = withContext(Dispatchers.IO) {
        if (!markerFile.isFile) {
            mutableOpenCodeState.value = mutableOpenCodeState.value.copy(
                error = "Install Debian before installing OpenCode."
            )
            return@withContext
        }
        if (openCodeBinary.isFile) {
            mutableOpenCodeState.value = OpenCodeState(installed = true)
            return@withContext
        }
        mutableOpenCodeState.value = OpenCodeState(installing = true)
        val installResult = runCommand(
            "apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -qq install -y ca-certificates curl git && curl -fsSL https://opencode.ai/v2/install | bash && /root/.opencode/bin/opencode --version",
            timeoutSeconds = 900
        )
        val installed = openCodeBinary.isFile
        mutableOpenCodeState.value = OpenCodeState(
            installed = installed,
            error = if (installed) null else installResult.error
                ?: "OpenCode installation did not create /root/.opencode/bin/opencode.",
            output = installResult.output
        )
    }

    suspend fun startOpenCode() = withContext(Dispatchers.IO) {
        if (!openCodeBinary.isFile) {
            mutableOpenCodeState.value = mutableOpenCodeState.value.copy(
                error = "Install OpenCode before starting its server."
            )
            return@withContext
        }
        if (openCodeProcess?.isAlive == true) {
            mutableOpenCodeState.value = OpenCodeState(installed = true, running = true)
            return@withContext
        }
        mutableOpenCodeState.value = OpenCodeState(installed = true, starting = true)
        val password = openCodePassword ?: createOpenCodePassword().also { openCodePassword = it }
        val temporaryDirectory = File(appContext.cacheDir, "proot-tmp")
        if (!temporaryDirectory.exists()) temporaryDirectory.mkdirs()
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
                "PATH=/root/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "OPENCODE_PASSWORD=" + password,
                "/root/.opencode/bin/opencode",
                "serve",
                "--hostname", "127.0.0.1",
                "--port", OPEN_CODE_PORT.toString()
            ).directory(rootfsDirectory)
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_LOADER_32"] = loader32.absolutePath
                    environment()["PROOT_NO_SECCOMP"] = "1"
                }
                .start()
        } catch (exception: Exception) {
            mutableOpenCodeState.value = OpenCodeState(
                installed = true,
                error = "Could not start OpenCode: " + (exception.message ?: "process error")
            )
            return@withContext
        }
        openCodeProcess = process
        captureOpenCodeOutput(process)
        var ready = false
        repeat(OPEN_CODE_START_ATTEMPTS) {
            if (!process.isAlive) return@repeat
            if (isOpenCodeServerReady(password)) {
                ready = true
                return@repeat
            }
            Thread.sleep(OPEN_CODE_POLL_INTERVAL_MS)
        }
        if (ready && process.isAlive) {
            mutableOpenCodeState.value = OpenCodeState(installed = true, running = true)
            Thread({
                val exitCode = try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                if (openCodeProcess === process) {
                    openCodeProcess = null
                    openCodePassword = null
                    mutableOpenCodeState.value = OpenCodeState(
                        installed = openCodeBinary.isFile,
                        error = "OpenCode server stopped with exit code " + exitCode + "."
                    )
                }
            }, "fxforge-opencode-monitor").apply {
                isDaemon = true
                start()
            }
        } else {
            process.destroyForcibly()
            openCodeProcess = null
            val log = File(appContext.filesDir, "linux/opencode-server.log")
            mutableOpenCodeState.value = OpenCodeState(
                installed = true,
                error = if (!process.isAlive) {
                    "OpenCode server exited before it became ready."
                } else {
                    "OpenCode server did not become ready within the startup timeout."
                },
                output = if (log.isFile) log.readText().takeLast(4000) else null
            )
        }
    }

    suspend fun stopOpenCode() = withContext(Dispatchers.IO) {
        val process = openCodeProcess
        openCodeProcess = null
        if (process != null && process.isAlive) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        openCodeProcess = null
        openCodePassword = null
        mutableOpenCodeState.value = OpenCodeState(installed = openCodeBinary.isFile)
    }

    fun openCodeCredentials(): Pair<String, String>? =
        openCodePassword?.let { "opencode" to it }

    private fun createOpenCodePassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isOpenCodeServerReady(password: String): Boolean {
        val connection = try {
            URL("http://127.0.0.1:" + OPEN_CODE_PORT + "/api/info").openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return false
        }
        return try {
            connection.connectTimeout = 500
            connection.readTimeout = 500
            val token = Base64.getEncoder().encodeToString(("opencode:" + password).toByteArray(Charsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic " + token)
            connection.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun captureOpenCodeOutput(process: Process) {
        val logFile = File(appContext.filesDir, "linux/opencode-server.log")
        logFile.parentFile?.mkdirs()
        Thread({
            try {
                process.inputStream.use { input ->
                    FileOutputStream(logFile, false).use { output ->
                        val buffer = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            val remaining = MAX_SERVER_LOG_BYTES - total
                            if (remaining > 0) {
                                val writeCount = minOf(count, remaining)
                                output.write(buffer, 0, writeCount)
                                total += writeCount
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // The server process may close its stream during shutdown.
            }
        }, "fxforge-opencode-log").apply {
            isDaemon = true
            start()
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
                        val target = File(stagingDirectory, linkName).canonicalFile
                        if (!target.path.startsWith(stagingCanonical.path + File.separator) || !target.isFile) {
                            throw IOException("Debian archive contains an invalid hard link.")
                        }
                        try {
                            Files.createLink(destination.toPath(), target.toPath())
                        } catch (_: IOException) {
                            target.copyTo(destination, overwrite = true)
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
            if (Files.isSymbolicLink(toPath())) delete()
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
        private const val MAX_COMMAND_TIMEOUT_SECONDS = 3600
        private const val MIN_SDK_FREE_SPACE_BYTES = 4L * 1024L * 1024L * 1024L
        private const val OPEN_CODE_PORT = 4096
        private const val OPEN_CODE_START_ATTEMPTS = 40
        private const val OPEN_CODE_POLL_INTERVAL_MS = 250L
        private const val MAX_SERVER_LOG_BYTES = 512 * 1024
        private const val MIN_FREE_SPACE_BYTES = 160L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private const val EXTRACT_BUFFER_SIZE = 32 * 1024
    }
}

private data class CommandOutput(val text: String, val truncated: Boolean)
