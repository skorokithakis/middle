package com.middle.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SpeechEndpointerTest {

    @Test
    fun stopsAndSavesAfterSpeechThenSilence() {
        // The first chunk holds three speech frames; the silence frame that
        // ends the capture arrives in the next chunk.
        val endpointer = SpeechEndpointer(FakeClassifier(true, true, true, false))

        assertEquals(SpeechEndpointer.Decision.CONTINUE, endpointer.onChunk(chunk(3200)))
        assertEquals(SpeechEndpointer.Decision.STOP_SAVE, endpointer.onChunk(chunk(3200)))
    }

    @Test
    fun discardsWhenNoSpeechWithinFiveSeconds() {
        val endpointer = SpeechEndpointer(ConstantClassifier(isSpeech = false))

        // 5s at 16kHz is 80_000 samples, or exactly 50 chunks of 100ms.
        repeat(49) {
            assertEquals(SpeechEndpointer.Decision.CONTINUE, endpointer.onChunk(chunk(3200)))
        }
        assertEquals(SpeechEndpointer.Decision.STOP_DISCARD, endpointer.onChunk(chunk(3200)))
    }

    @Test
    fun keepsGoingWhileSpeechContinues() {
        val endpointer = SpeechEndpointer(ConstantClassifier(isSpeech = true))

        // Six seconds of speech is past the no-speech timeout and must not stop.
        repeat(60) {
            assertEquals(SpeechEndpointer.Decision.CONTINUE, endpointer.onChunk(chunk(3200)))
        }
    }

    @Test
    fun reframesArbitraryChunksIntoExactlyOneKilobyteFrames() {
        val classifier = RecordingClassifier()
        val endpointer = SpeechEndpointer(classifier)

        // The first three chunks are not multiples of the frame size, so frames
        // have to span chunk boundaries. The last is the real recorder size.
        endpointer.onChunk(chunk(700))
        endpointer.onChunk(chunk(700))
        endpointer.onChunk(chunk(648))
        endpointer.onChunk(chunk(3200))

        // 2048 + 3200 = 5248 bytes is five whole frames with 128 bytes carried.
        assertEquals(5, classifier.frames.size)
        assertTrue(
            "expected only ${SpeechEndpointer.FRAME_BYTES}-byte frames",
            classifier.frames.all { it.size == SpeechEndpointer.FRAME_BYTES },
        )
    }

    @Test
    fun closeClosesTheClassifier() {
        val classifier = RecordingClassifier()

        SpeechEndpointer(classifier).close()

        assertTrue("close must release the model", classifier.closed)
    }

    /**
     * PhoneRecorder releases the mic immediately after closing the endpointer,
     * so a classifier whose close() fails must not throw past it.
     */
    @Test
    fun closeDoesNotPropagateClassifierCloseFailure() {
        val classifier = RecordingClassifier(closeException = IOException("model close failed"))

        SpeechEndpointer(classifier).close()
    }

    private fun chunk(size: Int) = ByteArray(size)

    private class FakeClassifier(vararg results: Boolean) : SpeechEndpointer.FrameClassifier {
        private val queue = ArrayDeque(results.toList())

        override fun isSpeech(frame: ByteArray): Boolean = queue.removeFirst()
    }

    private class ConstantClassifier(private val isSpeech: Boolean) :
        SpeechEndpointer.FrameClassifier {

        override fun isSpeech(frame: ByteArray): Boolean = isSpeech
    }

    private class RecordingClassifier(private val closeException: IOException? = null) :
        SpeechEndpointer.FrameClassifier {
        val frames = mutableListOf<ByteArray>()
        var closed = false

        override fun isSpeech(frame: ByteArray): Boolean {
            frames += frame
            return false
        }

        override fun close() {
            closed = true
            closeException?.let { throw it }
        }
    }
}
