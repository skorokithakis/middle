package com.middle.app.audio

/**
 * Why a [PhoneRecorder] capture ended without an explicit [PhoneRecorder.stop].
 *
 * Callers should save the audio for [SPEECH_ENDED], [MAX_DURATION] and
 * [FAILED], and discard it for [NO_SPEECH].
 */
enum class CaptureEndReason {
    /** The endpointer heard speech and then silence. */
    SPEECH_ENDED,

    /** The endpointer heard no speech within its timeout. */
    NO_SPEECH,

    /** The recorder's maximum capture duration was reached. */
    MAX_DURATION,

    /** The read loop ended on a read error or a classifier failure. */
    FAILED,
}
