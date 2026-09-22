package com.middle.app.viewmodel

import android.app.Application
import android.provider.CalendarContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.middle.app.data.Action
import com.middle.app.data.ActionType
import com.middle.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A calendar offered by the reminder picker. */
data class CalendarInfo(val id: Long, val displayName: String, val accountName: String)

class ActionsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)

    private val _actions = MutableStateFlow(settings.actions)
    val actions: StateFlow<List<Action>> = _actions

    private val _clickActions = MutableStateFlow(settings.clickActions)
    val clickActions: StateFlow<Map<Int, Action>> = _clickActions

    // The click slots only apply to the ring, so the screen hides its section
    // for the pendant.
    private val _deviceType = MutableStateFlow(settings.deviceType)
    val deviceType: StateFlow<String> = _deviceType

    // Null means no calendar is chosen, or the chosen one can no longer be read
    // (for example the account was removed). Both read as "None".
    private val _selectedCalendarName = MutableStateFlow<String?>(null)
    val selectedCalendarName: StateFlow<String?> = _selectedCalendarName

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars

    /**
     * Reloads the list from storage. A settings import writes actions directly,
     * so the cached flow would otherwise show the pre-import list.
     */
    fun refresh() {
        _actions.value = settings.actions
        _clickActions.value = settings.clickActions
        _deviceType.value = settings.deviceType
        refreshSelectedCalendarName()
    }

    /** Appends a new action of [type] with that type's default pattern. */
    fun addAction(type: ActionType) {
        val action = Action(
            id = UUID.randomUUID().toString(),
            enabled = true,
            type = type,
            pattern = when (type) {
                ActionType.ALARM -> Action.DEFAULT_ALARM_PATTERN
                ActionType.CALENDAR -> Action.DEFAULT_CALENDAR_PATTERN
                ActionType.WEBHOOK -> Action.DEFAULT_WEBHOOK_PATTERN
                ActionType.FAKE_CALL -> Action.DEFAULT_FAKE_CALL_PATTERN
                ActionType.PLAY_MEDIA -> Action.DEFAULT_PLAY_MEDIA_PATTERN
                ActionType.MEDIA_KEY -> Action.DEFAULT_MEDIA_KEY_PATTERN
            },
            // A webhook is the catch-all at the end of the chain; the other
            // types normally stop it.
            stop = type != ActionType.WEBHOOK,
            webhookBodyTemplate = if (type == ActionType.WEBHOOK) {
                Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE
            } else {
                ""
            },
        )
        persist(settings.actions + action)
    }

    /** Replaces the action with the same id. An unknown id leaves the list alone. */
    fun updateAction(action: Action) {
        persist(settings.actions.map { if (it.id == action.id) action else it })
    }

    fun deleteAction(id: String) {
        persist(settings.actions.filterNot { it.id == id })
    }

    /** Binds [action] to a ring click [count], or unbinds it when null. */
    fun setClickAction(count: Int, action: Action?) {
        val updated = settings.clickActions.toMutableMap()
        if (action == null) updated.remove(count) else updated[count] = action
        settings.clickActions = updated
        _clickActions.value = updated
    }

    /** Swaps the action with the one above it. The first action does not move. */
    fun moveUp(id: String) {
        val current = settings.actions
        val index = current.indexOfFirst { it.id == id }
        if (index <= 0) return
        persist(current.swapped(index, index - 1))
    }

    /** Swaps the action with the one below it. The last action does not move. */
    fun moveDown(id: String) {
        val current = settings.actions
        val index = current.indexOfFirst { it.id == id }
        if (index < 0 || index >= current.size - 1) return
        persist(current.swapped(index, index + 1))
    }

    /**
     * Reads the calendars the user may write to, for the reminder picker. The
     * query is suspended so the picker only opens once the list is ready,
     * rather than flashing an empty dialog.
     */
    suspend fun loadCalendars() {
        _calendars.value = withContext(Dispatchers.IO) { queryCalendars() }
    }

    /** Stores the chosen calendar and reloads its display name. */
    fun setCalendarId(id: Long) {
        settings.calendarId = id
        refreshSelectedCalendarName()
    }

    private fun refreshSelectedCalendarName() {
        val id = settings.calendarId
        if (id == null) {
            _selectedCalendarName.value = null
            return
        }
        viewModelScope.launch {
            _selectedCalendarName.value = withContext(Dispatchers.IO) { queryCalendarName(id) }
        }
    }

    private fun queryCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val order = "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        val result = mutableListOf<CalendarInfo>()
        try {
            getApplication<Application>().contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                order,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountColumn =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                while (cursor.moveToNext()) {
                    result.add(
                        CalendarInfo(
                            id = cursor.getLong(idColumn),
                            displayName = cursor.getString(nameColumn) ?: "",
                            accountName = cursor.getString(accountColumn) ?: "",
                        ),
                    )
                }
            }
        } catch (exception: Exception) {
            // The permission can be revoked between the grant check and the
            // query, and the provider is a separate process that can fail.
            Log.w(TAG, "Could not list calendars: $exception")
        }
        return result
    }

    private fun queryCalendarName(id: Long): String? {
        val projection = arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val selection = "${CalendarContract.Calendars._ID} = ?"
        val args = arrayOf(id.toString())
        return try {
            getApplication<Application>().contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Could not read the chosen calendar: $exception")
            null
        }
    }

    private fun persist(actions: List<Action>) {
        settings.actions = actions
        _actions.value = actions
    }

    private fun List<Action>.swapped(first: Int, second: Int): List<Action> =
        toMutableList().apply {
            val value = this[first]
            this[first] = this[second]
            this[second] = value
        }

    companion object {
        private const val TAG = "ActionsViewModel"
    }
}
