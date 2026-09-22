package com.middle.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ActionType {
    ALARM,
    CALENDAR,
    WEBHOOK,
    FAKE_CALL,
    PLAY_MEDIA,
    MEDIA_KEY,
}

/** The transport key a MEDIA_KEY action sends to the system. */
enum class MediaKey {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
}

/**
 * A user-defined action that runs against a transcript after transcription.
 * The whole list is persisted as one JSON array string in [Settings], and the
 * array order is the order the actions run in.
 */
data class Action(
    val id: String,
    val enabled: Boolean,
    val type: ActionType,
    val pattern: String,
    val stop: Boolean,
    val webhookUrl: String = "",
    val webhookBodyTemplate: String = "",
    val callerName: String = "",
    val callerNumber: String = "",
    // The fake call speaks this; blank means a silent call.
    val message: String = "",
    val mediaKey: MediaKey = MediaKey.PLAY_PAUSE,
) {
    private fun toJsonObject(): JSONObject = JSONObject().apply {
        put(FIELD_ID, id)
        put(FIELD_ENABLED, enabled)
        put(FIELD_TYPE, type.name)
        put(FIELD_PATTERN, pattern)
        put(FIELD_STOP, stop)
        put(FIELD_WEBHOOK_URL, webhookUrl)
        put(FIELD_WEBHOOK_BODY_TEMPLATE, webhookBodyTemplate)
        put(FIELD_CALLER_NAME, callerName)
        put(FIELD_CALLER_NUMBER, callerNumber)
        put(FIELD_MESSAGE, message)
        put(FIELD_MEDIA_KEY, mediaKey.name)
    }

    companion object {
        // The matcher compiles patterns with IGNORE_CASE, so the source is
        // stored as written rather than baked into a Regex here.
        const val DEFAULT_ALARM_PATTERN = """\bset (an? )?alarm (for|at)\b"""
        const val DEFAULT_CALENDAR_PATTERN = """\b(remind me|add (an? )?(appointment|event))\b"""
        const val DEFAULT_WEBHOOK_PATTERN = ".*"
        const val DEFAULT_FAKE_CALL_PATTERN = """\bfake call\b"""
        // Anchored at the start so a normal note like "I will play tennis"
        // does not fire.
        const val DEFAULT_PLAY_MEDIA_PATTERN = """^play\b"""
        // Anchored at the start so a normal note like "I will resume work" does
        // not fire. Pause/resume only: a new action defaults to PLAY_PAUSE, so
        // matching "next track"/"previous song" here would toggle playback
        // instead of skipping. Skip actions need an explicit pattern and key.
        const val DEFAULT_MEDIA_KEY_PATTERN = """^(pause|resume)\b"""

        // The ring button supports one, two or three clicks.
        private const val MIN_CLICK_COUNT = 1
        private const val MAX_CLICK_COUNT = 3

        private const val TAG = "Action"
        private const val FIELD_ID = "id"
        private const val FIELD_ENABLED = "enabled"
        private const val FIELD_TYPE = "type"
        private const val FIELD_PATTERN = "pattern"
        private const val FIELD_STOP = "stop"
        // Builds before the rename wrote 'suppressWebhook'; reading accepts it
        // as a fallback so stored action lists keep loading.
        private const val FIELD_LEGACY_SUPPRESS_WEBHOOK = "suppressWebhook"
        private const val FIELD_WEBHOOK_URL = "webhookUrl"
        private const val FIELD_WEBHOOK_BODY_TEMPLATE = "webhookBodyTemplate"
        private const val FIELD_CALLER_NAME = "callerName"
        private const val FIELD_CALLER_NUMBER = "callerNumber"
        private const val FIELD_MESSAGE = "message"
        private const val FIELD_MEDIA_KEY = "mediaKey"

        fun toJson(actions: List<Action>): String =
            JSONArray().apply { actions.forEach { put(it.toJsonObject()) } }.toString()

        /**
         * Serializes the per-click-count map as one JSON object keyed by the
         * click count, so [Settings.clickActions] stays a single preference key.
         */
        fun clickActionsToJson(clickActions: Map<Int, Action>): String =
            JSONObject().apply {
                clickActions.forEach { (clickCount, action) ->
                    put(clickCount.toString(), action.toJsonObject())
                }
            }.toString()

        /**
         * Parses a click-actions object written by [clickActionsToJson]. An
         * entry with a key outside 1..3 or an unreadable action is skipped and
         * logged instead of failing the whole map. Returns null when [text] is
         * not a JSON object at all, so a caller validating a backup can reject
         * it rather than silently treating it as no click actions.
         */
        fun parseClickActionsOrNull(text: String): Map<Int, Action>? {
            val json = try {
                JSONObject(text)
            } catch (exception: JSONException) {
                Log.w(TAG, "Discarding malformed click actions JSON: $exception")
                return null
            }
            val clickActions = mutableMapOf<Int, Action>()
            for (key in json.keys()) {
                val clickCount = key.toIntOrNull()
                if (clickCount == null || clickCount !in MIN_CLICK_COUNT..MAX_CLICK_COUNT) {
                    Log.w(TAG, "Skipping click action '$key': not a click count")
                    continue
                }
                val actionJson = json.optJSONObject(key)
                if (actionJson == null) {
                    Log.w(TAG, "Skipping click action $clickCount: not a JSON object")
                    continue
                }
                actionFromJson(actionJson, clickCount)?.let { clickActions[clickCount] = it }
            }
            return clickActions
        }

        /**
         * Parses a JSON array written by [toJson]. An entry the current build
         * cannot understand is skipped and logged instead of failing the whole
         * list, so a stored action with an unknown type never makes reading
         * [Settings.actions] throw. Returns null when [text] is not a JSON
         * array at all, so a caller validating a backup can reject it rather
         * than silently treating it as an empty list.
         */
        fun parseJsonOrNull(text: String): List<Action>? {
            val array = try {
                JSONArray(text)
            } catch (exception: JSONException) {
                Log.w(TAG, "Discarding malformed actions JSON: $exception")
                return null
            }
            val actions = mutableListOf<Action>()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index)
                if (json == null) {
                    Log.w(TAG, "Skipping action entry $index: not a JSON object")
                    continue
                }
                actionFromJson(json, index)?.let { actions.add(it) }
            }
            return actions
        }

        /** Parses a JSON array written by [toJson]; malformed text reads as none. */
        fun fromJson(text: String): List<Action> = parseJsonOrNull(text) ?: emptyList()

        private fun actionFromJson(json: JSONObject, index: Int): Action? {
            val id = json.opt(FIELD_ID) as? String
            if (id.isNullOrEmpty()) {
                Log.w(TAG, "Skipping action entry $index: missing id")
                return null
            }
            val typeName = json.opt(FIELD_TYPE) as? String
            val type = ActionType.entries.firstOrNull { it.name == typeName }
            if (type == null) {
                Log.w(TAG, "Skipping action $id: unknown type '$typeName'")
                return null
            }
            val enabled = json.opt(FIELD_ENABLED)
            val pattern = json.opt(FIELD_PATTERN)
            val stop = json.opt(FIELD_STOP) ?: json.opt(FIELD_LEGACY_SUPPRESS_WEBHOOK)
            val webhookUrl = if (json.has(FIELD_WEBHOOK_URL)) json.opt(FIELD_WEBHOOK_URL) else ""
            val webhookBodyTemplate =
                if (json.has(FIELD_WEBHOOK_BODY_TEMPLATE)) json.opt(FIELD_WEBHOOK_BODY_TEMPLATE) else ""
            val callerName = if (json.has(FIELD_CALLER_NAME)) json.opt(FIELD_CALLER_NAME) else ""
            val callerNumber = if (json.has(FIELD_CALLER_NUMBER)) json.opt(FIELD_CALLER_NUMBER) else ""
            val message = if (json.has(FIELD_MESSAGE)) json.opt(FIELD_MESSAGE) else ""
            if (enabled !is Boolean || pattern !is String || stop !is Boolean ||
                webhookUrl !is String || webhookBodyTemplate !is String ||
                callerName !is String || callerNumber !is String || message !is String
            ) {
                Log.w(TAG, "Skipping action $id: malformed field")
                return null
            }
            // An absent key keeps the default so builds before MEDIA_KEY stored
            // actions without it; a present but unknown name is malformed.
            val mediaKey = if (json.has(FIELD_MEDIA_KEY)) {
                val mediaKeyName = json.opt(FIELD_MEDIA_KEY)
                if (mediaKeyName is String) {
                    MediaKey.entries.firstOrNull { it.name == mediaKeyName }
                } else {
                    null
                }
            } else {
                MediaKey.PLAY_PAUSE
            }
            if (mediaKey == null) {
                Log.w(TAG, "Skipping action $id: unknown media key")
                return null
            }
            return Action(
                id = id,
                enabled = enabled,
                type = type,
                pattern = pattern,
                stop = stop,
                webhookUrl = webhookUrl,
                webhookBodyTemplate = webhookBodyTemplate,
                callerName = callerName,
                callerNumber = callerNumber,
                message = message,
                mediaKey = mediaKey,
            )
        }
    }
}
