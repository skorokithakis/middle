package com.middle.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionMatcherTest {

    private val alarm = Action(
        id = "a1",
        enabled = true,
        type = ActionType.ALARM,
        pattern = Action.DEFAULT_ALARM_PATTERN,
        suppressWebhook = false,
    )

    private fun result(transcript: String, vararg actions: Action): ActionResult =
        ActionMatcher.evaluate(transcript, actions.toList())

    private fun time(transcript: String): AlarmTime? =
        result(transcript, alarm).fired.singleOrNull()?.time

    @Test
    fun parsesBareHourAsTwentyFourHour() {
        assertEquals(AlarmTime(7, 0), time("Set alarm for 7"))
        assertEquals(AlarmTime(19, 0), time("Set alarm for 19"))
    }

    @Test
    fun parsesHourWithAm() {
        assertEquals(AlarmTime(7, 0), time("Set alarm for 7am"))
        assertEquals(AlarmTime(7, 0), time("Set alarm for 7 a.m."))
        assertEquals(AlarmTime(7, 0), time("Set alarm for 7 AM"))
    }

    @Test
    fun parsesHourWithPm() {
        assertEquals(AlarmTime(19, 0), time("Set alarm for 7pm"))
        assertEquals(AlarmTime(19, 0), time("Set alarm for 7 p.m."))
    }

    @Test
    fun parsesHourAndMinute() {
        assertEquals(AlarmTime(7, 30), time("Set alarm for 7:30"))
        assertEquals(AlarmTime(19, 30), time("Set alarm for 7:30 pm"))
    }

    @Test
    fun parsesTwentyFourHourWithMinutes() {
        assertEquals(AlarmTime(19, 30), time("Set alarm for 19:30"))
    }

    @Test
    fun twelveHourMeridiemBoundaries() {
        assertEquals(AlarmTime(0, 0), time("Set alarm for 12am"))
        assertEquals(AlarmTime(12, 0), time("Set alarm for 12pm"))
    }

    @Test
    fun outOfRangeHourOrMinuteHasNoTime() {
        assertTrue(result("Set alarm for 25:00", alarm).fired.isEmpty())
        assertTrue(result("Set alarm for 7:99", alarm).fired.isEmpty())
        assertTrue(result("Set alarm for 13pm", alarm).fired.isEmpty())
        assertEquals(1, result("Set alarm for 25:00", alarm).noTime.size)
    }

    @Test
    fun malformedColonTimeHasNoTime() {
        val suppressor = alarm.copy(suppressWebhook = true)
        for (transcript in listOf("Set alarm for 7:3", "Set alarm for 7:300")) {
            val result = result(transcript, suppressor)
            assertTrue(result.fired.isEmpty())
            assertEquals(listOf("a1"), result.noTime.map { it.action.id })
            assertFalse(result.suppressWebhook)
        }
    }

    @Test
    fun textBeforeThePatternIsNotParsedAsTime() {
        // "20" precedes the trigger; the time is the trailing "7".
        assertEquals(AlarmTime(7, 0), time("20 minutes pass, set alarm for 7"))
    }

    @Test
    fun noParseableTimeGoesToNoTime() {
        val result = result("Set alarm for soon", alarm)
        assertTrue(result.fired.isEmpty())
        assertEquals(listOf("a1"), result.noTime.map { it.action.id })
        assertFalse(result.suppressWebhook)
    }

    @Test
    fun nonMatchingTranscriptDoesNothing() {
        val result = result("Remind me to buy milk", alarm)
        assertTrue(result.fired.isEmpty())
        assertTrue(result.noTime.isEmpty())
    }

    @Test
    fun disabledActionsAreIgnored() {
        val result = result("Set alarm for 7", alarm.copy(enabled = false))
        assertTrue(result.fired.isEmpty())
        assertTrue(result.noTime.isEmpty())
        assertTrue(result.invalid.isEmpty())
    }

    @Test
    fun invalidRegexIsReportedAndNeverCrashes() {
        val broken = alarm.copy(id = "bad", pattern = "([")
        val result = result("Set alarm for 7", broken)
        assertTrue(result.fired.isEmpty())
        assertEquals(listOf("bad"), result.invalid.map { it.id })
    }

    @Test
    fun suppressWebhookIsSetByFiredActionOnly() {
        val suppressor = alarm.copy(suppressWebhook = true)
        assertTrue(result("Set alarm for 7", suppressor).suppressWebhook)
        assertTrue(result("Set alarm for 7", alarm).suppressWebhook.not())
        // A no-time match must not suppress even when the action is flagged.
        assertFalse(result("Set alarm for soon", suppressor).suppressWebhook)
    }

    @Test
    fun capitalsAndPunctuationAreHandled() {
        val result = result("SET ALARM AT 7 A.M.!", alarm)
        assertEquals(AlarmTime(7, 0), result.fired.single().time)
    }

    @Test
    fun resultsPreserveInputOrderAcrossActions() {
        val second = alarm.copy(id = "a2", suppressWebhook = true)
        val result = result("Set alarm for 7", alarm, second)
        assertEquals(listOf("a1", "a2"), result.fired.map { it.action.id })
        assertTrue(result.suppressWebhook)
    }
}
