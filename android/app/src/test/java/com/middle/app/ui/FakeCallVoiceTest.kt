package com.middle.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeCallVoiceTest {

    private fun voice(
        name: String,
        language: String = "en",
        networkRequired: Boolean = false,
        notInstalled: Boolean = false,
    ) = VoiceCandidate(
        name = name,
        language = language,
        networkRequired = networkRequired,
        notInstalled = notInstalled,
    )

    @Test
    fun installedVoicesKeepTheDeviceLanguageOnly() {
        val candidates = listOf(
            voice("en-us-1"),
            voice("fr-fr-1", language = "fr"),
            voice("en-gb-1"),
        )

        assertEquals(listOf("en-gb-1", "en-us-1"), installedVoiceNames(candidates, "en"))
    }

    @Test
    fun networkAndNotInstalledVoicesAreLeftOut() {
        val candidates = listOf(
            voice("local"),
            voice("network", networkRequired = true),
            voice("missing", notInstalled = true),
        )

        assertEquals(listOf("local"), installedVoiceNames(candidates, "en"))
    }

    @Test
    fun optionsStartWithDefaultThenTheInstalledVoices() {
        assertEquals(
            listOf("", "en-gb-1", "en-us-1"),
            fakeCallVoiceOptions(listOf("en-gb-1", "en-us-1"), saved = ""),
        )
    }

    @Test
    fun aSavedVoiceThatIsNotInstalledStaysVisible() {
        assertEquals(
            listOf("", "en-us-1", "en-au-9"),
            fakeCallVoiceOptions(listOf("en-us-1"), saved = "en-au-9"),
        )
    }

    @Test
    fun aSavedInstalledVoiceIsNotDuplicated() {
        assertEquals(
            listOf("", "en-us-1"),
            fakeCallVoiceOptions(listOf("en-us-1"), saved = "en-us-1"),
        )
    }
}
