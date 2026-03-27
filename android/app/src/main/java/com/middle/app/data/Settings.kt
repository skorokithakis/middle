package com.middle.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

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

        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_TRANSCRIPTION_PROVIDER = "transcription_provider"
        private const val KEY_BACKGROUND_SYNC = "background_sync"
        private const val KEY_TRANSCRIPTION = "transcription"
        private const val KEY_WEBHOOK_ENABLED = "webhook_enabled"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_BODY_TEMPLATE = "webhook_body_template"
        private const val KEY_LAST_BATTERY_VOLTAGE = "last_battery_voltage"
        private const val KEY_LAST_BATTERY_NOTIFICATION_TIME = "last_battery_notification_time"
        private const val KEY_PAIRED_DEVICE_ADDRESS = "paired_device_address"
        private const val KEY_PAIRING_TOKEN = "pairing_token"
        const val DEFAULT_WEBHOOK_BODY_TEMPLATE = "{\"phrase\": \"\$transcript\"}"
    }
}
