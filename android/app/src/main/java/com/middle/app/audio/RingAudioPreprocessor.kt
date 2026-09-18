package com.middle.app.audio

import kotlin.math.roundToInt

/**
 * Turns the raw samples of an Index 01 ring collection into signed PCM that is
 * ready to resample and encode. Three things happen here, and the first is the
 * one that matters.
 *
 * The ring's ADC delivers unsigned 16-bit offset-binary samples. Reading them
 * as signed wraps every value above 32767 to a large negative, which puts a
 * near full-scale sign flip between adjacent samples, audible as crackle. It is
 * worst on loud consonants because that is where the waveform crosses the wrap
 * point. Measured on one real recording, 700 of 11620 samples wrapped, and the
 * largest jump between neighbours was 65488 out of a 65536 range; reinterpreted
 * as unsigned, the largest jump was 2896.
 *
 * Offset-binary audio also has a large positive mean by construction, so the
 * mean is subtracted to centre the waveform. That is a consequence of the same
 * fact, not a separate defect.
 *
 * Finally a short fade is applied to each end. The waveform begins and ends
 * mid-signal, so the step from the implicit silence before playback to the
 * first sample, and back to silence after the last, is an impulse and audible
 * as a click.
 *
 * The vendor library ships `coredevices.haversine.UtilKt.removeDCBias`, and
 * Core Devices' own app calls it. It is not enough: it treats the samples as
 * signed and only subtracts a mean, so it cannot repair the wraparound. It also
 * converts back with a narrowing `toShort()`, which wraps a value pushed
 * outside the Short range instead of clamping it, turning a loud peak into a
 * full-scale click. Do not swap this out for it.
 */
object RingAudioPreprocessor {

    fun process(input: ShortArray, sampleRate: Int): ShortArray {
        // Derive the fade from the sample rate rather than hardcoding a sample
        // count, so it stays the same few-millisecond duration even though the
        // ring's native rate is not a standard one. Cap it at half the
        // recording so a very short one cannot have overlapping fades, or a
        // negative-length fade out.
        val fadeLength = minOf(
            (sampleRate * FADE_MILLISECONDS / 1000.0).roundToInt(),
            input.size / 2,
        )

        // A few hundred thousand samples near full scale sum well past Int's
        // range (65535 * 300000 is roughly 2.0e10), so accumulate in Long,
        // which cannot overflow until the array holds more samples than could
        // ever fit in memory.
        var sum = 0L
        for (sample in input) {
            sum += sample.toInt() and 0xFFFF
        }
        val mean = sum.toDouble() / input.size

        val output = ShortArray(input.size)
        for (index in input.indices) {
            val unsigned = input[index].toInt() and 0xFFFF
            // The measured range after centring leaves roughly 2x headroom, so
            // this clamp does not fire on real audio. Centring on the mean
            // rather than the midpoint means a much louder recording could in
            // principle exceed the range, so the clamp stays as a genuine
            // guard against the narrowing `toShort` below wrapping.
            val centred = (unsigned - mean)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())

            // Ramp in over the first fadeLength samples and out over the last,
            // reaching zero at each end so the step to playback silence is
            // gone. The second branch cannot overlap the first because
            // fadeLength is capped at half the recording.
            val fade = when {
                fadeLength > 0 && index < fadeLength -> index.toDouble() / fadeLength
                fadeLength > 0 && index >= input.size - fadeLength ->
                    (input.size - 1 - index).toDouble() / fadeLength
                else -> 1.0
            }
            output[index] = (centred * fade).roundToInt().toShort()
        }
        return output
    }

    // A few milliseconds is long enough to remove the step to silence without
    // audibly shortening the recording.
    private const val FADE_MILLISECONDS = 5.0
}
