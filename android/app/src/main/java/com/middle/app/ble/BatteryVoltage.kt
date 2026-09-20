package com.middle.app.ble

import com.middle.app.data.Settings

/** Shown when a device's battery reading is unknown. */
const val UNKNOWN_BATTERY_VOLTAGE = "N/A"

/** The battery-reading key of the single pendant. */
const val PENDANT_DEVICE_KEY = "pendant"

private const val RING_DEVICE_KEY_PREFIX = "ring:"

/**
 * Identifies a device for battery readings: the pendant, or a ring tagged with
 * its address so two rings do not share a value.
 */
fun batteryDeviceKey(deviceType: String, ringAddress: String): String =
    if (deviceType == Settings.DEVICE_TYPE_RING) {
        "$RING_DEVICE_KEY_PREFIX$ringAddress"
    } else {
        PENDANT_DEVICE_KEY
    }

/** Formats a milli-volt reading the way the status bar shows it. */
fun formatBatteryVoltage(millivolts: Int): String = "%.2fV".format(millivolts / 1000.0)

/**
 * Formats a ring transfer reading. A ring reading can be null or zero; neither
 * is a voltage, so both return null instead of "0.00V".
 *
 * The pendant path deliberately does not use this: a non-null pendant 0mV is a
 * real reading that keeps its existing 0.00V display and alert.
 */
fun formatRingBatteryVoltage(millivolts: Int?): String? =
    millivolts?.takeIf { it > 0 }?.let(::formatBatteryVoltage)

/**
 * The battery value the status bar should show for the selected device.
 *
 * [select] gates publication: a reading for a device that is not selected is
 * ignored, and selecting a device shows that device's own last reading instead
 * of whatever was displayed before. That is what stops a late reading from a
 * just-deselected device reaching the bar.
 */
class BatteryVoltageTracker {

    private val readings = mutableMapOf<String, String>()
    private var selectedKey: String? = null

    /** Selects [deviceKey], returning that device's own reading or [fallback]. */
    fun select(deviceKey: String, fallback: String = UNKNOWN_BATTERY_VOLTAGE): String {
        selectedKey = deviceKey
        return readings[deviceKey] ?: fallback
    }

    /** Returns the value to display for a ring reading, or null to leave it. */
    fun reportRing(deviceKey: String, millivolts: Int?): String? {
        val text = formatRingBatteryVoltage(millivolts) ?: return null
        return record(deviceKey, text)
    }

    /** Returns the value to display for a pendant reading, or null to leave it. */
    fun reportPendant(deviceKey: String, millivolts: Int): String? =
        record(deviceKey, formatBatteryVoltage(millivolts))

    /** Drops the device's reading; returns the value to display, or null to leave it. */
    fun reportUnavailable(deviceKey: String): String? {
        if (deviceKey != selectedKey) return null
        readings.remove(deviceKey)
        return UNKNOWN_BATTERY_VOLTAGE
    }

    private fun record(deviceKey: String, text: String): String? {
        if (deviceKey != selectedKey) return null
        readings[deviceKey] = text
        return text
    }
}
