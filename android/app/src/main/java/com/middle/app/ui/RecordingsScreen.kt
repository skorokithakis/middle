package com.middle.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.middle.app.R
import com.middle.app.ble.SyncForegroundService
import com.middle.app.data.Recording
import com.middle.app.viewmodel.RecordingsViewModel
import java.time.format.DateTimeFormatter

private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel,
    onOpenDrawer: () -> Unit,
) {
    val recordings by viewModel.recordings.collectAsState()
    val currentlyPlaying by viewModel.currentlyPlaying.collectAsState()
    val syncState by SyncForegroundService.syncState.collectAsState()
    val batteryVoltage by SyncForegroundService.batteryVoltage.collectAsState()
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // Selection mode state.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateListOf<String>() }

    // Path of the recording whose transcript is expanded.
    var expandedPath by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // Prune stale selections when recordings change.
    LaunchedEffect(recordings.size) {
        if (selectedItems.isNotEmpty()) {
            val paths = recordings.map { it.audioFile.absolutePath }.toSet()
            selectedItems.removeAll { it !in paths }
            if (selectedItems.isEmpty()) selectionMode = false
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete all recordings?") },
            text = { Text("All recordings and transcripts will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllRecordings()
                        selectionMode = false
                        selectedItems.clear()
                        showDeleteAllDialog = false
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteSelectedDialog) {
        val count = selectedItems.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete $count recording${if (count != 1) "s" else ""}?") },
            text = { Text("The selected recording${if (count != 1) "s" else ""} and transcript${if (count != 1) "s" else ""} will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = recordings.filter { it.audioFile.absolutePath in selectedItems }
                        viewModel.deleteRecordings(toDelete)
                        selectedItems.clear()
                        selectionMode = false
                        showDeleteSelectedDialog = false
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedItems.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedItems.clear()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        if (selectedItems.isNotEmpty()) {
                            // Copy transcripts for selected items.
                            TextButton(onClick = {
                                val transcripts = recordings
                                    .filter { it.audioFile.absolutePath in selectedItems && it.hasTranscript }
                                    .mapNotNull { it.transcriptText?.trim() }
                                    .filter { it.isNotEmpty() }
                                if (transcripts.isEmpty()) {
                                    Toast.makeText(context, "No transcripts to copy", Toast.LENGTH_SHORT).show()
                                } else {
                                    val combined = transcripts.joinToString("\n\n")
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Transcripts", combined))
                                    Toast.makeText(context, "Copied ${transcripts.size} transcript(s)", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("Copy")
                            }
                            // Transcribe selected items.
                            if (viewModel.transcriptionAvailable) {
                                IconButton(onClick = {
                                    val toTranscribe = recordings.filter { it.audioFile.absolutePath in selectedItems }
                                    viewModel.transcribeRecordings(toTranscribe)
                                    selectionMode = false
                                    selectedItems.clear()
                                }) {
                                    Icon(Icons.Default.Create, contentDescription = "Transcribe selected")
                                }
                            }
                            IconButton(onClick = { showDeleteSelectedDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Middle") },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    },
                    actions = {
                        if (recordings.isNotEmpty()) {
                            IconButton(onClick = { showDeleteAllDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete all recordings")
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Sync status bar.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = syncState,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "🔋 $batteryVoltage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (recordings.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No recordings yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the pendant button to record, then bring it near your phone to sync.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val listState = rememberLazyListState()

                // Scroll to top whenever the number of recordings changes.
                LaunchedEffect(recordings.size) {
                    listState.animateScrollToItem(0)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(recordings, key = { it.audioFile.absolutePath }) { recording ->
                        val path = recording.audioFile.absolutePath
                        val isSelected = path in selectedItems
                        val isExpanded = expandedPath == path

                        RecordingItem(
                            recording = recording,
                            isPlaying = currentlyPlaying == recording,
                            isExpanded = isExpanded,
                            selectionMode = selectionMode,
                            isSelected = isSelected,
                            onTap = {
                                if (selectionMode) {
                                    if (isSelected) selectedItems.remove(path)
                                    else selectedItems.add(path)
                                    if (selectedItems.isEmpty()) selectionMode = false
                                } else {
                                    expandedPath = if (isExpanded) null else path
                                }
                            },
                            onLongPress = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedItems.clear()
                                    selectedItems.add(path)
                                }
                            },
                            onTogglePlayback = { viewModel.togglePlayback(recording) },
                            onDelete = { viewModel.deleteRecording(recording) },
                            showResendWebhook = viewModel.webhookEnabled && (recording.hasTranscript || viewModel.transcriptionAvailable),
                            onResendWebhook = { viewModel.sendWebhook(recording) },
                            showTranscribe = viewModel.transcriptionAvailable,
                            onTranscribe = { viewModel.transcribeRecording(recording) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingItem(
    recording: Recording,
    isPlaying: Boolean,
    isExpanded: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePlayback: () -> Unit,
    onDelete: () -> Unit,
    showResendWebhook: Boolean,
    onResendWebhook: () -> Unit,
    showTranscribe: Boolean = false,
    onTranscribe: () -> Unit = {},
) {
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete recording?") },
            text = { Text("The recording and its transcript will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onTap() },
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.timestamp.format(DISPLAY_FORMAT),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = formatDuration(recording.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (!selectionMode) {
                    IconButton(onClick = onTogglePlayback) {
                        if (isPlaying) {
                            Icon(
                                painter = painterResource(R.drawable.ic_stop),
                                contentDescription = "Stop",
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                recording.audioFile,
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share recording"))
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share audio",
                        )
                    }

                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                        )
                    }

                    if (showTranscribe) {
                        IconButton(onClick = onTranscribe) {
                            Icon(
                                imageVector = Icons.Default.Create,
                                contentDescription = "Transcribe",
                            )
                        }
                    }

                    if (showResendWebhook) {
                        IconButton(onClick = onResendWebhook) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Resend webhook",
                            )
                        }
                    }
                }
            }

            // Transcript section.
            if (recording.hasTranscript) {
                val transcript = recording.transcriptText ?: ""

                if (!isExpanded) {
                    // Collapsed: show 3-line preview.
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Full selectable transcript text.
                        SelectionContainer {
                            Text(
                                text = transcript,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Action buttons for the transcript.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", transcript))
                                    Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                Text("Copy", style = MaterialTheme.typography.labelMedium)
                            }

                            TextButton(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, transcript)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share transcript"))
                                },
                            ) {
                                Text("Share text", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes}m ${remainingSeconds}s"
    } else {
        "${remainingSeconds}s"
    }
}
