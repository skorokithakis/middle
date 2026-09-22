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
import com.middle.app.data.MediaKey
// Aliased because android.provider.Settings is already imported for the overlay
// permission check.
import com.middle.app.data.Settings as AppSettings
import com.middle.app.telecom.FakeCallAccount
import com.middle.app.viewmodel.ActionsViewModel
import com.middle.app.viewmodel.CalendarInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "ActionsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(
    viewModel: ActionsViewModel,
    onOpenDrawer: () -> Unit,
) {
    val actions by viewModel.actions.collectAsState()
    val clickActions by viewModel.clickActions.collectAsState()
    val deviceType by viewModel.deviceType.collectAsState()
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
            // The picker is opened from both the action list and a ring click
            // slot, so the id selects whichever list holds it. A click slot is
            // written back through setClickAction instead of updateAction.
            val action = viewModel.actions.value.firstOrNull { it.id == actionId }
            if (action != null) {
                viewModel.updateAction(
                    action.copy(callerName = contact.first, callerNumber = contact.second),
                )
                return@launch
            }
            val clickEntry = viewModel.clickActions.value.entries
                .firstOrNull { it.value.id == actionId }
                ?: return@launch
            viewModel.setClickAction(
                clickEntry.key,
                clickEntry.value.copy(callerName = contact.first, callerNumber = contact.second),
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
                if (deviceType == AppSettings.DEVICE_TYPE_RING) {
                    item {
                        RingButtonSection(
                            clickActions = clickActions,
                            onSetClickAction = { count, action ->
                                viewModel.setClickAction(count, action)
                            },
                            onChooseContact = chooseContact,
                        )
                    }
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
    ActionType.PLAY_MEDIA -> stringResource(R.string.actions_type_play_media)
    ActionType.MEDIA_KEY -> stringResource(R.string.actions_type_media_key)
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
                WebhookFields(action = action, onUpdate = onUpdate)
            }
            if (action.type == ActionType.FAKE_CALL) {
                Spacer(modifier = Modifier.height(8.dp))
                FakeCallFields(
                    action = action,
                    onUpdate = onUpdate,
                    onChooseContact = onChooseContact,
                )
            }
            if (action.type == ActionType.MEDIA_KEY) {
                Spacer(modifier = Modifier.height(8.dp))
                MediaKeySelector(
                    selected = action.mediaKey,
                    onSelect = { onUpdate(action.copy(mediaKey = it)) },
                )
            }
        }
    }
}

/** The URL and body template fields a WEBHOOK action needs. */
@Composable
private fun WebhookFields(action: Action, onUpdate: (Action) -> Unit) {
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

/** The caller fields and calling-accounts link a FAKE_CALL action needs. */
@Composable
private fun FakeCallFields(
    action: Action,
    onUpdate: (Action) -> Unit,
    onChooseContact: () -> Unit,
) {
    val context = LocalContext.current
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
    OutlinedTextField(
        value = action.message,
        onValueChange = { onUpdate(action.copy(message = it)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        label = { Text(stringResource(R.string.actions_fake_call_message_label)) },
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
            // Register first so the Middle toggle exists on the screen the
            // intent opens.
            FakeCallAccount.register(context)
            FakeCallAccount.openSettings(context)
        },
    ) {
        Text(stringResource(R.string.actions_fake_call_account_open))
    }
}

/** The three click slots the ring button exposes. */
private val CLICK_COUNTS = listOf(1, 2, 3)

/**
 * The ring button's per click-count bindings. It is only shown for the ring,
 * because a pendant has no button to click.
 */
@Composable
private fun RingButtonSection(
    clickActions: Map<Int, Action>,
    onSetClickAction: (Int, Action?) -> Unit,
    onChooseContact: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.actions_ring_section_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CLICK_COUNTS.forEach { count ->
            val label = when (count) {
                1 -> stringResource(R.string.actions_click_single)
                2 -> stringResource(R.string.actions_click_double)
                else -> stringResource(R.string.actions_click_triple)
            }
            ClickActionRow(
                label = label,
                action = clickActions[count],
                onSelect = { onSetClickAction(count, it) },
                onChooseContact = onChooseContact,
            )
        }
    }
}

@Composable
private fun ClickActionRow(
    label: String,
    action: Action?,
    onSelect: (Action?) -> Unit,
    onChooseContact: (String) -> Unit,
) {
    val fakeCallMessage = stringResource(R.string.actions_fake_call_default_message)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            DropdownSelector(
                selectedLabel = clickActionLabel(action),
                options = ClickChoice.entries,
                optionLabel = { clickChoiceLabel(it) },
                onSelect = { onSelect(actionForClickChoice(action, it, fakeCallMessage)) },
            )
        }
        if (action != null && action.type == ActionType.FAKE_CALL) {
            Spacer(modifier = Modifier.height(8.dp))
            FakeCallFields(
                action = action,
                onUpdate = { onSelect(it) },
                onChooseContact = { onChooseContact(action.id) },
            )
        }
        if (action != null && action.type == ActionType.WEBHOOK) {
            Spacer(modifier = Modifier.height(8.dp))
            WebhookFields(action = action, onUpdate = { onSelect(it) })
        }
    }
}

/** The choice a click slot's dropdown offers. NONE unbinds the click. */
internal enum class ClickChoice { NONE, PLAY_PAUSE, NEXT, PREVIOUS, FAKE_CALL, WEBHOOK }

/**
 * True when [action] already stores [choice], so re-selecting it is a no-op.
 * Without this a re-selection would replace the action with a fresh id and drop
 * any edited webhook URL/body or caller fields.
 */
private fun isCurrentClickChoice(action: Action?, choice: ClickChoice): Boolean = when (choice) {
    ClickChoice.NONE -> action == null
    ClickChoice.PLAY_PAUSE ->
        action?.type == ActionType.MEDIA_KEY && action.mediaKey == MediaKey.PLAY_PAUSE
    ClickChoice.NEXT ->
        action?.type == ActionType.MEDIA_KEY && action.mediaKey == MediaKey.NEXT
    ClickChoice.PREVIOUS ->
        action?.type == ActionType.MEDIA_KEY && action.mediaKey == MediaKey.PREVIOUS
    ClickChoice.FAKE_CALL -> action?.type == ActionType.FAKE_CALL
    ClickChoice.WEBHOOK -> action?.type == ActionType.WEBHOOK
}

/**
 * The action a click slot stores when [choice] is selected from [current], or
 * null for [ClickChoice.NONE]. Selecting the choice already stored is a no-op
 * and returns [current] so edited fields survive; any other choice builds a
 * fresh action. A click carries no transcript and has no chain, so its action
 * is always enabled, has no pattern and never stops; a webhook gets the default
 * body template so it is usable as soon as it is created, and a fake call gets
 * [fakeCallMessage] so it speaks a filler line out of the box.
 */
internal fun actionForClickChoice(
    current: Action?,
    choice: ClickChoice,
    fakeCallMessage: String = "",
): Action? {
    if (isCurrentClickChoice(current, choice)) return current
    return when (choice) {
        ClickChoice.NONE -> null
        ClickChoice.PLAY_PAUSE -> clickAction(ActionType.MEDIA_KEY, MediaKey.PLAY_PAUSE)
        ClickChoice.NEXT -> clickAction(ActionType.MEDIA_KEY, MediaKey.NEXT)
        ClickChoice.PREVIOUS -> clickAction(ActionType.MEDIA_KEY, MediaKey.PREVIOUS)
        ClickChoice.FAKE_CALL -> clickAction(ActionType.FAKE_CALL, message = fakeCallMessage)
        ClickChoice.WEBHOOK -> clickAction(ActionType.WEBHOOK)
    }
}

private fun clickAction(
    type: ActionType,
    mediaKey: MediaKey = MediaKey.PLAY_PAUSE,
    message: String = "",
) = Action(
    id = UUID.randomUUID().toString(),
    enabled = true,
    type = type,
    pattern = "",
    stop = false,
    // A click slot is a FAKE_CALL or a WEBHOOK; the default body keeps a new
    // webhook usable immediately. A MEDIA_KEY slot never reads the template.
    webhookBodyTemplate = if (type == ActionType.MEDIA_KEY) {
        ""
    } else {
        AppSettings.DEFAULT_WEBHOOK_BODY_TEMPLATE
    },
    // Only a FAKE_CALL speaks; the other click slots never read the message.
    message = if (type == ActionType.FAKE_CALL) message else "",
    mediaKey = mediaKey,
)

@Composable
private fun clickActionLabel(action: Action?): String = when {
    action == null -> stringResource(R.string.actions_click_nothing)
    action.type == ActionType.MEDIA_KEY -> mediaKeyLabel(action.mediaKey)
    else -> actionTypeLabel(action.type)
}

@Composable
private fun clickChoiceLabel(choice: ClickChoice): String = when (choice) {
    ClickChoice.NONE -> stringResource(R.string.actions_click_nothing)
    ClickChoice.PLAY_PAUSE -> mediaKeyLabel(MediaKey.PLAY_PAUSE)
    ClickChoice.NEXT -> mediaKeyLabel(MediaKey.NEXT)
    ClickChoice.PREVIOUS -> mediaKeyLabel(MediaKey.PREVIOUS)
    ClickChoice.FAKE_CALL -> actionTypeLabel(ActionType.FAKE_CALL)
    ClickChoice.WEBHOOK -> actionTypeLabel(ActionType.WEBHOOK)
}

@Composable
private fun mediaKeyLabel(mediaKey: MediaKey): String = when (mediaKey) {
    MediaKey.PLAY_PAUSE -> stringResource(R.string.actions_media_key_play_pause)
    MediaKey.NEXT -> stringResource(R.string.actions_media_key_next)
    MediaKey.PREVIOUS -> stringResource(R.string.actions_media_key_previous)
}

@Composable
private fun MediaKeySelector(selected: MediaKey, onSelect: (MediaKey) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.actions_media_key_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        DropdownSelector(
            selectedLabel = mediaKeyLabel(selected),
            options = MediaKey.entries,
            optionLabel = { mediaKeyLabel(it) },
            onSelect = onSelect,
        )
    }
}

/** A button that shows [selectedLabel] and lets the user pick one of [options]. */
@Composable
private fun <T> DropdownSelector(
    selectedLabel: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selectedLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
