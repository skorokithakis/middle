package com.middle.app.audio

import android.util.Log
import java.io.Closeable

/**
 * Decides when a stream of PCM16 chunks no longer contains speech.
 *
 * [PhoneRecorder] reads 3200-byte chunks, but the classifier needs fixed
 * 1024-byte frames, so chunks are reframed here with a carry buffer.
 *
 * The speech/silence hysteresis lives in the classifier (the Silero model's
 * silence duration), so this only has to watch for the first speech frame and
 * the later silence. Timing is derived from sample counts rather than the wall
 * clock, which makes a decision for a given sequence of chunks deterministic
 * and testable.
 */
class SpeechEndpointer(
    private val classifier: FrameClassifier,
    sampleRate: Int = PhoneRecorder.SAMPLE_RATE,
    noSpeechTimeoutMillis: Long = NO_SPEECH_TIMEOUT_MILLIS,
) : Closeable {

    /**
     * Classifies one fixed [FRAME_BYTES]-byte PCM16 frame as speech or silence.
     * The Silero adapter loads a model worth releasing; classifiers without
     * resources keep the default no-op [close].
     */
    fun interface FrameClassifier : Closeable {
        fun isSpeech(frame: ByteArray): Boolean

        override fun close() {}
    }

    /** What the caller should do after feeding a chunk. */
    enum class Decision {
        CONTINUE,
        STOP_SAVE,
        STOP_DISCARD,
    }

    // Feed and close can race when a capture is released without joining the
    // read loop, so they share a lock and close is idempotent.
    private val lock = Any()
    private var closed = false

    private val pending = ByteArray(FRAME_BYTES)
    private var pendingBytes = 0

    private var samplesSeen = 0L
    private var speechHeard = false

    private val noSpeechSamples = sampleRate.toLong() * noSpeechTimeoutMillis / 1000L

    /**
     * Feeds [length] bytes of [chunk]. Returns [Decision.STOP_SAVE] once speech
     * has been heard and a later frame is silence, or [Decision.STOP_DISCARD]
     * when no speech was heard within [noSpeechTimeoutMillis].
     */
    fun onChunk(chunk: ByteArray, length: Int = chunk.size): Decision = synchronized(lock) {
        if (closed) return Decision.CONTINUE
        require(length in 0..chunk.size) { "length $length is outside chunk size ${chunk.size}" }

        samplesSeen += length / 2
        var offset = 0
        while (offset < length) {
            val copied = minOf(FRAME_BYTES - pendingBytes, length - offset)
            System.arraycopy(chunk, offset, pending, pendingBytes, copied)
            pendingBytes += copied
            offset += copied
            if (pendingBytes < FRAME_BYTES) continue

            // Copy because the classifier may retain the frame, and [pending]
            // is overwritten by the next frame.
            val speech = classifier.isSpeech(pending.copyOf())
            pendingBytes = 0
            if (speech) {
                speechHeard = true
            } else if (speechHeard) {
                return Decision.STOP_SAVE
            }
        }

        if (!speechHeard && samplesSeen >= noSpeechSamples) {
            return Decision.STOP_DISCARD
        }
        return Decision.CONTINUE
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                classifier.close()
            } catch (exception: Exception) {
                // PhoneRecorder releases the mic right after this, so a
                // classifier that cannot close must not throw past it.
                Log.w(TAG, "Could not close frame classifier.", exception)
            }
        }
    }

    companion object {
        private const val TAG = "SpeechEndpointer"

        /** 512 samples of PCM16; the frame size Silero is configured with. */
        const val FRAME_BYTES = 1024

        const val NO_SPEECH_TIMEOUT_MILLIS = 5_000L
    }
}
