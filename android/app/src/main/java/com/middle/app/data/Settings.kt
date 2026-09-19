package com.middle.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The settings that take part in a backup. A null field means the backup file
 * did not have that key, so an import leaves the stored value alone. Runtime
 * state that is rebuilt on the next sync is deliberately not part of a backup.
 */
data class SettingsBackup(
    val openAiApiKey: String? = null,
    val elevenLabsApiKey: String? = null,
    val transcriptionProvider: String? = null,
    val deviceType: String? = null,
    val ringDeviceAddress: String? = null,
    val backgroundSyncEnabled: Boolean? = null,
    val transcriptionEnabled: Boolean? = null,
    val webhookEnabled: Boolean? = null,
    val webhookUrl: String? = null,
    val webhookBodyTemplate: String? = null,
    val pairedDeviceAddress: String? = null,
    val pairingToken: String? = null,
)

enum class BackupParseError {
    NOT_A_BACKUP,
    NEWER_VERSION,
}

sealed interface BackupParseResult {
    data class Valid(val backup: SettingsBackup) : BackupParseResult
    data class Invalid(val error: BackupParseError) : BackupParseResult
}

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
                    // A reset index means the ring is unknown again, so the
                    // first-sync backlog decision has to be made afresh.
                    remove(KEY_RING_BACKLOG_DECIDED)
                } else {
                    putInt(KEY_LAST_SUCCESSFUL_COLLECTION_INDEX, value)
                }
            }.apply()
        }

    // Records that the first-sync backlog decision has been made, so a first
    // transfer that fails before committing an index cannot make the next
    // range look like a fresh backlog. Runtime state, so it is not exported.
    var ringBacklogDecided: Boolean
        get() = prefs.getBoolean(KEY_RING_BACKLOG_DECIDED, false)
        set(value) = prefs.edit().putBoolean(KEY_RING_BACKLOG_DECIDED, value).apply()

    val isPaired: Boolean
        get() = pairedDeviceAddress.isNotEmpty() && pairingToken.isNotEmpty()

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_PAIRED_DEVICE_ADDRESS)
            .remove(KEY_PAIRING_TOKEN)
            .apply()
    }

    /**
     * Serializes the configurable settings to the backup file format, keyed by
     * the preference names so the file describes itself against this class.
     */
    fun exportBackupJson(): String = JSONObject().apply {
        put(BACKUP_VERSION_KEY, BACKUP_VERSION)
        put(KEY_OPENAI_API_KEY, openAiApiKey)
        put(KEY_ELEVENLABS_API_KEY, elevenLabsApiKey)
        put(KEY_TRANSCRIPTION_PROVIDER, transcriptionProvider)
        put(KEY_DEVICE_TYPE, deviceType)
        put(KEY_RING_DEVICE_ADDRESS, ringDeviceAddress)
        put(KEY_BACKGROUND_SYNC, backgroundSyncEnabled)
        put(KEY_TRANSCRIPTION, transcriptionEnabled)
        put(KEY_WEBHOOK_ENABLED, webhookEnabled)
        put(KEY_WEBHOOK_URL, webhookUrl)
        put(KEY_WEBHOOK_BODY_TEMPLATE, webhookBodyTemplate)
        put(KEY_PAIRED_DEVICE_ADDRESS, pairedDeviceAddress)
        put(KEY_PAIRING_TOKEN, pairingToken)
    }.toString()

    /**
     * Parses and type-checks a backup without writing anything. The whole file
     * is validated before it is applied because a half-applied import would mix
     * old and new settings in a way the user cannot see or undo.
     */
    fun parseBackupJson(text: String): BackupParseResult {
        val tokener = JSONTokener(text)
        val json = try {
            JSONObject(tokener)
        } catch (exception: JSONException) {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        // JSONObject(text) stops at the first object and ignores trailing
        // content, so the tokener must be exhausted for the file to be valid.
        val trailing = try {
            tokener.nextClean()
        } catch (exception: JSONException) {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        if (trailing != '\u0000') {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        val version = json.opt(BACKUP_VERSION_KEY)
        if (version !is Int) {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        if (version > BACKUP_VERSION) {
            return BackupParseResult.Invalid(BackupParseError.NEWER_VERSION)
        }
        if (version < BACKUP_VERSION) {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        for (key in BACKUP_STRING_KEYS) {
            if (json.has(key) && json.opt(key) !is String) {
                return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
            }
        }
        for (key in BACKUP_BOOLEAN_KEYS) {
            if (json.has(key) && json.opt(key) !is Boolean) {
                return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
            }
        }
        return BackupParseResult.Valid(
            SettingsBackup(
                openAiApiKey = json.stringOrNull(KEY_OPENAI_API_KEY),
                elevenLabsApiKey = json.stringOrNull(KEY_ELEVENLABS_API_KEY),
                transcriptionProvider = json.stringOrNull(KEY_TRANSCRIPTION_PROVIDER),
                deviceType = json.stringOrNull(KEY_DEVICE_TYPE),
                ringDeviceAddress = json.stringOrNull(KEY_RING_DEVICE_ADDRESS),
                backgroundSyncEnabled = json.booleanOrNull(KEY_BACKGROUND_SYNC),
                transcriptionEnabled = json.booleanOrNull(KEY_TRANSCRIPTION),
                webhookEnabled = json.booleanOrNull(KEY_WEBHOOK_ENABLED),
                webhookUrl = json.stringOrNull(KEY_WEBHOOK_URL),
                webhookBodyTemplate = json.stringOrNull(KEY_WEBHOOK_BODY_TEMPLATE),
                pairedDeviceAddress = json.stringOrNull(KEY_PAIRED_DEVICE_ADDRESS),
                pairingToken = json.stringOrNull(KEY_PAIRING_TOKEN),
            ),
        )
    }

    /**
     * Writes a parsed backup through the property setters instead of the raw
     * preferences, so a device type or ring address change reaches the sync
     * service and it restarts its session against the imported device.
     */
    fun applyBackup(backup: SettingsBackup) {
        backup.openAiApiKey?.let { openAiApiKey = it }
        backup.elevenLabsApiKey?.let { elevenLabsApiKey = it }
        backup.transcriptionProvider?.let { transcriptionProvider = it }
        backup.deviceType?.let { deviceType = it }
        backup.ringDeviceAddress?.let { ringDeviceAddress = it }
        backup.backgroundSyncEnabled?.let { backgroundSyncEnabled = it }
        backup.transcriptionEnabled?.let { transcriptionEnabled = it }
        backup.webhookEnabled?.let { webhookEnabled = it }
        backup.webhookUrl?.let { webhookUrl = it }
        backup.webhookBodyTemplate?.let { webhookBodyTemplate = it }
        backup.pairedDeviceAddress?.let { pairedDeviceAddress = it }
        backup.pairingToken?.let { pairingToken = it }
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
        private const val KEY_RING_BACKLOG_DECIDED = "ring_backlog_decided"
        const val DEFAULT_WEBHOOK_BODY_TEMPLATE = "{\"phrase\": \"\$transcript\"}"

        // A backup is one flat object tagged with the format version. New
        // settings are added to a later version's backup, so a file written by
        // an older build never has to know about them.
        private const val BACKUP_VERSION_KEY = "version"
        private const val BACKUP_VERSION = 1

        // Grouped by JSON type so a parse can reject a key that carries the
        // wrong type before any setting is written.
        private val BACKUP_STRING_KEYS = listOf(
            KEY_OPENAI_API_KEY,
            KEY_ELEVENLABS_API_KEY,
            KEY_TRANSCRIPTION_PROVIDER,
            KEY_DEVICE_TYPE,
            KEY_RING_DEVICE_ADDRESS,
            KEY_WEBHOOK_URL,
            KEY_WEBHOOK_BODY_TEMPLATE,
            KEY_PAIRED_DEVICE_ADDRESS,
            KEY_PAIRING_TOKEN,
        )
        private val BACKUP_BOOLEAN_KEYS = listOf(
            KEY_BACKGROUND_SYNC,
            KEY_TRANSCRIPTION,
            KEY_WEBHOOK_ENABLED,
        )
    }
}

/** A key that is absent stays null, which an import reads as "leave as is". */
private fun JSONObject.stringOrNull(key: String): String? = if (has(key)) getString(key) else null

private fun JSONObject.booleanOrNull(key: String): Boolean? = if (has(key)) getBoolean(key) else null
