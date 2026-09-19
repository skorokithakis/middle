package com.middle.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

class ActionRunnerTest {

    @Test
    fun allDayEventTimesUseUtcMidnightOfTheDateAndNextDate() {
        val previousZone = TimeZone.getDefault()
        // A non-UTC default proves the all-day bounds do not follow the device
        // timezone, which is the whole point of using UTC midnights here.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        try {
            // The parsed time of day is ignored: only the date matters.
            val times = allDayEventTimes(LocalDateTime.of(2026, 9, 21, 9, 15))

            assertEquals(1_789_948_800_000L, times.startMillis) // 2026-09-21T00:00Z
            assertEquals(1_790_035_200_000L, times.endMillis) // 2026-09-22T00:00Z
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun timedEventTimesDefaultTheEndToThirtyMinutesLater() {
        val times = timedEventTimes(
            start = LocalDateTime.of(2026, 9, 21, 7, 30),
            end = null,
            zone = ZoneId.of("Europe/Athens"),
        )

        assertEquals(1_789_965_000_000L, times.startMillis) // 07:30+03:00
        assertEquals(1_789_966_800_000L, times.endMillis) // 08:00+03:00
    }

    @Test
    fun timedEventTimesUseTheGivenEnd() {
        val times = timedEventTimes(
            start = LocalDateTime.of(2026, 9, 21, 7, 30),
            end = LocalDateTime.of(2026, 9, 21, 9, 0),
            zone = ZoneId.of("Europe/Athens"),
        )

        assertEquals(1_789_970_400_000L, times.endMillis) // 09:00+03:00
    }

    @Test
    fun alarmLimitIsStrictlyMoreThanTwentyFourHours() {
        val now = ZonedDateTime.of(2026, 9, 21, 7, 0, 0, 0, ZoneId.of("Europe/Athens"))

        assertFalse(isMoreThanOneDayAhead(now.plusHours(23), now))
        assertFalse(isMoreThanOneDayAhead(now.plusHours(24), now))
        assertTrue(isMoreThanOneDayAhead(now.plusHours(25), now))
    }

    @Test
    fun reminderLabelsUseTheDayAbbreviation() {
        val start = LocalDateTime.of(2026, 9, 21, 7, 30)

        assertEquals("Mon 07:30", reminderTimeLabel(allDay = false, start = start, locale = Locale.US))
        assertEquals("Mon", reminderTimeLabel(allDay = true, start = start, locale = Locale.US))
    }
}
