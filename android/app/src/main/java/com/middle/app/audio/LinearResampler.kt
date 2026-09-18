package com.middle.app.audio

import kotlin.math.roundToInt

/**
 * Resamples mono 16-bit PCM by linear interpolation.
 *
 * The Index 01 reports a native sample rate that AAC cannot represent, so its
 * audio has to be converted to a codec-legal rate before encoding. This only
 * runs upward (currently 9997 -> 16000): upsampling introduces no aliasing, so
 * interpolating between neighbouring input samples is sufficient for the
 * narrowband speech that gets transcribed. Downsampling would need a low-pass
 * filter first and is not what this is for.
 */
object LinearResampler {

    fun resample(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
        // Duration is preserved, so the output holds input.size / inputRate
        // seconds at outputRate. Rounding here can be off by a sample or two,
        // which is inaudible, whereas getting the ratio wrong changes playback
        // speed.
        val outputLength = (input.size.toDouble() * outputRate / inputRate).roundToInt()
        val output = ShortArray(outputLength)

        for (outputIndex in 0 until outputLength) {
            // Output sample `outputIndex` is at outputIndex / outputRate seconds,
            // which falls at outputIndex * inputRate / outputRate input samples.
            val sourcePosition = outputIndex.toDouble() * inputRate / outputRate
            val sourceIndex = sourcePosition.toInt()
            val fraction = sourcePosition - sourceIndex
            val first = input[sourceIndex].toDouble()
            // The last output sample can land after the final input sample, so
            // clamp the right-hand neighbour instead of reading past the array.
            val second = input[minOf(sourceIndex + 1, input.lastIndex)].toDouble()
            output[outputIndex] = (first + (second - first) * fraction).roundToInt().toShort()
        }

        return output
    }
}
