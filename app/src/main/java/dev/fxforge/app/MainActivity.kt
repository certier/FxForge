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
                        fxForgeApplication.linuxRuntimeManager
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
    linuxRuntimeManager: LinuxRuntimeManager
) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    var openFile by remember { mutableStateOf<ProjectFile?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var apkToInstall by remember { mutableStateOf<ProjectFile?>(null) }
    var installResult by remember { mutableStateOf<ShizukuApkInstallResult?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installingApk by remember { mutableStateOf(false) }

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
                Destination.HOME -> HomeScreen(repository, padding)
                Destination.PROJECTS -> {
                    val file = openFile
                    if (file == null) {
                        ProjectsScreen(
                            padding,
                            onOpenFile = { openFile = it },
                            onInstallApk = {
                                apkToInstall = it
                                installResult = null
                                installError = null
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
                Destination.TERMINAL -> TerminalScreen(userServiceController, padding)
                Destination.OPENCODE -> OpenCodeScreen(linuxRuntimeManager, padding)
            }
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
private fun HomeScreen(repository: ShizukuStatusRepository, padding: PaddingValues) {
    val status by repository.status.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InfoCard(
                title = "Foundation build",
                detail = "Native Android UI, a SAF project browser and text editor, Shizuku diagnostics, APK installation, and an Android shell console."
            )
        }
        item {
            InfoCard(
                title = "Device bridge",
                detail = when {
                    !status.binderAvailable -> "Shizuku binder is not connected."
                    status.permissionGranted -> "Permission granted · ${status.privilege.label}"
                    else -> "Shizuku is connected; FxForge permission has not been granted."
                }
            )
        }
        item {
            Text("Not yet available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(
            listOf(
                "Persistent Debian/Ubuntu ARM64 Linux userland and PTY",
                "OpenCode runtime, model configuration, and agent sessions",
                "On-device Android SDK, ARM64-compatible build tools, and Gradle builds",
                "Git workspace integration and authenticated Android MCP bridge"
            )
        ) { feature ->
            Text("•  $feature", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun OpenCodeScreen(
    runtimeManager: LinuxRuntimeManager,
    padding: PaddingValues
) {
    val state by runtimeManager.openCodeState.collectAsState()
    val linuxState by runtimeManager.state.collectAsState()
    val scope = rememberCoroutineScope()
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
                Text("OpenCode · localhost:4096", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = {
                    scope.launch { runtimeManager.stopOpenCode() }
                }) {
                    Text("Stop")
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
            Text("OpenCode runs as a Linux ARM64 process inside the Debian environment. FxForge embeds its local web interface in this screen.")
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
                Button(
                    enabled = !state.starting,
                    onClick = {
                        scope.launch { runtimeManager.startOpenCode() }
                    }
                ) {
                    Text(if (state.starting) "Starting…" else "Start OpenCode")
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
    padding: PaddingValues
) {
    val serviceStatus by controller.status.collectAsState()
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("id") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ShizukuCommandResult?>(null) }
    var history by remember { mutableStateOf<List<Pair<String, ShizukuCommandResult>>>(emptyList()) }
    val reportedUid = serviceStatus.deviceFacts?.uid?.toString() ?: "not queried"

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Android shell", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Commands run as the UID reported by Shizuku. This is a command console, not a persistent PTY: each command starts a fresh shell process. Commands are sent only when you press Run."
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
            if (response.truncated) {
                Text("Output reached the 64 KiB limit and was truncated.")
            }
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
private fun ProjectsScreen(
    padding: PaddingValues,
    onOpenFile: (ProjectFile) -> Unit,
    onInstallApk: (ProjectFile) -> Unit
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
    LaunchedEffect(currentDirectory) {
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
        "Linux userland / PTY" to "Not installed",
        "OpenCode" to "Not installed",
        "Android SDK build tools" to "Not installed in this app"
    )
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
