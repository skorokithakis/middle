package com.middle.app.ble

/**
 * Parses the ring's space-separated button sequence into a click count.
 *
 * The ring reports one token per press, with a trailing space, e.g. "short "
 * for a single click and "short short " for a double click. Only sequences
 * made entirely of 'short' presses are clicks: a 'long' token means a hold, or
 * the first half of a short-then-long hold-to-record gesture, and neither is a
 * click. Counts outside 1..3 are not click gestures either.
 *
 * Returns the click count, or null when [sequence] is not a plain click.
 */
fun parseRingButtonClickCount(sequence: String): Int? {
    val presses = sequence.trim().split(" ").filter { it.isNotEmpty() }
    if (presses.size !in 1..3) return null
    return if (presses.all { it == "short" }) presses.size else null
}
