package com.middle.app.audio

import android.content.Context
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/**
 * Adapts the Silero VAD model to [SpeechEndpointer.FrameClassifier].
 *
 * Loading the model is expensive, so construct one per capture; the
 * [SpeechEndpointer] closes it when capture ends. [VadSilero] applies the
 * speech/silence hysteresis itself, which is why [SpeechEndpointer] only has to
 * watch for a speech-to-silence edge.
 */
class SileroClassifier(context: Context) : SpeechEndpointer.FrameClassifier {

    private val vad = VadSilero(
        context,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.NORMAL,
        silenceDurationMs = SILENCE_DURATION_MILLIS,
        speechDurationMs = SPEECH_DURATION_MILLIS,
    )

    override fun isSpeech(frame: ByteArray): Boolean = vad.isSpeech(frame)

    override fun close() {
        vad.close()
    }

    private companion object {
        const val SILENCE_DURATION_MILLIS = 1500
        const val SPEECH_DURATION_MILLIS = 50
    }
}
