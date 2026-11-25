package dev.fxforge.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.documentfile.provider.DocumentFile
import dev.fxforge.shizuku.ShizukuPrivilege
import dev.fxforge.shizuku.ShizukuStatusRepository
import dev.fxforge.shizuku.ShizukuUserServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                        fxForgeApplication.shizukuUserServiceController
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
    ENVIRONMENT("Environment", "E")
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
)

@Composable
private fun FxForgeApp(
    repository: ShizukuStatusRepository,
    userServiceController: ShizukuUserServiceController
) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    var openFile by remember { mutableStateOf<ProjectFile?>(null) }
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
                    ProjectsScreen(padding, onOpenFile = { openFile = it })
                } else {
                    ProjectEditorScreen(
                        file = file,
                        padding = padding,
                        onClose = { openFile = null }
                    )
                }
            }
            Destination.DEVICE -> DeviceScreen(repository, userServiceController, padding)
            Destination.ENVIRONMENT -> EnvironmentScreen(padding)
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
                detail = "Native Android UI, a SAF project browser and text editor, and live Shizuku status."
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
                "Linux userland and interactive PTY terminal",
                "OpenCode runtime and agent sessions",
                "Android SDK, ARM64 build tools, and APK builds",
                "Git operations and Android MCP tools"
            )
        ) { feature ->
            Text("•  $feature", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProjectsScreen(padding: PaddingValues, onOpenFile: (ProjectFile) -> Unit) {
    val context = LocalContext.current
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
                        else if (entry.canRead) Modifier.clickable {
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
                    }
                }
            }
        }
        item {
            Text(
                "Text files up to 2 MiB can be edited. Linux synchronization and Git operations are not implemented yet.",
                style = MaterialTheme.typography.bodySmall
            )
        }
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
private fun EnvironmentScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val items = listOf(
        "Android release" to Build.VERSION.RELEASE,
        "API level" to Build.VERSION.SDK_INT.toString(),
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Supported ABIs" to Build.SUPPORTED_ABIS.joinToString(),
        "Available processors" to Runtime.getRuntime().availableProcessors().toString(),
        "App storage allocatable" to allocatableBytes(context),
        "App private storage" to context.filesDir.absolutePath,
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
