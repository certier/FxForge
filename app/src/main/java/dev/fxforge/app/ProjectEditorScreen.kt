package dev.fxforge.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun ProjectEditorScreen(
    file: ProjectFile,
    padding: PaddingValues,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var initialContent by remember(file.uri) { mutableStateOf<String?>(null) }
    var loadError by remember(file.uri) { mutableStateOf<String?>(null) }
    var loading by remember(file.uri) { mutableStateOf(true) }

    LaunchedEffect(file.uri) {
        loading = true
        loadError = null
        try {
            initialContent = withContext(Dispatchers.IO) {
                ProjectTextContent.read(context.contentResolver, file.uri.toUri())
            }
        } catch (exception: IOException) {
            loadError = exception.message ?: "The file could not be read"
        } catch (exception: SecurityException) {
            loadError = "Android denied access to this file: ${exception.message ?: "permission denied"}"
        } finally {
            loading = false
        }
    }

    val content = initialContent
    when {
        content != null -> ProjectTextEditor(file, content, padding, onClose)
        else -> {
            BackHandler(onBack = onClose)
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onClose) { Text("Back to project") }
                if (loading) {
                    CircularProgressIndicator()
                    Text("Opening ${file.name}…")
                } else {
                    Text(
                        loadError ?: "The file could not be opened",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectTextEditor(
    file: ProjectFile,
    initialContent: String,
    padding: PaddingValues,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dirty = remember(file.uri) { mutableStateOf(false) }
    var saving by remember(file.uri) { mutableStateOf(false) }
    var saveError by remember(file.uri) { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember(file.uri) { mutableStateOf(false) }
    val editor = remember(file.uri) {
        CodeEditor(context).apply {
            setText(initialContent)
            if (file.name.endsWith(".java", ignoreCase = true)) {
                setEditorLanguage(JavaLanguage())
            } else {
                setEditorLanguage(EmptyLanguage())
            }
        }
    }
    val eventSubscription = remember(editor) {
        val receiver = EventReceiver<ContentChangeEvent> { _, _ ->
            dirty.value = true
        }
        EditorEventSubscription(
            receiver,
            editor.subscribeEvent(ContentChangeEvent::class.java, receiver)
        )
    }

    DisposableEffect(editor, eventSubscription) {
        onDispose {
            eventSubscription.receipt.unsubscribe()
            editor.release()
        }
    }

    val requestClose = {
        if (!saving) {
            if (dirty.value) showDiscardDialog = true else onClose()
        }
    }
    BackHandler(onBack = requestClose)

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Changes to ${file.name} have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = requestClose, enabled = !saving) { Text("‹  Files") }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        !file.canWrite -> MaterialTheme.colorScheme.surfaceContainerHighest
                        saving -> MaterialTheme.colorScheme.secondaryContainer
                        dirty.value -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        when {
                            !file.canWrite -> "READ ONLY"
                            saving -> "SAVING"
                            dirty.value -> "UNSAVED CHANGES"
                            else -> "SAVED"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Button(
                enabled = file.canWrite && dirty.value && !saving,
                onClick = {
                    val contentToSave = editor.text.toString()
                    coroutineScope.launch {
                        saving = true
                        saveError = null
                        try {
                            withContext(Dispatchers.IO) {
                                ProjectTextContent.write(
                                    context.contentResolver,
                                    file.uri.toUri(),
                                    contentToSave
                                )
                            }
                            dirty.value = editor.text.toString() != contentToSave
                        } catch (exception: IOException) {
                            saveError = exception.message ?: "The file could not be saved"
                        } catch (exception: SecurityException) {
                            saveError = "Android denied write access: ${exception.message ?: "permission denied"}"
                        } finally {
                            saving = false
                        }
                    }
                }
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        }
        AnimatedVisibility(visible = saveError != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    saveError.orEmpty(),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        AndroidView(
            factory = { editor },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

private data class EditorEventSubscription(
    val receiver: EventReceiver<ContentChangeEvent>,
    val receipt: SubscriptionReceipt<ContentChangeEvent>
)
