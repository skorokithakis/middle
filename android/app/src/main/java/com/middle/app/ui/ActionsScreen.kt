package com.middle.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.middle.app.R
import com.middle.app.data.Action
import com.middle.app.data.ActionType
import com.middle.app.viewmodel.ActionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(
    viewModel: ActionsViewModel,
    onOpenDrawer: () -> Unit,
) {
    val actions by viewModel.actions.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // A settings import can replace the stored actions while this screen is
    // not composed, so reload on first composition and on every resume.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // The permission is granted in a system screen, so the value read when the
    // screen first composes goes stale while the user is away. Re-reading on
    // resume makes the warning disappear as soon as they come back.
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.actions_title)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.actions_open_menu),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.addAction() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.actions_add),
                        )
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
            if (!canDrawOverlays) {
                OverlayPermissionCard(
                    onGrant = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(actions, key = { it.id }) { action ->
                    ActionCard(
                        action = action,
                        onUpdate = { viewModel.updateAction(it) },
                        onDelete = { viewModel.deleteAction(action.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayPermissionCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.actions_overlay_message),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onGrant) {
                Text(stringResource(R.string.actions_overlay_grant))
            }
        }
    }
}

@Composable
private fun ActionCard(
    action: Action,
    onUpdate: (Action) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only one type exists, so the type is shown as a plain label
                // rather than a picker.
                val typeLabel = when (action.type) {
                    ActionType.ALARM -> stringResource(R.string.actions_type_alarm)
                }
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = action.enabled,
                    onCheckedChange = { onUpdate(action.copy(enabled = it)) },
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.actions_delete),
                    )
                }
            }
            OutlinedTextField(
                value = action.pattern,
                onValueChange = { onUpdate(action.copy(pattern = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.actions_pattern_label)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.actions_suppress_webhook),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = action.suppressWebhook,
                    onCheckedChange = { onUpdate(action.copy(suppressWebhook = it)) },
                )
            }
        }
    }
}
