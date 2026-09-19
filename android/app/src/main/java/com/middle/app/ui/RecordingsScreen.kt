package com.middle.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.middle.app.R
import com.middle.app.ble.SyncForegroundService
import com.middle.app.data.Recording
import com.middle.app.data.Settings
import com.middle.app.viewmodel.RecordingsViewModel
import kotlinx.coroutines.launch
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
    val pendingFilenames by viewModel.pendingFilenames.collectAsState()
    val syncState by SyncForegroundService.syncState.collectAsState()
    val batteryVoltage by SyncForegroundService.batteryVoltage.collectAsState()
    val activeDeviceType by SyncForegroundService.activeDeviceType.collectAsState()
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val recordingState by viewModel.recordingState.collectAsState()
    val elapsedSeconds =
        (recordingState as? RecordingsViewModel.RecordingState.Recording)?.elapsedSeconds ?: 0

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch { snackbarHostState.showSnackbar("Microphone permission needed") }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.saveErrors.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // A screen lock pauses the activity; stop and save what was captured rather
    // than leaving the microphone open in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.stopRecording()
    }

    val onRecordStart = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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

    Scaffold(
        topBar = {
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
        },
        floatingActionButton = {
            RecordButton(
                isRecording = recordingState is RecordingsViewModel.RecordingState.Recording,
                elapsedSeconds = elapsedSeconds,
                onStart = onRecordStart,
                onStop = { viewModel.stopRecording() },
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Sync status bar. Both the status text and the battery reading are
            // only ever written by the pendant path, so the bar would sit frozen
            // on stale pendant values while the ring is selected.
            if (activeDeviceType != Settings.DEVICE_TYPE_RING) {
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
                        text = if (activeDeviceType == Settings.DEVICE_TYPE_RING) {
                            "Record on the ring. Recordings sync when the ring is near your phone."
                        } else {
                            "Tap the pendant button to record, then bring it near your phone to sync."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val listState = rememberLazyListState()

                // Scroll to top whenever the number of recordings changes (i.e. a new one was added).
                LaunchedEffect(recordings.size) {
                    listState.animateScrollToItem(0)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(recordings, key = { it.audioFile.absolutePath }) { recording ->
                        RecordingItem(
                            recording = recording,
                            isPlaying = currentlyPlaying == recording,
                            isPending = recording.audioFile.name in pendingFilenames,
                            onTogglePlayback = { viewModel.togglePlayback(recording) },
                            onDelete = { viewModel.deleteRecording(recording) },
                            showRetry = !recording.hasTranscript || viewModel.webhookEnabled,
                            onRetry = { viewModel.retryPipeline(recording) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    elapsedSeconds: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(72.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onStart()
                            // Wait for the finger to lift. This also returns
                            // when the gesture is cancelled (sliding off), so
                            // either way the recording ends.
                            tryAwaitRelease()
                            onStop()
                        },
                    )
                },
            shape = CircleShape,
            color = if (isRecording) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (isRecording) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = if (isRecording) "Recording" else "Hold to record",
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        if (isRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatElapsed(elapsedSeconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}

@Composable
private fun RecordingItem(
    recording: Recording,
    isPlaying: Boolean,
    isPending: Boolean,
    onTogglePlayback: () -> Unit,
    onDelete: () -> Unit,
    showRetry: Boolean,
    onRetry: () -> Unit,
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
            .clickable(onClick = onTogglePlayback),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        contentDescription = "Share",
                    )
                }

                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                    )
                }

                if (showRetry) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                        )
                    }
                }
            }

            if (recording.hasTranscript) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = recording.transcriptText ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                )
            } else if (isPending) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Transcription pending",
                    style = MaterialTheme.typography.bodySmall,
                )
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
