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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    val repository = (application as MiddleApplication).repository
    private val settings = Settings(application)

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

    fun sendWebhook(recording: Recording) {
        val transcript = recording.transcriptText ?: return
        val webhookUrl = settings.webhookUrl.trim()
        if (!settings.webhookEnabled || webhookUrl.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val jsonEscapedText = JSONObject.quote(transcript)
                .removeSurrounding("\"")
            val template = settings.webhookBodyTemplate.ifBlank {
                Settings.DEFAULT_WEBHOOK_BODY_TEMPLATE
            }
            val json = template.replace("\$transcript", jsonEscapedText)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Webhook resend succeeded for ${recording.audioFile.name}.")
                        showToast("Webhook sent")
                    } else {
                        Log.w(TAG, "Webhook resend failed with status ${response.code} for ${recording.audioFile.name}.")
                        showToast("Webhook failed (${response.code})")
                    }
                }
            } catch (exception: Exception) {
                Log.w(TAG, "Webhook resend error for ${recording.audioFile.name}: $exception")
                showToast("Webhook failed")
            }
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
