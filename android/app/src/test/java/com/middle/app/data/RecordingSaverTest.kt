package com.middle.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class RecordingSaverTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * A phone recording is only visible in the list when its filename matches
     * [Recording.fromFile]'s parse, and the timestamp has to round-trip.
     */
    @Test
    fun phoneFilenameIsParsedBackAsARecording() {
        val timestamp = LocalDateTime.of(2026, 9, 20, 14, 5, 9)
        val filename = RecordingSaver.filenameFor(timestamp)

        assertEquals("recording_20260920_140509_0.m4a", filename)

        val file = temporaryFolder.newFile(filename)
        file.writeBytes(ByteArray(16_000))

        val recording = Recording.fromFile(file)
        assertNotNull("Phone recordings must match the shared filename pattern", recording)
        assertEquals(timestamp, recording!!.timestamp)
    }

    /** A stray tap must not become a recording, but one second must survive. */
    @Test
    fun audioUnderOneSecondIsRejected() {
        assertFalse(RecordingSaver.hasMinimumDuration(ByteArray(31_999), 16_000))
        assertTrue(RecordingSaver.hasMinimumDuration(ByteArray(32_000), 16_000))
    }

    /**
     * A pendant or ring file saved in the same second must not be overwritten,
     * and the chosen suffix still has to match the shared filename pattern.
     */
    @Test
    fun filenameSkipsSuffixesAlreadyOnDisk() {
        val timestamp = LocalDateTime.of(2026, 9, 20, 14, 5, 9)
        val taken = setOf(
            "recording_20260920_140509_0.m4a",
            "recording_20260920_140509_1.m4a",
        )

        val filename = RecordingSaver.filenameFor(timestamp) { it in taken }

        assertEquals("recording_20260920_140509_2.m4a", filename)

        val file = temporaryFolder.newFile(filename)
        assertNotNull(Recording.fromFile(file))
    }
}
