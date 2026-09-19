package com.middle.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.middle.app.transcription.TranscriptionClient
import com.middle.app.transcription.TranscriptionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.File

private const val TAG = "PipelineQueue"

/** The stage a queued recording is at. Persisted in the job file. */
enum class PipelineStage { TRANSCRIBE, WEBHOOK }

/**
 * Durable transcribe-then-webhook jobs, one JSON file per recording.
 *
 * The job file records only the [PipelineStage]; per-job retry state lives in
 * memory because a restart is rare and the network gate already stops
 * hammering. Settings are read at send time so a corrected API key or webhook
 * URL applies to jobs that were queued before the fix.
 */
class PipelineQueue(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: RecordingsRepository,
) {

    private val appContext = context.applicationContext
    private val settings = Settings(appContext)
    private val recordingsDirectory = File(appContext.filesDir, "recordings")
    private val pipelineDirectory = File(appContext.filesDir, "pipeline").also { it.mkdirs() }

    private val lock = Any()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val backoffByFilename = mutableMapOf<String, BackoffState>()

    private val _pendingFilenames = MutableStateFlow<Set<String>>(emptySet())
    val pendingFilenames: StateFlow<Set<String>> = _pendingFilenames

    @Volatile
    private var isOnline = true

    @Volatile
    private var workerJob: Job? = null

    private var networkCallbackRegistered = false

    // Set once a missing-key message has been logged and cleared as soon as a
    // key is seen, so a long key-missing stretch logs only once. The worker
    // coroutine is the only reader and writer.
    private var loggedMissingKey = false

    init {
        // The old webhook retry format is incompatible with pipeline jobs, so
        // its directory is discarded rather than migrated.
        File(appContext.filesDir, "webhooks").deleteRecursively()
        _pendingFilenames.value = readJobsLocked().map { it.filename }.toSet()
    }

    /**
     * Starts the worker loop in [scope] once. Subsequent calls are no-ops while
     * the worker is alive.
     */
    fun start() {
        synchronized(lock) {
            if (workerJob?.isActive == true) return
            registerNetworkCallback()
            workerJob = scope.launch(Dispatchers.IO) { runLoop() }
        }
        wake()
    }

    /**
     * Creates a job for [filename] at TRANSCRIBE, or WEBHOOK when the transcript
     * already exists, and does nothing when the transcript exists and the
     * webhook is disabled.
     */
    fun enqueue(filename: String) {
        synchronized(lock) {
            ensureJobLocked(filename)
            refreshPendingFilenamesLocked()
        }
        wake()
    }

    /**
     * Ensures a job exists (same rules as [enqueue]) and makes it due now.
     * Used by the UI's manual retry.
     */
    fun retryNow(filename: String) {
        synchronized(lock) {
            if (ensureJobLocked(filename)) {
                backoffByFilename.getOrPut(filename) { BackoffState() }.dueNowRequested = true
            }
            refreshPendingFilenamesLocked()
        }
        wake()
    }

    fun removeForRecording(filename: String) {
        synchronized(lock) {
            jobFile(filename).delete()
            backoffByFilename.remove(filename)
            refreshPendingFilenamesLocked()
        }
        wake()
    }

    fun removeAll() {
        synchronized(lock) {
            backoffByFilename.clear()
            pipelineDirectory.listFiles()?.forEach { it.delete() }
            _pendingFilenames.value = emptySet()
        }
        wake()
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                processDueJobs()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // A transient local failure (disk, storage, settings) must not
                // kill the worker: log and let the next pass retry.
                Log.e(TAG, "Pipeline pass failed; will retry", exception)
            }
            val waitMillis = computeWaitMillis().coerceAtLeast(1L)
            // A wake can arrive while jobs are being processed, so the channel
            // may already hold a signal when this call starts.
            withTimeoutOrNull(waitMillis) { wakeSignal.receive() }
            while (wakeSignal.tryReceive().isSuccess) {
                // Drain collapsed signals so the next pass runs immediately.
            }
        }
    }

    private suspend fun processDueJobs() {
        val jobs = synchronized(lock) { readJobsLocked() }
        var skipRemainingTranscribe = false

        for ((index, job) in jobs.withIndex()) {
            if (!jobFile(job.filename).exists()) {
                synchronized(lock) {
                    backoffByFilename.remove(job.filename)
                }
                continue
            }

            val state = synchronized(lock) {
                backoffByFilename.getOrPut(job.filename) { BackoffState() }
            }
            val now = System.currentTimeMillis()
            if (now < nextDueMillis(state)) continue

            // The gate is advisory. A job is also let through once it has been
            // parked offline for the full cap, so a wrong offline signal cannot
            // hold it forever.
            val delay = PipelinePolicy.backoffDelayMillis(state.attempts)
            if (!isOnline && delay < PipelinePolicy.MAX_BACKOFF_MILLIS && !offlineFallbackReached(state, now)) {
                if (state.offlineSinceMillis == 0L) state.offlineSinceMillis = now
                continue
            }

            if (skipRemainingTranscribe && job.stage == PipelineStage.TRANSCRIBE) continue

            // A real attempt clears both advisory waits.
            state.offlineSinceMillis = 0L
            state.keyMissing = false

            when (job.stage) {
                PipelineStage.TRANSCRIBE -> {
                    if (attemptTranscribe(job) == AttemptResult.AUTH) {
                        skipRemainingTranscribe = true
                        // Back off the rest of this pass's TRANSCRIBE jobs,
                        // including the ones just skipped, so a bad key is not
                        // tried once per job across back-to-back passes.
                        bumpOtherTranscribeJobs(jobs, index)
                    }
                }
                PipelineStage.WEBHOOK -> attemptWebhook(job)
            }
        }
        synchronized(lock) { refreshPendingFilenamesLocked() }
    }

    private fun bumpOtherTranscribeJobs(jobs: List<PipelineJob>, failingIndex: Int) {
        synchronized(lock) {
            for ((index, job) in jobs.withIndex()) {
                if (index == failingIndex) continue
                if (job.stage != PipelineStage.TRANSCRIBE) continue
                if (!jobFile(job.filename).exists()) continue
                val state = backoffByFilename.getOrPut(job.filename) { BackoffState() }
                state.attempts += 1
                state.lastAttemptMillis = System.currentTimeMillis()
                state.dueNowRequested = false
                state.offlineSinceMillis = 0L
                state.keyMissing = false
            }
        }
    }

    private fun computeWaitMillis(): Long {
        val now = System.currentTimeMillis()
        val jobs = synchronized(lock) { readJobsLocked() }
        if (jobs.isEmpty()) return PipelinePolicy.MAX_BACKOFF_MILLIS

        var minWait = PipelinePolicy.MAX_BACKOFF_MILLIS
        for (job in jobs) {
            val state = synchronized(lock) {
                backoffByFilename[job.filename] ?: BackoffState()
            }
            val delay = PipelinePolicy.backoffDelayMillis(state.attempts)
            if (!isOnline && delay < PipelinePolicy.MAX_BACKOFF_MILLIS && !offlineFallbackReached(state, now)) {
                // Parked offline: wake at the fallback deadline, or when the job
                // first becomes due if this pass has not parked it yet.
                val wakeAt = if (state.offlineSinceMillis != 0L) {
                    state.offlineSinceMillis + PipelinePolicy.MAX_BACKOFF_MILLIS
                } else {
                    nextDueMillis(state)
                }
                minWait = minOf(minWait, (wakeAt - now).coerceAtLeast(0L))
                continue
            }
            minWait = minOf(minWait, (nextDueMillis(state) - now).coerceAtLeast(0L))
        }
        return minWait
    }

    private fun offlineFallbackReached(state: BackoffState, now: Long): Boolean =
        state.offlineSinceMillis != 0L &&
            now - state.offlineSinceMillis >= PipelinePolicy.MAX_BACKOFF_MILLIS

    private suspend fun attemptTranscribe(job: PipelineJob): AttemptResult {
        val provider = settings.transcriptionProvider
        val apiKey = selectedApiKey(provider)
        if (apiKey.isEmpty()) {
            if (!loggedMissingKey) {
                loggedMissingKey = true
                val message = "Transcription skipped for ${job.filename}: missing ${providerDisplayName(provider)} API key"
                Log.w(TAG, message)
                WebhookLog.error(message)
            }
            // Not an attempt: wait a fixed, slow interval for the key rather
            // than the attempt backoff, and clear any forced retry so one tap
            // does not make every following pass immediate too.
            synchronized(lock) {
                val state = backoffByFilename.getOrPut(job.filename) { BackoffState() }
                state.lastAttemptMillis = System.currentTimeMillis()
                state.dueNowRequested = false
                state.keyMissing = true
            }
            return AttemptResult.MISSING_KEY
        }
        loggedMissingKey = false

        val audioFile = recordingFile(job.filename)
        if (audioFile == null) {
            Log.w(TAG, "Recording ${job.filename} is gone; dropping transcribe job")
            deleteJob(job.filename)
            return AttemptResult.DROPPED
        }

        val result = TranscriptionClient(provider, apiKey).transcribe(audioFile)
        return when (PipelinePolicy.classifyTranscription(result)) {
            TranscriptionOutcome.SUCCESS -> {
                val text = (result as TranscriptionResult.Success).text
                // The recording (or its job) can be deleted while the request
                // is in flight; without this the delete would resurrect the
                // transcript and the webhook job.
                val stillQueued = synchronized(lock) {
                    jobFile(job.filename).exists() && recordingFile(job.filename) != null
                }
                if (!stillQueued) {
                    Log.d(TAG, "Dropping transcription result for ${job.filename}: job or recording is gone")
                    return AttemptResult.DROPPED
                }
                repository.saveTranscript(text, audioFile)
                advanceAfterTranscription(job.filename)
                AttemptResult.SUCCESS
            }
            TranscriptionOutcome.BAD_FILE -> {
                WebhookLog.error("Transcription rejected for ${job.filename}: ${describe(result)}")
                Log.w(TAG, "Transcription rejected for ${job.filename}: ${describe(result)}")
                deleteJob(job.filename)
                AttemptResult.BAD_FILE
            }
            TranscriptionOutcome.AUTH -> {
                bumpAttempt(job.filename)
                WebhookLog.error("Transcription auth failed for ${job.filename}: ${describe(result)}")
                Log.w(TAG, "Transcription auth failed for ${job.filename}; skipping the rest of this pass")
                AttemptResult.AUTH
            }
            TranscriptionOutcome.TRANSIENT -> {
                bumpAttempt(job.filename)
                Log.w(TAG, "Transcription failed for ${job.filename}: ${describe(result)}")
                AttemptResult.TRANSIENT
            }
        }
    }

    private fun advanceAfterTranscription(filename: String) {
        // Action handling is best-effort: a failure here (for example a
        // SecurityException from the clock app) must not leave the job at
        // TRANSCRIBE, where it would be retried and the recording transcribed
        // again. On failure the webhook is still sent, so fail open.
        val suppressWebhook = try {
            val transcript = transcriptFile(filename)
            val result = ActionMatcher.evaluate(
                if (transcript.exists()) transcript.readText() else "",
                settings.actions,
            )
            logInvalidActions(result)
            // Actions are run once here, on the only successful transcription
            // of a recording; the later webhook stage never evaluates them
            // again.
            AlarmActionRunner.run(appContext, result)
            result.suppressWebhook
        } catch (exception: Exception) {
            Log.e(TAG, "[action] action handling failed for $filename; sending webhook", exception)
            false
        }

        val webhookConfigured = settings.webhookEnabled && settings.webhookUrl.trim().isNotEmpty()
        synchronized(lock) {
            when {
                webhookConfigured && !suppressWebhook ->
                    writeJobLocked(filename, PipelineStage.WEBHOOK)
                else -> {
                    if (webhookConfigured) {
                        WebhookLog.info("[action] webhook suppressed by alarm action")
                    }
                    deleteJobLocked(filename)
                }
            }
            refreshPendingFilenamesLocked()
        }
    }

    private fun attemptWebhook(job: PipelineJob) {
        val webhookUrl = settings.webhookUrl.trim()
        if (!settings.webhookEnabled || webhookUrl.isEmpty()) {
            deleteJob(job.filename)
            return
        }

        val transcriptFile = transcriptFile(job.filename)
        if (!transcriptFile.exists()) {
            Log.w(TAG, "No transcript for ${job.filename}; dropping webhook job")
            deleteJob(job.filename)
            return
        }

        val template = settings.webhookBodyTemplate.ifBlank { Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE }
        val result = try {
            WebhookClient.post(webhookUrl, transcriptFile.readText(), template)
        } catch (exception: Exception) {
            WebhookLog.error("Webhook error for ${job.filename}: ${exception::class.simpleName}: ${exception.message}")
            bumpAttempt(job.filename)
            return
        }

        when (PipelinePolicy.classifyWebhook(result.success, result.code)) {
            WebhookOutcome.SUCCESS -> {
                WebhookLog.info("Webhook sent for ${job.filename} (${result.code})")
                deleteJob(job.filename)
            }
            WebhookOutcome.DROP -> {
                WebhookLog.error("Webhook abandoned for ${job.filename}: ${result.code} ${result.message}")
                Log.w(TAG, "Webhook abandoned for ${job.filename}: ${result.code}")
                deleteJob(job.filename)
            }
            WebhookOutcome.RETRY -> {
                WebhookLog.error("Webhook failed for ${job.filename}: ${result.code} ${result.message}")
                bumpAttempt(job.filename)
            }
        }
    }

    private fun ensureJobLocked(filename: String): Boolean {
        val transcriptExists = transcriptFile(filename).exists()
        val webhookConfigured = settings.webhookEnabled && settings.webhookUrl.trim().isNotEmpty()
        // The suppress check is applied without running the alarms again, so a
        // suppressed transcript cannot leak its webhook on a manual retry.
        val webhookSuppressed = webhookConfigured && isWebhookSuppressedByAction(filename)
        if (webhookSuppressed) {
            WebhookLog.info("[action] webhook suppressed by alarm action")
        }
        return when {
            !transcriptExists -> {
                writeJobLocked(filename, PipelineStage.TRANSCRIBE)
                true
            }
            webhookConfigured && !webhookSuppressed -> {
                writeJobLocked(filename, PipelineStage.WEBHOOK)
                true
            }
            else -> {
                deleteJobLocked(filename)
                false
            }
        }
    }

    private fun isWebhookSuppressedByAction(filename: String): Boolean {
        val transcript = transcriptFile(filename)
        if (!transcript.exists()) return false
        val result = ActionMatcher.evaluate(transcript.readText(), settings.actions)
        logInvalidActions(result)
        return result.suppressWebhook
    }

    private fun logInvalidActions(result: ActionResult) {
        for (action in result.invalid) {
            Log.w(TAG, "[action] invalid pattern for action ${action.id}")
        }
    }

    private fun bumpAttempt(filename: String) {
        synchronized(lock) {
            val state = backoffByFilename.getOrPut(filename) { BackoffState() }
            state.attempts += 1
            state.lastAttemptMillis = System.currentTimeMillis()
            state.dueNowRequested = false
        }
    }

    private fun deleteJob(filename: String) {
        synchronized(lock) {
            deleteJobLocked(filename)
            refreshPendingFilenamesLocked()
        }
    }

    private fun deleteJobLocked(filename: String) {
        jobFile(filename).delete()
        backoffByFilename.remove(filename)
    }

    private fun writeJobLocked(filename: String, stage: PipelineStage) {
        val json = JSONObject().apply {
            put(FIELD_FILENAME, filename)
            put(FIELD_STAGE, stage.name)
        }
        val target = jobFile(filename)
        // writeText truncates first, so a crash mid-write would leave a partial
        // file that startup deletes as malformed. Rename is the atomic commit.
        val temp = File(pipelineDirectory, "${target.name}.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(target)) {
            Log.w(TAG, "Atomic rename failed for ${target.name}; writing in place")
            target.writeText(json.toString())
            temp.delete()
        }
    }

    private fun readJobsLocked(): List<PipelineJob> {
        // A crash between write and rename leaves an orphan; it is never a
        // valid job, so clean it up rather than let it accumulate.
        pipelineDirectory.listFiles { file -> file.extension == "tmp" }?.forEach { it.delete() }

        val files = pipelineDirectory.listFiles { file -> file.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val filename = json.optString(FIELD_FILENAME)
                val stage = PipelineStage.entries.firstOrNull { it.name == json.optString(FIELD_STAGE) }
                if (filename.isBlank() || stage == null) {
                    Log.w(TAG, "Discarding malformed pipeline job ${file.name}")
                    file.delete()
                    null
                } else {
                    PipelineJob(filename, stage)
                }
            } catch (exception: JSONException) {
                Log.w(TAG, "Discarding malformed pipeline job ${file.name}", exception)
                file.delete()
                null
            }
        }
    }

    private fun refreshPendingFilenamesLocked() {
        _pendingFilenames.value = readJobsLocked().map { it.filename }.toSet()
    }

    private fun nextDueMillis(state: BackoffState): Long = when {
        state.dueNowRequested -> 0L
        state.keyMissing -> state.lastAttemptMillis + MISSING_KEY_RECHECK_MILLIS
        state.attempts == 0 && state.lastAttemptMillis == 0L -> 0L
        else -> state.lastAttemptMillis + PipelinePolicy.backoffDelayMillis(state.attempts)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true

        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            isOnline = true
            return
        }
        isOnline = computeOnline(manager)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
                wake()
            }

            override fun onLost(network: Network) {
                // Another network may already be the active one.
                isOnline = computeOnline(manager)
            }
        }

        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (exception: Exception) {
            // Without a callback the queue must not park on a stale offline
            // flag, so fail open and let the request itself decide.
            Log.w(TAG, "Network callback registration failed; assuming online", exception)
            isOnline = true
        }
    }

    private fun computeOnline(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        // INTERNET only: a captive portal or VPN can leave the network
        // unvalidated while requests still work, and the gate is advisory.
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun wake() {
        wakeSignal.trySend(Unit)
    }

    private fun jobFile(filename: String) = File(pipelineDirectory, "$filename.json")

    private fun recordingFile(filename: String) = File(recordingsDirectory, filename).takeIf { it.exists() }

    private fun transcriptFile(filename: String) =
        File(recordingsDirectory, filename.substringBeforeLast('.') + ".txt")

    private fun selectedApiKey(provider: String): String = when (provider) {
        Settings.TRANSCRIPTION_PROVIDER_OPENAI -> settings.openAiApiKey.trim()
        Settings.TRANSCRIPTION_PROVIDER_ELEVENLABS -> settings.elevenLabsApiKey.trim()
        else -> ""
    }

    private fun providerDisplayName(provider: String): String = when (provider) {
        Settings.TRANSCRIPTION_PROVIDER_OPENAI -> "OpenAI"
        Settings.TRANSCRIPTION_PROVIDER_ELEVENLABS -> "ElevenLabs"
        else -> provider
    }

    private fun describe(result: TranscriptionResult): String = when (result) {
        is TranscriptionResult.Success -> "success"
        is TranscriptionResult.HttpError -> "HTTP ${result.code}: ${result.body.take(200)}"
        is TranscriptionResult.NetworkError ->
            "${result.exception::class.simpleName}: ${result.exception.message}"
        is TranscriptionResult.ParseError -> result.message
    }

    private class BackoffState(
        var attempts: Int = 0,
        var lastAttemptMillis: Long = 0L,
        var dueNowRequested: Boolean = false,
        // Set when the worker first parks the job because the gate says the
        // device is offline; cleared on any real attempt. Bounds how long a
        // wrong offline signal can hold a job.
        var offlineSinceMillis: Long = 0L,
        // Set while the provider API key is missing, so the wait uses
        // MISSING_KEY_RECHECK_MILLIS instead of the attempt backoff.
        var keyMissing: Boolean = false,
    )

    private data class PipelineJob(val filename: String, val stage: PipelineStage)

    private enum class AttemptResult { SUCCESS, TRANSIENT, AUTH, BAD_FILE, MISSING_KEY, DROPPED }

    private companion object {
        const val FIELD_FILENAME = "filename"
        const val FIELD_STAGE = "stage"

        // A missing key is not an attempt, so it must not use the attempt
        // backoff: checking every couple of seconds would poll settings and the
        // job directory for as long as the user takes to enter one.
        const val MISSING_KEY_RECHECK_MILLIS = 60_000L
    }
}
