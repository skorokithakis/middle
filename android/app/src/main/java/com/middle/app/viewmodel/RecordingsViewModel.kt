package com.middle.app.viewmodel

import android.app.Application
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.middle.app.MiddleApplication
import com.middle.app.data.Recording
import com.middle.app.data.RecordingsRepository
import com.middle.app.data.Settings
import com.middle.app.data.WebhookClient
import com.middle.app.data.WebhookLog
import com.middle.app.data.WebhookRetryQueue
import com.middle.app.transcription.TranscriptionClient
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
    private val retryQueue = WebhookRetryQueue(application, viewModelScope)

    init {
        retryQueue.startRetryLoopIfNeeded()
    }

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
        get() = settings.webhookEnabled && settings.webhookUrl.trim().isNotEmpty()

    val transcriptionAvailable: Boolean
        get() = settings.transcriptionEnabled && settings.openAiApiKey.trim().isNotEmpty()

    fun sendWebhook(recording: Recording) {
        val webhookUrl = settings.webhookUrl.trim()
        if (!settings.webhookEnabled || webhookUrl.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val existingTranscript = recording.transcriptText
            val transcript: String
            if (existingTranscript == null) {
                val apiKey = settings.openAiApiKey.trim()
                val transcribed = TranscriptionClient(apiKey).transcribe(recording.audioFile)
                if (transcribed == null) {
                    Log.w(TAG, "Transcription failed for ${recording.audioFile.name}, skipping webhook.")
                    showToast("Transcription failed")
                    return@launch
                }
                repository.saveTranscript(transcribed, recording.audioFile)
                transcript = transcribed
            } else {
                transcript = existingTranscript
            }

            val template = settings.webhookBodyTemplate.ifBlank {
                Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE
            }
            val filename = recording.audioFile.name
            WebhookLog.info("POST $webhookUrl ($filename)")
            try {
                val result = WebhookClient.post(webhookUrl, transcript, template)
                if (result.success) {
                    Log.d(TAG, "Webhook resend succeeded for $filename.")
                    WebhookLog.info("${result.code} OK ($filename)")
                    showToast("Webhook sent")
                } else {
                    Log.w(TAG, "Webhook resend failed with status ${result.code} for $filename.")
                    WebhookLog.error("${result.code} ${result.message} ($filename): ${result.body}")
                    showToast("Webhook failed (${result.code})")
                    if (result.code !in 400..499) {
                        retryQueue.enqueue(transcript, webhookUrl, template, filename)
                    }
                }
            } catch (exception: Exception) {
                Log.w(TAG, "Webhook resend error for $filename: $exception")
                WebhookLog.error("$filename: ${exception::class.simpleName}: ${exception.message}")
                showToast("Webhook failed: ${exception.message}")
                retryQueue.enqueue(transcript, webhookUrl, template, filename)
            }
        }
    }

    fun deleteRecording(recording: Recording) {
        if (_currentlyPlaying.value == recording) {
            stopPlayback()
        }
        viewModelScope.launch(Dispatchers.IO) {
            retryQueue.removeForRecording(recording.audioFile.name)
            repository.deleteRecording(recording)
        }
    }

    fun deleteAllRecordings() {
        stopPlayback()
        val currentRecordings = recordings.value
        viewModelScope.launch(Dispatchers.IO) {
            currentRecordings.forEach { retryQueue.removeForRecording(it.audioFile.name) }
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

    companion object {
        private const val TAG = "RecordingsViewModel"
    }
}
