package com.middle.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.concurrent.CopyOnWriteArrayList

class Settings(context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        "middle_settings",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()

    var elevenLabsApiKey: String
        get() = prefs.getString(KEY_ELEVENLABS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_API_KEY, value).apply()

    var transcriptionProvider: String
        get() = prefs.getString(KEY_TRANSCRIPTION_PROVIDER, TRANSCRIPTION_PROVIDER_OPENAI) ?: TRANSCRIPTION_PROVIDER_OPENAI
        set(value) = prefs.edit().putString(KEY_TRANSCRIPTION_PROVIDER, value).apply()

    // Pendant is the default because the pendant is the only device existing
    // installs have, so an install that upgrades to a build with the ring must
    // keep syncing from the device it already paired.
    var deviceType: String
        get() = prefs.getString(KEY_DEVICE_TYPE, DEVICE_TYPE_PENDANT) ?: DEVICE_TYPE_PENDANT
        set(value) {
            prefs.edit().putString(KEY_DEVICE_TYPE, value).apply()
            notifySessionChanged(SessionChange.DEVICE_TYPE)
        }

    /**
     * Called whenever a setting that determines the live sync session changes.
     *
     * EncryptedSharedPreferences only notifies listeners registered on the same
     * instance, and the settings UI and the sync service each construct their
     * own [Settings], so a listener registered the usual way would never fire
     * for the other component's write. The notification is fanned out from a
     * process-wide list instead.
     *
     * The change kind is carried rather than just the value because the two
     * changes are not interchangeable: a device type write that repeats the
     * running value must not restart anything, while a ring address write
     * always invalidates the running ring session even though the device type
     * stays the same.
     */
    fun addSessionChangeListener(listener: (SessionChange) -> Unit) {
        sessionChangeListeners.add(listener)
    }

    fun removeSessionChangeListener(listener: (SessionChange) -> Unit) {
        sessionChangeListeners.remove(listener)
    }

    private fun notifySessionChanged(change: SessionChange) {
        sessionChangeListeners.forEach { listener -> listener(change) }
    }

    enum class SessionChange {
        DEVICE_TYPE,
        RING_DEVICE_ADDRESS,
    }

    var backgroundSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, value).apply()

    var transcriptionEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSCRIPTION, true)
        set(value) = prefs.edit().putBoolean(KEY_TRANSCRIPTION, value).apply()

    var webhookEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEBHOOK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WEBHOOK_ENABLED, value).apply()

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    var webhookBodyTemplate: String
        get() = prefs.getString(KEY_WEBHOOK_BODY_TEMPLATE, DEFAULT_WEBHOOK_BODY_TEMPLATE) ?: DEFAULT_WEBHOOK_BODY_TEMPLATE
        set(value) = prefs.edit().putString(KEY_WEBHOOK_BODY_TEMPLATE, value).apply()

    var lastBatteryVoltage: String
        get() = prefs.getString(KEY_LAST_BATTERY_VOLTAGE, "N/A") ?: "N/A"
        set(value) = prefs.edit().putString(KEY_LAST_BATTERY_VOLTAGE, value).apply()

    var lastBatteryNotificationTime: Long
        get() = prefs.getLong(KEY_LAST_BATTERY_NOTIFICATION_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BATTERY_NOTIFICATION_TIME, value).apply()

    var pairedDeviceAddress: String
        get() = prefs.getString(KEY_PAIRED_DEVICE_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRED_DEVICE_ADDRESS, value).apply()

    var pairingToken: String
        get() = prefs.getString(KEY_PAIRING_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRING_TOKEN, value).apply()

    var ringDeviceAddress: String
        get() = prefs.getString(KEY_RING_DEVICE_ADDRESS, "") ?: ""
        set(value) {
            val previous = ringDeviceAddress
            prefs.edit().putString(KEY_RING_DEVICE_ADDRESS, value).apply()
            // The collection index is a position in one specific ring's
            // timeline. If the stored ring changes, the new ring would inherit
            // the old ring's index and its earlier recordings would be skipped.
            // Writing the same address again is a no-op, so this must compare
            // rather than reset on every write.
            if (value.isNotEmpty() && value != previous) {
                lastSuccessfulCollectionIndex = null
                notifySessionChanged(SessionChange.RING_DEVICE_ADDRESS)
            }
        }

    // Null means the ring has never reported a completed collection, so the
    // library starts from the beginning rather than from a stale index.
    var lastSuccessfulCollectionIndex: Int?
        get() = if (prefs.contains(KEY_LAST_SUCCESSFUL_COLLECTION_INDEX)) {
            prefs.getInt(KEY_LAST_SUCCESSFUL_COLLECTION_INDEX, 0)
        } else {
            null
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_LAST_SUCCESSFUL_COLLECTION_INDEX)
                } else {
                    putInt(KEY_LAST_SUCCESSFUL_COLLECTION_INDEX, value)
                }
            }.apply()
        }

    val isPaired: Boolean
        get() = pairedDeviceAddress.isNotEmpty() && pairingToken.isNotEmpty()

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_PAIRED_DEVICE_ADDRESS)
            .remove(KEY_PAIRING_TOKEN)
            .apply()
    }

    companion object {
        const val TRANSCRIPTION_PROVIDER_OPENAI = "openai"
        const val TRANSCRIPTION_PROVIDER_ELEVENLABS = "elevenlabs"

        // Process-wide because every Settings instance has its own encrypted
        // preferences wrapper, but a session change has to reach listeners
        // registered by any instance.
        private val sessionChangeListeners = CopyOnWriteArrayList<(SessionChange) -> Unit>()

        const val DEVICE_TYPE_PENDANT = "pendant"
        const val DEVICE_TYPE_RING = "ring"

        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_TRANSCRIPTION_PROVIDER = "transcription_provider"
        private const val KEY_DEVICE_TYPE = "device_type"
        private const val KEY_BACKGROUND_SYNC = "background_sync"
        private const val KEY_TRANSCRIPTION = "transcription"
        private const val KEY_WEBHOOK_ENABLED = "webhook_enabled"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_BODY_TEMPLATE = "webhook_body_template"
        private const val KEY_LAST_BATTERY_VOLTAGE = "last_battery_voltage"
        private const val KEY_LAST_BATTERY_NOTIFICATION_TIME = "last_battery_notification_time"
        private const val KEY_PAIRED_DEVICE_ADDRESS = "paired_device_address"
        private const val KEY_PAIRING_TOKEN = "pairing_token"
        private const val KEY_RING_DEVICE_ADDRESS = "ring_device_address"
        private const val KEY_LAST_SUCCESSFUL_COLLECTION_INDEX = "last_successful_collection_index"
        const val DEFAULT_WEBHOOK_BODY_TEMPLATE = "{\"phrase\": \"\$transcript\"}"
    }
}
