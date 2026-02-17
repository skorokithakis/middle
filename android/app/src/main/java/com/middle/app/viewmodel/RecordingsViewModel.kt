package com.middle.app.viewmodel

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.middle.app.MiddleApplication
import com.middle.app.data.Recording
import com.middle.app.data.RecordingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    val repository = (application as MiddleApplication).repository

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

    fun refresh() {
        repository.refresh()
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
