package com.middle.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Captures mono signed 16-bit little-endian PCM from the phone microphone.
 *
 * The mic is held only while a recording is live: [stop], the five-minute cap
 * and [release] all end capture and release the [AudioRecord], so an abandoned
 * recording cannot leave the microphone open.
 */
class PhoneRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
) {

    /**
     * One live capture, with its own PCM buffer. A new capture gets a fresh
     * buffer, so a recording started while the previous [stop] is still
     * releasing can never write into the audio that stop is about to return.
     */
    private class Capture(
        val record: AudioRecord,
        val pcm: ByteArrayOutputStream,
        // Optional speech endpointer and its result callback; both null for
        // callers that stop capture themselves.
        val endpointer: SpeechEndpointer?,
        val onCaptureEnded: ((CaptureEndReason) -> Unit)?,
    ) {
        // Stored per capture so a stop can join only its own read loop even
        // after a new capture has already started.
        var readJob: Job? = null

        // Set by whichever path performs the stop/release, so the read loop's
        // five-minute self-stop and a [stop] call cannot both release the mic.
        var recordReleased = false
    }

    private val lock = Any()
    private var capture: Capture? = null

    // Outlives the ViewModel on purpose: a stopped capture's AudioRecord must
    // still be released if the ViewModel is cleared right after [stop].
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts capture in [scope]. Returns false when the mic is unavailable.
     *
     * When [endpointer] is supplied, each chunk is classified on the read loop
     * and capture ends on its own once speech stops or the no-speech timeout
     * elapses. [onCaptureEnded] then reports why, including when the five-minute
     * cap ends capture. Both default to null, so callers that stop capture
     * themselves are unaffected.
     */
    fun start(
        scope: CoroutineScope,
        endpointer: SpeechEndpointer? = null,
        onCaptureEnded: ((CaptureEndReason) -> Unit)? = null,
    ): Boolean {
        synchronized(lock) {
            if (capture != null) return false

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
            if (minBufferSize <= 0) {
                Log.w(TAG, "Invalid AudioRecord buffer size ($minBufferSize).")
                return false
            }

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    CHANNEL,
                    ENCODING,
                    minBufferSize * BUFFER_SIZE_MULTIPLIER,
                )
            } catch (exception: IllegalArgumentException) {
                Log.e(TAG, "Could not create AudioRecord.", exception)
                return false
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Could not create AudioRecord.", exception)
                return false
            } catch (exception: SecurityException) {
                Log.e(TAG, "Could not create AudioRecord.", exception)
                return false
            } catch (exception: UnsupportedOperationException) {
                Log.e(TAG, "Could not create AudioRecord.", exception)
                return false
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord did not initialize.")
                record.release()
                return false
            }

            val pcm = ByteArrayOutputStream()
            try {
                record.startRecording()
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Could not start AudioRecord.", exception)
                record.release()
                return false
            } catch (exception: SecurityException) {
                Log.e(TAG, "Could not start AudioRecord.", exception)
                record.release()
                return false
            } catch (exception: UnsupportedOperationException) {
                Log.e(TAG, "Could not start AudioRecord.", exception)
                record.release()
                return false
            }

            val newCapture = Capture(record, pcm, endpointer, onCaptureEnded)
            capture = newCapture
            newCapture.readJob = scope.launch(Dispatchers.IO) { readLoop(newCapture) }
            return true
        }
    }

    /**
     * Detaches the live capture and returns everything recorded so far.
     *
     * Detaching synchronously is what lets a fast stop-then-start begin a new
     * capture immediately instead of being rejected while the previous
     * AudioRecord is still being torn down. Only the teardown runs in the
     * background, so this call never blocks the caller on a read.
     */
    fun stop(): ByteArray {
        val active: Capture
        val pcm: ByteArray
        synchronized(lock) {
            val current = capture ?: return ByteArray(0)
            capture = null
            active = current
            // Snapshot while holding the lock: the read loop only appends under
            // this same lock, and stops appending once [capture] no longer
            // matches its record.
            pcm = current.pcm.toByteArray()
        }
        val readJob = active.readJob
        active.readJob = null
        teardownScope.launch {
            readJob?.cancelAndJoin()
            if (markRecordReleased(active)) releaseRecord(active)
        }
        return pcm
    }

    /** Ends capture without returning the audio. Safe to call from any thread. */
    fun release() {
        val active = synchronized(lock) {
            val current = capture ?: return
            capture = null
            current
        }
        active.readJob?.cancel()
        active.readJob = null
        if (markRecordReleased(active)) releaseRecord(active)
    }

    private suspend fun readLoop(owned: Capture) {
        val record = owned.record
        val pcm = owned.pcm
        val endpointer = owned.endpointer
        val chunk = ByteArray(CHUNK_BYTES)
        val deadline = SystemClock.elapsedRealtime() + MAX_DURATION_MILLIS
        var endReason: CaptureEndReason? = null
        try {
            while (currentCoroutineContext().isActive) {
                val read = try {
                    record.read(chunk, 0, chunk.size)
                } catch (exception: IllegalStateException) {
                    // release() from a cleared ViewModel can race a blocking read.
                    Log.w(TAG, "AudioRecord.read failed; stopping.", exception)
                    endReason = CaptureEndReason.FAILED
                    break
                }
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read returned $read; stopping.")
                    endReason = CaptureEndReason.FAILED
                    break
                }
                // A stop-and-restart can leave this loop alive for one more read;
                // never let a superseded recorder write into the next capture.
                val stillCurrent = synchronized(lock) {
                    if (capture?.record !== record) {
                        false
                    } else {
                        pcm.write(chunk, 0, read)
                        true
                    }
                }
                if (!stillCurrent) break
                if (endpointer != null) {
                    // onnxruntime throws OrtException, which is a checked
                    // Exception, so this must not be a RuntimeException guard.
                    val decision = try {
                        endpointer.onChunk(chunk, read)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        // A classifier failure must not leave the mic held; end
                        // capture and let the caller stop as usual.
                        Log.e(TAG, "Speech endpointer failed; stopping.", exception)
                        endReason = CaptureEndReason.FAILED
                        break
                    }
                    when (decision) {
                        SpeechEndpointer.Decision.STOP_SAVE -> {
                            endReason = CaptureEndReason.SPEECH_ENDED
                            break
                        }
                        SpeechEndpointer.Decision.STOP_DISCARD -> {
                            endReason = CaptureEndReason.NO_SPEECH
                            break
                        }
                        SpeechEndpointer.Decision.CONTINUE -> Unit
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Log.d(TAG, "Reached the ${MAX_DURATION_MILLIS / 1000}s recording cap.")
                    endReason = CaptureEndReason.MAX_DURATION
                    break
                }
            }
        } finally {
            // The endpointer, cap or a read error ended capture, so release the
            // mic without waiting for the UI to release the button. This runs on
            // every exit path so no failure can leak the AudioRecord. The
            // Capture is kept so a later stop() can still return what was
            // recorded.
            if (markRecordReleased(owned)) releaseRecord(owned)
        }
        // Runs outside the lock and after the mic is released.
        if (endReason != null) owned.onCaptureEnded?.invoke(endReason)
    }

    /**
     * Marks [capture]'s AudioRecord as released and returns true only for the
     * one caller that must perform the stop/release.
     */
    private fun markRecordReleased(capture: Capture): Boolean = synchronized(lock) {
        if (capture.recordReleased) return false
        capture.recordReleased = true
        true
    }

    private fun releaseRecord(capture: Capture) {
        // Release the VAD model alongside the mic. A throwing close must not
        // stop the AudioRecord from being stopped and released.
        try {
            capture.endpointer?.close()
        } catch (exception: Exception) {
            Log.w(TAG, "Could not close speech endpointer.", exception)
        }
        val record = capture.record
        try {
            record.stop()
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "AudioRecord was already stopped.", exception)
        }
        record.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000

        private const val TAG = "PhoneRecorder"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2

        // 100 ms of audio per read, so cancellation is never blocked for long.
        private const val CHUNK_BYTES = 3200

        const val MAX_DURATION_MILLIS = 5 * 60 * 1000L
    }
}
