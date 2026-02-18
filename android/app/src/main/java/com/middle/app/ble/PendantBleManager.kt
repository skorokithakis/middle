package com.middle.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.suspend
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages the BLE connection to the Middle pendant and implements the
 * sync protocol. Mirrors the logic in sync.py exactly.
 */
class PendantBleManager(context: Context) : BleManager(context) {

    private var fileCountCharacteristic: BluetoothGattCharacteristic? = null
    private var fileInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var audioDataCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        fileCountCharacteristic = service.getCharacteristic(CHARACTERISTIC_FILE_COUNT_UUID)
        fileInfoCharacteristic = service.getCharacteristic(CHARACTERISTIC_FILE_INFO_UUID)
        audioDataCharacteristic = service.getCharacteristic(CHARACTERISTIC_AUDIO_DATA_UUID)
        commandCharacteristic = service.getCharacteristic(CHARACTERISTIC_COMMAND_UUID)
        return fileCountCharacteristic != null
            && fileInfoCharacteristic != null
            && audioDataCharacteristic != null
            && commandCharacteristic != null
    }

    override fun onServicesInvalidated() {
        fileCountCharacteristic = null
        fileInfoCharacteristic = null
        audioDataCharacteristic = null
        commandCharacteristic = null
    }

    override fun initialize() {
        requestMtu(REQUESTED_MTU).enqueue()
    }

    /**
     * Connect to a pendant device with a 10-second timeout, matching sync.py.
     */
    suspend fun connectTo(device: BluetoothDevice) {
        connect(device)
            .retry(3, 200)
            .timeout(10_000)
            .useAutoConnect(false)
            .suspend()
    }

    suspend fun readFileCount(): Int {
        val characteristic = fileCountCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        val data = readCharacteristic(characteristic).suspend()
        return ByteBuffer.wrap(data.value!!)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF
    }

    suspend fun readFileInfo(): Int {
        val characteristic = fileInfoCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        val data = readCharacteristic(characteristic).suspend()
        return ByteBuffer.wrap(data.value!!)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }

    private suspend fun writeCommand(command: Byte) {
        val characteristic = commandCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")
        writeCharacteristic(
            characteristic,
            byteArrayOf(command),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ).suspend()
    }

    /**
     * Request and download one file from the pendant. Subscribes to audio
     * data notifications, sends REQUEST_NEXT, collects chunks until
     * expectedSize bytes are received, then unsubscribes.
     *
     * Returns the raw IMA ADPCM file data (header + payload), or null if
     * the file is empty (corrupt or aborted recording).
     */
    suspend fun requestNextFile(): ByteArray? {
        val audioCharacteristic = audioDataCharacteristic
            ?: throw IllegalStateException("Not connected or service not discovered.")

        for (attempt in 1..MAX_FILE_TRANSFER_ATTEMPTS) {
            if (attempt > 1) {
                Log.w(TAG, "Retrying file transfer (attempt $attempt/$MAX_FILE_TRANSFER_ATTEMPTS).")
            }

            val buffer = ByteArrayOutputStream()
            val transferComplete = CompletableDeferred<ByteArray>()
            var expectedSize = 0

            // Enable notifications before requesting the file.
            setNotificationCallback(audioCharacteristic).with { _: BluetoothDevice, data: Data ->
                val chunk = data.value ?: return@with
                buffer.write(chunk)
                if (expectedSize > 0 && buffer.size() >= expectedSize) {
                    transferComplete.complete(buffer.toByteArray())
                }
            }
            enableNotifications(audioCharacteristic).suspend()

            try {
                writeCommand(COMMAND_REQUEST_NEXT)

                // Brief pause for the pendant to prepare the file info,
                // matching the 100ms sleep in sync.py.
                kotlinx.coroutines.delay(100)

                expectedSize = readFileInfo()
                Log.d(TAG, "Expecting $expectedSize bytes.")

                // Empty files are corrupt or aborted recordings. Return null
                // immediately rather than retrying.
                if (expectedSize == 0) {
                    Log.w(TAG, "File is empty, skipping.")
                    return null
                }

                // If chunks arrived before we read expectedSize, check now.
                if (buffer.size() >= expectedSize) {
                    val result = buffer.toByteArray().copyOfRange(0, expectedSize)
                    return result
                }

                val result = withTimeout(TRANSFER_TOTAL_TIMEOUT_MILLIS) {
                    transferComplete.await()
                }
                return result.copyOfRange(0, expectedSize)
            } catch (exception: TimeoutCancellationException) {
                Log.w(TAG, "Transfer stalled at ${buffer.size()}/$expectedSize bytes.")
            } finally {
                disableNotifications(audioCharacteristic).suspend()
            }
        }

        throw RuntimeException(
            "Failed to transfer file after $MAX_FILE_TRANSFER_ATTEMPTS attempts."
        )
    }

    suspend fun acknowledgeFile() {
        writeCommand(COMMAND_ACK_RECEIVED)
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
