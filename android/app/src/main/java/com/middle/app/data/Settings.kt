package com.middle.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The settings that take part in a backup. A null field means the backup file
 * did not have that key, so an import leaves the stored value alone. Runtime
 * state that is rebuilt on the next sync is deliberately not part of a backup.
 */
data class SettingsBackup(
    val openAiApiKey: String? = null,
    val elevenLabsApiKey: String? = null,
    val spotifyClientId: String? = null,
    val spotifyClientSecret: String? = null,
    val transcriptionProvider: String? = null,
    val deviceType: String? = null,
    val ringDeviceAddress: String? = null,
    val backgroundSyncEnabled: Boolean? = null,
    val transcriptionEnabled: Boolean? = null,
    val calendarId: Long? = null,
    // Legacy global webhook keys. Kept so a version 1 backup still imports,
    // where they are then migrated into a WEBHOOK action. A version 2 backup
    // carries the webhook in [actions] instead and does not write these.
    val webhookEnabled: Boolean? = null,
    val webhookUrl: String? = null,
    val webhookBodyTemplate: String? = null,
    val actions: List<Action>? = null,
    val clickActions: Map<Int, Action>? = null,
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

    // Spotify client-credentials pair for the PLAY_MEDIA action. The app polls
    // the Web API for a track URI, then hands it to the installed Spotify app.
    var spotifyClientId: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_ID, value).apply()

    var spotifyClientSecret: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_SECRET, value).apply()

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

    // Null means no calendar has been picked yet.
    var calendarId: Long?
        get() = if (prefs.contains(KEY_CALENDAR_ID)) prefs.getLong(KEY_CALENDAR_ID, 0L) else null
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CALENDAR_ID) else putLong(KEY_CALENDAR_ID, value)
            }.apply()
        }

    // Stored as one JSON array string so the list stays a single preference key.
    // An absent key reads as no actions.
    var actions: List<Action>
        get() = prefs.getString(KEY_ACTIONS, null)?.let { Action.parseJsonOrNull(it) } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_ACTIONS, Action.toJson(value)).apply()

    // One action per ring click count, stored as one JSON object string keyed
    // by the count. An absent or malformed key reads as no click actions.
    var clickActions: Map<Int, Action>
        get() = prefs.getString(KEY_CLICK_ACTIONS, null)
            ?.let { Action.parseClickActionsOrNull(it) }
            ?: emptyMap()
        set(value) =
            prefs.edit().putString(KEY_CLICK_ACTIONS, Action.clickActionsToJson(value)).apply()

    /**
     * Moves the legacy global webhook into the action list as a catch-all
     * WEBHOOK action, then clears the legacy fields so it cannot repeat.
     *
     * Runs once per install from [android.app.Application.onCreate]. It is also
     * called at the end of [applyBackup], which resets the guard, so a version 1
     * backup's webhook is migrated too.
     */
    fun migrateGlobalWebhookToAction() {
        if (prefs.getBoolean(KEY_WEBHOOK_MIGRATED, false)) return
        // The legacy keys are no longer exposed as properties, so the migration
        // reads them raw. [applyBackup] writes a version 1 backup's keys here
        // first for exactly this read.
        val url = (prefs.getString(KEY_WEBHOOK_URL, "") ?: "").trim()
        val enabled = prefs.getBoolean(KEY_WEBHOOK_ENABLED, false)
        // A configured webhook migrates even when it was disabled, so switching
        // the old toggle off does not silently drop the URL on upgrade. A
        // catch-all action with the same URL means a version 1 backup was
        // imported before; do not append a second one.
        val alreadyMigrated = actions.any {
            it.type == ActionType.WEBHOOK &&
                it.pattern == Action.DEFAULT_WEBHOOK_PATTERN &&
                it.webhookUrl == url
        }
        if (url.isNotEmpty() && !alreadyMigrated) {
            val template =
                prefs.getString(KEY_WEBHOOK_BODY_TEMPLATE, DEFAULT_WEBHOOK_BODY_TEMPLATE)
                    ?: DEFAULT_WEBHOOK_BODY_TEMPLATE
            val migrated = Action(
                id = UUID.randomUUID().toString(),
                enabled = enabled,
                type = ActionType.WEBHOOK,
                pattern = Action.DEFAULT_WEBHOOK_PATTERN,
                stop = false,
                webhookUrl = url,
                webhookBodyTemplate = template,
            )
            // Appending preserves the position of the existing rules.
            actions = actions + migrated
        }
        prefs.edit()
            .putBoolean(KEY_WEBHOOK_ENABLED, false)
            .putString(KEY_WEBHOOK_URL, "")
            .putString(KEY_WEBHOOK_BODY_TEMPLATE, DEFAULT_WEBHOOK_BODY_TEMPLATE)
            .putBoolean(KEY_WEBHOOK_MIGRATED, true)
            .apply()
    }

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
        put(KEY_SPOTIFY_CLIENT_ID, spotifyClientId)
        put(KEY_SPOTIFY_CLIENT_SECRET, spotifyClientSecret)
        put(KEY_TRANSCRIPTION_PROVIDER, transcriptionProvider)
        put(KEY_DEVICE_TYPE, deviceType)
        put(KEY_RING_DEVICE_ADDRESS, ringDeviceAddress)
        put(KEY_BACKGROUND_SYNC, backgroundSyncEnabled)
        put(KEY_TRANSCRIPTION, transcriptionEnabled)
        // A null calendar is written as an absent key rather than JSON null.
        calendarId?.let { put(KEY_CALENDAR_ID, it) }
        put(KEY_ACTIONS, Action.toJson(actions))
        put(KEY_CLICK_ACTIONS, Action.clickActionsToJson(clickActions))
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
        // Older versions stay importable; a version 1 file's global webhook is
        // migrated into an action when it is applied.
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
        // JSON numbers arrive as Int or Long, so both are accepted.
        if (json.has(KEY_CALENDAR_ID) && json.opt(KEY_CALENDAR_ID) !is Number) {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        // A present actions value must be a JSON array: a plain string would
        // otherwise parse as an empty list and wipe the stored actions.
        val actions = json.stringOrNull(KEY_ACTIONS)?.let { Action.parseJsonOrNull(it) }
        if (json.has(KEY_ACTIONS) && actions == null) {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        // Like actions, a present clickActions value must be a JSON object: a
        // plain string would otherwise read as an empty map and wipe the map.
        val clickActions = json.stringOrNull(KEY_CLICK_ACTIONS)
            ?.let { Action.parseClickActionsOrNull(it) }
        if (json.has(KEY_CLICK_ACTIONS) && clickActions == null) {
            return BackupParseResult.Invalid(BackupParseError.NOT_A_BACKUP)
        }
        return BackupParseResult.Valid(
            SettingsBackup(
                openAiApiKey = json.stringOrNull(KEY_OPENAI_API_KEY),
                elevenLabsApiKey = json.stringOrNull(KEY_ELEVENLABS_API_KEY),
                spotifyClientId = json.stringOrNull(KEY_SPOTIFY_CLIENT_ID),
                spotifyClientSecret = json.stringOrNull(KEY_SPOTIFY_CLIENT_SECRET),
                transcriptionProvider = json.stringOrNull(KEY_TRANSCRIPTION_PROVIDER),
                deviceType = json.stringOrNull(KEY_DEVICE_TYPE),
                ringDeviceAddress = json.stringOrNull(KEY_RING_DEVICE_ADDRESS),
                backgroundSyncEnabled = json.booleanOrNull(KEY_BACKGROUND_SYNC),
                transcriptionEnabled = json.booleanOrNull(KEY_TRANSCRIPTION),
                calendarId = json.longOrNull(KEY_CALENDAR_ID),
                webhookEnabled = json.booleanOrNull(KEY_WEBHOOK_ENABLED),
                webhookUrl = json.stringOrNull(KEY_WEBHOOK_URL),
                webhookBodyTemplate = json.stringOrNull(KEY_WEBHOOK_BODY_TEMPLATE),
                actions = actions,
                clickActions = clickActions,
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
        backup.spotifyClientId?.let { spotifyClientId = it }
        backup.spotifyClientSecret?.let { spotifyClientSecret = it }
        backup.transcriptionProvider?.let { transcriptionProvider = it }
        backup.deviceType?.let { deviceType = it }
        backup.ringDeviceAddress?.let { ringDeviceAddress = it }
        backup.backgroundSyncEnabled?.let { backgroundSyncEnabled = it }
        backup.transcriptionEnabled?.let { transcriptionEnabled = it }
        backup.calendarId?.let { calendarId = it }
        backup.actions?.let { actions = it }
        backup.clickActions?.let { clickActions = it }
        backup.pairedDeviceAddress?.let { pairedDeviceAddress = it }
        backup.pairingToken?.let { pairingToken = it }
        // A version 1 backup carries its global webhook in the legacy keys.
        // They are written raw, then the guard is cleared so the migration below
        // turns them into a WEBHOOK action; a version 2 backup has no legacy
        // keys and clearing the guard is a harmless no-op.
        prefs.edit().apply {
            backup.webhookEnabled?.let { putBoolean(KEY_WEBHOOK_ENABLED, it) }
            backup.webhookUrl?.let { putString(KEY_WEBHOOK_URL, it) }
            backup.webhookBodyTemplate?.let { putString(KEY_WEBHOOK_BODY_TEMPLATE, it) }
            remove(KEY_WEBHOOK_MIGRATED)
        }.apply()
        migrateGlobalWebhookToAction()
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
        private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
        private const val KEY_SPOTIFY_CLIENT_SECRET = "spotify_client_secret"
        private const val KEY_TRANSCRIPTION_PROVIDER = "transcription_provider"
        private const val KEY_DEVICE_TYPE = "device_type"
        private const val KEY_BACKGROUND_SYNC = "background_sync"
        private const val KEY_TRANSCRIPTION = "transcription"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_WEBHOOK_ENABLED = "webhook_enabled"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_BODY_TEMPLATE = "webhook_body_template"
        private const val KEY_WEBHOOK_MIGRATED = "webhook_migrated"
        private const val KEY_ACTIONS = "actions"
        private const val KEY_CLICK_ACTIONS = "clickActions"
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
        private const val BACKUP_VERSION = 2

        // Grouped by JSON type so a parse can reject a key that carries the
        // wrong type before any setting is written.
        private val BACKUP_STRING_KEYS = listOf(
            KEY_OPENAI_API_KEY,
            KEY_ELEVENLABS_API_KEY,
            KEY_SPOTIFY_CLIENT_ID,
            KEY_SPOTIFY_CLIENT_SECRET,
            KEY_TRANSCRIPTION_PROVIDER,
            KEY_DEVICE_TYPE,
            KEY_RING_DEVICE_ADDRESS,
            KEY_WEBHOOK_URL,
            KEY_WEBHOOK_BODY_TEMPLATE,
            KEY_ACTIONS,
            KEY_CLICK_ACTIONS,
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

/** JSON numbers arrive as Int or Long depending on magnitude, so both convert. */
private fun JSONObject.longOrNull(key: String): Long? = when (val value = opt(key)) {
    is Number -> value.toLong()
    else -> null
}
