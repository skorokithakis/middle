package com.middle.app.data

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.middle.app.MainActivity
import com.middle.app.MiddleApplication
import com.middle.app.R
import com.middle.app.telecom.FakeCallAccount
import com.middle.app.transcription.CommandKind
import com.middle.app.transcription.ParsedCommand
import com.middle.app.transcription.SpotifySearchClient
import com.middle.app.transcription.SpotifySearchResult
import com.middle.app.transcription.TimeParseClient
import com.middle.app.transcription.TimeParseResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Executes an ALARM, CALENDAR, FAKE_CALL, PLAY_MEDIA or MEDIA_KEY [ActionHit]
 * against the system.
 *
 * [run] blocks while it calls the time parser and writes to a provider, so the
 * caller runs it on [kotlinx.coroutines.Dispatchers.IO]. It returns true only
 * when the hit produced a result, which is what lets the caller apply the
 * action's `stop` and otherwise continue with the next action. A WEBHOOK hit is
 * not this class's job and always reports no result.
 *
 * Every notification uses the `middle_actions` channel. Successes, the
 * tap-to-set alarm and tap-to-play media use
 * [MiddleApplication.ACTIONS_NOTIFICATION_ID] so a later result replaces the
 * pending one; failures use
 * [MiddleApplication.ACTIONS_INFO_NOTIFICATION_ID] so they cannot replace it.
 * The tap-to-set alarm and tap-to-play media are the only notifications that do
 * not open the app.
 */
class ActionRunner(context: Context) {

    private val appContext = context.applicationContext
    private val settings = Settings(appContext)

    fun run(hit: ActionHit, transcript: String): Boolean {
        return when (hit.action.type) {
            ActionType.ALARM, ActionType.CALENDAR -> {
                // Refuse a reminder before the LLM call when there is nowhere to
                // write it, so a missing calendar or permission does not spend
                // an API request.
                if (hit.action.type == ActionType.CALENDAR && !calendarReady()) {
                    Log.w(TAG, "[action] reminder action matched but no calendar is ready")
                    postInfoNotification(appContext.getString(R.string.calendar_none_notification_text))
                    return false
                }
                val now = ZonedDateTime.now()
                val kind = when (hit.action.type) {
                    ActionType.ALARM -> CommandKind.ALARM
                    else -> CommandKind.REMINDER
                }
                val command = parseCommand(transcript, now, kind) ?: return false
                when (hit.action.type) {
                    ActionType.ALARM -> runAlarm(command, now)
                    else -> runCalendar(command)
                }
            }
            ActionType.WEBHOOK -> false
            ActionType.FAKE_CALL -> runFakeCall(hit.action)
            ActionType.PLAY_MEDIA -> runPlayMedia(hit)
            ActionType.MEDIA_KEY -> runMediaKey(hit.action)
        }
    }

    /**
     * Runs the shared time-parse step. Returns null, after posting the matching
     * info notification, when the key is missing or no time could be read.
     */
    private fun parseCommand(
        transcript: String,
        now: ZonedDateTime,
        kind: CommandKind,
    ): ParsedCommand? {
        val apiKey = settings.openAiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "[action] time-based action matched but no OpenAI key is set")
            postInfoNotification(appContext.getString(R.string.actions_missing_api_key_notification_text))
            return null
        }
        return when (val result = TimeParseClient(apiKey).parse(transcript, now, kind)) {
            is TimeParseResult.Success -> {
                if (result.command.start == null) {
                    Log.d(TAG, "[action] time parse found no start time")
                    postInfoNotification(appContext.getString(R.string.actions_no_time_notification_text))
                    null
                } else {
                    result.command
                }
            }
            is TimeParseResult.Failure -> {
                Log.w(TAG, "[action] time parse failed: ${result.message}")
                postInfoNotification(appContext.getString(R.string.actions_no_time_notification_text))
                null
            }
        }
    }

    private fun runAlarm(command: ParsedCommand, now: ZonedDateTime): Boolean {
        // An all-day command has a date but no time of day, and an alarm needs a
        // clock time, so it is treated as a command with no usable time.
        val start = command.start
        if (command.allDay || start == null) {
            postInfoNotification(appContext.getString(R.string.actions_no_time_notification_text))
            return false
        }
        if (isMoreThanOneDayAhead(start.atZone(ZoneId.systemDefault()), now)) {
            postInfoNotification(appContext.getString(R.string.alarm_too_far_notification_text))
            return false
        }

        val intent = alarmIntent(start, command.title)
        // Starting an activity from the background needs the overlay permission.
        // Without it the alarm is offered as a notification the user taps.
        if (android.provider.Settings.canDrawOverlays(appContext)) {
            try {
                appContext.startActivity(intent)
            } catch (exception: ActivityNotFoundException) {
                Log.w(TAG, "[action] no clock app accepted the alarm", exception)
                postInfoNotification(appContext.getString(R.string.alarm_could_not_set_notification_text))
                return false
            } catch (exception: SecurityException) {
                Log.w(TAG, "[action] the clock app refused the alarm", exception)
                postInfoNotification(appContext.getString(R.string.alarm_could_not_set_notification_text))
                return false
            }
            postNotification(
                appContext.getString(R.string.alarm_set_notification_text, formatClockTime(start)),
                MiddleApplication.ACTIONS_NOTIFICATION_ID,
                openMiddlePendingIntent(),
            )
            return true
        }

        postTapToSetNotification(intent, start)
        return true
    }

    private fun calendarReady(): Boolean =
        settings.calendarId != null && hasWriteCalendarPermission()

    private fun runCalendar(command: ParsedCommand): Boolean {
        // The caller checked readiness before parsing; the checks are repeated
        // because the permission can be revoked between the two calls.
        val calendarId = settings.calendarId
        if (calendarId == null || !hasWriteCalendarPermission()) {
            Log.w(TAG, "[action] reminder action matched but no calendar is ready")
            postInfoNotification(appContext.getString(R.string.calendar_none_notification_text))
            return false
        }
        val start = command.start
        if (start == null) {
            postInfoNotification(appContext.getString(R.string.actions_no_time_notification_text))
            return false
        }
        val title = command.title ?: appContext.getString(R.string.calendar_default_title)

        return try {
            insertEvent(calendarId, title, command)
            val label = reminderTimeLabel(command.allDay, start, Locale.getDefault())
            val text = if (command.allDay) {
                appContext.getString(R.string.calendar_added_all_day_notification_text, title, label)
            } else {
                appContext.getString(R.string.calendar_added_notification_text, title, label)
            }
            postNotification(
                text,
                MiddleApplication.ACTIONS_NOTIFICATION_ID,
                openMiddlePendingIntent(),
            )
            true
        } catch (exception: Exception) {
            // The provider is a separate process and the write permission can be
            // revoked between the check above and the insert.
            Log.e(TAG, "[action] could not add a calendar event", exception)
            postInfoNotification(appContext.getString(R.string.calendar_could_not_add_notification_text))
            false
        }
    }

    /**
     * Hands a fake call to Telecom so the system dialer shows its own
     * incoming-call screen. The account must be enabled by the user once; the
     * enabled state cannot be read without a phone permission, so the call is
     * attempted and a [SecurityException] means it is not enabled. A missing
     * overlay permission is not needed for this path.
     */
    private fun runFakeCall(action: Action): Boolean {
        FakeCallAccount.register(appContext)
        val telecomManager = appContext.getSystemService(TelecomManager::class.java)
        if (telecomManager == null) {
            Log.w(TAG, "[action] fake call action matched but Telecom is unavailable")
            postInfoNotification(appContext.getString(R.string.fake_call_account_disabled_notification_text))
            return false
        }
        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("tel", action.callerNumber.trim(), null),
            )
            putString(FakeCallAccount.EXTRA_CALLER_NAME, action.callerName)
            putString(FakeCallAccount.EXTRA_MESSAGE, action.message)
            putString(FakeCallAccount.EXTRA_VOICE_NAME, action.voiceName)
        }
        return try {
            telecomManager.addNewIncomingCall(FakeCallAccount.handle(appContext), extras)
            true
        } catch (exception: SecurityException) {
            // The account not being registered or enabled is the only failure
            // Telecom reports this way; there is no permission-free check.
            Log.w(TAG, "[action] fake call refused; the Middle calling account may be disabled", exception)
            postInfoNotification(appContext.getString(R.string.fake_call_account_disabled_notification_text))
            false
        }
    }

    /**
     * Plays [ActionHit.rest] in the Spotify app. Spotify ignores Android's
     * play-from-search route and refuses a MediaBrowserService connection, so
     * the track URI comes from Spotify's Web API and is opened with a VIEW
     * intent addressed to the Spotify package. "Liked songs" is a fixed URI,
     * because a client-credentials token cannot read the user's library. A
     * blank query or missing credentials is not a failure the user can act on,
     * so those post an info notification and report no result to let later
     * actions run.
     */
    private fun runPlayMedia(hit: ActionHit): Boolean {
        val query = hit.rest.trim()
        if (query.isBlank()) {
            Log.d(TAG, "[action] play media action matched but the query is blank")
            postInfoNotification(appContext.getString(R.string.play_media_no_query_notification_text))
            return false
        }

        val clientId = settings.spotifyClientId
        val clientSecret = settings.spotifyClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            Log.w(TAG, "[action] play media action matched but no Spotify credentials are set")
            postInfoNotification(appContext.getString(R.string.play_media_missing_credentials_notification_text))
            return false
        }

        val uri: String
        val label: String
        if (query.equals("liked songs", ignoreCase = true) ||
            query.equals("my liked songs", ignoreCase = true)
        ) {
            uri = LIKED_SONGS_URI
            label = appContext.getString(R.string.play_media_liked_songs_label)
        } else {
            when (val result = SpotifySearchClient(clientId, clientSecret).search(query)) {
                is SpotifySearchResult.Success -> {
                    uri = result.track.uri
                    label = "${result.track.artist} - ${result.track.name}"
                }
                SpotifySearchResult.NotFound -> {
                    Log.d(TAG, "[action] no Spotify result for \"$query\"")
                    postInfoNotification(
                        appContext.getString(R.string.play_media_no_result_notification_text, query),
                    )
                    return false
                }
                is SpotifySearchResult.Failure -> {
                    Log.w(TAG, "[action] Spotify search failed: ${result.message}")
                    postInfoNotification(appContext.getString(R.string.play_media_search_failed_notification_text))
                    return false
                }
            }
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(SPOTIFY_PACKAGE)
            putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + appContext.packageName))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val text = appContext.getString(R.string.play_media_playing_notification_text, label)

        // Starting an activity from the background needs the overlay permission.
        // Without it the song is offered as a notification the user taps.
        if (android.provider.Settings.canDrawOverlays(appContext)) {
            try {
                appContext.startActivity(intent)
            } catch (exception: ActivityNotFoundException) {
                Log.w(TAG, "[action] Spotify is not installed", exception)
                postInfoNotification(appContext.getString(R.string.play_media_spotify_not_installed_notification_text))
                return false
            } catch (exception: SecurityException) {
                Log.w(TAG, "[action] Spotify refused the play request", exception)
                postInfoNotification(appContext.getString(R.string.play_media_refused_notification_text))
                return false
            }
            postNotification(text, MiddleApplication.ACTIONS_NOTIFICATION_ID, openMiddlePendingIntent())
            return true
        }

        postTapToPlayNotification(intent, text)
        return true
    }

    /**
     * Sends a transport key to whatever media session currently has focus.
     * Android requires the down/up pair to register as one press. There is no
     * way to know whether a player received it, so the action always reports a
     * result and lets `stop` behave predictably.
     */
    private fun runMediaKey(action: Action): Boolean {
        val keyCode = when (action.mediaKey) {
            MediaKey.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            Log.w(TAG, "[action] media key action matched but AudioManager is unavailable")
            return false
        }
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }

    private fun insertEvent(calendarId: Long, title: String, command: ParsedCommand) {
        val start = command.start ?: return
        val zone = ZoneId.systemDefault()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            // Android expects "UTC" for all-day events; a timed event keeps the
            // system zone it was parsed in.
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                if (command.allDay) "UTC" else zone.id,
            )
            if (command.allDay) {
                // An all-day event spans whole UTC days; the local zone only
                // decides which calendar day it is shown on.
                val times = allDayEventTimes(start)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.DTSTART, times.startMillis)
                put(CalendarContract.Events.DTEND, times.endMillis)
            } else {
                val times = timedEventTimes(start, command.end, zone)
                put(CalendarContract.Events.DTSTART, times.startMillis)
                put(CalendarContract.Events.DTEND, times.endMillis)
            }
        }
        val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("Calendar provider returned no event URI")

        if (!command.allDay) {
            try {
                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    put(CalendarContract.Reminders.MINUTES, 0)
                }
                val reminderUri =
                    appContext.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
                if (reminderUri == null) {
                    // The event is already saved; a missing alert row is worth a
                    // log but not a failure the user has to act on.
                    Log.w(TAG, "[action] calendar event saved without a reminder row")
                }
            } catch (exception: Exception) {
                // The event insert already succeeded, so a failed reminder must
                // not turn the whole action into a failure the user has to retry.
                Log.w(TAG, "[action] calendar event saved without a reminder row", exception)
            }
        }
    }

    private fun alarmIntent(start: LocalDateTime, title: String?): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, start.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, start.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, title ?: appContext.getString(R.string.app_name))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun postTapToSetNotification(intent: Intent, start: LocalDateTime) {
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        postNotification(
            appContext.getString(R.string.alarm_tap_notification_text, formatClockTime(start)),
            MiddleApplication.ACTIONS_NOTIFICATION_ID,
            pendingIntent,
        )
    }

    private fun postTapToPlayNotification(intent: Intent, text: String) {
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        postNotification(text, MiddleApplication.ACTIONS_NOTIFICATION_ID, pendingIntent)
    }

    private fun postInfoNotification(text: String) {
        postNotification(text, MiddleApplication.ACTIONS_INFO_NOTIFICATION_ID, openMiddlePendingIntent())
    }

    private fun postNotification(text: String, notificationId: Int, contentIntent: PendingIntent) {
        val notification = NotificationCompat.Builder(appContext, MiddleApplication.ACTIONS_CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    private fun openMiddlePendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun hasWriteCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ActionRunner"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        // Spotify's own URI for the signed-in user's saved tracks; the app
        // cannot discover it through a client-credentials token.
        private const val LIKED_SONGS_URI = "spotify:collection:tracks"
    }
}

/** Epoch-millis bounds of a calendar event, in the event timezone. */
internal data class EventTimes(val startMillis: Long, val endMillis: Long)

/**
 * A timed event uses the given end, or half an hour after the start when the
 * command gave none. The local date-times are resolved in [zone].
 */
internal fun timedEventTimes(start: LocalDateTime, end: LocalDateTime?, zone: ZoneId): EventTimes =
    EventTimes(
        startMillis = start.atZone(zone).toInstant().toEpochMilli(),
        endMillis = (end ?: start.plusMinutes(30)).atZone(zone).toInstant().toEpochMilli(),
    )

/**
 * An all-day event spans UTC midnight of the date to UTC midnight of the next
 * date. The parsed start can carry a wall-clock time, which is ignored: the
 * calendar only needs the date. DTEND is exclusive, so it is the next date.
 */
internal fun allDayEventTimes(start: LocalDateTime): EventTimes {
    val date = start.toLocalDate()
    return EventTimes(
        startMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endMillis = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
}

/** Alarms more than a day out are rejected; exactly 24 hours ahead is allowed. */
internal fun isMoreThanOneDayAhead(start: ZonedDateTime, now: ZonedDateTime): Boolean =
    start.isAfter(now.plusHours(24))

/** A short label for the reminder notification, e.g. "Mon 07:30" or "Mon". */
internal fun reminderTimeLabel(allDay: Boolean, start: LocalDateTime, locale: Locale): String {
    val pattern = if (allDay) "EEE" else "EEE HH:mm"
    return start.format(DateTimeFormatter.ofPattern(pattern, locale))
}

private fun formatClockTime(time: LocalDateTime): String =
    String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
