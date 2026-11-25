package dev.fxforge.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import dev.fxforge.shizuku.ShizukuApkInstallResult
import dev.fxforge.shizuku.ShizukuCommandResult
import dev.fxforge.shizuku.ShizukuPrivilege
import dev.fxforge.shizuku.ShizukuStatusRepository
import dev.fxforge.shizuku.ShizukuUserServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fxForgeApplication = application as FxForgeApplication
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FxForgeApp(
                        fxForgeApplication.shizukuStatusRepository,
                        fxForgeApplication.shizukuUserServiceController,
                        fxForgeApplication.linuxRuntimeManager,
                        fxForgeApplication.androidMcpBridge
                    )
                }
            }
        }
    }
}

private enum class Destination(val title: String, val marker: String) {
    HOME("Home", "H"),
    PROJECTS("Projects", "P"),
    DEVICE("Device", "D"),
    ENVIRONMENT("Environment", "E"),
    TERMINAL("Terminal", ">"),
    OPENCODE("OpenCode", "AI")
}

private data class ProjectEntry(
    val document: DocumentFile,
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val contentType: String?,
    val sizeBytes: Long
) {
    val isApk: Boolean
        get() = !isDirectory && name.endsWith(".apk", ignoreCase = true)
}

@Composable
private fun FxForgeApp(
    repository: ShizukuStatusRepository,
    userServiceController: ShizukuUserServiceController,
    linuxRuntimeManager: LinuxRuntimeManager,
    androidMcpBridge: AndroidMcpBridge
) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    var openFile by remember { mutableStateOf<ProjectFile?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var apkToInstall by remember { mutableStateOf<ProjectFile?>(null) }
    var installResult by remember { mutableStateOf<ShizukuApkInstallResult?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installingApk by remember { mutableStateOf(false) }
    var projectToBuild by remember { mutableStateOf<DocumentFile?>(null) }
    var buildingProject by remember { mutableStateOf(false) }
    var buildResult by remember { mutableStateOf<AndroidBuildResult?>(null) }
    var projectsRefreshToken by remember { mutableStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                    Text("FxForge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Android development workstation", style = MaterialTheme.typography.bodyMedium)
                }
            },
            bottomBar = {
                if (openFile == null) {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Text(item.marker, fontWeight = FontWeight.Bold) },
                                label = { Text(item.title) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            when (destination) {
                Destination.HOME -> HomeScreen(repository, linuxRuntimeManager, androidMcpBridge, padding)
                Destination.PROJECTS -> {
                    val file = openFile
                    if (file == null) {
                        ProjectsScreen(
                            padding,
                            refreshToken = projectsRefreshToken,
                            onOpenFile = { openFile = it },
                            onInstallApk = {
                                apkToInstall = it
                                installResult = null
                                installError = null
                            },
                            onBuildProject = {
                                projectToBuild = it
                                buildResult = null
                            }
                        )
                    } else {
                        ProjectEditorScreen(
                            file = file,
                            padding = padding,
                            onClose = { openFile = null }
                        )
                    }
                }
                Destination.DEVICE -> DeviceScreen(repository, userServiceController, padding)
                Destination.ENVIRONMENT -> EnvironmentScreen(padding, linuxRuntimeManager)
                Destination.TERMINAL -> TerminalScreen(userServiceController, linuxRuntimeManager, padding)
                Destination.OPENCODE -> OpenCodeScreen(linuxRuntimeManager, androidMcpBridge, padding)
            }
        }

        projectToBuild?.let { target ->
            AlertDialog(
                onDismissRequest = {
                    if (!buildingProject) {
                        projectToBuild = null
                        buildResult = null
                    }
                },
                title = { Text("Build Android project") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FxForge copies this SAF folder into its private Debian workspace, runs Gradle with the ARM64 Android SDK, then copies the debug APK back into the project folder.")
                        if (!linuxRuntimeManager.state.value.installed) {
                            Text("Install Debian from Environment first.", color = MaterialTheme.colorScheme.error)
                        }
                        if (!linuxRuntimeManager.androidSdkState.value.installed) {
                            Text("Install Android SDK and ARM64 build tools from Environment first.", color = MaterialTheme.colorScheme.error)
                        }
                        if (buildingProject) {
                            CircularProgressIndicator()
                            Text("Building on the ARM64 Linux toolchain…")
                        }
                        buildResult?.let { result ->
                            Text(
                                if (result.success) "Build successful. FxForge-debug.apk was saved in the selected project folder."
                                else result.error ?: "Build failed.",
                                color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            if (result.output.isNotBlank()) {
                                Card(Modifier.fillMaxWidth()) {
                                    SelectionContainer {
                                        Text(
                                            result.output.takeLast(6000),
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (buildResult?.success == true) {
                        Button(onClick = {
                            projectToBuild = null
                            buildResult = null
                        }) { Text("Done") }
                    } else {
                        Button(
                            enabled = !buildingProject &&
                                linuxRuntimeManager.state.value.installed &&
                                linuxRuntimeManager.androidSdkState.value.installed,
                            onClick = {
                                buildingProject = true
                                buildResult = null
                                coroutineScope.launch {
                                    val result = linuxRuntimeManager.buildProject(target)
                                    buildResult = result
                                    if (result.success) projectsRefreshToken += 1
                                    buildingProject = false
                                }
                            }
                        ) {
                            Text(if (buildingProject) "Building…" else if (buildResult == null) "Build debug APK" else "Retry build")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !buildingProject,
                        onClick = {
                            projectToBuild = null
                            buildResult = null
                        }
                    ) { Text("Close") }
                }
            )
        }

        apkToInstall?.let { target ->
            AlertDialog(
                onDismissRequest = {
                    if (!installingApk) {
                        apkToInstall = null
                        installResult = null
                        installError = null
                    }
                },
                title = { Text(if (installResult == null) "Install ${target.name}?" else "APK installation") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (installResult == null && installError == null) {
                            Text(
                                "FxForge will send this APK to Android's package manager through the Shizuku service. " +
                                    "Only install APKs you trust. Replacing an app may update it while preserving its data."
                            )
                        }
                        if (installingApk) {
                            CircularProgressIndicator()
                            Text("Installing through Shizuku…")
                        }
                        installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        installResult?.let { result ->
                            Text(
                                if (result.success) "Android's package manager reported success."
                                else result.error ?: "Android's package manager reported failure.",
                                color = if (result.success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            if (result.success) {
                                result.error?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (result.output.isNotBlank()) {
                                Card {
                                    Text(
                                        result.output,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (installResult == null) {
                        Button(
                            enabled = !installingApk,
                            onClick = {
                                val uri = target.uri.toUri()
                                installingApk = true
                                installError = null
                                coroutineScope.launch {
                                    try {
                                        val descriptor = withContext(Dispatchers.IO) {
                                            context.contentResolver.openFileDescriptor(uri, "r")
                                                ?: throw FileNotFoundException("Android could not open this APK")
                                        }
                                        installResult = descriptor.use {
                                            userServiceController.installApk(it)
                                        }
                                    } catch (exception: IOException) {
                                        installError = exception.message ?: "Could not read the selected APK"
                                    } catch (exception: SecurityException) {
                                        installError = "Android denied access to the APK: ${exception.message ?: "permission denied"}"
                                    } finally {
                                        installingApk = false
                                    }
                                }
                            }
                        ) {
                            Text(if (installError == null) "Install" else "Retry")
                        }
                    } else {
                        TextButton(onClick = {
                            apkToInstall = null
                            installResult = null
                            installError = null
                        }) {
                            Text("Close")
                        }
                    }
                },
                dismissButton = {
                    if (installResult == null) {
                        TextButton(
                            enabled = !installingApk,
                            onClick = {
                                apkToInstall = null
                                installError = null
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    repository: ShizukuStatusRepository,
    linuxRuntimeManager: LinuxRuntimeManager,
    androidMcpBridge: AndroidMcpBridge,
    padding: PaddingValues
) {
    val status by repository.status.collectAsState()
    val linuxState by linuxRuntimeManager.state.collectAsState()
    val sdkState by linuxRuntimeManager.androidSdkState.collectAsState()
    val openCodeState by linuxRuntimeManager.openCodeState.collectAsState()
    val mcpState by androidMcpBridge.state.collectAsState()
    val terminalState by linuxRuntimeManager.terminalState.collectAsState()
    val workspaceState by linuxRuntimeManager.workspaceState.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InfoCard(
                title = "FxForge workstation",
                detail = "Android project editor and scaffolder, Debian ARM64 environment, Gradle APK builds, embedded OpenCode, and Shizuku device tools."
            )
        }
        item {
            InfoCard(
                title = "Device bridge",
                detail = when {
                    !status.binderAvailable -> "Shizuku binder is not connected."
                    status.permissionGranted -> "Permission granted · " + status.privilege.label
                    else -> "Shizuku is connected; FxForge permission has not been granted."
                }
            )
        }
        item {
            InfoCard(
                "Debian Linux",
                when {
                    linuxState.installing -> linuxState.phase
                    linuxState.installed -> "Debian Bookworm ARM64 installed"
                    else -> "Not installed"
                }
            )
        }
        item {
            InfoCard(
                "Android build toolchain",
                when {
                    sdkState.installing -> sdkState.phase
                    sdkState.installed -> "Java 17 · Android API 36 · ARM64 Build Tools · Gradle 8.13"
                    else -> "Not installed"
                }
            )
        }
        item {
            InfoCard(
                "OpenCode",
                when {
                    openCodeState.running -> "Server running · embedded web interface ready"
                    openCodeState.starting -> "Starting local server…"
                    openCodeState.installing -> "Installing…"
                    openCodeState.installed -> "Installed · start it from the OpenCode tab"
                    else -> "Not installed"
                }
            )
        }
        item {
            InfoCard(
                "Android MCP tools",
                when {
                    !mcpState.running -> "Disconnected"
                    mcpState.allowDeviceControl -> "Connected · read-only and device-changing tools enabled"
                    else -> "Connected · read-only device tools enabled"
                }
            )
        }
        item {
            InfoCard(
                "Linux shell",
                if (terminalState.running) "Persistent Debian Bash session is running"
                else "Stopped · " + if (linuxState.installed) "ready to start" else "install Debian first"
            )
        }
        item {
            InfoCard(
                "Active project workspace",
                workspaceState.name ?: "No SAF project folder selected"
            )
        }
        item {
            Text(
                "The Linux shell is persistent but pipe-based rather than a kernel PTY. Full-screen terminal applications are not supported yet.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun OpenCodeScreen(
    runtimeManager: LinuxRuntimeManager,
    androidMcpBridge: AndroidMcpBridge,
    padding: PaddingValues
) {
    val state by runtimeManager.openCodeState.collectAsState()
    val linuxState by runtimeManager.state.collectAsState()
    val mcpState by androidMcpBridge.state.collectAsState()
    val workspaceState by runtimeManager.workspaceState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val root = DocumentFile.fromTreeUri(context, uri)
                if (root != null) {
                    scope.launch { runtimeManager.syncWorkspaceFromSaf(root) }
                }
            } catch (exception: SecurityException) {
                // The workspace state reports the access failure after the next sync attempt.
            }
        }
    }
    var allowDeviceControl by remember { mutableStateOf(false) }
    var mcpError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    if (state.running) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("OpenCode · localhost:4096", fontWeight = FontWeight.SemiBold)
                    mcpError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        (workspaceState.name ?: "No project workspace") + " · " +
                            if (mcpState.running) {
                            if (mcpState.allowDeviceControl) "Android tools · full device control enabled"
                            else "Android tools · read-only mode"
                        } else "Android tools disconnected",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(
                        enabled = workspaceState.selected && !workspaceState.syncing,
                        onClick = { scope.launch { runtimeManager.syncWorkspaceToSaf() } }
                    ) {
                        Text(if (workspaceState.syncing) "Syncing…" else "Sync")
                    }
                    TextButton(onClick = {
                        androidMcpBridge.stop()
                        scope.launch { runtimeManager.stopOpenCode() }
                    }) {
                        Text("Stop")
                    }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedHttpAuthRequest(
                                view: WebView?,
                                handler: HttpAuthHandler?,
                                host: String?,
                                realm: String?
                            ) {
                                val credentials = runtimeManager.openCodeCredentials()
                                if (host == "localhost" && credentials != null) {
                                    handler?.proceed(credentials.first, credentials.second)
                                } else {
                                    handler?.cancel()
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return request?.url?.host != "localhost"
                            }
                        }
                        webView = this
                        loadUrl(runtimeManager.openCodeUrl)
                    }
                },
                update = { view ->
                    if (view.url != runtimeManager.openCodeUrl) {
                        view.loadUrl(runtimeManager.openCodeUrl)
                    }
                }
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("OpenCode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("OpenCode runs as a Linux ARM64 process inside Debian. Its project files live in FxForge's private workspace and can be synchronized with a SAF folder.")
            InfoCard(
                "OpenCode status",
                when {
                    state.running -> "Server running"
                    state.starting -> "Starting local server…"
                    state.installing -> "Installing OpenCode v2…"
                    state.installed -> "Installed"
                    else -> "Not installed"
                }
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            mcpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            InfoCard(
                "Project workspace",
                workspaceState.name ?: "No folder selected. OpenCode will start in an empty private workspace."
            )
            workspaceState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !workspaceState.syncing && !state.starting,
                    onClick = { workspacePicker.launch(null) }
                ) {
                    Text(if (workspaceState.selected) "Change workspace" else "Choose project folder")
                }
                Button(
                    enabled = workspaceState.selected && !workspaceState.syncing,
                    onClick = { scope.launch { runtimeManager.syncWorkspaceToSaf() } }
                ) {
                    Text(if (workspaceState.syncing) "Syncing…" else "Sync changes")
                }
            }
            if (workspaceState.syncing) CircularProgressIndicator()
            if (!state.installed) {
                Button(
                    enabled = !state.installing && linuxState.installed,
                    onClick = {
                        scope.launch { runtimeManager.installOpenCode() }
                    }
                ) {
                    Text(if (state.installing) "Installing…" else "Install OpenCode")
                }
                if (!linuxState.installed) {
                    Text("Install Debian first from Environment.")
                }
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowDeviceControl,
                        onCheckedChange = { allowDeviceControl = it }
                    )
                    Text("Allow shell commands, APK installation, and app launching through Shizuku")
                }
                Text("Connect the Shizuku device service from Device before using Android tools. Read-only device facts and logcat are available when control is off.")
                Button(
                    enabled = !state.starting && linuxState.installed,
                    onClick = {
                        scope.launch {
                            mcpError = null
                            try {
                                val bridgeInfo = withContext(Dispatchers.IO) {
                                    androidMcpBridge.start(allowDeviceControl)
                                }
                                runtimeManager.startOpenCode()
                                if (runtimeManager.openCodeState.value.running) {
                                    mcpError = runtimeManager.registerAndroidMcp(bridgeInfo)
                                } else {
                                    androidMcpBridge.stop()
                                }
                            } catch (exception: Exception) {
                                androidMcpBridge.stop()
                                mcpError = exception.message ?: "Could not start Android MCP tools."
                            }
                        }
                    }
                ) {
                    Text(if (state.starting) "Starting…" else "Start OpenCode with Android tools")
                }
            }
            if (state.installing || state.starting) CircularProgressIndicator()
            state.output?.let { output ->
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            output,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalScreen(
    controller: ShizukuUserServiceController,
    runtimeManager: LinuxRuntimeManager,
    padding: PaddingValues
) {
    var linuxMode by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { linuxMode = false }) { Text("Android shell") }
            Button(onClick = { linuxMode = true }) { Text("Debian Linux") }
        }
        if (linuxMode) {
            LinuxTerminalPanel(runtimeManager, Modifier.weight(1f))
        } else {
            AndroidTerminalPanel(controller, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AndroidTerminalPanel(
    controller: ShizukuUserServiceController,
    modifier: Modifier = Modifier
) {
    val serviceStatus by controller.status.collectAsState()
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("id") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ShizukuCommandResult?>(null) }
    var history by remember { mutableStateOf<List<Pair<String, ShizukuCommandResult>>>(emptyList()) }
    val reportedUid = serviceStatus.deviceFacts?.uid?.toString() ?: "not queried"

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Android shell", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Commands run as the UID reported by Shizuku. Each command starts a fresh shell process; commands are sent only when you press Run."
        )
        InfoCard(
            "Device service",
            when {
                serviceStatus.connected -> "Connected · UID $reportedUid"
                serviceStatus.connecting -> "Connecting…"
                else -> "Not connected. Open Device and connect the Shizuku device service."
            }
        )
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Command") },
            minLines = 2,
            maxLines = 5,
            enabled = !running,
            placeholder = { Text("uname -a") }
        )
        Button(
            enabled = serviceStatus.connected && !running && command.isNotBlank(),
            onClick = {
                val submitted = command.trim()
                running = true
                scope.launch {
                    val response = controller.executeCommand(submitted, 30)
                    result = response
                    history = (history + (submitted to response)).takeLast(30)
                    running = false
                }
            }
        ) {
            Text(if (running) "Running…" else "Run command")
        }
        if (running) CircularProgressIndicator()
        result?.let { response ->
            InfoCard(
                "Exit status",
                response.error ?: response.exitCode?.toString() ?: "No exit code returned"
            )
            if (response.truncated) Text("Output reached the 64 KiB limit and was truncated.")
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        response.output.ifBlank { response.error ?: "(no output)" },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Text("Recent commands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        history.asReversed().forEach { (input, response) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$ $input", fontWeight = FontWeight.SemiBold)
                    val exitLabel = response.error ?: "exit " + (response.exitCode?.toString() ?: "unknown")
                    Text(exitLabel, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { command = input }) { Text("Run again") }
                }
            }
        }
    }
}

@Composable
private fun LinuxTerminalPanel(
    runtimeManager: LinuxRuntimeManager,
    modifier: Modifier = Modifier
) {
    val state by runtimeManager.terminalState.collectAsState()
    val linuxState by runtimeManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("pwd && uname -a") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LinuxCommandResult?>(null) }
    var history by remember { mutableStateOf<List<Pair<String, LinuxCommandResult>>>(emptyList()) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Debian Linux shell", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("A persistent Bash session in the Debian ARM64 workspace. It preserves cd and environment changes between commands. It uses pipes rather than a kernel PTY, so full-screen interactive programs are not supported.")
        InfoCard(
            "Linux session",
            when {
                state.running -> "Running · /workspace"
                state.starting -> "Starting PRoot shell…"
                else -> "Stopped"
            }
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.running) {
            Button(
                enabled = linuxState.installed && !state.starting,
                onClick = {
                    scope.launch { runtimeManager.startTerminalSession() }
                }
            ) {
                Text(if (state.starting) "Starting…" else "Start Linux shell")
            }
            if (!linuxState.installed) Text("Install Debian from Environment first.")
        } else {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bash command") },
                minLines = 2,
                maxLines = 4,
                enabled = !running,
                placeholder = { Text("cd app && ls") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && command.isNotBlank(),
                    onClick = {
                        val submitted = command.trim()
                        running = true
                        scope.launch {
                            val response = runtimeManager.executeTerminalCommand(submitted)
                            result = response
                            history = (history + (submitted to response)).takeLast(30)
                            running = false
                        }
                    }
                ) {
                    Text(if (running) "Running…" else "Run")
                }
                TextButton(
                    enabled = !running,
                    onClick = { scope.launch { runtimeManager.stopTerminalSession() } }
                ) {
                    Text("Close session")
                }
            }
            if (running) CircularProgressIndicator()
            result?.let { response ->
                InfoCard(
                    "Exit status",
                    response.error ?: response.exitCode?.toString() ?: "No exit code returned"
                )
                if (response.truncated) Text("Output reached the 128 KiB limit and was truncated.")
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            response.output.ifBlank { response.error ?: "(no output)" },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Text("Recent Linux commands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            history.asReversed().forEach { (input, response) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$ $input", fontWeight = FontWeight.SemiBold)
                        Text(
                            response.error ?: "exit " + (response.exitCode?.toString() ?: "unknown"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { command = input }) { Text("Run again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectsScreen(
    padding: PaddingValues,
    refreshToken: Int,
    onOpenFile: (ProjectFile) -> Unit,
    onInstallApk: (ProjectFile) -> Unit,
    onBuildProject: (DocumentFile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCreateProject by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("MyAndroidApp") }
    var packageName by remember { mutableStateOf("com.example.myandroidapp") }
    var creatingProject by remember { mutableStateOf(false) }
    var createProjectError by remember { mutableStateOf<String?>(null) }
    val preferences = remember {
        context.getSharedPreferences("project_selection", Context.MODE_PRIVATE)
    }
    var selectedUri by remember { mutableStateOf(preferences.getString("tree_uri", null)) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<ProjectEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var currentDirectoryName by remember { mutableStateOf<String?>(null) }
    val directoryStack = remember { mutableStateListOf<DocumentFile>() }

    LaunchedEffect(selectedUri) {
        directoryStack.clear()
        val uri = selectedUri?.toUri()
        if (uri != null) {
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root == null || !root.canRead()) {
                error = "The saved folder is unavailable. Re-select it to restore access."
            } else {
                directoryStack.add(root)
                error = null
            }
        } else {
            entries = emptyList()
        }
    }

    val currentDirectory = directoryStack.lastOrNull()
    LaunchedEffect(currentDirectory, refreshToken) {
        if (currentDirectory == null) {
            entries = emptyList()
            currentDirectoryName = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val listing = withContext(Dispatchers.IO) {
                if (!currentDirectory.canRead()) {
                    throw SecurityException("Folder read access is no longer granted")
                }
                val items = currentDirectory.listFiles().map { document ->
                    ProjectEntry(
                        document = document,
                        uri = document.uri.toString(),
                        name = document.name ?: "Unnamed item",
                        isDirectory = document.isDirectory,
                        canRead = document.canRead(),
                        canWrite = document.canWrite(),
                        contentType = document.type,
                        sizeBytes = if (document.isDirectory) 0L else document.length()
                    )
                }.sortedWith(
                    compareBy<ProjectEntry>({ !it.isDirectory }, { it.name.lowercase() })
                )
                currentDirectory.name to items
            }
            currentDirectoryName = listing.first
            entries = listing.second
        } catch (exception: SecurityException) {
            entries = emptyList()
            currentDirectoryName = null
            error = "Cannot read this folder: ${exception.message ?: "access denied"}"
        } finally {
            loading = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                selectedUri = uri.toString()
                preferences.edit { putString("tree_uri", selectedUri) }
                error = null
            } catch (exception: SecurityException) {
                error = "Android did not grant persistent access: ${exception.message ?: "permission denied"}"
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Project folder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Browse a folder through Android's Storage Access Framework. Access is limited to the folder you selected.")
        }
        item {
            if (selectedUri == null) {
                Text("No project folder selected.")
            } else {
                InfoCard(
                    title = "Selected folder",
                    detail = currentDirectoryName ?: selectedUri.orEmpty()
                )
            }
            Button(onClick = { folderPicker.launch(null) }) {
                Text(if (selectedUri == null) "Choose project folder" else "Change project folder")
            }
            if (currentDirectory?.canWrite() == true) {
                Button(onClick = {
                    createProjectError = null
                    showCreateProject = true
                }) {
                    Text("Create Android project")
                }
            }
        }
        if (directoryStack.size > 1) {
            item {
                TextButton(onClick = { directoryStack.removeAt(directoryStack.lastIndex) }) {
                    Text("Up one folder")
                }
            }
        }
        if (currentDirectory?.let {
                it.findFile("settings.gradle.kts")?.isFile == true ||
                    it.findFile("settings.gradle")?.isFile == true
            } == true) {
            item {
                Button(onClick = {
                    currentDirectory?.let(onBuildProject)
                }) {
                    Text("Build debug APK")
                }
            }
        }
        if (loading) item { Text("Loading folder…") }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (!loading && error == null && currentDirectory != null && entries.isEmpty()) {
            item { Text("This folder is empty.") }
        }
        items(entries, key = { it.uri }) { entry ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (entry.isDirectory) Modifier.clickable { directoryStack.add(entry.document) }
                        else if (entry.canRead && !entry.isApk) Modifier.clickable {
                            onOpenFile(ProjectFile(entry.uri, entry.name, entry.canWrite))
                        }
                        else Modifier
                    )
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${if (entry.isDirectory) "Folder" else "File"} · ${entry.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!entry.isDirectory) {
                        Text(
                            listOfNotNull(entry.contentType, entry.sizeBytes.takeIf { it > 0 }?.let(::formatBytes))
                                .joinToString(" · ")
                                .ifEmpty { "File details unavailable" },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (entry.canRead && !entry.canWrite) {
                            Text("Read-only", style = MaterialTheme.typography.labelSmall)
                        } else if (!entry.canRead) {
                            Text("Cannot read this file", style = MaterialTheme.typography.labelSmall)
                        }
                        if (entry.isApk) {
                            Button(
                                enabled = entry.canRead,
                                onClick = {
                                    onInstallApk(ProjectFile(entry.uri, entry.name, entry.canWrite))
                                }
                            ) {
                                Text("Install APK through Shizuku")
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Text files up to 2 MiB can be edited. APK installation requires an explicitly authorized Shizuku service. Linux synchronization and Git operations are not implemented yet.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (showCreateProject) {
        val validationError = ProjectScaffolder.validate(projectName.trim(), packageName.trim())
        AlertDialog(
            onDismissRequest = { if (!creatingProject) showCreateProject = false },
            title = { Text("New Android project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text("Project name") },
                        singleLine = true,
                        enabled = !creatingProject
                    )
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("Application ID") },
                        singleLine = true,
                        enabled = !creatingProject
                    )
                    Text(
                        "Creates a Kotlin + Jetpack Compose project with Gradle Kotlin DSL and Android SDK 36 files in the selected folder."
                    )
                    validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    createProjectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (creatingProject) CircularProgressIndicator()
                }
            },
            confirmButton = {
                Button(
                    enabled = !creatingProject && validationError == null && currentDirectory?.canWrite() == true,
                    onClick = {
                        val parent = currentDirectory ?: return@Button
                        creatingProject = true
                        createProjectError = null
                        scope.launch {
                            try {
                                val created = withContext(Dispatchers.IO) {
                                    ProjectScaffolder.create(
                                        context,
                                        parent,
                                        projectName.trim(),
                                        packageName.trim()
                                    )
                                }
                                directoryStack.add(created)
                                showCreateProject = false
                                projectName = "MyAndroidApp"
                                packageName = "com.example.myandroidapp"
                            } catch (exception: Exception) {
                                createProjectError = exception.message ?: "Could not create the Android project."
                            } finally {
                                creatingProject = false
                            }
                        }
                    }
                ) {
                    Text(if (creatingProject) "Creating…" else "Create project")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !creatingProject,
                    onClick = { showCreateProject = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

}

@Composable
private fun DeviceScreen(
    repository: ShizukuStatusRepository,
    userServiceController: ShizukuUserServiceController,
    padding: PaddingValues
) {
    val context = LocalContext.current
    val status by repository.status.collectAsState()
    val userServiceStatus by userServiceController.status.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var requestError by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Shizuku connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        InfoCard("Binder", if (status.binderAvailable) "Connected" else "Not connected")
        InfoCard("FxForge permission", if (status.permissionGranted) "Granted" else "Not granted")
        InfoCard(
            "Reported privilege",
            when {
                !status.binderAvailable -> "Unavailable"
                !status.permissionGranted -> "Grant permission to read the Shizuku UID"
                else -> "${status.privilege.label} (UID ${status.uid})"
            }
        )
        if (status.binderAvailable && !status.permissionGranted) {
            Button(onClick = {
                try {
                    repository.requestPermission()
                    requestError = null
                } catch (exception: IllegalStateException) {
                    repository.refresh()
                    requestError = "Shizuku disconnected before the request: ${exception.message ?: "binder unavailable"}"
                }
            }) {
                Text("Request Shizuku permission")
            }
        }
        if (status.permissionGranted) {
            Button(onClick = {
                if (userServiceStatus.connected || userServiceStatus.connecting) {
                    userServiceController.disconnect()
                } else {
                    userServiceController.connect()
                }
            }) {
                Text(
                    when {
                        userServiceStatus.connected -> "Stop device service"
                        userServiceStatus.connecting -> "Cancel device service"
                        else -> "Connect Shizuku device service"
                    }
                )
            }
        }
        if (userServiceStatus.connected) {
            Button(onClick = {
                coroutineScope.launch { userServiceController.readDeviceInfo() }
            }) {
                Text("Read device information through Shizuku")
            }
            Button(
                enabled = !userServiceStatus.logcatLoading,
                onClick = {
                    coroutineScope.launch { userServiceController.readLogcat() }
                }
            ) {
                Text(if (userServiceStatus.logcatLoading) "Reading logcat…" else "Capture last 200 logcat lines")
            }
        }
        userServiceStatus.deviceFacts?.let { facts ->
            InfoCard(
                "UserService-reported UID",
                "${facts.uid} · ${ShizukuPrivilege.fromUid(facts.uid).label}"
            )
            InfoCard(
                "Device information from UserService",
                "${facts.manufacturer} ${facts.model} · Android ${facts.androidRelease} (API ${facts.apiLevel})\n" +
                    "ABIs: ${facts.supportedAbis.joinToString()}\nBuild: ${facts.fingerprint}"
            )
        }
        userServiceStatus.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        userServiceStatus.logcatError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        userServiceStatus.logcatOutput?.let { output ->
            InfoCard(
                "Device logcat",
                if (userServiceStatus.logcatTruncated) {
                    "Output reached the 64 KiB text limit and was truncated."
                } else {
                    "Captured on request. Logs can contain sensitive device data."
                }
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        output.ifEmpty { "No log output was returned." },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/guide/setup/".toUri())
            )
        }) {
            Text("Shizuku setup guide")
        }
        requestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "ADB-started Shizuku normally uses UID 2000, not root. The optional UserService runs under Shizuku's actual UID. Its device-facts and bounded logcat actions are explicit; it does not provide arbitrary shell access.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun EnvironmentScreen(padding: PaddingValues, linuxRuntimeManager: LinuxRuntimeManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probingProot by remember { mutableStateOf(false) }
    var prootProbe by remember { mutableStateOf<ProotProbeResult?>(null) }
    val linuxState by linuxRuntimeManager.state.collectAsState()
    val sdkState by linuxRuntimeManager.androidSdkState.collectAsState()
    val openCodeState by linuxRuntimeManager.openCodeState.collectAsState()
    var showSdkLicense by remember { mutableStateOf(false) }
    var verifyingSdk by remember { mutableStateOf(false) }
    var sdkVerification by remember { mutableStateOf<LinuxCommandResult?>(null) }
    var linuxCommand by remember { mutableStateOf("uname -a && cat /etc/os-release") }
    var runningLinuxCommand by remember { mutableStateOf(false) }
    var linuxCommandResult by remember { mutableStateOf<LinuxCommandResult?>(null) }
    val items = listOf(
        "Android release" to Build.VERSION.RELEASE,
        "API level" to Build.VERSION.SDK_INT.toString(),
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Supported ABIs" to Build.SUPPORTED_ABIS.joinToString(),
        "Available processors" to Runtime.getRuntime().availableProcessors().toString(),
        "App storage allocatable" to allocatableBytes(context),
        "App private storage" to context.filesDir.absolutePath,
        "Bundled PRoot binary" to if (ProotBinaryRuntime.isBundled(context)) "ARM64 executable and loader extracted" else "Unavailable for this ABI",
        "Android SDK" to if (sdkState.installed) "API 36 · ARM64 Build Tools · Gradle 8.13" else "Not installed",
        "Linux shell" to if (linuxState.installed) "Persistent Debian Bash session · no kernel PTY" else "Not installed",
        "OpenCode" to when {
            openCodeState.running -> "Running · localhost:4096"
            openCodeState.installing -> "Installing"
            openCodeState.installed -> "Installed"
            else -> "Not installed"
        },
        "Android SDK build tools" to if (sdkState.installed) "ARM64 AAPT2, AIDL, ZIPALIGN, split-select" else "Not installed"
    )
    if (showSdkLicense) {
        AlertDialog(
            onDismissRequest = { showSdkLicense = false },
            title = { Text("Android SDK license") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "FxForge will download Google's Android command-line tools, Android SDK Platform 36, and Build Tools 36.0.0. It will accept the SDK licenses only after your confirmation."
                    )
                    Text("The ARM64 native build tools and Gradle 8.13 will be checksum-verified before use. Keep at least 4 GiB free.")
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://developer.android.com/studio/terms".toUri())
                        )
                    }) {
                        Text("Read Android SDK terms")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showSdkLicense = false
                    scope.launch { linuxRuntimeManager.installAndroidSdk() }
                }) {
                    Text("I agree and install")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSdkLicense = false }) { Text("Cancel") }
            }
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Device diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Values below are queried from the current Android device; missing runtime components are explicitly marked.")
        }
        items(items) { (name, value) -> InfoCard(name, value) }
        item {
            Text("Debian Linux environment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            InfoCard(
                "Linux root filesystem",
                when {
                    linuxState.installed -> "Debian Bookworm ARM64 is installed in FxForge private storage."
                    linuxState.installing -> linuxState.phase
                    else -> "Not installed. The compressed root filesystem is about 27 MiB; extraction needs additional free space."
                }
            )
            if (linuxState.installing) {
                CircularProgressIndicator()
                val progress = if (linuxState.totalBytes > 0) {
                    (linuxState.downloadedBytes * 100 / linuxState.totalBytes).toInt().coerceIn(0, 100)
                } else 0
                Text(linuxState.phase + " · " + progress + "%")
            }
            linuxState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (!linuxState.installed) {
                Button(
                    enabled = !linuxState.installing && linuxRuntimeManager.isProotAvailable(),
                    onClick = {
                        scope.launch { linuxRuntimeManager.installDebian() }
                    }
                ) {
                    Text(if (linuxState.installing) "Installing Debian…" else "Install Debian ARM64")
                }
            } else {
                OutlinedTextField(
                    value = linuxCommand,
                    onValueChange = { linuxCommand = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Linux command") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !runningLinuxCommand
                )
                Button(
                    enabled = !runningLinuxCommand && linuxCommand.isNotBlank(),
                    onClick = {
                        runningLinuxCommand = true
                        scope.launch {
                            linuxCommandResult = linuxRuntimeManager.runCommand(linuxCommand)
                            runningLinuxCommand = false
                        }
                    }
                ) {
                    Text(if (runningLinuxCommand) "Running in Debian…" else "Run in Debian")
                }
                if (runningLinuxCommand) CircularProgressIndicator()
                linuxCommandResult?.let { result ->
                    InfoCard(
                        "Linux command result",
                        result.error ?: "Exit code: " + (result.exitCode?.toString() ?: "unknown")
                    )
                    if (result.truncated) {
                        Text("Linux output reached the 64 KiB limit.")
                    }
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                result.output.ifBlank { result.error ?: "(no output)" },
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        item {
            Text("Android app build toolchain", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            InfoCard(
                "SDK status",
                when {
                    sdkState.installed -> "Android SDK 36, ARM64 Build Tools 36.0.0, Java 17, and Gradle 8.13 are installed."
                    sdkState.installing -> sdkState.phase
                    else -> "Not installed. The first setup downloads Android command-line tools and SDK packages; allow several gigabytes of free storage."
                }
            )
            sdkState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (sdkState.installing) {
                CircularProgressIndicator()
                Text(sdkState.phase)
            } else if (!sdkState.installed) {
                Button(
                    enabled = linuxState.installed,
                    onClick = { showSdkLicense = true }
                ) {
                    Text("Install Android SDK and ARM64 build tools")
                }
                if (!linuxState.installed) {
                    Text("Install Debian first.")
                }
            }
            if (sdkState.installed) {
                Button(
                    enabled = !verifyingSdk,
                    onClick = {
                        verifyingSdk = true
                        scope.launch {
                            sdkVerification = linuxRuntimeManager.verifyAndroidToolchain()
                            verifyingSdk = false
                        }
                    }
                ) {
                    Text(if (verifyingSdk) "Verifying…" else "Verify ARM64 build tools")
                }
                if (verifyingSdk) CircularProgressIndicator()
                sdkVerification?.let { result ->
                    InfoCard(
                        "Toolchain verification",
                        result.error ?: "Exit code: " + (result.exitCode?.toString() ?: "unknown")
                    )
                    if (result.output.isNotBlank()) {
                        Card(Modifier.fillMaxWidth()) {
                            SelectionContainer {
                                Text(
                                    result.output,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            sdkState.output?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            Button(
                enabled = !probingProot && ProotBinaryRuntime.isBundled(context),
                onClick = {
                    probingProot = true
                    scope.launch {
                        prootProbe = withContext(Dispatchers.IO) {
                            ProotBinaryRuntime.probe(context)
                        }
                        probingProot = false
                    }
                }
            ) {
                Text(if (probingProot) "Checking PRoot…" else "Verify PRoot executable")
            }
            if (probingProot) CircularProgressIndicator()
            prootProbe?.let { result ->
                InfoCard(
                    "PRoot smoke test",
                    if (result.available) result.output.ifBlank { "PRoot executed successfully." }
                    else result.error ?: result.output
                )
                if (result.output.isNotBlank() && result.error != null) {
                    Text(result.output, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}

private fun allocatableBytes(context: android.content.Context): String {
    return try {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val storageUuid = storageManager.getUuidForPath(context.filesDir)
        formatBytes(storageManager.getAllocatableBytes(storageUuid))
    } catch (exception: IOException) {
        "Unavailable: ${exception.message ?: "storage query failed"}"
    }
}
