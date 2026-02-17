package com.middle.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Size of the IMA ADPCM file header (little-endian uint32 sample count). */
const val IMA_HEADER_SIZE = 4

private val STEP_TABLE = intArrayOf(
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37,
    41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173,
    190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
    724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
    7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500,
    20350, 22385, 24623, 27086, 29794, 32767,
)

private val INDEX_TABLE = intArrayOf(
    -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8,
)

object ImaAdpcmDecoder {

    /**
     * Decode a complete IMA ADPCM file (4-byte header + packed nibbles) into
     * signed 16-bit little-endian PCM suitable for encoding or playback.
     */
    fun decodeFile(imaFileData: ByteArray): ByteArray {
        val sampleCount = ByteBuffer.wrap(imaFileData, 0, IMA_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        val adpcmPayload = imaFileData.copyOfRange(IMA_HEADER_SIZE, imaFileData.size)
        return decodeAdpcm(adpcmPayload, sampleCount)
    }

    /**
     * Decode IMA ADPCM packed nibbles to signed 16-bit little-endian PCM.
     *
     * Each byte contains two nibbles (low nibble first). This mirrors the
     * encoder in the firmware exactly.
     */
    fun decodeAdpcm(data: ByteArray, sampleCount: Int): ByteArray {
        var predictedSample = 0
        var stepIndex = 0
        val output = ByteArray(sampleCount * 2)
        var writePosition = 0

        for (i in 0 until sampleCount) {
            val byteIndex = i / 2
            if (byteIndex >= data.size) break

            val nibble = if (i % 2 == 0) {
                data[byteIndex].toInt() and 0x0F
            } else {
                (data[byteIndex].toInt() shr 4) and 0x0F
            }

            val step = STEP_TABLE[stepIndex]
            var delta = step shr 3
            if (nibble and 4 != 0) delta += step
            if (nibble and 2 != 0) delta += step shr 1
            if (nibble and 1 != 0) delta += step shr 2

            if (nibble and 8 != 0) {
                predictedSample -= delta
            } else {
                predictedSample += delta
            }

            predictedSample = predictedSample.coerceIn(-32768, 32767)

            val sampleLe = predictedSample and 0xFFFF
            output[writePosition] = (sampleLe and 0xFF).toByte()
            output[writePosition + 1] = ((sampleLe shr 8) and 0xFF).toByte()
            writePosition += 2

            stepIndex = (stepIndex + INDEX_TABLE[nibble]).coerceIn(0, 88)
        }

        return output.copyOfRange(0, writePosition)
    }
}
