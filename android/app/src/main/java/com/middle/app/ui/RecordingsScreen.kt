package com.middle.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.middle.app.ble.SyncForegroundService
import com.middle.app.data.Recording
import com.middle.app.viewmodel.RecordingsViewModel
import java.time.format.DateTimeFormatter

private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel,
    onNavigateToSettings: () -> Unit,
) {
    val recordings by viewModel.recordings.collectAsState()
    val currentlyPlaying by viewModel.currentlyPlaying.collectAsState()
    val syncState by SyncForegroundService.syncState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Middle") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
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
                Text(
                    text = syncState,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
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
                            onTogglePlayback = { viewModel.togglePlayback(recording) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingItem(
    recording: Recording,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
) {
    val context = LocalContext.current

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

                FilledTonalButton(onClick = onTogglePlayback) {
                    Text(if (isPlaying) "Stop" else "Play")
                }

                FilledTonalButton(
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
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Share")
                }
            }

            if (recording.hasTranscript) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = recording.transcriptText ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
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
