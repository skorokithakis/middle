package com.middle.app.data

/** An hour/minute parsed out of a transcript after an action pattern matched. */
data class AlarmTime(val hour: Int, val minute: Int)

/** Outcome of matching one enabled action against a transcript. */
sealed class ActionMatch {
    data class Alarm(val action: Action, val time: AlarmTime) : ActionMatch()

    data class NoTime(val action: Action) : ActionMatch()
}

/**
 * Result of running every action against a transcript, in input order.
 * [invalid] holds enabled actions whose pattern could not be compiled so the
 * caller can log them; they never match.
 */
data class ActionResult(
    val fired: List<ActionMatch.Alarm>,
    val noTime: List<ActionMatch.NoTime>,
    val suppressWebhook: Boolean,
    val invalid: List<Action>,
)

/**
 * Decides which actions fire on a transcript. Deliberately free of Android
 * imports so the rules can be unit tested directly. Alarms are only resolved to
 * an hour/minute; rolling a past time to tomorrow is left to the clock app.
 */
object ActionMatcher {

    // IGNORE_CASE so '7 AM', '7am' and '7 a.m.' all parse. The digit and colon
    // boundaries stop a longer number ("123") or a malformed colon time
    // ("7:3", "7:300") from being read as a time.
    private val TIME_REGEX = Regex(
        """(?<![\d:])(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?!(?:\d|:))""",
        RegexOption.IGNORE_CASE,
    )

    fun evaluate(transcript: String, actions: List<Action>): ActionResult {
        val fired = mutableListOf<ActionMatch.Alarm>()
        val noTime = mutableListOf<ActionMatch.NoTime>()
        val invalid = mutableListOf<Action>()

        for (action in actions) {
            if (!action.enabled) continue

            val pattern = try {
                Regex(action.pattern, RegexOption.IGNORE_CASE)
            } catch (exception: IllegalArgumentException) {
                // Invalid regex is a per-action problem, not a fatal one.
                invalid.add(action)
                continue
            }

            // The time is everything after the pattern match, so a number that
            // appears before the trigger phrase is not treated as the alarm.
            val match = pattern.find(transcript) ?: continue
            val time = parseTime(transcript.substring(match.range.last + 1))
            if (time == null) {
                noTime.add(ActionMatch.NoTime(action))
            } else {
                fired.add(ActionMatch.Alarm(action, time))
            }
        }

        return ActionResult(
            fired = fired,
            noTime = noTime,
            // Only a fired alarm can suppress the webhook; a no-time match
            // must still let the transcript through.
            suppressWebhook = fired.any { it.action.suppressWebhook },
            invalid = invalid,
        )
    }

    private fun parseTime(text: String): AlarmTime? {
        val match = TIME_REGEX.find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].lowercase().replace(".", "")
        if (minute !in 0..59) return null
        return when (meridiem) {
            // Bare hour is 24-hour: '7' -> 07:00, '19' -> 19:00.
            "" -> hour.takeIf { it in 0..23 }?.let { AlarmTime(it, minute) }
            "am" -> hour.takeIf { it in 1..12 }?.let { AlarmTime(it % 12, minute) }
            "pm" -> hour.takeIf { it in 1..12 }?.let { AlarmTime((it % 12) + 12, minute) }
            else -> null
        }
    }
}
