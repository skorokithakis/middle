package com.middle.app.viewmodel

import android.app.Application
import android.media.MediaPlayer
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.middle.app.MiddleApplication
import com.middle.app.data.ActionType
import com.middle.app.data.Recording
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    val repository = (application as MiddleApplication).repository
    private val settings = Settings(application)
    private val pipelineQueue = (application as MiddleApplication).pipelineQueue

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

    val webhookEnabled: Boolean
        get() = settings.actions.any {
            it.enabled && it.type == ActionType.WEBHOOK && it.webhookUrl.trim().isNotEmpty()
        }

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

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
