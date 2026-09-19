package com.middle.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ActionType {
    ALARM,
}

/**
 * A user-defined action that runs against a transcript after transcription.
 * The whole list is persisted as one JSON array string in [Settings].
 */
data class Action(
    val id: String,
    val enabled: Boolean,
    val type: ActionType,
    val pattern: String,
    val suppressWebhook: Boolean,
) {
    private fun toJsonObject(): JSONObject = JSONObject().apply {
        put(FIELD_ID, id)
        put(FIELD_ENABLED, enabled)
        put(FIELD_TYPE, type.name)
        put(FIELD_PATTERN, pattern)
        put(FIELD_SUPPRESS_WEBHOOK, suppressWebhook)
    }

    companion object {
        // The matcher compiles patterns with IGNORE_CASE, so the source is
        // stored as written rather than baked into a Regex here.
        const val DEFAULT_ALARM_PATTERN = """\bset (an? )?alarm (for|at)\b"""

        private const val TAG = "Action"
        private const val FIELD_ID = "id"
        private const val FIELD_ENABLED = "enabled"
        private const val FIELD_TYPE = "type"
        private const val FIELD_PATTERN = "pattern"
        private const val FIELD_SUPPRESS_WEBHOOK = "suppressWebhook"

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
            val suppressWebhook = json.opt(FIELD_SUPPRESS_WEBHOOK)
            if (enabled !is Boolean || pattern !is String || suppressWebhook !is Boolean) {
                Log.w(TAG, "Skipping action $id: malformed field")
                return null
            }
            return Action(
                id = id,
                enabled = enabled,
                type = type,
                pattern = pattern,
                suppressWebhook = suppressWebhook,
            )
        }
    }
}
