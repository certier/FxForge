package dev.fxforge.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
import java.util.UUID

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

data class AndroidBuildResult(
    val success: Boolean,
    val output: String,
    val artifactUri: String? = null,
    val error: String? = null
)

data class WorkspaceState(
    val selected: Boolean = false,
    val name: String? = null,
    val syncing: Boolean = false,
    val error: String? = null,
    val conflicts: List<String> = emptyList(),
    val lastSyncSummary: String? = null
)

data class LinuxTerminalState(
    val running: Boolean = false,
    val starting: Boolean = false,
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
    private val workspacePreferences = appContext.getSharedPreferences("linux_workspace", Context.MODE_PRIVATE)
    private val legacyWorkspaceDirectory = File(appContext.filesDir, "workspaces/active")
    private val workspaceDirectory: File
        get() {
            val uri = workspacePreferences.getString("tree_uri", null)
            if (uri.isNullOrBlank()) return legacyWorkspaceDirectory
            val projectDirectory = workspaceDirectoryFor(uri)
            return if (!projectDirectory.exists() && legacyWorkspaceDirectory.exists()) {
                legacyWorkspaceDirectory
            } else {
                projectDirectory
            }
        }
    private val mutableWorkspaceState = MutableStateFlow(
        WorkspaceState(
            selected = workspacePreferences.getString("tree_uri", null) != null,
            name = workspacePreferences.getString("tree_name", null)
        )
    )
    val workspaceState: StateFlow<WorkspaceState> = mutableWorkspaceState.asStateFlow()
    private val terminalMutex = Mutex()
    private val workspaceMutex = Mutex()
    private val mutableTerminalState = MutableStateFlow(LinuxTerminalState())
    val terminalState: StateFlow<LinuxTerminalState> = mutableTerminalState.asStateFlow()
    @Volatile private var terminalProcess: Process? = null
    @Volatile private var terminalReader: BufferedReader? = null
    @Volatile private var terminalWriter: BufferedWriter? = null
    private var terminalCommandSequence = 0L
    private val rootfsDirectory = File(runtimeDirectory, DebianRootfs.VERSION)
    private val markerFile = File(rootfsDirectory, ".fxforge-rootfs-version")
    private val downloadFile = File(appContext.cacheDir, "debian-bookworm-arm64.tar.gz")
    private val openCodeBinary = File(rootfsDirectory, "root/.opencode/bin/opencode")
    private val androidSdkMarker = File(rootfsDirectory, "opt/android-sdk/.fxforge-sdk-version")
    private val developerToolsMarker = File(rootfsDirectory, "opt/android-sdk/.fxforge-developer-tools-version")
    private val mutableSdkState = MutableStateFlow(
        AndroidSdkState(
            installed = androidSdkMarker.isFile,
            phase = if (androidSdkMarker.isFile) "Android SDK installed" else "Not installed"
        )
    )
    val androidSdkState: StateFlow<AndroidSdkState> = mutableSdkState.asStateFlow()

    fun areDeveloperToolsInstalled(): Boolean = developerToolsMarker.isFile
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

    init {
        val uri = workspacePreferences.getString("tree_uri", null)
        if (!uri.isNullOrBlank()) {
            val restored = try {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, Uri.parse(uri))
                root != null && root.isDirectory && root.canRead() && root.canWrite()
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
            if (restored) {
                mutableWorkspaceState.value = WorkspaceState(
                    selected = true,
                    name = workspacePreferences.getString("tree_name", null)
                )
            } else {
                mutableWorkspaceState.value = WorkspaceState(
                    selected = false,
                    name = workspacePreferences.getString("tree_name", null),
                    error = "FxForge can no longer access the saved project folder. Re-select it to restore read and write access. Your private workspace files are preserved."
                )
            }
        }

        if (!uri.isNullOrBlank() && legacyWorkspaceDirectory.exists()) {
            val projectDirectory = workspaceDirectoryFor(uri)
            if (!projectDirectory.exists()) {
                projectDirectory.parentFile?.mkdirs()
                // One-time migration: keep the user's existing private files
                // when moving from the older shared "active" workspace layout.
                legacyWorkspaceDirectory.renameTo(projectDirectory)
            }
        }
    }

    fun isProotAvailable(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } && ProotBinaryRuntime.isBundled(appContext)

    /**
     * Android procfs can return ENOSYS for libgcrypt's FIPS sysctl probe.
     * Overlay that guest path with a regular file containing 0 (FIPS disabled).
     */
    private fun prepareFipsCompatibilityFile(): File {
        val source = File(runtimeDirectory, "proot-compat/fips_enabled")
        source.parentFile?.mkdirs()
        if (!source.isFile || source.readText() != "0\n") source.writeText("0\n")
        val target = File(rootfsDirectory, "proc/sys/crypto/fips_enabled")
        target.parentFile?.mkdirs()
        if (!target.exists()) target.writeText("0\n")
        return source
    }

    /**
     * PRoot emulates root but cannot reliably switch APT's HTTP helper to the
     * unprivileged _apt UID. Keep the helper as guest root inside this sandbox.
     * This does not change package signature verification.
     */
    private fun prepareAptProotCompatibility() {
        val config = File(rootfsDirectory, "etc/apt/apt.conf.d/99fxforge-proot")
        config.parentFile?.mkdirs()
        val contents = "APT::Sandbox::User \"root\";\n"
        if (!config.isFile || config.readText() != contents) config.writeText(contents)
    }

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
            if (!developerToolsMarker.isFile) {
                mutableSdkState.value = AndroidSdkState(
                    installed = true,
                    installing = true,
                    phase = "Installing Git and developer CLI tools"
                )
                val developerTools = runCommand(
                    "apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -qq install -y git openssh-client ripgrep jq procps file rsync",
                    timeoutSeconds = 900
                )
                if (developerTools.error != null || developerTools.exitCode != 0) {
                    mutableSdkState.value = AndroidSdkState(
                        installed = true,
                        phase = "Android SDK installed · developer tools need attention",
                        error = developerTools.error ?: developerTools.output.takeLast(1200)
                    )
                    return@withContext
                }
                developerToolsMarker.parentFile?.mkdirs()
                developerToolsMarker.writeText("git\nopenssh-client\nripgrep\njq\nprocps\nfile\nrsync\n")
            }
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
                "apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -qq install -y openjdk-17-jdk-headless curl unzip xz-utils ca-certificates git openssh-client ripgrep jq procps file rsync",
                timeoutSeconds = 900
            )
            requireCommandSuccess(prerequisites, "Could not install Java and SDK prerequisites.")
            developerToolsMarker.parentFile?.mkdirs()
            developerToolsMarker.writeText("git\nopenssh-client\nripgrep\njq\nprocps\nfile\nrsync\n")
            mutableSdkState.value = mutableSdkState.value.copy(phase = "Downloading Android command-line tools")
            val tools = runCommand(
                "mkdir -p /opt/android-sdk/cmdline-tools /tmp/fxforge-sdk-tools && " +
                    "curl -fsSL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o /tmp/fxforge-sdk-tools.zip && " +
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
                    "curl -fsSL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/aapt2 -o /opt/android-sdk/build-tools/36.0.0/aapt2 && " +
                    "echo '7512ff7e381bea6fd310b6f6e347422c8fda21e07c6e3f0162742e95eb9d7f98  /opt/android-sdk/build-tools/36.0.0/aapt2' | sha256sum -c - && " +
                    "curl -fL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/aidl -o /opt/android-sdk/build-tools/36.0.0/aidl && " +
                    "echo '8c97356b8bba8f7aad44cfd408e3ee24c66a1258244b5d08af1c9249f50dd659  /opt/android-sdk/build-tools/36.0.0/aidl' | sha256sum -c - && " +
                    "curl -fL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/zipalign -o /opt/android-sdk/build-tools/36.0.0/zipalign && " +
                    "echo 'e8856fb24b10095eb6e940c577ce96d89ddbfc45aa0f7eeaef1597ef68f11a12  /opt/android-sdk/build-tools/36.0.0/zipalign' | sha256sum -c - && " +
                    "curl -fL https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/split-select -o /opt/android-sdk/build-tools/36.0.0/split-select && " +
                    "echo 'fb7f0c3c87dbd4243d7e1277ba64ded2399389244058b6a4c969cc615360766c  /opt/android-sdk/build-tools/36.0.0/split-select' | sha256sum -c - && " +
                    "chmod 755 /opt/android-sdk/build-tools/36.0.0/aapt2 /opt/android-sdk/build-tools/36.0.0/aidl /opt/android-sdk/build-tools/36.0.0/zipalign /opt/android-sdk/build-tools/36.0.0/split-select && " +
                    "mkdir -p /opt/gradle && " +
                    "curl -fsSL --retry 3 https://services.gradle.org/distributions/gradle-8.13-bin.zip -o /tmp/gradle-8.13-bin.zip && " +
                    "echo '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78  /tmp/gradle-8.13-bin.zip' | sha256sum -c - && " +
                    "unzip -q /tmp/gradle-8.13-bin.zip -d /opt/gradle && " +
                    "rm -f /tmp/gradle-8.13-bin.zip && " +
                    "/opt/android-sdk/build-tools/36.0.0/aapt2 version && /opt/gradle/gradle-8.13/bin/gradle --version",
                timeoutSeconds = 1800
            )
            requireCommandSuccess(nativeTools, "Could not install ARM64 Android build tools or Gradle.")
            val gradleProperties = File(rootfsDirectory, "root/.gradle/gradle.properties")
            gradleProperties.parentFile?.mkdirs()
            if (!gradleProperties.exists() || !gradleProperties.readText().contains("android.aapt2FromMavenOverride=")) {
                gradleProperties.appendText("\nandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/36.0.0/aapt2\n")
            }
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

    suspend fun verifyAndroidToolchain(): LinuxCommandResult {
        if (!androidSdkMarker.isFile) {
            return LinuxCommandResult("", null, false, "Install the Android SDK first.")
        }
        return runCommand(
            "set -e; aapt2 version; aidl --version; zipalign -h >/dev/null 2>&1 || true; split-select --help >/dev/null 2>&1 || true; gradle --version | head -n 4; java -version",
            timeoutSeconds = 120
        )
    }

    suspend fun inspectInstalledTools(): LinuxCommandResult {
        if (!markerFile.isFile) {
            return LinuxCommandResult("", null, false, "Install Debian before inspecting Linux tools.")
        }
        return runCommand(
            "set +e; " +
                "echo '=== Debian ==='; cat /etc/os-release; " +
                "echo '=== Architecture ==='; uname -m; " +
                "echo '=== Java ==='; java -version 2>&1; " +
                "echo '=== Gradle ==='; /opt/gradle/gradle-8.13/bin/gradle --version 2>&1 | head -n 5; " +
                "echo '=== Android SDK Manager ==='; /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --version 2>&1; " +
                "echo '=== Installed SDK packages ==='; /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --list_installed 2>&1 | head -n 50; " +
                "echo '=== Android NDK ==='; if [ -d /opt/android-sdk/ndk ]; then find /opt/android-sdk/ndk -mindepth 1 -maxdepth 1 -type d -print; else echo 'Not installed'; fi; " +
                "echo '=== Installed Debian packages ==='; dpkg-query -W -f='\${binary:Package} \${Version}\\n' 2>/dev/null | sort",
            timeoutSeconds = 180,
            maxOutputChars = 65536
        )
    }

    suspend fun installOpenCode() = withContext(Dispatchers.IO) {
        if (!markerFile.isFile) {
            mutableOpenCodeState.value = mutableOpenCodeState.value.copy(
                error = "Install Debian before installing OpenCode."
            )
            return@withContext
        }
        // A file existing is not proof that the CLI can execute in this PRoot.
        // Verify it from inside Debian before reporting the installation as healthy.
        if (openCodeBinary.isFile) {
            val verification = runCommand(
                "test -x /root/.opencode/bin/opencode && /root/.opencode/bin/opencode --version",
                timeoutSeconds = 60
            )
            if (verification.exitCode == 0) {
                mutableOpenCodeState.value = OpenCodeState(
                    installed = true,
                    output = verification.output
                )
                return@withContext
            }
        }
        mutableOpenCodeState.value = OpenCodeState(installing = true, output = null)
        val installResult = runCommand(
            "set -eu; " +
                "apt-get update; " +
                "DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl git bash; " +
                "curl -fL --retry 3 --retry-delay 2 https://opencode.ai/v2/install -o /tmp/fxforge-opencode-install.sh; " +
                "bash /tmp/fxforge-opencode-install.sh; " +
                "test -x /root/.opencode/bin/opencode; " +
                "/root/.opencode/bin/opencode --version; " +
                "rm -f /tmp/fxforge-opencode-install.sh",
            timeoutSeconds = 900,
            maxOutputChars = MAX_BUILD_OUTPUT_CHARS
        )
        // The install script itself runs test -x and --version inside Debian;
        // host-side File.canExecute() is not authoritative for PRoot guest files.
        val installed = installResult.exitCode == 0 && openCodeBinary.isFile
        val failureDetails = when {
            installResult.error != null -> installResult.error
            installResult.exitCode != 0 -> buildString {
                append("OpenCode installer exited with code ")
                append(installResult.exitCode ?: "unknown")
                if (installResult.output.isNotBlank()) {
                    append(":\n")
                    append(installResult.output.takeLast(3000))
                }
            }
            !installed -> "Installer finished but /root/.opencode/bin/opencode is missing or not executable.\n" +
                installResult.output.takeLast(3000)
            else -> null
        }
        mutableOpenCodeState.value = OpenCodeState(
            installed = installed,
            error = if (installed) null else failureDetails
                ?: "OpenCode installation failed without reporting an error.",
            output = installResult.output
        )
    }

    suspend fun startOpenCode() = withContext(Dispatchers.IO) {
        workspaceMutex.withLock {
        if (!openCodeBinary.isFile) {
            mutableOpenCodeState.value = OpenCodeState(
                installed = false,
                error = "OpenCode is not installed. Install it before starting the server."
            )
            return@withContext
        }
        val verification = runCommand(
            "test -x /root/.opencode/bin/opencode && /root/.opencode/bin/opencode --version",
            timeoutSeconds = 60
        )
        if (verification.exitCode != 0) {
            mutableOpenCodeState.value = OpenCodeState(
                installed = false,
                error = buildString {
                    append("OpenCode is present but failed its Debian execution check.")
                    if (verification.error != null) {
                        append("\n")
                        append(verification.error)
                    }
                    if (verification.output.isNotBlank()) {
                        append("\n")
                        append(verification.output.takeLast(2000))
                    }
                },
                output = verification.output
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
        workspaceDirectory.mkdirs()
        File(rootfsDirectory, "workspace").mkdirs()
        val binary = File(appContext.applicationInfo.nativeLibraryDir, PROOT_BINARY)
        val loader = File(appContext.applicationInfo.nativeLibraryDir, PROOT_LOADER)
        val loader32 = File(appContext.applicationInfo.nativeLibraryDir, PROOT_LOADER_32)
        val fipsEnabledFile = prepareFipsCompatibilityFile()
        val process = try {
            ProcessBuilder(
                binary.absolutePath,
                "--link2symlink",
                "-0",
                "-r", rootfsDirectory.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", fipsEnabledFile.absolutePath + ":/proc/sys/crypto/fips_enabled",
                "-b", "/sys",
                "-b", workspaceDirectory.absolutePath + ":/workspace",
                "-w", "/workspace",
                "/usr/bin/env",
                "HOME=/root",
                "PATH=/root/.opencode/bin:/opt/gradle/gradle-8.13/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/36.0.0:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64",
                "ANDROID_HOME=/opt/android-sdk",
                "ANDROID_SDK_ROOT=/opt/android-sdk",
                "GRADLE_USER_HOME=/root/.gradle",
                "TERM=xterm-256color",
                "OPENCODE_PASSWORD=" + password,
                "OPENCODE_SERVER_PASSWORD=" + password,
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
        for (attempt in 0 until OPEN_CODE_START_ATTEMPTS) {
            if (!process.isAlive) break
            if (isOpenCodeServerReady(password)) {
                ready = true
                break
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
    }

    suspend fun registerAndroidMcp(info: AndroidMcpBridgeInfo): String? = withContext(Dispatchers.IO) {
        val password = openCodePassword
            ?: return@withContext "OpenCode server credentials are unavailable."
        val config = JSONObject()
            .put("type", "remote")
            .put("url", "http://127.0.0.1:" + info.port + "/mcp")
            .put("oauth", false)
            .put("disabled", false)
            .put("codemode", false)
            .put("timeout", JSONObject().put("catalog", 120000).put("execution", 120000))
            .put("protocol", "legacy")
            .put("headers", JSONObject().put("Authorization", "Bearer " + info.token))
        val payload = JSONObject().put("config", config)
        val token = Base64.getEncoder().encodeToString(("opencode:" + password).toByteArray(Charsets.UTF_8))
        val registration = try {
            URL("http://localhost:" + OPEN_CODE_PORT + "/api/experimental/mcp/fxforge-android")
                .openConnection() as HttpURLConnection
        } catch (exception: IOException) {
            return@withContext "Could not reach OpenCode's MCP registration API: " + (exception.message ?: "connection error")
        }
        try {
            registration.connectTimeout = 5000
            registration.readTimeout = 15000
            registration.requestMethod = "PUT"
            registration.doOutput = true
            registration.setRequestProperty("Content-Type", "application/json")
            registration.setRequestProperty("Authorization", "Basic " + token)
            registration.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = registration.responseCode
            if (code !in 200..299) {
                val detail = try {
                    registration.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } catch (_: IOException) {
                    ""
                }
                return@withContext "OpenCode rejected the Android MCP server (HTTP " + code + "). " + detail.take(1000)
            }
        } catch (exception: IOException) {
            return@withContext "Could not register the Android MCP bridge: " + (exception.message ?: "HTTP error")
        } finally {
            registration.disconnect()
        }

        val statusConnection = try {
            URL("http://localhost:" + OPEN_CODE_PORT + "/api/mcp").openConnection() as HttpURLConnection
        } catch (exception: IOException) {
            return@withContext "The Android MCP server was registered, but its connection status could not be checked."
        }
        try {
            statusConnection.connectTimeout = 5000
            statusConnection.readTimeout = 10000
            statusConnection.setRequestProperty("Authorization", "Basic " + token)
            val code = statusConnection.responseCode
            if (code !in 200..299) {
                return@withContext "The Android MCP server was registered, but OpenCode status returned HTTP " + code + "."
            }
            val body = statusConnection.inputStream.bufferedReader().use { it.readText() }
            val servers = JSONObject(body).optJSONArray("data") ?: JSONArray()
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                if (server.optString("name") == "fxforge-android") {
                    return@withContext when (server.optJSONObject("status")?.optString("status")) {
                        "connected" -> null
                        "pending" -> "Android MCP server is registered and still connecting."
                        "disabled" -> "Android MCP server is registered but disabled in OpenCode."
                        "needs_auth" -> "Android MCP server requires authentication."
                        "failed" -> "Android MCP connection failed: " +
                            (server.optJSONObject("status")?.optString("error") ?: "unknown error")
                        else -> "Android MCP server is registered, but OpenCode returned an unknown status."
                    }
                }
            }
            "OpenCode accepted the Android MCP configuration but did not list the server."
        } catch (exception: IOException) {
            "The Android MCP server was registered, but status verification failed: " +
                (exception.message ?: "HTTP error")
        } finally {
            statusConnection.disconnect()
        }
    }

    suspend fun stopOpenCode() = withContext(Dispatchers.IO) {
        workspaceMutex.withLock {
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
            URL("http://localhost:" + OPEN_CODE_PORT + "/api/info").openConnection() as HttpURLConnection
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

    suspend fun startTerminalSession() = withContext(Dispatchers.IO) {
        workspaceMutex.withLock {
            terminalMutex.withLock {
            if (terminalProcess?.isAlive == true) {
                mutableTerminalState.value = LinuxTerminalState(running = true)
                return@withLock
            }
            if (!markerFile.isFile || !isProotAvailable()) {
                mutableTerminalState.value = LinuxTerminalState(
                    error = "Install the ARM64 PRoot runtime and Debian before opening the Linux shell."
                )
                return@withLock
            }
            mutableTerminalState.value = LinuxTerminalState(starting = true)
            workspaceDirectory.mkdirs()
            File(rootfsDirectory, "workspace").mkdirs()
            val temporaryDirectory = File(appContext.cacheDir, "proot-tmp")
            temporaryDirectory.mkdirs()
            val binary = File(appContext.applicationInfo.nativeLibraryDir, PROOT_BINARY)
            val loader = File(appContext.applicationInfo.nativeLibraryDir, PROOT_LOADER)
            val loader32 = File(appContext.applicationInfo.nativeLibraryDir, PROOT_LOADER_32)
            val fipsEnabledFile = prepareFipsCompatibilityFile()
            prepareAptProotCompatibility()
            try {
                val process = ProcessBuilder(
                    binary.absolutePath,
                    "--link2symlink",
                    "-0",
                    "-r", rootfsDirectory.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", fipsEnabledFile.absolutePath + ":/proc/sys/crypto/fips_enabled",
                    "-b", "/sys",
                    "-b", workspaceDirectory.absolutePath + ":/workspace",
                    "-w", "/workspace",
                    "/usr/bin/env",
                    "HOME=/root",
                    "PATH=/opt/gradle/gradle-8.13/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/36.0.0:/root/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64",
                    "ANDROID_HOME=/opt/android-sdk",
                    "ANDROID_SDK_ROOT=/opt/android-sdk",
                    "GRADLE_USER_HOME=/root/.gradle",
                    "TERM=xterm-256color",
                    "/bin/bash",
                    "--noprofile",
                    "--norc"
                ).directory(rootfsDirectory)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
                        environment()["PROOT_LOADER"] = loader.absolutePath
                        environment()["PROOT_LOADER_32"] = loader32.absolutePath
                        environment()["PROOT_NO_SECCOMP"] = "1"
                    }
                    .start()
                terminalProcess = process
                terminalReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                terminalWriter = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
                mutableTerminalState.value = LinuxTerminalState(running = true)
            } catch (exception: Exception) {
                terminalProcess = null
                terminalReader = null
                terminalWriter = null
                mutableTerminalState.value = LinuxTerminalState(
                    error = "Could not start Debian shell: " + (exception.message ?: "process error")
                )
            }
            }
        }
    }

    suspend fun executeTerminalCommand(command: String, timeoutSeconds: Int = 120): LinuxCommandResult =
        withContext(Dispatchers.IO) {
            terminalMutex.withLock {
                val process = terminalProcess
                val reader = terminalReader
                val writer = terminalWriter
                if (process == null || !process.isAlive || reader == null || writer == null) {
                    mutableTerminalState.value = LinuxTerminalState(error = "Start the Linux shell before running commands.")
                    return@withLock LinuxCommandResult("", null, false, "Linux shell is not running.")
                }
                if (command.isBlank() || command.length > MAX_COMMAND_CHARS) {
                    return@withLock LinuxCommandResult("", null, false, "Command must contain 1 to 8192 characters.")
                }
                terminalCommandSequence += 1
                val marker = "__FXFORGE_TERMINAL_END_" + terminalCommandSequence + "__"
                val outputTask = FutureTask {
                    val output = StringBuilder()
                    var truncated = false
                    var exitCode: Int? = null
                    while (true) {
                        val line = reader.readLine() ?: throw IOException("Linux shell exited before returning a command result.")
                        if (line.startsWith(marker + ":")) {
                            exitCode = line.substringAfter(':').toIntOrNull()
                            break
                        }
                        val remaining = MAX_TERMINAL_OUTPUT_CHARS - output.length
                        if (remaining > 0) {
                            if (line.length > remaining) {
                                output.append(line.take(remaining))
                                truncated = true
                            } else {
                                output.append(line).append('\n')
                            }
                        } else {
                            truncated = true
                        }
                    }
                    CommandOutput(output.toString(), truncated, exitCode)
                }
                Thread(outputTask, "fxforge-terminal-reader").apply {
                    isDaemon = true
                    start()
                }
                try {
                    writer.write(command)
                    writer.newLine()
                    writer.write("printf '\\n" + marker + ":%s\\n' \"$?\"")
                    writer.newLine()
                    writer.flush()
                    val timeout = timeoutSeconds.coerceIn(1, MAX_TERMINAL_TIMEOUT_SECONDS)
                    val output = outputTask.get(timeout.toLong(), TimeUnit.SECONDS)
                    LinuxCommandResult(output.text, output.exitCode, output.truncated)
                } catch (exception: TimeoutException) {
                    process.destroyForcibly()
                    terminalProcess = null
                    terminalReader = null
                    terminalWriter = null
                    mutableTerminalState.value = LinuxTerminalState(error = "Linux command timed out; the shell session was closed.")
                    LinuxCommandResult("", null, false, "Command timed out after " + timeoutSeconds + " seconds.")
                } catch (exception: ExecutionException) {
                    process.destroyForcibly()
                    terminalProcess = null
                    terminalReader = null
                    terminalWriter = null
                    mutableTerminalState.value = LinuxTerminalState(error = exception.cause?.message ?: "Linux shell stopped.")
                    LinuxCommandResult("", null, false, exception.cause?.message ?: "Linux shell stopped.")
                } catch (exception: Exception) {
                    process.destroyForcibly()
                    terminalProcess = null
                    terminalReader = null
                    terminalWriter = null
                    mutableTerminalState.value = LinuxTerminalState(error = exception.message ?: "Linux shell stopped.")
                    LinuxCommandResult("", null, false, exception.message ?: "Linux shell stopped.")
                }
            }
        }

    suspend fun stopTerminalSession() = withContext(Dispatchers.IO) {
        workspaceMutex.withLock {
            terminalMutex.withLock {
            val process = terminalProcess
            terminalProcess = null
            terminalReader = null
            terminalWriter = null
            if (process != null && process.isAlive) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            mutableTerminalState.value = LinuxTerminalState()
            }
        }
    }

    suspend fun syncWorkspaceFromSaf(
        source: androidx.documentfile.provider.DocumentFile,
        preferPrivateOnConflicts: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        workspaceMutex.withLock {
            if (!source.isDirectory || !source.canRead() || !source.canWrite()) {
                mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                    error = "The selected workspace folder must be readable and writable."
                )
                return@withLock
            }

            val requestedUri = source.uri.toString()
            val activeUri = workspacePreferences.getString("tree_uri", null)
            mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                syncing = true,
                error = null,
                conflicts = emptyList()
            )
            try {
                if (activeUri == requestedUri) {
                    synchronizeWorkspaceLocked(source, requestedUri, preferPrivateOnConflicts)
                    return@withLock
                }

                // Do not switch the project while a process is still using the
                // current /workspace bind mount. Stop OpenCode and the Linux shell first.
                if (openCodeProcess?.isAlive == true ||
                    terminalProcess?.isAlive == true ||
                    mutableOpenCodeState.value.starting ||
                    mutableTerminalState.value.starting
                ) {
                    mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                        syncing = false,
                        error = "Stop OpenCode and the Linux terminal before switching the active workspace."
                    )
                    return@withLock
                }

                // Reconcile the old project before changing the active URI. Each
                // project keeps its own private directory, so switching never
                // replaces or discards another project's local files.
                var previousWorkspaceDetached = false
                if (!activeUri.isNullOrBlank()) {
                    val previousRoot = try {
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, Uri.parse(activeUri))
                    } catch (_: Exception) {
                        null
                    }
                    if (previousRoot == null || !previousRoot.isDirectory ||
                        !previousRoot.canRead() || !previousRoot.canWrite()
                    ) {
                        // The Android grant may have been revoked or the external
                        // folder removed. Keep the old private project directory
                        // under its original URI hash; never replace or delete it.
                        previousWorkspaceDetached = true
                    } else {
                        val previousSynced = synchronizeWorkspaceLocked(
                            previousRoot,
                            activeUri,
                            preferPrivateOnConflicts
                        )
                        if (!previousSynced) {
                            mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                                syncing = false,
                                error = "Resolve the previous workspace conflicts before switching projects. No files were discarded."
                            )
                            return@withLock
                        }
                    }
                } else if (legacyWorkspaceDirectory.listFiles()?.isNotEmpty() == true) {
                    throw IOException(
                        "FxForge found private workspace files without a linked storage folder. " +
                            "They were preserved; link or recover that workspace before switching."
                    )
                }

                val targetWorkspace = workspaceDirectoryFor(requestedUri)
                val targetParent = targetWorkspace.parentFile
                    ?: throw IOException("Could not locate the private workspace directory.")
                if (!targetParent.exists() && !targetParent.mkdirs()) {
                    throw IOException("Could not create the private workspace directory.")
                }

                if (targetWorkspace.isDirectory) {
                    val restored = ProjectWorkspaceSync.synchronize(
                        appContext,
                        source,
                        targetWorkspace,
                        loadWorkspaceBaseline(requestedUri)
                    )
                    if (restored.conflicts.isNotEmpty()) {
                        mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                            syncing = false,
                            error = "This saved workspace has conflicts with its storage folder. Resolve them before opening it.",
                            conflicts = restored.conflicts.map { it.path }
                        )
                        return@withLock
                    }
                    saveWorkspaceBaseline(
                        requestedUri,
                        restored.baselineAfter
                            ?: throw IOException("Could not verify the saved workspace.")
                    )
                } else {
                    // Import into a separate project-specific directory. No
                    // existing project's active or cached workspace is replaced.
                    val stagingWorkspace = File(targetParent, ".incoming-" + UUID.randomUUID().toString())
                    ProjectWorkspaceSync.copyFromSaf(appContext, source, stagingWorkspace)
                    try {
                        val initialSync = ProjectWorkspaceSync.synchronize(
                            appContext,
                            source,
                            stagingWorkspace,
                            emptyMap()
                        )
                        if (initialSync.conflicts.isNotEmpty()) {
                            throw IOException("The selected folder changed during import. Retry the workspace selection.")
                        }
                        saveWorkspaceBaseline(
                            requestedUri,
                            initialSync.baselineAfter
                                ?: throw IOException("Could not verify the imported workspace.")
                        )
                        if (!stagingWorkspace.renameTo(targetWorkspace)) {
                            throw IOException("Could not activate the imported project workspace.")
                        }
                    } catch (exception: Exception) {
                        stagingWorkspace.deleteRecursively()
                        throw exception
                    }
                }

                val saved = workspacePreferences.edit()
                    .putString("tree_uri", requestedUri)
                    .putString("tree_name", source.name ?: "Android project")
                    .commit()
                if (!saved) {
                    throw IOException("Could not save the active workspace selection. The imported project files remain available.")
                }
                mutableWorkspaceState.value = WorkspaceState(
                    selected = true,
                    name = source.name ?: "Android project",
                    lastSyncSummary = if (previousWorkspaceDetached) {
                        "Workspace ready · previous private project preserved because its Android folder access was revoked"
                    } else {
                        "Workspace ready · " + (source.name ?: "Android project")
                    }
                )
            } catch (exception: Exception) {
                mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                    syncing = false,
                    error = exception.message ?: "Could not synchronize the selected project workspace."
                )
            }
        }
    }

    suspend fun syncWorkspaceToSaf(preferPrivateOnConflicts: Boolean? = null) =
        withContext(Dispatchers.IO) {
            workspaceMutex.withLock {
                val uri = workspacePreferences.getString("tree_uri", null)
                if (uri.isNullOrBlank()) {
                    mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                        error = "Choose a project folder before synchronizing changes."
                    )
                    return@withLock
                }
                val destination =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, Uri.parse(uri))
                if (destination == null) {
                    mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                        syncing = false,
                        error = "The saved workspace folder is no longer available. Private files were preserved."
                    )
                    return@withLock
                }
                synchronizeWorkspaceLocked(destination, uri, preferPrivateOnConflicts)
            }
        }

    /**
     * Returns false when conflicts are waiting for an explicit user decision.
     * Both copies and the old baseline remain intact in that case.
     */
    private fun synchronizeWorkspaceLocked(
        storageRoot: androidx.documentfile.provider.DocumentFile,
        uri: String,
        preferPrivateOnConflicts: Boolean?
    ): Boolean {
        mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
            syncing = true,
            error = null,
            conflicts = emptyList()
        )
        return try {
            val baseline = loadWorkspaceBaseline(uri)
            val result = ProjectWorkspaceSync.synchronize(
                appContext,
                storageRoot,
                workspaceDirectory,
                baseline,
                preferPrivateOnConflicts
            )
            if (result.conflicts.isNotEmpty()) {
                val paths = result.conflicts.map { it.path }
                mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                    syncing = false,
                    error = "Both copies changed these files. Choose which version to keep before syncing.",
                    conflicts = paths
                )
                return false
            }
            val newBaseline = result.baselineAfter
                ?: throw IOException("The workspace sync completed without a verified baseline.")
            saveWorkspaceBaseline(uri, newBaseline)
            mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                syncing = false,
                error = null,
                conflicts = emptyList(),
                lastSyncSummary = buildString {
                    append("Synced ")
                    append(result.copiedToPrivate + result.copiedToStorage)
                    append(" file(s); removed ")
                    append(result.deletedFromPrivate + result.deletedFromStorage)
                    append(" deleted file(s)")
                }
            )
            true
        } catch (exception: Exception) {
            mutableWorkspaceState.value = mutableWorkspaceState.value.copy(
                syncing = false,
                error = exception.message ?: "Workspace synchronization failed. Existing files were preserved where possible."
            )
            false
        }
    }

    private fun loadWorkspaceBaseline(uri: String): Map<String, String> {
        val file = workspaceBaselineFile(uri)
        if (!file.isFile) return emptyMap()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                result[path] = json.getString(path)
            }
            result
        } catch (exception: Exception) {
            throw IOException("The workspace sync history is damaged. Files were not overwritten; recovery is required.", exception)
        }
    }

    private fun saveWorkspaceBaseline(uri: String, baseline: Map<String, String>) {
        val destination = workspaceBaselineFile(uri)
        val parent = destination.parentFile
            ?: throw IOException("Could not locate the workspace history directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create the workspace history directory.")
        }
        val json = JSONObject()
        baseline.toSortedMap().forEach { (path, hash) -> json.put(path, hash) }
        val temporary = File.createTempFile(".workspace-manifest-", ".tmp", parent)
        val backup = File(parent, ".workspace-manifest-backup-" + UUID.randomUUID().toString())
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            var movedOld = false
            if (destination.exists()) {
                if (!destination.renameTo(backup)) {
                    throw IOException("Could not preserve the previous workspace sync history.")
                }
                movedOld = true
            }
            if (!temporary.renameTo(destination)) {
                if (movedOld && !backup.renameTo(destination)) {
                    throw IOException("Could not update the workspace history. Recovery copy: " + backup.absolutePath)
                }
                throw IOException("Could not activate the updated workspace sync history.")
            }
            if (movedOld) backup.delete()
        } catch (exception: Exception) {
            temporary.delete()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw exception
        }
    }

    private fun workspaceDirectoryFor(uri: String): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(appContext.filesDir, "workspaces/projects/$key")
    }

    private fun workspaceBaselineFile(uri: String): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(appContext.filesDir, "workspaces/manifests/$key.json")
    }

    suspend fun buildProject(projectRoot: androidx.documentfile.provider.DocumentFile): AndroidBuildResult =
        withContext(Dispatchers.IO) {
            workspaceMutex.withLock {
                if (!androidSdkMarker.isFile) {
                    return@withLock AndroidBuildResult(
                        false,
                        "",
                        error = "Install the Android SDK and ARM64 build tools first."
                    )
                }
                if (!projectRoot.isDirectory || !projectRoot.canRead()) {
                    return@withLock AndroidBuildResult(
                        false,
                        "",
                        error = "The selected Android project folder cannot be read."
                    )
                }
                val hasSettings = projectRoot.findFile("settings.gradle.kts")?.isFile == true ||
                    projectRoot.findFile("settings.gradle")?.isFile == true
                if (!hasSettings) {
                    return@withLock AndroidBuildResult(
                        false,
                        "",
                        error = "The selected folder is not a Gradle project. A settings.gradle or settings.gradle.kts file is required."
                    )
                }

                // A project build must not repurpose the active OpenCode workspace.
                // Build from it only when the selected SAF root is the exact active
                // workspace; otherwise use an isolated temporary build directory.
                val isActiveWorkspace =
                    workspacePreferences.getString("tree_uri", null) == projectRoot.uri.toString() &&
                        workspaceDirectory.isDirectory
                val workspace = if (isActiveWorkspace) {
                    workspaceDirectory
                } else {
                    File(appContext.filesDir, "build-workspaces/" + UUID.randomUUID().toString())
                }
                val temporaryWorkspace = !isActiveWorkspace

                try {
                    if (temporaryWorkspace) {
                        ProjectWorkspaceSync.copyFromSaf(appContext, projectRoot, workspace)
                    }
                    File(workspace, "local.properties").writeText("sdk.dir=/opt/android-sdk\\n")
                    File(rootfsDirectory, "workspace").mkdirs()
                    val result = runCommand(
                        "cd /workspace && if [ -f ./gradlew ]; then sh ./gradlew --no-daemon assembleDebug; else gradle --no-daemon assembleDebug; fi",
                        timeoutSeconds = 3600,
                        binds = listOf(workspace.absolutePath to "/workspace"),
                        maxOutputChars = MAX_BUILD_OUTPUT_CHARS
                    )
                    if (result.exitCode != 0) {
                        return@withLock AndroidBuildResult(
                            false,
                            result.output,
                            error = result.error ?: "Gradle build failed with exit code " +
                                (result.exitCode?.toString() ?: "unknown")
                        )
                    }
                    val apk = File(workspace, "app/build/outputs/apk/debug/app-debug.apk")
                    if (!apk.isFile) {
                        return@withLock AndroidBuildResult(
                            false,
                            result.output,
                            error = "Gradle completed but app/build/outputs/apk/debug/app-debug.apk was not found."
                        )
                    }
                    val copiedApk = ProjectWorkspaceSync.copyArtifactToSaf(
                        appContext,
                        apk,
                        projectRoot,
                        "FxForge-debug.apk"
                    )
                    AndroidBuildResult(true, result.output, copiedApk.uri.toString())
                } catch (exception: Exception) {
                    AndroidBuildResult(
                        false,
                        "",
                        error = exception.message ?: "Could not build the selected Android project."
                    )
                } finally {
                    if (temporaryWorkspace && workspace.exists() && !workspace.deleteRecursively()) {
                        // Leave a recoverable build directory rather than risk touching
                        // the active user's workspace if Android refuses deletion.
                    }
                }
            }
        }

    suspend fun runCommand(
        command: String,
        timeoutSeconds: Int = 30,
        binds: List<Pair<String, String>> = emptyList(),
        maxOutputChars: Int = MAX_OUTPUT_CHARS
    ): LinuxCommandResult =
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
            val fipsEnabledFile = prepareFipsCompatibilityFile()
            prepareAptProotCompatibility()
            val prootArguments = mutableListOf(
                binary.absolutePath,
                "--link2symlink",
                "-0",
                "-r", rootfsDirectory.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", fipsEnabledFile.absolutePath + ":/proc/sys/crypto/fips_enabled",
                "-b", "/sys"
            )
            binds.forEach { (hostPath, guestPath) ->
                prootArguments.addAll(listOf("-b", hostPath + ":" + guestPath))
            }
            prootArguments.addAll(
                listOf(
                    "-w", "/root",
                    "/usr/bin/env",
                    "HOME=/root",
                    "PATH=/opt/gradle/gradle-8.13/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/36.0.0:/root/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64",
                    "ANDROID_HOME=/opt/android-sdk",
                    "ANDROID_SDK_ROOT=/opt/android-sdk",
                    "GRADLE_USER_HOME=/root/.gradle",
                    "TERM=xterm-256color",
                    "/bin/sh",
                    "-lc",
                    command
                )
            )
            val process = try {
                ProcessBuilder(prootArguments)
                    .directory(rootfsDirectory)
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
                        val outputLimit = maxOutputChars.coerceIn(1024, MAX_BUILD_OUTPUT_CHARS)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            val remaining = outputLimit - output.length
                            if (remaining > 0) {
                                val retained = minOf(count, remaining)
                                output.append(buffer, 0, retained)
                                if (retained < count) truncated = true
                            } else {
                                truncated = true
                            }
                            // Keep draining the pipe after the display limit. Killing
                            // the child here used to turn successful apt/Gradle commands
                            // into failures merely because they produced verbose output.
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
                    process.waitFor(2, TimeUnit.SECONDS)
                    val partialOutput = try {
                        outputTask.get(2, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        CommandOutput("", false)
                    }
                    LinuxCommandResult(
                        partialOutput.text,
                        null,
                        partialOutput.truncated,
                        "Linux command timed out after " + timeout + " seconds."
                    )
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
        private const val MAX_TERMINAL_OUTPUT_CHARS = 64 * 1024
        private const val MAX_TERMINAL_TIMEOUT_SECONDS = 3600
        private const val MAX_BUILD_OUTPUT_CHARS = 256 * 1024
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

private data class CommandOutput(
    val text: String,
    val truncated: Boolean,
    val exitCode: Int? = null
)
