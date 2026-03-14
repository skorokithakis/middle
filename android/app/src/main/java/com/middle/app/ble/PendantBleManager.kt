package com.middle.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the BLE connection to the Middle pendant and implements the
 * sync protocol. Mirrors the logic in sync.py exactly.
 */
class PendantBleManager(context: Context) : BleManager(context) {

    private var fileCountCharacteristic: BluetoothGattCharacteristic? = null
    private var fileInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var audioDataCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var voltageCharacteristic: BluetoothGattCharacteristic? = null
    private var pairingCharacteristic: BluetoothGattCharacteristic? = null

    // Holds the active transfer state so the notification callback (set once per
    // session) can write to whichever file is currently being received.
    private val activeTransfer = AtomicReference<TransferState?>(null)

    init {
        // Fail the active transfer immediately on disconnect rather than waiting
        // for the 45s TRANSFER_TOTAL_TIMEOUT_MILLIS to expire. The library calls
        // onDeviceDisconnected on the main thread; completeExceptionally is thread-safe.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                activeTransfer.get()?.deferred?.completeExceptionally(
                    IOException("Pendant disconnected during transfer")
                )
            }
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
        })
    }

    private data class TransferState(
        val buffer: ByteArrayOutputStream,
        val deferred: CompletableDeferred<ByteArray>,
        val expectedSize: Int,
    )

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        fileCountCharacteristic = service.getCharacteristic(CHARACTERISTIC_FILE_COUNT_UUID)
        fileInfoCharacteristic = service.getCharacteristic(CHARACTERISTIC_FILE_INFO_UUID)
        audioDataCharacteristic = service.getCharacteristic(CHARACTERISTIC_AUDIO_DATA_UUID)
        commandCharacteristic = service.getCharacteristic(CHARACTERISTIC_COMMAND_UUID)
        // Voltage is optional — older firmware may not expose it.
        voltageCharacteristic = service.getCharacteristic(CHARACTERISTIC_VOLTAGE_UUID)
        pairingCharacteristic = service.getCharacteristic(CHARACTERISTIC_PAIRING_UUID)
        return fileCountCharacteristic != null
            && fileInfoCharacteristic != null
            && audioDataCharacteristic != null
            && commandCharacteristic != null
            && pairingCharacteristic != null
    }

    override fun onServicesInvalidated() {
        fileCountCharacteristic = null
        fileInfoCharacteristic = null
        audioDataCharacteristic = null
        commandCharacteristic = null
        voltageCharacteristic = null
        pairingCharacteristic = null
    }

    override fun initialize() {
        // MTU negotiation is done in connectTo() via suspend() so it completes
        // before any GATT reads/writes. Nothing to do here.
    }

    /**
     * Connect to a pendant device with a 10-second timeout, matching sync.py.
     * MTU negotiation is performed immediately after connection so it completes
     * before readPairingStatus() or any other GATT operation is called.
     */
    suspend fun connectTo(device: BluetoothDevice) {
        connect(device)
            .retry(3, 200)
            .timeout(10_000)
            .useAutoConnect(false)
            .suspend()
        withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) { requestMtu(REQUESTED_MTU).suspend() }
    }

    /**
     * Reads the pairing characteristic. Returns 0x00 if the pendant is
     * unclaimed, or 0x01 if it is already claimed by a token.
     */
    suspend fun readPairingStatus(): Int {
        val characteristic = pairingCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        val data = withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) { readCharacteristic(characteristic).suspend() }
        return (data.value?.firstOrNull()?.toInt() ?: 0) and 0xFF
    }

    /**
     * Writes 16 token bytes to the pairing characteristic. If the pendant
     * rejects the token (wrong token on a claimed device), it will disconnect
     * us — the caller detects this via the connection state.
     */
    suspend fun writePairingToken(token: ByteArray) {
        val characteristic = pairingCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) {
            writeCharacteristic(
                characteristic,
                token,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ).suspend()
        }
    }

    suspend fun readFileCount(): Int {
        val characteristic = fileCountCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        val data = withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) { readCharacteristic(characteristic).suspend() }
        return ByteBuffer.wrap(data.value!!)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF
    }

    suspend fun readFileInfo(): Int {
        val characteristic = fileInfoCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        val data = withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) { readCharacteristic(characteristic).suspend() }
        return ByteBuffer.wrap(data.value!!)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }

    /**
     * Reads the battery voltage in millivolts from the optional voltage
     * characteristic. Returns null if the characteristic is absent (older
     * firmware), so callers must handle both cases.
     */
    suspend fun readVoltageMillivolts(): Int? {
        val characteristic = voltageCharacteristic ?: return null
        val data = withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) { readCharacteristic(characteristic).suspend() }
        return ByteBuffer.wrap(data.value!!)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF
    }

    private suspend fun writeCommand(command: Byte) {
        val characteristic = commandCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) {
            writeCharacteristic(
                characteristic,
                byteArrayOf(command),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ).suspend()
        }
    }

    /**
     * Enables audio data notifications for the session. Must be called once
     * before the file transfer loop. The notification callback writes to
     * whichever TransferState is currently active, so it survives across
     * multiple files without re-toggling the CCCD.
     */
    suspend fun enableAudioNotifications() {
        val audioCharacteristic = audioDataCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        setNotificationCallback(audioCharacteristic).with { _: BluetoothDevice, data: Data ->
            val chunk = data.value ?: return@with
            val state = activeTransfer.get() ?: return@with
            state.buffer.write(chunk)
            if (state.expectedSize > 0 && state.buffer.size() >= state.expectedSize) {
                state.deferred.complete(state.buffer.toByteArray())
            }
        }
        withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) { enableNotifications(audioCharacteristic).suspend() }
    }

    /**
     * Disables audio data notifications. Called once after the file transfer
     * loop completes (or fails). Errors are swallowed by the caller so a dead
     * connection does not mask the original failure.
     */
    suspend fun disableAudioNotifications() {
        val audioCharacteristic = audioDataCharacteristic ?: return
        withTimeout(GATT_OPERATION_TIMEOUT_MILLIS) { disableNotifications(audioCharacteristic).suspend() }
    }

    /**
     * Request and download one file from the pendant. Resets the internal
     * buffer and deferred for each attempt without toggling CCCD — the
     * notification subscription is managed at session level by
     * enableAudioNotifications() / disableAudioNotifications().
     *
     * Returns the raw IMA ADPCM file data (header + payload), or null if
     * the file is empty (corrupt or aborted recording).
     */
    suspend fun requestNextFile(): ByteArray? {
        for (attempt in 1..MAX_FILE_TRANSFER_ATTEMPTS) {
            if (attempt > 1) {
                Log.w(TAG, "Retrying file transfer (attempt $attempt/$MAX_FILE_TRANSFER_ATTEMPTS).")
            }

            val buffer = ByteArrayOutputStream()
            val transferComplete = CompletableDeferred<ByteArray>()
            // Publish the fresh state before sending the command so no chunk
            // can arrive between the command write and the state swap.
            activeTransfer.set(TransferState(buffer, transferComplete, 0))

            try {
                val result = withTimeout(TRANSFER_TOTAL_TIMEOUT_MILLIS) {
                    writeCommand(COMMAND_REQUEST_NEXT)

                    // Brief pause for the pendant to prepare the file info,
                    // matching the 100ms sleep in sync.py.
                    kotlinx.coroutines.delay(100)

                    val expectedSize = readFileInfo()
                    Log.d(TAG, "Expecting $expectedSize bytes.")

                    // Empty files are corrupt or aborted recordings; signal null
                    // so the caller can skip without retrying.
                    if (expectedSize == 0) {
                        Log.w(TAG, "File is empty, skipping.")
                        return@withTimeout null
                    }

                    // Update expectedSize in the active state so the callback can
                    // complete the deferred once enough bytes have arrived.
                    activeTransfer.set(TransferState(buffer, transferComplete, expectedSize))

                    // If chunks arrived before we updated expectedSize, check now.
                    if (buffer.size() >= expectedSize) {
                        return@withTimeout buffer.toByteArray().copyOfRange(0, expectedSize)
                    }

                    // Tell the firmware to begin the notification stream now that
                    // the GATT read of file_info is complete. Sending this before
                    // the read would race notifications against the read response.
                    writeCommand(COMMAND_START_STREAM)
                    Log.d(TAG, "[SyncDebug] START_STREAM sent.")

                    val data = transferComplete.await()
                    Log.d(TAG, "[SyncDebug] transferComplete.await() returned ${data.size} bytes received.")
                    data.copyOfRange(0, expectedSize)
                }
                // null means the file was empty — return immediately without retrying.
                return result
            } catch (exception: Exception) {
                if (exception is CancellationException && exception !is TimeoutCancellationException) throw exception
                val expectedSize = activeTransfer.get()?.expectedSize ?: 0
                Log.w(TAG, "[SyncDebug] Transfer failed: received ${buffer.size()} of $expectedSize bytes. $exception")
            } finally {
                activeTransfer.set(null)
            }
        }

        throw RuntimeException(
            "Failed to transfer file after $MAX_FILE_TRANSFER_ATTEMPTS attempts."
        )
    }

    suspend fun acknowledgeFile() {
        Log.d(TAG, "[SyncDebug] Sending ACK_RECEIVED command.")
        writeCommand(COMMAND_ACK_RECEIVED)
        Log.d(TAG, "[SyncDebug] ACK_RECEIVED command written successfully.")
    }

    suspend fun syncDone() {
        try {
            writeCommand(COMMAND_SYNC_DONE)
        } catch (exception: Exception) {
            Log.w(TAG, "SYNC_DONE write failed: $exception")
        }
    }

    companion object {
        private const val TAG = "PendantBle"
    }
}
