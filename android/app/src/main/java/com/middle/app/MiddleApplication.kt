package com.middle.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.middle.app.data.RecordingsRepository

class MiddleApplication : Application() {
    lateinit var repository: RecordingsRepository

    override fun onCreate() {
        super.onCreate()
        repository = RecordingsRepository(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SYNC_CHANNEL_ID,
            getString(R.string.sync_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val SYNC_CHANNEL_ID = "middle_sync"
        const val SYNC_NOTIFICATION_ID = 1
    }
}
