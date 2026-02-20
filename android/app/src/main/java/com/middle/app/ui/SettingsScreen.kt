package com.middle.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.middle.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDrawer: () -> Unit,
) {
    val apiKey by viewModel.apiKey.collectAsState()
    val backgroundSync by viewModel.backgroundSyncEnabled.collectAsState()
    val transcription by viewModel.transcriptionEnabled.collectAsState()
    val webhookEnabled by viewModel.webhookEnabled.collectAsState()
    val webhookUrl by viewModel.webhookUrl.collectAsState()
    val webhookBodyTemplate by viewModel.webhookBodyTemplate.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("OpenAI API key", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { viewModel.setApiKey(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text("sk-...") },
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background sync", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Automatically sync when pendant is nearby",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = backgroundSync,
                    onCheckedChange = { viewModel.setBackgroundSync(it) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automatic transcription", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Transcribe recordings after sync",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = transcription,
                    onCheckedChange = { viewModel.setTranscription(it) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Webhook", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "POST transcripts to a URL",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = webhookEnabled,
                    onCheckedChange = { viewModel.setWebhookEnabled(it) },
                )
            }

            if (webhookEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = webhookUrl,
                    onValueChange = { viewModel.setWebhookUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://example.com/webhook") },
                    label = { Text("URL") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = webhookBodyTemplate,
                    onValueChange = { viewModel.setWebhookBodyTemplate(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    placeholder = { Text("{\"phrase\": \"\$transcript\"}") },
                    label = { Text("Body template") },
                    supportingText = { Text("\$transcript is replaced with the transcription text") },
                )
            }
        }
    }
}
