package com.middle.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.middle.app.R
import com.middle.app.data.Action
import com.middle.app.data.ActionType
import com.middle.app.telecom.FakeCallAccount
import com.middle.app.viewmodel.ActionsViewModel
import com.middle.app.viewmodel.CalendarInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ActionsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(
    viewModel: ActionsViewModel,
    onOpenDrawer: () -> Unit,
) {
    val actions by viewModel.actions.collectAsState()
    val selectedCalendarName by viewModel.selectedCalendarName.collectAsState()
    val calendars by viewModel.calendars.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // A settings import can replace the stored actions while this screen is
    // not composed, so reload on first composition and on every resume.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // The permission is granted in a system screen, so the value read when the
    // screen first composes goes stale while the user is away. Re-reading on
    // resume makes the warning disappear as soon as they come back.
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var canUseCalendar by remember { mutableStateOf(hasCalendarPermissions(context)) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    // The id of the fake-call action the contact picker was opened for; null
    // while no pick is in flight.
    var pendingContactActionId by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                canUseCalendar = hasCalendarPermissions(context)
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The list is loaded before the picker opens so an empty dialog is only
    // shown when there really is nothing to choose.
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        canUseCalendar = results[Manifest.permission.READ_CALENDAR] == true &&
            results[Manifest.permission.WRITE_CALENDAR] == true
        if (canUseCalendar) {
            scope.launch {
                viewModel.loadCalendars()
                showCalendarPicker = true
            }
        }
    }

    // The picker result carries a temporary read grant for the one picked row,
    // so no READ_CONTACTS permission is needed. A cancelled pick has no data
    // URI and is ignored; the row query runs off the main thread because the
    // contacts provider is a separate process.
    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val actionId = pendingContactActionId
        pendingContactActionId = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val contact = withContext(Dispatchers.IO) { readContact(context, uri) }
                ?: return@launch
            val action = viewModel.actions.value.firstOrNull { it.id == actionId } ?: return@launch
            viewModel.updateAction(
                action.copy(callerName = contact.first, callerNumber = contact.second),
            )
        }
    }
    val chooseContact: (String) -> Unit = { actionId ->
        pendingContactActionId = actionId
        contactPickerLauncher.launch(
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
        )
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
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.actions_add),
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                        ) {
                            ActionType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(actionTypeLabel(type)) },
                                    onClick = {
                                        viewModel.addAction(type)
                                        showAddMenu = false
                                    },
                                )
                            }
                        }
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
            CalendarRow(
                selectedName = selectedCalendarName,
                onClick = {
                    if (canUseCalendar) {
                        scope.launch {
                            viewModel.loadCalendars()
                            showCalendarPicker = true
                        }
                    } else {
                        calendarPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR,
                            ),
                        )
                    }
                },
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(actions, key = { _, action -> action.id }) { index, action ->
                    ActionCard(
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < actions.lastIndex,
                        onUpdate = { viewModel.updateAction(it) },
                        onDelete = { viewModel.deleteAction(action.id) },
                        onMoveUp = { viewModel.moveUp(action.id) },
                        onMoveDown = { viewModel.moveDown(action.id) },
                        onChooseContact = { chooseContact(action.id) },
                    )
                }
            }
        }
    }

    if (showCalendarPicker) {
        CalendarPickerDialog(
            calendars = calendars,
            onSelect = { calendar ->
                viewModel.setCalendarId(calendar.id)
                showCalendarPicker = false
            },
            onDismiss = { showCalendarPicker = false },
        )
    }
}

private fun hasCalendarPermissions(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun actionTypeLabel(type: ActionType): String = when (type) {
    ActionType.ALARM -> stringResource(R.string.actions_type_alarm)
    ActionType.CALENDAR -> stringResource(R.string.actions_type_calendar)
    ActionType.WEBHOOK -> stringResource(R.string.actions_type_webhook)
    ActionType.FAKE_CALL -> stringResource(R.string.actions_type_fake_call)
}

/** Reads the name and number of the single row the contact picker returned. */
private fun readContact(context: Context, uri: Uri): Pair<String, String>? =
    try {
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                ) ?: ""
                val number = cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ) ?: ""
                name to number
            } else {
                null
            }
        }
    } catch (exception: Exception) {
        Log.w(TAG, "Could not read the picked contact: $exception")
        null
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
private fun CalendarRow(selectedName: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.actions_calendar_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = selectedName ?: stringResource(R.string.actions_calendar_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarPickerDialog(
    calendars: List<CalendarInfo>,
    onSelect: (CalendarInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.actions_calendar_dialog_title)) },
        text = {
            if (calendars.isEmpty()) {
                Text(stringResource(R.string.actions_calendar_empty))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    calendars.forEach { calendar ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(calendar) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = calendar.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = calendar.accountName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.actions_calendar_cancel))
            }
        },
    )
}

@Composable
private fun ActionCard(
    action: Action,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (Action) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onChooseContact: () -> Unit,
) {
    val context = LocalContext.current
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
                Text(
                    text = actionTypeLabel(action.type),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = action.enabled,
                    onCheckedChange = { onUpdate(action.copy(enabled = it)) },
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.actions_move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.actions_move_down),
                    )
                }
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
                    text = stringResource(R.string.actions_stop),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = action.stop,
                    onCheckedChange = { onUpdate(action.copy(stop = it)) },
                )
            }
            if (action.type == ActionType.WEBHOOK) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = action.webhookUrl,
                    onValueChange = { onUpdate(action.copy(webhookUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.actions_webhook_url_label)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = action.webhookBodyTemplate,
                    onValueChange = { onUpdate(action.copy(webhookBodyTemplate = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.actions_webhook_body_label)) },
                    supportingText = { Text(stringResource(R.string.actions_webhook_body_helper)) },
                )
            }
            if (action.type == ActionType.FAKE_CALL) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = action.callerName,
                    onValueChange = { onUpdate(action.copy(callerName = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.actions_fake_call_caller_name_label)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = action.callerNumber,
                    onValueChange = { onUpdate(action.copy(callerNumber = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.actions_fake_call_caller_number_label)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onChooseContact) {
                    Text(stringResource(R.string.actions_fake_call_choose_contact))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.actions_fake_call_account_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // Register first so the Middle toggle exists on the
                        // screen the intent opens.
                        FakeCallAccount.register(context)
                        FakeCallAccount.openSettings(context)
                    },
                ) {
                    Text(stringResource(R.string.actions_fake_call_account_open))
                }
            }
        }
    }
}
