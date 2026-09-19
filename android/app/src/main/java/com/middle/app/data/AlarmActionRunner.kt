package com.middle.app.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.middle.app.MiddleApplication
import com.middle.app.R
import java.util.Locale

/**
 * Turns the alarms [ActionMatcher] matched on a transcript into system alarms.
 *
 * Starting an activity from the background is blocked unless the app can draw
 * overlays, so without that permission the alarm is offered as a notification
 * the user taps instead. A notification is always posted: it either confirms the
 * alarm reached the clock app or, in the fallback case, carries the alarm as its
 * tap action.
 */
object AlarmActionRunner {

    private const val TAG = "AlarmActionRunner"
    private const val ALARM_MESSAGE = "Middle"

    fun run(context: Context, result: ActionResult) {
        for (match in result.fired) {
            val intent = alarmIntent(match.time)
            if (Settings.canDrawOverlays(context)) {
                try {
                    context.startActivity(intent)
                    val text = context.getString(R.string.alarm_sent_notification_text, formatTime(match.time))
                    postNotification(context, text, MiddleApplication.ACTIONS_NOTIFICATION_ID)
                } catch (exception: ActivityNotFoundException) {
                    Log.w(TAG, "[action] no clock app found for ${match.action.id}")
                    postNotification(
                        context,
                        context.getString(R.string.alarm_no_clock_app_notification_text),
                        MiddleApplication.ACTIONS_INFO_NOTIFICATION_ID,
                    )
                } catch (exception: SecurityException) {
                    Log.w(TAG, "[action] could not set alarm for ${match.action.id}")
                    postNotification(
                        context,
                        context.getString(R.string.alarm_could_not_set_notification_text),
                        MiddleApplication.ACTIONS_INFO_NOTIFICATION_ID,
                    )
                }
            } else {
                postTapToSetNotification(context, intent, match.time)
            }
        }

        for (match in result.noTime) {
            Log.d(TAG, "[action] alarm action ${match.action.id} matched with no time")
            postNotification(
                context,
                context.getString(R.string.alarm_no_time_notification_text),
                MiddleApplication.ACTIONS_INFO_NOTIFICATION_ID,
            )
        }
    }

    private fun alarmIntent(time: AlarmTime): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_MESSAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun postTapToSetNotification(context: Context, intent: Intent, time: AlarmTime) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = notificationBuilder(
            context,
            context.getString(R.string.alarm_tap_notification_text, formatTime(time)),
        )
            .setContentIntent(pendingIntent)
            .build()
        notificationManager(context).notify(MiddleApplication.ACTIONS_NOTIFICATION_ID, notification)
    }

    private fun postNotification(context: Context, text: String, notificationId: Int) {
        val notification = notificationBuilder(context, text).build()
        notificationManager(context).notify(notificationId, notification)
    }

    private fun notificationBuilder(context: Context, text: String) =
        NotificationCompat.Builder(context, MiddleApplication.ACTIONS_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)

    private fun notificationManager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun formatTime(time: AlarmTime): String =
        String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
}
