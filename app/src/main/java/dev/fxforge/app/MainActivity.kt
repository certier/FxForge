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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fxforge.shizuku.ShizukuStatusRepository
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as FxForgeApplication).shizukuStatusRepository
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FxForgeApp(repository)
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

@Composable
private fun FxForgeApp(repository: ShizukuStatusRepository) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    Scaffold(
        topBar = {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                Text("FxForge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Android development workstation", style = MaterialTheme.typography.bodyMedium)
            }
        },
        bottomBar = {
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
    ) { padding ->
        when (destination) {
            Destination.HOME -> HomeScreen(repository, padding)
            Destination.PROJECTS -> ProjectsScreen(padding)
            Destination.DEVICE -> DeviceScreen(repository, padding)
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
                detail = "Native Android UI, persistent project-folder access, and live Shizuku status."
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
                "Code editor, Git operations, and Android MCP tools"
            )
        ) { feature ->
            Text("•  $feature", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProjectsScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("project_selection", Context.MODE_PRIVATE)
    }
    var selectedUri by remember { mutableStateOf(preferences.getString("tree_uri", null)) }
    var error by remember { mutableStateOf<String?>(null) }
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
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Project folder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Choose a folder through Android's Storage Access Framework. Access is limited to the folder you select.")
        if (selectedUri == null) {
            Text("No project folder selected.")
        } else {
            InfoCard(title = "Selected folder", detail = selectedUri.orEmpty())
        }
        Button(onClick = { folderPicker.launch(null) }) {
            Text(if (selectedUri == null) "Choose project folder" else "Change project folder")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "The selected folder is persisted across app restarts. Linux synchronization and file editing are not implemented yet.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DeviceScreen(repository: ShizukuStatusRepository, padding: PaddingValues) {
    val context = LocalContext.current
    val status by repository.status.collectAsState()
    var requestError by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Shizuku connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        InfoCard("Binder", if (status.binderAvailable) "Connected" else "Not connected")
        InfoCard("FxForge permission", if (status.permissionGranted) "Granted" else "Not granted")
        InfoCard(
            "Reported privilege",
            if (status.binderAvailable) "${status.privilege.label} (UID ${status.uid})" else "Unavailable"
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
        TextButton(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/guide/setup/".toUri())
            )
        }) {
            Text("Shizuku setup guide")
        }
        requestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "ADB-started Shizuku normally uses UID 2000, not root. A granted permission does not provide root access. This build reports access state but does not execute privileged commands.",
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
