package com.middle.app.ble

import com.middle.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryVoltageTest {

    private val ringA = batteryDeviceKey(Settings.DEVICE_TYPE_RING, "AA:BB")
    private val ringB = batteryDeviceKey(Settings.DEVICE_TYPE_RING, "CC:DD")

    @Test
    fun pendantAndTwoRingsGetDifferentDeviceKeys() {
        val pendant = batteryDeviceKey(Settings.DEVICE_TYPE_PENDANT, "")
        assertNotEquals(pendant, ringA)
        assertNotEquals(ringA, ringB)
    }

    @Test
    fun formatsEveryNonNullPendantReadingIncludingZero() {
        assertEquals("0.00V", formatBatteryVoltage(0))
        assertEquals("0.50V", formatBatteryVoltage(500))
        assertEquals("3.85V", formatBatteryVoltage(3850))
        assertEquals("4.05V", formatBatteryVoltage(4050))
    }

    @Test
    fun ringMissingMetadataIsNotZero() {
        assertNull(formatRingBatteryVoltage(null))
        assertNull(formatRingBatteryVoltage(0))
        assertEquals("0.50V", formatRingBatteryVoltage(500))
        assertEquals("4.05V", formatRingBatteryVoltage(4050))
    }

    @Test
    fun switchingRingsResetsTheShownValue() {
        val tracker = BatteryVoltageTracker()
        tracker.select(ringA)
        assertEquals("4.00V", tracker.reportRing(ringA, 4000))

        assertEquals(UNKNOWN_BATTERY_VOLTAGE, tracker.select(ringB))
    }

    @Test
    fun lateReadingFromTheDeselectedRingIsIgnoredAndTheNewOneIsAccepted() {
        val tracker = BatteryVoltageTracker()
        tracker.select(ringA)
        tracker.reportRing(ringA, 4000)
        tracker.select(ringB)

        assertNull(tracker.reportRing(ringA, 3900))
        assertEquals("3.90V", tracker.reportRing(ringB, 3900))
    }

    @Test
    fun switchingBetweenPendantAndRingDoesNotCarryReadings() {
        val tracker = BatteryVoltageTracker()
        tracker.select(PENDANT_DEVICE_KEY)
        assertEquals("4.05V", tracker.reportPendant(PENDANT_DEVICE_KEY, 4050))

        assertEquals(UNKNOWN_BATTERY_VOLTAGE, tracker.select(ringA))
        assertNull(tracker.reportPendant(PENDANT_DEVICE_KEY, 4000))
        // Switching back shows the pendant's own reading, not the ring's.
        assertEquals("4.05V", tracker.select(PENDANT_DEVICE_KEY))
    }

    @Test
    fun missingRingMetadataLeavesTheLastRingReadingShown() {
        val tracker = BatteryVoltageTracker()
        tracker.select(ringA)
        assertEquals("4.00V", tracker.reportRing(ringA, 4000))

        assertNull(tracker.reportRing(ringA, null))
        assertNull(tracker.reportRing(ringA, 0))
        assertEquals("4.00V", tracker.select(ringA))
    }

    @Test
    fun pendantZeroMillivoltsIsPublished() {
        val tracker = BatteryVoltageTracker()
        tracker.select(PENDANT_DEVICE_KEY)

        assertEquals("0.00V", tracker.reportPendant(PENDANT_DEVICE_KEY, 0))
    }

    @Test
    fun unavailablePendantReadingResetsToUnknown() {
        val tracker = BatteryVoltageTracker()
        tracker.select(PENDANT_DEVICE_KEY)
        tracker.reportPendant(PENDANT_DEVICE_KEY, 4050)

        assertEquals(UNKNOWN_BATTERY_VOLTAGE, tracker.reportUnavailable(PENDANT_DEVICE_KEY))
        assertEquals("4.00V", tracker.reportPendant(PENDANT_DEVICE_KEY, 4000))
    }
}
