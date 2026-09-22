package com.middle.app.viewmodel

import android.app.Application
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.middle.app.MiddleApplication
import com.middle.app.audio.PhoneRecorder
import com.middle.app.data.Recording
import com.middle.app.data.RecordingSaver
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    val repository = (application as MiddleApplication).repository
    private val settings = Settings(application)
    private val pipelineQueue = (application as MiddleApplication).pipelineQueue
    private val recordingSaver = RecordingSaver(repository, settings, pipelineQueue)
    private val phoneRecorder = PhoneRecorder()

    sealed interface RecordingState {
        data object Idle : RecordingState
        data class Recording(val elapsedSeconds: Int) : RecordingState
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState
    private var recordingTimerJob: Job? = null

    // One-shot save failures for the screen's Snackbar. A channel keeps the
    // message from reappearing after it has been shown once.
    private val saveErrorChannel = Channel<String>(Channel.BUFFERED)
    val saveErrors: Flow<String> = saveErrorChannel.receiveAsFlow()

    val recordings: StateFlow<List<Recording>> = repository.recordings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentlyPlaying = MutableStateFlow<Recording?>(null)
    val currentlyPlaying: StateFlow<Recording?> = _currentlyPlaying

    private var mediaPlayer: MediaPlayer? = null

    fun togglePlayback(recording: Recording) {
        if (_currentlyPlaying.value == recording) {
            stopPlayback()
        } else {
            stopPlayback()
            playRecording(recording)
        }
    }

    private fun playRecording(recording: Recording) {
        mediaPlayer = MediaPlayer().apply {
            setDataSource(recording.audioFile.absolutePath)
            setOnCompletionListener { stopPlayback() }
            prepare()
            start()
        }
        _currentlyPlaying.value = recording
    }

    fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        _currentlyPlaying.value = null
    }

    val anyActionEnabled: Boolean
        get() = settings.actions.any { it.enabled }

    val pendingFilenames: StateFlow<Set<String>>
        get() = pipelineQueue.pendingFilenames

    fun retryPipeline(recording: Recording) {
        pipelineQueue.retryNow(recording.audioFile.name)
        viewModelScope.launch { showToast("Queued") }
    }

    fun deleteRecording(recording: Recording) {
        if (_currentlyPlaying.value == recording) {
            stopPlayback()
        }
        viewModelScope.launch(Dispatchers.IO) {
            pipelineQueue.removeForRecording(recording.audioFile.name)
            repository.deleteRecording(recording)
        }
    }

    fun deleteAllRecordings() {
        stopPlayback()
        viewModelScope.launch(Dispatchers.IO) {
            pipelineQueue.removeAll()
            repository.deleteAllRecordings()
        }
    }

    fun refresh() {
        repository.refresh()
    }

    /**
     * Starts capturing from the phone microphone. The timer also enforces the
     * five-minute cap, so the state returns to idle when it is reached even
     * though the user is still holding the button.
     */
    fun startRecording() {
        if (_recordingState.value is RecordingState.Recording) return
        if (!phoneRecorder.start(viewModelScope)) {
            Log.w(TAG, "Could not start phone recording.")
            return
        }
        _recordingState.value = RecordingState.Recording(0)
        recordingTimerJob = viewModelScope.launch {
            var elapsedSeconds = 0
            while (isActive && elapsedSeconds < MAX_RECORDING_SECONDS) {
                delay(1_000)
                elapsedSeconds++
                _recordingState.value = RecordingState.Recording(elapsedSeconds)
            }
            stopRecording()
        }
    }

    /**
     * Stops capture and saves it through the recorder pipeline. Idempotent.
     *
     * [PhoneRecorder.stop] detaches synchronously, so a new [startRecording]
     * can begin immediately; only encoding and saving run off the main thread.
     */
    fun stopRecording() {
        if (_recordingState.value !is RecordingState.Recording) return
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _recordingState.value = RecordingState.Idle
        val pcm16 = phoneRecorder.stop()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                recordingSaver.save(pcm16, PhoneRecorder.SAMPLE_RATE)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Could not save recording.", exception)
                saveErrorChannel.send(SAVE_ERROR_MESSAGE)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        phoneRecorder.release()
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "RecordingsViewModel"
        private const val SAVE_ERROR_MESSAGE = "Could not save recording"

        // Matches PhoneRecorder's own hard cap; the timer stops the UI state at
        // the same moment.
        private const val MAX_RECORDING_SECONDS = 5 * 60
    }
}
