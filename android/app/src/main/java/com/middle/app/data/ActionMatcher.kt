package com.middle.app.data

/**
 * One enabled action matched against a transcript.
 *
 * [rest] is the transcript after the matched pattern, trimmed. A pattern that
 * consumes the whole transcript (notably the `.*` catch-all) has no text after
 * it, so there [rest] is the empty string.
 *
 * [index] is the hit's position in the `actions` list it was planned from. A
 * caller that needs to resume after a hit must use this rather than look the id
 * up, because ids are not guaranteed unique (imported backups can duplicate
 * them).
 */
data class ActionHit(val action: Action, val rest: String, val index: Int)

/**
 * Outcome of planning a transcript through an ordered action list.
 *
 * [hits] holds the matched actions in list order, up to and including the first
 * hit whose action has `stop = true`. [invalid] holds enabled actions whose
 * pattern could not be compiled so the caller can log them; they never match.
 */
data class MatchPlan(val hits: List<ActionHit>, val invalid: List<Action>)

/**
 * Decides which actions match a transcript. Deliberately free of Android
 * imports so the rules can be unit tested directly. No time is parsed here:
 * interpreting an ALARM/CALENDAR hit is the runner's job.
 *
 * `stop` is applied optimistically: planning stops at the first hit flagged
 * `stop`. A runner may later find an ALARM/CALENDAR hit produced nothing (no
 * time, LLM failure) and must then continue with the actions after that hit.
 * To do that it resumes with [planFrom], passing [ActionHit.index] plus one.
 * [plan] is just `planFrom(transcript, actions, 0)`.
 */
object ActionMatcher {

    fun plan(transcript: String, actions: List<Action>): MatchPlan =
        planFrom(transcript, actions, 0)

    /**
     * The trimmed text after [pattern]'s first match in [transcript], or null
     * when the pattern is invalid or does not match. A single-action re-match
     * for callers that already decided the action ran, so `stop` semantics are
     * not reapplied.
     */
    fun restFor(pattern: String, transcript: String): String? {
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (exception: IllegalArgumentException) {
            return null
        }
        val match = regex.find(transcript) ?: return null
        return restAfter(transcript, match)
    }

    fun planFrom(transcript: String, actions: List<Action>, startIndex: Int): MatchPlan {
        val hits = mutableListOf<ActionHit>()
        val invalid = mutableListOf<Action>()

        for (index in startIndex until actions.size) {
            val action = actions[index]
            if (!action.enabled) continue

            val pattern = try {
                Regex(action.pattern, RegexOption.IGNORE_CASE)
            } catch (exception: IllegalArgumentException) {
                // Invalid regex is a per-action problem, not a fatal one.
                invalid.add(action)
                continue
            }

            val match = pattern.find(transcript) ?: continue
            hits.add(ActionHit(action, restAfter(transcript, match), index))
            if (action.stop) break
        }

        return MatchPlan(hits = hits, invalid = invalid)
    }

    private fun restAfter(transcript: String, match: MatchResult): String =
        transcript.substring(match.range.last + 1).trim()
}
