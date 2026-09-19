package com.middle.app.data

import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Saves host-captured PCM16 audio as an M4A and queues it for the
 * transcribe-then-webhook pipeline.
 *
 * The synced paths save first and only enqueue once the file exists, so a
 * failed encode never queues a recording that is not on disk; this keeps the
 * same order for the phone microphone path.
 */
class RecordingSaver(
    private val repository: RecordingsRepository,
    private val settings: Settings,
    private val pipelineQueue: PipelineQueue,
) {

    /**
     * Saves [pcm16] (mono signed 16-bit little-endian, at [sampleRate]) as
     * `recording_<yyyyMMdd_HHmmss>_<n>.m4a` and enqueues it when transcription
     * is enabled. The lowest free suffix is used so a phone recording cannot
     * overwrite a pendant or ring file saved in the same second. Returns the
     * saved file, or null when the audio is too short: under a second is a
     * stray tap, not a recording.
     */
    suspend fun save(pcm16: ByteArray, sampleRate: Int): File? {
        if (!hasMinimumDuration(pcm16, sampleRate)) {
            Log.d(TAG, "Discarding ${pcm16.size}-byte recording; shorter than ${MINIMUM_DURATION_SECONDS}s.")
            return null
        }

        val filename = filenameFor(LocalDateTime.now(), repository::recordingExists)
        val file = repository.savePcm16Recording(pcm16, filename, sampleRate)
        if (settings.transcriptionEnabled) {
            pipelineQueue.enqueue(filename)
        }
        return file
    }

    companion object {
        private const val TAG = "RecordingSaver"
        private const val MINIMUM_DURATION_SECONDS = 1
        private const val BYTES_PER_SAMPLE = 2

        // Not SimpleDateFormat: that is not thread-safe and this can be reached
        // from several recorders.
        private val FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /**
         * Picks the lowest suffix whose `recording_<timestamp>_<n>.m4a` is not
         * already taken, starting at 0, so parallel recorders cannot collide.
         */
        fun filenameFor(timestamp: LocalDateTime, exists: (String) -> Boolean = { false }): String {
            val prefix = "recording_${timestamp.format(FILENAME_FORMATTER)}_"
            var suffix = 0
            while (true) {
                val candidate = "$prefix$suffix.m4a"
                if (!exists(candidate)) return candidate
                suffix++
            }
        }

        /** True when the PCM holds at least a second of audio, not a stray tap. */
        fun hasMinimumDuration(pcm16: ByteArray, sampleRate: Int): Boolean =
            pcm16.size >= sampleRate * BYTES_PER_SAMPLE * MINIMUM_DURATION_SECONDS
    }
}
