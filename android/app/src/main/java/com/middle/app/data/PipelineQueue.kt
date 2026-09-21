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
import org.json.JSONArray
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
     * already exists and an action matches a webhook, and does nothing when the
     * transcript exists with no webhook to deliver.
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
            // A WEBHOOK job already holds the ids matched at transcription time.
            // Re-planning would recompute them from the transcript and could
            // lose an id the user just re-enabled, so an existing job keeps its
            // persisted ids and is only made due now.
            val hasWebhookJob = readJobsLocked().any {
                it.filename == filename && it.stage == PipelineStage.WEBHOOK
            }
            if (hasWebhookJob || ensureJobLocked(filename)) {
                resetBackoffLocked(filename)
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
        // A crash between saving the transcript and advancing the job leaves a
        // TRANSCRIBE job whose transcript is already on disk. Re-running the API
        // would spend a request and re-run local actions, so advance by pattern
        // alone exactly as a manual retry would.
        if (transcriptFile(job.filename).exists()) {
            Log.i(TAG, "[action] transcript already present, skipping local actions")
            advanceByRegexOnly(job.filename)
            return AttemptResult.SUCCESS
        }

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
        // The action phase runs on the worker's IO dispatcher, where
        // transcription already runs, and ActionRunner blocks on the network
        // and the calendar provider.
        val transcript = transcriptFile(filename)
        val transcriptText = if (transcript.exists()) transcript.readText() else ""
        val actions = settings.actions

        val webhookActionIds = try {
            runActions(transcriptText, actions)
        } catch (exception: Exception) {
            // A runner failure (for example a SecurityException from the clock
            // app) must not lose webhook delivery, so fall back to matching the
            // WEBHOOK actions by pattern alone.
            Log.e(TAG, "[action] action handling failed for $filename; collecting webhooks by pattern", exception)
            collectWebhookIdsByRegex(transcriptText, actions)
        }

        synchronized(lock) {
            if (webhookActionIds.isNotEmpty()) {
                writeJobLocked(filename, PipelineStage.WEBHOOK, webhookActionIds)
            } else {
                deleteJobLocked(filename)
            }
            refreshPendingFilenamesLocked()
        }
    }

    /**
     * Runs the ordered plan against [transcript] and returns the WEBHOOK action
     * ids to deliver, in order. ALARM/CALENDAR/FAKE_CALL hits go through
     * [ActionRunner]; when such a `stop` hit produces nothing, evaluation
     * resumes after it, because the matcher applies `stop` optimistically.
     */
    private fun runActions(transcript: String, actions: List<Action>): List<String> {
        val runner = ActionRunner(appContext)
        val webhookActionIds = mutableListOf<String>()
        var startIndex = 0

        while (startIndex < actions.size) {
            val plan = ActionMatcher.planFrom(transcript, actions, startIndex)
            logInvalidActions(plan)
            if (plan.hits.isEmpty()) break

            var resumeAt = -1
            for (hit in plan.hits) {
                when (hit.action.type) {
                    ActionType.WEBHOOK -> webhookActionIds.add(hit.action.id)
                    ActionType.ALARM, ActionType.CALENDAR, ActionType.FAKE_CALL -> {
                        // A runner crash on one hit must not abort the whole
                        // plan: treat it as producing nothing so the ids already
                        // collected still get delivered and later hits run. The
                        // outer catch in advanceAfterTranscription remains the
                        // last resort for anything outside a single hit.
                        val produced = try {
                            runner.run(hit, transcript)
                        } catch (exception: Exception) {
                            Log.e(TAG, "[action] action ${hit.action.id} failed; continuing", exception)
                            false
                        }
                        if (!produced && hit.action.stop) {
                            // Resume from the hit's position in the list, not
                            // its id: ids are not guaranteed unique (imported
                            // backups), so an id lookup could land on an earlier
                            // duplicate and never advance.
                            resumeAt = hit.index + 1
                            break
                        }
                    }
                }
            }
            // Belt and braces: a plan whose hits do not move the cursor
            // forward would loop forever, so stop instead.
            if (resumeAt <= startIndex) break
            startIndex = resumeAt
        }
        return webhookActionIds
    }

    /**
     * Collects the WEBHOOK ids a regex-only plan would deliver. Used when the
     * runner must not run: a manual retry, or a crash in the action phase. The
     * matcher already stops at the first `stop` hit, so a blocking
     * ALARM/CALENDAR/FAKE_CALL still hides the webhooks after it.
     */
    private fun collectWebhookIdsByRegex(transcript: String, actions: List<Action>): List<String> {
        val plan = ActionMatcher.plan(transcript, actions)
        logInvalidActions(plan)
        return plan.hits.filter { it.action.type == ActionType.WEBHOOK }.map { it.action.id }
    }

    private fun attemptWebhook(job: PipelineJob) {
        if (job.webhookActionIds.isEmpty()) {
            // A job queued before webhooks moved into actions has no ids left to
            // deliver, so it is dropped rather than retried forever.
            Log.d(TAG, "Dropping webhook job for ${job.filename}: no pending action ids")
            deleteJob(job.filename)
            return
        }

        val transcriptFile = transcriptFile(job.filename)
        if (!transcriptFile.exists()) {
            Log.w(TAG, "No transcript for ${job.filename}; dropping webhook job")
            deleteJob(job.filename)
            return
        }

        val transcript = transcriptFile.readText()
        val actions = settings.actions
        val remaining = job.webhookActionIds.toMutableList()

        while (remaining.isNotEmpty()) {
            // The job is deleted when its recording is deleted. A request that
            // is already in flight can finish after that, so stop rather than
            // recreate the job (and re-queue work for a gone recording).
            if (!jobStillExists(job.filename)) {
                Log.d(TAG, "Stopping webhook delivery for ${job.filename}: job was removed")
                return
            }

            val id = remaining.first()
            val action = actions.find { it.id == id && it.type == ActionType.WEBHOOK && it.enabled }
            if (action == null || action.webhookUrl.isBlank()) {
                // The action was deleted or disabled since the job was queued,
                // or has nowhere to go: drop it instead of retrying forever.
                WebhookLog.info("Webhook action $id is gone; skipping for ${job.filename}")
                remaining.removeAt(0)
                if (!persistWebhookIds(job.filename, remaining)) return
                continue
            }

            val rest = ActionMatcher.restFor(action.pattern, transcript) ?: ""
            val template = action.webhookBodyTemplate.ifBlank { Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE }
            val result = try {
                WebhookClient.post(action.webhookUrl, transcript, rest, template)
            } catch (exception: Exception) {
                WebhookLog.error("Webhook error for ${job.filename} ($id): ${exception::class.simpleName}: ${exception.message}")
                bumpAttempt(job.filename)
                return
            }

            when (PipelinePolicy.classifyWebhook(result.success, result.code)) {
                WebhookOutcome.SUCCESS -> {
                    WebhookLog.info("Webhook sent for ${job.filename} ($id, ${result.code})")
                    remaining.removeAt(0)
                    if (!persistWebhookIds(job.filename, remaining)) return
                }
                WebhookOutcome.DROP -> {
                    WebhookLog.error("Webhook abandoned for ${job.filename} ($id): ${result.code} ${result.message}")
                    Log.w(TAG, "Webhook abandoned for ${job.filename} ($id): ${result.code}")
                    remaining.removeAt(0)
                    if (!persistWebhookIds(job.filename, remaining)) return
                }
                WebhookOutcome.RETRY -> {
                    WebhookLog.error("Webhook failed for ${job.filename} ($id): ${result.code} ${result.message}")
                    // Stop this pass: the id stays first in the persisted job so
                    // it is retried before the ones behind it.
                    bumpAttempt(job.filename)
                    return
                }
            }
        }
    }

    /**
     * Persists the still-pending ids, deleting the job when none remain.
     * Returns false without writing when the job was removed (the recording was
     * deleted) while the request was in flight, so it is not recreated.
     */
    private fun persistWebhookIds(filename: String, ids: List<String>): Boolean {
        synchronized(lock) {
            if (!jobFile(filename).exists()) return false
            if (ids.isEmpty()) {
                deleteJobLocked(filename)
            } else {
                writeJobLocked(filename, PipelineStage.WEBHOOK, ids)
            }
            refreshPendingFilenamesLocked()
        }
        return true
    }

    private fun jobStillExists(filename: String): Boolean =
        synchronized(lock) { jobFile(filename).exists() }

    private fun ensureJobLocked(filename: String): Boolean {
        val transcript = transcriptFile(filename)
        if (!transcript.exists()) {
            writeJobLocked(filename, PipelineStage.TRANSCRIBE)
            return true
        }
        // A manual retry never runs the alarms again, so it plans by pattern
        // only; the runner is left for a successful transcription.
        val webhookActionIds = collectWebhookIdsByRegex(transcript.readText(), settings.actions)
        return if (webhookActionIds.isNotEmpty()) {
            writeJobLocked(filename, PipelineStage.WEBHOOK, webhookActionIds)
            true
        } else {
            deleteJobLocked(filename)
            false
        }
    }

    /**
     * Advances [filename] to a WEBHOOK job with the ids a regex-only plan
     * matches, or deletes the job when none match. The local actions do not run.
     */
    private fun advanceByRegexOnly(filename: String) {
        synchronized(lock) {
            // The caller saw the recording, but it can be deleted while the
            // transcription is in flight; do not recreate a job for a recording
            // that is gone.
            if (!jobFile(filename).exists()) return
            ensureJobLocked(filename)
            refreshPendingFilenamesLocked()
        }
    }

    private fun logInvalidActions(plan: MatchPlan) {
        for (action in plan.invalid) {
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

    /** Clears the retry history and makes the job due immediately. */
    private fun resetBackoffLocked(filename: String) {
        backoffByFilename.remove(filename)
        backoffByFilename.getOrPut(filename) { BackoffState() }.dueNowRequested = true
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

    private fun writeJobLocked(
        filename: String,
        stage: PipelineStage,
        webhookActionIds: List<String> = emptyList(),
    ) {
        val json = JSONObject().apply {
            put(FIELD_FILENAME, filename)
            put(FIELD_STAGE, stage.name)
            // Optional: only WEBHOOK jobs with pending actions carry it.
            if (webhookActionIds.isNotEmpty()) {
                put(FIELD_WEBHOOK_ACTION_IDS, JSONArray(webhookActionIds))
            }
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
                    // Absent or malformed entries read as no pending ids, which
                    // a WEBHOOK job then drops on its next pass.
                    val webhookActionIds = json.optJSONArray(FIELD_WEBHOOK_ACTION_IDS)
                        ?.let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                array.optString(index).takeIf { it.isNotEmpty() }
                            }
                        }
                        ?: emptyList()
                    PipelineJob(filename, stage, webhookActionIds)
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

    private data class PipelineJob(
        val filename: String,
        val stage: PipelineStage,
        val webhookActionIds: List<String> = emptyList(),
    )

    private enum class AttemptResult { SUCCESS, TRANSIENT, AUTH, BAD_FILE, MISSING_KEY, DROPPED }

    private companion object {
        const val FIELD_FILENAME = "filename"
        const val FIELD_STAGE = "stage"
        const val FIELD_WEBHOOK_ACTION_IDS = "webhookActionIds"

        // A missing key is not an attempt, so it must not use the attempt
        // backoff: checking every couple of seconds would poll settings and the
        // job directory for as long as the user takes to enter one.
        const val MISSING_KEY_RECHECK_MILLIS = 60_000L
    }
}
