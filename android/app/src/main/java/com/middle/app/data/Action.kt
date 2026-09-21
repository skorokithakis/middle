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
    }

    companion object {
        // The matcher compiles patterns with IGNORE_CASE, so the source is
        // stored as written rather than baked into a Regex here.
        const val DEFAULT_ALARM_PATTERN = """\bset (an? )?alarm (for|at)\b"""
        const val DEFAULT_CALENDAR_PATTERN = """\b(remind me|add (an? )?(appointment|event))\b"""
        const val DEFAULT_WEBHOOK_PATTERN = ".*"
        const val DEFAULT_FAKE_CALL_PATTERN = """\bfake call\b"""

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

        fun toJson(actions: List<Action>): String =
            JSONArray().apply { actions.forEach { put(it.toJsonObject()) } }.toString()

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
            if (enabled !is Boolean || pattern !is String || stop !is Boolean ||
                webhookUrl !is String || webhookBodyTemplate !is String ||
                callerName !is String || callerNumber !is String
            ) {
                Log.w(TAG, "Skipping action $id: malformed field")
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
            )
        }
    }
}
