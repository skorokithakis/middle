package com.middle.app.ble

import android.app.NotificationManager
import android.app.PendingIntent
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
import com.middle.app.AppVisibility
import com.middle.app.MainActivity
import com.middle.app.MiddleApplication
import com.middle.app.R
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import com.middle.app.data.WebhookClient
import com.middle.app.data.WebhookLog
import com.middle.app.transcription.TranscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncForegroundService : Service() {

    private data class ScanProfile(
        val scanMode: Int,
        val windowMillis: Long,
        val periodMillis: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var syncJob: Job? = null
    private var syncLoopJob: Job? = null
    private var scanning = false

    // Set when the selected ring changed while the ring loop was live. The old
    // session can commit an index for the old ring after the setter has reset
    // it, so the fresh session re-resets once the old one is torn down.
    private var pendingRingIndexReset = false

    private lateinit var repository: RecordingsRepository
    private lateinit var settings: Settings

    private val sessionChangeListener: (Settings.SessionChange) -> Unit = { change ->
        when (change) {
            Settings.SessionChange.DEVICE_TYPE -> {
                // Ignore writes that set the value that is already running; the
                // radio buttons call the setter even when the option is already
                // selected.
                if (settings.deviceType != activeDeviceType.value) {
                    startSyncLoop()
                }
            }
            Settings.SessionChange.RING_DEVICE_ADDRESS -> {
                // The running ring session caches the old ring's address and
                // index, so a new address invalidates it even though the loop
                // itself does not change.
                if (activeDeviceType.value == Settings.DEVICE_TYPE_RING) {
                    pendingRingIndexReset = true
                    startSyncLoop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = (application as MiddleApplication).repository
        settings = Settings(this)
        _batteryVoltage.value = settings.lastBatteryVoltage
        startForegroundNotification(getString(R.string.sync_notification_idle))
        settings.addSessionChangeListener(sessionChangeListener)
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        settings.removeSessionChangeListener(sessionChangeListener)
        stopSyncLoop()
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

    /**
     * Starts the loop for the device type currently chosen in settings,
     * replacing any loop that is already running.
     */
    private fun startSyncLoop() {
        val deviceType = settings.deviceType
        _activeDeviceType.value = deviceType
        // Cancel the old loop synchronously, then wait for its teardown inside
        // the new loop. A ring loop owns a session scope that the vendor uses to
        // launch scanning and transfer work, and waiting for the loop to finish
        // means waiting for that session too, so the vendor cannot be left
        // running alongside its replacement.
        val previousLoop = syncLoopJob
        previousLoop?.cancel()
        // The per-device sync is launched in the service scope rather than as a
        // child of the loop job, so it has to be cancelled explicitly or a
        // pendant transfer would keep running after the device type changed.
        syncJob?.cancel()
        syncJob = null
        stopScan()
        syncLoopJob = scope.launch {
            previousLoop?.join()
            when (deviceType) {
                Settings.DEVICE_TYPE_RING -> runRingSyncLoop()
                else -> runPendantSyncLoop()
            }
        }
    }

    private fun stopSyncLoop() {
        syncLoopJob?.cancel()
        syncLoopJob = null
        syncJob?.cancel()
        syncJob = null
        stopScan()
    }

    private suspend fun runPendantSyncLoop() {
        updateNotification(getString(R.string.sync_notification_scanning))
        while (true) {
            // Skip scan if a sync job is currently active.
            if (syncJob?.isActive != true) {
                val profile = currentScanProfile()
                startScan(profile.scanMode)
                delay(profile.windowMillis)
                stopScan()
                delayUntilNextScan(profile)
            } else {
                // Sync is active, wait before checking again.
                delay(500)
            }
        }
    }

    private suspend fun runRingSyncLoop() {
        // Set once and never updated: the vendor flow drives ring transfers and
        // reports no progress back here, so any more specific text would go
        // stale for the rest of the session.
        updateNotification(getString(R.string.sync_notification_ring_waiting))
        while (true) {
            // Each attempt gets a fresh session. The IndexSyncLoop seeds the
            // collection index from Settings when it is constructed, so after a
            // failed save the retry asks the ring for the collection whose save
            // failed instead of trusting the vendor's already-advanced index.
            // The session scope is a child of this loop, so everything the
            // vendor launches into it is torn down with the loop, and cancelling
            // the session cannot affect the service-wide transcription dispatch.
            val sessionJob = SupervisorJob(coroutineContext[Job])
            val sessionScope = CoroutineScope(sessionJob + Dispatchers.Main)
            if (pendingRingIndexReset) {
                // The address setter reset the index, but a completion from the
                // previous ring can have committed over the reset in between.
                // The previous session has been joined by the time this loop
                // starts, so resetting now cannot be overwritten by it.
                pendingRingIndexReset = false
                settings.lastSuccessfulCollectionIndex = null
            }
            try {
                val indexSyncLoop = IndexSyncLoop(
                    context = this,
                    settings = settings,
                    repository = repository,
                    scope = sessionScope,
                    onRecordingSaved = { audioFile, filename ->
                        postNewRecordingNotification()
                        if (settings.transcriptionEnabled) {
                            dispatchTranscriptionAndWebhook(audioFile, filename) {
                                // The ring has no per-session transcription state, so a
                                // failed transcription only affects this recording.
                            }
                        }
                    },
                    onBacklogSkipped = { count -> postBacklogSkippedNotification(count) },
                )
                indexSyncLoop.run()
                Log.d(TAG, "Ring scan flow ended, restarting.")
            } catch (exception: Exception) {
                // The pendant path likewise logs a failed sync and returns to
                // scanning, so a failed ring session must not stop syncing.
                Log.e(TAG, "Ring sync loop failed, restarting.", exception)
            } finally {
                // Cancel and join rather than cancel and hope: the vendor's own
                // cleanup cannot be confirmed from the stripped AAR, so the
                // session must not be considered done while anything it started
                // is still running. The join must still happen when this loop is
                // being cancelled, hence NonCancellable.
                //
                // The wait is bounded because the vendor session ships native
                // code and coroutine cancellation cannot interrupt a blocking
                // JNI call. Without a bound, a hang inside the vendor would trap
                // this NonCancellable join forever, so even stopSyncLoop() could
                // not break it and ring sync would stay dead until the app was
                // force-stopped. The bound keeps a vendor hang from killing our
                // own loop, and the warning makes the choice explicit: if it
                // ever fires we find out, instead of guessing whether the vendor
                // stopped.
                withContext(NonCancellable) {
                    sessionJob.cancel()
                    val sessionStopped = withTimeoutOrNull(SESSION_TEARDOWN_TIMEOUT_MILLIS) {
                        sessionJob.join()
                    }
                    if (sessionStopped == null) {
                        Log.w(
                            TAG,
                            "Vendor ring session did not stop within " +
                                "${SESSION_TEARDOWN_TIMEOUT_MILLIS}ms, abandoning the wait.",
                        )
                    }
                }
            }
            // Without a pause a run() that returns immediately would spin, and a
            // deterministic failure is retried forever by design. Keeping the
            // delay at least as long as before stops a persistent failure from
            // hammering the BLE radio.
            delay(RING_LOOP_RESTART_DELAY_MILLIS)
        }
    }

    private suspend fun delayUntilNextScan(profile: ScanProfile) {
        var remaining = profile.periodMillis - profile.windowMillis
        while (remaining > 0) {
            if (currentScanProfile() != profile) {
                return
            }
            val step = minOf(remaining, 250L)
            delay(step)
            remaining -= step
        }
    }

    private fun currentScanProfile(): ScanProfile {
        if (AppVisibility.isForeground.value) {
            return ScanProfile(
                scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
                windowMillis = FOREGROUND_SCAN_WINDOW_MILLIS,
                periodMillis = FOREGROUND_SCAN_PERIOD_MILLIS,
            )
        }

        return ScanProfile(
            scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
            windowMillis = BACKGROUND_SCAN_WINDOW_MILLIS,
            periodMillis = BACKGROUND_SCAN_PERIOD_MILLIS,
        )
    }

    private fun startScan(scanMode: Int) {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        // When paired, filter by MAC so we only wake up for our pendant.
        // When not paired, filter by service UUID to discover any pendant.
        val filter = if (settings.isPaired) {
            ScanFilter.Builder()
                .setDeviceAddress(settings.pairedDeviceAddress)
                .build()
        } else {
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(scanMode)
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
            // A result can still arrive after the scanner was stopped, so ignore
            // it when the ring loop has taken over.
            if (activeDeviceType.value == Settings.DEVICE_TYPE_RING) return
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
        (application as MiddleApplication).retryQueue.startRetryLoopIfNeeded()
        val manager = PendantBleManager(this)
        try {
            updateNotification(getString(R.string.sync_notification_connecting))
            manager.connectTo(scanResult.device)
            Log.d(TAG, "Connected to pendant.")

            val pairingStatus = manager.readPairingStatus()
            if (settings.isPaired) {
                // We have a stored token — write it so the firmware can verify us.
                manager.writePairingToken(hexToBytes(settings.pairingToken))
                Log.d(TAG, "[sync] token sent to pendant.")
            } else {
                if (pairingStatus == 0x00) {
                    // Pendant is unclaimed — claim it with a fresh random token.
                    val token = generatePairingToken()
                    manager.writePairingToken(token)
                    settings.pairingToken = bytesToHex(token)
                    settings.pairedDeviceAddress = scanResult.device.address
                    Log.d(TAG, "[sync] pendant claimed, stored token and MAC.")
                } else {
                    // Pendant is already claimed by a different device.
                    Log.w(TAG, "[sync] pendant already claimed, skipping.")
                    return
                }
            }

            val millivolts = manager.readVoltageMillivolts()
            if (millivolts != null) {
                val volts = millivolts / 1000.0
                val formatted = "%.2fV".format(volts)
                _batteryVoltage.value = formatted
                settings.lastBatteryVoltage = formatted
                Log.d(TAG, "Battery voltage: $formatted ($millivolts mV)")
                maybePostBatteryLowNotification(millivolts)
            } else {
                _batteryVoltage.value = "N/A"
                settings.lastBatteryVoltage = "N/A"
                Log.d(TAG, "Voltage characteristic not available.")
            }

            updateNotification(getString(R.string.sync_notification_syncing))
            val fileCount = manager.readFileCount()
            Log.d(TAG, "[SyncDebug] readFileCount() returned $fileCount.")

            if (fileCount == 0) {
                manager.syncDone()
                return
            }

            var skipTranscription = false

            // Enable notifications once for the whole session to avoid rapid
            // CCCD churn that destabilises the GATT link between files.
            manager.enableAudioNotifications()
            try {
                for (i in 0 until fileCount) {
                    Log.d(TAG, "Requesting file ${i + 1}/$fileCount...")
                    updateNotification("Syncing file ${i + 1}/$fileCount...")

                    val imaData = manager.requestNextFile()
                    Log.d(TAG, "[SyncDebug] requestNextFile() returned ${if (imaData == null) "null" else "${imaData.size} bytes"}.")

                    // Empty files are corrupt or aborted recordings. ACK to delete
                    // them from the pendant and continue to the next file.
                    if (imaData == null) {
                        Log.d(TAG, "[SyncDebug] Skipping empty file ${i + 1}/$fileCount, sending ACK.")
                        manager.acknowledgeFile()
                        Log.d(TAG, "[SyncDebug] ACK sent for empty file ${i + 1}/$fileCount.")
                        delay(300)
                        continue
                    }

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val filename = "recording_${timestamp}_$i.m4a"
                    // The pendant firmware records at 16000 Hz; state it here so
                    // the encoder does not assume a rate for other sources.
                    val audioFile = repository.saveEncodedRecording(imaData, filename, 16000)
                    Log.d(TAG, "[SyncDebug] saveEncodedRecording() returned path=${audioFile.absolutePath} size=${audioFile.length()} bytes.")
                    postNewRecordingNotification()

                    manager.acknowledgeFile()
                    Log.d(TAG, "[SyncDebug] ACK sent for file ${i + 1}/$fileCount.")
                    // Brief pause between files to let the pendant settle before
                    // the next COMMAND_REQUEST_NEXT, reducing GATT instability.
                    delay(300)

                    if (!skipTranscription && settings.transcriptionEnabled) {
                        dispatchTranscriptionAndWebhook(audioFile, filename) {
                            // Disable further transcription attempts this
                            // session if the first one fails, same as sync.py.
                            skipTranscription = true
                        }
                    }
                }

                val remainingFileCount = manager.readFileCount()
                Log.d(TAG, "[SyncDebug] Post-loop readFileCount() returned $remainingFileCount (expected 0 if all ACKs were processed).")
                manager.syncDone()
                Log.d(TAG, "[SyncDebug] Sync complete, $fileCount file(s) transferred.")
            } finally {
                try {
                    manager.disableAudioNotifications()
                } catch (exception: Exception) {
                    Log.w(TAG, "disableAudioNotifications error (connection may be dead): $exception")
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "[SyncDebug] Sync failed.", exception)
        } finally {
            try {
                manager.disconnect().enqueue()
            } catch (exception: Exception) {
                Log.w(TAG, "Disconnect error: $exception")
            }
            updateNotification(getString(R.string.sync_notification_scanning))
        }
    }

    /**
     * Transcribes a saved recording and delivers the transcript to the webhook
     * if one is configured. This is deliberately fire and forget: the caller
     * must not wait for transcription or the webhook, because both take far
     * longer than the per-file GATT pause the pendant transfer relies on.
     *
     * [onTranscriptionUnavailable] is invoked when transcription cannot be
     * attempted or fails. It exists because the pendant disables transcription
     * for the rest of the sync session after the first failure, while the ring
     * has no session to disable; the decision cannot be returned because the
     * failure is usually discovered inside the launched coroutine, long after
     * this function has returned.
     */
    private fun dispatchTranscriptionAndWebhook(
        audioFile: File,
        filename: String,
        onTranscriptionUnavailable: () -> Unit,
    ) {
        val provider = settings.transcriptionProvider
        val apiKey = getSelectedProviderApiKey()
        if (apiKey.isEmpty()) {
            val message = "Transcription skipped: missing ${providerDisplayName(provider)} API key"
            Log.w(TAG, message)
            WebhookLog.error("$message ($filename)")
            updateNotification(message)
            onTranscriptionUnavailable()
        } else {
            scope.launch(Dispatchers.IO) {
                val client = TranscriptionClient(provider, apiKey)
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
                        val appRetryQueue = (application as MiddleApplication).retryQueue
                        try {
                            val result = WebhookClient.post(webhookUrl, text, template)
                            if (result.success) {
                                Log.d(TAG, "Webhook POST succeeded for $filename.")
                                WebhookLog.info("${result.code} OK ($filename)")
                            } else {
                                Log.w(TAG, "Webhook POST failed with status ${result.code} for $filename.")
                                WebhookLog.error("${result.code} ${result.message} ($filename): ${result.body}")
                                if (result.code !in 400..499) {
                                    appRetryQueue.enqueue(text, webhookUrl, template, filename)
                                }
                            }
                        } catch (exception: Exception) {
                            Log.w(TAG, "Webhook POST error for $filename: $exception")
                            WebhookLog.error("$filename: ${exception::class.simpleName}: ${exception.message}")
                            appRetryQueue.enqueue(text, webhookUrl, template, filename)
                        }
                    }
                } else {
                    val message = "Transcription failed (${providerDisplayName(provider)})"
                    Log.w(TAG, message)
                    WebhookLog.error("$message ($filename)")
                    updateNotification(message)
                    onTranscriptionUnavailable()
                }
            }
        }
    }

    private fun maybePostBatteryLowNotification(millivolts: Int) {
        if (millivolts >= BATTERY_LOW_THRESHOLD_MV) return
        val now = System.currentTimeMillis()
        if (now - settings.lastBatteryNotificationTime < BATTERY_LOW_DEBOUNCE_MS) return

        val notification = NotificationCompat.Builder(this, MiddleApplication.BATTERY_LOW_CHANNEL_ID)
            .setContentTitle(getString(R.string.battery_low_notification_title))
            .setContentText(getString(R.string.battery_low_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(MiddleApplication.BATTERY_LOW_NOTIFICATION_ID, notification)
        settings.lastBatteryNotificationTime = now
        Log.d(TAG, "Battery low notification posted ($millivolts mV).")
    }

    /**
     * Posts (or replaces) the "new recording added" notification. A fixed ID is
     * used so several recordings saved during one sync collapse into a single
     * notification instead of stacking up.
     *
     * Suppressing this while the app is in the foreground was considered and
     * rejected; the notification is wanted even when the app is on screen.
     */
    private fun postNewRecordingNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_RECORDINGS, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, MiddleApplication.NEW_RECORDING_CHANNEL_ID)
            .setContentTitle(getString(R.string.new_recording_notification_title))
            .setContentText(getString(R.string.new_recording_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(MiddleApplication.NEW_RECORDING_NOTIFICATION_ID, notification)
        Log.d(TAG, "New recording notification posted.")
    }

    /**
     * Tells the user that a first ring sync left the recordings already on the
     * ring alone, so the empty import is expected rather than a failure.
     */
    private fun postBacklogSkippedNotification(count: Int) {
        val notification = NotificationCompat.Builder(this, MiddleApplication.NEW_RECORDING_CHANNEL_ID)
            .setContentTitle(getString(R.string.ring_backlog_skipped_notification_title))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.ring_backlog_skipped_notification_text,
                    count,
                    count,
                ),
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(RING_BACKLOG_SKIPPED_NOTIFICATION_ID, notification)
        Log.d(TAG, "Ring backlog skip notification posted ($count skipped).")
    }

    private fun generatePairingToken(): ByteArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun getSelectedProviderApiKey(): String {
        return when (settings.transcriptionProvider) {
            Settings.TRANSCRIPTION_PROVIDER_OPENAI -> settings.openAiApiKey.trim()
            Settings.TRANSCRIPTION_PROVIDER_ELEVENLABS -> settings.elevenLabsApiKey.trim()
            else -> ""
        }
    }

    private fun providerDisplayName(provider: String): String {
        return when (provider) {
            Settings.TRANSCRIPTION_PROVIDER_OPENAI -> "OpenAI"
            Settings.TRANSCRIPTION_PROVIDER_ELEVENLABS -> "ElevenLabs"
            else -> provider
        }
    }

    companion object {
        private const val TAG = "SyncService"

        private const val BATTERY_LOW_THRESHOLD_MV = 3860
        private const val BATTERY_LOW_DEBOUNCE_MS = 6 * 60 * 60 * 1000L

        // Matches the pause the pendant loop uses while a sync is in flight, so
        // a ring session that ends immediately is retried at the same cadence.
        private const val RING_LOOP_RESTART_DELAY_MILLIS = 500L

        // Long enough that a normal vendor teardown finishes, short enough that
        // a native hang does not keep ring sync stalled for long. Three seconds
        // is well above the instant that cooperative cancellation takes, so a
        // warning here means something is genuinely stuck in the vendor.
        private const val SESSION_TEARDOWN_TIMEOUT_MILLIS = 3000L

        private val _syncState = MutableStateFlow("Idle")
        val syncState: StateFlow<String> = _syncState

        private val _batteryVoltage = MutableStateFlow("N/A")
        val batteryVoltage: StateFlow<String> = _batteryVoltage

        // The device type of the loop that is currently running. The recordings
        // screen hides the pendant status bar while the ring is selected,
        // because the ring path never updates that text or the battery reading.
        // Null until the service has started its first loop.
        private val _activeDeviceType = MutableStateFlow<String?>(null)
        val activeDeviceType: StateFlow<String?> = _activeDeviceType

        // Never collides with the IDs MiddleApplication owns.
        private const val RING_BACKLOG_SKIPPED_NOTIFICATION_ID = 4
    }
}
