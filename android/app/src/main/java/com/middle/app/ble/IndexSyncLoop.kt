package com.middle.app.ble

import android.content.Context
import android.util.Log
import com.middle.app.audio.RingAudioPreprocessor
import com.middle.app.audio.LinearResampler
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineDebugInfo
import coredevices.haversine.KMPHaversineHacksDelegate
import coredevices.haversine.KMPHaversineSatellite
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.haversine.SatelliteStatus
import coredevices.haversine.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.ExperimentalTime

/**
 * Drives the vendor library that speaks the Pebble Index 01 ring's closed BLE
 * protocol and feeds completed audio collections into [RecordingsRepository].
 *
 * This shares nothing with the pendant path: the pendant is a custom GATT
 * protocol we implement ourselves, the ring is a vendor flow. The only common
 * point is the recording pipeline, so that is the seam.
 */
class IndexSyncLoop(
    context: Context,
    private val settings: Settings,
    private val repository: RecordingsRepository,
    scope: CoroutineScope,
    private val onRecordingSaved: (File, String) -> Unit,
    private val onBacklogSkipped: (Int) -> Unit,
    private val onBatteryVoltage: (Int?) -> Unit,
) {

    private val collectionIndexStorage = RingCollectionIndexStorage(settings)

    private val manager = KMPHaversineSatelliteManager(
        pairedSatelliteIdProvider = {
            // The library compares this against advertisement ids, which have
            // no separators, while Android addresses are colon-separated.
            settings.ringDeviceAddress.replace(":", "").takeIf { it.isNotEmpty() }
        },
        debugDelegate = RingDebugDelegate(),
        hacksDelegate = RingHacksDelegate(),
        collectionIndexStorage = collectionIndexStorage,
        context = context.applicationContext,
        hwVersion = RING_HARDWARE_VERSION,
        scope = scope,
        updateJsonProvider = { null },
        useScanReceiver = false,
    )

    /**
     * Waits for Bluetooth and collects satellite statuses until the scanning
     * flow ends. Transfers themselves are handled by the library; this only
     * persists completed audio.
     */
    suspend fun run() {
        manager.awaitBluetoothReady()
        try {
            manager.startScanning().collect { status ->
                when (status) {
                    is SatelliteStatus.Transferring -> handleTransferStatus(status.transferStatus)
                    is SatelliteStatus.BluetoothFailure -> {
                        Log.w(TAG, "Ring Bluetooth failure on ${status.satellite.id}: ${status.reason}")
                    }
                    // Firmware updating and user-id programming only happen when the
                    // host opts into them, which we do not.
                    else -> Unit
                }
            }
        } catch (exception: BacklogSkippedException) {
            // Returning normally matters: SyncForegroundService restarts the ring
            // session after run() returns, and the fresh session re-seeds its
            // index from disk, so the skip must not surface as a failure.
            Log.d(TAG, "Ended the ring session early after skipping the backlog.")
        }
    }

    private suspend fun handleTransferStatus(transferStatus: TransferStatus) {
        when (transferStatus) {
            is TransferStatus.TransferStarted -> handleTransferStarted(transferStatus)
            is TransferStatus.TransferComplete -> saveTransfer(transferStatus)
            is TransferStatus.TransferFailed -> {
                Log.w(
                    TAG,
                    "Ring transfer failed for collection ${transferStatus.collectionIndex}: " +
                        "${transferStatus.exception?.message}",
                    transferStatus.exception,
                )
            }
            is TransferStatus.IrrecoverableDataDetected -> {
                Log.e(
                    TAG,
                    "Ring reported irrecoverable data: ${transferStatus.exception?.message}",
                    transferStatus.exception,
                )
            }
            is TransferStatus.TransferTypeDetermined -> {
                // The ring attaches battery voltage to some collection metadata
                // and omits it (null) on others, so a missing value is passed on
                // as null rather than defaulted to zero.
                onBatteryVoltage(transferStatus.batteryVoltageMilliV?.toInt())
            }
            // In-progress statuses carry no audio.
            else -> Unit
        }
    }

    /**
     * Skips a backlog the user did not ask for, in two cases that both end up
     * committing the last index in the offered range:
     *
     * - The first sync from a ring has no stored index, so the vendor would pull
     *   its entire backlog. Committing the last index leaves the next session
     *   starting beyond the range, exactly the state a completed sync leaves.
     *   That skip-or-import decision is recorded durably, so a first transfer
     *   that fails before committing cannot turn the next range into a skipped
     *   backlog.
     * - The ring's collection store was cleared (pairing, SOS reset, factory
     *   reset), so its indices restart at 0 while the stored index stays high.
     *   The vendor then computes a start past everything it has and transfers
     *   nothing forever; committing the range's last index repairs it.
     */
    private fun handleTransferStarted(transferStatus: TransferStatus.TransferStarted) {
        val range = transferStatus.willTransferRange
        val stored = settings.lastSuccessfulCollectionIndex

        if (stored != null) {
            // A stored index above the range means the ring moved backwards,
            // which only happens when its store was cleared. Repairing is safe
            // for any range size, including a single collection, or a cleared
            // ring would stay unrepaired and sync would stay dead.
            if (stored <= range.last) return
        } else {
            // No stored index means the ring has never been synced, but that is
            // also true after a first transfer failed before committing one.
            // The decision is therefore recorded durably before the outcome is
            // known, so a failed import cannot make the next range look like a
            // fresh backlog and get skipped as one. Only a range with more than
            // one collection is a backlog; a lone one is a real recording.
            val decided = settings.ringBacklogDecided
            settings.ringBacklogDecided = true
            if (decided || range.last <= range.first) return
        }

        // Write durably only. The in-memory flow still reads the old value, but
        // the session ends below, so the next session is what must see the value.
        collectionIndexStorage.commitLastSuccessfulCollectionIndex(range.last)
        onBacklogSkipped(range.last - range.first + 1)
        // Abort from inside the scanning collect, before any audio moves.
        throw BacklogSkippedException()
    }

    // kotlin.time.Instant is still ExperimentalTime in the Kotlin version this
    // project builds with; the vendor library uses the same type.
    @OptIn(ExperimentalTime::class)
    private suspend fun saveTransfer(transfer: TransferStatus.TransferComplete) {
        val samples = transfer.samples
        val sampleRate = transfer.sampleRate

        // The ring occasionally reports collections with no usable audio; a
        // missing sample rate would also make the duration division meaningless.
        if (sampleRate <= 0L || samples.isEmpty()) {
            Log.w(
                TAG,
                "Discarding ring collection ${transfer.collectionIndex}: " +
                    "sampleRate=$sampleRate, samples=${samples.size}.",
            )
            // A deliberate discard counts as handled: re-downloading a
            // collection we have already rejected would never terminate.
            collectionIndexStorage.commitLastSuccessfulCollectionIndex(transfer.collectionIndex)
            return
        }

        val durationSeconds = samples.size.toDouble() / sampleRate
        if (durationSeconds < MINIMUM_AUDIO_DURATION_SECONDS) {
            Log.d(
                TAG,
                "Discarding ring collection ${transfer.collectionIndex}: " +
                    "only %.2f seconds of audio.".format(durationSeconds),
            )
            collectionIndexStorage.commitLastSuccessfulCollectionIndex(transfer.collectionIndex)
            return
        }

        // The button release time is the recording timestamp. It is absent when
        // the ring had no advertisement to anchor it, in which case the transfer
        // completion time is the closest available substitute.
        val recordedAt = transfer.buttonReleaseTimestamp ?: transfer.transferCompleteTimestamp
        val timestamp = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(recordedAt.toEpochMilliseconds()),
            ZoneId.systemDefault(),
        )
        val filename = "recording_${FILENAME_TIMESTAMP_FORMATTER.format(timestamp)}_${transfer.collectionIndex}.m4a"

        // The ring delivers unsigned 16-bit samples biased away from centre, so
        // read as signed they wrap and the bias turns the step to playback
        // silence into a click. Condition them before resampling so both the
        // interpolator and the encoder see centred, faded audio.
        val prepared = RingAudioPreprocessor.process(samples, sampleRate.toInt())

        // The ring's reported rate is not one AAC can represent (currently
        // 9997Hz). MediaCodec accepts it anyway and silently records 44100Hz
        // while we derive timestamps from the reported rate, which makes the
        // file play back about 4.4x too fast. Resample up to a rate the encoder
        // can actually represent. The duration above is computed from the
        // native rate, so it stays the authoritative length.
        val resampled = LinearResampler.resample(prepared, sampleRate.toInt(), ENCODED_SAMPLE_RATE)
        val file = repository.savePcm16Recording(
            resampled.toLittleEndianPcm16(),
            filename,
            ENCODED_SAMPLE_RATE,
        )
        // Only now is the audio durable. The library may already have advanced
        // its in-memory index past this collection, so commit the index of the
        // transfer we just handled rather than reading the current flow value.
        collectionIndexStorage.commitLastSuccessfulCollectionIndex(transfer.collectionIndex)
        Log.d(
            TAG,
            "Saved ring collection ${transfer.collectionIndex} as ${file.absolutePath} " +
                "(%.2f seconds at ${sampleRate}Hz, resampled to ${ENCODED_SAMPLE_RATE}Hz)."
                    .format(durationSeconds),
        )
        // Fired only after the audio and the collection index are durable, so
        // the caller (transcription/webhook dispatch) never sees a file that
        // could still be rolled back.
        onRecordingSaved(file, filename)
    }

    /**
     * [com.middle.app.audio.AudioEncoder.encodeToM4a] expects signed 16-bit
     * little-endian PCM, so each sample must be written least-significant byte
     * first. Writing them the other way round would produce loud noise rather
     * than silence, which is hard to notice without listening to the output.
     */
    private fun ShortArray.toLittleEndianPcm16(): ByteArray {
        val bytes = ByteArray(size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in this) {
            buffer.putShort(sample)
        }
        return bytes
    }

    /**
     * Ends a session from inside the vendor's scanning flow, which offers no
     * way to stop it cooperatively. See [handleTransferStarted].
     */
    private class BacklogSkippedException : Exception()

    companion object {
        private const val TAG = "IndexSyncLoop"

        private val RING_HARDWARE_VERSION = Pair(11, 0)
        private val FILENAME_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        // Every ring recording is encoded at this rate after resampling, so it
        // is also the rate declared in the container. 16000 is a standard
        // speech rate and is legal for AAC.
        private const val ENCODED_SAMPLE_RATE = 16000

        // The ring emits spurious collections that are far too short to contain
        // speech. One second matches what the official app treats as the floor.
        private const val MINIMUM_AUDIO_DURATION_SECONDS = 1.0
    }
}

private class RingDebugDelegate : KMPHaversineDebugDelegate {
    override fun handleHaversineDebugInfo(info: KMPHaversineDebugInfo) = Unit

    override fun shouldReadRxRSSI(satellite: KMPHaversineSatellite): Boolean = false

    override fun handleRxRSSI(rssi: Float, satellite: KMPHaversineSatellite) = Unit
}

private class RingHacksDelegate : KMPHaversineHacksDelegate {
    // Wiping collections would destroy recordings that have not been synced yet,
    // so the library must never be allowed to do it.
    override fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite): Boolean = false

    override fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) = Unit
}

/**
 * Backs the library's transfer bookkeeping with [Settings] so the index
 * survives an app restart.
 *
 * The library calls [setLastSuccessfulCollectionIndex] as soon as it finishes
 * transferring a collection, but the audio for that collection only reaches us
 * afterwards, through the same delegate method. Persisting the index at that
 * point would record a collection as done before its recording exists, so a
 * crash or encode failure in the gap would make the library skip that
 * recording forever. The durable write is therefore deferred to
 * [commitLastSuccessfulCollectionIndex], which [IndexSyncLoop] calls only once
 * the recording is saved or deliberately discarded.
 *
 * The in-memory flow still tracks the library's value immediately: the library
 * reads it to decide which collections to request, and within a session its
 * behaviour must be identical to the previous implementation.
 */
private class RingCollectionIndexStorage(private val settings: Settings) : CollectionIndexStorage {

    private val _lastSuccessfulCollectionIndex = MutableStateFlow(settings.lastSuccessfulCollectionIndex)

    override val lastSuccessfulCollectionIndex: StateFlow<Int?> = _lastSuccessfulCollectionIndex

    override fun setLastSuccessfulCollectionIndex(index: Int?) {
        _lastSuccessfulCollectionIndex.value = index
        // A null is the library explicitly resetting its position, which
        // carries no audio risk and must survive a restart, so it is persisted
        // straight away rather than deferred.
        if (index == null) {
            settings.lastSuccessfulCollectionIndex = null
        }
    }

    fun commitLastSuccessfulCollectionIndex(index: Int) {
        settings.lastSuccessfulCollectionIndex = index
    }
}
