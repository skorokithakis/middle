package com.middle.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.middle.app.audio.CaptureEndReason
import com.middle.app.audio.PhoneRecorder
import com.middle.app.audio.SileroClassifier
import com.middle.app.audio.SpeechEndpointer
import com.middle.app.data.RecordingSaver
import com.middle.app.data.Settings
import com.middle.app.ui.theme.MiddleTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The system assistant target (long-press power). It behaves like holding the
 * in-app mic button: record, stop automatically when the speaker stops through
 * the Silero [SpeechEndpointer], then save through [RecordingSaver].
 *
 * The activity owns capture because it runs in the foreground, so mic access
 * needs no foreground service. It is kept to wiring only.
 */
class AssistActivity : ComponentActivity() {

    private val applicationScope
        get() = (application as MiddleApplication).applicationScope

    private lateinit var phoneRecorder: PhoneRecorder
    private lateinit var recordingSaver: RecordingSaver

    private val elapsedSeconds = MutableStateFlow(0)
    private var timerJob: Job? = null
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

        val app = application as MiddleApplication
        phoneRecorder = PhoneRecorder()
        recordingSaver = RecordingSaver(app.repository, Settings(application), app.pipelineQueue)

        setContent {
            MiddleTheme {
                val elapsed by elapsedSeconds.collectAsState()
                AssistScreen(
                    elapsedSeconds = elapsed,
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
            toast("Could not start recording")
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
            toast("Could not start recording")
            finish()
            return
        }
        captureStarted = true
        timerJob = lifecycleScope.launch {
            var seconds = 0
            while (isActive) {
                delay(1_000)
                seconds++
                elapsedSeconds.value = seconds
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
     * Saving runs on the application scope so [finish] cannot cut it short.
     */
    private fun endCapture(save: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        timerJob?.cancel()
        timerJob = null

        if (!save) {
            phoneRecorder.release()
            toast(NO_SPEECH_MESSAGE)
            finish()
            return
        }

        val pcm16 = phoneRecorder.stop()
        applicationScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    recordingSaver.save(pcm16, PhoneRecorder.SAMPLE_RATE)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save recording.", exception)
                toast(SAVE_ERROR_MESSAGE)
                return@launch
            }
            toast(if (file != null) SAVED_MESSAGE else NO_SPEECH_MESSAGE)
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the activity ends capture and saves, exactly like releasing
        // the record button.
        if (captureStarted) endCapture(save = true)
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AssistActivity"
        private const val SAVED_MESSAGE = "Saved"
        private const val NO_SPEECH_MESSAGE = "No speech detected"
        private const val SAVE_ERROR_MESSAGE = "Could not save recording"
    }
}

@Composable
private fun AssistScreen(
    elapsedSeconds: Int,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Listening…", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}
