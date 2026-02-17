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

    companion object {
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_BACKGROUND_SYNC = "background_sync"
        private const val KEY_TRANSCRIPTION = "transcription"
        private const val KEY_WEBHOOK_ENABLED = "webhook_enabled"
        private const val KEY_WEBHOOK_URL = "webhook_url"
    }
}
