package com.middle.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.middle.app.data.Settings
import com.middle.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDrawer: () -> Unit,
) {
    val transcriptionProvider by viewModel.transcriptionProvider.collectAsState()
    val openAiApiKey by viewModel.openAiApiKey.collectAsState()
    val elevenLabsApiKey by viewModel.elevenLabsApiKey.collectAsState()
    val backgroundSync by viewModel.backgroundSyncEnabled.collectAsState()
    val transcription by viewModel.transcriptionEnabled.collectAsState()
    val webhookEnabled by viewModel.webhookEnabled.collectAsState()
    val webhookUrl by viewModel.webhookUrl.collectAsState()
    val webhookBodyTemplate by viewModel.webhookBodyTemplate.collectAsState()
    val isPaired by viewModel.isPaired.collectAsState()
    val pairingToken by viewModel.pairingToken.collectAsState()
    var showUnpairDialog by remember { mutableStateOf(false) }

    val isOpenAiProvider = transcriptionProvider == Settings.TRANSCRIPTION_PROVIDER_OPENAI
    val apiKey = if (isOpenAiProvider) openAiApiKey else elevenLabsApiKey
    val apiKeyLabel = if (isOpenAiProvider) "OpenAI API key" else "ElevenLabs API key"
    val apiKeyPlaceholder = if (isOpenAiProvider) "sk-..." else "xi-..."
    val apiKeyHelp = if (isOpenAiProvider) {
        "Create an API key in your OpenAI account settings."
    } else {
        "Create an API key in your ElevenLabs profile settings."
    }

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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Transcription provider", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isOpenAiProvider,
                    onClick = { viewModel.setTranscriptionProvider(Settings.TRANSCRIPTION_PROVIDER_OPENAI) },
                )
                Text("OpenAI")
                Spacer(modifier = Modifier.weight(1f))
                RadioButton(
                    selected = !isOpenAiProvider,
                    onClick = { viewModel.setTranscriptionProvider(Settings.TRANSCRIPTION_PROVIDER_ELEVENLABS) },
                )
                Text("ElevenLabs")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(apiKeyLabel, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    if (isOpenAiProvider) {
                        viewModel.setOpenAiApiKey(it)
                    } else {
                        viewModel.setElevenLabsApiKey(it)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(apiKeyPlaceholder) },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = apiKeyHelp,
                style = MaterialTheme.typography.bodySmall,
            )
            if (transcription && apiKey.isBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Automatic transcription is enabled, but this provider has no API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

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

            if (isPaired) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Pairing token", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = pairingToken,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showUnpairDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Unpair pendant",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (showUnpairDialog) {
                AlertDialog(
                    onDismissRequest = { showUnpairDialog = false },
                    title = { Text("Unpair pendant?") },
                    text = { Text("You will need to re-pair with a pendant to sync recordings.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.unpairPendant()
                                showUnpairDialog = false
                            },
                        ) {
                            Text("Unpair", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUnpairDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}
