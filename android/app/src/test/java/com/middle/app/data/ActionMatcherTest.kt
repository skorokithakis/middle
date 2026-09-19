package com.middle.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionMatcherTest {

    private val alarm = Action(
        id = "a1",
        enabled = true,
        type = ActionType.ALARM,
        pattern = Action.DEFAULT_ALARM_PATTERN,
        stop = false,
    )

    private fun plan(transcript: String, vararg actions: Action): MatchPlan =
        ActionMatcher.plan(transcript, actions.toList())

    @Test
    fun hitsPreserveActionListOrder() {
        val second = alarm.copy(id = "a2", pattern = "buy milk")
        val result = plan("set an alarm for 7 and buy milk", alarm, second)
        assertEquals(listOf("a1", "a2"), result.hits.map { it.action.id })
    }

    @Test
    fun hitIndexIsThePositionInTheActionList() {
        val second = alarm.copy(id = "a2", pattern = "buy milk")
        val result = plan("set an alarm for 7 and buy milk", alarm, second)
        assertEquals(listOf(0, 1), result.hits.map { it.index })
    }

    @Test
    fun stopHaltsEvaluationOfLaterActions() {
        val stopper = alarm.copy(id = "a1", stop = true)
        val later = alarm.copy(id = "a2", pattern = "buy milk")
        val result = plan("set an alarm for 7 and buy milk", stopper, later)
        assertEquals(listOf("a1"), result.hits.map { it.action.id })
    }

    @Test
    fun nonMatchingActionIsSkipped() {
        assertTrue(plan("buy milk", alarm).hits.isEmpty())
    }

    @Test
    fun disabledActionsAreSkippedWithoutBeingReported() {
        val result = plan("set an alarm", alarm.copy(enabled = false))
        assertTrue(result.hits.isEmpty())
        assertTrue(result.invalid.isEmpty())
    }

    @Test
    fun invalidRegexIsReportedAndLaterActionsStillMatch() {
        val broken = alarm.copy(id = "bad", pattern = "([")
        val good = alarm.copy(id = "good", pattern = "buy milk")
        val result = plan("buy milk", broken, good)
        assertEquals(listOf("bad"), result.invalid.map { it.id })
        assertEquals(listOf("good"), result.hits.map { it.action.id })
    }

    @Test
    fun restIsTextAfterTheMatchTrimmed() {
        val action = alarm.copy(pattern = "note to self")
        val hit = plan("Note to self, buy milk!", action).hits.single()
        assertEquals(", buy milk!", hit.rest)
    }

    @Test
    fun restHandlesCapitalsAndPunctuation() {
        val hit = plan("SET ALARM AT 7 A.M.!", alarm).hits.single()
        assertEquals("7 A.M.!", hit.rest)
    }

    @Test
    fun catchAllLeavesAnEmptyRest() {
        val catchAll = alarm.copy(
            id = "hook",
            type = ActionType.WEBHOOK,
            pattern = Action.DEFAULT_WEBHOOK_PATTERN,
            stop = false,
        )
        val hit = plan("buy milk", catchAll).hits.single()
        assertEquals("", hit.rest)
    }

    @Test
    fun stoppingWebhookHidesLaterCatchAllAndKeepsTheTailAsRest() {
        val note = alarm.copy(
            id = "note",
            type = ActionType.WEBHOOK,
            pattern = "note to self",
            stop = true,
        )
        val catchAll = alarm.copy(
            id = "catch-all",
            type = ActionType.WEBHOOK,
            pattern = Action.DEFAULT_WEBHOOK_PATTERN,
            stop = false,
        )
        val result = plan("note to self buy milk", note, alarm, catchAll)
        assertEquals(listOf("note"), result.hits.map { it.action.id })
        assertEquals("buy milk", result.hits.single().rest)
    }

    @Test
    fun plainTranscriptFallsThroughToTheCatchAllWithEmptyRest() {
        val note = alarm.copy(
            id = "note",
            type = ActionType.WEBHOOK,
            pattern = "note to self",
            stop = true,
        )
        val catchAll = alarm.copy(
            id = "catch-all",
            type = ActionType.WEBHOOK,
            pattern = Action.DEFAULT_WEBHOOK_PATTERN,
            stop = false,
        )
        val result = plan("just a thought", note, alarm, catchAll)
        assertEquals(listOf("catch-all"), result.hits.map { it.action.id })
        assertEquals("", result.hits.single().rest)
    }

    @Test
    fun restForRerunsASinglePatternWithoutStopSemantics() {
        assertEquals("buy milk", ActionMatcher.restFor("note to self", "note to self buy milk"))
        assertEquals("", ActionMatcher.restFor(Action.DEFAULT_WEBHOOK_PATTERN, "anything"))
        assertEquals(null, ActionMatcher.restFor("note to self", "buy milk"))
        assertEquals(null, ActionMatcher.restFor("([", "buy milk"))
    }

    @Test
    fun catchAllHitCanStopLaterActions() {
        val stopper = alarm.copy(id = "a1", pattern = ".*", stop = true)
        val later = alarm.copy(id = "a2", pattern = "buy milk")
        val result = plan("buy milk", stopper, later)
        assertEquals(listOf("a1"), result.hits.map { it.action.id })
    }

    @Test
    fun planFromResumesAfterAFailedStopHit() {
        val stopper = alarm.copy(id = "a1", stop = true)
        val catchAll = alarm.copy(id = "a2", pattern = "buy milk")
        val actions = listOf(stopper, catchAll)

        val first = ActionMatcher.plan("set an alarm for 7 and buy milk", actions)
        assertEquals(listOf("a1"), first.hits.map { it.action.id })

        // The runner found the stop hit produced nothing; resume after it.
        val resumed = ActionMatcher.planFrom(
            "set an alarm for 7 and buy milk",
            actions,
            first.hits.single().index + 1,
        )
        assertEquals(listOf("a2"), resumed.hits.map { it.action.id })
    }

    @Test
    fun planFromResumesByIndexWhenIdsAreDuplicated() {
        // Duplicate ids (an imported backup) must not resolve to the first copy,
        // or a failed stop hit would be re-planned forever.
        val firstStop = alarm.copy(id = "dup", stop = true)
        val secondStop = alarm.copy(id = "dup", stop = true)
        val later = alarm.copy(id = "a3", pattern = "buy milk")
        val actions = listOf(firstStop, secondStop, later)
        val transcript = "set an alarm for 7 and buy milk"

        val first = ActionMatcher.plan(transcript, actions)
        assertEquals(listOf(0), first.hits.map { it.index })

        val afterFirst = ActionMatcher.planFrom(transcript, actions, first.hits.single().index + 1)
        assertEquals(listOf(1), afterFirst.hits.map { it.index })

        val afterSecond = ActionMatcher.planFrom(transcript, actions, afterFirst.hits.single().index + 1)
        assertEquals(listOf("a3"), afterSecond.hits.map { it.action.id })
    }
}
