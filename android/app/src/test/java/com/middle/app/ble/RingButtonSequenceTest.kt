package com.middle.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingButtonSequenceTest {

    @Test
    fun singleDoubleAndTripleClicksAreCounted() {
        assertEquals(1, parseRingButtonClickCount("short "))
        assertEquals(2, parseRingButtonClickCount("short short "))
        assertEquals(3, parseRingButtonClickCount("short short short "))
    }

    @Test
    fun sequencesContainingALongPressAreNotClicks() {
        assertNull(parseRingButtonClickCount("long "))
        assertNull(parseRingButtonClickCount("short long "))
        assertNull(parseRingButtonClickCount("short short long "))
    }

    @Test
    fun countsOutsideOneToThreeAreNotClicks() {
        assertNull(parseRingButtonClickCount(""))
        assertNull(parseRingButtonClickCount("   "))
        assertNull(parseRingButtonClickCount("short short short short "))
    }

    @Test
    fun surroundingAndRepeatedSpacesAreTolerated() {
        assertEquals(2, parseRingButtonClickCount(" short   short "))
    }
}
