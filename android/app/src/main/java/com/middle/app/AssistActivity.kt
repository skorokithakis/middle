package com.middle.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.middle.app.R
import com.middle.app.audio.CaptureEndReason
import com.middle.app.audio.PhoneRecorder
import com.middle.app.audio.SileroClassifier
import com.middle.app.audio.SpeechEndpointer
import com.middle.app.data.RecordingSaver
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import com.middle.app.ui.theme.MiddleTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the assistant card is showing. */
private sealed interface AssistState {
    data class Listening(val elapsedSeconds: Int) : AssistState
    data object Transcribing : AssistState
    data class Transcript(val text: String) : AssistState
    data class Done(val message: String) : AssistState
}

/**
 * The system assistant target (long-press power). It behaves like holding the
 * in-app mic button: record, stop automatically when the speaker stops through
 * the Silero [SpeechEndpointer], then save through [RecordingSaver].
 *
 * After the save the card stays open: it shows the transcript once the pipeline
 * has written it (observed through [RecordingsRepository.recordings]), then
 * closes 3 s later, or immediately when the user taps outside. The activity owns
 * capture because it runs in the foreground, so mic access needs no foreground
 * service. It is kept to wiring only.
 */
class AssistActivity : ComponentActivity() {

    private val applicationScope
        get() = (application as MiddleApplication).applicationScope

    private lateinit var phoneRecorder: PhoneRecorder
    private lateinit var recordingSaver: RecordingSaver
    private lateinit var repository: RecordingsRepository
    private lateinit var settings: Settings

    private val state = MutableStateFlow<AssistState>(AssistState.Listening(0))
    private var timerJob: Job? = null
    private var transcriptJob: Job? = null
    private var autoFinishJob: Job? = null
    private var captureStarted = false

    // The endpointer reports from the IO read loop and onStop can also end
    // capture, so whichever arrives first wins.
    private val ended = AtomicBoolean(false)

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCapture()
        } else {
            toast("Microphone permission needed")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tap anywhere outside the card dismisses the popup in any state. The
        // card wraps its content, so the window stays card-sized; without that
        // the full-screen window would swallow the outside touch.
        setFinishOnTouchOutside(true)
        window.setGravity(Gravity.CENTER)

        val app = application as MiddleApplication
        repository = app.repository
        settings = Settings(application)
        phoneRecorder = PhoneRecorder()
        recordingSaver = RecordingSaver(repository, settings, app.pipelineQueue)

        setContent {
            MiddleTheme {
                val current by state.collectAsState()
                AssistScreen(
                    state = current,
                    onStop = { endCapture(save = true) },
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCapture()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCapture() {
        // Loading the Silero model can fail (bad asset, missing native lib), so
        // do not start a capture we cannot classify.
        val endpointer = try {
            SpeechEndpointer(SileroClassifier(applicationContext))
        } catch (exception: Exception) {
            Log.e(TAG, "Could not create the speech endpointer.", exception)
            toast(START_ERROR_MESSAGE)
            finish()
            return
        }
        val started = phoneRecorder.start(
            scope = applicationScope,
            endpointer = endpointer,
            onCaptureEnded = ::onCaptureEnded,
        )
        if (!started) {
            Log.w(TAG, "Could not start phone recording.")
            // The recorder never took ownership of the classifier.
            endpointer.close()
            toast(START_ERROR_MESSAGE)
            finish()
            return
        }
        captureStarted = true
        timerJob = lifecycleScope.launch {
            var seconds = 0
            while (isActive) {
                delay(1_000)
                seconds++
                state.value = AssistState.Listening(seconds)
            }
        }
    }

    private fun onCaptureEnded(reason: CaptureEndReason) {
        runOnUiThread {
            endCapture(save = reason != CaptureEndReason.NO_SPEECH)
        }
    }

    /**
     * Ends capture once. [save] keeps the audio; otherwise it is dropped.
     * Saving and enqueueing transcription run on the application scope so
     * leaving the card cannot cut them short.
     */
    private fun endCapture(save: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        timerJob?.cancel()
        timerJob = null

        if (!save) {
            phoneRecorder.release()
            showDone(NO_SPEECH_MESSAGE)
            return
        }

        state.value = AssistState.Transcribing
        val pcm16 = phoneRecorder.stop()
        val transcriptionEnabled = settings.transcriptionEnabled

        // Only the save runs on the application scope, and it captures the
        // saver locally so the job does not hold the activity alive. The waiter
        // lives on lifecycleScope, so destroying the activity cancels the wait
        // but never the save.
        val saver = recordingSaver
        val saved = applicationScope.async(Dispatchers.IO) {
            saver.save(pcm16, PhoneRecorder.SAMPLE_RATE)
        }
        lifecycleScope.launch {
            val file = try {
                saved.await()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save recording.", exception)
                showDone(SAVE_ERROR_MESSAGE)
                return@launch
            }

            when {
                file == null -> showDone(NO_SPEECH_MESSAGE)
                !transcriptionEnabled -> showDone(SAVED_MESSAGE)
                else -> awaitTranscript(file)
            }
        }
    }

    /**
     * Collects [RecordingsRepository.recordings] until the saved file gains a
     * transcript, then shows it. Gives up after [TRANSCRIPT_TIMEOUT_MILLIS].
     */
    private fun awaitTranscript(file: File) {
        if (!isStarted()) return
        transcriptJob?.cancel()
        transcriptJob = lifecycleScope.launch {
            val text = withTimeoutOrNull(TRANSCRIPT_TIMEOUT_MILLIS) {
                repository.recordings
                    .mapNotNull { list -> list.firstOrNull { it.audioFile.name == file.name } }
                    .first { it.hasTranscript }
                    .transcriptText
                    .orEmpty()
            }
            if (text != null) showTranscript(text) else showDone(PENDING_MESSAGE)
        }
    }

    private fun showTranscript(text: String) {
        if (!isStarted()) return
        state.value = AssistState.Transcript(text)
        scheduleFinish()
    }

    private fun showDone(message: String) {
        if (!isStarted()) return
        state.value = AssistState.Done(message)
        scheduleFinish()
    }

    // The card closes a few seconds after it reaches a terminal state.
    private fun scheduleFinish() {
        autoFinishJob?.cancel()
        autoFinishJob = lifecycleScope.launch {
            delay(FINISH_DELAY_MILLIS)
            finish()
        }
    }

    private fun isStarted(): Boolean =
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun onStop() {
        super.onStop()
        // Leaving while still recording stops and saves; the pipeline then
        // transcribes in the background. In any later state the work is already
        // underway, so just finish without waiting for a transcript. Waiting on
        // the permission prompt is neither, and must not finish so the result
        // can still be delivered.
        if (captureStarted && state.value is AssistState.Listening) {
            endCapture(save = true)
        }
        if (ended.get()) finish()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AssistActivity"
        private const val SAVED_MESSAGE = "Saved"
        private const val PENDING_MESSAGE = "Saved, transcription pending"
        private const val NO_SPEECH_MESSAGE = "No speech detected"
        private const val SAVE_ERROR_MESSAGE = "Could not save recording"
        private const val START_ERROR_MESSAGE = "Could not start recording"

        private const val FINISH_DELAY_MILLIS = 3_000L
        private const val TRANSCRIPT_TIMEOUT_MILLIS = 30_000L
    }
}

@Composable
private fun AssistScreen(
    state: AssistState,
    onStop: () -> Unit,
) {
    // No fillMaxSize: the window wraps this card, which is what lets a tap
    // outside the card reach the window and dismiss it. The icon straddles the
    // top edge, so the card is pushed down by half the icon to keep it in view.
    val showIcon = state !is AssistState.Listening
    Box(
        modifier = Modifier.padding(8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .padding(top = if (showIcon) ICON_SIZE / 2 else 0.dp)
                .widthIn(min = 280.dp, max = 360.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(top = if (showIcon) ICON_SIZE / 2 else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    is AssistState.Listening -> {
                        Text("Listening…", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = formatElapsed(state.elapsedSeconds),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onStop) {
                            Text("Stop")
                        }
                    }
                    AssistState.Transcribing -> Text(
                        text = "Transcribing…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    is AssistState.Transcript -> {
                        Text("Transcript", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .heightIn(max = TRANSCRIPT_MAX_HEIGHT)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    is AssistState.Done -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        if (showIcon) {
            MiddleIcon()
        }
    }
}

@Composable
private fun MiddleIcon() {
    Box(
        modifier = Modifier
            .size(ICON_SIZE)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_background))
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun formatElapsed(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}

// Keeps a long transcript from growing the card beyond the screen.
private val TRANSCRIPT_MAX_HEIGHT = 280.dp

private val ICON_SIZE = 64.dp
