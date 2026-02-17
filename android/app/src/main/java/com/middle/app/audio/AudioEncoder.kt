package com.middle.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

const val SAMPLE_RATE = 16000
private const val AAC_BIT_RATE = 64000
private const val CODEC_TIMEOUT_MICROSECONDS = 10_000L

object AudioEncoder {

    /**
     * Encode signed 16-bit little-endian PCM (mono, 16kHz) into an M4A file
     * using Android's built-in MediaCodec AAC encoder.
     *
     * We use AAC/M4A instead of MP3 because Android has no built-in MP3
     * encoder, and the OpenAI transcription API accepts M4A just fine.
     */
    fun encodeToM4a(pcm16: ByteArray, outputFile: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var inputDone = false

        try {
            while (true) {
                // Feed input.
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_MICROSECONDS)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val remaining = pcm16.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val chunkSize = minOf(remaining, inputBuffer.capacity())
                            inputBuffer.put(pcm16, inputOffset, chunkSize)
                            val presentationTimeUs = (inputOffset.toLong() / 2) * 1_000_000 / SAMPLE_RATE
                            codec.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
                            inputOffset += chunkSize
                        }
                    }
                }

                // Drain output.
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_MICROSECONDS)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // Codec config data — muxer handles this via the format.
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }
        }
    }

    /**
     * Decode an IMA ADPCM file and encode to M4A in one step.
     */
    fun encodeFromIma(imaFileData: ByteArray, outputFile: File) {
        val pcm16 = ImaAdpcmDecoder.decodeFile(imaFileData)
        encodeToM4a(pcm16, outputFile)
    }
}
