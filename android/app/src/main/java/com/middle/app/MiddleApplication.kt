package com.middle.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.middle.app.data.PipelineQueue
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MiddleApplication : Application() {
    lateinit var repository: RecordingsRepository
    lateinit var pipelineQueue: PipelineQueue

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // Must run before the pipeline reads the actions, so a legacy global
        // webhook is already an action by the time a job is processed.
        Settings(this).migrateGlobalWebhookToAction()
        repository = RecordingsRepository(this)
        pipelineQueue = PipelineQueue(this, applicationScope, repository)
        pipelineQueue.start()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SYNC_CHANNEL_ID,
                getString(R.string.sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                BATTERY_LOW_CHANNEL_ID,
                getString(R.string.battery_low_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NEW_RECORDING_CHANNEL_ID,
                getString(R.string.new_recording_notification_channel),
                // A low-importance channel was tried first and rejected: it is
                // silent with no banner, so the notification went unnoticed in
                // the shade. Android freezes importance when the channel is
                // first created, so lowering this again needs a new channel ID.
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ACTIONS_CHANNEL_ID,
                getString(R.string.actions_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    companion object {
        const val SYNC_CHANNEL_ID = "middle_sync"
        const val SYNC_NOTIFICATION_ID = 1
        const val BATTERY_LOW_CHANNEL_ID = "middle_battery_low"
        const val BATTERY_LOW_NOTIFICATION_ID = 2
        const val NEW_RECORDING_CHANNEL_ID = "middle_new_recording"
        const val NEW_RECORDING_NOTIFICATION_ID = 3
        const val ACTIONS_CHANNEL_ID = "middle_actions"
        const val ACTIONS_NOTIFICATION_ID = 5

        // Separate from the alarm confirmation above so an informational
        // notification cannot replace a pending tap-to-set alarm.
        const val ACTIONS_INFO_NOTIFICATION_ID = 6
    }
}
