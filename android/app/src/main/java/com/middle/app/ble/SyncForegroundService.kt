package com.middle.app.ble

import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.middle.app.MiddleApplication
import com.middle.app.R
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import com.middle.app.data.WebhookClient
import com.middle.app.data.WebhookLog
import com.middle.app.data.WebhookRetryQueue
import com.middle.app.transcription.TranscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var syncJob: Job? = null
    private var scanning = false

    private lateinit var repository: RecordingsRepository
    private lateinit var settings: Settings
    private lateinit var retryQueue: WebhookRetryQueue

    override fun onCreate() {
        super.onCreate()
        repository = (application as MiddleApplication).repository
        settings = Settings(this)
        retryQueue = WebhookRetryQueue(this, scope)
        _batteryVoltage.value = settings.lastBatteryVoltage
        startForegroundNotification(getString(R.string.sync_notification_idle))
        startScanLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopScan()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification(text: String) {
        val notification = NotificationCompat.Builder(this, MiddleApplication.SYNC_CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(MiddleApplication.SYNC_NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        _syncState.value = text
        startForegroundNotification(text)
    }

    private fun startScanLoop() {
        scope.launch {
            updateNotification(getString(R.string.sync_notification_scanning))
            while (true) {
                // Skip scan if a sync job is currently active.
                if (syncJob?.isActive != true) {
                    startScan()
                    delay(SCAN_INTERVAL_MILLIS)
                    stopScan()
                    // Brief pause to let the BT stack release the scanner slot.
                    delay(500)
                } else {
                    // Sync is active, wait before checking again.
                    delay(500)
                }
            }
        }
    }

    private fun startScan() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), scanSettings, scanCallback)
            scanning = true
            Log.d(TAG, "BLE scan started.")
        } catch (exception: SecurityException) {
            Log.e(TAG, "BLE scan permission denied: $exception")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (exception: SecurityException) {
            Log.e(TAG, "Failed to stop scan: $exception")
        }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Avoid starting multiple sync jobs simultaneously.
            if (syncJob?.isActive == true) return

            Log.d(TAG, "Found pendant: ${result.device.address}")
            stopScan()
            syncJob = scope.launch { syncWithDevice(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            // The scan loop will naturally retry on the next iteration.
        }
    }

    private suspend fun syncWithDevice(scanResult: ScanResult) {
        retryQueue.startRetryLoopIfNeeded()
        val manager = PendantBleManager(this)
        try {
            updateNotification(getString(R.string.sync_notification_connecting))
            manager.connectTo(scanResult.device)
            Log.d(TAG, "Connected to pendant.")

            val millivolts = manager.readVoltageMillivolts()
            if (millivolts != null) {
                val volts = millivolts / 1000.0
                val formatted = "%.2fV".format(volts)
                _batteryVoltage.value = formatted
                settings.lastBatteryVoltage = formatted
                Log.d(TAG, "Battery voltage: $formatted ($millivolts mV)")
            } else {
                _batteryVoltage.value = "N/A"
                settings.lastBatteryVoltage = "N/A"
                Log.d(TAG, "Voltage characteristic not available.")
            }

            updateNotification(getString(R.string.sync_notification_syncing))
            val fileCount = manager.readFileCount()
            Log.d(TAG, "Pendant reports $fileCount pending recording(s).")

            if (fileCount == 0) {
                manager.syncDone()
                return
            }

            var skipTranscription = false

            for (i in 0 until fileCount) {
                Log.d(TAG, "Requesting file ${i + 1}/$fileCount...")
                updateNotification("Syncing file ${i + 1}/$fileCount...")

                val imaData = manager.requestNextFile()

                // Empty files are corrupt or aborted recordings. ACK to delete
                // them from the pendant and continue to the next file.
                if (imaData == null) {
                    Log.d(TAG, "Skipping empty file ${i + 1}/$fileCount.")
                    manager.acknowledgeFile()
                    continue
                }

                Log.d(TAG, "Received ${imaData.size} bytes.")

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "recording_${timestamp}_$i.m4a"
                val audioFile = repository.saveEncodedRecording(imaData, filename)
                Log.d(TAG, "Saved $filename.")

                manager.acknowledgeFile()

                if (!skipTranscription && settings.transcriptionEnabled) {
                    val apiKey = settings.openAiApiKey
                    if (apiKey.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val client = TranscriptionClient(apiKey)
                            val text = client.transcribe(audioFile)
                            if (text != null) {
                                repository.saveTranscript(text, audioFile)
                                Log.d(TAG, "Saved transcript for $filename.")

                                val webhookUrl = settings.webhookUrl.trim()
                                if (settings.webhookEnabled && webhookUrl.isNotEmpty()) {
                                    val template = settings.webhookBodyTemplate.ifBlank {
                                        Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE
                                    }
                                    WebhookLog.info("POST $webhookUrl ($filename)")
                                    try {
                                        val result = WebhookClient.post(webhookUrl, text, template)
                                        if (result.success) {
                                            Log.d(TAG, "Webhook POST succeeded for $filename.")
                                            WebhookLog.info("${result.code} OK ($filename)")
                                        } else {
                                            Log.w(TAG, "Webhook POST failed with status ${result.code} for $filename.")
                                            WebhookLog.error("${result.code} ${result.message} ($filename): ${result.body}")
                                            if (result.code !in 400..499) {
                                                retryQueue.enqueue(text, webhookUrl, template, filename)
                                            }
                                        }
                                    } catch (exception: Exception) {
                                        Log.w(TAG, "Webhook POST error for $filename: $exception")
                                        WebhookLog.error("$filename: ${exception::class.simpleName}: ${exception.message}")
                                        retryQueue.enqueue(text, webhookUrl, template, filename)
                                    }
                                }
                            } else {
                                // Disable further transcription attempts this
                                // session if the first one fails, same as sync.py.
                                skipTranscription = true
                            }
                        }
                    }
                }
            }

            manager.syncDone()
            Log.d(TAG, "Sync complete, $fileCount file(s) transferred.")
        } catch (exception: Exception) {
            Log.e(TAG, "Sync failed: $exception")
        } finally {
            try {
                manager.disconnect().enqueue()
            } catch (exception: Exception) {
                Log.w(TAG, "Disconnect error: $exception")
            }
            updateNotification(getString(R.string.sync_notification_scanning))
        }
    }

    companion object {
        private const val TAG = "SyncService"

        private val _syncState = MutableStateFlow("Idle")
        val syncState: StateFlow<String> = _syncState

        private val _batteryVoltage = MutableStateFlow("N/A")
        val batteryVoltage: StateFlow<String> = _batteryVoltage
    }
}
